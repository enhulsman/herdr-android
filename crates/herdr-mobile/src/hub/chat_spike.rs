//! Spike for issue 17 (never merged): the try-it chat view. Finds the log of
//! the agent running in a terminal, tails it by byte offset and parses it with
//! herdr-chat, all through `terminal.get` and `machine.exec`; sends with
//! `terminal.send`. The screen polls after each event for the terminal
//! ([`ChatSpike::wait_change`]) and every half second while the agent works:
//! the log gains finished blocks without an event.

use super::HubCore;
use base64::Engine;
use herdr_chat::{Agent, Health, Image, Item, Parser, image, locate, tail};
use herdr_proto::rpc::{Stream, StreamItem};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// The first open reads at most this much of the log's end.
const FIRST_BUDGET: u64 = 2_000_000;
/// Each read back on scrolling up starts this far before the oldest line held,
/// doubled while a single line is longer.
const OLDER_BUDGET: u64 = 2_000_000;
/// `machine.exec` keeps the first 8 MB of output; the script prints at most
/// the bytes it reads, so no read spans more than this.
const READ_MAX: u64 = 7_500_000;
/// With the same agent process in front, the log is looked up again only this
/// often (a `/clear` or `/resume` keeps the process but changes the log).
const RELOCATE: Duration = Duration::from_secs(10);
/// Fetched images kept for scrolling back and forth; dropped all at once past this.
const IMAGE_CACHE: usize = 48_000_000;

/// An image in the conversation, fetched with [`ChatSpike::image`] when shown.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatImage {
    /// `log:<line>:<n>`, `file:<path>`, `inline:<base64>` or `missing`.
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
    /// "user", "said", "tools", "note" or "agent".
    pub kind: String,
    pub text: String,
    /// For "agent": who sent it (a subagent's task, or a session's name).
    pub from: String,
    pub images: Vec<ChatImage>,
    pub calls: Vec<ChatCall>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatPoll {
    /// "ready", "fallback" (show the terminal; `reason` says why).
    pub state: String,
    pub reason: String,
    pub agent: String,
    pub title: String,
    pub version: String,
    /// The terminal's status: "working", "idle", "done", "blocked", ... (empty
    /// when not looked at). The screen polls faster while it works.
    pub status: String,
    /// An agent runs in front and may take a message now.
    pub can_send: bool,
    /// Older rows exist in the log: [`ChatSpike::older`] reads them.
    pub cut: bool,
    pub rows: Vec<ChatRow>,
    /// Milliseconds per stage, for the log: "get 40 locate - tail 61 total 103".
    pub timings: String,
}

struct Open {
    agent: Agent,
    session: String,
    path: Option<String>,
    /// The log's filtered text held, from byte `from` to byte `next`; parsed
    /// again whole when older text is put in front.
    text: String,
    from: u64,
    next: Option<u64>,
    parser: Parser,
    /// The agent process the log was found for, and when.
    pid: i64,
    located: Instant,
    /// Why the last read failed, shown over the chat; empty when it worked.
    problem: String,
    /// Reading back stopped at a record too large to read.
    older_blocked: bool,
}

#[derive(uniffi::Object)]
pub struct ChatSpike {
    core: Arc<HubCore>,
    machine: String,
    address: String,
    open: Mutex<Option<Open>>,
    images: Mutex<HashMap<String, Vec<u8>>>,
    /// This terminal's events, subscribed on the first wait.
    events: tokio::sync::Mutex<Option<Stream>>,
    /// Until then every poll looks the log up again: a slash command was just
    /// sent, and `/clear` starts a new log in the same process.
    relook_until: Mutex<Option<Instant>>,
}

impl ChatSpike {
    pub(crate) fn new(core: Arc<HubCore>, machine: String, address: String) -> Self {
        ChatSpike {
            core,
            machine,
            address,
            open: Mutex::new(None),
            images: Mutex::new(HashMap::new()),
            events: tokio::sync::Mutex::new(None),
            relook_until: Mutex::new(None),
        }
    }
}

fn exec_stdout(v: &Value) -> Option<String> {
    let b = base64::engine::general_purpose::STANDARD
        .decode(v["stdout"].as_str()?)
        .ok()?;
    Some(String::from_utf8_lossy(&b).into_owned())
}

