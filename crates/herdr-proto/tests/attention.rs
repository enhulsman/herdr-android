//! The attention model shared by the app and herdr-push (agent-list spec, task M1.1).

use herdr_proto::attention::{Attention, Tracker, folder_name};
use serde_json::{Value, json};

const WSL: &str = "WSL";

fn agent(address: &str, state: Option<&str>, turn: u64) -> Value {
    json!({
        "address": address,
        "state": "live",
        "cwd": "/home/me/src/website",
        "argv": ["claude"],
        "labels": {},
        "name": null,
        "parent": null,
        "reported": true,
        "status": state.map(|s| json!({"state": s, "app": "claude-code"})),
        "status_ms": 1_000,
        "turn": turn,
    })
}

fn with(mut v: Value, key: &str, value: Value) -> Value {
    v[key] = value;
    v
}

fn status(v: Value, status: Value) -> Value {
    with(v, "status", status)
}

fn state_of(t: &Tracker, address: &str) -> Option<Attention> {
    t.rows()
        .into_iter()
        .find(|r| r.address == address)
        .map(|r| r.attention)
}

/// A tracker that has seen WSL before (so new terminals are not a first contact).
fn tracker_knowing_wsl() -> Tracker {
    let mut t = Tracker::default();
    t.observe(WSL, &[], 0);
    t
}

// ---- the spec table ----

#[test]
fn error_counts_as_needing_you() {
    let mut t = tracker_knowing_wsl();
    t.observe(WSL, &[agent("t1", Some("working"), 0)], 1);
    t.observe(WSL, &[agent("t1", Some("error"), 0)], 2);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Error));
    assert!(Attention::Error.needs_you());
    assert_eq!(Attention::Error.group(), Attention::NeedsYou.group());
    // At the top, above a finished one that changed later.
    t.observe(
        WSL,
        &[
            agent("t1", Some("error"), 0),
            agent("t2", Some("working"), 0),
        ],
        3,
    );
    t.observe(
        WSL,
        &[agent("t1", Some("error"), 0), agent("t2", Some("idle"), 1)],
        4,
    );
    assert_eq!(t.rows()[0].address, "t1");
}

#[test]
fn working_to_idle_is_finished() {
    for end in ["idle", "done"] {
        for start in ["working", "blocked", "error"] {
            let mut t = tracker_knowing_wsl();
            t.observe(WSL, &[agent("t1", Some(start), 0)], 1);
            t.observe(WSL, &[agent("t1", Some(end), 1)], 2);
            assert_eq!(
                state_of(&t, "t1"),
                Some(Attention::Done),
                "{start} -> {end}"
            );
        }
    }
}

#[test]
fn finished_unseen_becomes_idle_once_opened() {
    let mut t = tracker_knowing_wsl();
    t.observe(WSL, &[agent("t1", Some("working"), 0)], 1);
    t.observe(WSL, &[agent("t1", Some("idle"), 1)], 2);
    t.mark_seen(WSL, "t1");
    assert_eq!(state_of(&t, "t1"), Some(Attention::Idle));
    // A finish watched on the phone is idle straight away.
    t.set_looking(Some((WSL, "t1")));
    t.observe(WSL, &[agent("t1", Some("working"), 1)], 3);
    t.observe(WSL, &[agent("t1", Some("done"), 2)], 4);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Idle));
}

#[test]
fn anything_to_working_is_working_and_blocked_needs_you() {
    let mut t = tracker_knowing_wsl();
    t.observe(WSL, &[agent("t1", Some("idle"), 0)], 1);
    t.observe(WSL, &[agent("t1", Some("working"), 0)], 2);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Working));
    t.observe(WSL, &[agent("t1", Some("blocked"), 1)], 3);
    assert_eq!(state_of(&t, "t1"), Some(Attention::NeedsYou));
    t.observe(WSL, &[agent("t1", Some("idle"), 1)], 4);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Done));
    t.observe(WSL, &[agent("t1", Some("working"), 1)], 5);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Working));
}

