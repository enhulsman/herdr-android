//! Row identity (design D3): every row records the offsets of all its log
//! lines; a row's key is set when it is first seen and kept across read-backs.

use herdr_chat::{Agent, Item, Key, Parser, keep_keys};
use std::collections::HashSet;

fn tool_use(id: &str, cmd: &str) -> String {
    format!(
        "{{\"type\":\"assistant\",\"sessionId\":\"s\",\"message\":{{\"id\":\"m-{id}\",\"role\":\"assistant\",\"content\":[{{\"type\":\"tool_use\",\"id\":\"{id}\",\"name\":\"Bash\",\"input\":{{\"command\":\"{cmd}\"}}}}]}}}}"
    )
}
fn tool_result(id: &str, out: &str) -> String {
    format!(
        "{{\"type\":\"user\",\"sessionId\":\"s\",\"message\":{{\"role\":\"user\",\"content\":[{{\"type\":\"tool_result\",\"tool_use_id\":\"{id}\",\"content\":\"{out}\"}}]}}}}"
    )
}
fn said(id: &str, text: &str) -> String {
    format!(
        "{{\"type\":\"assistant\",\"sessionId\":\"s\",\"message\":{{\"id\":\"m-{id}\",\"role\":\"assistant\",\"content\":[{{\"type\":\"text\",\"text\":\"{text}\"}}]}}}}"
    )
}
/// One record with text and a tool call: two rows from one line.
fn said_and_ran(id: &str, text: &str, tool: &str, cmd: &str) -> String {
    format!(
        "{{\"type\":\"assistant\",\"sessionId\":\"s\",\"message\":{{\"id\":\"m-{id}\",\"role\":\"assistant\",\"content\":[{{\"type\":\"text\",\"text\":\"{text}\"}},{{\"type\":\"tool_use\",\"id\":\"{tool}\",\"name\":\"Bash\",\"input\":{{\"command\":\"{cmd}\"}}}}]}}}}"
    )
}
fn asked(text: &str) -> String {
    format!(
        "{{\"type\":\"user\",\"sessionId\":\"s\",\"message\":{{\"role\":\"user\",\"content\":{}}}}}",
        serde_json::to_string(text).unwrap()
    )
}
fn queued(origin: &str, prompt: &str) -> String {
    format!(
        "{{\"type\":\"attachment\",\"sessionId\":\"s\",\"isSidechain\":false,\"attachment\":{{\"type\":\"queued_command\",\"commandMode\":\"prompt\",\"prompt\":{},\"origin\":{origin}}}}}",
        serde_json::to_string(prompt).unwrap()
    )
}

/// The log's text and where each line starts.
fn log(lines: &[String]) -> (String, Vec<u64>) {
    let mut text = String::new();
    let mut at = Vec::new();
    for l in lines {
        at.push(text.len() as u64);
        text.push_str(l);
        text.push('\n');
    }
    (text, at)
}

/// Lines `from..` of the log, parsed with their offsets, as a read would.
fn parse_from(lines: &[String], from: usize) -> Parser {
    let (_, at) = log(lines);
    let text: String = lines[from..].iter().map(|l| format!("{l}\n")).collect();
    let mut p = Parser::new(Agent::Claude);
    p.feed_lines(&text, &at[from..]);
    p
}

fn calls(item: &Item) -> usize {
    match item {
        Item::Tools { calls } => calls.len(),
        other => panic!("tools expected, got {other:?}"),
    }
}

#[test]
fn feed_gives_lines_their_byte_offsets_from_the_start() {
    let lines = vec![asked("Hi"), said("a", "Hello"), asked("Bye")];
    let (text, at) = log(&lines);
    let mut p = Parser::new(Agent::Claude);
    p.feed(&text);
    assert_eq!(p.sources(), &[vec![at[0]], vec![at[1]], vec![at[2]]]);
    assert_eq!(p.first_keys(), &[(at[0], 0), (at[1], 0), (at[2], 0)]);
}

#[test]
fn a_folded_tool_row_records_every_call_and_result_line() {
    let lines = vec![
        asked("Run them"),
        tool_use("t1", "ls"),
        tool_result("t1", "a"),
        tool_use("t2", "pwd"),
        tool_result("t2", "/w"),
        said("x", "Done"),
    ];
    let (_, at) = log(&lines);
    let p = parse_from(&lines, 0);
    assert_eq!(calls(&p.items()[1]), 2);
    assert_eq!(p.sources()[1], vec![at[1], at[2], at[3], at[4]]);
    assert_eq!(p.first_keys()[1], (at[1], 0));
}

#[test]
fn two_rows_from_one_line_get_their_index_within_it() {
    let lines = vec![asked("Go"), said_and_ran("a", "Running it", "t1", "make"), tool_result("t1", "ok")];
    let (_, at) = log(&lines);
    let p = parse_from(&lines, 0);
    assert_eq!(p.items().len(), 3);
    assert_eq!(&p.first_keys()[1..], &[(at[1], 0), (at[1], 1)]);
    assert_eq!(p.sources()[2], vec![at[1], at[2]]);
}

