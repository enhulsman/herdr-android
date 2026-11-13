//! Spike for issue 17 (never merged): the try-it chat view. Finds the log of
//! the agent running in a terminal, tails it by byte offset and parses it with
//! herdr-chat, all through `terminal.get` and `machine.exec`; sends with
//! `terminal.send`. Polled by the screen; the real feature would tail on
//! `turn` events instead.

use super::HubCore;
use base64::Engine;
use herdr_chat::{Agent, Health, Image, Item, Parser, image, locate, tail};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// The first open reads at most this much of the log's end.
const FIRST_BUDGET: u64 = 2_000_000;
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
    /// "user", "said", "tools" or "note".
    pub kind: String,
    pub text: String,
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
    /// An agent runs in front and may take a message now.
    pub can_send: bool,
    /// Whether the earliest rows were cut by the first-open budget.
    pub cut: bool,
    pub rows: Vec<ChatRow>,
}

struct Open {
    agent: Agent,
    session: String,
    path: Option<String>,
    next: Option<u64>,
    cut: bool,
    parser: Parser,
}

#[derive(uniffi::Object)]
pub struct ChatSpike {
    core: Arc<HubCore>,
    machine: String,
    address: String,
    open: Mutex<Option<Open>>,
    images: Mutex<HashMap<String, Vec<u8>>>,
}

