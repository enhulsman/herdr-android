//! An open terminal against the scripted core (task M2.1): attach, typing,
//! size events, reconnects, interrupt and leaving (terminal-view and
//! terminal-input specs; design.md "The size rule").

use super::*;
use crate::keys_row::{Key, Mods};
use crate::term::{self, Cmd, TermPhase, TermSink, TermState};

const PHONE: (u16, u16) = (53, 31);

#[derive(Debug)]
enum Seen {
    Frame(Vec<u8>),
    State(TermState),
    Interrupted(String),
}

struct Sink(mpsc::UnboundedSender<Seen>);

impl TermSink for Sink {
    fn frame(&self, bytes: Vec<u8>) {
        let _ = self.0.send(Seen::Frame(bytes));
    }
    fn state(&self, state: TermState) {
        let _ = self.0.send(Seen::State(state));
    }
    fn interrupted(&self, report: String) {
        let _ = self.0.send(Seen::Interrupted(report));
    }
}

/// What the phone shows, rebuilt from the frames it was handed.
struct Screen {
    seen: mpsc::UnboundedReceiver<Seen>,
    rows: HashMap<u16, String>,
    flags: u8,
    states: Vec<TermState>,
    /// States before this index were matched already.
    consumed: usize,
    reports: Vec<String>,
}

fn u16_at(b: &[u8], i: usize) -> u16 {
    u16::from_le_bytes([b[i], b[i + 1]])
}
fn u32_at(b: &[u8], i: usize) -> u32 {
    u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]])
}

impl Screen {
    fn apply(&mut self, f: &[u8]) {
        let cols = usize::from(u16_at(f, 0));
        let count = u16_at(f, 10);
        self.flags = f[8];
        if f[8] & 1 != 0 {
            self.rows.clear();
        }
        let mut at = 32;
        for _ in 0..count {
            let y = u16_at(f, at);
            at += 2;
            let cells = at;
            at += cols * 16;
            let mut line = String::new();
            for x in 0..cols {
                let c = &f[cells + x * 16..cells + (x + 1) * 16];
                if c[13] >= 2 {
                    continue;
                }
                line.push(
                    char::from_u32(u32_at(c, 0))
                        .filter(|c| *c != '\0')
                        .unwrap_or(' '),
                );
                for _ in 0..c[14] {
                    line.push(char::from_u32(u32_at(f, at)).unwrap());
                    at += 4;
                }
            }
            self.rows.insert(y, line.trim_end().to_owned());
        }
    }

    fn text(&self) -> String {
        let mut ys: Vec<_> = self.rows.keys().copied().collect();
        ys.sort_unstable();
        ys.iter()
            .map(|y| self.rows[y].as_str())
            .collect::<Vec<_>>()
            .join("\n")
    }

    async fn pump(&mut self) -> bool {
        match tokio::time::timeout(WAIT, self.seen.recv()).await {
            Ok(Some(Seen::Frame(f))) => self.apply(&f),
            Ok(Some(Seen::State(s))) => self.states.push(s),
            Ok(Some(Seen::Interrupted(r))) => self.reports.push(r),
            _ => return false,
        }
        true
    }

    async fn until_text(&mut self, want: &str) {
        while !self.text().contains(want) {
            assert!(
                self.pump().await,
                "never showed {want:?}; shows:\n{}",
                self.text()
            );
        }
    }

    /// The next state, in order, that `ok` accepts.
    async fn until_state(&mut self, ok: impl Fn(&TermState) -> bool) -> TermState {
        loop {
            if let Some(i) = self.states[self.consumed..].iter().position(&ok) {
                self.consumed += i + 1;
                return self.states[self.consumed - 1].clone();
            }
            assert!(
                self.pump().await,
                "never the wanted state; saw {:?}",
                self.states
            );
        }
    }

    async fn until_report(&mut self) -> String {
        while self.reports.is_empty() {
            assert!(self.pump().await, "no interrupt report");
        }
        self.reports.remove(0)
    }
}

fn sized(address: &str, cols: u16, rows: u16) -> Value {
    let mut a = agent(address, "blocked", 1);
    a["cols"] = cols.into();
    a["rows"] = rows.into();
    a
}

