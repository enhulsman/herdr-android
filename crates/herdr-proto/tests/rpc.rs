//! The JSON-RPC client over framed mode, against a scripted fake core (task M1.1).
//! Time is tokio's paused clock: timeouts and the dead-link rule run instantly.

use herdr_proto::rpc::{self, CallError, Client, Ended, StreamItem};
use herdr_proto::wire::{self, Frame};
use serde_json::{Value, json};
use std::time::Duration;
use tokio::io::{AsyncWriteExt, DuplexStream, ReadHalf, WriteHalf};
use tokio::time::Instant;

struct FakeCore {
    r: ReadHalf<DuplexStream>,
    w: WriteHalf<DuplexStream>,
}

impl FakeCore {
    async fn frame(&mut self) -> Frame {
        wire::read_frame(&mut self.r)
            .await
            .unwrap()
            .expect("the app closed the connection")
    }

    /// The next JSON-RPC request on channel 0, skipping CREDIT and other frames.
    async fn request(&mut self) -> Value {
        loop {
            let f = self.frame().await;
            if f.channel == 0 && f.kind == wire::DATA {
                return serde_json::from_slice(&f.payload).unwrap();
            }
        }
    }

    async fn send(&mut self, channel: u32, kind: u8, payload: &[u8]) {
        self.w
            .write_all(&wire::encode(channel, kind, payload))
            .await
            .unwrap();
    }

    async fn reply(&mut self, id: &Value, result: Value) {
        let msg = json!({"jsonrpc": "2.0", "id": id, "result": result});
        self.send(0, wire::DATA, msg.to_string().as_bytes()).await;
    }
}

fn connect() -> (Client, FakeCore) {
    let (app, core) = tokio::io::duplex(1 << 20);
    let (app_r, app_w) = tokio::io::split(app);
    let (r, w) = tokio::io::split(core);
    (Client::start(app_r, app_w), FakeCore { r, w })
}

#[tokio::test(start_paused = true)]
async fn replies_find_their_call_by_id_in_any_order() {
    let (client, mut core) = connect();
    let a = tokio::spawn({
        let c = client.clone();
        async move { c.call("terminal.get", json!({"terminal": "t1"})).await }
    });
    let first = core.request().await;
    let b = tokio::spawn({
        let c = client.clone();
        async move { c.call("terminal.get", json!({"terminal": "t2"})).await }
    });
    let second = core.request().await;
    assert_ne!(first["id"], second["id"]);
    assert_eq!(first["jsonrpc"], "2.0");
    core.reply(&second["id"], json!({"address": "t2"})).await;
    core.reply(&first["id"], json!({"address": "t1"})).await;
    assert_eq!(a.await.unwrap().unwrap()["address"], "t1");
    assert_eq!(b.await.unwrap().unwrap()["address"], "t2");
}

#[tokio::test(start_paused = true)]
async fn an_error_reply_carries_kind_message_and_hint() {
    let (client, mut core) = connect();
    let call =
        tokio::spawn(async move { client.call("terminal.get", json!({"terminal": "t9"})).await });
    let req = core.request().await;
    let msg = json!({"jsonrpc": "2.0", "id": req["id"], "error": {"code": -32000, "message": "no terminal t9", "data": {"kind": "not_found", "hint": "hc ls"}}});
    core.send(0, wire::DATA, msg.to_string().as_bytes()).await;
    match call.await.unwrap() {
        Err(CallError::Rpc {
            kind,
            message,
            hint,
        }) => {
            assert_eq!(kind.as_deref(), Some("not_found"));
            assert_eq!(message, "no terminal t9");
            assert_eq!(hint.as_deref(), Some("hc ls"));
        }
        other => panic!("expected an rpc error, got {other:?}"),
    }
}

#[tokio::test(start_paused = true)]
async fn a_call_without_reply_has_an_unknown_outcome_after_10_s() {
    let (client, mut core) = connect();
    let start = Instant::now();
    let call = tokio::spawn({
        let c = client.clone();
        async move {
            c.call("terminal.send", json!({"terminal": "t1", "text": "y"}))
                .await
        }
    });
    let req = core.request().await;
    assert!(matches!(
        call.await.unwrap(),
        Err(CallError::OutcomeUnknown)
    ));
    assert_eq!(start.elapsed(), rpc::CALL_TIMEOUT);
    assert_eq!(rpc::CALL_TIMEOUT, Duration::from_secs(10));
    // The late reply is dropped quietly and the connection keeps working.
    core.reply(&req["id"], json!({})).await;
    let next = tokio::spawn(async move { client.call("server.status", json!({})).await });
    let req = core.request().await;
    core.reply(&req["id"], json!({"ok": true})).await;
    assert_eq!(next.await.unwrap().unwrap()["ok"], true);
}

