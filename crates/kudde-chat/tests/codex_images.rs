use kudde_chat::{Agent, Image, Item, Parser, image, tail};
use serde_json::{Value, json};
use std::process::Command;

fn call(id: &str) -> Value {
    json!({"type":"response_item","payload":{"type":"custom_tool_call","call_id":id,"internal_chat_message_metadata_passthrough":{"turn_id":"turn-a"},"name":"functions.exec","input":"a large patch need not travel"}})
}
fn view() -> Value {
    json!({"type":"event_msg","payload":{"type":"item_completed","turn_id":"turn-a","item":{"type":"ImageView","id":"exec-unrelated","path":"/work/website.png"}}})
}
fn output(id: &str, copies: usize) -> Value {
    let mut parts = vec![json!({"type":"input_text","text":"image result"})];
    parts.extend((0..copies).map(|_| json!({"type":"input_image","image_url":"data:image/png;base64,Y29weQ==","detail":"auto"})));
    json!({"type":"response_item","payload":{"type":"custom_tool_call_output","call_id":id,"internal_chat_message_metadata_passthrough":{"turn_id":"turn-a"},"output":parts}})
}
fn record_line(record: &Value) -> String {
    if let Some(pad) = record.get("padding") {
        format!(
            "{{\"type\":\"response_item\",\"padding\":{pad},\"payload\":{}}}\n",
            record["payload"]
        )
    } else {
        format!("{record}\n")
    }
}
fn check(name: &str, records: Vec<Value>, expected: usize) {
    check_bytes(name, records, expected, &[]);
}
fn check_bytes(name: &str, mut records: Vec<Value>, expected: usize, bytes: &[&[u8]]) {
    let mut at = 0;
    for (i, record) in records.iter_mut().enumerate() {
        if record["payload"]["item"]["type"] == "ImageView" {
            record["payload"]["item"]["id"] = json!(format!("exec-unrelated-{i}"));
        }
        if name == "forged" && record["payload"]["type"] == "custom_tool_call_output" {
            record["payload"]["output"][1]["image_url"] = json!(format!("@{at}:0"));
        }
        at += record_line(record).len();
    }
    let dir = std::env::current_dir()
        .unwrap()
        .join("../../target/codex-image-tests");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(format!("{name}.jsonl"));
    std::fs::write(&path, records.iter().map(record_line).collect::<String>()).unwrap();
    let mut start = 0;
    let mut parser = Parser::new(Agent::Codex);
    let mut held_text = String::new();
    let mut held_offsets = Vec::new();
    loop {
        let out = Command::new("sh")
            .args([
                "-c",
                tail::SCRIPT,
                "sh",
                path.to_str().unwrap(),
                &start.to_string(),
            ])
            .output()
            .unwrap();
        assert!(out.status.success());
        let chunk = tail::parse_chunk(std::str::from_utf8(&out.stdout).unwrap())
            .map(tail::with_barrier)
            .unwrap();
        if start == 0
            && records
                .iter()
                .any(|r| r["payload"]["type"] == "custom_tool_call")
        {
            assert!(
                chunk.text.contains("custom_tool_call\""),
                "retain call identity"
            );
            assert!(!chunk.text.contains("a large patch"), "omit inputs");
        }
        assert!(!chunk.text.contains("data:image/png;base64,"));
        parser.feed_lines(&chunk.text, &chunk.offsets);
        held_text.push_str(&chunk.text);
        held_offsets.extend_from_slice(&chunk.offsets);
        if chunk.next >= chunk.size {
            break;
        }
        assert!(chunk.next > start);
        start = chunk.next;
    }
    let mut replay = Parser::new(Agent::Codex);
    replay.feed_lines(&held_text, &held_offsets);
    assert_eq!(
        parser.items(),
        replay.items(),
        "held history reparses conservatively"
    );
    if name.starts_with("padded-") {
        let reason = if name == "padded-before" {
            "uncertain call" // The later output reports the invalidated call.
        } else {
            "skipped record"
        };
        assert_eq!(parser.image_counts().get(reason), Some(&1));
        assert!(held_text.contains("kudde_skipped"));
    }
    let images: Vec<_> = parser
        .items()
        .iter()
        .filter_map(|r| match r {
            Item::Tools { calls } => Some(calls),
            _ => None,
        })
        .flatten()
        .flat_map(|c| &c.images)
        .collect();
    assert_eq!(
        images
            .iter()
            .filter(|i| matches!(i, Image::FileWithFallback { .. }))
            .count(),
        expected
    );
    for (position, i) in images.into_iter().enumerate() {
        if let Image::FileWithFallback { copy, .. } = i {
            let Image::InLog { line, n, .. } = copy.as_ref() else {
                panic!()
            };
            let out = Command::new("sh")
                .args([
                    "-c",
                    image::LOG_SCRIPT,
                    "sh",
                    path.to_str().unwrap(),
                    &line.to_string(),
                    &n.to_string(),
                ])
                .output()
                .unwrap();
            assert_eq!(
                image::decode(&out.stdout).unwrap(),
                bytes.get(position).copied().unwrap_or(b"copy")
            );
        }
    }
}
#[test]
fn one_view_one_copy_with_intervening_events() {
    check(
        "one",
        vec![
            call("call-a"),
            view(),
            json!({"type":"event_msg","payload":{"type":"item_completed","turn_id":"turn-a","item":{"type":"CommandExecution","id":"cmd","command":"true"}}}),
            output("call-a", 1),
        ],
        1,
    );
}
#[test]
fn same_number_more_than_one() {
    let mut o = output("call-a", 2);
    o["payload"]["output"][2]["image_url"] = json!("data:image/png;base64,dHdv");
    check_bytes(
        "equal-many",
        vec![call("call-a"), view(), view(), o],
        2,
        &[b"copy", b"two"],
    );
}
#[test]
fn three_views_pair_by_position_with_interleaved_text() {
    let mut o = output("call-a", 0);
    o["payload"]["output"] = json!([
        {"type":"input_image","image_url":"data:image/png;base64,b25l"},
        {"type":"input_text","text":"between"},
        {"type":"input_image","image_url":"data:image/png;base64,dHdv"},
        {"type":"input_text","text":"between again"},
        {"type":"input_image","image_url":"data:image/png;base64,dGhyZWU="}
    ]);
    check_bytes(
        "three-position",
        vec![call("call-a"), view(), view(), view(), o],
        3,
        &[b"one", b"two", b"three"],
    );
}

