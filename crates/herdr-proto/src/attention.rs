//! The attention model shared by the app and herdr-push (agent-list spec,
//! "One attention model, shared with the watcher").
//!
//! Adapted from herdr-app `src/model.rs` (`Model::set_word`, `alert`, `status`,
//! `mark_seen`, `folder_name`) and `src/window.rs` (`agent_name`), Apache-2.0.
//! Changed: the phone tracks terminals across several machines and across app
//! restarts. A finish it did not see live is caught by each terminal's turn
//! counter (`turn` rises each time the status leaves `working`): the last turn
//! the person saw is remembered per terminal (M1 plan, pick 2b), with whether
//! a finish was still unseen and whether the agent was busy, since leaving
//! `blocked` or `error` does not move the counter. Rows are derived from the
//! core's terminal records, which the tracker diffs.

use serde_json::{Map, Value, json};
use std::collections::{BTreeMap, HashMap};

/// How a row is shown, in list order.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Attention {
    /// `blocked`: waiting for the person.
    NeedsYou,
    /// `error`: needs the person too, shown red.
    Error,
    /// It stopped working and the person has not looked since.
    Done,
    Working,
    Idle,
}

impl Attention {
    pub fn needs_you(self) -> bool {
        matches!(self, Attention::NeedsYou | Attention::Error)
    }

    /// Sort group: needs you, finished unseen, working, idle.
    pub fn group(self) -> u8 {
        match self {
            Attention::NeedsYou | Attention::Error => 0,
            Attention::Done => 1,
            Attention::Working => 2,
            Attention::Idle => 3,
        }
    }
}

/// One agent on the home list.
#[derive(Clone, Debug, PartialEq)]
pub struct Row {
    pub machine: String,
    pub address: String,
    pub name: String,
    pub attention: Attention,
    /// "Permission: Run npm test?", "Needs you", "Working", ...
    pub label: String,
    /// 0-100 when the program reported progress.
    pub progress: Option<u8>,
    /// When the state last changed, ms since the epoch.
    pub since_ms: Option<u64>,
    /// The folder it works in now, as a name.
    pub folder: String,
    /// Which program it is, for its icon: "claude", "codex", ... (see [`program_id`]).
    pub program: Option<String>,
    /// Needs you or error: the card's heading ("Permission", "Question",
    /// "Needs you", "Error"); none for other rows.
    pub request_kind: Option<String>,
    /// Needs you or error: the program's `msg`, shown under the heading.
    pub request: Option<String>,
}

/// A terminal with no status now ("Other terminals"): it never reported, or
/// the agent in it quit back to its shell.
#[derive(Clone, Debug, PartialEq)]
pub struct Other {
    pub machine: String,
    pub address: String,
    pub name: String,
    pub folder: String,
    /// The command's file name ("make"), if it has one.
    pub program: Option<String>,
}

#[derive(Clone, Debug)]
struct Agent {
    word: Option<String>,
    turn: u64,
    /// The last turn the person saw finished.
    seen_turn: u64,
    unseen: bool,
    since_ms: Option<u64>,
    status_ms: Option<u64>,
}

/// What survives an app restart for one terminal.
#[derive(Clone, Copy, Debug, Default)]
struct Remembered {
    seen_turn: u64,
    unseen: bool,
    busy: bool,
}

#[derive(Default)]
struct Machine {
    terminals: Vec<Value>,
    agents: HashMap<String, Agent>,
}

#[derive(Default)]
pub struct Tracker {
    machines: BTreeMap<String, Machine>,
    /// Restored from storage, for machines not observed yet.
    remembered: BTreeMap<String, BTreeMap<String, Remembered>>,
    looking: Option<(String, String)>,
}

fn busy(word: Option<&str>) -> bool {
    matches!(word, Some("working" | "blocked" | "error"))
}

/// A terminal the tracker follows: live, reported at some point, not a
/// sub-agent. Its seen turn is kept while the agent in it has quit, so an
/// agent started again there does not show an old finish as unseen.
fn is_tracked(t: &Value) -> bool {
    t["state"] == "live" && t["reported"] == true && t["parent"].is_null()
}

