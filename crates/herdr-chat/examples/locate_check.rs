//! Spike check: runs the locate script for each pid given and prints the
//! decision kind and session id prefix (no content).

use herdr_chat::locate::{self, Found};
use std::process::Command;

fn main() {
    for pid in std::env::args().skip(1) {
        let out = Command::new("sh").args(["-c", locate::SCRIPT, "sh", &pid]).output().unwrap();
        let probe = locate::parse_probe(&String::from_utf8_lossy(&out.stdout));
        let d = match locate::decide(&probe) {
            Found::Log { session, .. } => format!("log {}", &session[..8]),
            Found::Empty { session } => format!("empty {}", &session[..8]),
            Found::Nothing(why) => format!("nothing: {why}"),
        };
        println!("{pid} {} -> {d}", probe.name);
    }
}
