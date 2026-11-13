//! The shell scripts the phone runs on the desk through `machine.exec`, run
//! here with the real `sh` and `awk`.

use herdr_chat::image;
use herdr_chat::tail::{self, Chunk};
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
    assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
    String::from_utf8(out.stdout).unwrap()
}

fn temp(name: &str, bytes: &[u8]) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("herdr-chat-{}", std::process::id()));
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
    assert_eq!(c2, Chunk { text: String::new(), next: c.next, size: c.size });
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
    assert!(c.text.contains(&format!("\"data\":\"@{at}:0\"")), "{}", c.text);
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
    assert!(c.text.starts_with("{\"type\""), "starts at a line: {:.40}", c.text);
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
    let out = run(herdr_chat::locate::SCRIPT, &[&me]);
    let p = herdr_chat::locate::parse_probe(&out);
    assert!(!p.name.is_empty(), "{out}");
}

/// Base64 of `n` bytes counting up, long enough to be emptied by the tail.
fn blob(n: usize, seed: u8) -> (Vec<u8>, String) {
    let bytes: Vec<u8> = (0..n).map(|i| (i as u8).wrapping_mul(7).wrapping_add(seed)).collect();
    let out = Command::new("base64").arg("-w0").stdin(std::process::Stdio::piped()).stdout(std::process::Stdio::piped()).spawn().unwrap();
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
            let got = run_bytes(image::LOG_SCRIPT, &[path, &line.to_string(), &n.to_string()]);
            assert_eq!(&image::decode(&got).unwrap(), want, "start {start}, ref {line}:{n}");
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
fn an_image_file_is_read_whole_and_a_missing_one_is_none() {
    let (bytes, _) = blob(20000, 3);
    let f = temp("i3.png", &bytes);
    assert_eq!(run_bytes(image::FILE_SCRIPT, &[f.to_str().unwrap()]), bytes);
    assert!(run_bytes(image::FILE_SCRIPT, &["/nonexistent/x.png"]).is_empty());
}

fn run_bytes(script: &str, args: &[&str]) -> Vec<u8> {
    let out = Command::new("sh").arg("-c").arg(script).arg("sh").args(args).output().unwrap();
    out.stdout
}
