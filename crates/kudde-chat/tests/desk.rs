//! The shell scripts the phone runs on the desk through `machine.exec`, run
//! here with the real `sh` and `awk`.

use kudde_chat::image;
use kudde_chat::tail::{self, Chunk};
use std::io::Write;
use std::process::Command;

fn run(script: &str, args: &[&str]) -> String {
    let out = Command::new("sh")
        .arg("-c")
        .arg(script)
        .arg("sh")
        .args(args)
        .env("LC_ALL", "C.UTF-8")
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8(out.stdout).unwrap()
}

fn temp(name: &str, bytes: &[u8]) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("kudde-chat-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join(name);
    std::fs::File::create(&p).unwrap().write_all(bytes).unwrap();
    p
}

#[test]
fn tail_gives_only_whole_new_lines_and_where_to_go_on() {
    let a = "{\"type\":\"user\",\"n\":1}\n";
    let b = "{\"type\":\"user\",\"n\":2}\n";
    let partial = "{\"type\":\"assis";
    let f = temp("t1.jsonl", format!("{a}{b}{partial}").as_bytes());
    let path = f.to_str().unwrap();

    let c = tail::parse_chunk(&run(tail::SCRIPT, &[path, "0"])).unwrap();
    assert_eq!(c.text, format!("{a}{b}"));
    assert_eq!(c.next, (a.len() + b.len()) as u64, "the partial line waits");

    let c2 = tail::parse_chunk(&run(tail::SCRIPT, &[path, &c.next.to_string()])).unwrap();
    assert_eq!(
        c2,
        Chunk {
            text: String::new(),
            offsets: vec![],
            skipped: None,
            from: c.next,
            next: c.next,
            size: c.size
        }
    );
}

#[test]
fn tail_gives_each_kept_line_its_byte_offset() {
    let (text, at) = ten_lines();
    let f = temp("t8.jsonl", text.as_bytes());
    let path = f.to_str().unwrap();
    let c = tail::parse_chunk(&run(tail::SCRIPT, &[path, "0"])).unwrap();
    assert_eq!(
        c.offsets, at,
        "one offset per line, where the line starts in the file"
    );
    assert_eq!(
        c.text, text,
        "the text is the lines alone, without their offsets"
    );
    // A read from the middle of line 3 to line 8: lines 4 to 7, their own offsets.
    let c = tail::parse_chunk(&run(
        tail::SCRIPT,
        &[path, &(at[3] + 5).to_string(), &at[8].to_string()],
    ))
    .unwrap();
    assert_eq!(c.offsets, at[4..8]);
}

#[test]
fn tail_offsets_skip_the_lines_it_drops() {
    let kept1 = "{\"type\":\"user\",\"n\":1}\n";
    let dropped = "{\"type\":\"api-request\",\"id\":\"r\"}\n";
    let kept2 = "{\"type\":\"user\",\"n\":2}\n";
    let f = temp("t9.jsonl", format!("{kept1}{dropped}{kept2}").as_bytes());
    let c = tail::parse_chunk(&run(tail::SCRIPT, &[f.to_str().unwrap(), "0"])).unwrap();
    assert_eq!(c.text, format!("{kept1}{kept2}"));
    assert_eq!(c.offsets, vec![0, (kept1.len() + dropped.len()) as u64]);
}

fn token_count(n: u32) -> String {
    format!(
        "{{\"timestamp\":\"t\",\"type\":\"event_msg\",\"payload\":{{\"type\":\"token_count\",\"info\":{{\"total_token_usage\":{{\"input_tokens\":{n}}},\"model_context_window\":475000}}}}}}\n"
    )
}

