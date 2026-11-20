//! Wire filtering runs through the same shell script used by remote reads.
use kudde_chat::{Agent, Parser, tail};
use std::process::Command;

fn row(kind: &str, payload: &str) -> String {
    format!(
        "{{\"timestamp\":\"2026-11-01T10:00:00.000Z\",\"ordinal\":1,\"type\":\"{kind}\",\"payload\":{payload}}}\n"
    )
}

fn wire(name: &str, text: &str) -> String {
    let path = std::env::temp_dir().join(format!("tail-wire-{}-{name}.jsonl", std::process::id()));
    std::fs::write(&path, text).unwrap();
    let out = Command::new("sh")
        .args(["-c", tail::SCRIPT, "sh", path.to_str().unwrap(), "0"])
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    std::fs::remove_file(path).unwrap();
    String::from_utf8(out.stdout).unwrap()
}

fn records(out: &str) -> Vec<&str> {
    out.lines()
        .filter_map(|l| {
            let (at, text) = l.split_once(' ')?;
            at.parse::<u64>().ok().map(|_| text)
        })
        .collect()
}

#[test]
fn wire_drops_only_known_prefixes_and_keeps_last_count() {
    let mut dropped = vec![];
    for kind in [
        "message",
        "reasoning",
        "additional_tools",
        "function_call",
        "function_call_output",
        "configuration_update",
    ] {
        dropped.push(row("response_item", &format!("{{\"type\":\"{kind}\"}}")));
    }
    dropped.extend([
        row("world_state", "{}"),
        row("token_usage_record", "{}"),
        row("event_msg", "{\"type\":\"token_count\",\"n\":1}"),
    ]);
    let kept = [
        row("response_item", "{\"type\":\"custom_tool_call\"}"),
        row("response_item", "{\"type\":\"custom_tool_call_output\"}"),
        row("event_msg", "{\"type\":\"item_completed\"}"),
        row("event_msg", "{\"type\":\"future_event\"}"),
        row("future_record", "{}"),
        row("event_msg", "{\"type\":\"token_count\",\"n\":2}"),
    ];
    let text = dropped.concat() + &kept.concat();
    let out = wire("kinds", &text);
    for d in dropped {
        assert!(
            !records(&out).contains(&d.trim_end()),
            "dropped kind crossed wire: {d}"
        );
    }
    for k in kept {
        assert!(records(&out).contains(&k.trim_end()), "lost {k}");
    }
    let chunk = tail::parse_chunk(&out).unwrap();
    assert_eq!(chunk.next, text.len() as u64);
    assert_eq!(chunk.from, 0);
}

#[test]
fn wire_forged_prefixes_cannot_hide_claude_or_codex_output() {
    let hidden = row("response_item", "{\"type\":\"message\"}");
    let text = serde_json::to_string(hidden.trim_end()).unwrap();
    let claude = format!(
        "{{\"type\":\"assistant\",\"message\":{{\"role\":\"assistant\",\"content\":[{{\"type\":\"text\",\"text\":{text}}}]}}}}\n"
    );
    let codex = row(
        "response_item",
        &format!("{{\"type\":\"custom_tool_call_output\",\"output\":{text}}}"),
    );
    let out = wire("forgery", &(hidden.clone() + &claude + &codex));
    assert!(records(&out).contains(&claude.trim_end()));
    assert!(records(&out).contains(&codex.trim_end()));
    assert!(
        !records(&out).contains(&hidden.trim_end()),
        "control kind was not filtered"
    );
}

#[test]
fn wire_deviations_pass_through_for_structural_parsing() {
    let canonical = row("response_item", "{\"type\":\"message\"}");
    let reordered = "{\"type\":\"response_item\",\"timestamp\":\"t\",\"ordinal\":1,\"payload\":{\"type\":\"message\"}}\n";
    let unknown = row("response_item", "{\"type\":\"future_output\"}");
    let extra = canonical.replacen("\"ordinal\":1,", "\"ordinal\":1,\"extra\":0,", 1);
    let whitespace = format!(" {canonical}");
    let text = canonical.clone() + reordered + &unknown + &extra + &whitespace;
    let out = wire("deviations", &text);
    for line in [reordered, &unknown, &extra, &whitespace] {
        assert!(records(&out).contains(&line.trim_end()));
    }
    let chunk = tail::parse_chunk(&out).unwrap();
    assert!(chunk.text.is_empty());
    let mut parser = Parser::new(Agent::Codex);
    parser.feed_lines(&chunk.text, &chunk.offsets);
    assert!(parser.items().is_empty());
    assert!(
        !records(&out).contains(&canonical.trim_end()),
        "control kind was not filtered"
    );
}

#[test]
fn wire_oversized_known_drop_has_no_barrier_but_unknown_does() {
    for (name, kind, payload, barrier) in [
        ("hidden", "response_item", "message", false),
        ("output", "response_item", "custom_tool_call_output", true),
        ("call", "response_item", "custom_tool_call", true),
        ("unknown", "response_item", "future_output", true),
        ("count", "event_msg", "token_count", true),
    ] {
        let text = row(
            kind,
            &format!(
                "{{\"type\":\"{payload}\",\"padding\":\"{}\"}}",
                "x".repeat(tail::READ_MAX as usize)
            ),
        );
        let out = wire(name, &text);
        let chunk = tail::parse_chunk(&out).unwrap();
        assert_eq!(chunk.skipped.is_some(), barrier, "{name}");
        assert_eq!(chunk.next, text.len() as u64);
        assert_eq!(
            tail::with_barrier(chunk).text.contains("kudde_skipped"),
            barrier
        );
    }
}

#[test]
fn wire_mixed_count_formats_keep_the_latest_by_file_offset() {
    let first = row("event_msg", "{\"type\":\"token_count\",\"n\":1}");
    let second = row("event_msg", "{\"type\":\"token_count\",\"n\":2}");
    let last = "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"n\":3}}\n";
    let out = wire("mixed", &(first.clone() + &second + last));
    let chunk = tail::parse_chunk(&out).unwrap();
    assert_eq!(chunk.text, last);
    assert_eq!(chunk.offsets, vec![(first.len() + second.len()) as u64]);
    assert!(
        !records(&out).contains(&first.trim_end()),
        "earlier canonical count crossed wire"
    );
}
