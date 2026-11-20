//! Ports of ChatPendingTest: caller-clock times, real core rows, no I/O.
use kudde_chat::core::Row;
use kudde_chat::send::{self, Pending, PendingState};
use kudde_chat::{Agent, Image, Item, ToolCall};

fn user(key: u64, text: &str, images: usize) -> Row {
    Row {
        key: (key, 0),
        item: Item::User {
            text: text.into(),
            images: vec![Image::Missing; images],
        },
    }
}
fn said(key: u64) -> Row {
    Row {
        key: (key, 0),
        item: Item::Assistant {
            text: "Done.".into(),
        },
    }
}
fn shell(key: u64, commands: &[&str]) -> Row {
    Row {
        key: (key, 0),
        item: Item::Tools {
            calls: commands
                .iter()
                .map(|s| ToolCall {
                    name: "Shell".into(),
                    title: format!("! {s}"),
                    input: (*s).into(),
                    output: String::new(),
                    done: true,
                    failed: false,
                    images: vec![],
                })
                .collect(),
        },
    }
}
fn sent(text: &str, rows: &[Row], status: &str, images: usize, at: u64) -> Pending {
    Pending::new(1, text.into(), images, rows, status.into(), at).typed(at, true)
}
fn follow(p: &[Pending], rows: &[Row], status: &str, now: u64, queued: bool) -> Vec<Pending> {
    send::follow_pending(p, rows, status, now, |_| queued)
}
fn claude_idle() -> Vec<String> {
    lines(&["● Done.", "", "────", "❯ ", "────", "  Haiku"])
}
fn claude_dialog() -> Vec<String> {
    lines(&["Subagents     % of usage", "Esc to cancel"])
}
fn codex_idle() -> Vec<String> {
    lines(&[
        "› hello",
        "• Hello.",
        "› Ask Codex to do anything",
        "",
        "Context 98% left",
        "? for shortcuts",
    ])
}
fn codex_picker() -> Vec<String> {
    lines(&[
        "› hello",
        "Select Model and Effort",
        "› 1. Model (current)",
        "enter select · esc back",
    ])
}
fn lines(s: &[&str]) -> Vec<String> {
    s.iter().map(|s| (*s).into()).collect()
}

#[test]
fn a_normal_send_turns_into_its_row() {
    let before = vec![said(0)];
    let p = sent("Run the tests", &before, "idle", 0, 0);
    assert_eq!(p.state(100), PendingState::Sending);
    assert_eq!(
        follow(std::slice::from_ref(&p), &before, "idle", 1000, false),
        vec![p.clone()]
    );
    assert!(
        follow(
            &[p],
            &[said(0), user(120, "Run the tests", 0)],
            "idle",
            1000,
            false
        )
        .is_empty()
    );
}

#[test]
fn an_older_row_and_reading_back_confirm_nothing() {
    let before = vec![user(0, "/usage", 0), said(40)];
    let p = sent("/usage", &before, "idle", 0, 0);
    assert_eq!(
        follow(std::slice::from_ref(&p), &before, "idle", 1000, false).len(),
        1
    );
    let mut after = before.clone();
    after.push(user(90, "/usage", 0));
    assert!(follow(&[p], &after, "idle", 1000, false).is_empty());
    let p = sent("Run the tests", &[said(500)], "idle", 0, 0);
    assert_eq!(
        follow(
            &[p],
            &[user(10, "Run the tests", 0), said(90), said(500)],
            "idle",
            1000,
            false
        )
        .len(),
        1
    );
}

#[test]
fn matching_ignores_outer_space_but_not_other_text_or_row_kinds() {
    let p = sent("  Read @notes.md\n", &[], "idle", 0, 0);
    assert!(follow(&[p], &[user(0, "Read @notes.md", 0)], "idle", 1000, false).is_empty());
    let p = sent("Run the tests", &[], "idle", 0, 0);
    assert_eq!(
        follow(
            std::slice::from_ref(&p),
            &[user(0, "typed at the desk", 0)],
            "idle",
            1000,
            false
        )
        .len(),
        1
    );
    assert_eq!(follow(&[p], &[said(0)], "idle", 1000, false).len(), 1);
}

#[test]
fn a_photo_alone_needs_images_and_no_text() {
    let p = sent("", &[], "idle", 1, 0);
    for row in [
        user(0, "Something else", 0),
        user(0, "", 0),
        user(0, "other", 1),
    ] {
        assert_eq!(
            follow(std::slice::from_ref(&p), &[row], "idle", 1000, false).len(),
            1
        );
    }
    assert!(follow(&[p], &[user(0, "", 1)], "idle", 1000, false).is_empty());
}