// Establish that the call can pair before exercising its refusal; otherwise
// the old blanket refusal for multiple views would make these tests vacuous.
fn many_refusal(name: &str, records: Vec<Value>) {
    check(
        &format!("{name}-baseline"),
        vec![call("call-a"), view(), view(), output("call-a", 2)],
        2,
    );
    check(name, records, 0);
}
#[test]
fn many_second_output_revokes_all_pairs() {
    many_refusal(
        "many-second",
        vec![
            call("call-a"),
            view(),
            view(),
            output("call-a", 2),
            output("call-a", 2),
        ],
    );
}
#[test]
fn many_skipped_before_output_refuses_all() {
    many_refusal(
        "many-skip-before",
        vec![
            call("call-a"),
            view(),
            view(),
            oversized(),
            output("call-a", 2),
        ],
    );
}
#[test]
fn many_skipped_after_pairing_revokes_all() {
    many_refusal(
        "many-skip-after",
        vec![
            call("call-a"),
            view(),
            view(),
            output("call-a", 2),
            oversized(),
        ],
    );
}
#[test]
fn many_one_view_turn_mismatch_refuses_all() {
    let mut v = view();
    v["payload"]["turn_id"] = json!("turn-b");
    many_refusal(
        "many-turn",
        vec![call("call-a"), view(), v, output("call-a", 2)],
    );
}
#[test]
fn many_one_forged_copy_refuses_all() {
    let mut o = output("call-a", 2);
    o["payload"]["output"][2]["image_url"] = json!("@0:1");
    many_refusal("many-forged", vec![call("call-a"), view(), view(), o]);
}

