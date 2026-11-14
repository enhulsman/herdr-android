//! The context gauge's numbers (design D8): what the log already holds.

use herdr_chat::{Agent, Gauge, Parser};

fn parse(agent: Agent, lines: &[String]) -> Parser {
    let mut p = Parser::new(agent);
    for l in lines {
        p.feed(&format!("{l}\n"));
    }
    p
}

/// Codex 0.169's `token_count`: `input_tokens` already holds the cached part.
fn token_count(input: u64, window: u64) -> String {
    format!(
        "{{\"type\":\"event_msg\",\"payload\":{{\"type\":\"token_count\",\"info\":{{\"total_token_usage\":{{\"input_tokens\":9999999,\"cached_input_tokens\":0,\"output_tokens\":1}},\"last_token_usage\":{{\"input_tokens\":{input},\"cached_input_tokens\":{cached},\"output_tokens\":1229,\"total_tokens\":{total}}},\"model_context_window\":{window}}},\"rate_limits\":{{\"primary\":{{\"used_percent\":12.0}}}}}}}}",
        cached = input / 2,
        total = input + 1229
    )
}
fn codex_said(text: &str) -> String {
    format!("{{\"type\":\"event_msg\",\"payload\":{{\"type\":\"item_completed\",\"turn_id\":\"t\",\"item\":{{\"type\":\"AgentMessage\",\"id\":\"a\",\"content\":[{{\"type\":\"Text\",\"text\":\"{text}\"}}]}}}}}}")
}

/// A Claude Code assistant record with its usage.
fn claude_said(sidechain: bool, input: u64, created: u64, read: u64) -> String {
    format!(
        "{{\"type\":\"assistant\",\"sessionId\":\"s\",\"isSidechain\":{sidechain},\"message\":{{\"id\":\"m\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[{{\"type\":\"text\",\"text\":\"ok\"}}],\"usage\":{{\"input_tokens\":{input},\"cache_creation_input_tokens\":{created},\"cache_read_input_tokens\":{read},\"output_tokens\":366,\"cache_creation\":{{\"ephemeral_1h_input_tokens\":{created}}}}}}}}}"
    )
}
fn claude_asked() -> String {
    "{\"type\":\"user\",\"sessionId\":\"s\",\"message\":{\"role\":\"user\",\"content\":\"Hi\"}}".into()
}

#[test]
fn codex_reads_the_last_requests_input_against_the_window() {
    let p = parse(Agent::Codex, &[codex_said("Hi"), token_count(223_000, 475_000)]);
    let g = p.gauge().expect("a gauge");
    assert_eq!(g, Gauge { used: 223_000, window: Some(475_000) });
    assert_eq!(g.percent(), Some(47));
}

#[test]
fn codex_takes_the_latest_count_and_keeps_it_through_an_empty_one() {
    let null = "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":null,\"rate_limits\":{}}}".to_string();
    let p = parse(Agent::Codex, &[token_count(22_000, 258_400), token_count(99_318, 258_400), null]);
    assert_eq!(p.gauge(), Some(Gauge { used: 99_318, window: Some(258_400) }));
}

#[test]
fn claude_code_reads_input_plus_cache_tokens_of_the_last_assistant_record() {
    let p = parse(Agent::Claude, &[claude_asked(), claude_said(false, 10, 500, 100_000), claude_said(false, 2, 550, 282_448)]);
    let g = p.gauge().expect("a gauge");
    assert_eq!(g, Gauge { used: 283_000, window: None });
    assert_eq!(g.percent(), None, "no window in the log");
}

#[test]
fn a_subagents_record_or_one_without_tokens_is_not_the_sessions_count() {
    let no_usage = "{\"type\":\"assistant\",\"sessionId\":\"s\",\"message\":{\"id\":\"e\",\"role\":\"assistant\",\"model\":\"<synthetic>\",\"content\":[{\"type\":\"text\",\"text\":\"API Error\"}]}}".to_string();
    let p = parse(
        Agent::Claude,
        &[claude_said(false, 2, 550, 170_710), claude_said(true, 5, 0, 9_000), claude_said(false, 0, 0, 0), no_usage],
    );
    assert_eq!(p.gauge(), Some(Gauge { used: 171_262, window: None }));
}

#[test]
fn no_gauge_when_the_log_holds_no_count() {
    assert_eq!(parse(Agent::Codex, &[codex_said("Hi")]).gauge(), None);
    assert_eq!(parse(Agent::Claude, &[claude_asked()]).gauge(), None);
    assert_eq!(parse(Agent::Claude, &[]).gauge(), None);
}

#[test]
fn the_percent_rounds_and_a_window_of_zero_has_none() {
    assert_eq!(Gauge { used: 22_000, window: Some(475_000) }.percent(), Some(5));
    assert_eq!(Gauge { used: 1, window: Some(0) }.percent(), None);
}