#[test]
fn sub_agents() {
    let mut t = tracker_knowing_wsl();
    let sub = |s: &str, turn| with(agent("t2", Some(s), turn), "parent", json!("t1"));
    t.observe(
        WSL,
        &[agent("t1", Some("working"), 0), sub("working", 0)],
        1,
    );
    t.observe(WSL, &[agent("t1", Some("working"), 0), sub("idle", 1)], 2);
    assert!(t.rows().iter().all(|r| r.address != "t2"));
    assert!(t.others().iter().all(|r| r.address != "t2"));
    assert_eq!(t.rows().len(), 1);
}

// ---- finishes while the app was away (plan pick 2b) ----

#[test]
fn a_finish_while_disconnected_is_unseen_after_reconnecting() {
    let mut t = tracker_knowing_wsl();
    t.observe(WSL, &[agent("t1", Some("working"), 4)], 1);
    // Disconnected; the next snapshot already has it idle.
    t.observe(WSL, &[agent("t1", Some("idle"), 5)], 2);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Done));
}

#[test]
fn a_finish_while_the_app_was_closed_is_unseen_after_a_restart() {
    // First contact: what is there now counts as seen.
    let mut before = Tracker::default();
    before.observe(
        WSL,
        &[
            agent("t1", Some("idle"), 4),
            agent("t2", Some("working"), 7),
        ],
        1,
    );
    assert_eq!(state_of(&before, "t1"), Some(Attention::Idle));
    let memory = before.memory();
    // The app process ended; a new one restores what it remembered.
    let mut t = Tracker::restore(&memory);
    t.observe(
        WSL,
        &[agent("t1", Some("idle"), 4), agent("t2", Some("idle"), 8)],
        2,
    );
    assert_eq!(state_of(&t, "t1"), Some(Attention::Idle));
    assert_eq!(state_of(&t, "t2"), Some(Attention::Done));
    // An unseen finish stays unseen across a restart until opened.
    let mut again = Tracker::restore(&t.memory());
    again.observe(
        WSL,
        &[agent("t1", Some("idle"), 4), agent("t2", Some("idle"), 8)],
        3,
    );
    assert_eq!(state_of(&again, "t2"), Some(Attention::Done));
    again.mark_seen(WSL, "t2");
    let mut third = Tracker::restore(&again.memory());
    third.observe(WSL, &[agent("t2", Some("idle"), 8)], 4);
    assert_eq!(state_of(&third, "t2"), Some(Attention::Idle));
}

#[test]
fn a_turn_that_came_and_went_between_two_looks_is_unseen() {
    // working -> idle inside one batch of events: the word never changed in
    // what the tracker saw, the turn counter did.
    let mut t = Tracker::default();
    t.observe(WSL, &[agent("t1", Some("idle"), 2)], 1);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Idle));
    t.observe(WSL, &[agent("t1", Some("idle"), 3)], 2);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Done));
}

#[test]
fn the_first_contact_with_a_machine_shows_nothing_as_unseen() {
    let mut t = Tracker::default();
    t.observe(WSL, &[agent("t1", Some("idle"), 12)], 1);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Idle));
    // Later, a terminal that appeared and finished a turn while away is unseen.
    let mut t = Tracker::restore(&t.memory());
    t.observe(
        WSL,
        &[agent("t1", Some("idle"), 12), agent("t9", Some("idle"), 1)],
        2,
    );
    assert_eq!(state_of(&t, "t9"), Some(Attention::Done));
    assert_eq!(state_of(&t, "t1"), Some(Attention::Idle));
}

#[test]
fn removed_terminals_and_forgotten_machines_leave_memory() {
    let mut t = tracker_knowing_wsl();
    t.observe(WSL, &[agent("t1", Some("idle"), 1)], 1);
    t.observe("server", &[agent("t1", Some("idle"), 1)], 1);
    t.observe(WSL, &[], 2);
    assert!(t.memory()["machines"][WSL].as_object().unwrap().is_empty());
    t.forget_machine("server");
    assert!(t.memory()["machines"].get("server").is_none());
    assert!(t.rows().is_empty());
}

