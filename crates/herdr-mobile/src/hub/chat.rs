//! The chat view of the agent in a terminal (issue 17): finds its log with the
//! lookup script, tails it by byte offset and parses it with herdr-chat, all
//! through `terminal.get` and `machine.exec`; sends with `terminal.send`.
//! What to look up and read is decided by [`ChatCore`]; this file makes the
//! calls. The screen polls after each event for the terminal
//! ([`Chat::wait_change`]) and on a timer between them, only while the chat
//! or the terminal view needs it (design D5).

use super::HubCore;
use super::chat_core::{ChatCore, Kind, Read, Row, Term};
use base64::Engine;
use herdr_chat::locate::{self, Lookup};
use herdr_chat::send::{self, Refusal, Seen};
use herdr_chat::{Agent, Health, Image, Item, image, tail};
use herdr_proto::rpc::{Client, Stream, StreamItem};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Fetched images kept for scrolling back and forth; dropped all at once past this.
const IMAGE_CACHE: usize = 48_000_000;

/// An image in the conversation, fetched with [`Chat::image`] when shown.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatImage {
    /// `log:<session>:<line>:<n>`, `file:<path>`, `inline:<base64>` or `missing`.
    pub key: String,
    pub media_type: String,
}

/// An image the person attaches from the phone.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatUpload {
    pub name: String,
    pub data: Vec<u8>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatCall {
    pub name: String,
    pub title: String,
    pub input: String,
    pub output: String,
    pub done: bool,
    pub failed: bool,
    pub images: Vec<ChatImage>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatRow {
    /// The row's identity (design D3): kept while the log grows and is read
    /// back, so the screen keys its list by it.
    pub key: String,
    /// "user", "said", "tools", "note" or "agent".
    pub kind: String,
    pub text: String,
    /// For "agent": who sent it (a subagent's task, or a session's name).
    pub from: String,
    pub images: Vec<ChatImage>,
    pub calls: Vec<ChatCall>,
}

/// The lookup result (spec: the lookup's four results).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ChatFound {
    Found,
    Empty,
    Missed,
    None,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ChatHealth {
    Ok,
    /// Readable, with parts left out.
    Degraded,
    /// Not readable any more: keep what was read, show the terminal button.
    Broken { reason: String },
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatPoll {
    pub found: ChatFound,
    /// "claude" or "codex"; empty when no log is held.
    pub agent: String,
    pub title: String,
    pub version: String,
    /// The terminal's status: "working", "idle", "done", "blocked", ...
    /// (empty when not looked at).
    pub status: String,
    pub health: ChatHealth,
    /// A read that failed, shown over the chat until one succeeds.
    pub problem: String,
    /// The agent whose log is shown has exited: read-only.
    pub exited: bool,
    /// The agent shown is in front and may take a message now.
    pub can_send: bool,
    /// Older rows exist in the log: [`Chat::older`] reads them.
    pub cut: bool,
    pub rows: Vec<ChatRow>,
    /// The context gauge (design D8): tokens in use, and the window when the
    /// log names it (Codex).
    pub gauge_used: Option<u64>,
    pub gauge_window: Option<u64>,
    pub gauge_percent: Option<u32>,
    /// Claude Code: the texts it holds queued, not taken in yet (design
    /// D10): a pending message among them reads "Queued".
    pub queued: Vec<String>,
    /// Milliseconds per stage, for the log: "get 40 locate 50 tail 61 total 151".
    pub timings: String,
}

/// Why a send typed nothing; the draft stays.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ChatRefusal {
    /// "The agent is waiting for you in the terminal" (blocked, or no status).
    Waiting,
    /// "The agent has exited".
    Exited,
    /// "This is a different session now".
    Changed,
    /// Not connected, a failed upload or call: the reason, for the chat.
    Failed { reason: String },
}

#[derive(uniffi::Object)]
pub struct Chat {
    core: Arc<HubCore>,
    machine: String,
    address: String,
    state: Mutex<ChatCore>,
    /// Fetched images by key, for scrolling back and forth.
    images: Mutex<HashMap<String, Vec<u8>>>,
    /// This terminal's events, subscribed on the first wait.
    events: tokio::sync::Mutex<Option<Stream>>,
}

impl Chat {
    pub(crate) fn new(core: Arc<HubCore>, machine: String, address: String) -> Self {
        Chat {
            core,
            machine,
            address,
            state: Mutex::new(ChatCore::new()),
            images: Mutex::new(HashMap::new()),
            events: tokio::sync::Mutex::new(None),
        }
    }
}

fn exec_stdout(v: &Value) -> Option<String> {
    let b = base64::engine::general_purpose::STANDARD.decode(v["stdout"].as_str()?).ok()?;
    Some(String::from_utf8_lossy(&b).into_owned())
}

/// What `terminal.get` says, as the core reads it.
fn term_of(t: &Value) -> Term {
    let fg = &t["foreground"];
    let argv: Vec<&str> = fg["argv"].as_array().map(|a| a.iter().filter_map(Value::as_str).collect()).unwrap_or_default();
    Term {
        agent: locate::agent_in_front(fg["name"].as_str().unwrap_or(""), &argv),
        pid: fg["pid"].as_i64(),
        status: t["status"]["state"].as_str().map(str::to_owned),
    }
}

/// `session` is the log's: a `log:` key names its session, so an image of an
/// earlier session (after `/clear`) is never fetched from the current log.
fn chat_images(images: &[Image], session: &str) -> Vec<ChatImage> {
    images
        .iter()
        .map(|m| match m {
            Image::InLog { line, n, media_type } => {
                ChatImage { key: format!("log:{session}:{line}:{n}"), media_type: media_type.clone() }
            }
            Image::Inline { media_type, data } => ChatImage { key: format!("inline:{data}"), media_type: media_type.clone() },
            Image::File { path } => ChatImage { key: format!("file:{path}"), media_type: String::new() },
            Image::Missing => ChatImage { key: "missing".into(), media_type: String::new() },
        })
        .collect()
}

fn chat_row(r: &Row, session: &str) -> ChatRow {
    let row = |kind: &str, text: &str| ChatRow {
        key: format!("{}:{}", r.key.0, r.key.1),
        kind: kind.into(),
        text: text.into(),
        from: String::new(),
        images: vec![],
        calls: vec![],
    };
    match &r.item {
        Item::User { text, images } => ChatRow { images: chat_images(images, session), ..row("user", text) },
        Item::Assistant { text } => row("said", text),
        Item::AgentMessage { from, text } => ChatRow { from: from.clone(), ..row("agent", text) },
        Item::Note { text } => row("note", text),
        Item::Tools { calls } => ChatRow {
            calls: calls
                .iter()
                .map(|c| ChatCall {
                    name: c.name.clone(),
                    title: c.title.clone(),
                    input: c.input.clone(),
                    output: c.output.clone(),
                    done: c.done,
                    failed: c.failed,
                    images: chat_images(&c.images, session),
                })
                .collect(),
            ..row("tools", "")
        },
    }
}

/// The message text for images put at `paths`: each path on a line of its
/// own, then the text.
fn with_images(paths: &[String], text: &str) -> String {
    let mut lines = paths.to_vec();
    lines.push(text.to_owned());
    lines.join("\n").trim().to_owned()
}

/// The `machine.exec` argv that prints the image behind `key`; a `log:` key
/// only for the open log (`session`, `path`).
fn image_argv(key: &str, log: Option<(&str, &str)>) -> Option<Value> {
    if let Some(r) = key.strip_prefix("log:") {
        let mut parts = r.rsplitn(3, ':');
        let (n, line, session) = (parts.next()?, parts.next()?, parts.next()?);
        let (open, path) = log?;
        if session != open {
            return None;
        }
        Some(json!(["sh", "-c", image::LOG_SCRIPT, "sh", path, line, n]))
    } else {
        let path = key.strip_prefix("file:")?;
        Some(json!(["sh", "-c", image::FILE_SCRIPT, "sh", path]))
    }
}

/// The agent in front may take a message: idle, done, or working (it
/// queues). Not blocked (a dialog), not unreported (a start-up prompt), not a
/// shell (it would run the text).
fn may_send(t: &Term) -> bool {
    t.agent.is_some() && matches!(t.status.as_deref(), Some("idle" | "done" | "working"))
}

fn refusal(r: Refusal) -> ChatRefusal {
    match r {
        Refusal::Waiting => ChatRefusal::Waiting,
        Refusal::Exited => ChatRefusal::Exited,
        Refusal::Changed => ChatRefusal::Changed,
    }
}

/// One read of the log through the tail script: an error says why (the
/// output was cut, the read timed out, the file went away).
async fn read_chunk(c: &Client, r: &Read) -> Result<tail::Chunk, String> {
    let mut argv = vec![json!("sh"), json!("-c"), json!(tail::SCRIPT), json!("sh"), json!(r.path), json!(r.start)];
    if let Some(e) = r.end {
        argv.push(json!(e.to_string()));
    }
    let got = c
        .call_timeout("machine.exec", json!({"argv": argv, "timeout": 25}), Duration::from_secs(30))
        .await
        .map_err(|e| format!("Couldn't read the log: {e}"))?;
    if got["truncated"] == true {
        return Err("Couldn't read the log: the output was cut".into());
    }
    if got["timed_out"] == true {
        return Err("Couldn't read the log: it took too long".into());
    }
    let out = exec_stdout(&got).unwrap_or_default();
    if out.trim() == "missing" {
        return Err("The log is gone".into());
    }
    tail::parse_chunk(&out).ok_or_else(|| "Couldn't read the log: unexpected output".into())
}

/// The lookup script's result for the agent in front, and the process start
/// it read; `None` when the desk did not answer.
async fn look_up(c: &Client, t: &Term) -> (Option<Lookup>, Option<u64>) {
    let (Some(agent), Some(pid)) = (t.agent, t.pid) else {
        return (Some(Lookup::None), None);
    };
    let got = c
        .call_timeout(
            "machine.exec",
            json!({"argv": ["sh", "-c", locate::SCRIPT, "sh", pid.to_string()], "timeout": 20}),
            Duration::from_secs(30),
        )
        .await;
    match got.ok().as_ref().and_then(exec_stdout) {
        Some(out) => {
            let probe = locate::parse_probe(&out);
            (Some(locate::decide_for(agent, &probe)), probe.proc_start)
        }
        None => (None, None),
    }
}

/// Stage timings for the log, in the order they ran.
#[derive(Default)]
struct Times(String);

impl Times {
    fn mark(&mut self, stage: &str, since: Instant) {
        if !self.0.is_empty() {
            self.0.push(' ');
        }
        self.0.push_str(&format!("{stage} {}", since.elapsed().as_millis()));
    }
}

#[uniffi::export]
impl Chat {
    /// Looks up and reads what is new. `event`: a status, turn or title
    /// event of the terminal came (see [`Chat::wait_change`]). Blocks: call
    /// it off the main thread.
    pub fn poll(&self, event: bool) -> ChatPoll {
        let core = self.core.clone();
        core.runtime().block_on(self.poll_async(event))
    }

    /// For the terminal view (design D5): looks the log up and reads its
    /// recent part only when [`ChatCore::needs_watch`] says so, to decide the
    /// switch; otherwise one `terminal.get`. Blocks.
    pub fn watch(&self, event: bool) -> ChatPoll {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let began = Instant::now();
            let mut times = Times::default();
            let Some(conn) = self.core.live_conn(&self.machine) else {
                return self.view("", false, Times("not connected".into()));
            };
            let c = conn.client;
            let Ok(t) = c.call("terminal.get", json!({"terminal": self.address})).await.map(|t| term_of(&t)) else {
                return self.view("", false, times);
            };
            self.state.lock().unwrap().watch_see(&t);
            if self.state.lock().unwrap().needs_watch(&t, event) {
                let at = Instant::now();
                let (l, _) = look_up(&c, &t).await;
                times.mark("locate", at);
                self.state.lock().unwrap().take_watch_lookup(&t, l, Instant::now());
                for _ in 0..2 {
                    let Some(r) = self.state.lock().unwrap().plan_read() else { break };
                    let at = Instant::now();
                    let got = read_chunk(&c, &r).await;
                    times.mark("tail", at);
                    if !self.state.lock().unwrap().take_read(got, Instant::now()) {
                        break;
                    }
                }
            }
            times.mark("total", began);
            let status = t.status.clone().unwrap_or_default();
            self.view(&status, may_send(&t), times)
        })
    }

    /// What is held, with no call: the last view drawn at once on return
    /// (design D12); `None` before the first lookup.
    pub fn held(&self) -> Option<ChatPoll> {
        if self.state.lock().unwrap().lookup().is_none() {
            return None;
        }
        Some(self.view("", false, Times::default()))
    }

    /// Reads the part of the log before the oldest row held and parses it
    /// together with what is held. Blocks.
    pub fn older(&self) -> ChatPoll {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let began = Instant::now();
            let mut times = Times::default();
            if let Some(conn) = self.core.live_conn(&self.machine) {
                loop {
                    let Some(r) = self.state.lock().unwrap().plan_older() else { break };
                    let got = read_chunk(&conn.client, &r).await;
                    if !self.state.lock().unwrap().take_older(got) {
                        break;
                    }
                }
            }
            times.mark("older", began);
            self.view("", false, times)
        })
    }

    /// The chat left the screen (terminal view, another terminal, the app
    /// in the background): it makes no reads until polled again, and then
    /// looks up and reads only what was written since (design D12).
    pub fn leave(&self) {
        self.state.lock().unwrap().leave();
    }

    /// Waits up to `timeout_ms` for a status, turn or title event of this
    /// terminal; true when one came. Blocks.
    pub fn wait_change(&self, timeout_ms: u32) -> bool {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let mut events = self.events.lock().await;
            if events.is_none() {
                let Some(conn) = self.core.live_conn(&self.machine) else {
                    drop(events);
                    tokio::time::sleep(Duration::from_millis(timeout_ms.into())).await;
                    return false;
                };
                match conn.client.subscribe(json!({"terminal": self.address})).await {
                    Ok((_, stream)) => *events = Some(stream),
                    // Waits anyway: returning at once would make the poll loop spin.
                    Err(_) => {
                        drop(events);
                        tokio::time::sleep(Duration::from_millis(timeout_ms.into())).await;
                        return false;
                    }
                }
            }
            let stream = events.as_mut().unwrap();
            let wait = async {
                loop {
                    match stream.recv().await {
                        StreamItem::Data(d) => {
                            if !String::from_utf8_lossy(&d).contains("heartbeat") {
                                return Some(true);
                            }
                        }
                        StreamItem::Closed(_) | StreamItem::Lost => return None,
                    }
                }
            };
            match tokio::time::timeout(Duration::from_millis(timeout_ms.into()), wait).await {
                Ok(Some(changed)) => changed,
                Ok(None) => {
                    *events = None;
                    drop(events);
                    tokio::time::sleep(Duration::from_millis(u64::from(timeout_ms).min(1000))).await;
                    false
                }
                Err(_) => false,
            }
        })
    }

    /// Sends `text` as the person's message (design D4); `None` when typed.
    pub fn send(&self, text: String) -> Option<ChatRefusal> {
        self.send_with_images(text, vec![])
    }

    /// Checks the target, puts each image beside the terminal
    /// (`terminal.put`), checks again, and sends the message with the
    /// images' paths first, one per line: Claude Code attaches a pasted line
    /// that is an image path as the image itself. `None` when typed.
    pub fn send_with_images(&self, text: String, images: Vec<ChatUpload>) -> Option<ChatRefusal> {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let failed = |reason: String| Some(ChatRefusal::Failed { reason });
            let Some(conn) = self.core.live_conn(&self.machine) else {
                return failed("Not connected".into());
            };
            let c = conn.client;
            let Some((agent, session)) = self.state.lock().unwrap().shown() else {
                return Some(ChatRefusal::Changed);
            };
            let target = match self.check(&c).await {
                Ok((t, start, l)) => match send::first_check(agent, &session, &seen(&t, start, &l)) {
                    Ok(target) => target,
                    Err(r) => return Some(refusal(r)),
                },
                Err(e) => return failed(e),
            };
            let mut lines = Vec::new();
            for up in images {
                let data = base64::engine::general_purpose::STANDARD.encode(&up.data);
                let put = c
                    .call_timeout(
                        "terminal.put",
                        json!({"terminal": self.address, "name": up.name, "data": data}),
                        Duration::from_secs(60),
                    )
                    .await;
                match put.as_ref().ok().and_then(|r| r["path"].as_str()) {
                    Some(p) => lines.push(p.to_owned()),
                    None => {
                        return failed(format!(
                            "Couldn't send the image: {}",
                            put.err().map(|e| e.to_string()).unwrap_or_default()
                        ));
                    }
                }
            }
            let message = with_images(&lines, &text);
            // Uploads take time: check again right before typing, and let the
            // core refuse if the status changed since (its `if`).
            let guard = match self.check(&c).await {
                Ok((t, start, l)) => match send::check_again(&target, &seen(&t, start, &l)) {
                    Ok(g) => g,
                    Err(r) => return Some(refusal(r)),
                },
                Err(e) => return failed(e),
            };
            let mut params = json!({"terminal": self.address, "text": message});
            if let Some(st) = guard {
                params["if"] = json!(st);
            }
            match c.call("terminal.send", params).await {
                Ok(_) => {
                    self.state.lock().unwrap().sent(&message, Instant::now());
                    None
                }
                Err(e) => failed(format!("{e}")),
            }
        })
    }

    /// The bytes of one image from [`ChatImage::key`], fetched from the desk
    /// the first time; `None` when it is gone or cannot be read. Blocks.
    pub fn image(&self, key: String) -> Option<Vec<u8>> {
        if let Some(b) = self.images.lock().unwrap().get(&key) {
            return Some(b.clone());
        }
        let core = self.core.clone();
        let got = core.runtime().block_on(self.fetch_image(&key))?;
        let mut cache = self.images.lock().unwrap();
        if cache.values().map(Vec::len).sum::<usize>() + got.len() > IMAGE_CACHE {
            cache.clear();
        }
        cache.insert(key, got.clone());
        Some(got)
    }
}

