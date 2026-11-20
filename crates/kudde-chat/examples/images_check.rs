//! Counts only; run `images_check <log file>` (or `images_check codex <log file>`).
//! Uses the same bounded, filtered reads and parser as the phone.
use kudde_chat::{Agent, Parser, tail};
use std::process::Command;

fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    let path = match args.as_slice() {
        [path] => path,
        [agent, path] if agent == "codex" => path,
        _ => {
            eprintln!("usage: images_check <log file>");
            std::process::exit(2);
        }
    };
    let mut parser = Parser::new(Agent::Codex);
    let mut offset = 0;
    let mut wire_bytes = 0;
    loop {
        let out = Command::new("sh")
            .args(["-c", tail::SCRIPT, "sh", path, &offset.to_string()])
            .output()
            .expect("tail command");
        wire_bytes += out.stdout.len();
        let Some(chunk) =
            tail::parse_chunk(&String::from_utf8_lossy(&out.stdout)).map(tail::with_barrier)
        else {
            eprintln!("cannot read log");
            std::process::exit(1);
        };
        parser.feed_lines(&chunk.text, &chunk.offsets);
        if chunk.next >= chunk.size {
            break;
        }
        if chunk.next <= offset {
            eprintln!("incomplete record");
            std::process::exit(1);
        }
        offset = chunk.next;
    }
    let counts = parser.image_counts();
    println!("viewed {}", counts.values().sum::<usize>());
    println!("paired {}", counts.get("paired").unwrap_or(&0));
    println!("wire bytes {wire_bytes}");
    for (reason, count) in counts {
        if reason != "paired" {
            println!("placeholder {reason}: {count}");
        }
    }
}