// ---- the home list ----

#[test]
fn two_agents_on_wsl() {
    let mut t = tracker_knowing_wsl();
    t.observe(
        WSL,
        &[
            agent("t1", Some("working"), 0),
            agent("t2", Some("working"), 0),
        ],
        1,
    );
    let rows = t.rows();
    assert_eq!(rows.len(), 2);
    assert!(rows.iter().all(|r| r.machine == WSL));
    assert!(rows.iter().all(|r| r.name == "Claude Code"));
}

#[test]
fn a_build_tab() {
    let mut t = tracker_knowing_wsl();
    let build = json!({"address": "t3", "state": "live", "cwd": "/home/me/src/api", "argv": ["/usr/bin/make", "-j8"],
                       "labels": {}, "name": null, "parent": null, "reported": false, "status": null, "turn": 0});
    t.observe(WSL, &[agent("t1", Some("idle"), 0), build], 1);
    assert!(t.rows().iter().all(|r| r.address != "t3"));
    let others = t.others();
    assert_eq!(others.len(), 1);
    assert_eq!(
        (
            others[0].address.as_str(),
            others[0].name.as_str(),
            others[0].folder.as_str()
        ),
        ("t3", "make", "api")
    );
    assert_eq!(others[0].machine, WSL);
}

#[test]
fn finished_terminals_are_not_listed() {
    let mut t = tracker_knowing_wsl();
    let gone = with(agent("t1", None, 1), "state", json!("finished"));
    let plain = with(
        with(agent("t2", None, 0), "reported", json!(false)),
        "state",
        json!("finished"),
    );
    t.observe(WSL, &[gone, plain], 1);
    assert!(t.rows().is_empty() && t.others().is_empty());
}

#[test]
fn names_come_from_the_terminal_then_the_program_then_the_id() {
    let mut t = tracker_knowing_wsl();
    let named = with(agent("t1", Some("idle"), 0), "name", json!("fix login"));
    let codex = status(
        agent("t2", Some("idle"), 0),
        json!({"state": "idle", "app": "codex"}),
    );
    let unknown_app = status(
        agent("t3", Some("idle"), 0),
        json!({"state": "idle", "app": "aider"}),
    );
    let no_app = with(
        status(agent("t4", Some("idle"), 0), json!({"state": "idle"})),
        "argv",
        json!([]),
    );
    t.observe(WSL, &[named, codex, unknown_app, no_app], 1);
    let name = |a: &str| t.rows().into_iter().find(|r| r.address == a).unwrap().name;
    assert_eq!(name("t1"), "fix login");
    assert_eq!(name("t2"), "Codex");
    assert_eq!(name("t3"), "aider");
    assert_eq!(name("t4"), "t4");
}

#[test]
fn the_order_is_needs_you_finished_working_idle_newest_first() {
    let mut t = tracker_knowing_wsl();
    let all = |states: [(&str, &str, u64); 6]| states.map(|(a, s, turn)| agent(a, Some(s), turn));
    t.observe(
        WSL,
        &all([
            ("t1", "idle", 0),
            ("t2", "working", 0),
            ("t3", "working", 0),
            ("t4", "working", 0),
            ("t5", "working", 0),
            ("t6", "idle", 0),
        ]),
        1,
    );
    t.observe(
        WSL,
        &all([
            ("t1", "idle", 0),
            ("t2", "idle", 1),
            ("t3", "working", 0),
            ("t4", "blocked", 1),
            ("t5", "working", 0),
            ("t6", "idle", 0),
        ]),
        2,
    );
    t.observe(
        WSL,
        &all([
            ("t1", "idle", 0),
            ("t2", "idle", 1),
            ("t3", "error", 1),
            ("t4", "blocked", 1),
            ("t5", "idle", 1),
            ("t6", "idle", 0),
        ]),
        3,
    );
    let order: Vec<String> = t.rows().into_iter().map(|r| r.address).collect();
    // t3 and t4 need you (t3 newer); t5 then t2 finished; nothing working; t1, t6 idle.
    assert_eq!(order[..4], ["t3", "t4", "t5", "t2"]);
    assert_eq!(order[4..].len(), 2);
}