#[test]
fn unequal_numbers() {
    check(
        "unequal",
        vec![call("call-a"), view(), output("call-a", 2)],
        0,
    );
}
#[test]
fn missing_copy() {
    check(
        "missing",
        vec![call("call-a"), view(), output("call-a", 0)],
        0,
    );
}
#[test]
fn copies_without_view() {
    check("no-view", vec![call("call-a"), output("call-a", 1)], 0);
}
#[test]
fn view_without_open_call() {
    check("no-call", vec![view(), output("call-a", 1)], 0);
}
#[test]
fn view_with_multiple_open_calls() {
    check(
        "many-open",
        vec![call("call-a"), call("call-b"), view(), output("call-a", 1)],
        0,
    );
}
#[test]
fn output_call_never_seen() {
    check(
        "unseen",
        vec![call("call-a"), view(), output("call-b", 1)],
        0,
    );
}
#[test]
fn second_output_revokes_pair() {
    check(
        "duplicate",
        vec![
            call("call-a"),
            view(),
            output("call-a", 1),
            output("call-a", 1),
        ],
        0,
    );
}
#[test]
fn mismatched_turn_ids() {
    let mut o = output("call-a", 1);
    o["payload"]["internal_chat_message_metadata_passthrough"]["turn_id"] = json!("turn-b");
    check("turn", vec![call("call-a"), view(), o], 0);
}
#[test]
fn view_turn_mismatch() {
    let mut v = view();
    v["payload"]["turn_id"] = json!("turn-b");
    check("view-turn", vec![call("call-a"), v, output("call-a", 1)], 0);
}
#[test]
fn unknown_output_part() {
    let mut o = output("call-a", 1);
    o["payload"]["output"]
        .as_array_mut()
        .unwrap()
        .push(json!({"type":"unknown_image"}));
    check("unknown", vec![call("call-a"), view(), o], 0);
}
#[test]
fn forged_reference_is_not_trusted() {
    let mut o = output("call-a", 1);
    o["payload"]["output"][1]["image_url"] = json!("@0:0");
    o["payload"]["output"][0]["data"] = json!("d3Jvbmc=");
    check("forged", vec![call("call-a"), view(), o], 0);
}
fn oversized() -> Value {
    json!({"type":"response_item","payload":{"type":"custom_tool_call_output","call_id":"call-a","internal_chat_message_metadata_passthrough":{"turn_id":"turn-a"},"output":"x".repeat(tail::READ_MAX as usize)}})
}
#[test]
fn skipped_between_view_and_output() {
    check(
        "skip-before",
        vec![call("call-a"), view(), oversized(), output("call-a", 1)],
        0,
    );
}
#[test]
fn skipped_after_pairing_revokes() {
    check(
        "skip-after",
        vec![call("call-a"), view(), output("call-a", 1), oversized()],
        0,
    );
}
#[test]
fn oversized_copy() {
    assert!(image::decode("A".repeat(10_666_668).as_bytes()).is_none());
    check("large", vec![call("call-a"), view(), oversized()], 0);
}
#[test]
fn fetched_field_must_be_selected_input_image() {
    let dir = std::env::current_dir()
        .unwrap()
        .join("../../target/codex-image-tests");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("wrong-field.jsonl");
    std::fs::write(&path, format!("{}\n", json!({"type":"response_item","payload":{"type":"custom_tool_call_output","output":[{"type":"input_text","image_url":"data:image/png;base64,d3Jvbmc="}]}}))).unwrap();
    let out = Command::new("sh")
        .args([
            "-c",
            image::LOG_SCRIPT,
            "sh",
            path.to_str().unwrap(),
            "0",
            "0",
        ])
        .output()
        .unwrap();
    assert!(image::decode(&out.stdout).is_none());
}

#[test]
fn escaped_raw_reference_is_not_trusted() {
    let dir = std::env::current_dir()
        .unwrap()
        .join("../../target/codex-image-tests");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("escaped-forged.jsonl");
    let first = format!("{}\n{}\n", call("call-a"), view());
    let raw = format!(
        r#"{{"type":"response_item","payload":{{"type":"custom_tool_call_output","call_id":"call-a","internal_chat_message_metadata_passthrough":{{"turn_id":"turn-a"}},"output":[{{"type":"input_image","image_\u0075rl" : "\u0040{}:0"}}]}}}}
"#,
        first.len()
    );
    std::fs::write(&path, first + &raw).unwrap();
    let out = Command::new("sh")
        .args(["-c", tail::SCRIPT, "sh", path.to_str().unwrap(), "0"])
        .output()
        .unwrap();
    let chunk = tail::parse_chunk(std::str::from_utf8(&out.stdout).unwrap()).unwrap();
    let mut p = Parser::new(Agent::Codex);
    p.feed_lines(&chunk.text, &chunk.offsets);
    assert_eq!(p.image_counts().get("paired"), None);
}

