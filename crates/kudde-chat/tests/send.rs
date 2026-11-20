//! The send target (design D4): fixed at the first check, compared before
//! photo uploads and right before typing.

use kudde_chat::Agent;
use kudde_chat::locate::Lookup;
use kudde_chat::send::{self, Refusal, Seen, Target};

const SESSION: &str = "aaaaaaaa-1111-4111-8111-111111111111";

fn found(session: &str) -> Lookup {
    Lookup::Found {
        path: format!("/u/.claude/projects/-work-website/{session}.jsonl"),
        session: session.into(),
    }
}

/// What a check sees: Claude Code in front as pid 4242, started at 9001.
fn seen<'a>(status: &'a str, lookup: &'a Lookup) -> Seen<'a> {
    Seen {
        agent: Some(Agent::Claude),
        pid: Some(4242),
        proc_start: Some(9001),
        status: Some(status),
        lookup,
    }
}

fn target() -> Target {
    let l = found(SESSION);
    send::first_check(Agent::Claude, SESSION, &seen("idle", &l)).expect("sendable")
}

#[test]
fn the_first_check_records_process_start_and_session() {
    assert_eq!(
        target(),
        Target {
            agent: Agent::Claude,
            pid: 4242,
            proc_start: Some(9001),
            session: SESSION.into()
        }
    );
}

#[test]
fn the_guard_carries_the_status_seen_for_idle_and_working_and_none_for_done() {
    let l = found(SESSION);
    let t = target();
    assert_eq!(send::check_again(&t, &seen("idle", &l)), Ok(Some("idle")));
    assert_eq!(
        send::check_again(&t, &seen("working", &l)),
        Ok(Some("working"))
    );
    assert_eq!(
        send::check_again(&t, &seen("done", &l)),
        Ok(None),
        "herdr-core has no guard for done"
    );
}

#[test]
fn a_changed_process_start_time_or_session_before_typing_refuses() {
    let t = target();
    let l = found(SESSION);
    let mut s = seen("idle", &l);
    s.pid = Some(5150);
    assert_eq!(
        send::check_again(&t, &s),
        Err(Refusal::Changed),
        "restarted: another pid"
    );
    let mut s = seen("idle", &l);
    s.proc_start = Some(9500);
    assert_eq!(
        send::check_again(&t, &s),
        Err(Refusal::Changed),
        "same pid, another process"
    );
    let cleared = found("cccccccc-3333-4333-8333-333333333333");
    assert_eq!(
        send::check_again(&t, &seen("idle", &cleared)),
        Err(Refusal::Changed),
        "/clear: another session"
    );
    let mut s = seen("idle", &l);
    s.agent = Some(Agent::Codex);
    assert_eq!(
        send::check_again(&t, &s),
        Err(Refusal::Changed),
        "another agent"
    );
}

#[test]
fn missed_is_never_sendable() {
    let missed = Lookup::Missed("two Codex sessions open, written in the same second");
    assert_eq!(
        send::first_check(Agent::Claude, SESSION, &seen("idle", &missed)),
        Err(Refusal::Changed)
    );
    assert_eq!(
        send::check_again(&target(), &seen("idle", &missed)),
        Err(Refusal::Changed)
    );
}

#[test]
fn the_first_check_refuses_a_session_other_than_the_one_shown() {
    let l = found("cccccccc-3333-4333-8333-333333333333");
    assert_eq!(
        send::first_check(Agent::Claude, SESSION, &seen("idle", &l)),
        Err(Refusal::Changed)
    );
    let l = found(SESSION);
    assert_eq!(
        send::first_check(Agent::Codex, SESSION, &seen("idle", &l)),
        Err(Refusal::Changed),
        "another agent shown"
    );
}

#[test]
fn an_empty_session_is_sendable() {
    let l = Lookup::Empty {
        session: SESSION.into(),
    };
    let t = send::first_check(Agent::Claude, SESSION, &seen("idle", &l))
        .expect("Empty follows the send rules");
    assert_eq!(send::check_again(&t, &seen("idle", &l)), Ok(Some("idle")));
}

#[test]
fn blocked_or_no_status_is_the_agent_waiting_in_the_terminal() {
    let l = found(SESSION);
    assert_eq!(
        send::first_check(Agent::Claude, SESSION, &seen("blocked", &l)),
        Err(Refusal::Waiting)
    );
    let mut s = seen("idle", &l);
    s.status = None;
    assert_eq!(
        send::first_check(Agent::Claude, SESSION, &s),
        Err(Refusal::Waiting),
        "a start-up prompt"
    );
    assert_eq!(
        send::check_again(&target(), &seen("blocked", &l)),
        Err(Refusal::Waiting),
        "blocked during the upload"
    );
}