#[test]
fn tail_keeps_only_the_last_codex_token_count_of_a_read() {
    let msg = |n: u32| {
        format!(
            "{{\"timestamp\":\"t\",\"type\":\"event_msg\",\"payload\":{{\"type\":\"agent_message\",\"message\":\"m{n}\"}}}}\n"
        )
    };
    let lines = [
        msg(1),
        token_count(100),
        msg(2),
        token_count(200),
        msg(3),
        token_count(300),
        msg(4),
    ];
    let mut at = vec![0u64];
    for l in &lines {
        at.push(at.last().unwrap() + l.len() as u64);
    }
    let f = temp("t10.jsonl", lines.concat().as_bytes());
    let c = tail::parse_chunk(&run(tail::SCRIPT, &[f.to_str().unwrap(), "0"])).unwrap();
    assert!(
        !c.text.contains("\"input_tokens\":100") && !c.text.contains("\"input_tokens\":200"),
        "{}",
        c.text
    );
    assert!(
        c.text.contains("\"input_tokens\":300"),
        "the last one stays: {}",
        c.text
    );
    for m in ["m1", "m2", "m3", "m4"] {
        assert!(c.text.contains(m));
    }
    // Every kept line still carries its own offset, the token count included.
    let kept: Vec<&str> = c.text.lines().collect();
    assert_eq!(kept.len(), 5);
    let tc = kept.iter().position(|l| l.contains("token_count")).unwrap();
    assert_eq!(c.offsets[tc], at[5]);
    let mut msgs: Vec<u64> = c
        .offsets
        .iter()
        .enumerate()
        .filter(|(i, _)| *i != tc)
        .map(|(_, o)| *o)
        .collect();
    msgs.sort();
    assert_eq!(msgs, vec![at[0], at[2], at[4], at[6]]);
    assert_eq!(c.next, *at.last().unwrap());
}

#[test]
fn tail_with_no_token_count_keeps_everything_as_before() {
    let (text, _) = ten_lines();
    let f = temp("t11.jsonl", text.as_bytes());
    let c = tail::parse_chunk(&run(tail::SCRIPT, &[f.to_str().unwrap(), "0"])).unwrap();
    assert_eq!(c.text, text);
}

/// A user line of about `bytes` bytes (plain text, no image data).
fn big_line(bytes: usize, tag: &str) -> String {
    let pad = bytes.saturating_sub(40);
    format!(
        "{{\"type\":\"user\",\"tag\":\"{tag}\",\"pad\":\"{}\"}}\n",
        "x".repeat(pad)
    )
}

#[test]
fn tail_spans_at_most_the_read_limit() {
    let line = big_line(1 << 20, "mb");
    let f = temp("t12.jsonl", line.repeat(9).as_bytes());
    let c = tail::parse_chunk(&run(tail::SCRIPT, &[f.to_str().unwrap(), "0"])).unwrap();
    let per = line.len() as u64;
    assert_eq!(
        c.next,
        per * (tail::READ_MAX / per),
        "whole lines up to the limit, no more"
    );
    assert_eq!(c.offsets.len() as u64, tail::READ_MAX / per);
    assert!(c.skipped.is_none());
}

#[test]
fn tail_skips_a_record_over_the_read_limit_and_goes_on_after_it() {
    let a = big_line(100, "a");
    let b = big_line(tail::READ_MAX as usize + (1 << 20), "b");
    let c_line = big_line(100, "c");
    let f = temp("t13.jsonl", format!("{a}{b}{c_line}").as_bytes());
    let path = f.to_str().unwrap();
    let (at_b, at_c) = (a.len() as u64, (a.len() + b.len()) as u64);

    // Reached after lines this read kept: skipped in the same read.
    let r1 = tail::parse_chunk(&run(tail::SCRIPT, &[path, "0"])).unwrap();
    assert_eq!(r1.text, a);
    assert_eq!(r1.skipped, Some((at_b, b.len() as u64)));
    assert_eq!((r1.from, r1.next), (0, at_c));

    // Reached first.
    let r2 = tail::parse_chunk(&run(tail::SCRIPT, &[path, &at_b.to_string()])).unwrap();
    assert_eq!(
        r2.skipped,
        Some((at_b, b.len() as u64)),
        "the record is reported by offset and length"
    );
    assert_eq!(r2.text, "");
    assert_eq!(
        (r2.from, r2.next),
        (at_b, at_c),
        "the next read goes on after it"
    );

    let r3 = tail::parse_chunk(&run(tail::SCRIPT, &[path, &r2.next.to_string()])).unwrap();
    assert_eq!(r3.text, c_line);
}