impl Core {
    /// The next frame that is not CREDIT.
    async fn frame(&mut self) -> wire::Frame {
        loop {
            let f = wire::read_frame(&mut self.r).await.unwrap().expect("open");
            if f.kind != wire::CREDIT {
                return f;
            }
        }
    }

    async fn reply_to(&mut self, req: &Value, result: Value) {
        let msg = json!({"jsonrpc": "2.0", "id": req["id"], "result": result});
        self.send(0, wire::DATA, msg.to_string().as_bytes()).await;
    }

    /// Answers the next `terminal.attach` with `channel` at `size`; its params.
    async fn attach(&mut self, channel: u32, (cols, rows): (u16, u16)) -> Value {
        let req = self.request().await;
        assert_eq!(req["method"], "terminal.attach", "{req}");
        let t = json!({"address": "t3", "cols": cols, "rows": rows});
        self.reply_to(&req, json!({"channel": channel, "terminal": t}))
            .await;
        req["params"].clone()
    }

    /// Answers the observe check's `terminal.get` with `size`.
    async fn get(&mut self, (cols, rows): (u16, u16)) {
        let req = self.request().await;
        assert_eq!(req["method"], "terminal.get", "{req}");
        assert_eq!(req["params"]["terminal"], "t3");
        let t = json!({"address": "t3", "cols": cols, "rows": rows});
        self.reply_to(&req, t).await;
    }
}

struct Open {
    screen: Screen,
    cmds: mpsc::UnboundedSender<Cmd>,
}

fn open(rig: &Rig) -> Open {
    open_with(rig, false)
}

/// `lead`: open at the phone's size, as the app does since round 2.
fn open_with(rig: &Rig, lead: bool) -> Open {
    let (tx, rx) = mpsc::unbounded_channel();
    let cmds = term::spawn(&rig.hub, WS, "t3", PHONE, lead, Arc::new(Sink(tx)));
    Open {
        screen: Screen {
            seen: rx,
            rows: HashMap::new(),
            flags: 0,
            states: Vec::new(),
            consumed: 0,
            reports: Vec::new(),
        },
        cmds,
    }
}

/// Connected, the terminal open and watched at 80x24 on channel 5.
async fn watching(name: &str) -> (Rig, Core, Open) {
    let mut rig = rig(name);
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![sized("t3", 80, 24)]).await;
    rig.until_state(WS, connected).await;
    let mut o = open(&rig);
    let params = core.attach(5, (80, 24)).await;
    assert_eq!(params["observe"], true);
    assert_eq!(params["history"], 2000);
    assert!(params.get("display").is_none());
    core.get((80, 24)).await;
    core.send(5, wire::DATA, b"desk paint").await;
    o.screen.until_text("desk paint").await;
    let s = o
        .screen
        .until_state(|s| s.phase == TermPhase::Watching)
        .await;
    assert_eq!((s.cols, s.rows), (80, 24));
    (rig, core, o)
}

fn key(o: &Open, c: char) {
    o.cmds
        .send(Cmd::Key(Key::Char(c), Mods::default()))
        .unwrap();
}

// "Opening an agent": watching draws the desk's size and sends nothing.
#[tokio::test(start_paused = true)]
async fn watching_draws_the_first_paint_at_the_desks_size() {
    let (rig, mut core, o) = watching("term-watch").await;
    // Anything the phone sends now is a marker request; no RESIZE, FOCUS,
    // FACTS or input on the attach.
    let marker = tokio::spawn({
        let hub = rig.hub.clone();
        async move {
            let conn = hub.connection(WS).borrow().clone().unwrap();
            conn.client.call("test.marker", json!({})).await
        }
    });
    loop {
        let f = core.frame().await;
        if f.channel == 0 {
            let req: Value = serde_json::from_slice(&f.payload).unwrap();
            assert_eq!(req["method"], "test.marker");
            core.reply_to(&req, json!({})).await;
            break;
        }
        assert!(
            !matches!(
                f.kind,
                wire::DATA | wire::RESIZE | wire::FOCUS | wire::FACTS
            ),
            "watching sent {f:?}"
        );
    }
    marker.await.unwrap().unwrap();
    drop(o);
}