fn seen<'a>(t: &'a Term, proc_start: Option<u64>, l: &'a Lookup) -> Seen<'a> {
    Seen { agent: t.agent, pid: t.pid, proc_start, status: t.status.as_deref(), lookup: l }
}

impl Chat {
    /// One check of the send target: the terminal and a fresh lookup.
    async fn check(&self, c: &Client) -> Result<(Term, Option<u64>, Lookup), String> {
        let t = c.call("terminal.get", json!({"terminal": self.address})).await.map_err(|e| format!("{e}"))?;
        let t = term_of(&t);
        let (l, start) = look_up(c, &t).await;
        let l = l.ok_or_else(|| "The desk did not answer".to_string())?;
        Ok((t, start, l))
    }

    async fn poll_async(&self, event: bool) -> ChatPoll {
        let began = Instant::now();
        let mut times = Times::default();
        let Some(conn) = self.core.live_conn(&self.machine) else {
            return self.view("", false, Times("not connected".into()));
        };
        let c = conn.client;
        let t = match c.call("terminal.get", json!({"terminal": self.address})).await {
            Ok(t) => term_of(&t),
            Err(_) => {
                self.state.lock().unwrap().take_read(Err("terminal".into()), Instant::now());
                return self.view("", false, times);
            }
        };
        times.mark("get", began);
        let now = Instant::now();
        let lookup = self.state.lock().unwrap().needs_lookup(&t, event, now);
        if lookup {
            let at = Instant::now();
            let (l, _) = look_up(&c, &t).await;
            times.mark("locate", at);
            self.state.lock().unwrap().take_lookup(&t, l, Instant::now());
        }
        self.state.lock().unwrap().see(&t, Instant::now());
        // A return reads the log's size first, then the new lines.
        for _ in 0..2 {
            let Some(r) = self.state.lock().unwrap().plan_read() else { break };
            let at = Instant::now();
            let got = read_chunk(&c, &r).await;
            times.mark("tail", at);
            if !self.state.lock().unwrap().take_read(got, Instant::now()) {
                break;
            }
        }
        times.mark("total", began);
        let status = t.status.clone().unwrap_or_default();
        self.view(&status, may_send(&t), times)
    }

