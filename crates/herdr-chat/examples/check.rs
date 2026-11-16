//! Spike check against real logs: prints counts and health, never content.
//! `check <claude|codex> <log path> [budget bytes]`: runs the tail script the
//! way the phone would (whole, or the last `budget` bytes), then parses.

use herdr_chat::{Agent, Item, Parser, tail};
use std::process::Command;
use std::time::Instant;

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let agent = if a[1] == "codex" { Agent::Codex } else { Agent::Claude };
    let path = &a[2];
    // With a budget: one read of the last `budget` bytes, as the phone opens a
    // chat. Without: read on from each read's `next` to the end of the file,
    // as the phone does past a skipped record or the 7.5 MB read cap.
    let budget = a.get(3);
    let mut offset = budget.map(|b| format!("-{b}")).unwrap_or_else(|| "0".into());
    let t = Instant::now();
    let mut p = Parser::new(agent);
    let size;
    let (mut read, mut sent, mut skipped, mut reads) = (0, 0, 0, 0);
    loop {
        let out = Command::new("sh")
            .args(["-c", tail::SCRIPT, "sh", path, &offset])
            .output()
            .unwrap();
        let chunk = tail::parse_chunk(&String::from_utf8_lossy(&out.stdout)).expect("chunk");
        reads += 1;
        read += chunk.next.saturating_sub(chunk.from);
        sent += chunk.text.len();
        skipped += chunk.skipped.is_some() as u32;
        p.feed_lines(&chunk.text, &chunk.offsets);
        if budget.is_some() || chunk.next >= chunk.size || chunk.next.to_string() == offset {
            size = chunk.size;
            break;
        }
        offset = chunk.next.to_string();
    }
    let ms = t.elapsed().as_millis();
    let (mut u, mut s, mut tools, mut calls, mut notes, mut running, mut img) = (0, 0, 0, 0, 0, 0, 0);
    for i in p.items() {
        match i {
            Item::User { images, text } => {
                if text.starts_with('<') {
                    let tag: String = text.chars().take_while(|c| *c != '>' && *c != ' ').take(30).collect();
                    eprintln!("tagged user text: {tag}");
                }
                u += 1;
                img += images.len()
            }
            Item::Assistant { .. } | Item::AgentMessage { .. } => s += 1,
            Item::Tools { calls: c } => {
                tools += 1;
                calls += c.len();
                running += c.iter().filter(|c| !c.done).count();
                img += c.iter().map(|c| c.images.len()).sum::<usize>()
            }
            Item::Note { .. } | Item::Update { .. } => notes += 1,
        }
    }
    println!(
        "{:>6.1} MB -> {:>6.2} MB sent ({:>3.0}% of the bytes read) in {reads} reads, {ms} ms, {skipped} skipped | user {u} (img {img}) said {s} tools {tools}/{calls} calls ({running} open) notes {notes} | v{} | {:?} | {:?}",
        size as f64 / 1e6,
        sent as f64 / 1e6,
        100.0 * sent as f64 / read.max(1) as f64,
        p.version().unwrap_or("?"),
        p.health(),
        p.gauge()
    );
}
