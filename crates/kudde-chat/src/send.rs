//! Where a message from the chat may go (design D4). Pressing send fixes the
//! target: the agent process (its pid and start time) and the session the
//! chat shows. The check before photo uploads and the one right before
//! typing compare against it, so a restart, another agent or a new session
//! in between types nothing. Pending bubbles, queue and screen matching,
//! slash waits and the second Enter are pure decisions here (desktop-chat
//! design D3, ported from ChatPending.kt). Adapters read screens and execute
//! actions. All times are caller-clock milliseconds; backwards jumps saturate.

use crate::Agent;
use crate::core::Row;
use crate::locate::Lookup;
use crate::{Item, Key};
use std::collections::{HashMap, HashSet};

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
    if !may_send(seen.agent, seen.status) {
        return Err(Refusal::Waiting);
    }
    Ok((agent, pid))
}

/// An agent in front with a status that accepts chat input.
pub fn may_send(agent: Option<Agent>, status: Option<&str>) -> bool {
    agent.is_some() && matches!(status, Some("idle" | "done" | "working"))
}

/// What became of a terminal send. An unsure send stays pending, never
/// restores the draft and never causes a retry or a second Enter.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Sent {
    Typed,
    Unsure,
    Refused(String),
}

/// Transport evidence supplied by the adapter, without a protocol dependency.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SendError {
    OutcomeUnknown,
    Lost,
    /// Evidence that the terminal refused before any input was typed.
    Refused(String),
}

pub fn send_outcome(reply: Result<(), SendError>) -> Sent {
    match reply {
        Ok(()) => Sent::Typed,
        Err(SendError::OutcomeUnknown | SendError::Lost) => Sent::Unsure,
        Err(SendError::Refused(reason)) => Sent::Refused(reason),
    }
}

/// Execute with `terminal.send`, never bare keys or an empty paste. A space
/// releases Claude Code's held input; the fresh status guard travels with it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct EnterAgain {
    pub text: &'static str,
    pub guard: Option<&'static str>,
}

pub fn enter_again(target: Option<&Target>, seen: &Seen) -> Option<EnterAgain> {
    Some(EnterAgain {
        text: " ",
        guard: check_again(target?, seen).ok()?,
    })
}

/// The transport half of the per-message Enter state. Keep this target from
/// the original send, across uploads and later checks; never replace it with
/// the latest lookup. The caller uses Pending's screen/delay decision first.
#[derive(Clone, Debug)]
pub struct EnterState {
    target: Target,
    sure: bool,
    pressed: bool,
}

impl EnterState {
    pub fn new(target: Target, outcome: &Sent) -> Self {
        Self {
            target,
            sure: *outcome == Sent::Typed,
            pressed: false,
        }
    }

    /// Consume the attempt before dispatch, including when its reply is lost.
    /// A failed fresh target check consumes nothing and types nothing.
    pub fn take(&mut self, seen: &Seen) -> Option<EnterAgain> {
        if !self.sure || self.pressed {
            return None;
        }
        let action = enter_again(Some(&self.target), seen)?;
        self.pressed = true;
        Some(action)
    }
}

pub const ARRIVE_MS: u64 = 30_000;
pub const WAIT_MS: u64 = 3_000;
pub const SETTLE_MS: u64 = 1_000;
pub const ENTER_AGAIN_MS: u64 = 1_500;
pub const DIALOG_FIRST: &str = "Answer the dialog in the terminal first";

/// Drawing labels stay in the platform: Sending, Queued, Not in the
/// conversation yet, and Sent to the terminal, respectively.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PendingState {
    Sending,
    Queued,
    NotArrived,
    Sent,
}

/// One outgoing message. The text is the pending bubble's text, without
/// upload paths or transport-only spaces. Normalization belongs to task 3.3.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Pending {
    pub id: u64,
    pub text: String,
    pub images: usize,
    pub sent_at: u64,
    pub anchor: Option<Key>,
    pub shells_at: usize,
    pub status_at_send: String,
    pub typed_at: Option<u64>,
    pub queued: bool,
    pub was_queued: bool,
    pub stopped_at: Option<u64>,
    pub status_changed: bool,
    pub wait_dismissed: bool,
    pub enter_pressed: bool,
    pub sure: bool,
    pub box_checked: bool,
    pub free_seen: bool,
}

impl Pending {
    pub fn new(
        id: u64,
        text: String,
        images: usize,
        rows: &[Row],
        status: String,
        now: u64,
    ) -> Self {
        let mut p = Self {
            id,
            text,
            images,
            sent_at: now,
            anchor: rows.last().map(|r| r.key),
            shells_at: 0,
            status_at_send: status,
            typed_at: None,
            queued: false,
            was_queued: false,
            stopped_at: None,
            status_changed: false,
            wait_dismissed: false,
            enter_pressed: false,
            sure: false,
            box_checked: false,
            free_seen: false,
        };
        p.shells_at = rows.last().map(|r| p.shells(r)).unwrap_or(0);
        p
    }

