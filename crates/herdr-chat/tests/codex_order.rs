//! Codex turn order (design D7). Shapes from a Codex 0.169 rollout: a
//! command started with a short wait goes on running, the turn ends, and its
//! `CommandExecution` is written after the turn's `task_complete`.

use herdr_chat::{Agent, Item, Parser, keep_keys};

fn j(s: &str) -> String {
    serde_json::to_string(s).unwrap()
}
fn event(kind: &str, turn: &str) -> String {
    format!("{{\"type\":\"event_msg\",\"payload\":{{\"type\":\"{kind}\",\"turn_id\":\"{turn}\"}}}}")
}
fn item(turn: &str, item: &str) -> String {
    format!(
        "{{\"type\":\"event_msg\",\"payload\":{{\"type\":\"item_completed\",\"turn_id\":\"{turn}\",\"item\":{item}}}}}"
    )
}
fn asked(turn: &str, text: &str) -> String {
    item(
        turn,
        &format!(
            "{{\"type\":\"UserMessage\",\"id\":\"u-{text}\",\"content\":[{{\"type\":\"text\",\"text\":{}}}]}}",
            j(text)
        ),
    )
}
fn said(turn: &str, phase: &str, text: &str) -> String {
    item(
        turn,
        &format!(
            "{{\"type\":\"AgentMessage\",\"id\":\"a-{text}\",\"phase\":\"{phase}\",\"content\":[{{\"type\":\"Text\",\"text\":{}}}]}}",
            j(text)
        ),
    )
}
fn ran(turn: &str, id: &str, cmd: &str, source: &str) -> String {
    item(
        turn,
        &format!(
            "{{\"type\":\"CommandExecution\",\"id\":\"{id}\",\"command\":[\"/usr/bin/zsh\",\"-lc\",{c}],\"parsed_cmd\":[{{\"type\":\"unknown\",\"cmd\":{c}}}],\"source\":\"{source}\",\"status\":\"completed\",\"aggregated_output\":\"\",\"exit_code\":0}}",
            c = j(cmd)
        ),
    )
}

fn user(text: &str) -> Item {
    Item::User {
        text: text.into(),
        images: vec![],
    }
}
fn text(t: &str) -> Item {
    Item::Assistant { text: t.into() }
}
fn titles(item: &Item) -> Vec<String> {
    match item {
        Item::Tools { calls } => calls.iter().map(|c| c.title.clone()).collect(),
        other => panic!("tools expected, got {other:?}"),
    }
}

/// The rollout's turn: a question, a note while working, a command left
/// running, a steering message, the answer, then the command's record.
fn late_command_log() -> Vec<String> {
    vec![
        event("task_started", "t1"),
        asked("t1", "Run sleep 8, then say FIRST"),
        said("t1", "commentary", "Running it."),
        asked("t1", "Say SECOND"),
        said("t1", "final_answer", "SECOND"),
        event("task_complete", "t1"),
        ran("t1", "exec-1", "sleep 8", "unified_exec_startup"),
        event("task_started", "t2"),
        ran("t2", "exec-2", "echo bang-check", "user_shell"),
        event("task_complete", "t2"),
    ]
}

fn joined(lines: &[String]) -> (String, Vec<u64>) {
    let mut text = String::new();
    let mut at = Vec::new();
    for l in lines {
        at.push(text.len() as u64);
        text.push_str(l);
        text.push('\n');
    }
    (text, at)
}

#[test]
fn a_command_written_after_task_complete_sits_before_that_turns_answer() {
    let (log, _) = joined(&late_command_log());
    let mut p = Parser::new(Agent::Codex);
    p.feed(&log);
    let items = p.items();
    assert_eq!(items[0], user("Run sleep 8, then say FIRST"));
    assert_eq!(items[1], text("Running it."));
    assert_eq!(items[2], user("Say SECOND"));
    assert_eq!(titles(&items[3]), ["sleep 8"]);
    assert_eq!(items[4], text("SECOND"));
    // The next turn's `!` command: the person's row with its output.
    assert_eq!(items[5], user("!echo bang-check"));
    assert_eq!(titles(&items[6]), ["echo bang-check"]);
    assert_eq!(items.len(), 7, "{items:#?}");
    // The moved row keeps its own line as its key and source.
    let (_, at) = joined(&late_command_log());
    assert_eq!(p.first_keys()[3], (at[6], 0));
    assert_eq!(p.sources()[3], vec![at[6]]);
}