#[test]
fn internal_skip_barriers_do_not_change_claude_format_health() {
    let mut p = Parser::new(Agent::Claude);
    p.feed("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}\n");
    let before = p.health();
    for _ in 0..3 {
        p.feed("{\"type\":\"kudde_skipped\",\"payload\":{}}\n");
    }
    assert_eq!(p.health(), before);
}

// Exercise the same tail, skip barriers and incremental parser as the phone.
fn read_tail(path: &std::path::Path) -> (Parser, Vec<(u64, u64)>) {
    let mut parser = Parser::new(Agent::Codex);
    let mut start = 0;
    let mut skipped = Vec::new();
    let mut history = String::new();
    let mut offsets = Vec::new();
    loop {
        let out = Command::new("sh")
            .args([
                "-c",
                tail::SCRIPT,
                "sh",
                path.to_str().unwrap(),
                &start.to_string(),
            ])
            .output()
            .unwrap();
        assert!(out.status.success());
        let chunk = tail::parse_chunk(std::str::from_utf8(&out.stdout).unwrap()).unwrap();
        skipped.extend(chunk.skipped);
        let chunk = tail::with_barrier(chunk);
        parser.feed_lines(&chunk.text, &chunk.offsets);
        history.push_str(&chunk.text);
        offsets.extend(chunk.offsets);
        if chunk.next >= chunk.size {
            break;
        }
        assert!(chunk.next > start);
        start = chunk.next;
    }
    let mut replay = Parser::new(Agent::Codex);
    replay.feed_lines(&history, &offsets);
    assert_eq!(parser.items(), replay.items());
    assert_eq!(parser.image_counts(), replay.image_counts());
    (parser, skipped)
}

#[test]
fn real_shape_fixture_pairs_all_three_views_by_position() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/codex-real-shape.jsonl");
    let (parser, skipped) = read_tail(&path);
    assert!(skipped.is_empty());
    assert_eq!(parser.image_counts().values().sum::<usize>(), 3);
    assert_eq!(parser.image_counts().get("paired"), Some(&3));
    assert_eq!(parser.image_counts().get("multiple images"), None);
    let images: Vec<_> = parser
        .items()
        .iter()
        .filter_map(|item| match item {
            Item::Tools { calls } => Some(calls),
            _ => None,
        })
        .flatten()
        .flat_map(|c| &c.images)
        .collect();
    assert_eq!(images.len(), 3);
    let expected = [
        (
            "shot-1.png",
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPQCFgAAAG8ARlfd8kpAAAAAElFTkSuQmCC",
        ),
        (
            "shot-2.png",
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMICFgAAAI0AUFoixYYAAAAAElFTkSuQmCC",
        ),
        (
            "shot-3.png",
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGOoCFgAAAKsAWlKmtVGAAAAAElFTkSuQmCC",
        ),
    ];
    for (img, (filename, bytes)) in images.iter().zip(expected) {
        let Image::FileWithFallback {
            path: file, copy, ..
        } = img
        else {
            panic!("each view needs its own copy")
        };
        assert!(file.ends_with(filename));
        let Image::InLog { line, n, .. } = copy.as_ref() else {
            panic!("log reference required")
        };
        let out = Command::new("sh")
            .args([
                "-c",
                image::LOG_SCRIPT,
                "sh",
                path.to_str().unwrap(),
                &line.to_string(),
                &n.to_string(),
            ])
            .output()
            .unwrap();
        assert!(out.status.success());
        assert_eq!(image::decode(&out.stdout), image::decode(bytes.as_bytes()));
    }
}