#[test]
fn tail_passes_an_oversized_record_the_chat_never_shows_without_a_note() {
    let a = big_line(100, "a");
    let req = format!(
        "{{\"type\":\"api-request\",\"pad\":\"{}\"}}\n",
        "x".repeat(tail::READ_MAX as usize + (1 << 20))
    );
    let c_line = big_line(100, "c");
    let f = temp("t13r.jsonl", format!("{a}{req}{c_line}").as_bytes());
    let path = f.to_str().unwrap();
    let at_c = (a.len() + req.len()) as u64;
    let r1 = tail::parse_chunk(&run(tail::SCRIPT, &[path, "0"])).unwrap();
    assert_eq!(
        (r1.text.as_str(), r1.skipped, r1.next),
        (a.as_str(), None, at_c)
    );
    let r2 = tail::parse_chunk(&run(tail::SCRIPT, &[path, &a.len().to_string()])).unwrap();
    assert_eq!(
        (r2.skipped, r2.from, r2.next),
        (None, a.len() as u64, at_c),
        "reached first: passed over too"
    );
}

#[test]
fn tail_waits_for_an_oversized_record_still_being_written() {
    let a = big_line(100, "a");
    let mut b = big_line(tail::READ_MAX as usize + (1 << 20), "b");
    b.pop(); // no newline yet: the agent is mid-write
    let f = temp("t14.jsonl", format!("{a}{b}").as_bytes());
    let at_b = a.len() as u64;
    let r = tail::parse_chunk(&run(
        tail::SCRIPT,
        &[f.to_str().unwrap(), &at_b.to_string()],
    ))
    .unwrap();
    assert!(r.skipped.is_none(), "not skipped before it is whole");
    assert_eq!(r.next, at_b);
}

#[test]
fn tail_starting_inside_an_oversized_record_moves_past_it_without_a_note() {
    // A first open or read-back that lands inside a huge record: its cut
    // remainder is not a record of its own, so no note, but reading goes on.
    let a = big_line(100, "a");
    let b = big_line(tail::READ_MAX as usize + (2 << 20), "b");
    let c_line = big_line(100, "c");
    let f = temp("t15.jsonl", format!("{a}{b}{c_line}").as_bytes());
    let path = f.to_str().unwrap();
    let at_c = (a.len() + b.len()) as u64;
    let inside = (a.len() + 1000).to_string();
    let r = tail::parse_chunk(&run(tail::SCRIPT, &[path, &inside])).unwrap();
    assert!(r.skipped.is_none());
    assert!(
        r.next >= at_c && r.next <= at_c + c_line.len() as u64,
        "past the cut record: {}",
        r.next
    );
    let all = if r.next == at_c {
        tail::parse_chunk(&run(tail::SCRIPT, &[path, &r.next.to_string()]))
            .unwrap()
            .text
    } else {
        r.text.clone()
    };
    assert_eq!(all, c_line);
}

/// `machine.exec` keeps the first 8 MiB of what a script prints.
const EXEC_KEEPS: usize = 8 << 20;