#[test]
fn a_shell_in_front_is_an_exited_agent_and_gets_nothing() {
    let l = Lookup::None;
    let shell = Seen {
        agent: None,
        pid: Some(777),
        proc_start: Some(9900),
        status: None,
        lookup: &l,
    };
    assert_eq!(
        send::first_check(Agent::Claude, SESSION, &shell),
        Err(Refusal::Exited)
    );
    assert_eq!(send::check_again(&target(), &shell), Err(Refusal::Exited));
}

#[test]
fn outcomes_distinguish_acknowledgement_uncertainty_and_definitive_refusal() {
    use send::{SendError, Sent, send_outcome};
    assert_eq!(send_outcome(Ok(())), Sent::Typed);
    assert_eq!(send_outcome(Err(SendError::OutcomeUnknown)), Sent::Unsure);
    assert_eq!(send_outcome(Err(SendError::Lost)), Sent::Unsure);
    assert_eq!(
        send_outcome(Err(SendError::Refused("status is working".into()))),
        Sent::Refused("status is working".into())
    );
}

#[test]
fn a_second_enter_is_a_guarded_space_send_against_the_original_target() {
    let l = found(SESSION);
    let target = target();
    for (status, guard) in [
        ("idle", Some("idle")),
        ("working", Some("working")),
        ("done", None),
    ] {
        let action = send::enter_again(Some(&target), &seen(status, &l)).unwrap();
        assert_eq!(action.text, " ", "never bare keys or an empty paste");
        assert_eq!(action.guard, guard);
    }
    assert!(send::enter_again(None, &seen("idle", &l)).is_none());
    assert!(send::enter_again(Some(&target), &seen("blocked", &l)).is_none());
    let mut changed = seen("idle", &l);
    changed.pid = Some(5150);
    assert!(send::enter_again(Some(&target), &changed).is_none());
    changed = seen("idle", &l);
    changed.proc_start = Some(9500);
    assert!(send::enter_again(Some(&target), &changed).is_none());
    let other = found("other-session");
    assert!(send::enter_again(Some(&target), &seen("idle", &other)).is_none());
    changed = seen("idle", &l);
    changed.agent = None;
    assert!(send::enter_again(Some(&target), &changed).is_none());
}

#[test]
fn per_message_enter_state_is_once_only_and_unsure_never_retries() {
    let l = found(SESSION);
    let mut message = send::EnterState::new(target(), &send::Sent::Typed);
    assert!(message.take(&seen("blocked", &l)).is_none());
    assert!(message.take(&seen("idle", &l)).is_some());
    assert!(message.take(&seen("idle", &l)).is_none());
    for outcome in [send::Sent::Unsure, send::Sent::Refused("no".into())] {
        let mut message = send::EnterState::new(target(), &outcome);
        assert!(message.take(&seen("idle", &l)).is_none());
    }
    let mut next_message = send::EnterState::new(target(), &send::Sent::Typed);
    assert!(next_message.take(&seen("working", &l)).is_some());
}

#[test]
fn a_codex_thread_change_at_either_send_boundary_refuses_with_same_process() {
    let l = found(SESSION);
    let mut first = seen("working", &l);
    first.agent = Some(Agent::Codex);
    let t = send::first_check(Agent::Codex, SESSION, &first).unwrap();
    let other = found("new-thread");
    let mut final_seen = seen("working", &other);
    final_seen.agent = Some(Agent::Codex);
    assert_eq!(
        send::first_check(Agent::Codex, SESSION, &final_seen),
        Err(Refusal::Changed)
    );
    assert_eq!(send::check_again(&t, &final_seen), Err(Refusal::Changed));
    assert_eq!(
        t.session, SESSION,
        "a fresh lookup never replaces the original target"
    );
    for lookup in [
        Lookup::None,
        Lookup::Missed("unreadable or ambiguous open files"),
    ] {
        let uncertain = Seen {
            lookup: &lookup,
            ..final_seen.clone()
        };
        assert_eq!(
            send::first_check(Agent::Codex, SESSION, &uncertain),
            Err(Refusal::Changed)
        );
        assert_eq!(send::check_again(&t, &uncertain), Err(Refusal::Changed));
    }
}
