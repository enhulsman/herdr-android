//! The phone as a viewer against a scripted fake core (task M2.1): what each
//! attach asks for, and every frame the phone sends (terminal-view spec,
//! "Watching never changes the terminal's size", "Size changes reattach
//! cleanly"; terminal-input spec, "Typing goes straight into the terminal at
//! phone size", "Leaving the terminal stops leading").

use herdr_proto::rpc::{CallError, Client, StreamItem};
use herdr_proto::viewer::{Attached, HISTORY, Mode, Viewer};
use herdr_proto::wire::{self, Frame};
use serde_json::{Value, json};
use tokio::io::{AsyncWriteExt, DuplexStream, ReadHalf, WriteHalf};

const PHONE: (u16, u16) = (60, 30);
const DESK: (u16, u16) = (180, 50);

struct FakeCore {
    r: ReadHalf<DuplexStream>,
    w: WriteHalf<DuplexStream>,
    /// Every frame the phone sent, in order.
    seen: Vec<Frame>,
}

impl FakeCore {
    async fn frame(&mut self) -> Frame {
        let f = wire::read_frame(&mut self.r)
            .await
            .unwrap()
            .expect("the app closed the connection");
        self.seen.push(f.clone());
        f
    }

    /// The next frame that is not CREDIT (credit is returned as data is read).
    async fn next(&mut self) -> Frame {
        loop {
            let f = self.frame().await;
            if f.kind != wire::CREDIT {
                return f;
            }
        }
    }