#[test]
fn a_folded_tool_row_gaining_older_calls_keeps_its_key_and_place() {
    let lines = vec![
        asked("Run them"),
        tool_use("t1", "a"),
        tool_result("t1", "1"),
        tool_use("t2", "b"),
        tool_result("t2", "2"),
        tool_use("t3", "c"),
        tool_result("t3", "3"),
        tool_use("t4", "d"),
        tool_result("t4", "4"),
        said("x", "All four ran"),
        asked("Thanks"),
    ];
    // The recent window starts at t3: its first row is a run of two calls.
    let window = parse_from(&lines, 5);
    assert_eq!(calls(&window.items()[0]), 2);
    let held = window.first_keys().to_vec();

    // Reading back parses everything held again, from the start.
    let back = parse_from(&lines, 0);
    assert_eq!(calls(&back.items()[1]), 4, "the older calls join the same row");
    let keys = keep_keys(&held, back.first_keys(), back.sources());
    assert_eq!(keys[1], held[0], "the tool row keeps the key it was first seen with");
    assert_eq!(&keys[1..], &held[..], "every held row keeps its key, in the same order");
    assert_eq!(keys[0], back.first_keys()[0], "the new older row keys itself");
}

#[test]
fn a_persons_row_meeting_its_older_queued_copy_is_one_row_with_the_screen_key() {
    let note = "<task-notification><summary>Agent \"Docs\" finished</summary></task-notification>";
    let lines = vec![
        asked("Write the docs"),
        queued("{\"kind\":\"task-notification\",\"producer\":\"x\"}", note),
        said("a", "Working on it"),
        asked(note),
        said("b", "The docs agent is done"),
    ];
    let (_, at) = log(&lines);
    // On screen: the window from the repeated user record on.
    let window = parse_from(&lines, 3);
    assert_eq!(window.items()[0], Item::Note { text: "Agent \"Docs\" finished".into() });
    let held = window.first_keys().to_vec();

    let back = parse_from(&lines, 0);
    let notes = back.items().iter().filter(|i| matches!(i, Item::Note { .. })).count();
    assert_eq!(notes, 1, "one row, not the copy and the record");
    let i = back.items().iter().position(|i| matches!(i, Item::Note { .. })).unwrap();
    assert_eq!(back.sources()[i], vec![at[1], at[3]], "the merged row has both lines");
    let keys = keep_keys(&held, back.first_keys(), back.sources());
    assert_eq!(keys[i], held[0], "it keeps the key it had on screen");
    assert_eq!(keys.iter().collect::<HashSet<_>>().len(), keys.len());
}

#[test]
fn a_peer_message_and_its_repeat_are_one_row_with_both_lines() {
    let peer = "<agent-message from=\"docs-writer\">Check the README</agent-message>";
    let lines = vec![queued("{\"kind\":\"peer\",\"name\":\"docs-writer\"}", peer), asked(peer)];
    let (_, at) = log(&lines);
    let p = parse_from(&lines, 0);
    assert_eq!(p.items().len(), 1);
    assert_eq!(p.sources()[0], vec![at[0], at[1]]);
}

/// A longer log: questions, answers, runs of tool calls, two-row records.
fn long_log() -> Vec<String> {
    let mut lines = Vec::new();
    for t in 0..12 {
        lines.push(asked(&format!("Question {t}")));
        if t % 3 == 0 {
            lines.push(said_and_ran(&format!("s{t}"), "Checking", &format!("u{t}"), "git status"));
            lines.push(tool_result(&format!("u{t}"), "clean"));
        }
        for c in 0..(t % 4) {
            let id = format!("c{t}-{c}");
            lines.push(tool_use(&id, "make"));
            lines.push(tool_result(&id, "ok"));
        }
        lines.push(said(&format!("a{t}"), &format!("Answer {t}")));
    }
    lines
}

#[test]
fn a_log_read_in_one_go_and_read_back_in_pieces_gives_the_same_rows() {
    let lines = long_log();
    let whole = parse_from(&lines, 0);

    // The chat: the last lines first, then older pieces, each parsed together
    // with everything held, keys carried over by keep_keys.
    let mut from = lines.len() - 7;
    let first = parse_from(&lines, from);
    let mut keys = first.first_keys().to_vec();
    let mut items = first.items().to_vec();
    let mut sources = first.sources().to_vec();
    while from > 0 {
        from = from.saturating_sub(5);
        let p = parse_from(&lines, from);
        let next = keep_keys(&keys, p.first_keys(), p.sources());
        for k in &keys {
            assert!(next.contains(k), "held key {k:?} lost reading back to line {from}");
        }
        assert_eq!(next.iter().collect::<HashSet<_>>().len(), next.len(), "keys stay unique");
        keys = next;
        items = p.items().to_vec();
        sources = p.sources().to_vec();
    }
    assert_eq!(items, whole.items(), "same rows in the same order");
    assert_eq!(sources, whole.sources(), "and from the same lines");
}

#[test]
fn keep_keys_gives_a_held_key_once_and_new_rows_their_own() {
    // Two fresh rows from one line: the held keys of that line go each to its own row.
    let held: Vec<Key> = vec![(100, 0), (100, 1)];
    let first: Vec<Key> = vec![(40, 0), (100, 0), (100, 1)];
    let sources = vec![vec![40], vec![100], vec![100, 140]];
    assert_eq!(keep_keys(&held, &first, &sources), vec![(40, 0), (100, 0), (100, 1)]);
    // A fresh row holding two held rows' lines takes the first held one.
    let held: Vec<Key> = vec![(10, 0), (30, 0)];
    let first: Vec<Key> = vec![(10, 0)];
    let sources = vec![vec![10, 30]];
    assert_eq!(keep_keys(&held, &first, &sources), vec![(10, 0)]);
}