#[test]
fn identical_messages_each_take_their_own_row_oldest_first() {
    let before = vec![said(0)];
    let a = sent("again", &before, "idle", 0, 0);
    let mut b = a.clone();
    b.id = 2;
    let one = follow(
        &[a, b.clone()],
        &[said(0), user(50, "again", 0)],
        "idle",
        1000,
        false,
    );
    assert_eq!(one, vec![b]);
    assert!(
        follow(
            &one,
            &[said(0), user(50, "again", 0), user(90, "again", 0)],
            "idle",
            1000,
            false
        )
        .is_empty()
    );
}

#[test]
fn a_missing_message_does_not_hold_back_the_next() {
    let rows = vec![said(0)];
    let lost = sent("/usage", &rows, "idle", 0, 0);
    let next = sent("Run the tests", &rows, "idle", 0, 0);
    assert_eq!(
        follow(
            &[lost.clone(), next],
            &[said(0), user(70, "Run the tests", 0)],
            "idle",
            1000,
            false
        ),
        vec![lost]
    );
}

#[test]
fn bang_commands_confirm_by_row_or_new_shell_calls_including_folded_anchor() {
    let p = sent("!echo hi", &[said(0)], "idle", 0, 0);
    assert!(
        follow(
            &[p],
            &[said(0), user(30, "!echo hi", 0), shell(31, &["echo hi"])],
            "idle",
            1000,
            false
        )
        .is_empty()
    );
    let before = vec![shell(0, &["ls"])];
    let p = sent("!echo hi", &before, "idle", 0, 0);
    assert_eq!(
        follow(std::slice::from_ref(&p), &before, "idle", 1000, false).len(),
        1
    );
    assert!(follow(&[p], &[shell(0, &["ls", "echo hi"])], "idle", 1000, false).is_empty());
    let before = vec![shell(0, &["echo hi"])];
    let p = sent("!echo hi", &before, "idle", 0, 0);
    assert_eq!(p.shells_at, 1);
    assert_eq!(
        follow(std::slice::from_ref(&p), &before, "idle", 1000, false).len(),
        1
    );
    assert!(
        follow(
            &[p],
            &[shell(0, &["echo hi"]), shell(60, &["echo hi"])],
            "idle",
            1000,
            false
        )
        .is_empty()
    );
    let p = sent("echo hi", &[], "idle", 0, 0);
    assert_eq!(
        follow(&[p], &[shell(0, &["echo hi"])], "idle", 1000, false).len(),
        1
    );
}

#[test]
fn clear_and_the_next_message_confirm_in_the_new_log() {
    let old = vec![user(100, "/clear", 0), said(200), said(90000)];
    let p = sent("/clear", &old, "idle", 0, 0);
    let new = vec![user(310, "/clear", 0)];
    assert!(follow(&[p], &new, "idle", 1000, false).is_empty());
    let p = sent("Run the tests", &new, "idle", 0, 0);
    assert!(
        follow(
            &[p],
            &[user(310, "/clear", 0), user(800, "Run the tests", 0)],
            "idle",
            1000,
            false
        )
        .is_empty()
    );
}

#[test]
fn queued_messages_never_expire_while_shown_queued() {
    let p = sent("Run the tests", &[], "working", 0, 0);
    let list = follow(&[p], &[], "working", 200, true);
    assert_eq!(list[0].state(200), PendingState::Queued);
    let list = follow(&list, &[], "working", 60000, true);
    assert_eq!(list[0].state(60000), PendingState::Queued);
}

#[test]
fn expiry_counts_from_typing_not_a_slow_upload_and_late_arrival_still_confirms() {
    let p = sent("Run the tests", &[], "idle", 0, 1000);
    assert_eq!(p.state(30999), PendingState::Sending);
    assert_eq!(p.state(31000), PendingState::NotArrived);
    assert!(follow(&[p], &[user(0, "Run the tests", 0)], "idle", 45000, false).is_empty());
    let p = Pending::new(
        1,
        "What is on this screen?".into(),
        1,
        &[],
        "idle".into(),
        0,
    );
    assert_eq!(p.state(120000), PendingState::Sending);
}

#[test]
fn queued_expiry_starts_when_work_stops_and_resets_if_work_resumes() {
    let p = sent("Run the tests", &[], "working", 0, 0);
    let list = follow(&[p], &[], "working", 500, true);
    let list = follow(&list, &[], "working", 40000, false);
    assert_eq!(list[0].state(40000), PendingState::Sending);
    let list = follow(&list, &[], "done", 50000, false);
    assert_eq!(list[0].state(79999), PendingState::Sending);
    assert_eq!(list[0].state(80000), PendingState::NotArrived);
    let list = follow(&list, &[], "working", 81000, false);
    assert_eq!(list[0].state(90000), PendingState::Sending);
    let list = follow(&list, &[], "idle", 100000, false);
    assert_eq!(list[0].state(129999), PendingState::Sending);
    assert_eq!(list[0].state(130000), PendingState::NotArrived);
}