/// A terminal that is its own row: tracked, with a status now. The core keeps
/// `reported` once set but clears `status` when the agent quits (issue 4).
fn is_agent(t: &Value) -> bool {
    is_tracked(t) && !t["status"].is_null()
}

/// A terminal under "Other terminals": live, no status now, not a sub-agent
/// that reported (those never show).
fn is_other(t: &Value) -> bool {
    t["state"] == "live"
        && t["status"].is_null()
        && (t["reported"] != true || t["parent"].is_null())
}

fn word_of(t: &Value) -> Option<String> {
    t.pointer("/status/state")
        .and_then(Value::as_str)
        .map(str::to_owned)
}

impl Tracker {
    /// A tracker that remembers the seen turns saved by [`Tracker::memory`].
    pub fn restore(memory: &Value) -> Tracker {
        let remembered = memory["machines"]
            .as_object()
            .into_iter()
            .flatten()
            .map(|(machine, turns)| {
                let turns = turns.as_object().into_iter().flatten();
                (
                    machine.clone(),
                    turns
                        .filter_map(|(a, t)| {
                            let r = Remembered {
                                seen_turn: t["seen_turn"].as_u64()?,
                                unseen: t["unseen"] == true,
                                busy: t["busy"] == true,
                            };
                            Some((a.clone(), r))
                        })
                        .collect(),
                )
            })
            .collect();
        Tracker {
            remembered,
            ..Default::default()
        }
    }

    /// What to save so finishes while the app is closed show as unseen later:
    /// `{"machines": {name: {address: {seen_turn, unseen, busy}}}}`.
    pub fn memory(&self) -> Value {
        let entry =
            |r: Remembered| json!({"seen_turn": r.seen_turn, "unseen": r.unseen, "busy": r.busy});
        let mut machines = Map::new();
        for (name, turns) in &self.remembered {
            let turns = turns.iter().map(|(a, r)| (a.clone(), entry(*r))).collect();
            machines.insert(name.clone(), Value::Object(turns));
        }
        for (name, m) in &self.machines {
            let turns = m
                .agents
                .iter()
                .map(|(a, s)| {
                    let r = Remembered {
                        seen_turn: s.seen_turn,
                        unseen: s.unseen,
                        busy: busy(s.word.as_deref()),
                    };
                    (a.clone(), entry(r))
                })
                .collect();
            machines.insert(name.clone(), Value::Object(turns));
        }
        json!({ "machines": machines })
    }

    /// Takes a machine's full terminal list (from `EventLines`) after a
    /// snapshot or a push. `now_ms` dates changes the core sent no time for.
    pub fn observe(&mut self, machine: &str, terminals: &[Value], now_ms: u64) {
        let known = self.machines.contains_key(machine) || self.remembered.contains_key(machine);
        let remembered = self.remembered.remove(machine).unwrap_or_default();
        let looking = self.looking.clone();
        let m = self.machines.entry(machine.to_owned()).or_default();
        let mut agents = HashMap::new();
        for t in terminals.iter().filter(|t| is_tracked(t)) {
            let Some(address) = t["address"].as_str() else {
                continue;
            };
            let looked_at = looking
                .as_ref()
                .is_some_and(|(lm, la)| lm == machine && la == address);
            let word = word_of(t);
            let turn = t["turn"].as_u64().unwrap_or(0);
            let status_ms = t["status_ms"].as_u64();
            let mut a = match m.agents.remove(address) {
                None => {
                    let finished = !busy(word.as_deref()) && word.is_some();
                    let (seen_turn, unseen) = match remembered.get(address) {
                        // Unseen before, busy then and finished now, or a turn since.
                        Some(r) => (
                            r.seen_turn,
                            finished && (r.unseen || r.busy || turn > r.seen_turn),
                        ),
                        // A first contact with the machine counts what is there as
                        // seen; a terminal new to a known machine has seen nothing yet.
                        None => {
                            let seen_turn = if known { 0 } else { turn };
                            (seen_turn, finished && turn > seen_turn)
                        }
                    };
                    Agent {
                        unseen,
                        word,
                        turn,
                        seen_turn,
                        since_ms: status_ms.or(Some(now_ms)),
                        status_ms,
                    }
                }
                Some(mut a) => {
                    if a.word != word {
                        // A snapshot carries the core's time; status events carry none.
                        let fresh = status_ms.is_some() && status_ms != a.status_ms;
                        a.since_ms = if fresh { status_ms } else { Some(now_ms) };
                        if busy(word.as_deref()) {
                            a.unseen = false;
                        } else if busy(a.word.as_deref()) && word.is_some() {
                            a.unseen = true;
                        }
                        a.word = word;
                    }
                    // A turn that started and ended between two looks.
                    if a.turn < turn && !busy(a.word.as_deref()) && a.word.is_some() {
                        a.unseen = true;
                    }
                    a.turn = turn;
                    a.status_ms = status_ms;
                    a
                }
            };
            // Looked at on the phone, or quit at the desk (issue 4).
            if looked_at || a.word.is_none() {
                a.unseen = false;
            }
            if !a.unseen && !busy(a.word.as_deref()) {
                a.seen_turn = a.turn;
            }
            agents.insert(address.to_owned(), a);
        }
        m.agents = agents;
        m.terminals = terminals.to_vec();
    }

