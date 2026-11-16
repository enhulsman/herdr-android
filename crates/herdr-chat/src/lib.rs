//! An agent's session log as the
//! conversation a chat view shows. Claude Code (JSONL per session, tested on
//! 2.1.322-2.1.323) and Codex (rollout JSONL, tested on 0.166.0 and 0.169.0).
//! Both formats are private and change; [`Parser::health`] is the guard the
//! view uses to fall back to the terminal.

pub mod image;
pub mod locate;
pub mod send;
pub mod tail;

use serde_json::Value;
use std::collections::{HashMap, HashSet, VecDeque};

/// A row's identity (design D3): the byte offset of the first log line it
/// was seen with, and its index among the rows that line began.
pub type Key = (u64, u32);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Agent {
    Claude,
    Codex,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Item {
    User {
        text: String,
        images: Vec<Image>,
    },
    /// Markdown as the agent wrote it.
    Assistant {
        text: String,
    },
    /// Tool calls in a row, shown folded as one line.
    Tools {
        calls: Vec<ToolCall>,
    },
    Note {
        text: String,
    },
    /// A note the agent wrote for the person while it works: Claude Code's
    /// thinking shown as updates (its terminal marks them "summary").
    Update {
        text: String,
    },
    /// A message from another agent: a subagent's report (named after the
    /// task it was given) or another session's message.
    AgentMessage {
        from: String,
        text: String,
    },
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
    InLog {
        line: u64,
        n: u32,
        media_type: String,
    },
    /// Short enough that the tail script kept the base64.
    Inline { media_type: String, data: String },
    /// A file on the agent's machine ([`image::FILE_SCRIPT`]), named in the
    /// log line that starts at byte `line`: one path viewed twice is two
    /// images.
    File { path: String, line: u64 },
    /// Emptied with no way back, or a shape this parser does not know.
    Missing,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Health {
    Ok,
    /// Readable, with this many parts it did not know (left out).
    Degraded {
        unknown: u32,
    },
    /// Not this format any more: show the terminal instead.
    Broken {
        reason: String,
    },
}

/// How full the session's context is (design D8): the tokens the last
/// request sent, and the model's window where the log names it (Codex).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Gauge {
    pub used: u64,
    pub window: Option<u64>,
}

impl Gauge {
    /// The share of the window used, rounded; none without a window.
    pub fn percent(&self) -> Option<u32> {
        let w = self.window.filter(|w| *w > 0)?;
        Some(((self.used * 100 + w / 2) / w) as u32)
    }
}

/// This many lines with no message means the format changed.
const NO_MESSAGE_LINES: u32 = 30;

pub struct Parser {
    agent: Agent,
    partial: String,
    /// The byte offset in the log where [`Parser::partial`] starts.
    pos: u64,
    /// The line being parsed: its offset, and the rows it began so far.
    at: u64,
    k: u32,
    items: Vec<Item>,
    /// Per item: the offsets of every line it came from, in the order seen.
    srcs: Vec<Vec<u64>>,
    /// Per item: its own first line and index within it.
    keys: Vec<Key>,
    /// tool id -> (item index, call index)
    calls: HashMap<String, (usize, usize)>,
    /// subagent id -> the task it was started with
    agents: HashMap<String, String>,
    /// Notices and peer messages already shown from a queued command, with
    /// the row they made: the same text may come again as a user record.
    injected: HashMap<String, Option<usize>>,
    /// Codex: per turn id, the row of its latest answer, and the turns past
    /// their `task_complete` (design D7).
    answers: HashMap<String, usize>,
    completed: HashSet<String>,
    last_text_id: Option<String>,
    cwd: String,
    lines: u32,
    recognised: u32,
    unknown: u32,
    broken: Option<String>,
    /// The log's first line has been read (no-message rule).
    start_read: bool,
    /// An open turn: where the log ended when it started, and whether it is
    /// one the per-turn rule skips.
    turn: Option<(u64, bool)>,
    /// Broken by the per-turn rule: where the log ended when it was judged.
    turn_broken: Option<u64>,
    title: Option<String>,
    version: Option<String>,
    gauge: Option<Gauge>,
    /// Claude Code: the texts queued while the agent works, oldest first
    /// (`queue-operation` records; an enqueue without text holds a place).
    queue: VecDeque<String>,
}

impl Parser {
    pub fn new(agent: Agent) -> Self {
        Parser {
            agent,
            partial: String::new(),
            pos: 0,
            at: 0,
            k: 0,
            items: Vec::new(),
            srcs: Vec::new(),
            keys: Vec::new(),
            calls: HashMap::new(),
            agents: HashMap::new(),
            injected: HashMap::new(),
            answers: HashMap::new(),
            completed: HashSet::new(),
            last_text_id: None,
            cwd: String::new(),
            lines: 0,
            recognised: 0,
            unknown: 0,
            broken: None,
            start_read: false,
            turn: None,
            turn_broken: None,
            title: None,
            version: None,
            gauge: None,
            queue: VecDeque::new(),
        }
    }

    /// New text from the log, which continues where the text fed before
    /// ended (the first feed starts at byte 0); a line without its newline
    /// waits for the rest.
    pub fn feed(&mut self, text: &str) {
        if self.pos == 0 {
            self.start_read = true;
        }
        self.partial.push_str(text);
        while let Some(i) = self.partial.find('\n') {
            let line: String = self.partial.drain(..=i).collect();
            let at = self.pos;
            self.pos += line.len() as u64;
            let line = line.trim_end();
            if !line.is_empty() {
                self.at = at;
                self.k = 0;
                self.line(line);
            }
        }
    }

    /// Whole lines as the tail script gives them: `offsets` holds where each
    /// line of `text` starts in the log ([`tail::Chunk`]).
    pub fn feed_lines(&mut self, text: &str, offsets: &[u64]) {
        if offsets.first() == Some(&0) {
            self.start_read = true;
        }
        for (line, &at) in text.lines().zip(offsets) {
            self.pos = at + line.len() as u64 + 1;
            let line = line.trim_end();
            if !line.is_empty() {
                self.at = at;
                self.k = 0;
                self.line(line);
            }
        }
    }

    pub fn items(&self) -> &[Item] {
        &self.items
    }

    /// Per item: the offsets of every log line it came from.
    pub fn sources(&self) -> &[Vec<u64>] {
        &self.srcs
    }

    /// Per item: the key of its own first line in this parse. A chat keeps
    /// the keys rows had when first seen: [`keep_keys`].
    pub fn first_keys(&self) -> &[Key] {
        &self.keys
    }

    pub fn title(&self) -> Option<&str> {
        self.title.as_deref()
    }

    pub fn version(&self) -> Option<&str> {
        self.version.as_deref()
    }

    /// The context gauge from the latest count read; none before one.
    /// Claude Code: the texts typed while the agent worked that it has not
    /// taken in yet, oldest first (design D10). Empty for Codex.
    pub fn queued(&self) -> Vec<String> {
        self.queue
            .iter()
            .filter(|t| !t.is_empty())
            .cloned()
            .collect()
    }

    pub fn gauge(&self) -> Option<Gauge> {
        self.gauge
    }

    /// The agent started working while the chat was open (design D6).
    /// `skip`: the turn was started by a `!` or `/` command sent from the
    /// chat. A turn starting before the last one was judged joins it.
    pub fn turn_started(&mut self, skip: bool) {
        let (mark, skipped) = self.turn.unwrap_or((self.pos, true));
        self.turn = Some((mark, skipped && skip));
    }

    /// The open turn ended, and the lines written in the 5 s after it have
    /// been read: a turn that changed no row at all means the agent's
    /// records are no longer read, and the log is Broken until the agent
    /// produces a row again.
    pub fn turn_ended(&mut self) {
        let Some((mark, skip)) = self.turn.take() else {
            return;
        };
        let changed = self.srcs.iter().flatten().any(|&at| at >= mark);
        if !skip && !changed {
            self.turn_broken.get_or_insert(self.pos);
        }
    }

    /// A new parse of the same log (a read-back) keeps the per-turn state of
    /// the parse it replaces; call before feeding it.
    pub fn carry_from(&mut self, old: &Parser) {
        self.turn = old.turn;
        self.turn_broken = old.turn_broken;
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
        // message at all; meta records alone are never a conversation. Only
        // judged from the start: the last 2 MB may be bookkeeping only.
        if self.start_read && self.lines >= NO_MESSAGE_LINES && self.items.is_empty() {
            return Health::Broken {
                reason: format!("no messages in {} lines", self.lines),
            };
        }
        if self.turn_broken.is_some() {
            return Health::Broken {
                reason: "a turn ended with nothing readable".into(),
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
        // One bad line (a write cut short) is left out; a log that is mostly
        // not JSON fails the format check in health().
        let Ok(v) = serde_json::from_str::<Value>(line) else {
            self.unknown += 1;
            return;
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
        self.new_row(item);
    }

    fn new_row(&mut self, item: Item) {
        if matches!(
            item,
            Item::Assistant { .. } | Item::Note { .. } | Item::Update { .. }
        ) {
            self.agent_wrote();
        }
        self.items.push(item);
        self.srcs.push(vec![self.at]);
        self.keys.push((self.at, self.k));
        self.k += 1;
    }

    /// The line being parsed holds something the agent produced: it clears
    /// the per-turn Broken when written after it.
    fn agent_wrote(&mut self) {
        if self.turn_broken.is_some_and(|b| self.at >= b) {
            self.turn_broken = None;
        }
    }

    /// The line being parsed changed row `i` too.
    fn touch(&mut self, i: usize) {
        if let Some(s) = self.srcs.get_mut(i)
            && !s.contains(&self.at)
        {
            s.push(self.at);
        }
    }

    /// A tool call; `own` is false for a `!` command the person ran.
    fn add_call(&mut self, id: Option<String>, call: ToolCall, own: bool) {
        self.last_text_id = None;
        if own {
            self.agent_wrote();
        }
        if matches!(self.items.last(), Some(Item::Tools { .. })) {
            self.touch(self.items.len() - 1);
        } else {
            self.new_row(Item::Tools { calls: Vec::new() });
        }
        let at = self.items.len() - 1;
        if let Some(Item::Tools { calls }) = self.items.last_mut() {
            calls.push(call);
            if let Some(id) = id {
                self.calls.insert(id, (at, calls.len() - 1));
            }
        }
    }

    /// An agent's call written after its turn ended, placed before that
    /// turn's answer (row `answer`): folded into a tool row just before it,
    /// else a new row there. Rows after it move down one.
    fn add_call_before(&mut self, answer: usize, call: ToolCall) {
        self.agent_wrote();
        if let Some(Item::Tools { calls }) =
            answer.checked_sub(1).and_then(|i| self.items.get_mut(i))
        {
            calls.push(call);
            self.touch(answer - 1);
            return;
        }
        self.items.insert(answer, Item::Tools { calls: vec![call] });
        self.srcs.insert(answer, vec![self.at]);
        self.keys.insert(answer, (self.at, self.k));
        self.k += 1;
        let shift = |i: &mut usize| {
            if *i >= answer {
                *i += 1;
            }
        };
        self.answers.values_mut().for_each(shift);
        self.calls.values_mut().for_each(|(i, _)| shift(i));
        self.injected.values_mut().flatten().for_each(shift);
    }

    /// The call with this id, its row marked as changed by this line.
    fn call_mut(&mut self, id: &str) -> Option<&mut ToolCall> {
        let (i, c) = *self.calls.get(id)?;
        self.touch(i);
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
            || matches!(
                kind,
                "user" | "assistant" | "system" | "file-history-snapshot"
            )
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
            "queue-operation" => {
                let text = v["content"].as_str().map(unwrap_sent);
                match (v["operation"].as_str(), text) {
                    (Some("enqueue"), t) => self.queue.push_back(t.unwrap_or_default()),
                    (Some("dequeue"), _) => {
                        self.queue.pop_front();
                    }
                    (Some("remove"), Some(t)) => {
                        if let Some(i) = self.queue.iter().position(|q| *q == t) {
                            self.queue.remove(i);
                        }
                    }
                    (Some("popAll"), _) => self.queue.clear(),
                    _ => {}
                }
                return;
            }
            "attachment" => {
                let a = &v["attachment"];
                if a["type"] == "queued_command" && v["isSidechain"] != true {
                    self.claude_queued(a);
                }
                return;
            }
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
                // A slash command and its output (`/usage` writes both here,
                // once its dialog closes).
                if v["subtype"] == "local_command" && v["isSidechain"] != true {
                    self.claude_user(&v["content"]);
                }
                return;
            }
            _ => return,
        }
        let msg = &v["message"];
        let content = &msg["content"];
        if msg["role"].as_str().is_none() || !(content.is_string() || content.is_array()) {
            return self.break_with(format!(
                "line {}: message without role or content",
                self.lines
            ));
        }
        if v["isSidechain"] == true {
            return;
        }
        if kind == "assistant" {
            // Input plus cache tokens: what the request sent. A record with
            // none (an error the harness wrote) is not a request.
            let u = &msg["usage"];
            let used: u64 = [
                "input_tokens",
                "cache_creation_input_tokens",
                "cache_read_input_tokens",
            ]
            .iter()
            .filter_map(|k| u[k].as_u64())
            .sum();
            if used > 0 {
                self.gauge = Some(Gauge { used, window: None });
            }
        }
        if let Some(row) = content
            .as_str()
            .and_then(|t| self.injected.get(t.trim()).copied())
        {
            if let Some(i) = row {
                self.touch(i);
            }
            return;
        }
        if let Some(text) = content
            .as_str()
            .filter(|t| kind == "user" && t.contains("<agent-message from=\""))
        {
            return self.claude_agent_message(text);
        }
        // The summary a compaction writes is not the person's; the boundary
        // already left a note.
        if v["isMeta"] == true || v["isCompactSummary"] == true {
            return;
        }
        if kind == "assistant" {
            self.claude_assistant(msg, v["thinkingDisplay"] == "updates");
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

    fn claude_assistant(&mut self, msg: &Value, updates: bool) {
        let id = msg["id"].as_str().map(str::to_owned);
        for b in msg["content"].as_array().into_iter().flatten() {
            match b["type"].as_str().unwrap_or("") {
                // Reasoning stays hidden; in the updates display Claude Code
                // keeps thinking empty but for the notes it shows the person.
                "thinking" => {
                    let t = b["thinking"].as_str().unwrap_or("").trim();
                    if updates && !t.is_empty() {
                        self.push(Item::Update { text: t.to_owned() });
                    }
                }
                "redacted_thinking" => {}
                "text" => {
                    let t = b["text"].as_str().unwrap_or("").trim();
                    if t.is_empty() {
                        continue;
                    }
                    match (self.items.last_mut(), &self.last_text_id) {
                        (Some(Item::Assistant { text }), Some(last))
                            if Some(last) == id.as_ref() =>
                        {
                            text.push_str("\n\n");
                            text.push_str(t);
                            self.touch(self.items.len() - 1);
                            self.agent_wrote();
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
                        true,
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
        let Some(rest) = s.split("<agent-message from=\"").nth(1) else {
            return;
        };
        let Some((from, rest)) = rest.split_once("\">") else {
            return;
        };
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
        let from = self
            .agents
            .get(from)
            .cloned()
            .unwrap_or_else(|| from.to_owned());
        self.push(Item::AgentMessage {
            from,
            text: text.trim().to_owned(),
        });
    }

    /// A message that arrived while the agent worked: Claude Code writes the
    /// person's only as this record, and notices and peer messages often too.
    fn claude_queued(&mut self, a: &Value) {
        let prompt = &a["prompt"];
        let kind = a["origin"]["kind"].as_str().unwrap_or("");
        match (kind, prompt.as_str()) {
            ("human", Some(_)) | ("human", None) if prompt.is_string() || prompt.is_array() => {
                self.claude_user(prompt)
            }
            (_, Some(s)) => {
                let t = s.trim();
                if let Some(row) = self.injected.get(t).copied() {
                    if let Some(i) = row {
                        self.touch(i);
                    }
                    return;
                }
                let before = self.items.len();
                if t.contains("<agent-message from=\"") {
                    self.claude_agent_message(t);
                } else if t.starts_with("<task-notification>") {
                    self.claude_user(prompt);
                }
                let row = (self.items.len() > before).then_some(before);
                self.injected.insert(t.to_owned(), row);
            }
            _ => {}
        }
    }

    fn claude_user(&mut self, content: &Value) {
        if let Some(s) = content.as_str() {
            let t = s.trim_start();
            if t.starts_with("[Request interrupted by user") {
                self.push(Item::Note {
                    text: "Interrupted".into(),
                });
                return;
            }
            if t.starts_with("<task-notification>") {
                let summary =
                    between(t, "<summary>", "</summary>").unwrap_or("A background task finished");
                self.push(Item::Note {
                    text: summary.trim().to_owned(),
                });
                return;
            }
            if let Some(cmd) = between(t, "<bash-input>", "</bash-input>") {
                let cmd = cmd.trim().to_owned();
                // The person typed it: their message, then the command and its output.
                self.push(Item::User {
                    text: format!("!{cmd}"),
                    images: vec![],
                });
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
                    false,
                );
                return;
            }
            if t.starts_with("<bash-stdout>") || t.starts_with("<bash-stderr>") {
                let out = between(t, "<bash-stdout>", "</bash-stdout>")
                    .unwrap_or("")
                    .trim_end();
                let err = between(t, "<bash-stderr>", "</bash-stderr>")
                    .unwrap_or("")
                    .trim_end();
                if let Some(call) = self.call_mut(BASH) {
                    call.output = [out, err]
                        .iter()
                        .filter(|x| !x.is_empty())
                        .copied()
                        .collect::<Vec<_>>()
                        .join("\n");
                    call.failed = out.is_empty() && !err.is_empty();
                    call.done = true;
                }
                self.calls.remove(BASH);
                return;
            }
            if let Some(rest) = t.strip_prefix("<command-name>") {
                let cmd = rest.split("</command-name>").next().unwrap_or("").trim();
                let args = between(t, "<command-args>", "</command-args>")
                    .unwrap_or("")
                    .trim();
                let text = if args.is_empty() {
                    cmd.to_owned()
                } else {
                    format!("{cmd} {args}")
                };
                self.push(Item::User {
                    text,
                    images: vec![],
                });
            } else if let Some(out) =
                between(t, "<local-command-stdout>", "</local-command-stdout>")
            {
                let out = strip_ansi(out);
                if !out.trim().is_empty() {
                    self.push(Item::Note {
                        text: out.trim().to_owned(),
                    });
                }
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
                "text"
                    if b["text"].as_str().is_some_and(|t| {
                        t.trim_start().starts_with("[Request interrupted by user")
                    }) =>
                {
                    self.push(Item::Note {
                        text: "Interrupted".into(),
                    });
                }
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
        let Some(kind) = v["type"].as_str() else {
            return;
        };
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
            // Not seen in a log yet (task 2.8 checks it): assumed from Codex's
            // own event name.
            "event_msg" if p["type"] == "turn_aborted" => {
                self.push(Item::Note {
                    text: "Interrupted".into(),
                });
            }
            "event_msg" if p["type"] == "token_count" => {
                let info = &p["info"];
                if let Some(used) = info["last_token_usage"]["input_tokens"].as_u64() {
                    let window = info["model_context_window"].as_u64();
                    self.gauge = Some(Gauge { used, window });
                }
            }
            "event_msg" if p["type"] == "task_complete" => {
                if let Some(turn) = p["turn_id"].as_str() {
                    self.completed.insert(turn.to_owned());
                }
            }
            "event_msg" if p["type"] == "item_completed" => {
                let item = &p["item"];
                match item["type"].as_str() {
                    Some(t) => self.codex_item(t, item, p["turn_id"].as_str()),
                    None => self.break_with(format!("line {}: item without type", self.lines)),
                }
            }
            _ => {}
        }
    }

    fn codex_item(&mut self, t: &str, item: &Value, turn: Option<&str>) {
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
                let (shown, mut sent) = leading_image_paths(&text(), self.at);
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
            "AgentMessage" => {
                if let Some(turn) = turn {
                    self.answers.insert(turn.to_owned(), self.items.len());
                }
                self.push(Item::Assistant { text: text() })
            }
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
                let own = item["source"] != "user_shell";
                let call = ToolCall {
                    name: "Shell".into(),
                    title: cmd.lines().next().unwrap_or("").to_owned(),
                    input: cmd.clone(),
                    output: item["aggregated_output"].as_str().unwrap_or("").to_owned(),
                    done: true,
                    failed: item["exit_code"].as_i64().is_some_and(|c| c != 0),
                    images: vec![],
                };
                // A command left running, written after its turn ended.
                let late = turn
                    .filter(|t| own && self.completed.contains(*t))
                    .and_then(|t| self.answers.get(t).copied());
                if let Some(answer) = late {
                    return self.add_call_before(answer, call);
                }
                // A `!` command the person ran: their message, then the command.
                if !own {
                    self.push(Item::User {
                        text: format!("!{cmd}"),
                        images: vec![],
                    });
                }
                self.add_call(None, call, own);
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
                    true,
                );
            }
            "ImageView" => {
                // A path, or (0.169) a file URL.
                let raw = item["path"].as_str().unwrap_or("");
                let path = raw
                    .strip_prefix("file://")
                    .map(percent_decode)
                    .unwrap_or_else(|| raw.to_owned());
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
                        images: vec![Image::File {
                            path,
                            line: self.at,
                        }],
                    },
                    true,
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
                    true,
                );
            }
            _ => self.unknown += 1,
        }
    }
}

/// The key a `!` shell command waits under for its output record.
const BASH: &str = "!bash";

/// `s` without terminal escape sequences (a command's output can be coloured).
fn strip_ansi(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\u{1b}' {
            if chars.peek() == Some(&'[') {
                chars.next();
                // Parameters and intermediates, then one final byte in @..~.
                for c in chars.by_ref() {
                    if ('@'..='~').contains(&c) {
                        break;
                    }
                }
            }
            continue;
        }
        out.push(c);
    }
    out
}

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
        .filter(|&b| b["type"] == "text")
        .map(|b| b["text"].as_str().unwrap_or(""))
        .collect::<Vec<_>>()
        .join("\n")
}

/// A Claude Code `image` block: base64 kept inline, or the tail's reference.
fn claude_image(b: &Value) -> Image {
    let src = &b["source"];
    let media_type = src["media_type"].as_str().unwrap_or("image/png").to_owned();
    let data = src["data"].as_str().unwrap_or("");
    if let Some((line, n)) = data.strip_prefix('@').and_then(|r| r.split_once(':'))
        && let (Ok(line), Ok(n)) = (line.parse(), n.parse())
    {
        return Image::InLog {
            line,
            n,
            media_type,
        };
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
fn leading_image_paths(text: &str, at: u64) -> (String, Vec<Image>) {
    let mut images = Vec::new();
    let mut rest = text;
    loop {
        let (line, after) = rest.split_once('\n').unwrap_or((rest, ""));
        let l = line.trim().to_ascii_lowercase();
        let image = line.trim().starts_with('/')
            && [".png", ".jpg", ".jpeg", ".gif", ".webp"]
                .iter()
                .any(|e| l.ends_with(e));
        if !image {
            return (rest.trim().to_owned(), images);
        }
        images.push(Image::File {
            path: line.trim().to_owned(),
            line: at,
        });
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
        if b[i] == b'%'
            && i + 2 < b.len()
            && let (Some(h), Some(l)) = (hex(b[i + 1]), hex(b[i + 2]))
        {
            out.push((h * 16 + l) as u8);
            i += 3;
            continue;
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
        if let Some(rest) = t.strip_prefix("<pasted_content id=\"")
            && let (Some(open), Some(close)) = (rest.find("\">"), rest.rfind("</pasted_content"))
            && open + 2 <= close
        {
            t = rest[open + 2..close].trim().to_owned();
        }
        if let Some(rest) = t.strip_prefix("<hc from=\"")
            && let (Some(open), Some(stripped)) = (rest.find("\">"), rest.strip_suffix("</hc>"))
        {
            t = stripped[open + 2..].trim().to_owned();
        }
        if t == before {
            return t;
        }
    }
}

/// The keys for rows parsed again after a read-back (design D3), given the
/// keys the held rows had (`held`, in row order) and the new parse's
/// [`Parser::first_keys`] and [`Parser::sources`]. A held key goes to the row
/// that begins with it, else to the first row containing its line; each held
/// key to one row only. A row with none keys itself by its first line.
pub fn keep_keys(held: &[Key], first: &[Key], sources: &[Vec<u64>]) -> Vec<Key> {
    let held_set: HashSet<Key> = held.iter().copied().collect();
    let mut used: HashSet<Key> = HashSet::new();
    let mut got: Vec<Option<Key>> = vec![None; first.len()];
    for (i, k) in first.iter().enumerate() {
        if held_set.contains(k) && used.insert(*k) {
            got[i] = Some(*k);
        }
    }
    for (i, slot) in got.iter_mut().enumerate() {
        if slot.is_none()
            && let Some(k) = held
                .iter()
                .find(|k| !used.contains(*k) && sources[i].contains(&k.0))
        {
            used.insert(*k);
            *slot = Some(*k);
        }
    }
    got.into_iter()
        .zip(first)
        .map(|(slot, own)| {
            slot.unwrap_or_else(|| {
                // Never two rows with one key: a row whose own key a held row
                // already took moves to a free index of its line.
                let mut k = *own;
                while used.contains(&k) {
                    k.1 += 1 << 16;
                }
                used.insert(k);
                k
            })
        })
        .collect()
}
