//! Task M0.7, Spike C: encrypt test payloads to a phone's UnifiedPush
//! registration and POST them to ntfy, printing what the phone should report.
//!
//! cargo run -p herdr-push --example push_spike -- \
//!     <endpoint> <p256dh> <auth> <token-file> [json|binary|all]
//!
//! The token file holds the ntfy access token (write-only on `up*`). The phone
//! logs each decrypted message's length and SHA-256 under herdr-spike-push.

use herdr_push::webpush::{self, Subscription};
use sha2::{Digest, Sha256};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [endpoint, p256dh, auth, token_file, rest @ ..] = &args[..] else {
        eprintln!("usage: push_spike <endpoint> <p256dh> <auth> <token-file> [json|binary|all]");
        std::process::exit(2);
    };
    let mode = rest.first().map(String::as_str).unwrap_or("all");
    let token = std::fs::read_to_string(token_file).expect("token file is readable");
    let subscription = Subscription {
        endpoint: endpoint.clone(),
        p256dh: p256dh.clone(),
        auth: auth.clone(),
    };

    let mut payloads: Vec<(&str, Vec<u8>)> = Vec::new();
    if matches!(mode, "json" | "all") {
        let at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let json = format!(
            r#"{{"v":1,"type":"test","machine":"workstation","name":"api","state":"blocked","at":{at}}}"#
        );
        payloads.push(("json", json.into_bytes()));
    }
    if matches!(mode, "binary" | "all") {
        // Every byte value, as large as one record allows.
        payloads.push(("binary", (0..=255u8).cycle().take(3967).collect()));
    }

    for (name, payload) in payloads {
        let sha = Sha256::digest(&payload);
        let body = webpush::encrypt(&subscription, &payload).expect("encrypts");
        let sha_hex: String = sha.iter().map(|b| format!("{b:02x}")).collect();
        println!(
            "{name}: {} bytes, sha256 {sha_hex}, body {} bytes",
            payload.len(),
            body.len()
        );
        match webpush::post(endpoint, token.trim(), body) {
            Ok(accepted) => println!("  ntfy: {} {}", accepted.status, accepted.body.trim()),
            Err(e) => println!("  {e}"),
        }
    }
}