    fn view(&self, status: &str, in_front: bool, times: Times) -> ChatPoll {
        let st = self.state.lock().unwrap();
        let v = st.view();
        let session = st.shown().map(|(_, s)| s).unwrap_or_default();
        let found = match v.found {
            Kind::Found => ChatFound::Found,
            Kind::Empty => ChatFound::Empty,
            Kind::Missed => ChatFound::Missed,
            Kind::None => ChatFound::None,
        };
        let health = match &v.health {
            Health::Ok => ChatHealth::Ok,
            Health::Degraded { .. } => ChatHealth::Degraded,
            Health::Broken { reason } => ChatHealth::Broken { reason: reason.clone() },
        };
        let can_send = in_front
            && matches!(found, ChatFound::Found | ChatFound::Empty)
            && !v.exited
            && !matches!(health, ChatHealth::Broken { .. });
        ChatPoll {
            found,
            agent: match v.agent {
                Some(Agent::Claude) => "claude".into(),
                Some(Agent::Codex) => "codex".into(),
                None => String::new(),
            },
            title: v.title.unwrap_or_default(),
            version: v.version.unwrap_or_default(),
            status: status.to_owned(),
            health,
            problem: v.problem.unwrap_or_default(),
            exited: v.exited,
            can_send,
            cut: v.cut,
            rows: v.rows.iter().map(|r| chat_row(r, &session)).collect(),
            gauge_used: v.gauge.map(|g| g.used),
            gauge_window: v.gauge.and_then(|g| g.window),
            gauge_percent: v.gauge.and_then(|g| g.percent()),
            queued: v.queued.clone(),
            timings: times.0,
        }
    }