#[test]
fn the_late_command_lands_the_same_when_it_comes_in_a_later_read() {
    let lines = late_command_log();
    let (text_all, at) = joined(&lines);
    let mut whole = Parser::new(Agent::Codex);
    whole.feed(&text_all);
    // The answer and `task_complete` in one read, the command in the next.
    let mut live = Parser::new(Agent::Codex);
    let (a, _) = joined(&lines[..6]);
    let (b, _) = joined(&lines[6..]);
    live.feed_lines(&a, &at[..6]);
    let shown = live.items().to_vec();
    assert_eq!(shown.last(), Some(&text("SECOND")));
    live.feed_lines(&b, &at[6..]);
    assert_eq!(live.items(), whole.items());
    assert_eq!(live.first_keys(), whole.first_keys());
    // Read back from the start: the same rows keep their keys.
    let kept = keep_keys(live.first_keys(), whole.first_keys(), whole.sources());
    assert_eq!(kept, live.first_keys());
}

#[test]
fn a_late_command_folds_into_the_tool_row_before_the_answer() {
    let lines = vec![
        event("task_started", "t1"),
        asked("t1", "Build it"),
        ran("t1", "exec-1", "cargo build", "unified_exec_startup"),
        said("t1", "final_answer", "Built."),
        event("task_complete", "t1"),
        ran("t1", "exec-2", "sleep 20", "unified_exec_startup"),
    ];
    let (log, at) = joined(&lines);
    let mut p = Parser::new(Agent::Codex);
    p.feed(&log);
    let items = p.items();
    assert_eq!(titles(&items[1]), ["cargo build", "sleep 20"]);
    assert_eq!(items[2], text("Built."));
    assert_eq!(items.len(), 3);
    assert_eq!(p.sources()[1], vec![at[2], at[5]]);
}

#[test]
fn a_late_command_in_a_turn_without_an_answer_goes_last() {
    let lines = vec![
        event("task_started", "t1"),
        asked("t1", "Start the server"),
        event("task_complete", "t1"),
        ran("t1", "exec-1", "npm run dev", "unified_exec_startup"),
    ];
    let (log, _) = joined(&lines);
    let mut p = Parser::new(Agent::Codex);
    p.feed(&log);
    assert_eq!(p.items()[0], user("Start the server"));
    assert_eq!(titles(&p.items()[1]), ["npm run dev"]);
}

#[test]
fn a_late_command_goes_before_its_own_turns_answer_not_a_later_one() {
    let mut lines = late_command_log()[..6].to_vec();
    lines.extend([
        event("task_started", "t2"),
        asked("t2", "And now?"),
        said("t2", "final_answer", "Done."),
        // t1's command, finishing after the next turn began.
        ran("t1", "exec-1", "sleep 8", "unified_exec_startup"),
    ]);
    let (log, _) = joined(&lines);
    let mut p = Parser::new(Agent::Codex);
    p.feed(&log);
    let items = p.items();
    assert_eq!(titles(&items[3]), ["sleep 8"]);
    assert_eq!(items[4], text("SECOND"));
    assert_eq!(items[5], user("And now?"));
    assert_eq!(items[6], text("Done."));
}

#[test]
fn a_command_run_twice_shows_twice() {
    // Why a turn listed `sleep 20` twice under "Ran 2 commands": the agent
    // ran it with a 1 s wait, then again with a 30 s wait; two processes,
    // two items with their own ids. No item id was written twice in the
    // November rollouts (794 items).
    let lines = vec![
        event("task_started", "t1"),
        asked("t1", "Sleep 20 seconds"),
        ran("t1", "exec-a", "sleep 20", "unified_exec_startup"),
        ran("t1", "exec-b", "sleep 20", "unified_exec_startup"),
        said("t1", "final_answer", "Slept."),
        event("task_complete", "t1"),
    ];
    let (log, _) = joined(&lines);
    let mut p = Parser::new(Agent::Codex);
    p.feed(&log);
    assert_eq!(titles(&p.items()[1]), ["sleep 20", "sleep 20"]);
}
