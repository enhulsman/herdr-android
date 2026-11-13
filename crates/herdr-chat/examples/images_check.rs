//! Spike check against real logs: every image the parser finds, fetched the
//! way the phone would. Prints counts, sizes and times, never content.
//! `images_check <claude|codex> <log path> [budget bytes]`.

use herdr_chat::{Agent, Image, Item, Parser, image, tail};
use std::process::Command;
use std::time::Instant;

fn sh(script: &str, args: &[&str]) -> Vec<u8> {
    let mut c = Command::new("sh");
    c.args(["-c", script, "sh"]).args(args);
    c.output().unwrap().stdout
}

fn kind(b: &[u8]) -> &'static str {
    match b {
        [0x89, b'P', b'N', b'G', ..] => "png",
        [0xff, 0xd8, ..] => "jpeg",
        [b'G', b'I', b'F', ..] => "gif",
        [b'R', b'I', b'F', b'F', _, _, _, _, b'W', b'E', b'B', b'P', ..] => "webp",
        _ => "other",
    }
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let agent = if a[1] == "codex" { Agent::Codex } else { Agent::Claude };
    let path = &a[2];
    let offset = a.get(3).map(|b| format!("-{b}")).unwrap_or_else(|| "0".into());
    let raw = std::fs::metadata(path).unwrap().len();
    let out = sh(tail::SCRIPT, &[path, &offset]);
    let chunk = tail::parse_chunk(&String::from_utf8_lossy(&out)).expect("chunk");
    let mut p = Parser::new(agent);
    p.feed(&chunk.text);
    let mut all = Vec::new();
    for i in p.items() {
        match i {
            Item::User { images, .. } => all.extend(images.iter().map(|m| ("user", m.clone()))),
            Item::Tools { calls } => {
                for c in calls {
                    all.extend(c.images.iter().map(|m| ("tool", m.clone())))
                }
            }
            _ => {}
        }
    }
    let (mut ok, mut gone, mut missing, mut inline, mut bytes, mut slowest) = (0, 0, 0, 0, 0usize, 0u128);
    let mut kinds = std::collections::BTreeMap::new();
    for (_, m) in &all {
        let t = Instant::now();
        let got = match m {
            Image::InLog { line, n, .. } => image::decode(&sh(image::LOG_SCRIPT, &[path, &line.to_string(), &n.to_string()])),
            Image::File { path } => Some(sh(image::FILE_SCRIPT, &[path])).filter(|b| !b.is_empty()),
            Image::Inline { .. } => {
                inline += 1;
                continue;
            }
            Image::Missing => {
                missing += 1;
                continue;
            }
        };
        slowest = slowest.max(t.elapsed().as_millis());
        match got {
            Some(b) => {
                ok += 1;
                bytes += b.len();
                *kinds.entry(kind(&b)).or_insert(0) += 1;
            }
            None => gone += 1,
        }
    }
    println!(
        "raw {raw} sent {} images {} (user {}, tool {}) fetched {ok} gone {gone} missing {missing} inline {inline} kinds {kinds:?} avg {} KB slowest {slowest} ms health {:?}",
        chunk.text.len(),
        all.len(),
        all.iter().filter(|x| x.0 == "user").count(),
        all.iter().filter(|x| x.0 == "tool").count(),
        if ok > 0 { bytes / ok / 1000 } else { 0 },
        p.health()
    );
}