#[test]
fn a_new_permission_prompt() {
    let mut t = tracker_knowing_wsl();
    t.observe(
        WSL,
        &[
            agent("t1", Some("working"), 0),
            agent("t2", Some("working"), 0),
        ],
        1,
    );
    assert_eq!(t.rows()[0].address, "t1");
    let blocked = status(
        agent("t2", Some("blocked"), 1),
        json!({"state": "blocked", "kind": "permission", "msg": "Run npm test?"}),
    );
    t.observe(WSL, &[agent("t1", Some("working"), 0), blocked], 2);
    let top = &t.rows()[0];
    assert_eq!(top.address, "t2");
    assert_eq!(top.since_ms, Some(2));
}

#[test]
fn ages_come_from_the_core_on_a_snapshot_and_from_the_phone_on_a_live_change() {
    let mut t = tracker_knowing_wsl();
    t.observe(
        WSL,
        &[with(
            agent("t1", Some("working"), 0),
            "status_ms",
            json!(500),
        )],
        9_000,
    );
    assert_eq!(t.rows()[0].since_ms, Some(500));
    // Status events carry no `status_ms`; the record keeps the old one.
    t.observe(
        WSL,
        &[with(
            agent("t1", Some("blocked"), 1),
            "status_ms",
            json!(500),
        )],
        9_100,
    );
    assert_eq!(t.rows()[0].since_ms, Some(9_100));
    // A reconnect's snapshot has the core's time again.
    t.observe(
        WSL,
        &[with(
            agent("t1", Some("idle"), 1),
            "status_ms",
            json!(9_050),
        )],
        20_000,
    );
    assert_eq!(t.rows()[0].since_ms, Some(9_050));
}

#[test]
fn permission_with_message() {
    let mut t = tracker_knowing_wsl();
    let blocked = status(
        agent("t1", Some("blocked"), 0),
        json!({"state": "blocked", "kind": "permission", "msg": "Run npm test?"}),
    );
    t.observe(WSL, &[blocked], 1);
    assert_eq!(t.rows()[0].label, "Permission: Run npm test?");
}

#[test]
fn no_kind() {
    let mut t = tracker_knowing_wsl();
    t.observe(
        WSL,
        &[status(
            agent("t1", Some("blocked"), 0),
            json!({"state": "blocked"}),
        )],
        1,
    );
    assert_eq!(t.rows()[0].label, "Needs you");
    t.observe(
        WSL,
        &[status(
            agent("t1", Some("blocked"), 0),
            json!({"state": "blocked", "kind": "question"}),
        )],
        2,
    );
    assert_eq!(t.rows()[0].label, "Question");
    t.observe(
        WSL,
        &[status(
            agent("t1", Some("blocked"), 0),
            json!({"state": "blocked", "msg": "Which branch?"}),
        )],
        3,
    );
    assert_eq!(t.rows()[0].label, "Which branch?");
    t.observe(
        WSL,
        &[status(
            agent("t1", Some("error"), 0),
            json!({"state": "error", "msg": "rate limited"}),
        )],
        4,
    );
    assert_eq!(t.rows()[0].label, "Error: rate limited");
}

#[test]
fn progress_reported() {
    let mut t = tracker_knowing_wsl();
    t.observe(
        WSL,
        &[status(
            agent("t1", Some("working"), 0),
            json!({"state": "working", "progress": 40}),
        )],
        1,
    );
    assert_eq!(t.rows()[0].progress, Some(40));
    t.observe(WSL, &[agent("t1", Some("working"), 0)], 2);
    assert_eq!(t.rows()[0].progress, None);
    // Out of range is not a bar.
    t.observe(
        WSL,
        &[status(
            agent("t1", Some("working"), 0),
            json!({"state": "working", "progress": 250}),
        )],
        3,
    );
    assert_eq!(t.rows()[0].progress, None);
}