// "Core behaviour drift": a size change on an observe attach is logged.
#[tokio::test(start_paused = true)]
async fn an_observe_attach_that_changed_the_size_is_logged() {
    let mut rig = rig("term-drift");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![sized("t3", 80, 24)]).await;
    rig.until_state(WS, connected).await;
    let _o = open(&rig);
    core.attach(5, (80, 24)).await;
    core.get((100, 30)).await;
    let log = rig.dir.join("hub.log");
    for _ in 0..100 {
        if std::fs::read_to_string(&log).is_ok_and(|l| l.contains("observe attach")) {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    let text = std::fs::read_to_string(&log).unwrap_or_default();
    assert!(
        text.contains("desk size changed on observe attach: 80x24 -> 100x30"),
        "{text}"
    );
}

#[tokio::test(start_paused = true)]
async fn an_unchanged_size_logs_nothing() {
    let (rig, _core, _o) = watching("term-nodrift").await;
    tokio::time::sleep(Duration::from_secs(1)).await;
    let text = std::fs::read_to_string(rig.dir.join("hub.log")).unwrap_or_default();
    assert!(!text.contains("observe attach"), "{text}");
}

// "Typing on the phone": the first key reattaches at the phone's size with
// null display facts, then the key reaches the program.
#[tokio::test(start_paused = true)]
async fn the_first_key_reattaches_at_the_phones_size_then_types() {
    let (_rig, mut core, mut o) = watching("term-type").await;
    key(&o, 'y');
    let close = core.frame().await;
    assert_eq!((close.channel, close.kind), (5, wire::CLOSE));
    let params = core.attach(6, PHONE).await;
    assert_eq!(params["observe"], false);
    assert_eq!(
        (params["cols"].clone(), params["rows"].clone()),
        (json!(53), json!(31))
    );
    assert_eq!(
        params["display"],
        json!({"fg": null, "bg": null, "cursor": null, "cell": null})
    );
    let typed = core.frame().await;
    assert_eq!(
        (typed.channel, typed.kind, typed.payload.as_slice()),
        (6, wire::DATA, b"y".as_slice())
    );
    let s = o.screen.until_state(|s| s.phase == TermPhase::Typing).await;
    assert_eq!((s.cols, s.rows), PHONE);
    core.send(6, wire::DATA, b"phone paint").await;
    o.screen.until_text("phone paint").await;
    assert!(!o.screen.text().contains("desk paint"), "a fresh VT");

    // The next key goes straight in.
    key(&o, 'n');
    let typed = core.frame().await;
    assert_eq!(
        (typed.channel, typed.payload.as_slice()),
        (6, b"n".as_slice())
    );
}

// Keys-row keys and the compose paste go through the same path.
#[tokio::test(start_paused = true)]
async fn keys_row_keys_and_a_paste_reach_the_program_encoded() {
    let (_rig, mut core, o) = watching("term-keys").await;
    o.cmds.send(Cmd::Key(Key::Down, Mods::default())).unwrap();
    assert_eq!(core.frame().await.kind, wire::CLOSE);
    core.attach(6, PHONE).await;
    assert_eq!(core.frame().await.payload, b"\x1b[B");
    o.cmds
        .send(Cmd::Key(
            Key::Char('c'),
            Mods {
                ctrl: true,
                alt: false,
            },
        ))
        .unwrap();
    assert_eq!(core.frame().await.payload, b"\x03");
    o.cmds.send(Cmd::Paste("fix it\nplease".into())).unwrap();
    assert_eq!(
        core.frame().await.payload,
        b"\x1b[200~fix it\nplease\x1b[201~"
    );
}

// "Desk resizes mid-stream" and "Back at the desk": a size event that is not
// the phone's own discards the VT and attaches afresh as a watcher.
#[tokio::test(start_paused = true)]
async fn a_desk_size_event_reattaches_into_a_fresh_vt() {
    let (_rig, mut core, mut o) = watching("term-size").await;
    core.event(1, "size", "t3", json!({"cols": 120, "rows": 40}))
        .await;
    let close = core.frame().await;
    assert_eq!((close.channel, close.kind), (5, wire::CLOSE));
    let params = core.attach(7, (120, 40)).await;
    assert_eq!(params["observe"], true);
    core.get((120, 40)).await;
    let s = o
        .screen
        .until_state(|s| s.phase == TermPhase::Watching && s.cols == 120)
        .await;
    assert_eq!(s.rows, 40);
    core.send(7, wire::DATA, b"wider").await;
    o.screen.until_text("wider").await;
    assert!(!o.screen.text().contains("desk paint"));
}

// Hiding the keyboard while typing: RESIZE on the attach, the VT follows,
// and the phone's own size event changes nothing.
#[tokio::test(start_paused = true)]
async fn the_phones_new_size_while_typing_resizes_without_reattaching() {
    let (_rig, mut core, mut o) = watching("term-phone-size").await;
    key(&o, 'y');
    core.frame().await;
    core.attach(6, PHONE).await;
    core.frame().await;
    o.cmds.send(Cmd::PhoneSize(53, 45)).unwrap();
    let f = core.frame().await;
    assert_eq!((f.channel, f.kind), (6, wire::RESIZE));
    assert_eq!(
        f.payload,
        [53u16.to_le_bytes(), 45u16.to_le_bytes()].concat()
    );
    let s = o.screen.until_state(|s| s.rows == 45).await;
    assert_eq!((s.phase, s.cols), (TermPhase::Typing, 53));
    core.event(1, "size", "t3", json!({"cols": 53, "rows": 45}))
        .await;
    key(&o, 'z');
    let f = core.frame().await;
    assert_eq!(
        (f.channel, f.payload.as_slice()),
        (6, b"z".as_slice()),
        "no reattach"
    );
}

// "Reading while the agent works": scrolled up, output does not move the
// view; Live returns to the bottom.
#[tokio::test(start_paused = true)]
async fn scrolling_back_holds_the_view_and_live_returns() {
    let (_rig, mut core, mut o) = watching("term-scroll").await;
    let lines: String = (0..60).map(|i| format!("\r\nline {i}")).collect();
    core.send(5, wire::DATA, lines.as_bytes()).await;
    o.screen.until_text("line 59").await;
    assert_eq!(o.screen.flags & 4, 4, "live");
    o.cmds.send(Cmd::Scroll(-10)).unwrap();
    while o.screen.flags & 4 != 0 {
        assert!(o.screen.pump().await);
    }
    let held = o.screen.text();
    assert!(!held.contains("line 59"), "{held}");
    core.send(5, wire::DATA, b"\r\nline 60").await;
    tokio::time::sleep(Duration::from_millis(100)).await;
    while let Ok(seen) = o.screen.seen.try_recv() {
        if let Seen::Frame(f) = seen {
            o.screen.apply(&f);
        }
    }
    assert_eq!(o.screen.text(), held, "the view stays put");
    o.cmds.send(Cmd::Live).unwrap();
    o.screen.until_text("line 60").await;
    assert_eq!(o.screen.flags & 4, 4);
}

// "Stopping an agent".
#[tokio::test(start_paused = true)]
async fn interrupt_reports_what_the_core_says() {
    let (_rig, mut core, mut o) = watching("term-interrupt").await;
    o.cmds.send(Cmd::Interrupt).unwrap();
    let req = core.request().await;
    assert_eq!(req["method"], "terminal.interrupt");
    assert_eq!(req["params"]["terminal"], "t3");
    core.reply_to(&req, json!({"outcome": "stopped", "key": "esc"}))
        .await;
    assert_eq!(o.screen.until_report().await, "Stopped");
}

#[tokio::test(start_paused = true)]
async fn an_unanswered_interrupt_is_outcome_unknown() {
    let (_rig, mut core, mut o) = watching("term-interrupt-timeout").await;
    o.cmds.send(Cmd::Interrupt).unwrap();
    let req = core.request().await;
    assert_eq!(req["method"], "terminal.interrupt");
    assert_eq!(o.screen.until_report().await, "Outcome unknown");
}

// "Back to the list": leaving detaches the active viewer.
#[tokio::test(start_paused = true)]
async fn leaving_the_terminal_detaches() {
    let (_rig, mut core, o) = watching("term-leave").await;
    key(&o, 'y');
    core.frame().await;
    core.attach(6, PHONE).await;
    core.frame().await;
    drop(o);
    let f = core.frame().await;
    assert_eq!((f.channel, f.kind), (6, wire::CLOSE));
}

// design.md "Reconnect": the open terminal reattaches into a fresh VT.
#[tokio::test(start_paused = true)]
async fn a_reconnect_reattaches_the_open_terminal() {
    let (mut rig, core, mut o) = watching("term-reconnect").await;
    core.exit(0, "");
    o.screen
        .until_state(|s| s.phase == TermPhase::Waiting)
        .await;
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![sized("t3", 80, 24)]).await;
    let params = core.attach(5, (80, 24)).await;
    assert_eq!(params["observe"], true);
    core.get((80, 24)).await;
    core.send(5, wire::DATA, b"again").await;
    o.screen.until_text("again").await;
    o.screen
        .until_state(|s| s.phase == TermPhase::Watching)
        .await;
}