#[tokio::test(start_paused = true)]
async fn slow_interrupt() {
    let (client, mut core) = connect();
    let start = Instant::now();
    let call = tokio::spawn(async move {
        client
            .call("terminal.interrupt", json!({"terminal": "t1"}))
            .await
    });
    core.request().await;
    assert!(matches!(
        call.await.unwrap(),
        Err(CallError::OutcomeUnknown)
    ));
    assert_eq!(start.elapsed(), Duration::from_secs(15));
    assert_eq!(
        CallError::OutcomeUnknown.to_string(),
        "outcome unknown, check the terminal"
    );
}

#[tokio::test(start_paused = true)]
async fn calls_fail_at_once_when_the_core_goes_away() {
    let (client, mut core) = connect();
    let pending = tokio::spawn({
        let c = client.clone();
        async move { c.call("terminal.get", json!({})).await }
    });
    core.request().await;
    let start = Instant::now();
    drop(core);
    assert!(matches!(pending.await.unwrap(), Err(CallError::Lost)));
    assert!(matches!(client.ended().await, Ended::Closed));
    assert!(matches!(
        client.call("terminal.get", json!({})).await,
        Err(CallError::Lost)
    ));
    assert!(start.elapsed() < Duration::from_secs(1));
}

#[tokio::test(start_paused = true)]
async fn silent_link() {
    let (client, mut core) = connect();
    let mut events = subscribe(&client, &mut core).await;
    let pending = tokio::spawn({
        let c = client.clone();
        async move { c.call("doc.get", json!({"name": "x"})).await }
    });
    core.request().await;
    // The fake core stays connected and sends nothing: a dropped network path.
    let start = Instant::now();
    assert!(matches!(client.ended().await, Ended::DeadLink));
    assert_eq!(start.elapsed(), rpc::DEAD_LINK);
    assert_eq!(rpc::DEAD_LINK, Duration::from_secs(45));
    // `pending` started together with the clock above but its own 10 s ran out first.
    assert!(matches!(
        pending.await.unwrap(),
        Err(CallError::OutcomeUnknown)
    ));
    assert!(matches!(events.recv().await, StreamItem::Lost));
    drop(core);
}

#[tokio::test(start_paused = true)]
async fn heartbeats_keep_a_quiet_link_alive() {
    let (client, mut core) = connect();
    let events = subscribe(&client, &mut core).await;
    let channel = events.channel();
    let beating = tokio::spawn(async move {
        for seq in 0..10 {
            tokio::time::sleep(Duration::from_secs(15)).await;
            let line = format!(
                "{}\n",
                json!({"jsonrpc": "2.0", "method": "heartbeat", "params": {"seq": seq}})
            );
            core.send(channel, wire::DATA, line.as_bytes()).await;
        }
        core
    });
    // 150 s of heartbeats only: no dead link.
    assert!(
        tokio::time::timeout(Duration::from_secs(140), client.ended())
            .await
            .is_err()
    );
    let _core = beating.await.unwrap();
    drop(events);
}

/// Subscribes and answers like a core: a snapshot and a fresh channel.
async fn subscribe(client: &Client, core: &mut FakeCore) -> rpc::Stream {
    let call = tokio::spawn({
        let c = client.clone();
        async move { c.subscribe(json!({})).await }
    });
    let req = core.request().await;
    assert_eq!(req["method"], "events.subscribe");
    core.reply(&req["id"], json!({"seq": 3, "terminals": [], "channel": 5}))
        .await;
    let (snapshot, stream) = call.await.unwrap().unwrap();
    assert_eq!(snapshot["seq"], 3);
    assert_eq!(stream.channel(), 5);
    stream
}

#[tokio::test(start_paused = true)]
async fn frames_that_beat_the_reply_wait_for_their_stream() {
    let (client, mut core) = connect();
    let call = tokio::spawn({
        let c = client.clone();
        async move { c.call("terminal.attach", json!({"terminal": "t1"})).await }
    });
    let req = core.request().await;
    core.reply(&req["id"], json!({"channel": 9})).await;
    // The first paint follows the reply at once, before the app opens the stream.
    core.send(9, wire::DATA, b"first paint").await;
    core.send(
        9,
        wire::CLOSE,
        br#"{"reason":"exited","exit_code":0,"signal":null}"#,
    )
    .await;
    let reply = call.await.unwrap().unwrap();
    tokio::time::sleep(Duration::from_millis(10)).await;
    let mut stream = client.open_stream(reply["channel"].as_u64().unwrap() as u32);
    assert!(matches!(stream.recv().await, StreamItem::Data(d) if d == b"first paint"));
    match stream.recv().await {
        StreamItem::Closed(why) => assert_eq!(why["reason"], "exited"),
        _ => panic!("expected the close reason"),
    }
}