#[test]
fn a_disconnected_machine_keeps_its_rows() {
    let mut t = tracker_knowing_wsl();
    t.observe(WSL, &[agent("t1", Some("working"), 0)], 1);
    // No observe while unreachable: the last rows stay ("as of hh:mm" is the UI's).
    assert_eq!(t.rows().len(), 1);
}

#[test]
fn row_line_two_is_the_folder() {
    let mut t = tracker_knowing_wsl();
    let moved = with(
        agent("t1", Some("idle"), 0),
        "foreground",
        json!({"pid": 1, "name": "claude", "argv": [], "cwd": "/home/me/src/docs"}),
    );
    t.observe(WSL, &[agent("t2", Some("idle"), 0), moved], 1);
    let folder = |a: &str| {
        t.rows()
            .into_iter()
            .find(|r| r.address == a)
            .unwrap()
            .folder
    };
    assert_eq!(folder("t1"), "docs");
    assert_eq!(folder("t2"), "website");
}

#[test]
fn folders_name_as_in_herdr() {
    assert_eq!(folder_name("/home/can", Some("/home/can")), "~");
    assert_eq!(folder_name("/home/can/", Some("/home/can")), "~");
    assert_eq!(folder_name("/Users/can/Projects/herdr", None), "herdr");
    assert_eq!(folder_name("/", None), "/");
    assert_eq!(folder_name("", None), "~");
    assert_eq!(folder_name(r"C:\Users\can\src\api", None), "api");
    assert_eq!(folder_name("C:/Users/can/src/web", None), "web");
}

// ---- review finding: unseen finishes that never moved the turn counter ----

#[test]
fn a_blocked_finish_stays_unseen_across_a_restart() {
    for (busy_word, turn) in [("blocked", 3), ("error", 3), ("blocked", 0)] {
        let mut t = Tracker::default();
        t.observe(WSL, &[agent("t1", Some(busy_word), turn)], 1);
        // Leaving blocked or error does not raise `turn` (only leaving working does).
        t.observe(WSL, &[agent("t1", Some("idle"), turn)], 2);
        assert_eq!(
            state_of(&t, "t1"),
            Some(Attention::Done),
            "{busy_word} at turn {turn}"
        );
        let mut t = Tracker::restore(&t.memory());
        t.observe(WSL, &[agent("t1", Some("idle"), turn)], 3);
        assert_eq!(
            state_of(&t, "t1"),
            Some(Attention::Done),
            "{busy_word} at turn {turn}, restored"
        );
    }
}

#[test]
fn a_blocked_agent_that_finished_while_the_app_was_closed_is_unseen() {
    for (busy_word, end) in [("blocked", "idle"), ("error", "done"), ("working", "idle")] {
        let mut t = Tracker::default();
        t.observe(WSL, &[agent("t1", Some(busy_word), 0)], 1);
        let mut t = Tracker::restore(&t.memory());
        // `working` raises the turn on its way out; blocked and error do not.
        let turn = u64::from(busy_word == "working");
        t.observe(WSL, &[agent("t1", Some(end), turn)], 2);
        assert_eq!(
            state_of(&t, "t1"),
            Some(Attention::Done),
            "{busy_word} -> {end}"
        );
    }
}

#[test]
fn a_seen_blocked_agent_that_was_answered_is_idle_after_a_restart() {
    // Opened while blocked, then it went back to work and finished: unseen.
    // Opened after it finished: seen, and stays seen after a restart.
    let mut t = Tracker::default();
    t.observe(WSL, &[agent("t1", Some("blocked"), 2)], 1);
    t.observe(WSL, &[agent("t1", Some("idle"), 2)], 2);
    t.mark_seen(WSL, "t1");
    let mut t = Tracker::restore(&t.memory());
    t.observe(WSL, &[agent("t1", Some("idle"), 2)], 3);
    assert_eq!(state_of(&t, "t1"), Some(Attention::Idle));
}