    async fn fetch_image(&self, key: &str) -> Option<Vec<u8>> {
        let b64 = base64::engine::general_purpose::STANDARD;
        if let Some(data) = key.strip_prefix("inline:") {
            return b64.decode(data).ok();
        }
        let log = {
            let st = self.state.lock().unwrap();
            let session = st.shown().map(|(_, s)| s);
            let path = match st.lookup() {
                Some(Lookup::Found { path, .. }) => Some(path.clone()),
                _ => None,
            };
            session.zip(path)
        };
        let argv = image_argv(key, log.as_ref().map(|(s, p)| (s.as_str(), p.as_str())))?;
        let c = self.core.live_conn(&self.machine)?.client;
        let got = c
            .call_timeout("machine.exec", json!({"argv": argv, "timeout": 30}), Duration::from_secs(40))
            .await
            .ok()?;
        if got["truncated"] == true {
            return None;
        }
        let out = b64.decode(got["stdout"].as_str()?).ok()?;
        if key.starts_with("log:") { image::decode(&out) } else { Some(out).filter(|b| !b.is_empty()) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn image_paths_go_first_one_per_line() {
        let p = vec!["/f/t1/a.png".to_owned(), "/f/t1/b.png".to_owned()];
        assert_eq!(with_images(&p, "What is this?"), "/f/t1/a.png\n/f/t1/b.png\nWhat is this?");
        assert_eq!(with_images(&p[..1], ""), "/f/t1/a.png", "an image alone");
        assert_eq!(with_images(&[], "hi"), "hi");
    }

    #[test]
    fn commands_are_sent_as_typed() {
        assert_eq!(with_images(&[], "/clear"), "/clear");
        assert_eq!(with_images(&[], "!ls"), "!ls");
        assert_eq!(with_images(&[], "/compact keep the API notes"), "/compact keep the API notes");
    }

    #[test]
    fn an_image_key_names_what_to_run() {
        let open = Some(("s1", "/logs/s.jsonl"));
        let a = image_argv("log:s1:2048:1", open).unwrap();
        assert_eq!(a[4], "/logs/s.jsonl");
        assert_eq!((a[5].as_str(), a[6].as_str()), (Some("2048"), Some("1")));
        assert_eq!(a[2], image::LOG_SCRIPT);
        let f = image_argv("file:/work/a b.png", None).unwrap();
        assert_eq!((f[2].as_str(), f[4].as_str()), (Some(image::FILE_SCRIPT), Some("/work/a b.png")));
        assert!(image_argv("log:s1:2048:1", None).is_none(), "no log open yet");
        assert!(image_argv("missing", None).is_none());
        let keys = chat_images(
            &[Image::InLog { line: 9, n: 0, media_type: "image/png".into() }, Image::File { path: "/x.png".into() }],
            "s1",
        );
        assert!(image_argv(&keys[0].key, open).is_some() && image_argv(&keys[1].key, None).is_some());
    }

    #[test]
    fn an_image_of_an_earlier_session_is_not_read_from_the_current_log() {
        // After /clear the new log can have a line at the same offset.
        assert!(image_argv("log:s1:2048:1", Some(("s2", "/logs/s2.jsonl"))).is_none());
    }

    fn get(name: &str, argv: &[&str], state: Option<&str>) -> Value {
        json!({"foreground": {"name": name, "argv": argv, "pid": 7}, "status": {"state": state}})
    }

    #[test]
    fn the_terminal_is_read_as_agent_process_and_status() {
        let t = term_of(&get("node", &["node", "/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js"], Some("idle")));
        assert_eq!((t.agent, t.pid, t.status.as_deref()), (Some(Agent::Claude), Some(7), Some("idle")));
        let shell = term_of(&get("zsh", &["zsh"], None));
        assert_eq!((shell.agent, shell.status), (None, None));
    }

    #[test]
    fn sends_only_to_an_agent_that_can_take_it() {
        let t = |name: &str, state: Option<&str>| term_of(&get(name, &[name], state));
        assert!(may_send(&t("claude", Some("idle"))));
        assert!(may_send(&t("codex", Some("working"))));
        assert!(may_send(&t("claude", Some("done"))));
        assert!(!may_send(&t("claude", Some("blocked"))), "a dialog would take the text");
        assert!(!may_send(&t("claude", None)), "no status yet: a start-up prompt");
        assert!(!may_send(&t("zsh", Some("idle"))), "a shell would run it");
    }

    #[test]
    fn a_row_key_names_its_line_and_index() {
        let r = Row { key: (2048, 1), item: Item::Note { text: "Interrupted".into() } };
        let c = chat_row(&r, "s1");
        assert_eq!((c.key.as_str(), c.kind.as_str(), c.text.as_str()), ("2048:1", "note", "Interrupted"));
    }
}
