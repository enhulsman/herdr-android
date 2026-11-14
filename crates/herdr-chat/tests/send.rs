//! The send target (design D4): fixed at the first check, compared before
//! photo uploads and right before typing.

use herdr_chat::Agent;
use herdr_chat::locate::Lookup;
use herdr_chat::send::{self, Refusal, Seen, Target};

const SESSION: &str = "aaaaaaaa-1111-4111-8111-111111111111";

fn found(session: &str) -> Lookup {
    Lookup::Found { path: format!("/u/.claude/projects/-work-website/{session}.jsonl"), session: session.into() }
}

/// What a check sees: Claude Code in front as pid 4242, started at 9001.
fn seen<'a>(status: &'a str, lookup: &'a Lookup) -> Seen<'a> {
    Seen { agent: Some(Agent::Claude), pid: Some(4242), proc_start: Some(9001), status: Some(status), lookup }
}

fn target() -> Target {
    let l = found(SESSION);
    send::first_check(Agent::Claude, SESSION, &seen("idle", &l)).expect("sendable")
}

#[test]
fn the_first_check_records_process_start_and_session() {
    assert_eq!(
        target(),
        Target { agent: Agent::Claude, pid: 4242, proc_start: Some(9001), session: SESSION.into() }
    );
}

#[test]
fn the_guard_carries_the_status_seen_for_idle_and_working_and_none_for_done() {
    let l = found(SESSION);
    let t = target();
    assert_eq!(send::check_again(&t, &seen("idle", &l)), Ok(Some("idle")));
    assert_eq!(send::check_again(&t, &seen("working", &l)), Ok(Some("working")));
    assert_eq!(send::check_again(&t, &seen("done", &l)), Ok(None), "herdr-core has no guard for done");
}

#[test]
fn a_changed_process_start_time_or_session_before_typing_refuses() {
    let t = target();
    let l = found(SESSION);
    let mut s = seen("idle", &l);
    s.pid = Some(5150);
    assert_eq!(send::check_again(&t, &s), Err(Refusal::Changed), "restarted: another pid");
    let mut s = seen("idle", &l);
    s.proc_start = Some(9500);
    assert_eq!(send::check_again(&t, &s), Err(Refusal::Changed), "same pid, another process");
    let cleared = found("cccccccc-3333-4333-8333-333333333333");
    assert_eq!(send::check_again(&t, &seen("idle", &cleared)), Err(Refusal::Changed), "/clear: another session");
    let mut s = seen("idle", &l);
    s.agent = Some(Agent::Codex);
    assert_eq!(send::check_again(&t, &s), Err(Refusal::Changed), "another agent");
}

#[test]
fn missed_is_never_sendable() {
    let missed = Lookup::Missed("two Codex sessions open, written in the same second");
    assert_eq!(send::first_check(Agent::Claude, SESSION, &seen("idle", &missed)), Err(Refusal::Changed));
    assert_eq!(send::check_again(&target(), &seen("idle", &missed)), Err(Refusal::Changed));
}

#[test]
fn the_first_check_refuses_a_session_other_than_the_one_shown() {
    let l = found("cccccccc-3333-4333-8333-333333333333");
    assert_eq!(send::first_check(Agent::Claude, SESSION, &seen("idle", &l)), Err(Refusal::Changed));
    let l = found(SESSION);
    assert_eq!(send::first_check(Agent::Codex, SESSION, &seen("idle", &l)), Err(Refusal::Changed), "another agent shown");
}

#[test]
fn an_empty_session_is_sendable() {
    let l = Lookup::Empty { session: SESSION.into() };
    let t = send::first_check(Agent::Claude, SESSION, &seen("idle", &l)).expect("Empty follows the send rules");
    assert_eq!(send::check_again(&t, &seen("idle", &l)), Ok(Some("idle")));
}

#[test]
fn blocked_or_no_status_is_the_agent_waiting_in_the_terminal() {
    let l = found(SESSION);
    assert_eq!(send::first_check(Agent::Claude, SESSION, &seen("blocked", &l)), Err(Refusal::Waiting));
    let mut s = seen("idle", &l);
    s.status = None;
    assert_eq!(send::first_check(Agent::Claude, SESSION, &s), Err(Refusal::Waiting), "a start-up prompt");
    assert_eq!(send::check_again(&target(), &seen("blocked", &l)), Err(Refusal::Waiting), "blocked during the upload");
}

#[test]
fn a_shell_in_front_is_an_exited_agent_and_gets_nothing() {
    let l = Lookup::None;
    let shell = Seen { agent: None, pid: Some(777), proc_start: Some(9900), status: None, lookup: &l };
    assert_eq!(send::first_check(Agent::Claude, SESSION, &shell), Err(Refusal::Exited));
    assert_eq!(send::check_again(&target(), &shell), Err(Refusal::Exited));
}
