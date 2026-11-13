//! Spike for issue 17 (never merged): the try-it chat view. Finds the log of
//! the agent running in a terminal, tails it by byte offset and parses it with
//! herdr-chat, all through `terminal.get` and `machine.exec`; sends with
//! `terminal.send`. Polled by the screen; the real feature would tail on
//! `turn` events instead.

use super::HubCore;
use base64::Engine;
use herdr_chat::{Agent, Health, Item, Parser, locate, tail};
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// The first open reads at most this much of the log's end.
const FIRST_BUDGET: u64 = 2_000_000;

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatCall {
    pub name: String,
    pub title: String,
    pub input: String,
    pub output: String,
    pub done: bool,
    pub failed: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct ChatRow {
    /// "user", "said", "tools" or "note".
    pub kind: String,
    pub text: String,
    pub images: u32,
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
}

impl ChatSpike {
    pub(crate) fn new(core: Arc<HubCore>, machine: String, address: String) -> Self {
        ChatSpike {
            core,
            machine,
            address,
            open: Mutex::new(None),
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

fn rows(items: &[Item]) -> Vec<ChatRow> {
    items
        .iter()
        .map(|i| match i {
            Item::User { text, images } => ChatRow {
                kind: "user".into(),
                text: text.clone(),
                images: *images,
                calls: vec![],
            },
            Item::Assistant { text } => ChatRow {
                kind: "said".into(),
                text: text.clone(),
                images: 0,
                calls: vec![],
            },
            Item::Note { text } => ChatRow {
                kind: "note".into(),
                text: text.clone(),
                images: 0,
                calls: vec![],
            },
            Item::Tools { calls } => ChatRow {
                kind: "tools".into(),
                text: String::new(),
                images: 0,
                calls: calls
                    .iter()
                    .map(|c| ChatCall {
                        name: c.name.clone(),
                        title: c.title.clone(),
                        input: c.input.clone(),
                        output: c.output.clone(),
                        done: c.done,
                        failed: c.failed,
                    })
                    .collect(),
            },
        })
        .collect()
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
            match c
                .call("terminal.send", json!({"terminal": self.address, "text": text}))
                .await
            {
                Ok(_) => None,
                Err(e) => Some(format!("{e}")),
            }
        })
    }
}

impl ChatSpike {
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
