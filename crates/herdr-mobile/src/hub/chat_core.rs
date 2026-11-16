//! The chat's state without any I/O (chat-view design D3, D5, D6, D12): what
//! to look up and read next, what a lookup or a read changes, and what the
//! screen shows. [`super::chat::Chat`] does the calls around it; the tests
//! drive it with made-up terminals, lookups and reads.

use herdr_chat::locate::{EmptyGuard, Lookup};
use herdr_chat::tail::Chunk;
use herdr_chat::{Agent, Gauge, Health, Item, Key, Parser, keep_keys};
use std::time::{Duration, Instant};

/// The first open reads at most this much of the log's end; so does a
/// return after more than this was written.
pub(crate) const FIRST_BUDGET: u64 = 2_000_000;
/// A read back starts this far before the oldest line held, doubled while a
/// single line is longer.
pub(crate) const OLDER_BUDGET: u64 = 2_000_000;
/// `machine.exec` keeps the first 8 MB of output: no read spans more.
pub(crate) const READ_MAX: u64 = 7_500_000;
/// With the same process in front, the log is looked up again this often.
pub(crate) const RELOCATE: Duration = Duration::from_secs(10);
/// After a `/` command, every poll looks the log up for this long; a turn
/// starting this soon after a `/` or `!` command is not held to the
/// per-turn rule; and a turn's end is judged on the lines read this long
/// after it.
pub(crate) const RELOOK: Duration = Duration::from_secs(5);
pub(crate) const TURN_GRACE: Duration = Duration::from_secs(5);
/// Chats held for the terminals viewed most recently.
pub(crate) const HELD: usize = 8;

pub(crate) const FAILED_READ: &str = "Can't read new messages; retrying";
pub(crate) const SKIPPED: &str = "A message too large to show was skipped";
pub(crate) const OLDER_FAILED: &str = "Earlier messages could not be loaded";
/// [`herdr_chat::locate::lookup`]'s reason when the desk did not answer.
const NO_ANSWER: &str = "the desk did not answer";

/// What `terminal.get` said about the terminal.
#[derive(Clone, Debug, Default)]
pub(crate) struct Term {
    pub agent: Option<Agent>,
    pub pid: Option<i64>,
    pub status: Option<String>,
}

/// One run of the tail script: `sh -c SCRIPT sh <path> <start> [<end>]`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Read {
    pub path: String,
    pub start: String,
    pub end: Option<u64>,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Row {
    pub key: Key,
    pub item: Item,
}

/// The lookup result as the screen sees it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    Found,
    Empty,
    Missed,
    None,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct View {
    pub found: Kind,
    /// The agent whose log is held, if any.
    pub agent: Option<Agent>,
    pub rows: Vec<Row>,
    pub health: Health,
    /// A read that failed, shown over the chat until one succeeds.
    pub problem: Option<String>,
    /// The agent whose log is held left the terminal.
    pub exited: bool,
    /// Older rows exist: a read back may fetch them.
    pub cut: bool,
    pub gauge: Option<Gauge>,
    /// Claude Code: texts typed while the agent works, not taken in yet.
    pub queued: Vec<String>,
    pub title: Option<String>,
    pub version: Option<String>,
}

/// The log held for one session.
struct Log {
    agent: Agent,
    session: String,
    path: Option<String>,
    /// The lines held, each with its byte offset and its newline.
    lines: Vec<(u64, String)>,
    from: u64,
    next: Option<u64>,
    parser: Parser,
    keys: Vec<Key>,
    /// Records over the read limit, by offset: each shows as a note.
    skipped: Vec<u64>,
    older_budget: u64,
    older_blocked: bool,
    /// Stepping back through a record over [`READ_MAX`] while reading back:
    /// where the next window ends (inside that record).
    older_scan: Option<u64>,
}

#[derive(Default)]
pub(crate) struct ChatCore {
    log: Option<Log>,
    lookup: Option<Lookup>,
    guard: EmptyGuard,
    located: Option<(Option<i64>, Instant)>,
    relook_until: Option<Instant>,
    command_sent: Option<Instant>,
    problem: Option<String>,
    last_status: Option<String>,
    turn_end: Option<Instant>,
    /// Left and not back yet: look up and check the log's size first.
    returned: bool,
    probe_size: bool,
    /// The status the terminal view saw last, for Empty's first turn only.
    watch_status: Option<String>,
}

