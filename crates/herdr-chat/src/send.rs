//! Where a message from the chat may go (design D4). Pressing send fixes the
//! target: the agent process (its pid and start time) and the session the
//! chat shows. The check before photo uploads and the one right before
//! typing compare against it, so a restart, another agent or a new session
//! in between types nothing.

use crate::Agent;
use crate::locate::Lookup;

/// What one check sees of the terminal.
#[derive(Clone, Debug)]
pub struct Seen<'a> {
    /// [`crate::locate::agent_in_front`] of the foreground process.
    pub agent: Option<Agent>,
    pub pid: Option<i64>,
    /// The process start time the lookup read (`/proc/<pid>/stat`).
    pub proc_start: Option<u64>,
    /// herdr-core's status for the terminal (`idle`, `working`, `done`,
    /// `blocked`), none when it reports none.
    pub status: Option<&'a str>,
    pub lookup: &'a Lookup,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Target {
    pub agent: Agent,
    pub pid: i64,
    pub proc_start: Option<u64>,
    pub session: String,
}

/// Why nothing is typed; the chat says it and keeps the draft.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refusal {
    /// Blocked, or no status (a prompt at start-up): "The agent is waiting
    /// for you in the terminal".
    Waiting,
    /// No agent in front: "The agent has exited".
    Exited,
    /// Another process, agent or session than the one shown, or one the
    /// lookup cannot confirm (Missed): "This is a different session now".
    Changed,
}

/// The check when send is pressed: the target, for the chat showing
/// `shown`'s session `session`.
pub fn first_check(shown: Agent, session: &str, seen: &Seen) -> Result<Target, Refusal> {
    let (agent, pid) = gates(seen)?;
    match session_of(seen.lookup) {
        Some(s) if agent == shown && s == session => Ok(Target {
            agent,
            pid,
            proc_start: seen.proc_start,
            session: s.to_owned(),
        }),
        _ => Err(Refusal::Changed),
    }
}

/// A later check against the target; on success, the status to pass as
/// `terminal.send`'s `if` guard (none for done: herdr-core has no guard
/// for it, a known window).
pub fn check_again(target: &Target, seen: &Seen) -> Result<Option<&'static str>, Refusal> {
    let (agent, pid) = gates(seen)?;
    let same = agent == target.agent
        && pid == target.pid
        && seen.proc_start == target.proc_start
        && session_of(seen.lookup) == Some(target.session.as_str());
    if !same {
        return Err(Refusal::Changed);
    }
    Ok(match seen.status {
        Some("idle") => Some("idle"),
        Some("working") => Some("working"),
        _ => None,
    })
}

/// An agent in front, with a status that takes input.
fn gates(seen: &Seen) -> Result<(Agent, i64), Refusal> {
    let (Some(agent), Some(pid)) = (seen.agent, seen.pid) else {
        return Err(Refusal::Exited);
    };
    if !matches!(seen.status, Some("idle" | "done" | "working")) {
        return Err(Refusal::Waiting);
    }
    Ok((agent, pid))
}

/// The session a lookup names; Missed and None name none.
fn session_of(l: &Lookup) -> Option<&str> {
    match l {
        Lookup::Found { session, .. } | Lookup::Empty { session } => Some(session),
        Lookup::Missed(_) | Lookup::None => None,
    }
}
