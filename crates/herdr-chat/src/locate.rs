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

#[derive(Clone, Debug, PartialEq)]
pub enum Found {
    Log { path: String, session: String },
    /// The session has no log yet (before its first message).
    Empty { session: String },
    Nothing(&'static str),
}

pub fn decide(p: &Probe) -> Found {
    match p.name.as_str() {
        "claude" => claude(p),
        "codex" => codex(p),
        _ => Found::Nothing("not an agent"),
    }
}

/// [`decide`] for an agent already known by other means (a launcher-wrapped
/// install runs as `node`, so its process name says nothing).
pub fn decide_for(agent: crate::Agent, p: &Probe) -> Found {
    match agent {
        crate::Agent::Claude => claude(p),
        crate::Agent::Codex => codex(p),
    }
}

fn claude(p: &Probe) -> Found {
    let Some(file) = p.claude_pidfile.as_deref() else {
        return Found::Nothing("no session file");
    };
    let Ok(v) = serde_json::from_str::<Value>(file) else {
        return Found::Nothing("session file unreadable");
    };
    let Some(session) = v["sessionId"].as_str() else {
        return Found::Nothing("session file unreadable");
    };
    // Claude Code writes it as a string ("756754"); accept a number too.
    let start = v["procStart"]
        .as_u64()
        .or_else(|| v["procStart"].as_str().and_then(|s| s.trim().parse().ok()));
    if start.is_none() || start != p.proc_start {
        return Found::Nothing("process start differs");
    }
    match p.claude_logs.as_slice() {
        [] => Found::Empty {
            session: session.to_owned(),
        },
        [one] => Found::Log {
            path: one.clone(),
            session: session.to_owned(),
        },
        _ => Found::Nothing("several logs"),
    }
}

/// The thread id at the end of a rollout or lock file name.
fn thread_id(path: &str) -> Option<&str> {
    let name = path.rsplit('/').next()?;
    let stem = name.strip_suffix(".jsonl").or_else(|| name.strip_suffix(".lock"))?;
    (stem.len() >= 36).then(|| &stem[stem.len() - 36..])
}

fn codex(p: &Probe) -> Found {
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
        return Found::Empty {
            session: id.to_owned(),
        };
    }
    // Times are whole seconds: two rollouts written in the same second could be
    // either thread in front (a resumed older thread beside the current one).
    let newest = rollouts.iter().map(|r| r.1).max();
    if rollouts.iter().filter(|r| Some(r.1) == newest).count() > 1 {
        return Found::Nothing("two Codex sessions open, written in the same second");
    }
    match rollouts.iter().max_by(|a, b| a.1.cmp(&b.1).then(a.2.cmp(b.2))) {
        Some((path, _, id)) => Found::Log {
            path: (*path).to_owned(),
            session: (*id).to_owned(),
        },
        None => Found::Nothing("no rollout open"),
    }
}