#[test]
fn claude_queue_matches_text_after_photo_paths() {
    let p = sent("Run the tests", &[], "idle", 0, 0);
    assert!(p.queued_in(&lines(&["other", "Run the tests"])));
    assert!(!p.queued_in(&lines(&["Run the tests now"])));
    let p = sent("What is this?", &[], "idle", 2, 0);
    assert!(p.queued_in(&lines(&["/tmp/a.png\n/tmp/b.png\nWhat is this?"])));
}

#[test]
fn codex_queue_needs_a_mid_turn_send_and_exact_screen_text() {
    let screen = lines(&[
        "• Ran cargo test",
        "",
        "› Run the tests and tell me",
        "  what failed",
        "",
        "• Working",
        "",
        "› Ask Codex to do anything",
    ]);
    assert!(send::codex_shows(
        &screen,
        "Run the tests and tell me what failed"
    ));
    assert!(send::codex_shows(
        &screen,
        "Run the tests and tell me\nwhat failed"
    ));
    assert!(!send::codex_shows(&screen, "Run the tests"));
    assert!(!send::codex_shows(&screen, "Something else"));
    let idle = sent("Run the tests and tell me what failed", &[], "idle", 0, 0);
    assert!(!idle.codex_queued("working", &screen));
    let mid = sent(
        "Run the tests and tell me what failed",
        &[],
        "working",
        0,
        0,
    );
    assert!(mid.codex_queued("working", &screen));
    assert!(!mid.codex_queued("idle", &screen));
}

#[test]
fn claude_box_is_between_the_last_two_rules_and_must_match_exactly() {
    let screen = lines(&[
        "──────",
        "❯ Read @notes.md",
        "some answer",
        "❯ an earlier queued message",
        "────",
        "❯ Read @notes/plan.md ",
        "────",
        "auto mode",
    ]);
    assert!(send::box_holds(
        Agent::Claude,
        &screen,
        "Read @notes/plan.md",
        0
    ));
    assert!(!send::box_holds(
        Agent::Claude,
        &screen,
        "an earlier queued message",
        0
    ));
    assert!(!send::box_holds(Agent::Claude, &screen, "Read", 0));
    assert!(!send::box_holds(
        Agent::Claude,
        &screen,
        "Read @notes.md",
        0
    ));
    let wrapped = lines(&[
        "────",
        "❯ Read @notes/no",
        "tes.md and then",
        "summarise it",
        "────",
    ]);
    assert!(send::box_holds(
        Agent::Claude,
        &wrapped,
        "Read @notes/notes.md and then summarise it",
        0
    ));
    assert!(send::box_holds(
        Agent::Claude,
        &wrapped,
        "Read @notes/notes.md and then\nsummarise it",
        0
    ));
}

#[test]
fn photo_placeholders_are_stripped_only_for_photo_sends() {
    for (agent, screen) in [
        (
            Agent::Claude,
            lines(&["────", "❯ [Image #1] [Image #2] What is this?", "────"]),
        ),
        (
            Agent::Codex,
            lines(&["› [Image #1] [Image #2] What is this?", "hint"]),
        ),
    ] {
        assert!(send::box_holds(agent, &screen, "What is this?", 2));
        assert!(!send::box_holds(agent, &screen, "What is this?", 0));
        assert!(!send::box_holds(agent, &screen, "", 2));
    }
}

#[test]
fn codex_box_is_its_last_prompt_and_may_have_hints_below() {
    let screen = lines(&[
        "› an older message",
        "",
        "• Done",
        "",
        "› Read @notes.md",
        "",
        "⏎ send   ⌃J newline",
    ]);
    assert!(send::box_holds(Agent::Codex, &screen, "Read @notes.md", 0));
    assert!(!send::box_holds(
        Agent::Codex,
        &screen,
        "an older message",
        0
    ));
    assert!(!send::box_holds(Agent::Claude, &[], "x", 0));
    assert!(!send::box_holds(Agent::Codex, &[], "x", 0));
}