fn fallback(reason: &str, agent: &str) -> ChatPoll {
    ChatPoll {
        state: "fallback".into(),
        reason: reason.into(),
        agent: agent.into(),
        title: String::new(),
        version: String::new(),
        status: String::new(),
        can_send: false,
        cut: false,
        rows: Vec::new(),
        timings: String::new(),
    }
}

/// `session` is the log's: a `log:` key names its session, so an image of an
/// earlier session (after `/clear`) is never fetched from the current log.
fn chat_images(images: &[Image], session: &str) -> Vec<ChatImage> {
    images
        .iter()
        .map(|m| match m {
            Image::InLog { line, n, media_type } => ChatImage {
                key: format!("log:{session}:{line}:{n}"),
                media_type: media_type.clone(),
            },
            Image::Inline { media_type, data } => ChatImage {
                key: format!("inline:{data}"),
                media_type: media_type.clone(),
            },
            Image::File { path } => ChatImage {
                key: format!("file:{path}"),
                media_type: String::new(),
            },
            Image::Missing => ChatImage {
                key: "missing".into(),
                media_type: String::new(),
            },
        })
        .collect()
}

fn rows(items: &[Item], session: &str) -> Vec<ChatRow> {
    items
        .iter()
        .map(|i| match i {
            Item::User { text, images } => ChatRow {
                kind: "user".into(),
                text: text.clone(),
                from: String::new(),
                images: chat_images(images, session),
                calls: vec![],
            },
            Item::Assistant { text } => ChatRow {
                kind: "said".into(),
                text: text.clone(),
                from: String::new(),
                images: vec![],
                calls: vec![],
            },
            Item::AgentMessage { from, text } => ChatRow {
                kind: "agent".into(),
                text: text.clone(),
                from: from.clone(),
                images: vec![],
                calls: vec![],
            },
            Item::Note { text } => ChatRow {
                kind: "note".into(),
                text: text.clone(),
                from: String::new(),
                images: vec![],
                calls: vec![],
            },
            Item::Tools { calls } => ChatRow {
                kind: "tools".into(),
                text: String::new(),
                from: String::new(),
                images: vec![],
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
            },
        })
        .collect()
}

/// The message text for images put at `paths`: each path on a line of its
/// own, then the text.
/// How long to look the log up on every poll after sending `message`: a
/// slash command may start a new log (`/clear`) in the same process.
fn relook_after(message: &str) -> Option<Duration> {
    message.trim_start().starts_with('/').then_some(Duration::from_secs(3))
}

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

/// An agent in front whose status lets a message in: idle, done, or working
/// (it queues). Not blocked (a dialog), not unreported (a start-up prompt
/// took the Enter in the spike), not a shell (it would run the text).
fn may_send(t: &Value) -> bool {
    let state = t["status"]["state"].as_str().unwrap_or("");
    agent_in_front(t).is_some() && matches!(state, "idle" | "done" | "working")
}

/// The agent running in front, by process name, or for a launcher-wrapped
/// install (`node .../claude`, `node .../codex`) by its argv. None for a shell.
fn agent_in_front(t: &Value) -> Option<Agent> {
    let fg = &t["foreground"];
    let by_name = |name: &str| match name {
        "claude" => Some(Agent::Claude),
        "codex" => Some(Agent::Codex),
        _ => None,
    };
    if let Some(a) = by_name(fg["name"].as_str().unwrap_or("")) {
        return Some(a);
    }
    if fg["name"] != "node" {
        return None;
    }
    fg["argv"].as_array()?.iter().skip(1).take(2).filter_map(Value::as_str).find_map(|arg| {
        let base = arg.rsplit('/').next().unwrap_or(arg);
        by_name(base.trim_end_matches(".js")).or_else(|| arg.contains("claude-code").then_some(Agent::Claude))
    })
}

/// The agent in front is still the one whose log the chat shows (`shown`:
/// its pid and agent): not restarted, not replaced by another agent.
fn still_shown(t: &Value, shown: Option<(i64, Agent)>) -> bool {
    shown.is_some_and(|(pid, agent)| t["foreground"]["pid"].as_i64() == Some(pid) && agent_in_front(t) == Some(agent))
}

