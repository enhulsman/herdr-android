//! Which log belongs to a terminal. The phone runs [`SCRIPT`] on the desk
//! through `machine.exec` with the terminal's foreground pid, and decides from
//! what it prints.
//!
//! - Claude Code writes `~/.claude/sessions/<pid>.json` for each running
//!   process with its current `sessionId` (it follows /clear, --resume,
//!   --continue and --fork-session) and `procStart` (the process start time,
//!   field 22 of /proc/<pid>/stat), and removes it on a clean exit. The log is
//!   `~/.claude/projects/<folder>/<sessionId>.jsonl`.
//! - Codex holds its rollout file and a per-thread lock file open while it
//!   runs; after /new it also keeps the old thread's files open.

use serde_json::Value;

/// `sh -c SCRIPT sh <pid>`: one fact per line (see [`parse_probe`]).
pub const SCRIPT: &str = r#"p=$1
n=$(cat /proc/$p/comm 2>/dev/null) || { echo "name ?"; exit 0; }
echo "name $n"
echo "procstart $(sed 's/.*) //' /proc/$p/stat | cut -d' ' -f20)"
f=$HOME/.claude/sessions/$p.json
if [ -r "$f" ]; then
  printf 'pidfile '; tr -d '\n' < "$f"; echo
  sid=$(sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p' "$f" | head -n 1)
  if [ -n "$sid" ]; then
    for l in "$HOME"/.claude/projects/*/"$sid".jsonl; do [ -e "$l" ] && echo "log $l"; done
  fi
fi
for q in $p $(pgrep -P $p 2>/dev/null); do
  for d in /proc/$q/fd/*; do
    t=$(readlink "$d" 2>/dev/null) || continue
    case $t in
      */sessions/*/rollout-*.jsonl|*/thread-writer-locks/*.lock)
        echo "fd $(stat -c %Y "$t" 2>/dev/null || echo 0) $t";;
    esac
  done
done
exit 0
"#;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Probe {
    /// The foreground process's name (`claude`, `codex`, `zsh`).
    pub name: String,
    pub claude_pidfile: Option<String>,
    pub proc_start: Option<u64>,
    pub claude_logs: Vec<String>,
    /// Codex rollout and lock files held open, with their mtime.
    pub codex_open: Vec<(String, u64)>,
}

pub fn parse_probe(out: &str) -> Probe {
    let mut p = Probe::default();
    for line in out.lines() {
        let (k, v) = line.split_once(' ').unwrap_or((line, ""));
        match k {
            "name" => p.name = v.trim().to_owned(),
            "procstart" => p.proc_start = v.trim().parse().ok(),
            "pidfile" => p.claude_pidfile = Some(v.to_owned()),
            "log" => p.claude_logs.push(v.to_owned()),
            "fd" => {
                if let Some((m, path)) = v
                    .split_once(' ')
                    .filter(|(_, p)| p.ends_with(".jsonl") || p.ends_with(".lock"))
                {
                    let entry = (path.to_owned(), m.parse().unwrap_or(0));
                    if !p.codex_open.contains(&entry) {
                        p.codex_open.push(entry);
                    }
                }
            }
            _ => {}
        }
    }
    p
}

/// What the lookup found for a terminal (spec: the lookup's four results).
#[derive(Clone, Debug, PartialEq)]
pub enum Lookup {
    /// The agent's log exists and is read.
    Found { path: String, session: String },
    /// The session is known but has no log yet (before its first message).
    Empty { session: String },
    /// An agent is in front but its session is not known: why.
    Missed(&'static str),
    /// No agent in front.
    None,
}

pub fn decide(p: &Probe) -> Lookup {
    match p.name.as_str() {
        "claude" => claude(p),
        "codex" => codex(p),
        _ => Lookup::None,
    }
}

/// The lookup for a terminal: `agent` is [`agent_in_front`] of its
/// foreground process, `out` what [`SCRIPT`] printed (none when the desk did
/// not answer).
pub fn lookup(agent: Option<crate::Agent>, out: Option<&str>) -> Lookup {
    let Some(agent) = agent else { return Lookup::None };
    match out {
        Some(out) => decide_for(agent, &parse_probe(out)),
        None => Lookup::Missed("the desk did not answer"),
    }
}

/// The agent a foreground process is, by its name, or for an install that
/// `node` runs (npm, nvm) by the script in its arguments. Node names its
/// main thread, so the name may read `MainThread`: its first argument says
/// it is node.
pub fn agent_in_front(name: &str, argv: &[&str]) -> Option<crate::Agent> {
    let by_name = |n: &str| match n {
        "claude" => Some(crate::Agent::Claude),
        "codex" => Some(crate::Agent::Codex),
        _ => None,
    };
    if let Some(a) = by_name(name) {
        return Some(a);
    }
    let base = |a: &str| a.rsplit('/').next().unwrap_or(a).to_owned();
    if name != "node" && argv.first().map(|a| base(a)).as_deref() != Some("node") {
        return None;
    }
    argv.iter().skip(1).take(2).find_map(|arg| {
        let b = base(arg);
        by_name(b.trim_end_matches(".js")).or_else(|| {
            if arg.contains("@anthropic-ai/claude-code") {
                Some(crate::Agent::Claude)
            } else if arg.contains("@openai/codex") {
                Some(crate::Agent::Codex)
            } else {
                None
            }
        })
    })
}

/// Empty turns Missed once the agent's first turn ended and its log still
/// does not exist: a misread session id shows the terminal, not an empty
/// chat for good. A new session (another id) is Empty again until its own
/// first turn ends.
#[derive(Clone, Debug, Default)]
pub struct EmptyGuard {
    ended: Option<String>,
}

impl EmptyGuard {
    /// The agent's turn ended (working, then done or idle) while the lookup
    /// read `now`.
    pub fn turn_ended(&mut self, now: &Lookup) {
        if let Lookup::Empty { session } = now {
            self.ended = Some(session.clone());
        }
    }

    /// A lookup's result as the chat takes it.
    pub fn apply(&self, l: Lookup) -> Lookup {
        match &l {
            Lookup::Empty { session } if self.ended.as_ref() == Some(session) => {
                Lookup::Missed("no log after the first turn")
            }
            _ => l,
        }
    }
}

/// [`decide`] for an agent already known by other means (a launcher-wrapped
/// install runs as `node`, so its process name says nothing).
pub fn decide_for(agent: crate::Agent, p: &Probe) -> Lookup {
    match agent {
        crate::Agent::Claude => claude(p),
        crate::Agent::Codex => codex(p),
    }
}

fn claude(p: &Probe) -> Lookup {
    let Some(file) = p.claude_pidfile.as_deref() else {
        return Lookup::Missed("no session file");
    };
    let Ok(v) = serde_json::from_str::<Value>(file) else {
        return Lookup::Missed("session file unreadable");
    };
    let Some(session) = v["sessionId"].as_str() else {
        return Lookup::Missed("session file unreadable");
    };
    // Claude Code writes it as a string ("756754"); accept a number too.
    let start = v["procStart"]
        .as_u64()
        .or_else(|| v["procStart"].as_str().and_then(|s| s.trim().parse().ok()));
    if start.is_none() || start != p.proc_start {
        return Lookup::Missed("process start differs");
    }
    match p.claude_logs.as_slice() {
        [] => Lookup::Empty {
            session: session.to_owned(),
        },
        [one] => Lookup::Found {
            path: one.clone(),
            session: session.to_owned(),
        },
        _ => Lookup::Missed("several logs"),
    }
}

/// The thread id at the end of a rollout or lock file name.
fn thread_id(path: &str) -> Option<&str> {
    let name = path.rsplit('/').next()?;
    let stem = name.strip_suffix(".jsonl").or_else(|| name.strip_suffix(".lock"))?;
    (stem.len() >= 36).then(|| &stem[stem.len() - 36..])
}

fn codex(p: &Probe) -> Lookup {
    let rollouts: Vec<(&str, u64, &str)> = p
        .codex_open
        .iter()
        .filter(|(path, _)| path.ends_with(".jsonl"))
        .filter_map(|(path, m)| Some((path.as_str(), *m, thread_id(path)?)))
        .collect();
    let newest_rollout_id = rollouts.iter().map(|r| r.2).max();
    // A lock for a thread newer than every rollout: /new before its first message.
    let fresh = p
        .codex_open
        .iter()
        .filter(|(path, _)| path.ends_with(".lock"))
        .filter_map(|(path, _)| thread_id(path))
        .filter(|id| !rollouts.iter().any(|r| r.2 == *id))
        .filter(|id| newest_rollout_id.is_none_or(|n| *id > n))
        .max();
    if let Some(id) = fresh {
        return Lookup::Empty {
            session: id.to_owned(),
        };
    }
    // Times are whole seconds: two rollouts written in the same second could be
    // either thread in front (a resumed older thread beside the current one).
    let newest = rollouts.iter().map(|r| r.1).max();
    if rollouts.iter().filter(|r| Some(r.1) == newest).count() > 1 {
        return Lookup::Missed("two Codex sessions open, written in the same second");
    }
    match rollouts.iter().max_by(|a, b| a.1.cmp(&b.1).then(a.2.cmp(b.2))) {
        Some((path, _, id)) => Lookup::Found {
            path: (*path).to_owned(),
            session: (*id).to_owned(),
        },
        None => Lookup::Missed("no rollout open"),
    }
}