    async fn request(&mut self) -> Value {
        let f = self.next().await;
        assert_eq!((f.channel, f.kind), (0, wire::DATA), "a request: {f:?}");
        serde_json::from_slice(&f.payload).unwrap()
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

    /// Answers a `terminal.attach` with `channel` and a terminal of that size;
    /// returns its params.
    async fn attach(&mut self, channel: u32, (cols, rows): (u16, u16)) -> Value {
        let req = self.request().await;
        assert_eq!(req["method"], "terminal.attach");
        let terminal = json!({"address": "t3", "cols": cols, "rows": rows});
        self.reply(
            &req["id"],
            json!({"channel": channel, "terminal": terminal}),
        )
        .await;
        req["params"].clone()
    }

    /// Everything the phone sends until `marker` (a call made by the test):
    /// frames are written in order, so the marker's arrival bounds them.
    async fn until_marker(&mut self) -> Vec<Frame> {
        let from = self.seen.len();
        loop {
            let f = self.frame().await;
            if f.channel == 0 && f.kind == wire::DATA {
                let req: Value = serde_json::from_slice(&f.payload).unwrap();
                if req["method"] == "test.marker" {
                    self.reply(&req["id"], json!({})).await;
                    let seen = self.seen[from..self.seen.len() - 1].to_vec();
                    return seen;
                }
            }
        }
    }
}

fn connect() -> (Client, FakeCore) {
    let (app, core) = tokio::io::duplex(1 << 20);
    let (app_r, app_w) = tokio::io::split(app);
    let (r, w) = tokio::io::split(core);
    let core = FakeCore {
        r,
        w,
        seen: Vec::new(),
    };
    (Client::start(app_r, app_w), core)
}

async fn marker(client: &Client, core: &mut FakeCore) -> Vec<Frame> {
    let c = client.clone();
    let call = tokio::spawn(async move { c.call("test.marker", json!({})).await });
    let frames = core.until_marker().await;
    call.await.unwrap().unwrap();
    frames
}

/// Only CREDIT and CLOSE: no input, RESIZE, FOCUS or FACTS, no call.
fn assert_sends_nothing(frames: &[Frame], what: &str) {
    for f in frames {
        assert!(
            f.channel != 0 && matches!(f.kind, wire::CREDIT | wire::CLOSE),
            "{what}: the phone sent {f:?}"
        );
    }
}

fn assert_never_resizes_focuses_or_sends_facts(frames: &[Frame]) {
    for f in frames {
        assert!(
            !matches!(f.kind, wire::RESIZE | wire::FOCUS | wire::FACTS) || f.channel == 0,
            "the phone sent {f:?}"
        );
    }
}

async fn data(viewer: &mut Viewer) -> Vec<u8> {
    match viewer.recv().await {
        StreamItem::Data(d) => d,
        StreamItem::Closed(v) => panic!("closed: {v}"),
        StreamItem::Lost => panic!("lost"),
    }
}

/// Watching on channel 7, the terminal at the desk's size, first paint read.
async fn watching(client: &Client, core: &mut FakeCore) -> Viewer {
    let c = client.clone();
    let open = tokio::spawn(async move { Viewer::watch(&c, "t3", PHONE).await });
    let params = core.attach(7, DESK).await;
    assert_eq!(params["observe"], true);
    let (mut viewer, attached) = open.await.unwrap().unwrap();
    assert_eq!(
        attached,
        Attached {
            cols: DESK.0,
            rows: DESK.1,
            mode: Mode::Watching,
            terminal: json!({"address": "t3", "cols": 180, "rows": 50}),
        }
    );
    core.send(7, wire::DATA, b"\x18first paint").await;
    assert_eq!(data(&mut viewer).await, b"\x18first paint");
    viewer
}

/// Typing on channel 9 at the phone's size, after typing `y`.
async fn typing(client: &Client, core: &mut FakeCore) -> Viewer {
    let mut viewer = watching(client, core).await;
    let typed = tokio::spawn(async move {
        let r = viewer.input(b"y").await;
        (viewer, r)
    });
    let close = core.next().await;
    assert_eq!((close.channel, close.kind), (7, wire::CLOSE));
    core.attach(9, PHONE).await;
    let input = core.next().await;
    assert_eq!(
        (input.channel, input.payload.as_slice()),
        (9, b"y".as_slice())
    );
    let (viewer, attached) = typed.await.unwrap();
    assert_eq!(attached.unwrap().unwrap().mode, Mode::Typing);
    viewer
}

#[tokio::test(start_paused = true)]
async fn watching_attaches_as_an_observer_and_sends_nothing() {
    let (client, mut core) = connect();
    let c = client.clone();
    let open = tokio::spawn(async move { Viewer::watch(&c, "t3", PHONE).await });
    let params = core.attach(7, DESK).await;
    assert_eq!(params["terminal"], "t3");
    assert_eq!(params["observe"], true);
    assert_eq!(params["history"], HISTORY);
    assert_eq!(HISTORY, 2000);
    assert!(
        params.get("display").is_none(),
        "no display facts while watching: {params}"
    );
    let (mut viewer, attached) = open.await.unwrap().unwrap();
    assert_eq!(
        (attached.cols, attached.rows),
        DESK,
        "drawn at its real size"
    );
    assert_eq!(viewer.size(), DESK);
    assert_eq!(viewer.mode(), Mode::Watching);

    // Output streams in and is read (CREDIT goes back), the size event for
    // the size it already has changes nothing.
    for chunk in [b"\x18first paint".as_slice(), b"agent output", b"more"] {
        core.send(7, wire::DATA, chunk).await;
        assert_eq!(data(&mut viewer).await, chunk);
    }
    assert_eq!(viewer.size_changed(DESK.0, DESK.1).await.unwrap(), None);

    let sent = marker(&client, &mut core).await;
    assert_sends_nothing(&sent, "while watching");
    assert!(
        sent.iter()
            .any(|f| f.kind == wire::CREDIT && f.channel == 7),
        "credit is returned: {sent:?}"
    );
    drop(viewer);
}

#[tokio::test(start_paused = true)]
async fn a_size_event_discards_the_attach_and_attaches_afresh() {
    let (client, mut core) = connect();
    let mut viewer = watching(&client, &mut core).await;

    let resized = tokio::spawn(async move {
        let r = viewer.size_changed(120, 40).await;
        (viewer, r)
    });
    // The old attach is closed before the new one is asked for.
    let close = core.next().await;
    assert_eq!((close.channel, close.kind), (7, wire::CLOSE));
    let params = core.attach(8, (120, 40)).await;
    assert_eq!(params["observe"], true);
    assert_eq!(params["history"], HISTORY);
    let (mut viewer, attached) = resized.await.unwrap();
    let attached = attached.unwrap().expect("a fresh attach");
    assert_eq!(
        (attached.cols, attached.rows, attached.mode),
        (120, 40, Mode::Watching)
    );
    assert_eq!(viewer.size(), (120, 40));

    // Output still in flight on the old attach is never drawn.
    core.send(7, wire::DATA, b"stale line at 180 columns").await;
    core.send(8, wire::DATA, b"\x18fresh paint").await;
    assert_eq!(data(&mut viewer).await, b"\x18fresh paint");

    // The same size again: nothing to do.
    assert_eq!(viewer.size_changed(120, 40).await.unwrap(), None);
    let sent = marker(&client, &mut core).await;
    assert_sends_nothing(&sent, "after the reattach");
    assert_never_resizes_focuses_or_sends_facts(&core.seen);
}

#[tokio::test(start_paused = true)]
async fn the_first_input_reattaches_at_the_phones_size_with_null_display() {
    let (client, mut core) = connect();
    let mut viewer = watching(&client, &mut core).await;

    let typed = tokio::spawn(async move {
        let r = viewer.input(b"y").await;
        (viewer, r)
    });
    let close = core.next().await;
    assert_eq!((close.channel, close.kind), (7, wire::CLOSE));
    let params = core.attach(9, PHONE).await;
    assert_eq!(params["observe"], false);
    assert_eq!(
        (params["cols"].clone(), params["rows"].clone()),
        (json!(60), json!(30))
    );
    assert_eq!(params["history"], HISTORY);
    assert_eq!(
        params["display"],
        json!({"fg": null, "bg": null, "cursor": null, "cell": null}),
        "null display keeps the desk's colours and cell size"
    );
    let input = core.next().await;
    assert_eq!(
        (input.channel, input.kind, input.payload.as_slice()),
        (9, wire::DATA, b"y".as_slice()),
        "the key goes to the new attach"
    );
    let (mut viewer, attached) = typed.await.unwrap();
    let attached = attached.unwrap().expect("a fresh attach");
    assert_eq!(
        (attached.cols, attached.rows, attached.mode),
        (60, 30, Mode::Typing)
    );
    assert_eq!(viewer.mode(), Mode::Typing);

    // Later input goes straight in; the size event for the phone's own size
    // (from its own attach) changes nothing.
    assert_eq!(viewer.input(b"es\r").await.unwrap(), None);
    assert_eq!(viewer.size_changed(PHONE.0, PHONE.1).await.unwrap(), None);
    let sent = marker(&client, &mut core).await;
    let data: Vec<&Frame> = sent.iter().filter(|f| f.kind == wire::DATA).collect();
    assert_eq!(data.len(), 1, "one input frame, no call: {sent:?}");
    assert_eq!(
        (data[0].channel, data[0].payload.as_slice()),
        (9, b"es\r".as_slice())
    );
    assert_never_resizes_focuses_or_sends_facts(&core.seen);
}

/// "Back at the desk": the desk types, the core sets its size and says so;
/// the phone drops back to watching at the desk's size, and its next input
/// takes the phone's size again.
#[tokio::test(start_paused = true)]
async fn desk_input_takes_the_size_back_and_the_phone_watches_again() {
    let (client, mut core) = connect();
    let viewer = typing(&client, &mut core).await;

    let resized = tokio::spawn(async move {
        let mut viewer = viewer;
        let r = viewer.size_changed(DESK.0, DESK.1).await;
        (viewer, r)
    });
    let close = core.next().await;
    assert_eq!((close.channel, close.kind), (9, wire::CLOSE));
    let params = core.attach(10, DESK).await;
    assert_eq!(
        params["observe"], true,
        "watching, so the desk keeps its size"
    );
    let (viewer, attached) = resized.await.unwrap();
    let attached = attached.unwrap().expect("a fresh attach");
    assert_eq!(
        (attached.cols, attached.rows, attached.mode),
        (180, 50, Mode::Watching)
    );

    let typed = tokio::spawn(async move {
        let mut viewer = viewer;
        let r = viewer.input(b"n").await;
        (viewer, r)
    });
    let close = core.next().await;
    assert_eq!((close.channel, close.kind), (10, wire::CLOSE));
    let params = core.attach(11, PHONE).await;
    assert_eq!(params["observe"], false);
    let input = core.next().await;
    assert_eq!(
        (input.channel, input.payload.as_slice()),
        (11, b"n".as_slice())
    );
    let (viewer, attached) = typed.await.unwrap();
    assert_eq!(attached.unwrap().unwrap().mode, Mode::Typing);
    assert_never_resizes_focuses_or_sends_facts(&core.seen);
    drop(viewer);
}

#[tokio::test(start_paused = true)]
async fn leaving_the_terminal_detaches_the_active_viewer() {
    let (client, mut core) = connect();
    let viewer = typing(&client, &mut core).await;
    drop(viewer);
    let close = core.next().await;
    assert_eq!((close.channel, close.kind), (9, wire::CLOSE));
}

#[tokio::test(start_paused = true)]
async fn a_refused_attach_is_reported_and_the_next_input_tries_again() {
    let (client, mut core) = connect();
    let c = client.clone();
    let open = tokio::spawn(async move { Viewer::watch(&c, "t3", PHONE).await });
    let req = core.request().await;
    let err = json!({"jsonrpc": "2.0", "id": req["id"],
        "error": {"code": -32000, "message": "t3 has finished", "data": {"kind": "finished"}}});
    core.send(0, wire::DATA, err.to_string().as_bytes()).await;
    let Err(e) = open.await.unwrap() else {
        panic!("attach should fail")
    };
    assert_eq!(e.kind(), Some("finished"));

    // An input whose reattach fails sends nothing and stays watching.
    let mut viewer = watching(&client, &mut core).await;
    let typed = tokio::spawn(async move {
        let r = viewer.input(b"y").await;
        (viewer, r)
    });
    let _close = core.next().await;
    let req = core.request().await;
    let err = json!({"jsonrpc": "2.0", "id": req["id"],
        "error": {"code": -32000, "message": "no", "data": {"kind": "refused"}}});
    core.send(0, wire::DATA, err.to_string().as_bytes()).await;
    let (mut viewer, r) = typed.await.unwrap();
    assert!(matches!(r, Err(CallError::Rpc { .. })));
    assert_eq!(viewer.mode(), Mode::Watching);
    assert!(matches!(viewer.recv().await, StreamItem::Lost));
    let sent = marker(&client, &mut core).await;
    assert!(
        sent.iter().all(|f| f.kind != wire::DATA),
        "the key was not sent anywhere: {sent:?}"
    );

    let retry = tokio::spawn(async move {
        let r = viewer.input(b"y").await;
        (viewer, r)
    });
    core.attach(12, PHONE).await;
    let input = core.next().await;
    assert_eq!(
        (input.channel, input.payload.as_slice()),
        (12, b"y".as_slice())
    );
    let (_viewer, r) = retry.await.unwrap();
    assert_eq!(r.unwrap().unwrap().mode, Mode::Typing);
}

/// A desk resize while typing whose watcher reattach fails leaves no attach;
/// the next input must attach again (actively) and deliver, not vanish.
#[tokio::test(start_paused = true)]
async fn input_after_a_failed_reattach_from_typing_attaches_and_is_delivered() {
    let (client, mut core) = connect();
    let viewer = typing(&client, &mut core).await;
    let resized = tokio::spawn(async move {
        let mut viewer = viewer;
        let r = viewer.size_changed(DESK.0, DESK.1).await;
        (viewer, r)
    });
    let close = core.next().await;
    assert_eq!((close.channel, close.kind), (9, wire::CLOSE));
    let req = core.request().await;
    let err = json!({"jsonrpc": "2.0", "id": req["id"],
        "error": {"code": -32000, "message": "no", "data": {"kind": "refused"}}});
    core.send(0, wire::DATA, err.to_string().as_bytes()).await;
    let (mut viewer, r) = resized.await.unwrap();
    assert!(r.is_err());
    assert!(matches!(viewer.recv().await, StreamItem::Lost));

    let typed = tokio::spawn(async move {
        let r = viewer.input(b"x").await;
        (viewer, r)
    });
    let params = core.attach(13, PHONE).await;
    assert_eq!(params["observe"], false, "the input takes the phone's size");
    let input = core.next().await;
    assert_eq!(
        (input.channel, input.kind, input.payload.as_slice()),
        (13, wire::DATA, b"x".as_slice())
    );
    let (viewer, r) = typed.await.unwrap();
    assert_eq!(r.unwrap().unwrap().mode, Mode::Typing);
    assert_eq!(viewer.mode(), Mode::Typing);
}

/// Empty input (an encoder that produced nothing) is not input: no attach,
/// no frame, the terminal keeps its size.
#[tokio::test(start_paused = true)]
async fn empty_input_while_watching_sends_nothing_and_keeps_the_size() {
    let (client, mut core) = connect();
    let mut viewer = watching(&client, &mut core).await;
    assert_eq!(viewer.input(b"").await.unwrap(), None);
    assert_eq!(viewer.mode(), Mode::Watching);
    assert_eq!(viewer.size(), DESK);
    let sent = marker(&client, &mut core).await;
    assert_sends_nothing(&sent, "after empty input");
    assert!(
        sent.iter().all(|f| f.kind != wire::CLOSE),
        "the watcher stays attached: {sent:?}"
    );
}

/// Hiding the keyboard while typing (decisions.md 2026-11-02): the phone's
/// new size goes to the core as RESIZE on the active attach, and the size
/// event that follows is the phone's own, so nothing reattaches.
#[tokio::test(start_paused = true)]
async fn the_phone_resizing_while_typing_sends_resize_and_keeps_the_attach() {
    let (client, mut core) = connect();
    let mut viewer = typing(&client, &mut core).await;
    assert!(viewer.resize_phone(60, 45), "the VT takes the new size");
    assert_eq!(viewer.size(), (60, 45));
    let f = core.next().await;
    assert_eq!((f.channel, f.kind), (9, wire::RESIZE));
    assert_eq!(f.payload, [60u16.to_le_bytes(), 45u16.to_le_bytes()].concat());

    assert_eq!(viewer.size_changed(60, 45).await.unwrap(), None);
    let frames = marker(&client, &mut core).await;
    assert_sends_nothing(&frames, "the phone's own size event");
    assert_eq!(viewer.mode(), Mode::Typing);
}

#[tokio::test(start_paused = true)]
async fn the_phone_resizing_while_watching_sends_nothing_and_types_at_the_new_size() {
    let (client, mut core) = connect();
    let mut viewer = watching(&client, &mut core).await;
    assert!(!viewer.resize_phone(60, 45), "the VT keeps the desk's size");
    assert_eq!(viewer.size(), DESK);
    let frames = marker(&client, &mut core).await;
    assert_sends_nothing(&frames, "a watcher's own size change");

    let typed = tokio::spawn(async move {
        let r = viewer.input(b"y").await;
        (viewer, r)
    });
    let close = core.next().await;
    assert_eq!((close.channel, close.kind), (7, wire::CLOSE));
    let params = core.attach(9, (60, 45)).await;
    assert_eq!((params["cols"].clone(), params["rows"].clone()), (json!(60), json!(45)));
    let (_viewer, attached) = typed.await.unwrap();
    let attached = attached.unwrap().unwrap();
    assert_eq!((attached.cols, attached.rows), (60, 45));
}

#[tokio::test(start_paused = true)]
async fn a_zero_or_unchanged_phone_size_is_ignored() {
    let (client, mut core) = connect();
    let mut viewer = typing(&client, &mut core).await;
    assert!(!viewer.resize_phone(0, 45));
    assert!(!viewer.resize_phone(PHONE.0, PHONE.1));
    let frames = marker(&client, &mut core).await;
    assert_sends_nothing(&frames, "no size change");
}