#[tokio::test(start_paused = true)]
async fn long_output() {
    // 5 MiB through a core that honours the 256 KiB starting window: it only
    // finishes if the app returns CREDIT for what it consumes.
    const TOTAL: usize = 5 << 20;
    const CHUNK: usize = 16 * 1024;
    let (client, mut core) = connect();
    let mut stream = client.open_stream(11);
    let core_task = tokio::spawn(async move {
        let mut credit = wire::WINDOW;
        let mut sent = 0;
        let mut returned = 0usize;
        while sent < TOTAL {
            if credit >= CHUNK {
                let chunk: Vec<u8> = (0..CHUNK).map(|i| ((sent + i) % 251) as u8).collect();
                core.send(11, wire::DATA, &chunk).await;
                sent += CHUNK;
                credit -= CHUNK;
            } else {
                let f = core.frame().await;
                assert_eq!((f.channel, f.kind), (11, wire::CREDIT));
                let n = u32::from_le_bytes(f.payload.try_into().unwrap()) as usize;
                credit += n;
                returned += n;
            }
        }
        (core, returned)
    });
    let mut got = 0usize;
    while got < TOTAL {
        match stream.recv().await {
            StreamItem::Data(d) => {
                assert!(
                    d.iter()
                        .enumerate()
                        .all(|(i, b)| *b == ((got + i) % 251) as u8)
                );
                got += d.len();
            }
            _ => panic!("the stream ended early"),
        }
    }
    let (_core, returned) = tokio::time::timeout(Duration::from_secs(5), core_task)
        .await
        .unwrap()
        .unwrap();
    assert!(returned >= TOTAL - wire::WINDOW);
    assert_eq!(got, TOTAL);
}

#[tokio::test(start_paused = true)]
async fn an_oversized_frame_ends_the_connection() {
    let (client, mut core) = connect();
    let mut header = Vec::new();
    header.extend(0u32.to_le_bytes());
    header.push(wire::DATA);
    header.extend(u32::MAX.to_le_bytes());
    core.w.write_all(&header).await.unwrap();
    assert!(matches!(client.ended().await, Ended::Protocol(_)));
    drop(core);
}

#[tokio::test(start_paused = true)]
async fn notifications_and_junk_on_channel_0_are_ignored() {
    let (client, mut core) = connect();
    let call = tokio::spawn({
        let c = client.clone();
        async move { c.call("server.status", json!({})).await }
    });
    let req = core.request().await;
    core.send(
        0,
        wire::DATA,
        br#"{"jsonrpc":"2.0","method":"heartbeat","params":{"seq":1}}"#,
    )
    .await;
    core.send(0, wire::DATA, b"not json").await;
    core.send(
        0,
        wire::DATA,
        br#"{"jsonrpc":"2.0","id":99999,"result":{}}"#,
    )
    .await;
    core.reply(&req["id"], json!({"ok": 1})).await;
    assert_eq!(call.await.unwrap().unwrap()["ok"], 1);
}

#[tokio::test(start_paused = true)]
async fn the_writer_sends_frames_in_call_order() {
    let (client, mut core) = connect();
    client.send(4, wire::RESIZE, &[80, 0, 24, 0]);
    client.send(4, wire::FOCUS, &[1]);
    let f = core.frame().await;
    assert_eq!(
        (f.channel, f.kind, f.payload),
        (4, wire::RESIZE, vec![80, 0, 24, 0])
    );
    let f = core.frame().await;
    assert_eq!((f.channel, f.kind, f.payload), (4, wire::FOCUS, vec![1]));
}

#[tokio::test(start_paused = true)]
async fn closing_the_client_ends_the_connection() {
    let (client, mut core) = connect();
    client.close();
    assert!(wire::read_frame(&mut core.r).await.unwrap().is_none());
    assert!(matches!(
        client.call("server.status", json!({})).await,
        Err(CallError::Lost)
    ));
}

// ---- review findings: shutdown, detach, early-frame budget ----

