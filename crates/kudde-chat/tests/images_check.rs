use kudde_chat::tail;
use std::process::Command;

#[test]
fn example_reports_position_pairs_and_total_wire_bytes() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/codex-real-shape.jsonl");
    let mut offset = 0;
    let mut wire_bytes = 0;
    loop {
        let out = Command::new("sh")
            .args([
                "-c",
                tail::SCRIPT,
                "sh",
                path.to_str().unwrap(),
                &offset.to_string(),
            ])
            .output()
            .unwrap();
        assert!(out.status.success());
        wire_bytes += out.stdout.len();
        let chunk = tail::parse_chunk(std::str::from_utf8(&out.stdout).unwrap()).unwrap();
        if chunk.next >= chunk.size {
            break;
        }
        assert!(chunk.next > offset);
        offset = chunk.next;
    }
    let out = Command::new(env!("CARGO"))
        .args([
            "run",
            "--offline",
            "-p",
            "kudde-chat",
            "--example",
            "images_check",
            "--",
            path.to_str().unwrap(),
        ])
        .output()
        .unwrap();
    assert!(out.status.success());
    assert_eq!(
        String::from_utf8(out.stdout).unwrap(),
        format!("viewed 3\npaired 3\nwire bytes {wire_bytes}\n")
    );
}