impl ChatSpike {
    pub(crate) fn new(core: Arc<HubCore>, machine: String, address: String) -> Self {
        ChatSpike {
            core,
            machine,
            address,
            open: Mutex::new(None),
            images: Mutex::new(HashMap::new()),
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
        can_send: false,
        cut: false,
        rows: Vec::new(),
    }
}

fn chat_images(images: &[Image]) -> Vec<ChatImage> {
    images
        .iter()
        .map(|m| match m {
            Image::InLog { line, n, media_type } => ChatImage {
                key: format!("log:{line}:{n}"),
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

fn rows(items: &[Item]) -> Vec<ChatRow> {
    items
        .iter()
        .map(|i| match i {
            Item::User { text, images } => ChatRow {
                kind: "user".into(),
                text: text.clone(),
                images: chat_images(images),
                calls: vec![],
            },
            Item::Assistant { text } => ChatRow {
                kind: "said".into(),
                text: text.clone(),
                images: vec![],
                calls: vec![],
            },
            Item::Note { text } => ChatRow {
                kind: "note".into(),
                text: text.clone(),
                images: vec![],
                calls: vec![],
            },
            Item::Tools { calls } => ChatRow {
                kind: "tools".into(),
                text: String::new(),
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
                        images: chat_images(&c.images),
                    })
                    .collect(),
            },
        })
        .collect()
}

/// The message text for images put at `paths`: each path on a line of its
/// own, then the text.
fn with_images(paths: &[String], text: &str) -> String {
    let mut lines = paths.to_vec();
    lines.push(text.to_owned());
    lines.join("\n").trim().to_owned()
}

/// The `machine.exec` argv that prints the image behind `key`.
fn image_argv(key: &str, log: Option<&str>) -> Option<Value> {
    if let Some(r) = key.strip_prefix("log:") {
        let (line, n) = r.split_once(':')?;
        Some(json!(["sh", "-c", image::LOG_SCRIPT, "sh", log?, line, n]))
    } else {
        let path = key.strip_prefix("file:")?;
        Some(json!(["sh", "-c", image::FILE_SCRIPT, "sh", path]))
    }
}

/// An agent in front whose status lets a message in: idle, done, or working
/// (it queues). Not blocked (a dialog), not unreported (a start-up prompt
/// took the Enter in the spike), not a shell (it would run the text).
fn may_send(t: &Value) -> bool {
    let name = t["foreground"]["name"].as_str().unwrap_or("");
    let state = t["status"]["state"].as_str().unwrap_or("");
    matches!(name, "claude" | "codex") && matches!(state, "idle" | "done" | "working")
}

#[uniffi::export]
impl ChatSpike {
    /// Locates and reads what is new. Blocks: call it off the main thread.
    pub fn poll(&self) -> ChatPoll {
        let core = self.core.clone();
        core.runtime().block_on(self.poll_async())
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
            let conn = self.core.live_conn(&self.machine)?;
            let c = conn.client;
            let t = match c.call("terminal.get", json!({"terminal": self.address})).await {
                Ok(t) => t,
                Err(e) => return Some(format!("{e}")),
            };
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
            match c
                .call("terminal.send", json!({"terminal": self.address, "text": message}))
                .await
            {
                Ok(_) => None,
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
        let log = self.open.lock().unwrap().as_ref().and_then(|o| o.path.clone());
        let argv = image_argv(key, log.as_deref())?;
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
        let Some(conn) = self.core.live_conn(&self.machine) else {
            return fallback("not connected", "");
        };
        let c = conn.client;
        let t = match c.call("terminal.get", json!({"terminal": self.address})).await {
            Ok(t) => t,
            Err(e) => return fallback(&format!("terminal: {e}"), ""),
        };
        let can_send = may_send(&t);
        let pid = t["foreground"]["pid"].as_i64();
        let fg = t["foreground"]["name"].as_str().unwrap_or("").to_owned();

        let wait = Duration::from_secs(30);
        // Locate: the agent in front, if any. A shell in front keeps what was read
        // (the agent exited into its shell): readable, not answerable.
        if let (Some(pid), true) = (pid, matches!(fg.as_str(), "claude" | "codex")) {
            let got = c
                .call_timeout(
                    "machine.exec",
                    json!({"argv": ["sh", "-c", locate::SCRIPT, "sh", pid.to_string()], "timeout": 20}),
                    wait,
                )
                .await;
            let probe = match got.as_ref().ok().and_then(exec_stdout) {
                Some(out) => locate::parse_probe(&out),
                None => return fallback("locating the log failed", &fg),
            };
            let agent = if fg == "codex" { Agent::Codex } else { Agent::Claude };
            let (session, path) = match locate::decide(&probe) {
                locate::Found::Log { path, session } => (session, Some(path)),
                locate::Found::Empty { session } => (session, None),
                locate::Found::Nothing(why) => return fallback(why, &fg),
            };
            let mut open = self.open.lock().unwrap();
            let same = open.as_ref().is_some_and(|o| o.session == session && o.agent == agent);
            if !same {
                *open = Some(Open {
                    agent,
                    session,
                    path: None,
                    next: None,
                    cut: false,
                    parser: Parser::new(agent),
                });
            }
            if let Some(o) = open.as_mut() {
                if o.path.is_none() {
                    o.path = path;
                }
            }
        }

        // Tail what is new.
        let (path, offset) = {
            let open = self.open.lock().unwrap();
            match open.as_ref() {
                None => return fallback("no agent in this terminal", &fg),
                Some(o) => (
                    o.path.clone(),
                    o.next.map(|n| n.to_string()).unwrap_or_else(|| format!("-{FIRST_BUDGET}")),
                ),
            }
        };
        if let Some(path) = path {
            let got = c
                .call_timeout(
                    "machine.exec",
                    json!({"argv": ["sh", "-c", tail::SCRIPT, "sh", path, offset], "timeout": 25}),
                    wait,
                )
                .await;
            let chunk = got.as_ref().ok().and_then(exec_stdout).and_then(|o| tail::parse_chunk(&o));
            let mut open = self.open.lock().unwrap();
            if let (Some(o), Some(chunk)) = (open.as_mut(), chunk) {
                if o.next.is_none() {
                    o.cut = chunk.size > FIRST_BUDGET;
                }
                o.parser.feed(&chunk.text);
                o.next = Some(chunk.next);
            }
        }

        let open = self.open.lock().unwrap();
        let Some(o) = open.as_ref() else {
            return fallback("no agent in this terminal", &fg);
        };
        if let Health::Broken { reason } = o.parser.health() {
            return fallback(&format!("unreadable log: {reason}"), &fg);
        }
        ChatPoll {
            state: "ready".into(),
            reason: String::new(),
            agent: match o.agent {
                Agent::Claude => "claude".into(),
                Agent::Codex => "codex".into(),
            },
            title: o.parser.title().unwrap_or("").to_owned(),
            version: o.parser.version().unwrap_or("").to_owned(),
            can_send,
            cut: o.cut,
            rows: rows(o.parser.items()),
        }
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
        let a = image_argv("log:2048:1", Some("/logs/s.jsonl")).unwrap();
        assert_eq!(a[4], "/logs/s.jsonl");
        assert_eq!((a[5].as_str(), a[6].as_str()), (Some("2048"), Some("1")));
        assert_eq!(a[2], image::LOG_SCRIPT);
        let f = image_argv("file:/work/a b.png", None).unwrap();
        assert_eq!((f[2].as_str(), f[4].as_str()), (Some(image::FILE_SCRIPT), Some("/work/a b.png")));
        assert!(image_argv("log:2048:1", None).is_none(), "no log open yet");
        assert!(image_argv("missing", None).is_none());
        // Keys made from the parser's images round-trip.
        let keys = chat_images(&[
            Image::InLog { line: 9, n: 0, media_type: "image/png".into() },
            Image::File { path: "/x.png".into() },
        ]);
        assert!(image_argv(&keys[0].key, Some("/l")).is_some() && image_argv(&keys[1].key, None).is_some());
    }
}