    pub fn slash(&self) -> bool {
        self.text.trim_start().starts_with('/')
    }
    pub fn command(&self) -> Option<&str> {
        self.text.trim().strip_prefix('!').map(str::trim)
    }
    pub fn plain(&self) -> bool {
        !self.slash() && self.command().is_none()
    }

    pub fn typed(mut self, now: u64, sure: bool) -> Self {
        self.typed_at = Some(now);
        self.sure = sure;
        self
    }
    pub fn dismiss_wait(mut self) -> Self {
        self.wait_dismissed = true;
        self
    }

    pub fn matches(&self, row: &Row) -> bool {
        let Item::User { text, images } = &row.item else {
            return false;
        };
        if self.text.trim().is_empty() {
            text.trim().is_empty() && !images.is_empty()
        } else {
            text.trim() == self.text.trim()
        }
    }

    fn shells(&self, row: &Row) -> usize {
        let Some(command) = self.command() else {
            return 0;
        };
        let Item::Tools { calls } = &row.item else {
            return 0;
        };
        calls
            .iter()
            .filter(|c| c.name == "Shell" && c.input.trim() == command)
            .count()
    }

    /// Claude's enqueued message has one path line per photo before its text.
    pub fn queued_in(&self, queue: &[String]) -> bool {
        queue.iter().any(|q| {
            q.lines()
                .skip(self.images)
                .collect::<Vec<_>>()
                .join("\n")
                .trim()
                == self.text.trim()
        })
    }

    pub fn codex_queued(&self, status: &str, lines: &[String]) -> bool {
        self.status_at_send == "working" && status == "working" && codex_shows(lines, &self.text)
    }

    pub fn state(&self, now: u64) -> PendingState {
        if self.queued {
            return PendingState::Queued;
        }
        let expired = self.typed_at.is_some_and(|typed| {
            if self.was_queued {
                self.stopped_at
                    .is_some_and(|t| now.saturating_sub(t) >= ARRIVE_MS)
            } else {
                now.saturating_sub(typed) >= ARRIVE_MS
            }
        });
        if !expired {
            PendingState::Sending
        } else if self.slash() && self.sure {
            PendingState::Sent
        } else {
            PendingState::NotArrived
        }
    }

    pub fn settled(&self, now: u64) -> bool {
        matches!(
            self.state(now),
            PendingState::NotArrived | PendingState::Sent
        )
    }

    pub fn wait_open(&self) -> bool {
        self.slash() && !self.wait_dismissed && !self.status_changed && !self.free_seen
    }
    pub fn waiting(&self, now: u64) -> bool {
        self.wait_open() && now.saturating_sub(self.sent_at) >= WAIT_MS
    }
    pub fn shows_wait(&self, now: u64) -> bool {
        self.waiting(now) && self.box_checked
    }
    pub fn waiting_text(&self) -> String {
        format!(
            "Waiting for the terminal: {} may have opened a dialog there.",
            self.text.trim().split(' ').next().unwrap_or_default()
        )
    }

    /// A free box seen before the wait began cannot dismiss a later dialog.
    pub fn box_seen(mut self, agent: Agent, lines: &[String], now: u64) -> Self {
        if self.waiting(now) {
            self.box_checked = true;
            self.free_seen = box_free(agent, lines);
        }
        self
    }

    fn status_moved(&self, status: &str, now: u64) -> bool {
        fn calm(s: &str) -> &str {
            if s == "done" { "idle" } else { s }
        }
        self.typed_at.is_some_and(|t| {
            now.saturating_sub(t) >= SETTLE_MS && calm(status) != calm(&self.status_at_send)
        })
    }

    pub fn needs_enter(&self, now: u64, box_holds_it: bool) -> bool {
        self.sure
            && !self.enter_pressed
            && box_holds_it
            && self
                .typed_at
                .is_some_and(|t| now.saturating_sub(t) >= ENTER_AGAIN_MS)
    }

    /// Select once, before executing I/O. The adapter must still check the
    /// original target afresh and execute only the guarded space-send action.
    pub fn take_enter(&mut self, agent: Agent, lines: &[String], now: u64, can_send: bool) -> bool {
        if !can_send || !self.needs_enter(now, box_holds(agent, lines, &self.text, self.images)) {
            return false;
        }
        self.enter_pressed = true;
        true
    }
}

/// Drop confirmed bubbles oldest first, each taking its own user row. Rows
/// before the send's anchor (including read-back) cannot confirm a message.
/// If /clear removed the anchor, search the new conversation from its start.
pub fn follow_pending(
    pending: &[Pending],
    rows: &[Row],
    status: &str,
    now: u64,
    queued: impl Fn(&Pending) -> bool,
) -> Vec<Pending> {
    let index: HashMap<_, _> = rows.iter().enumerate().map(|(i, r)| (r.key, i)).collect();
    let mut taken = HashSet::new();
    let mut left = Vec::with_capacity(pending.len());
    for p in pending {
        let from = p.anchor.and_then(|k| index.get(&k).copied());
        let after = from.map_or(0, |i| i + 1);
        if let Some(hit) = (after..rows.len()).find(|i| !taken.contains(i) && p.matches(&rows[*i]))
        {
            taken.insert(hit);
            continue;
        }
        if p.state(now) == PendingState::Sent && after < rows.len() {
            continue;
        }
        if p.command().is_some() {
            let shells: usize = rows[from.unwrap_or(0)..].iter().map(|r| p.shells(r)).sum();
            if shells > if from.is_some() { p.shells_at } else { 0 } {
                continue;
            }
        }
        let mut next = p.clone();
        next.queued = queued(p);
        next.was_queued = p.was_queued || next.queued;
        next.stopped_at = if !next.was_queued || status == "working" {
            None
        } else {
            p.stopped_at.or(Some(now))
        };
        next.status_changed = p.status_changed || p.status_moved(status, now);
        left.push(next);
    }
    left
}