#[tokio::test(start_paused = true)]
async fn a_terminal_that_ends_says_so() {
    let (_rig, mut core, mut o) = watching("term-ended").await;
    core.send(5, wire::CLOSE, br#"{"reason":"exited"}"#).await;
    let s = o
        .screen
        .until_state(|s| matches!(s.phase, TermPhase::Ended { .. }))
        .await;
    assert_eq!(
        s.phase,
        TermPhase::Ended {
            reason: "The terminal closed".into()
        }
    );
}

#[tokio::test(start_paused = true)]
async fn suspending_the_hub_closes_the_connection_under_the_open_terminal() {
    let (rig, mut core, mut o) = watching("term-suspend").await;
    rig.hub.suspend();
    o.screen
        .until_state(|s| s.phase == TermPhase::Waiting)
        .await;
    assert!(
        core.closed_by_app(WAIT).await,
        "the view's client clone is closed too"
    );
}

// Codex review: a desk resize reported while the attach is in flight must
// not be lost; the phone ends up at the new size.
#[tokio::test(start_paused = true)]
async fn a_resize_during_the_attach_is_not_lost() {
    let mut rig = rig("term-attach-race");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![sized("t3", 80, 24)]).await;
    rig.until_state(WS, connected).await;
    let mut o = open(&rig);
    let req = core.request().await;
    assert_eq!(req["method"], "terminal.attach");
    // The desk resizes, then the (older) attach reply arrives.
    core.event(1, "size", "t3", json!({"cols": 120, "rows": 40}))
        .await;
    let t = json!({"address": "t3", "cols": 80, "rows": 24});
    core.reply_to(&req, json!({"channel": 5, "terminal": t}))
        .await;
    // Whatever else the phone asks (the observe check), it reattaches at 120x40.
    loop {
        let f = core.frame().await;
        if f.channel != 0 {
            continue;
        }
        let req: Value = serde_json::from_slice(&f.payload).unwrap();
        match req["method"].as_str() {
            Some("terminal.get") => {
                let t = json!({"address": "t3", "cols": 120, "rows": 40});
                core.reply_to(&req, t).await;
            }
            Some("terminal.attach") => {
                assert_eq!(req["params"]["observe"], true);
                let t = json!({"address": "t3", "cols": 120, "rows": 40});
                core.reply_to(&req, json!({"channel": 6, "terminal": t}))
                    .await;
                break;
            }
            other => panic!("unexpected {other:?}"),
        }
    }
    let s = o.screen.until_state(|s| s.cols == 120).await;
    assert_eq!((s.phase, s.rows), (TermPhase::Watching, 40));
}