impl Log {
    fn new(agent: Agent, session: String, path: Option<String>) -> Self {
        Log {
            agent,
            session,
            path,
            lines: Vec::new(),
            from: 0,
            next: None,
            parser: Parser::new(agent),
            keys: Vec::new(),
            skipped: Vec::new(),
            older_budget: OLDER_BUDGET,
            older_blocked: false,
            older_scan: None,
        }
    }

    /// Parses every line held again in one parser (after lines were put in
    /// front or dropped), keeping the held rows' keys and the turn state.
    fn reparse(&mut self) {
        let text: String = self.lines.iter().map(|(_, l)| l.as_str()).collect();
        let offsets: Vec<u64> = self.lines.iter().map(|(at, _)| *at).collect();
        let mut p = Parser::new(self.agent);
        p.carry_from(&self.parser);
        p.feed_lines(&text, &offsets);
        self.keys = keep_keys(&self.keys, p.first_keys(), p.sources());
        self.parser = p;
    }

    fn take(&mut self, c: &Chunk) {
        if self.next.is_none() {
            self.from = c.from;
        }
        self.parser.feed_lines(&c.text, &c.offsets);
        let mut rest = c.text.as_str();
        for at in &c.offsets {
            let n = rest.find('\n').map_or(rest.len(), |i| i + 1);
            self.lines.push((*at, rest[..n].to_owned()));
            rest = &rest[n..];
        }
        if let Some((at, _)) = c.skipped {
            self.skipped.push(at);
        }
        self.next = Some(c.next);
        self.keys = keep_keys(&self.keys, self.parser.first_keys(), self.parser.sources());
    }
}