    /// The machine left the list: its rows and memory go.
    pub fn forget_machine(&mut self, machine: &str) {
        self.machines.remove(machine);
        self.remembered.remove(machine);
    }

    /// The person opened it: a finish is no longer unseen.
    pub fn mark_seen(&mut self, machine: &str, address: &str) {
        if let Some(a) = self
            .machines
            .get_mut(machine)
            .and_then(|m| m.agents.get_mut(address))
        {
            a.unseen = false;
            if !busy(a.word.as_deref()) {
                a.seen_turn = a.turn;
            }
        }
    }

    /// The terminal open on the phone, if any: finishes it shows are seen.
    pub fn set_looking(&mut self, at: Option<(&str, &str)>) {
        self.looking = at.map(|(m, a)| (m.to_owned(), a.to_owned()));
        if let Some((m, a)) = at {
            self.mark_seen(m, a);
        }
    }

    /// Every agent across machines, needs you first, newest change first within a group.
    pub fn rows(&self) -> Vec<Row> {
        let mut rows: Vec<Row> = self
            .machines
            .iter()
            .flat_map(|(name, m)| {
                m.terminals
                    .iter()
                    .filter(|t| is_agent(t))
                    .filter_map(move |t| {
                        let address = t["address"].as_str()?;
                        let a = m.agents.get(address)?;
                        Some(row(name, address, t, a))
                    })
            })
            .collect();
        rows.sort_by_key(|r| (r.attention.group(), std::cmp::Reverse(r.since_ms)));
        rows
    }

    /// Live terminals with no status now, per machine in the core's order.
    pub fn others(&self) -> Vec<Other> {
        self.machines
            .iter()
            .flat_map(|(name, m)| {
                m.terminals
                    .iter()
                    .filter(|t| is_other(t))
                    .filter_map(move |t| {
                        let address = t["address"].as_str()?;
                        Some(Other {
                            machine: name.clone(),
                            address: address.to_owned(),
                            name: name_of(t, address),
                            folder: folder_of(t),
                            program: program_of(t),
                        })
                    })
            })
            .collect()
    }
}

