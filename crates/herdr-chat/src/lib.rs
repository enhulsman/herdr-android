//! Spike for issue 17 (never merged): an agent's session log as the
//! conversation a chat view shows. Claude Code (JSONL per session, tested on
//! 2.1.322-2.1.323) and Codex (rollout JSONL, tested on 0.166.0 and 0.169.0).
//! Both formats are private and change; [`Parser::health`] is the guard the
//! view uses to fall back to the terminal.

pub mod locate;
pub mod tail;

use serde_json::Value;
use std::collections::HashMap;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Agent {
    Claude,
    Codex,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Item {
    User { text: String, images: u32 },
    /// Markdown as the agent wrote it.
    Assistant { text: String },
    /// Tool calls in a row, shown folded as one line.
    Tools { calls: Vec<ToolCall> },
    Note { text: String },
}

#[derive(Clone, Debug, PartialEq)]
pub struct ToolCall {
    pub name: String,
    pub title: String,
    pub input: String,
    pub output: String,
    pub done: bool,
    pub failed: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Health {
    Ok,
    /// Readable, with this many parts it did not know (left out).
    Degraded { unknown: u32 },
    /// Not this format any more: show the terminal instead.
    Broken { reason: String },
}

pub struct Parser {
    agent: Agent,
    partial: String,
    items: Vec<Item>,
    /// tool id -> (item index, call index)
    calls: HashMap<String, (usize, usize)>,
    last_text_id: Option<String>,
    cwd: String,
    lines: u32,
    recognised: u32,
    unknown: u32,
    broken: Option<String>,
    title: Option<String>,
    version: Option<String>,
}

impl Parser {
    pub fn new(agent: Agent) -> Self {
        Parser {
            agent,
            partial: String::new(),
            items: Vec::new(),
            calls: HashMap::new(),
            last_text_id: None,
            cwd: String::new(),
            lines: 0,
            recognised: 0,
            unknown: 0,
            broken: None,
            title: None,
            version: None,
        }
    }

    /// New text from the log; a line without its newline waits for the rest.
    pub fn feed(&mut self, text: &str) {
        self.partial.push_str(text);
        while let Some(i) = self.partial.find('\n') {
            let line: String = self.partial.drain(..=i).collect();
            let line = line.trim_end();
            if !line.is_empty() {
                self.line(line);
            }
        }
    }

    pub fn items(&self) -> &[Item] {
        &self.items
    }

    pub fn title(&self) -> Option<&str> {
        self.title.as_deref()
    }

    pub fn version(&self) -> Option<&str> {
        self.version.as_deref()
    }

    pub fn health(&self) -> Health {
        if let Some(reason) = &self.broken {
            return Health::Broken {
                reason: reason.clone(),
            };
        }
        if self.lines > 0 && self.recognised * 2 < self.lines {
            return Health::Broken {
                reason: "not this agent's format".into(),
            };
        }
        if self.unknown > 0 {
            return Health::Degraded {
                unknown: self.unknown,
            };
        }
        Health::Ok
    }

    fn line(&mut self, line: &str) {
        self.lines += 1;
        let v: Value = match serde_json::from_str(line) {
            Ok(v) => v,
            Err(e) => return self.break_with(format!("line {}: {e}", self.lines)),
        };
        match self.agent {
            Agent::Claude => self.claude(&v),
            Agent::Codex => self.codex(&v),
        }
    }

    fn break_with(&mut self, reason: String) {
        self.broken.get_or_insert(reason);
    }

    // ---- shared building

    fn push(&mut self, item: Item) {
        self.last_text_id = None;
        self.items.push(item);
    }

    fn add_call(&mut self, id: Option<String>, call: ToolCall) {
        self.last_text_id = None;
        if !matches!(self.items.last(), Some(Item::Tools { .. })) {
            self.items.push(Item::Tools { calls: Vec::new() });
        }
        let at = self.items.len() - 1;
        if let Some(Item::Tools { calls }) = self.items.last_mut() {
            calls.push(call);
            if let Some(id) = id {
                self.calls.insert(id, (at, calls.len() - 1));
            }
        }
    }

    fn call_mut(&mut self, id: &str) -> Option<&mut ToolCall> {
        let (i, c) = *self.calls.get(id)?;
        match self.items.get_mut(i)? {
            Item::Tools { calls } => calls.get_mut(c),
            _ => None,
        }
    }

    fn relative(&self, path: &str) -> String {
        let cwd = self.cwd.trim_end_matches('/');
        match path.strip_prefix(cwd).and_then(|p| p.strip_prefix('/')) {
            Some(rel) if !cwd.is_empty() => rel.to_owned(),
            _ => path.to_owned(),
        }
    }

    // ---- Claude Code

    fn claude(&mut self, v: &Value) {
        let kind = v["type"].as_str().unwrap_or("");
        if v.get("sessionId").is_some()
            || matches!(kind, "user" | "assistant" | "system" | "file-history-snapshot")
        {
            self.recognised += 1;
        }
        if let Some(cwd) = v["cwd"].as_str() {
            self.cwd = cwd.to_owned();
        }
        if let Some(ver) = v["version"].as_str() {
            self.version = Some(ver.to_owned());
        }
        match kind {
            "user" | "assistant" => {}
            "ai-title" => {
                self.title = v["aiTitle"].as_str().map(str::to_owned);
                return;
            }
            "system" => {
                if v["subtype"] == "compact_boundary" {
                    self.push(Item::Note {
                        text: "Conversation compacted".into(),
                    });
                }
                return;
            }
            _ => return,
        }
        let msg = &v["message"];
        let content = &msg["content"];
        if msg["role"].as_str().is_none() || !(content.is_string() || content.is_array()) {
            return self.break_with(format!("line {}: message without role or content", self.lines));
        }
        if v["isSidechain"] == true || v["isMeta"] == true {
            return;
        }
        if kind == "assistant" {
            self.claude_assistant(msg);
        } else {
            self.claude_user(content);
        }
    }

    fn claude_assistant(&mut self, msg: &Value) {
        let id = msg["id"].as_str().map(str::to_owned);
        for b in msg["content"].as_array().into_iter().flatten() {
            match b["type"].as_str().unwrap_or("") {
                "thinking" | "redacted_thinking" => {}
                "text" => {
                    let t = b["text"].as_str().unwrap_or("").trim();
                    if t.is_empty() {
                        continue;
                    }
                    match (self.items.last_mut(), &self.last_text_id) {
                        (Some(Item::Assistant { text }), Some(last)) if Some(last) == id.as_ref() => {
                            text.push_str("\n\n");
                            text.push_str(t);
                        }
                        _ => {
                            self.push(Item::Assistant { text: t.to_owned() });
                            self.last_text_id = id.clone();
                        }
                    }
                }
                "tool_use" => {
                    let name = b["name"].as_str().unwrap_or("tool").to_owned();
                    let input = &b["input"];
                    let (title, shown) = self.claude_tool_title(&name, input);
                    self.add_call(
                        b["id"].as_str().map(str::to_owned),
                        ToolCall {
                            name,
                            title,
                            input: shown,
                            output: String::new(),
                            done: false,
                            failed: false,
                        },
                    );
                }
                _ => self.unknown += 1,
            }
        }
    }

    fn claude_tool_title(&self, name: &str, input: &Value) -> (String, String) {
        let s = |k: &str| input[k].as_str().map(str::to_owned);
        let shown = s("command").unwrap_or_else(|| input.to_string());
        if let Some(d) = s("description") {
            return (d, shown);
        }
        let title = match name {
            "Bash" => s("command").map(|c| c.lines().next().unwrap_or("").to_owned()),
            "Read" | "Edit" | "Write" | "NotebookEdit" => {
                s("file_path").map(|p| format!("{name} {}", self.relative(&p)))
            }
            "Grep" | "Glob" => s("pattern").map(|p| format!("{name} {p}")),
            _ => None,
        };
        (title.unwrap_or_else(|| name.to_owned()), shown)
    }

    fn claude_user(&mut self, content: &Value) {
        if let Some(s) = content.as_str() {
            let t = s.trim_start();
            if let Some(rest) = t.strip_prefix("<command-name>") {
                let cmd = rest.split("</command-name>").next().unwrap_or("").trim();
                self.push(Item::Note { text: cmd.into() });
            } else if !t.starts_with("<local-command") {
                self.push(Item::User {
                    text: unwrap_sent(s),
                    images: 0,
                });
            }
            return;
        }
        let mut texts = Vec::new();
        let mut images = 0;
        for b in content.as_array().into_iter().flatten() {
            match b["type"].as_str().unwrap_or("") {
                "text" => texts.push(unwrap_sent(b["text"].as_str().unwrap_or(""))),
                "image" => images += 1,
                "tool_result" => {
                    let out = tool_output(&b["content"]);
                    let failed = b["is_error"] == true;
                    if let Some(call) = b["tool_use_id"].as_str().and_then(|id| self.call_mut(id)) {
                        call.output = out;
                        call.failed = failed;
                        call.done = true;
                    }
                }
                _ => self.unknown += 1,
            }
        }
        if !texts.is_empty() || images > 0 {
            self.push(Item::User {
                text: texts.join("\n\n"),
                images,
            });
        }
    }

    // ---- Codex

    fn codex(&mut self, v: &Value) {
        let Some(kind) = v["type"].as_str() else { return };
        if v.get("payload").is_none() {
            return;
        }
        self.recognised += 1;
        let p = &v["payload"];
        match kind {
            "session_meta" => {
                self.version = p["cli_version"].as_str().map(str::to_owned);
                if let Some(cwd) = p["cwd"].as_str() {
                    self.cwd = cwd.to_owned();
                }
            }
            "turn_context" => {
                if let Some(cwd) = p["cwd"].as_str() {
                    self.cwd = cwd.to_owned();
                }
            }
            "event_msg" if p["type"] == "item_completed" => {
                let item = &p["item"];
                match item["type"].as_str() {
                    Some(t) => self.codex_item(t, item),
                    None => self.break_with(format!("line {}: item without type", self.lines)),
                }
            }
            _ => {}
        }
    }

    fn codex_item(&mut self, t: &str, item: &Value) {
        let text = || {
            item["content"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(|c| c["text"].as_str())
                .collect::<Vec<_>>()
                .join("\n")
        };
        match t {
            "UserMessage" => {
                let images = item["content"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter(|c| c["type"].as_str().is_some_and(|k| k.contains("image")))
                    .count() as u32;
                self.push(Item::User {
                    text: text(),
                    images,
                })
            }
            "AgentMessage" => self.push(Item::Assistant { text: text() }),
            "Reasoning" => {}
            "CommandExecution" => {
                let cmd = item["parsed_cmd"][0]["cmd"]
                    .as_str()
                    .map(str::to_owned)
                    .or_else(|| {
                        item["command"]
                            .as_array()
                            .and_then(|a| a.last())
                            .and_then(Value::as_str)
                            .map(str::to_owned)
                    })
                    .unwrap_or_default();
                self.add_call(
                    None,
                    ToolCall {
                        name: "Shell".into(),
                        title: cmd.lines().next().unwrap_or("").to_owned(),
                        input: cmd,
                        output: item["aggregated_output"].as_str().unwrap_or("").to_owned(),
                        done: true,
                        failed: item["exit_code"].as_i64().is_some_and(|c| c != 0),
                    },
                );
            }
            "FileChange" => {
                let paths: Vec<String> = item["changes"]
                    .as_object()
                    .map(|m| m.keys().map(|p| self.relative(p)).collect())
                    .unwrap_or_default();
                self.add_call(
                    None,
                    ToolCall {
                        name: "Edit".into(),
                        title: format!("Edit {}", paths.join(", ")),
                        input: paths.join("\n"),
                        output: item["stdout"].as_str().unwrap_or("").to_owned(),
                        done: true,
                        failed: item["status"].as_str().is_some_and(|s| s != "completed"),
                    },
                );
            }
            "ImageView" => {
                let path = self.relative(item["path"].as_str().unwrap_or(""));
                self.add_call(
                    None,
                    ToolCall {
                        name: "View image".into(),
                        title: format!("View {path}"),
                        input: path,
                        output: "[image]".into(),
                        done: true,
                        failed: false,
                    },
                );
            }
            "Extension" => {
                let q = item["query"].as_str().unwrap_or("").to_owned();
                self.add_call(
                    None,
                    ToolCall {
                        name: item["kind"].as_str().unwrap_or("Extension").to_owned(),
                        title: q.clone(),
                        input: q,
                        output: String::new(),
                        done: true,
                        failed: false,
                    },
                );
            }
            _ => self.unknown += 1,
        }
    }
}

/// A tool result's text; images become `[image]`.
fn tool_output(c: &Value) -> String {
    if let Some(s) = c.as_str() {
        return s.to_owned();
    }
    c.as_array()
        .into_iter()
        .flatten()
        .map(|b| match b["type"].as_str() {
            Some("text") => b["text"].as_str().unwrap_or("").to_owned(),
            Some("image") => "[image]".to_owned(),
            _ => String::new(),
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// The person's own text from a sent message: herdr-core's `<hc from=..>`
/// note tag and Claude Code's `<pasted_content>` wrapper taken off.
pub fn unwrap_sent(s: &str) -> String {
    let mut t = s.trim().to_owned();
    loop {
        let before = t.clone();
        if let Some(rest) = t.strip_prefix("<pasted_content id=\"") {
            if let (Some(open), Some(close)) = (rest.find("\">"), rest.rfind("</pasted_content")) {
                if open + 2 <= close {
                    t = rest[open + 2..close].trim().to_owned();
                }
            }
        }
        if let Some(rest) = t.strip_prefix("<hc from=\"") {
            if let (Some(open), Some(stripped)) = (rest.find("\">"), rest.strip_suffix("</hc>")) {
                t = stripped[open + 2..].trim().to_owned();
            }
        }
        if t == before {
            return t;
        }
    }
}
