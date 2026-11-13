//! Spike for issue 17 (never merged): an agent's session log as the
//! conversation a chat view shows. Claude Code (JSONL per session, tested on
//! 2.1.322-2.1.323) and Codex (rollout JSONL, tested on 0.166.0 and 0.169.0).
//! Both formats are private and change; [`Parser::health`] is the guard the
//! view uses to fall back to the terminal.

pub mod image;
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
    User { text: String, images: Vec<Image> },
    /// Markdown as the agent wrote it.
    Assistant { text: String },
    /// Tool calls in a row, shown folded as one line.
    Tools { calls: Vec<ToolCall> },
    Note { text: String },
    /// A message from another agent: a subagent's report (named after the
    /// task it was given) or another session's message.
    AgentMessage { from: String, text: String },
}

#[derive(Clone, Debug, PartialEq)]
pub struct ToolCall {
    pub name: String,
    pub title: String,
    pub input: String,
    pub output: String,
    pub done: bool,
    pub failed: bool,
    /// Images in the result (a screenshot the agent read or viewed).
    pub images: Vec<Image>,
}

/// Where an image's bytes are; fetched only when the view shows it.
#[derive(Clone, Debug, PartialEq)]
pub enum Image {
    /// Base64 in the log, left on the desk by the tail script: the line that
    /// starts at byte `line`, its `n`th base64 field ([`image::LOG_SCRIPT`]).
    InLog { line: u64, n: u32, media_type: String },
    /// Short enough that the tail script kept the base64.
    Inline { media_type: String, data: String },
    /// A file on the agent's machine ([`image::FILE_SCRIPT`]).
    File { path: String },
    /// Emptied with no way back, or a shape this parser does not know.
    Missing,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Health {
    Ok,
    /// Readable, with this many parts it did not know (left out).
    Degraded { unknown: u32 },
    /// Not this format any more: show the terminal instead.
    Broken { reason: String },
}

/// This many lines with no message means the format changed.
const NO_MESSAGE_LINES: u32 = 30;

pub struct Parser {
    agent: Agent,
    partial: String,
    items: Vec<Item>,
    /// tool id -> (item index, call index)
    calls: HashMap<String, (usize, usize)>,
    /// subagent id -> the task it was started with
    agents: HashMap<String, String>,
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
            agents: HashMap::new(),
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
        // A renamed message record leaves lines this agent writes but no
        // message at all; meta records alone are never a conversation.
        if self.lines >= NO_MESSAGE_LINES && self.items.is_empty() {
            return Health::Broken {
                reason: format!("no messages in {} lines", self.lines),
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
        if v["isSidechain"] == true {
            return;
        }
        if let Some(text) = content.as_str().filter(|t| kind == "user" && t.contains("<agent-message from=\"")) {
            return self.claude_agent_message(text);
        }
        if v["isMeta"] == true {
            return;
        }
        if kind == "assistant" {
            self.claude_assistant(msg);
        } else {
            self.claude_user(content);
            // A subagent's id, to name its report when it comes back.
            if let Some(agent) = v["toolUseResult"]["agentId"].as_str() {
                for b in content.as_array().into_iter().flatten() {
                    if let Some(call) = b["tool_use_id"].as_str().and_then(|id| self.call_mut(id)) {
                        let task = call.title.clone();
                        self.agents.insert(agent.to_owned(), task);
                    }
                }
            }
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
                            images: vec![],
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

    fn claude_agent_message(&mut self, s: &str) {
        let Some(rest) = s.split("<agent-message from=\"").nth(1) else { return };
        let Some((from, rest)) = rest.split_once("\">") else { return };
        let body = rest.split("</agent-message>").next().unwrap_or("");
        let text = match body.split_once("The report follows:\n") {
            // A subagent's hand-back: the harness indents each line by two.
            Some((_, report)) => report
                .lines()
                .map(|l| l.strip_prefix("  ").unwrap_or(l))
                .collect::<Vec<_>>()
                .join("\n"),
            None => body.to_owned(),
        };
        let from = self.agents.get(from).cloned().unwrap_or_else(|| from.to_owned());
        self.push(Item::AgentMessage {
            from,
            text: text.trim().to_owned(),
        });
    }

    fn claude_user(&mut self, content: &Value) {
        if let Some(s) = content.as_str() {
            let t = s.trim_start();
            if t.starts_with("<task-notification>") {
                let summary = between(t, "<summary>", "</summary>").unwrap_or("A background task finished");
                self.push(Item::Note { text: summary.trim().to_owned() });
                return;
            }
            if let Some(cmd) = between(t, "<bash-input>", "</bash-input>") {
                let cmd = cmd.trim().to_owned();
                self.add_call(
                    Some(BASH.into()),
                    ToolCall {
                        name: "Shell".into(),
                        title: format!("! {}", cmd.lines().next().unwrap_or("")),
                        input: cmd,
                        output: String::new(),
                        done: false,
                        failed: false,
                        images: vec![],
                    },
                );
                return;
            }
            if t.starts_with("<bash-stdout>") || t.starts_with("<bash-stderr>") {
                let out = between(t, "<bash-stdout>", "</bash-stdout>").unwrap_or("").trim_end();
                let err = between(t, "<bash-stderr>", "</bash-stderr>").unwrap_or("").trim_end();
                if let Some(call) = self.call_mut(BASH) {
                    call.output = [out, err].iter().filter(|x| !x.is_empty()).copied().collect::<Vec<_>>().join("\n");
                    call.failed = out.is_empty() && !err.is_empty();
                    call.done = true;
                }
                self.calls.remove(BASH);
                return;
            }
            if let Some(rest) = t.strip_prefix("<command-name>") {
                let cmd = rest.split("</command-name>").next().unwrap_or("").trim();
                self.push(Item::Note { text: cmd.into() });
            } else if !t.starts_with("<local-command") {
                self.push(Item::User {
                    text: unwrap_sent(s),
                    images: vec![],
                });
            }
            return;
        }
        let mut texts = Vec::new();
        let mut images = Vec::new();
        for b in content.as_array().into_iter().flatten() {
            match b["type"].as_str().unwrap_or("") {
                "text" => {
                    let t = strip_image_marks(&unwrap_sent(b["text"].as_str().unwrap_or("")));
                    if !t.is_empty() {
                        texts.push(t);
                    }
                }
                "image" => images.push(claude_image(b)),
                "tool_result" => {
                    let out = tool_output(&b["content"]);
                    let shots = b["content"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter(|x| x["type"] == "image")
                        .map(claude_image)
                        .collect();
                    let failed = b["is_error"] == true;
                    if let Some(call) = b["tool_use_id"].as_str().and_then(|id| self.call_mut(id)) {
                        call.output = out;
                        call.failed = failed;
                        call.done = true;
                        call.images = shots;
                    }
                }
                _ => self.unknown += 1,
            }
        }
        if !texts.is_empty() || !images.is_empty() {
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
                let (shown, mut sent) = leading_image_paths(&text());
                // Not seen in a log yet: shown as an image that cannot be fetched.
                let images = item["content"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter(|c| c["type"].as_str().is_some_and(|k| k.contains("image")))
                    .map(|_| Image::Missing)
                    .collect::<Vec<_>>();
                sent.extend(images);
                self.push(Item::User {
                    text: shown,
                    images: sent,
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
                        images: vec![],
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
                        images: vec![],
                    },
                );
            }
            "ImageView" => {
                // A path, or (0.169) a file URL.
                let raw = item["path"].as_str().unwrap_or("");
                let path = raw.strip_prefix("file://").map(percent_decode).unwrap_or_else(|| raw.to_owned());
                let shown = self.relative(&path);
                self.add_call(
                    None,
                    ToolCall {
                        name: "View image".into(),
                        title: format!("View {shown}"),
                        input: shown,
                        output: String::new(),
                        done: true,
                        failed: false,
                        images: vec![Image::File { path }],
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
                        images: vec![],
                    },
                );
            }
            _ => self.unknown += 1,
        }
    }
}

/// The key a `!` shell command waits under for its output record.
const BASH: &str = "!bash";

/// The text between the first `open` and the `close` after it.
fn between<'a>(s: &'a str, open: &str, close: &str) -> Option<&'a str> {
    let rest = &s[s.find(open)? + open.len()..];
    Some(&rest[..rest.find(close)?])
}

/// A tool result's text (its images are taken separately).
fn tool_output(c: &Value) -> String {
    if let Some(s) = c.as_str() {
        return s.to_owned();
    }
    c.as_array()
        .into_iter()
        .flatten()
        .filter_map(|b| (b["type"] == "text").then(|| b["text"].as_str().unwrap_or("")))
        .collect::<Vec<_>>()
        .join("\n")
}

/// A Claude Code `image` block: base64 kept inline, or the tail's reference.
fn claude_image(b: &Value) -> Image {
    let src = &b["source"];
    let media_type = src["media_type"].as_str().unwrap_or("image/png").to_owned();
    let data = src["data"].as_str().unwrap_or("");
    if let Some((line, n)) = data.strip_prefix('@').and_then(|r| r.split_once(':')) {
        if let (Ok(line), Ok(n)) = (line.parse(), n.parse()) {
            return Image::InLog { line, n, media_type };
        }
    }
    if data.is_empty() || src["type"] != "base64" {
        return Image::Missing;
    }
    Image::Inline {
        media_type,
        data: data.to_owned(),
    }
}

/// Claude Code's `[Image #3]` placeholders, which stand for the images shown
/// beside the text.
fn strip_image_marks(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find("[Image #") {
        let after = &rest[i + 8..];
        let digits = after.bytes().take_while(u8::is_ascii_digit).count();
        if digits > 0 && after[digits..].starts_with(']') {
            out.push_str(&rest[..i]);
            rest = &after[digits + 1..];
        } else {
            out.push_str(&rest[..i + 8]);
            rest = after;
        }
    }
    out.push_str(rest);
    out.trim().to_owned()
}

/// Lines at the start of a message that are absolute paths to images (the
/// phone puts each attached image's path before the text): taken out of the
/// text and returned as images.
fn leading_image_paths(text: &str) -> (String, Vec<Image>) {
    let mut images = Vec::new();
    let mut rest = text;
    loop {
        let (line, after) = rest.split_once('\n').unwrap_or((rest, ""));
        let l = line.trim().to_ascii_lowercase();
        let image = line.trim().starts_with('/')
            && [".png", ".jpg", ".jpeg", ".gif", ".webp"].iter().any(|e| l.ends_with(e));
        if !image {
            return (rest.trim().to_owned(), images);
        }
        images.push(Image::File { path: line.trim().to_owned() });
        rest = after;
    }
}

/// `%20` and friends in a file URL's path.
fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        let hex = |c: u8| (c as char).to_digit(16);
        if b[i] == b'%' && i + 2 < b.len() {
            if let (Some(h), Some(l)) = (hex(b[i + 1]), hex(b[i + 2])) {
                out.push((h * 16 + l) as u8);
                i += 3;
                continue;
            }
        }
        out.push(b[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
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