fn row(machine: &str, address: &str, t: &Value, a: &Agent) -> Row {
    let attention = match a.word.as_deref() {
        Some("working") => Attention::Working,
        Some("blocked") => Attention::NeedsYou,
        Some("error") => Attention::Error,
        _ if a.unseen => Attention::Done,
        _ => Attention::Idle,
    };
    let text = |k: &str| {
        t.pointer(&format!("/status/{k}"))
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
    };
    let label = match attention {
        Attention::NeedsYou => match (text("kind").map(capitalized), text("msg")) {
            (Some(kind), Some(msg)) => format!("{kind}: {msg}"),
            (Some(kind), None) => kind,
            (None, Some(msg)) => msg.to_owned(),
            (None, None) => "Needs you".to_owned(),
        },
        Attention::Error => text("msg").map_or("Error".to_owned(), |m| format!("Error: {m}")),
        Attention::Done => "Done".to_owned(),
        Attention::Working => "Working".to_owned(),
        Attention::Idle => "Idle".to_owned(),
    };
    let (request_kind, request) = match attention {
        Attention::NeedsYou => (
            Some(text("kind").map_or("Needs you".to_owned(), capitalized)),
            text("msg").map(str::to_owned),
        ),
        Attention::Error => (Some("Error".to_owned()), text("msg").map(str::to_owned)),
        _ => (None, None),
    };
    Row {
        machine: machine.to_owned(),
        address: address.to_owned(),
        name: name_of(t, address),
        attention,
        label,
        progress: t
            .pointer("/status/progress")
            .and_then(Value::as_u64)
            .filter(|p| *p <= 100)
            .map(|p| p as u8),
        since_ms: a.since_ms,
        folder: folder_of(t),
        program: program_of(t),
        request_kind,
        request,
    }
}

/// The program for the icon: the reported app, else the command's file name.
fn program_of(t: &Value) -> Option<String> {
    fn nonempty(v: Option<&Value>) -> Option<&str> {
        v.and_then(Value::as_str).filter(|s| !s.is_empty())
    }
    if let Some(app) = nonempty(t.pointer("/status/app")) {
        return Some(program_id(app));
    }
    nonempty(t.pointer("/argv/0"))
        .map(|p| folder_name(p, None))
        .filter(|p| !p.is_empty() && p != "~" && p != "/")
        .map(|p| program_id(&p))
}

/// One name per program, whichever way it reported itself.
pub fn program_id(app: &str) -> String {
    match app {
        "claude" | "claude-code" => "claude".into(),
        "cursor" | "cursor-agent" => "cursor".into(),
        other => other.to_owned(),
    }
}

fn capitalized(s: &str) -> String {
    let mut c = s.chars();
    c.next()
        .map(|f| f.to_uppercase().chain(c).collect())
        .unwrap_or_default()
}

/// The terminal's name, else the program's display name, else its id.
fn name_of(t: &Value, address: &str) -> String {
    let nonempty = |v: &Value| v.as_str().filter(|s| !s.is_empty()).map(str::to_owned);
    if let Some(name) = nonempty(&t["name"]) {
        return name;
    }
    if let Some(app) = t.pointer("/status/app").and_then(nonempty) {
        return agent_name(&app);
    }
    if let Some(program) = t["argv"][0]
        .as_str()
        .map(|p| folder_name(p, None))
        .filter(|p| !p.is_empty() && p != "~")
    {
        return program;
    }
    address.to_owned()
}

/// How people call an agent, from its 7501 `app`.
pub fn agent_name(app: &str) -> String {
    match app {
        "claude" | "claude-code" => "Claude Code".into(),
        "codex" => "Codex".into(),
        "pi" => "Pi".into(),
        "opencode" => "OpenCode".into(),
        "cursor" | "cursor-agent" => "Cursor".into(),
        "amp" => "Amp".into(),
        "grok" => "Grok".into(),
        other => other.to_owned(),
    }
}

/// Where it works now: the foreground program's folder, else its start folder.
fn folder_of(t: &Value) -> String {
    let cwd = t
        .pointer("/foreground/cwd")
        .and_then(Value::as_str)
        .or(t["cwd"].as_str())
        .unwrap_or("");
    folder_name(cwd, None)
}

/// A folder as a name: its last part, `~` for the home folder (`/` and `\` both).
pub fn folder_name(path: &str, home: Option<&str>) -> String {
    let full = path;
    let path = path.trim_end_matches(['/', '\\']);
    let same = home.is_some_and(|h| path == h.trim_end_matches(['/', '\\']));
    if full.is_empty() || same {
        return "~".into();
    }
    if path.is_empty() {
        return full[..1].to_owned();
    }
    path.rsplit(['/', '\\'])
        .find(|s| !s.is_empty())
        .unwrap_or(path)
        .to_owned()
}