#[test]
fn enter_is_due_after_1500_ms_once_per_message_only_if_the_box_holds_it() {
    let mut p = sent("Read @notes.md", &[], "idle", 0, 10000);
    assert!(!p.needs_enter(11499, true));
    assert!(p.needs_enter(11500, true));
    assert!(!p.needs_enter(12000, false));
    let screen = lines(&["› Read @notes.md"]);
    assert!(!p.take_enter(Agent::Codex, &screen, 12000, false));
    assert!(p.take_enter(Agent::Codex, &screen, 12000, true));
    assert!(!p.needs_enter(20000, true));
    assert!(!p.take_enter(Agent::Codex, &screen, 20000, true));
    let p = Pending::new(1, "x".into(), 0, &[], "idle".into(), 0);
    assert!(!p.needs_enter(9000, true));
}

#[test]
fn an_unsure_send_is_never_given_another_enter() {
    let mut p = Pending::new(1, "Read @notes.md".into(), 0, &[], "idle".into(), 0).typed(0, false);
    assert!(!p.needs_enter(2000, true));
    assert!(!p.take_enter(Agent::Codex, &lines(&["› Read @notes.md"]), 2000, true));
}

#[test]
fn slash_wait_counts_from_the_tap_even_while_send_is_running() {
    let p = Pending::new(1, "/usage".into(), 0, &[], "idle".into(), 1000);
    assert!(!p.waiting(3999));
    assert!(p.waiting(4000));
    assert_eq!(
        send::send_refusal(std::slice::from_ref(&p), 4000),
        Some(send::DIALOG_FIRST)
    );
    assert!(p.typed(6200, true).waiting(6300));
    let p = sent("/usage now", &[], "idle", 0, 0);
    assert_eq!(
        p.waiting_text(),
        "Waiting for the terminal: /usage may have opened a dialog there."
    );
    assert!(!sent("Say hi", &[], "idle", 0, 0).waiting(60000));
}

#[test]
fn confirmed_slash_without_a_row_settles_as_sent_unsure_stays_not_arrived() {
    let rows = vec![user(0, "hello", 0)];
    let p = sent("/usage", &rows, "idle", 0, 0);
    assert_eq!(p.state(29999), PendingState::Sending);
    assert_eq!(p.state(30000), PendingState::Sent);
    assert!(p.settled(30000));
    let unsure = Pending::new(1, "/usage".into(), 0, &rows, "idle".into(), 0).typed(0, false);
    assert_eq!(unsure.state(30000), PendingState::NotArrived);
    assert_eq!(
        sent("Fix the retry", &rows, "idle", 0, 0).state(30000),
        PendingState::NotArrived
    );
    assert_eq!(
        follow(std::slice::from_ref(&p), &rows, "idle", 31000, false).len(),
        1
    );
    let newer = vec![user(0, "hello", 0), user(1, "hi", 0)];
    assert_eq!(
        follow(std::slice::from_ref(&p), &newer, "idle", 5000, false).len(),
        1
    );
    assert!(follow(&[p], &newer, "idle", 31000, false).is_empty());
    assert_eq!(follow(&[unsure], &newer, "idle", 31000, false).len(), 1);
}

#[test]
fn wait_refuses_until_dismissed_status_moves_or_its_row_arrives() {
    let p = sent("/usage", &[], "idle", 0, 0);
    assert_eq!(
        send::send_refusal(std::slice::from_ref(&p), 5000),
        Some(send::DIALOG_FIRST)
    );
    assert_eq!(send::send_refusal(&[p.clone().dismiss_wait()], 5000), None);
    let changed = follow(std::slice::from_ref(&p), &[], "working", 4000, false);
    assert!(!changed[0].waiting(5000));
    let back = follow(&changed, &[], "idle", 6000, false);
    assert!(!back[0].waiting(7000));
    assert_eq!(send::send_refusal(&back, 7000), None);
    assert!(follow(&[p], &[user(0, "/usage", 0)], "idle", 5000, false).is_empty());
}

#[test]
fn sends_own_working_blip_and_done_to_idle_do_not_end_the_wait() {
    let p = sent("/usage", &[], "done", 0, 0);
    let blip = follow(&[p], &[], "working", 300, false);
    let back = follow(&blip, &[], "done", 2000, false);
    assert!(back[0].waiting(3000));
    assert_eq!(send::send_refusal(&back, 3500), Some(send::DIALOG_FIRST));
    let p = sent("/usage", &[], "done", 0, 0);
    assert!(follow(&[p], &[], "idle", 2000, false)[0].waiting(3000));
    let p = sent("/usage", &[], "idle", 0, 0);
    assert!(!follow(&[p], &[], "working", 1000, false)[0].waiting(3000));
}