#[test]
fn tail_output_with_its_offsets_fits_what_exec_keeps() {
    // Many short records far into a log: each printed line gains its
    // offset, so 7.5 MB read can print more than exec keeps (found in the
    // review). Every read must fit, go on, and give each line once.
    let line = |i: usize| format!("{{\"type\":\"system\",\"n\":{i:08}}}\n");
    let body: String = (0..300_000).map(line).collect();
    let f = temp("t-out.jsonl", body.as_bytes());
    let path = f.to_str().unwrap();
    let (mut at, mut lines, mut reads) = (0u64, 0usize, 0);
    while at < body.len() as u64 {
        let out = run(tail::SCRIPT, &[path, &at.to_string()]);
        assert!(
            out.len() < EXEC_KEEPS,
            "read {reads} printed {} bytes",
            out.len()
        );
        let c = tail::parse_chunk(&out).unwrap();
        assert!(c.next > at, "a read goes on");
        lines += c.offsets.len();
        at = c.next;
        reads += 1;
    }
    assert_eq!(lines, 300_000, "each line once");
    assert!(reads >= 2);
}

#[test]
fn tail_script_and_rust_agree_on_the_read_limit() {
    assert!(tail::SCRIPT.contains(&format!("max={}", tail::READ_MAX)));
    assert_eq!(tail::READ_MAX, 7_864_320, "7.5 MB, as the spec says");
}

/// Ten lines of different lengths, and where each one starts.
fn ten_lines() -> (String, Vec<u64>) {
    let mut text = String::new();
    let mut starts = Vec::new();
    for n in 0..10 {
        starts.push(text.len() as u64);
        text.push_str(&format!(
            "{{\"type\":\"user\",\"n\":{n},\"pad\":\"{}\"}}\n",
            "y".repeat(n * 7)
        ));
    }
    (text, starts)
}

#[test]
fn tail_reads_an_older_range_up_to_its_end() {
    let (text, at) = ten_lines();
    let f = temp("t5.jsonl", text.as_bytes());
    let path = f.to_str().unwrap();
    // From the middle of line 3 up to where line 8 starts: line 3 is cut, so
    // it is left for the next older read; lines 4 to 7 come whole.
    let start = (at[3] + 5).to_string();
    let c = tail::parse_chunk(&run(tail::SCRIPT, &[path, &start, &at[8].to_string()])).unwrap();
    assert_eq!(c.text, text[at[4] as usize..at[8] as usize]);
    assert_eq!((c.from, c.next), (at[4], at[8]));
    assert_eq!(c.size, text.len() as u64);
}

#[test]
fn tail_keeps_a_first_line_that_starts_exactly_at_start() {
    let (text, at) = ten_lines();
    let f = temp("t6.jsonl", text.as_bytes());
    let path = f.to_str().unwrap();
    let c = tail::parse_chunk(&run(
        tail::SCRIPT,
        &[path, &at[3].to_string(), &at[5].to_string()],
    ))
    .unwrap();
    assert_eq!(c.text, text[at[3] as usize..at[5] as usize]);
    assert_eq!((c.from, c.next), (at[3], at[5]));
    // A last-N read that lands exactly on a line start keeps that line too.
    let back = text.len() as u64 - at[6];
    let c = tail::parse_chunk(&run(tail::SCRIPT, &[path, &format!("-{back}")])).unwrap();
    assert_eq!((c.from, c.next), (at[6], text.len() as u64));
    assert_eq!(c.text, text[at[6] as usize..]);
}

#[test]
fn tail_read_back_in_pieces_gives_the_whole_log_once() {
    // What the chat does: the last part on open, then older parts on scrolling
    // up, each ending where the one before began; no line twice or missing.
    let (text, _) = ten_lines();
    let f = temp("t7.jsonl", text.as_bytes());
    let path = f.to_str().unwrap();
    let mut c = tail::parse_chunk(&run(tail::SCRIPT, &[path, "-150"])).unwrap();
    let mut whole = c.text.clone();
    let mut reads = 1;
    while c.from > 0 {
        let start = c.from.saturating_sub(150).to_string();
        c = tail::parse_chunk(&run(tail::SCRIPT, &[path, &start, &c.from.to_string()])).unwrap();
        whole = format!("{}{whole}", c.text);
        reads += 1;
        assert!(reads < 20, "no progress at {}", c.from);
    }
    assert_eq!(whole, text);
    assert!(reads > 2);
}