pub fn send_refusal(pending: &[Pending], now: u64) -> Option<&'static str> {
    pending
        .iter()
        .any(|p| p.waiting(now))
        .then_some(DIALOG_FIRST)
}

fn bare(s: &str) -> String {
    s.chars().filter(|c| !c.is_whitespace()).collect()
}

/// Match across wraps, but finish at a line's end rather than accept a prefix.
fn holds_from(lines: &[String], at: usize, mark: char, text: &str) -> bool {
    let want = bare(text);
    if want.is_empty() {
        return false;
    }
    let mut got = String::new();
    for (j, line) in lines.iter().enumerate().skip(at) {
        let first = line.trim_start();
        got.push_str(&bare(if j == at {
            first.strip_prefix(mark).unwrap_or(first)
        } else {
            line
        }));
        if got.len() == want.len() {
            return got == want;
        }
        if got.len() > want.len() || !want.starts_with(&got) {
            return false;
        }
    }
    false
}

pub fn codex_shows(lines: &[String], text: &str) -> bool {
    lines
        .iter()
        .enumerate()
        .any(|(i, l)| l.trim_start().starts_with('›') && holds_from(lines, i, '›', text))
}

fn claude_box(lines: &[String]) -> Option<String> {
    let mut rules = lines
        .iter()
        .enumerate()
        .filter_map(|(i, l)| {
            let l = l.trim();
            (!l.is_empty() && l.chars().all(|c| c == '─')).then_some(i)
        })
        .rev();
    let end = rules.next()?;
    let start = rules.next()? + 1;
    let content = &lines[start..end];
    let first = content.first()?.trim_start().strip_prefix('❯')?;
    Some(
        std::iter::once(first)
            .chain(content[1..].iter().map(String::as_str))
            .collect::<Vec<_>>()
            .join("\n"),
    )
}

/// The phone's anchored `[placeholder]` prefix, without adding a regex dependency.
fn without_placeholders(text: &str) -> &str {
    let mut rest = text;
    let mut matched = false;
    loop {
        let next = rest.trim_start();
        let Some(bracket) = next.strip_prefix('[') else {
            break;
        };
        let Some(end) = bracket.find(']') else {
            break;
        };
        if bracket[..end].contains('\n') {
            break;
        }
        rest = bracket[end + 1..].trim_start();
        matched = true;
    }
    if matched { rest } else { text }
}

/// Exact input-box text after optional photo placeholders; whitespace (wraps
/// included) and the transport's trailing space do not affect matching.
pub fn box_holds(agent: Agent, lines: &[String], text: &str, images: usize) -> bool {
    match agent {
        Agent::Claude => {
            let Some(content) = claude_box(lines) else {
                return false;
            };
            let shown = if images > 0 {
                without_placeholders(&content)
            } else {
                &content
            };
            let want = bare(text);
            !want.is_empty() && bare(shown) == want
        }
        Agent::Codex => {
            let Some(at) = lines.iter().rposition(|l| l.trim_start().starts_with('›')) else {
                return false;
            };
            let first = lines[at].trim_start().strip_prefix('›').unwrap();
            let first = if images > 0 {
                without_placeholders(first)
            } else {
                first
            };
            let rest = std::iter::once(first.to_owned())
                .chain(lines[at + 1..].iter().cloned())
                .collect::<Vec<_>>();
            holds_from(&rest, 0, '›', text)
        }
    }
}

fn codex_footer(line: &str) -> bool {
    line.contains("? for shortcuts")
        || line.match_indices("Context ").any(|(at, _)| {
            let tail = &line[at + "Context ".len()..];
            let digits = tail.bytes().take_while(u8::is_ascii_digit).count();
            digits > 0 && tail[digits..].starts_with("% left")
        })
}

/// Claude's empty ruled box or Codex's footer below its last prompt shows
/// that no dialog covers the input box.
pub fn box_free(agent: Agent, lines: &[String]) -> bool {
    match agent {
        Agent::Claude => claude_box(lines).is_some_and(|b| b.trim().is_empty()),
        Agent::Codex => lines
            .iter()
            .rposition(|l| l.trim_start().starts_with('›'))
            .is_some_and(|at| lines[at + 1..].iter().any(|l| codex_footer(l))),
    }
}

/// The session a lookup names; Missed and None name none.
fn session_of(l: &Lookup) -> Option<&str> {
    match l {
        Lookup::Found { session, .. } | Lookup::Empty { session } => Some(session),
        Lookup::Missed(_) | Lookup::None => None,
    }
}