// "Scrolling a full-screen agent": the program keeps its own history, so the
// flick goes to it as wheel events; while watching it first takes over at
// the phone's size, as the first key does.
#[tokio::test(start_paused = true)]
async fn scrolling_a_full_screen_program_sends_it_the_wheel_from_the_phone() {
    let (_rig, mut core, mut o) = watching("term-scroll-full").await;
    core.send(
        5,
        wire::DATA,
        b"\x1b[?1049h\x1b[?1000h\x1b[?1006hfull screen",
    )
    .await;
    o.screen.until_text("full screen").await;
    o.cmds.send(Cmd::Scroll(-2)).unwrap();
    let close = core.frame().await;
    assert_eq!((close.channel, close.kind), (5, wire::CLOSE));
    let params = core.attach(6, PHONE).await;
    assert_eq!(params["observe"], false);
    let wheel = core.frame().await;
    assert_eq!(
        (wheel.channel, wheel.kind, wheel.payload.as_slice()),
        (6, wire::DATA, b"\x1b[<64;1;1M\x1b[<64;1;1M".as_slice())
    );
    let s = o.screen.until_state(|s| s.phase == TermPhase::Typing).await;
    assert_eq!((s.cols, s.rows), PHONE);

    // The next flick goes straight to the program, with the modes of the new paint.
    core.send(
        6,
        wire::DATA,
        b"\x1b[?1049h\x1b[?1000h\x1b[?1006hphone paint",
    )
    .await;
    o.screen.until_text("phone paint").await;
    o.cmds.send(Cmd::Scroll(1)).unwrap();
    let wheel = core.frame().await;
    assert_eq!(
        (wheel.channel, wheel.payload.as_slice()),
        (6, b"\x1b[<65;1;1M".as_slice())
    );
}