#[test]
fn tail_drops_request_records_and_long_image_data() {
    let image = "A".repeat(5000);
    let lines = format!(
        "{{\"type\":\"api-request\",\"id\":\"r\"}}\n\
         {{\"type\":\"api-request-blob\",\"hash\":\"h\"}}\n\
         {{\"type\":\"user\",\"message\":{{\"content\":[{{\"type\":\"image\",\"source\":{{\"data\":\"{image}\"}}}}]}}}}\n\
         {{\"type\":\"user\",\"short\":{{\"data\":\"abc=\"}}}}\n"
    );
    let f = temp("t2.jsonl", lines.as_bytes());
    let c = tail::parse_chunk(&run(tail::SCRIPT, &[f.to_str().unwrap(), "0"])).unwrap();
    assert!(!c.text.contains("api-request"));
    assert!(!c.text.contains(&image[..300]));
    // The emptied field says where the bytes are: line offset, field index.
    let at = lines.find("{\"type\":\"user\",\"message").unwrap();
    assert!(
        c.text.contains(&format!("\"data\":\"@{at}:0\"")),
        "{}",
        c.text
    );
    assert!(c.text.contains("\"data\":\"abc=\""), "short data stays");
    assert_eq!(c.next, lines.len() as u64, "dropped lines still advance");
}

#[test]
fn tail_from_the_end_of_a_long_file_reads_only_the_last_part() {
    let line = format!("{{\"type\":\"user\",\"pad\":\"{}\"}}\n", "x".repeat(100));
    let f = temp("t3.jsonl", line.repeat(1000).as_bytes());
    let size = (line.len() * 1000) as u64;
    // First open: start at most `budget` bytes before the end, at a line start.
    let c = tail::parse_chunk(&run(tail::SCRIPT, &[f.to_str().unwrap(), "-1000"])).unwrap();
    assert_eq!(c.size, size);
    assert_eq!(c.next, size);
    assert!(c.text.len() <= 1000 && !c.text.is_empty());
    assert!(
        c.text.starts_with("{\"type\""),
        "starts at a line: {:.40}",
        c.text
    );
    assert!(
        c.from > 0 && c.from.is_multiple_of(line.len() as u64),
        "from is that line's start: {}",
        c.from
    );
}

#[test]
fn tail_of_a_missing_file_says_so() {
    let out = run(tail::SCRIPT, &["/nonexistent/x.jsonl", "0"]);
    assert!(tail::parse_chunk(&out).is_none());
}

#[test]
fn locate_script_reports_this_process() {
    // Run against our own process tree: it is not an agent, but the script must
    // name it and not fail.
    let me = std::process::id().to_string();
    let out = run(kudde_chat::locate::SCRIPT, &[&me]);
    let p = kudde_chat::locate::parse_probe(&out);
    assert!(!p.name.is_empty(), "{out}");
}

/// Base64 of `n` bytes counting up, long enough to be emptied by the tail.
fn blob(n: usize, seed: u8) -> (Vec<u8>, String) {
    let bytes: Vec<u8> = (0..n)
        .map(|i| (i as u8).wrapping_mul(7).wrapping_add(seed))
        .collect();
    let out = Command::new("base64")
        .arg("-w0")
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .spawn()
        .unwrap();
    out.stdin.as_ref().unwrap().write_all(&bytes).unwrap();
    let b64 = String::from_utf8(out.wait_with_output().unwrap().stdout).unwrap();
    (bytes, b64)
}

/// Every `"@line:n"` reference the tail left, in order.
fn refs(text: &str) -> Vec<(u64, u32)> {
    text.split("\":\"@")
        .skip(1)
        .map(|r| {
            let r = &r[..r.find('"').unwrap()];
            let (l, n) = r.split_once(':').unwrap();
            (l.parse().unwrap(), n.parse().unwrap())
        })
        .collect()
}