#[derive(Debug, PartialEq)]
enum Older {
    Prepend,
    /// Not one whole line fits the budget: a record too big to read.
    Blocked,
    Failed(String),
}

fn older_outcome(chunk: &Result<tail::Chunk, String>, from: u64) -> Older {
    match chunk {
        Ok(c) if c.from == from => Older::Blocked,
        Ok(_) => Older::Prepend,
        Err(e) => Older::Failed(e.clone()),
    }
}

#[uniffi::export]
impl ChatSpike {
    /// Locates and reads what is new. Blocks: call it off the main thread.
    pub fn poll(&self) -> ChatPoll {
        let core = self.core.clone();
        core.runtime().block_on(self.poll_async())
    }

    /// Reads the part of the log before the oldest row held and parses it
    /// together with what is held, so tool results still meet their calls.
    /// Blocks.
    pub fn older(&self) -> ChatPoll {
        let core = self.core.clone();
        core.runtime().block_on(self.older_async())
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

    /// Sends `text` as the person's message; the error is for a toast.
    pub fn send(&self, text: String) -> Option<String> {
        self.send_with_images(text, vec![])
    }

    /// Puts each image beside the terminal (`terminal.put`), then sends the
    /// message with their paths first, one per line: Claude Code attaches a
    /// pasted line that is an image path as the image itself (tested on
    /// 2.1.323). The error is for a toast.
    pub fn send_with_images(&self, text: String, images: Vec<ChatUpload>) -> Option<String> {
        let core = self.core.clone();
        core.runtime().block_on(async {
            let Some(conn) = self.core.live_conn(&self.machine) else {
                return Some("Not connected".into());
            };
            let c = conn.client;
            let t = match c.call("terminal.get", json!({"terminal": self.address})).await {
                Ok(t) => t,
                Err(e) => return Some(format!("{e}")),
            };
            let shown = self.open.lock().unwrap().as_ref().map(|o| (o.pid, o.agent));
            if !still_shown(&t, shown) {
                return Some("Another agent is in this terminal now; reopen the chat".into());
            }
            if !may_send(&t) {
                return Some("The agent can't take a message right now".into());
            }
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
                    None => return Some(format!("Couldn't send the image: {}", put.err().map(|e| e.to_string()).unwrap_or_default())),
                }
            }
            let message = with_images(&lines, &text);
            // Uploads take time: look again right before typing, and let the
            // core refuse if the status changed since (its `if`, idle or
            // working only; done has no such guard).
            let t = match c.call("terminal.get", json!({"terminal": self.address})).await {
                Ok(t) => t,
                Err(e) => return Some(format!("{e}")),
            };
            let shown = self.open.lock().unwrap().as_ref().map(|o| (o.pid, o.agent));
            if !still_shown(&t, shown) {
                return Some("Another agent is in this terminal now; reopen the chat".into());
            }
            if !may_send(&t) {
                return Some("The agent can't take a message right now".into());
            }
            let mut params = json!({"terminal": self.address, "text": message});
            if let Some(st) = t["status"]["state"].as_str().filter(|s| matches!(*s, "idle" | "working")) {
                params["if"] = json!(st);
            }
            match c.call("terminal.send", params).await
            {
                Ok(_) => {
                    if let Some(w) = relook_after(&message) {
                        *self.relook_until.lock().unwrap() = Some(Instant::now() + w);
                    }
                    None
                }
                Err(e) => Some(format!("{e}")),
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

impl ChatSpike {
    async fn fetch_image(&self, key: &str) -> Option<Vec<u8>> {
        let b64 = base64::engine::general_purpose::STANDARD;
        if let Some(data) = key.strip_prefix("inline:") {
            return b64.decode(data).ok();
        }
        let log = self
            .open
            .lock()
            .unwrap()
            .as_ref()
            .and_then(|o| Some((o.session.clone(), o.path.clone()?)));
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
        if key.starts_with("log:") {
            image::decode(&out)
        } else {
            Some(out).filter(|b| !b.is_empty())
        }
    }

    async fn poll_async(&self) -> ChatPoll {
        let began = Instant::now();
        let mut times = Times::default();
        let Some(conn) = self.core.live_conn(&self.machine) else {
            return fallback("not connected", "");
        };
        let c = conn.client;
        let t = match c.call("terminal.get", json!({"terminal": self.address})).await {
            Ok(t) => t,
            Err(e) => return fallback(&format!("terminal: {e}"), ""),
        };
        times.mark("get", began);
        let can_send = may_send(&t);
        let status = t["status"]["state"].as_str().unwrap_or("").to_owned();
        let pid = t["foreground"]["pid"].as_i64();
        let fg = t["foreground"]["name"].as_str().unwrap_or("").to_owned();

        let wait = Duration::from_secs(30);
        // Locate: the agent in front, if any; skipped while the same process
        // stays in front and was looked up lately. A shell in front keeps what
        // was read (the agent exited into its shell): readable, not answerable.
        // While the log is still empty (a new session), look every time: its
        // first message creates the file.
        let fresh = |o: &Open| Some(o.pid) == pid && o.path.is_some() && o.located.elapsed() < RELOCATE;
        let relook = self.relook_until.lock().unwrap().is_some_and(|u| Instant::now() < u);
        let known = !relook && self.open.lock().unwrap().as_ref().is_some_and(fresh);
        let front = agent_in_front(&t);
        if let (Some(pid), Some(agent), false) = (pid, front, known) {
            let at = Instant::now();
            let got = c
                .call_timeout(
                    "machine.exec",
                    json!({"argv": ["sh", "-c", locate::SCRIPT, "sh", pid.to_string()], "timeout": 20}),
                    wait,
                )
                .await;
            times.mark("locate", at);
            // A lookup that fails while this agent's log is held keeps the
            // chat (and its message box) with a note; looked up again later.
            let held = self.open.lock().unwrap().as_ref().is_some_and(|o| o.path.is_some() && still_shown(&t, Some((o.pid, o.agent))));
            let found = match got.as_ref().ok().and_then(exec_stdout) {
                Some(out) => locate::decide_for(agent, &locate::parse_probe(&out)),
                None => locate::Found::Nothing("locating the log failed"),
            };
            let (session, path) = match found {
                locate::Found::Log { path, session } => (session, Some(path)),
                locate::Found::Empty { session } => (session, None),
                locate::Found::Nothing(why) if held => {
                    if let Some(o) = self.open.lock().unwrap().as_mut() {
                        o.problem = format!("Couldn't check the session log: {why}");
                    }
                    return self.ready(can_send, &status, &fg, times, began);
                }
                locate::Found::Nothing(why) => return fallback(why, &fg),
            };
            let mut open = self.open.lock().unwrap();
            let same = open.as_ref().is_some_and(|o| o.session == session && o.agent == agent);
            if !same {
                *open = Some(Open {
                    agent,
                    session,
                    path: None,
                    text: String::new(),
                    from: 0,
                    next: None,
                    parser: Parser::new(agent),
                    pid,
                    located: Instant::now(),
                    problem: String::new(),
                    older_blocked: false,
                });
            }
            if let Some(o) = open.as_mut() {
                if o.path.is_none() {
                    o.path = path;
                }
                o.pid = pid;
                o.located = Instant::now();
            }
        }

        // Tail what is new: at most READ_MAX bytes a poll, the rest next time.
        let (path, start, end) = {
            let open = self.open.lock().unwrap();
            match open.as_ref() {
                None => return fallback("no agent in this terminal", &fg),
                Some(o) => match o.next {
                    Some(n) => (o.path.clone(), n.to_string(), Some(n + READ_MAX)),
                    None => (o.path.clone(), format!("-{FIRST_BUDGET}"), None),
                },
            }
        };
        if let Some(path) = path {
            let at = Instant::now();
            let got = read_chunk(&c, &path, &start, end).await;
            times.mark("tail", at);
            let mut open = self.open.lock().unwrap();
            if let Some(o) = open.as_mut() {
                match got {
                    Ok(chunk) => {
                        let at = Instant::now();
                        if o.next.is_none() {
                            o.from = chunk.from;
                        }
                        // A full window with no whole line: one record longer than it.
                        let stuck = Some(chunk.next) == o.next && end.is_some_and(|e| chunk.size > e);
                        o.problem = if stuck { "A log record is too large to read".into() } else { String::new() };
                        o.parser.feed(&chunk.text);
                        o.text.push_str(&chunk.text);
                        o.next = Some(chunk.next);
                        times.mark("parse", at);
                    }
                    Err(why) => o.problem = why,
                }
            }
        }
        self.ready(can_send, &status, &fg, times, began)
    }

    /// What is held, as rows, or the fallback when the parser gave up.
    fn ready(&self, can_send: bool, status: &str, fg: &str, mut times: Times, began: Instant) -> ChatPoll {
        let open = self.open.lock().unwrap();
        let Some(o) = open.as_ref() else {
            return fallback("no agent in this terminal", fg);
        };
        let health = o.parser.health();
        if let Health::Broken { reason } = &health {
            return fallback(&format!("unreadable log: {reason}"), fg);
        }
        let mut rows = rows(o.parser.items(), &o.session);
        if o.older_blocked {
            rows.insert(0, note_row("Earlier messages could not be loaded"));
        }
        // Shown over the chat: a read that failed, or parts left out.
        let reason = match (&o.problem, &health) {
            (p, _) if !p.is_empty() => p.clone(),
            (_, Health::Degraded { .. }) => "Some parts of this session are not shown".into(),
            _ => String::new(),
        };
        times.mark("total", began);
        ChatPoll {
            state: "ready".into(),
            reason,
            agent: match o.agent {
                Agent::Claude => "claude".into(),
                Agent::Codex => "codex".into(),
            },
            title: o.parser.title().unwrap_or("").to_owned(),
            version: o.parser.version().unwrap_or("").to_owned(),
            status: status.to_owned(),
            can_send,
            cut: o.from > 0 && !o.older_blocked,
            rows,
            timings: times.0,
        }
    }

    async fn older_async(&self) -> ChatPoll {
        let began = Instant::now();
        let mut times = Times::default();
        let (path, from, agent) = {
            let open = self.open.lock().unwrap();
            match open.as_ref() {
                Some(o) => (o.path.clone(), o.from, o.agent),
                None => return fallback("no agent in this terminal", ""),
            }
        };
        if let (Some(path), true, Some(conn)) = (path, from > 0, self.core.live_conn(&self.machine)) {
            let mut budget = OLDER_BUDGET;
            // A window that holds no whole line (one line longer than it) reads again wider.
            let chunk = loop {
                let start = from.saturating_sub(budget);
                match read_chunk(&conn.client, &path, &start.to_string(), Some(from)).await {
                    Ok(c) if c.from == from && start > 0 && budget < READ_MAX => budget = (budget * 2).min(READ_MAX),
                    other => break other,
                }
            };
            times.mark("older", began);
            let mut open = self.open.lock().unwrap();
            if let Some(o) = open.as_mut().filter(|o| o.from == from && o.agent == agent) {
                match (older_outcome(&chunk, from), &chunk) {
                    // Stops reading back, so the top of the list does not ask again and again.
                    (Older::Failed(why), _) => {
                        o.problem = why;
                        o.older_blocked = true;
                    }
                    (Older::Blocked, _) => o.older_blocked = true,
                    (Older::Prepend, Ok(chunk)) => {
                        let at = Instant::now();
                        o.text.insert_str(0, &chunk.text);
                        o.from = chunk.from;
                        o.parser = Parser::new(agent);
                        o.parser.feed(&o.text);
                        times.mark("parse", at);
                    }
                    (Older::Prepend, Err(_)) => {}
                }
            }
        }
        let fg = match agent {
            Agent::Claude => "claude",
            Agent::Codex => "codex",
        };
        // Status and whether it may send are not looked at here: the screen
        // keeps what the last poll said.
        self.ready(false, "", fg, times, began)
    }
}

/// One read of the log through the tail script: an error says why, for the
/// screen (the output was cut, the read timed out, the file went away).
async fn read_chunk(c: &herdr_proto::rpc::Client, path: &str, start: &str, end: Option<u64>) -> Result<tail::Chunk, String> {
    let mut argv = vec![json!("sh"), json!("-c"), json!(tail::SCRIPT), json!("sh"), json!(path), json!(start)];
    if let Some(e) = end {
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

fn note_row(text: &str) -> ChatRow {
    ChatRow {
        kind: "note".into(),
        text: text.into(),
        from: String::new(),
        images: vec![],
        calls: vec![],
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
        // Keys made from the parser's images round-trip.
        let keys = chat_images(
            &[
                Image::InLog { line: 9, n: 0, media_type: "image/png".into() },
                Image::File { path: "/x.png".into() },
            ],
            "s1",
        );
        assert!(image_argv(&keys[0].key, open).is_some() && image_argv(&keys[1].key, None).is_some());
    }

    #[test]
    fn an_image_of_an_earlier_session_is_not_read_from_the_current_log() {
        // After /clear the new log can have a line at the same offset.
        assert!(image_argv("log:s1:2048:1", Some(("s2", "/logs/s2.jsonl"))).is_none());
    }

    fn term(name: &str, argv: &[&str], state: &str) -> Value {
        json!({"foreground": {"name": name, "argv": argv, "pid": 7}, "status": {"state": state}})
    }

    #[test]
    fn the_agent_in_front_by_name_or_by_its_launcher() {
        assert_eq!(agent_in_front(&term("claude", &["claude"], "idle")), Some(Agent::Claude));
        assert_eq!(agent_in_front(&term("codex", &["codex"], "idle")), Some(Agent::Codex));
        let npm = term("node", &["node", "/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js"], "idle");
        assert_eq!(agent_in_front(&npm), Some(Agent::Claude));
        assert_eq!(agent_in_front(&term("node", &["node", "/opt/bin/codex", "--no-daemon"], "idle")), Some(Agent::Codex));
        assert_eq!(agent_in_front(&term("node", &["node", "server.js"], "idle")), None, "another node program");
        assert_eq!(agent_in_front(&term("zsh", &["zsh"], "idle")), None);
    }

    #[test]
    fn sends_only_to_an_agent_that_can_take_it() {
        assert!(may_send(&term("claude", &[], "idle")));
        assert!(may_send(&term("codex", &[], "working")));
        assert!(may_send(&term("claude", &[], "done")));
        assert!(!may_send(&term("claude", &[], "blocked")), "a dialog would take the text");
        assert!(!may_send(&term("claude", &[], "")), "no status yet: a start-up prompt");
        assert!(!may_send(&term("zsh", &[], "idle")), "a shell would run it");
    }

    #[test]
    fn sends_only_to_the_agent_the_chat_shows() {
        let shown = Some((7, Agent::Claude));
        assert!(still_shown(&term("claude", &[], "idle"), shown));
        assert!(!still_shown(&term("codex", &[], "idle"), shown), "another agent took the terminal");
        let mut other = term("claude", &[], "idle");
        other["foreground"]["pid"] = json!(8);
        assert!(!still_shown(&other, shown), "the agent was restarted");
        assert!(!still_shown(&term("claude", &[], "idle"), None), "nothing read yet");
    }

    #[test]
    fn a_failed_read_back_stops_and_says_why() {
        let chunk = |from| Ok(tail::Chunk { text: "x\n".into(), from, next: 100, size: 100 });
        assert_eq!(older_outcome(&chunk(40), 100), Older::Prepend);
        assert_eq!(older_outcome(&chunk(100), 100), Older::Blocked, "no whole line fits");
        assert_eq!(
            older_outcome(&Err("Couldn't read the log: it took too long".into()), 100),
            Older::Failed("Couldn't read the log: it took too long".into())
        );
    }

    #[test]
    fn a_slash_command_looks_the_log_up_again_for_a_moment() {
        assert_eq!(relook_after("/clear"), Some(Duration::from_secs(3)));
        assert_eq!(relook_after("Fix the /api route"), None);
        assert_eq!(relook_after("!ls"), None);
    }

    #[test]
    fn commands_are_sent_as_typed() {
        // The chat sends whatever the agent's own input box takes: slash
        // commands and a leading `!` (a shell command on the desk) included.
        assert_eq!(with_images(&[], "/clear"), "/clear");
        assert_eq!(with_images(&[], "!ls"), "!ls");
        assert_eq!(with_images(&[], "/compact keep the API notes"), "/compact keep the API notes");
    }
}
