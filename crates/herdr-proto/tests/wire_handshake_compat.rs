//! Frame codec, the hello handshake and the compatibility check (task M1.1).

use herdr_proto::compat::{self, Compat};
use herdr_proto::handshake::{self, HandshakeError};
use herdr_proto::wire::{self, Frame};
use serde_json::{Value, json};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

// ---- wire ----

#[tokio::test]
async fn frames_round_trip() {
    let mut bytes = wire::encode(7, wire::DATA, b"hello");
    bytes.extend(wire::encode(0, wire::CREDIT, &4096u32.to_le_bytes()));
    bytes.extend(wire::encode(3, wire::CLOSE, b""));
    let mut r = bytes.as_slice();
    let f = wire::read_frame(&mut r).await.unwrap().unwrap();
    assert_eq!(
        f,
        Frame {
            channel: 7,
            kind: wire::DATA,
            payload: b"hello".to_vec()
        }
    );
    let f = wire::read_frame(&mut r).await.unwrap().unwrap();
    assert_eq!(
        (f.channel, f.kind, f.payload),
        (0, wire::CREDIT, 4096u32.to_le_bytes().to_vec())
    );
    let f = wire::read_frame(&mut r).await.unwrap().unwrap();
    assert_eq!((f.channel, f.kind, f.payload.len()), (3, wire::CLOSE, 0));
    // A clean end between frames is the end of the stream, not an error.
    assert!(wire::read_frame(&mut r).await.unwrap().is_none());
}

#[tokio::test]
async fn header_layout_is_channel_kind_length_little_endian() {
    let bytes = wire::encode(0x0102_0304, 5, &[9; 3]);
    assert_eq!(&bytes[..9], &[4, 3, 2, 1, 5, 3, 0, 0, 0]);
    assert_eq!(&bytes[9..], &[9, 9, 9]);
}

#[tokio::test]
async fn a_frame_over_16_mib_is_rejected_from_its_header() {
    // Only the header is there: rejecting it must not wait for, or allocate, the payload.
    let mut header = Vec::new();
    header.extend(1u32.to_le_bytes());
    header.push(wire::DATA);
    header.extend(((16u32 << 20) + 1).to_le_bytes());
    let (mut ours, mut theirs) = tokio::io::duplex(64);
    theirs.write_all(&header).await.unwrap();
    // `theirs` stays open: a reader that wanted the payload would hang here.
    let got = tokio::time::timeout(Duration::from_secs(5), wire::read_frame(&mut ours))
        .await
        .unwrap();
    let err = got.unwrap_err();
    assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
    // Exactly 16 MiB is allowed.
    assert!(
        wire::decode_header(&wire::encode(1, wire::DATA, &[])[..9].try_into().unwrap()).is_ok()
    );
    let mut max = [0u8; 9];
    max[5..9].copy_from_slice(&(16u32 << 20).to_le_bytes());
    assert_eq!(wire::decode_header(&max).unwrap().2, 16 << 20);
}

#[tokio::test]
async fn a_stream_cut_mid_frame_is_an_error() {
    let bytes = wire::encode(1, wire::DATA, b"abcdef");
    let mut r = &bytes[..12];
    assert!(wire::read_frame(&mut r).await.is_err());
    let mut r = &bytes[..4];
    assert!(wire::read_frame(&mut r).await.is_err());
}

// ---- handshake ----

fn reply(result: Value) -> String {
    format!("{}\n", json!({"jsonrpc": "2.0", "id": 0, "result": result}))
}

fn hello_result() -> Value {
    json!({"generation": 1, "version": "0.1.15", "core": {"id": "c1", "name": "workstation"},
           "framed": true, "methods": compat::USES, "features": []})
}

/// Runs the handshake against a scripted core: what the core prints, then
/// what the app wrote and what the handshake returned.
async fn handshake_against(
    core_output: Vec<u8>,
) -> (Vec<u8>, Result<handshake::Hello, HandshakeError>) {
    let (app, core) = tokio::io::duplex(1 << 20);
    let (mut app_r, mut app_w) = tokio::io::split(app);
    let (mut core_r, mut core_w) = tokio::io::split(core);
    let writer = tokio::spawn(async move {
        core_w.write_all(&core_output).await.unwrap();
        // The core's output ends here, as when the remote command exits.
        core_w.shutdown().await.unwrap();
        core_w
    });
    let got = handshake::handshake(&mut app_r, &mut app_w).await;
    // Dropping one half of a split pipe does not close it; shutting down does.
    app_w.shutdown().await.unwrap();
    let _core_w = writer.await.unwrap();
    let mut sent = Vec::new();
    core_r.read_to_end(&mut sent).await.unwrap();
    (sent, got)
}

#[tokio::test]
async fn hello_asks_for_framed_mode_and_says_nothing_about_cores() {
    let (sent, got) = handshake_against(reply(hello_result()).into_bytes()).await;
    let line = String::from_utf8(sent).unwrap();
    assert!(line.ends_with('\n'));
    let req: Value = serde_json::from_str(line.trim_end()).unwrap();
    assert_eq!(req["method"], "hello");
    assert_eq!(req["params"]["framed"], true);
    assert_eq!(req["params"]["generation"], compat::GENERATION);
    assert!(req["params"].get("core").is_none());
    assert!(req["params"].get("calls_you").is_none());
    assert_eq!(got.unwrap().result["version"], "0.1.15");
}