#[test]
fn an_emptied_image_comes_back_whole_through_its_reference() {
    let (one, b1) = blob(4000, 1);
    let (two, b2) = blob(9000, 2);
    // Claude Code: an image in a tool result, its structured copy (`base64`,
    // emptied with no way back: it is the same image), and a second image.
    let lines = format!(
        "{{\"type\":\"user\",\"n\":0}}\n\
         {{\"type\":\"user\",\"message\":{{\"content\":[{{\"type\":\"tool_result\",\"content\":[{{\"type\":\"image\",\"source\":{{\"data\":\"{b1}\"}}}}]}},{{\"type\":\"image\",\"source\":{{\"data\":\"{b2}\"}}}}]}},\"toolUseResult\":{{\"file\":{{\"base64\":\"{b1}\"}}}}}}\n"
    );
    let f = temp("i1.jsonl", lines.as_bytes());
    let path = f.to_str().unwrap();
    for start in ["0", "-30000"] {
        let c = tail::parse_chunk(&run(tail::SCRIPT, &[path, start])).unwrap();
        assert!(!c.text.contains(&b1[..300]) && !c.text.contains(&b2[..300]));
        let r = refs(&c.text);
        assert_eq!(r.len(), 3, "{}", c.text);
        for ((line, n), want) in r.iter().take(2).zip([&one, &two]) {
            let got = run_bytes(
                image::LOG_SCRIPT,
                &[path, &line.to_string(), &n.to_string()],
            );
            assert_eq!(
                &image::decode(&got).unwrap(),
                want,
                "start {start}, ref {line}:{n}"
            );
        }
    }
}

#[test]
fn an_image_reference_past_the_line_gives_nothing() {
    let f = temp("i2.jsonl", b"{\"type\":\"user\"}\n");
    let got = run_bytes(image::LOG_SCRIPT, &[f.to_str().unwrap(), "0", "3"]);
    assert!(image::decode(&got).is_none());
    let got = run_bytes(image::LOG_SCRIPT, &["/nonexistent/x.jsonl", "0", "0"]);
    assert!(image::decode(&got).is_none());
}

#[test]
fn an_image_file_is_read_whole_after_its_time_and_a_missing_one_is_none() {
    let (bytes, _) = blob(20000, 3);
    let f = temp("i3.png", &bytes);
    let (tag, got) =
        image::file_reply(&run_bytes(image::FILE_SCRIPT, &[f.to_str().unwrap(), ""])).unwrap();
    assert_eq!(got, Some(bytes));
    assert!(!tag.is_empty());
    let out = run_bytes(image::FILE_SCRIPT, &["/nonexistent/x.png", ""]);
    assert!(out.is_empty());
    assert_eq!(image::file_reply(&out), None);
}

#[test]
fn an_image_file_already_held_at_its_time_is_not_sent_again() {
    let (bytes, _) = blob(20000, 5);
    let f = temp("i4.png", &bytes);
    let p = f.to_str().unwrap();
    let (tag, _) = image::file_reply(&run_bytes(image::FILE_SCRIPT, &[p, ""])).unwrap();
    assert_eq!(
        image::file_reply(&run_bytes(image::FILE_SCRIPT, &[p, &tag])),
        Some((tag.clone(), None))
    );
    // The same path written again (a Codex screenshot reused): sent again.
    std::fs::write(&f, b"another image").unwrap();
    let (again, got) = image::file_reply(&run_bytes(image::FILE_SCRIPT, &[p, &tag])).unwrap();
    assert_ne!(again, tag);
    assert_eq!(got.as_deref(), Some(&b"another image"[..]));
}

fn run_bytes(script: &str, args: &[&str]) -> Vec<u8> {
    let out = Command::new("sh")
        .arg("-c")
        .arg(script)
        .arg("sh")
        .args(args)
        .output()
        .unwrap();
    out.stdout
}