impl ChatCore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Whether this poll runs the lookup script first: on the first poll and
    /// on return, when another process is in front, every [`RELOCATE`], on
    /// every poll for [`RELOOK`] after a `/` command, and on an event while
    /// Empty or Missed with an agent in front.
    pub fn needs_lookup(&self, t: &Term, event: bool, now: Instant) -> bool {
        let Some((pid, at)) = self.located else { return true };
        let waiting = matches!(self.lookup, Some(Lookup::Empty { .. } | Lookup::Missed(_)));
        self.returned
            || pid != t.pid
            || now.duration_since(at) >= RELOCATE
            || self.relook_until.is_some_and(|u| now < u)
            || (event && waiting && t.agent.is_some())
    }

    /// The lookup's result; `None` when the desk did not answer.
    pub fn take_lookup(&mut self, t: &Term, l: Option<Lookup>, now: Instant) {
        self.located = Some((t.pid, now));
        self.returned = false;
        let l = match l {
            Some(Lookup::Missed(why)) if why == NO_ANSWER => None,
            other => other,
        };
        let Some(l) = l else {
            if self.log.is_some() {
                self.problem = Some(FAILED_READ.into());
            } else {
                self.lookup = Some(Lookup::Missed(NO_ANSWER));
            }
            return;
        };
        let l = self.guard.apply(l);
        match &l {
            Lookup::Found { session, .. } | Lookup::Empty { session } => {
                let path = match &l {
                    Lookup::Found { path, .. } => Some(path.clone()),
                    _ => None,
                };
                let agent = t.agent.or(self.log.as_ref().map(|o| o.agent)).unwrap_or(Agent::Claude);
                let same = self.log.as_ref().is_some_and(|o| &o.session == session && o.agent == agent);
                if !same {
                    self.log = Some(Log::new(agent, session.clone(), path));
                    self.problem = None;
                    self.probe_size = false;
                } else if let Some(o) = self.log.as_mut().filter(|o| o.path.is_none()) {
                    o.path = path;
                }
            }
            Lookup::Missed(_) => {}
            Lookup::None => {}
        }
        self.lookup = Some(l);
    }

    /// Whether the terminal view looks the log up (design D5): on opening,
    /// when another process is in front, and on an event while Empty or
    /// Missed with an agent in front. Never on a timer: a Found agent's log
    /// is not read again while the terminal shows.
    pub fn needs_watch(&self, t: &Term, event: bool) -> bool {
        let Some((pid, _)) = self.located else { return true };
        let waiting = matches!(self.lookup, Some(Lookup::Empty { .. } | Lookup::Missed(_)));
        pid != t.pid || (event && waiting && t.agent.is_some())
    }

    /// A lookup made for the terminal view: a chat left meanwhile still
    /// looks up again on its return.
    pub fn take_watch_lookup(&mut self, t: &Term, l: Option<Lookup>, now: Instant) {
        let returned = self.returned;
        self.take_lookup(t, l, now);
        self.returned = returned;
    }

    /// The terminal's status while the terminal view shows: only Empty's
    /// first turn is followed; the per-turn rule judges turns the chat saw.
    pub fn watch_see(&mut self, t: &Term) {
        let was = self.watch_status.as_deref() == Some("working");
        if was
            && matches!(t.status.as_deref(), Some("idle" | "done"))
            && let Some(l) = &self.lookup
        {
            self.guard.turn_ended(l);
        }
        self.watch_status = t.status.clone();
    }

    /// The terminal's status at a poll: follows turns for the per-turn rule
    /// and for Empty's first turn.
    pub fn see(&mut self, t: &Term, now: Instant) {
        let status = t.status.clone();
        let working = status.as_deref() == Some("working");
        let was = self.last_status.as_deref() == Some("working");
        if working && !was && self.last_status.is_some() {
            let skip = self.command_sent.is_some_and(|at| now.duration_since(at) < RELOOK);
            if let Some(o) = self.log.as_mut() {
                o.parser.turn_started(skip);
            }
            self.turn_end = None;
        }
        if was && matches!(status.as_deref(), Some("idle" | "done")) {
            self.turn_end = Some(now);
            if let Some(l) = &self.lookup {
                self.guard.turn_ended(l);
            }
        }
        self.last_status = status;
    }

    pub fn plan_read(&self) -> Option<Read> {
        let o = self.log.as_ref()?;
        let path = o.path.clone()?;
        Some(match o.next {
            None => Read { path, start: format!("-{FIRST_BUDGET}"), end: None },
            Some(n) if self.probe_size => Read { path, start: n.to_string(), end: Some(n) },
            Some(n) => Read { path, start: n.to_string(), end: Some(n + READ_MAX) },
        })
    }

    /// A read's outcome; true when another read should follow at once.
    pub fn take_read(&mut self, got: Result<Chunk, String>, now: Instant) -> bool {
        let Some(o) = self.log.as_mut() else { return false };
        let c = match got {
            Ok(c) => c,
            Err(_) => {
                self.problem = Some(FAILED_READ.into());
                return false;
            }
        };
        self.problem = None;
        if self.probe_size {
            self.probe_size = false;
            if o.next.is_some_and(|n| c.size > n + FIRST_BUDGET) {
                *o = Log::new(o.agent, o.session.clone(), o.path.clone());
            }
            return true;
        }
        o.take(&c);
        if self.turn_end.is_some_and(|end| now.duration_since(end) >= TURN_GRACE)
            && self.last_status.as_deref() != Some("working")
        {
            o.parser.turn_ended();
            self.turn_end = None;
        }
        false
    }

    pub fn plan_older(&self) -> Option<Read> {
        let o = self.log.as_ref()?;
        let end = o.older_scan.unwrap_or(o.from);
        if end == 0 || o.older_blocked || o.next.is_none() {
            return None;
        }
        Some(Read { path: o.path.clone()?, start: end.saturating_sub(o.older_budget).to_string(), end: Some(end) })
    }

    /// A read back's outcome; true when it should be read again, wider.
    pub fn take_older(&mut self, got: Result<Chunk, String>) -> bool {
        let Some(o) = self.log.as_mut() else { return false };
        let c = match got {
            Ok(c) => c,
            Err(_) => {
                o.older_blocked = true;
                return false;
            }
        };
        let end = o.older_scan.unwrap_or(o.from);
        let start = end.saturating_sub(o.older_budget);
        if c.text.is_empty() && c.skipped.is_none() {
            if c.from < end {
                // Whole records, none the chat shows (requests): read on before them.
                o.from = c.from;
                o.older_scan = None;
                o.older_budget = OLDER_BUDGET;
                return true;
            }
            // No whole record: the window lies inside one. Wider, then step
            // back through it a window at a time to where it starts.
            if start == 0 {
                o.older_blocked = true;
                return false;
            }
            if o.older_budget < READ_MAX {
                o.older_budget = (o.older_budget * 2).min(READ_MAX);
            } else {
                o.older_scan = Some(start);
            }
            return true;
        }
        o.older_scan = None;
        let mut lines = Vec::new();
        let mut rest = c.text.as_str();
        for at in &c.offsets {
            let n = rest.find('\n').map_or(rest.len(), |i| i + 1);
            lines.push((*at, rest[..n].to_owned()));
            rest = &rest[n..];
        }
        if let Some((at, _)) = c.skipped {
            o.skipped.push(at);
        }
        lines.append(&mut o.lines);
        o.lines = lines;
        o.from = c.from.min(o.lines.first().map_or(c.from, |(at, _)| *at));
        o.older_budget = OLDER_BUDGET;
        o.reparse();
        false
    }

    /// A message went out from the chat.
    pub fn sent(&mut self, message: &str, now: Instant) {
        let m = message.trim_start();
        if m.starts_with('/') {
            self.relook_until = Some(now + RELOOK);
        }
        if m.starts_with('/') || m.starts_with('!') {
            self.command_sent = Some(now);
        }
    }

    /// The chat left the screen: rows read back beyond the last 2 MB go, and
    /// its return looks up and checks the log's size first.
    pub fn leave(&mut self) {
        self.returned = true;
        self.last_status = None;
        self.turn_end = None;
        let Some(o) = self.log.as_mut() else { return };
        if o.next.is_some() {
            self.probe_size = true;
        }
        if let Some(next) = o.next.filter(|n| o.from + FIRST_BUDGET < *n) {
            let cut = next - FIRST_BUDGET;
            o.lines.retain(|(at, _)| *at >= cut);
            o.skipped.retain(|at| *at >= cut);
            o.from = o.lines.first().map_or(next, |(at, _)| *at);
            o.older_blocked = false;
            o.older_scan = None;
            o.reparse();
        }
    }

    /// The agent and session shown, for the send target.
    pub fn shown(&self) -> Option<(Agent, String)> {
        self.log.as_ref().map(|o| (o.agent, o.session.clone()))
    }

    pub fn lookup(&self) -> Option<&Lookup> {
        self.lookup.as_ref()
    }

    pub fn view(&self) -> View {
        let found = match &self.lookup {
            Some(Lookup::Found { .. }) => Kind::Found,
            Some(Lookup::Empty { .. }) => Kind::Empty,
            Some(Lookup::Missed(_)) => Kind::Missed,
            Some(Lookup::None) | None => Kind::None,
        };
        let Some(o) = self.log.as_ref() else {
            return View {
                found,
                agent: None,
                rows: vec![],
                health: Health::Ok,
                problem: self.problem.clone(),
                exited: false,
                cut: false,
                gauge: None,
                queued: vec![],
                title: None,
                version: None,
            };
        };
        let mut rows: Vec<Row> =
            o.parser.items().iter().zip(&o.keys).map(|(item, key)| Row { key: *key, item: item.clone() }).collect();
        for at in &o.skipped {
            let i = rows.iter().position(|r| r.key.0 > *at).unwrap_or(rows.len());
            rows.insert(i, Row { key: (*at, 0), item: Item::Note { text: SKIPPED.into() } });
        }
        if o.older_blocked {
            rows.insert(0, Row { key: (o.from, u32::MAX), item: Item::Note { text: OLDER_FAILED.into() } });
        }
        View {
            found,
            agent: Some(o.agent),
            rows,
            health: o.parser.health(),
            problem: self.problem.clone(),
            exited: found == Kind::None,
            cut: o.from > 0 && !o.older_blocked,
            gauge: o.parser.gauge(),
            queued: o.parser.queued(),
            title: o.parser.title().map(str::to_owned),
            version: o.parser.version().map(str::to_owned),
        }
    }
}

/// The chats held, most recently viewed last (design D12).
pub(crate) struct Held<T> {
    order: Vec<(String, T)>,
}

impl<T: Clone> Held<T> {
    pub fn new() -> Self {
        Held { order: Vec::new() }
    }

    /// The chat for `key`, made when not held; it becomes the most recent,
    /// and the least recent past [`HELD`] is dropped.
    pub fn open(&mut self, key: &str, make: impl FnOnce() -> T) -> T {
        let v = match self.order.iter().position(|(k, _)| k == key) {
            Some(i) => self.order.remove(i).1,
            None => make(),
        };
        self.order.push((key.to_owned(), v.clone()));
        if self.order.len() > HELD {
            self.order.remove(0);
        }
        v
    }

    /// Android asked to free memory: keep only the one on screen.
    pub fn trim(&mut self, shown: Option<&str>) {
        self.order.retain(|(k, _)| Some(k.as_str()) == shown);
    }

    #[cfg(test)]
    pub fn keys(&self) -> Vec<String> {
        self.order.iter().map(|(k, _)| k.clone()).collect()
    }
}

#[cfg(test)]
#[path = "chat_core_tests.rs"]
mod tests;
