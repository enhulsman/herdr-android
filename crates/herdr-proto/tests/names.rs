//! Agents named after their session, and where they sit (session-names,
//! spec agent-names, task 1.1).

use herdr_proto::attention::Tracker;
use serde_json::{Value, json};

const WSL: &str = "WSL";

/// A Claude Code agent with a title and a desk placement.
fn agent(address: &str) -> Value {
    json!({
        "address": address,
        "state": "live",
        "cwd": "/home/me/src/website",
        "argv": ["claude"],
        "labels": {"herdr.space": "sp1", "herdr.tab": "tb1", "where": "website / 2"},
        "name": null,
        "title": "Pricing page copy",
        "parent": null,
        "reported": true,
        "status": {"state": "working", "app": "claude-code"},
        "status_ms": 1_000,
        "turn": 0,
    })
}

/// The same terminal after its agent quit to the shell.
fn quit(address: &str) -> Value {
    let mut t = agent(address);
    t["status"] = Value::Null;
    t["argv"] = json!(["/usr/bin/zsh"]);
    t
}

fn with(mut v: Value, key: &str, value: Value) -> Value {
    v[key] = value;
    v
}

fn labels(v: Value, labels: Value) -> Value {
    with(v, "labels", labels)
}

fn seen(terminals: &[Value]) -> Tracker {
    let mut t = Tracker::default();
    t.observe(WSL, &[], 0);
    t.observe(WSL, terminals, 1);
    t
}

fn name(terminal: Value) -> String {
    seen(&[terminal]).rows().remove(0).name
}

fn place(terminal: Value) -> Option<String> {
    seen(&[terminal]).rows().remove(0).place
}

// ---- An agent is named after its session ----

#[test]
fn a_titled_session_is_named_by_its_title() {
    assert_eq!(name(agent("t1")), "Pricing page copy");
}

#[test]
fn a_name_given_at_the_desk_wins() {
    assert_eq!(name(with(agent("t1"), "name", json!("release"))), "release");
}

#[test]
fn no_title_falls_back_to_the_program() {
    let codex = with(
        with(agent("t1"), "title", json!("")),
        "status",
        json!({"state": "working", "app": "codex"}),
    );
    assert_eq!(name(codex.clone()), "Codex");
    // A core that sends no title at all.
    let mut bare = codex;
    bare.as_object_mut().unwrap().remove("title");
    assert_eq!(name(bare), "Codex");
}

#[test]
fn a_quit_agent_is_an_other_terminal_named_by_its_shell_not_its_title() {
    let t = seen(&[quit("t1")]);
    assert!(t.rows().is_empty());
    assert_eq!(t.others()[0].name, "zsh");
}

#[test]
fn a_whitespace_title_counts_as_absent() {
    assert_eq!(
        name(with(agent("t1"), "title", json!("  \t "))),
        "Claude Code"
    );
    assert_eq!(
        name(with(agent("t1"), "name", json!("   "))),
        "Pricing page copy"
    );
}

#[test]
fn control_characters_and_whitespace_runs_are_cleaned() {
    assert_eq!(
        name(with(
            agent("t1"),
            "title",
            json!("  Pricing\u{1b}[0m \t\n page\u{7}  copy ")
        )),
        "Pricing[0m page copy"
    );
}

#[test]
fn a_long_title_is_cut_to_79_characters_and_an_ellipsis() {
    let long: String = "a".repeat(120);
    let got = name(with(agent("t1"), "title", json!(long)));
    assert_eq!(got.chars().count(), 80);
    assert_eq!(got, format!("{}…", "a".repeat(79)));
    // A multi-byte character at the cut stays whole.
    let wide: String = "é".repeat(120);
    let got = name(with(agent("t1"), "title", json!(wide)));
    assert_eq!(got, format!("{}…", "é".repeat(79)));
    // Exactly 80 is left alone.
    let edge: String = "b".repeat(80);
    assert_eq!(name(with(agent("t1"), "title", json!(edge.clone()))), edge);
}

// ---- The list shows where an agent sits ----

#[test]
fn a_placed_terminal_has_its_desk_place() {
    assert_eq!(place(agent("t1")).as_deref(), Some("website / 2"));
}

#[test]
fn a_terminal_without_where_has_no_place() {
    assert_eq!(
        place(labels(agent("t1"), json!({"herdr.space": "sp1"}))),
        None
    );
    assert_eq!(place(labels(agent("t1"), json!({}))), None);
    assert_eq!(place(with(agent("t1"), "labels", Value::Null)), None);
}

#[test]
fn a_whitespace_where_has_no_place() {
    assert_eq!(
        place(labels(
            agent("t1"),
            json!({"herdr.space": "sp1", "where": " \t"})
        )),
        None
    );
}

#[test]
fn a_parked_terminal_has_no_place() {
    assert_eq!(
        place(labels(
            agent("t1"),
            json!({"herdr.space": "", "where": "website / 2"})
        )),
        None
    );
    assert_eq!(
        place(labels(agent("t1"), json!({"where": "website / 2"}))),
        None
    );
}

#[test]
fn other_terminals_have_a_place_too() {
    let t = seen(&[quit("t1")]);
    assert_eq!(t.others()[0].place.as_deref(), Some("website / 2"));
}

#[test]
fn a_title_change_renames_the_row() {
    let mut t = seen(&[agent("t1")]);
    t.observe(
        WSL,
        &[with(agent("t1"), "title", json!("Fix login redirect"))],
        2,
    );
    assert_eq!(t.rows()[0].name, "Fix login redirect");
}