// A tap on a program that takes mouse clicks, as on the desk: only while the
// phone types at its own size (the cell watched at desk size would move).
#[tokio::test(start_paused = true)]
async fn a_tap_clicks_a_mouse_program_only_while_typing() {
    let (_rig, mut core, mut o) = watching("term-click").await;
    core.send(
        5,
        wire::DATA,
        b"\x1b[?1049h\x1b[?1000h\x1b[?1006hfull screen",
    )
    .await;
    o.screen.until_text("full screen").await;
    o.cmds.send(Cmd::Click(3, 4)).unwrap();
    key(&o, 'y');
    let close = core.frame().await;
    assert_eq!(
        (close.channel, close.kind),
        (5, wire::CLOSE),
        "the click sent nothing"
    );
    core.attach(6, PHONE).await;
    let typed = core.frame().await;
    assert_eq!(
        (typed.channel, typed.payload.as_slice()),
        (6, b"y".as_slice())
    );
    o.screen.until_state(|s| s.phase == TermPhase::Typing).await;
    core.send(
        6,
        wire::DATA,
        b"\x1b[?1049h\x1b[?1000h\x1b[?1006hphone paint",
    )
    .await;
    o.screen.until_text("phone paint").await;
    o.cmds.send(Cmd::Click(3, 4)).unwrap();
    let click = core.frame().await;
    assert_eq!(
        (click.channel, click.payload.as_slice()),
        (6, b"\x1b[<0;4;5M\x1b[<0;4;5m".as_slice())
    );
}

// A tap while watching: the phone leads at its own size without a keystroke.
#[tokio::test(start_paused = true)]
async fn leading_takes_over_at_the_phones_size_without_typing() {
    let (_rig, mut core, mut o) = watching("term-lead").await;
    o.cmds.send(Cmd::Lead).unwrap();
    let close = core.frame().await;
    assert_eq!((close.channel, close.kind), (5, wire::CLOSE));
    let params = core.attach(6, PHONE).await;
    assert_eq!(params["observe"], false);
    let s = o.screen.until_state(|s| s.phase == TermPhase::Typing).await;
    assert_eq!((s.cols, s.rows), PHONE);
    // The next frame to the core is the next key, not anything the lead typed.
    key(&o, 'k');
    let typed = core.frame().await;
    assert_eq!(
        (typed.channel, typed.kind, typed.payload.as_slice()),
        (6, wire::DATA, b"k".as_slice())
    );
}

// Opening from the list at the phone's size (phone check round 2): the first
// attach is active at the phone's size; the desk's own input still takes the
// size back, and the phone then watches.
#[tokio::test(start_paused = true)]
async fn opening_at_the_phones_size_attaches_actively_and_the_desk_can_take_it_back() {
    let mut rig = rig("term-open-phone");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![sized("t3", 80, 24)]).await;
    rig.until_state(WS, connected).await;
    let mut o = open_with(&rig, true);
    let params = core.attach(5, PHONE).await;
    assert_eq!(params["observe"], false);
    assert_eq!(
        (params["cols"].clone(), params["rows"].clone()),
        (json!(53), json!(31))
    );
    let s = o.screen.until_state(|s| s.phase == TermPhase::Typing).await;
    assert_eq!((s.cols, s.rows), PHONE);
    core.send(5, wire::DATA, b"phone paint").await;
    o.screen.until_text("phone paint").await;

    // The desk types: its size comes back and the phone watches.
    core.event(1, "size", "t3", json!({"cols": 120, "rows": 40}))
        .await;
    let close = core.frame().await;
    assert_eq!((close.channel, close.kind), (5, wire::CLOSE));
    let params = core.attach(6, (120, 40)).await;
    assert_eq!(params["observe"], true);
    o.screen
        .until_state(|s| s.phase == TermPhase::Watching)
        .await;
}