fn single_image_record_at_read_boundary(name: &str, size: usize, should_skip: bool) {
    let dir =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../target/codex-image-tests");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(format!("{name}.jsonl"));
    let prefix = format!("{}\n{}\n", call("call-a"), view());
    let mut record = output("call-a", 1);
    // Padding follows the image so fetching it need not scan the large text.
    record["payload"]["output"]
        .as_array_mut()
        .unwrap()
        .push(json!({"type":"input_text","text":""}));
    let overhead = format!("{record}\n").len();
    record["payload"]["output"][2]["text"] = json!("x".repeat(size - overhead));
    let line = format!("{record}\n");
    assert_eq!(line.len(), size, "record size includes newline");
    std::fs::write(&path, format!("{prefix}{line}")).unwrap();
    let (parser, skipped) = read_tail(&path);
    assert_eq!(parser.image_counts().values().sum::<usize>(), 1);
    if should_skip {
        assert_eq!(skipped, vec![(prefix.len() as u64, size as u64)]);
        assert_eq!(parser.image_counts().get("skipped record"), Some(&1));
        assert_eq!(parser.image_counts().get("paired"), None);
    } else {
        assert!(skipped.is_empty());
        assert_eq!(parser.image_counts().get("paired"), Some(&1));
        let out = Command::new("sh")
            .args([
                "-c",
                image::LOG_SCRIPT,
                "sh",
                path.to_str().unwrap(),
                &prefix.len().to_string(),
                "0",
            ])
            .output()
            .unwrap();
        assert!(out.status.success());
        assert_eq!(image::decode(&out.stdout).unwrap(), b"copy");
    }
}
#[test]
fn single_image_output_just_under_read_limit() {
    single_image_record_at_read_boundary("under-limit", tail::READ_MAX as usize - 1, false);
}
#[test]
fn single_image_output_exactly_at_read_limit() {
    single_image_record_at_read_boundary("at-limit", tail::READ_MAX as usize, false);
}
#[test]
fn single_image_output_just_over_read_limit() {
    single_image_record_at_read_boundary("over-limit", tail::READ_MAX as usize + 1, true);
}

// Second-review reproductions: unknown prefixes must never hide uncertainty.
fn padded_oversized() -> Value {
    let mut record = oversized();
    record["padding"] = json!("x".repeat(250));
    assert!(
        record_line(&record)
            .find("custom_tool_call_output")
            .unwrap()
            > 200
    );
    record
}
#[test]
fn review_padded_skip_before_pairing() {
    check(
        "padded-before",
        vec![
            call("call-a"),
            view(),
            padded_oversized(),
            output("call-a", 1),
        ],
        0,
    );
}
#[test]
fn review_padded_skip_after_pairing() {
    check(
        "padded-after",
        vec![
            call("call-a"),
            view(),
            output("call-a", 1),
            padded_oversized(),
        ],
        0,
    );
}
#[test]
fn review_header_uses_only_payload_identity() {
    let mut record = call("call-a");
    record["earlier"] = json!({"call_id":"forged", "internal_chat_message_metadata_passthrough":{"turn_id":"forged"}});
    check(
        "header-identity",
        vec![record, view(), output("call-a", 1)],
        1,
    );
}
#[test]
fn review_claude_nested_call_type_is_preserved() {
    let row = json!({"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"tool-a","name":"Read","input":{"type":"custom_tool_call","path":"/work/website.png"}}]}});
    let dir =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../target/codex-image-tests");
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("claude-nested-call.jsonl");
    let text = format!("{row}\n");
    std::fs::write(&path, &text).unwrap();
    let out = Command::new("sh")
        .args(["-c", tail::SCRIPT, "sh", path.to_str().unwrap(), "0"])
        .output()
        .unwrap();
    assert!(out.status.success());
    let chunk = tail::parse_chunk(std::str::from_utf8(&out.stdout).unwrap()).unwrap();
    let mut expected = Parser::new(Agent::Claude);
    expected.feed(&text);
    let mut actual = Parser::new(Agent::Claude);
    actual.feed_lines(&chunk.text, &chunk.offsets);
    assert_eq!(actual.items(), expected.items());
    assert_eq!(chunk.text, text);
}