#[tokio::test]
async fn shell_noise_before_the_reply_is_skipped() {
    let mut out =
        b"Welcome to workstation\r\n\nLast login: yesterday\n{\"not\":\"ours\"}\n".to_vec();
    out.extend(reply(hello_result()).as_bytes());
    let got = handshake_against(out).await.1.unwrap();
    assert_eq!(got.result["generation"], 1);
    assert!(got.leftover.is_empty());
}

#[tokio::test]
async fn bytes_after_the_reply_are_frames_and_are_kept() {
    let frame = wire::encode(
        0,
        wire::DATA,
        b"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
    );
    let mut out = reply(hello_result()).into_bytes();
    out.extend(&frame);
    let got = handshake_against(out).await.1.unwrap();
    // Whatever was read past the reply comes back; the rest is still in the reader.
    assert!(frame.starts_with(&got.leftover));
}

#[tokio::test]
async fn noise_up_to_64_kib_is_fine_and_more_fails() {
    let line = |n: usize| {
        let mut l = vec![b'x'; n - 1];
        l.push(b'\n');
        l
    };
    let mut out = line(32 * 1024);
    out.extend(line(32 * 1024));
    out.extend(reply(hello_result()).as_bytes());
    assert!(handshake_against(out).await.1.is_ok());

    let mut out = line(64 * 1024 + 1);
    out.extend(reply(hello_result()).as_bytes());
    assert!(matches!(
        handshake_against(out).await.1,
        Err(HandshakeError::NoiseTooLong)
    ));

    // One endless line with no newline at all fails the same way.
    let out = vec![b'y'; 200 * 1024];
    assert!(matches!(
        handshake_against(out).await.1,
        Err(HandshakeError::NoiseTooLong)
    ));
}

#[tokio::test]
async fn a_core_that_closes_before_replying_is_reported_as_closed() {
    let out = b"herdr-core: something printed on stdout\n".to_vec();
    assert!(matches!(
        handshake_against(out).await.1,
        Err(HandshakeError::Closed)
    ));
}

#[tokio::test]
async fn a_refused_hello_carries_the_core_error() {
    let out = format!(
        "{}\n",
        json!({"jsonrpc":"2.0","id":0,"error":{"code":-32000,"message":"unsupported_generation","data":{"kind":"unsupported_generation"}}})
    );
    match handshake_against(out.into_bytes()).await.1 {
        Err(HandshakeError::Refused { kind, message }) => {
            assert_eq!(kind.as_deref(), Some("unsupported_generation"));
            assert_eq!(message, "unsupported_generation");
        }
        _ => panic!("expected a refusal"),
    }
}

#[tokio::test(start_paused = true)]
async fn a_core_that_never_answers_times_out() {
    let (app, _core) = tokio::io::duplex(1024);
    let (mut r, mut w) = tokio::io::split(app);
    let start = tokio::time::Instant::now();
    let got = handshake::handshake(&mut r, &mut w).await;
    assert!(matches!(got, Err(HandshakeError::Timeout)));
    assert_eq!(start.elapsed(), handshake::HELLO_TIMEOUT);
}

// ---- compat ----

fn hello(generation: u64, version: &str, methods: &[&str]) -> Value {
    json!({"generation": generation, "version": version, "methods": methods})
}

#[test]
fn the_pinned_core_is_the_oldest_supported_and_tested() {
    assert_eq!(compat::MIN_CORE, "0.1.15");
    assert_eq!(compat::check(&hello(1, "0.1.15", compat::USES)), Compat::Ok);
}

#[test]
fn older_core() {
    assert_eq!(
        compat::check(&hello(1, "0.1.14", compat::USES)),
        Compat::TooOld {
            have: "0.1.14".into(),
            need: "0.1.15".into()
        }
    );
    // Lacking a method the app calls counts as too old too, naming the version.
    let without: Vec<&str> = compat::USES.iter().skip(1).copied().collect();
    assert_eq!(
        compat::check(&hello(1, "0.1.16", &without)),
        Compat::Missing {
            have: "0.1.16".into(),
            methods: vec![compat::USES[0].to_owned()]
        }
    );
    // An older generation, or no version at all.
    assert!(matches!(
        compat::check(&hello(0, "0.1.16", compat::USES)),
        Compat::TooOld { .. }
    ));
    assert!(matches!(
        compat::check(&json!({"generation": 1, "methods": compat::USES})),
        Compat::TooOld { .. }
    ));
}

#[test]
fn newer_core() {
    // A newer core connects with no notice (phone check round 2: CI tests the
    // latest release; bugs in a newer one become issues).
    for v in ["0.1.16", "0.2.0", "0.10.0", "0.1.16-rc1"] {
        assert_eq!(compat::check(&hello(1, v, compat::USES)), Compat::Ok, "{v}");
    }
    assert_eq!(
        compat::check(&hello(2, "1.0.0", compat::USES)),
        Compat::NewerGeneration { generation: 2 }
    );
}

#[test]
fn every_method_the_app_calls_is_checked_at_connect() {
    // A core lacking one of these would connect, then fail when an agent opens.
    for method in [
        "events.subscribe",
        "terminal.attach",
        "terminal.get",
        "terminal.interrupt",
    ] {
        assert!(compat::USES.contains(&method), "{method} not in USES");
        let without: Vec<&str> = compat::USES
            .iter()
            .copied()
            .filter(|m| *m != method)
            .collect();
        assert_eq!(
            compat::check(&hello(1, "0.1.15", &without)),
            Compat::Missing {
                have: "0.1.15".into(),
                methods: vec![method.to_owned()]
            }
        );
    }
}