#[test]
fn free_boxes_and_dialogs_are_detected_for_both_agents() {
    assert!(send::box_free(Agent::Claude, &claude_idle()));
    assert!(!send::box_free(
        Agent::Claude,
        &lines(&["────", "❯ half a message", "────"])
    ));
    assert!(!send::box_free(Agent::Claude, &claude_dialog()));
    assert!(send::box_free(Agent::Codex, &codex_idle()));
    assert!(send::box_free(
        Agent::Codex,
        &lines(&["› prompt", "? for shortcuts"])
    ));
    assert!(!send::box_free(Agent::Codex, &codex_picker()));
    for agent in [Agent::Claude, Agent::Codex] {
        assert!(!send::box_free(agent, &[]));
    }
}

#[test]
fn printing_command_ends_wait_only_after_a_screen_read_after_three_seconds() {
    let p = sent("/status", &[], "idle", 0, 0);
    assert!(p.waiting(3000));
    assert!(!p.shows_wait(3000));
    let early = p.clone().box_seen(Agent::Codex, &codex_idle(), 1500);
    assert_eq!(early, p);
    let free = p.box_seen(Agent::Codex, &codex_idle(), 3200);
    assert!(!free.waiting(3200));
    assert!(!free.shows_wait(3200));
    assert!(!free.wait_open());
    assert_eq!(send::send_refusal(&[free], 3200), None);
}

#[test]
fn dialog_keeps_the_line_until_closed_at_the_desk() {
    for (agent, dialog, idle) in [
        (Agent::Claude, claude_dialog(), claude_idle()),
        (Agent::Codex, codex_picker(), codex_idle()),
    ] {
        let p = sent("/model", &[], "idle", 0, 0);
        let seen = p.box_seen(agent, &dialog, 3200);
        assert!(seen.shows_wait(3200));
        assert_eq!(
            send::send_refusal(std::slice::from_ref(&seen), 3200),
            Some(send::DIALOG_FIRST)
        );
        let closed = seen.box_seen(agent, &idle, 9000);
        assert!(!closed.shows_wait(9000));
        assert_eq!(send::send_refusal(&[closed], 9000), None);
    }
    let p = sent("Say hi", &[], "idle", 0, 0);
    assert_eq!(p.clone().box_seen(Agent::Claude, &claude_idle(), 5000), p);
}

#[test]
fn backwards_and_equal_clocks_do_not_underflow_or_fire_timers_early() {
    let p = sent("/usage", &[], "idle", 0, 10000);
    for now in [0, 9999, 10000] {
        assert_eq!(p.state(now), PendingState::Sending);
        assert!(!p.waiting(now));
        assert!(!p.needs_enter(now, true));
        assert!(!follow(std::slice::from_ref(&p), &[], "working", now, false)[0].status_changed);
    }
    assert!(p.needs_enter(11500, true));
    assert!(p.waiting(13000));
    assert_eq!(p.state(40000), PendingState::Sent);
}

#[test]
fn canonical_pending_confirms_in_log_and_both_queues() {
    let p = sent("read\r\nno\x03tes\x1b", &[], "working", 1, 0);
    assert_eq!(p.text, "read\nnotes");
    assert!(
        follow(
            std::slice::from_ref(&p),
            &[user(0, "read\nnotes", 1)],
            "working",
            100,
            false
        )
        .is_empty()
    );
    assert!(p.queued_in(&lines(&["/tmp/a.png\nread\nnotes"])));
    assert!(p.codex_queued("working", &lines(&["› read", "notes"])));
}

#[test]
fn canonical_commands_keep_dialog_waits_and_shell_confirmation() {
    let p = sent("\x03/usage", &[], "idle", 0, 0);
    assert!(p.slash());
    assert_eq!(send::send_refusal(&[p], 3000), Some(send::DIALOG_FIRST));
    let p = sent("\x1b!echo hi", &[], "idle", 0, 0);
    assert_eq!(p.command(), Some("echo hi"));
    assert!(follow(&[p], &[shell(0, &["echo hi"])], "idle", 1000, false).is_empty());
}

#[test]
fn canonical_box_matching_keeps_the_once_only_enter_delay() {
    for (agent, screen) in [
        (
            Agent::Claude,
            lines(&["────", "❯ read", "@notes.md ", "────"]),
        ),
        (Agent::Codex, lines(&["› read", "@notes.md "])),
    ] {
        let mut p = sent("re\x03ad\r\n@notes.md", &[], "idle", 0, 0);
        assert!(!p.take_enter(agent, &screen, 1499, true));
        assert!(p.take_enter(agent, &screen, 1500, true));
        assert!(!p.take_enter(agent, &screen, 3000, true));
    }
}
