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
    let offset = a.get(3).map(|b| format!("-{b}")).unwrap_or_else(|| "0".into());
    let t = Instant::now();
    let out = Command::new("sh")
        .args(["-c", tail::SCRIPT, "sh", path, &offset])
        .output()
        .unwrap();
    let ms = t.elapsed().as_millis();
    let chunk = tail::parse_chunk(&String::from_utf8_lossy(&out.stdout)).expect("chunk");
    let mut p = Parser::new(agent);
    p.feed(&chunk.text);
    let (mut u, mut s, mut tools, mut calls, mut notes, mut running, mut img) = (0, 0, 0, 0, 0, 0, 0);
    for i in p.items() {
        match i {
            Item::User { images, .. } => {
                u += 1;
                img += images
            }
            Item::Assistant { .. } => s += 1,
            Item::Tools { calls: c } => {
                tools += 1;
                calls += c.len();
                running += c.iter().filter(|c| !c.done).count()
            }
            Item::Note { .. } => notes += 1,
        }
    }
    println!(
        "{:>6.1} MB -> {:>6.2} MB sent ({:>3.0}%) in {ms} ms | user {u} (img {img}) said {s} tools {tools}/{calls} calls ({running} open) notes {notes} | v{} | {:?}",
        chunk.size as f64 / 1e6,
        chunk.text.len() as f64 / 1e6,
        100.0 * chunk.text.len() as f64 / (chunk.size.max(1) as f64 - offset.trim_start_matches('-').parse::<f64>().map(|b| (chunk.size as f64 - b).max(0.0)).unwrap_or(0.0)).max(1.0),
        p.version().unwrap_or("?"),
        p.health()
    );
}