/// A transport that never accepts a byte, and says when it is dropped.
struct Stuck(std::sync::Arc<std::sync::atomic::AtomicBool>);

impl tokio::io::AsyncWrite for Stuck {
    fn poll_write(
        self: std::pin::Pin<&mut Self>,
        _: &mut std::task::Context<'_>,
        _: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        std::task::Poll::Pending
    }
    fn poll_flush(
        self: std::pin::Pin<&mut Self>,
        _: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::task::Poll::Pending
    }
    fn poll_shutdown(
        self: std::pin::Pin<&mut Self>,
        _: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        std::task::Poll::Pending
    }
}

impl Drop for Stuck {
    fn drop(&mut self) {
        self.0.store(true, std::sync::atomic::Ordering::SeqCst);
    }
}

fn connect_stuck() -> (
    Client,
    std::sync::Arc<std::sync::atomic::AtomicBool>,
    FakeCore,
) {
    let dropped = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let (app, core) = tokio::io::duplex(1 << 20);
    let (app_r, _app_w) = tokio::io::split(app);
    let (r, w) = tokio::io::split(core);
    let client = Client::start(app_r, Stuck(dropped.clone()));
    // Something for the writer to get stuck on.
    client.send(1, wire::DATA, b"keys");
    (client, dropped, FakeCore { r, w })
}

#[tokio::test(start_paused = true)]
async fn a_writer_stuck_on_a_full_transport_is_dropped_when_the_connection_ends() {
    // Closed by the app.
    let (client, dropped, _core) = connect_stuck();
    tokio::time::sleep(Duration::from_millis(10)).await;
    client.close();
    tokio::time::sleep(Duration::from_secs(5)).await;
    assert!(dropped.load(std::sync::atomic::Ordering::SeqCst), "close");

    // The last handle dropped.
    let (client, dropped, _core) = connect_stuck();
    tokio::time::sleep(Duration::from_millis(10)).await;
    drop(client);
    tokio::time::sleep(Duration::from_secs(5)).await;
    assert!(dropped.load(std::sync::atomic::Ordering::SeqCst), "drop");

    // A dead link.
    let (client, dropped, _core) = connect_stuck();
    assert!(matches!(client.ended().await, Ended::DeadLink));
    tokio::time::sleep(Duration::from_secs(5)).await;
    assert!(
        dropped.load(std::sync::atomic::Ordering::SeqCst),
        "dead link"
    );
    drop(client);
}

#[tokio::test(start_paused = true)]
async fn dropping_a_stream_detaches_it_on_the_core() {
    let (client, mut core) = connect();
    let gone = client.open_stream(7);
    let mut kept = client.open_stream(8);
    drop(gone);
    let f = core.frame().await;
    assert_eq!((f.channel, f.kind, f.payload.len()), (7, wire::CLOSE, 0));
    // Frames still in flight for it are dropped, then the core acknowledges.
    core.send(7, wire::DATA, b"late").await;
    core.send(7, wire::CLOSE, b"").await;
    core.send(8, wire::DATA, b"still here").await;
    assert!(matches!(kept.recv().await, StreamItem::Data(d) if d == b"still here"));
    // A stream the core already closed is not closed again.
    core.send(8, wire::CLOSE, br#"{"reason":"exited"}"#).await;
    assert!(matches!(kept.recv().await, StreamItem::Closed(_)));
    drop(kept);
    client.send(0, wire::DATA, b"{}");
    // Skip the CREDIT returned for what `kept` consumed.
    let mut f = core.frame().await;
    while f.kind == wire::CREDIT {
        f = core.frame().await;
    }
    assert_eq!(
        (f.channel, f.kind),
        (0, wire::DATA),
        "no CLOSE for channel 8"
    );
}

#[tokio::test(start_paused = true)]
async fn a_flood_of_frames_for_unopened_channels_ends_the_connection() {
    for payload in [&b""[..], br#"{"reason":"exited"}"#] {
        let (client, mut core) = connect();
        let flood = tokio::spawn(async move {
            for channel in 100..200_000u32 {
                let kind = if payload.is_empty() {
                    wire::DATA
                } else {
                    wire::CLOSE
                };
                if core
                    .w
                    .write_all(&wire::encode(channel, kind, payload))
                    .await
                    .is_err()
                {
                    break;
                }
            }
            core
        });
        assert!(
            matches!(client.ended().await, Ended::Protocol(_)),
            "payload {payload:?}"
        );
        drop(flood.await.unwrap());
    }
}