// ---- what the home screen draws (task M1.4) ----

/// The card shows the kind in red above the request in terminal type
/// (design-brief "Signature element"); the one-line label stays for the rest.
#[test]
fn a_needs_you_row_carries_its_kind_and_request_apart() {
    let cases = [
        (
            json!({"state": "blocked", "kind": "permission", "msg": "Run npm test?"}),
            Some("Permission"),
            Some("Run npm test?"),
        ),
        (json!({"state": "blocked"}), Some("Needs you"), None),
        (
            json!({"state": "blocked", "kind": "question"}),
            Some("Question"),
            None,
        ),
        (
            json!({"state": "blocked", "msg": "Which branch?"}),
            Some("Needs you"),
            Some("Which branch?"),
        ),
        (
            json!({"state": "blocked", "kind": "", "msg": ""}),
            Some("Needs you"),
            None,
        ),
        (
            json!({"state": "error", "msg": "rate limited"}),
            Some("Error"),
            Some("rate limited"),
        ),
        (json!({"state": "error"}), Some("Error"), None),
        // Only rows that need you carry a request.
        (
            json!({"state": "working", "kind": "permission", "msg": "stale"}),
            None,
            None,
        ),
        (json!({"state": "idle", "msg": "stale"}), None, None),
    ];
    for (st, kind, request) in cases {
        let mut t = tracker_knowing_wsl();
        t.observe(WSL, &[status(agent("t1", Some("idle"), 0), st.clone())], 1);
        let row = &t.rows()[0];
        assert_eq!(row.request_kind.as_deref(), kind, "{st}");
        assert_eq!(row.request.as_deref(), request, "{st}");
    }
}

/// The program decides the row's icon: the reported app first, else the
/// command's file name, named one way for each program.
#[test]
fn the_program_is_named_for_its_icon() {
    let mut t = tracker_knowing_wsl();
    let app = |a: &str, app: &str| status(agent(a, Some("idle"), 0), json!({"state": "idle", "app": app}));
    let claude_code = app("t1", "claude-code");
    let claude = app("t2", "claude");
    let codex = app("t3", "codex");
    let cursor = app("t4", "cursor-agent");
    let from_argv = with(
        status(agent("t5", Some("idle"), 0), json!({"state": "idle"})),
        "argv",
        json!(["/usr/local/bin/codex", "--yolo"]),
    );
    let nothing = with(
        status(agent("t6", Some("idle"), 0), json!({"state": "idle"})),
        "argv",
        json!([]),
    );
    t.observe(
        WSL,
        &[claude_code, claude, codex, cursor, from_argv, nothing],
        1,
    );
    let program = |a: &str| {
        t.rows()
            .into_iter()
            .find(|r| r.address == a)
            .unwrap()
            .program
    };
    assert_eq!(program("t1").as_deref(), Some("claude"));
    assert_eq!(program("t2").as_deref(), Some("claude"));
    assert_eq!(program("t3").as_deref(), Some("codex"));
    assert_eq!(program("t4").as_deref(), Some("cursor"));
    assert_eq!(program("t5").as_deref(), Some("codex"));
    assert_eq!(program("t6"), None);
}

#[test]
fn other_terminals_name_their_program() {
    let mut t = tracker_knowing_wsl();
    let build = json!({"address": "t3", "state": "live", "cwd": "/home/me/src/api", "argv": ["/usr/bin/make", "-j8"],
                       "labels": {}, "name": null, "parent": null, "reported": false, "status": null, "turn": 0});
    let shell = json!({"address": "t4", "state": "live", "cwd": "/home/me", "argv": [],
                       "labels": {}, "name": null, "parent": null, "reported": false, "status": null, "turn": 0});
    t.observe(WSL, &[build, shell], 1);
    let others = t.others();
    assert_eq!(others[0].program.as_deref(), Some("make"));
    assert_eq!(others[1].program, None);
}
