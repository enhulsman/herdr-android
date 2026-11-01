//! The connection hub against scripted machines (task M1.3's Rust half): a
//! fake dialer stands in for SSH and hands each connection's far end to the
//! test, which plays herdr-core on it. Time is tokio's paused clock, so the
//! backoff and the dead-link rule run instantly. The real SSH path is covered
//! by `tests/hub_sshd.rs`.

use super::*;
use crate::link::{DialError, Dialer, Exit, Link, Target};
use herdr_proto::wire;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Mutex;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream, ReadHalf, WriteHalf};
use tokio::sync::{mpsc, oneshot};
use tokio::time::Instant;

const WS: &str = "workstation";
const SRV: &str = "server";
const WS_KEY: &str = "SHA256:workstation-host-key";
const SRV_KEY: &str = "SHA256:server-host-key";
/// Long enough for any backoff step; the paused clock makes it free.
const WAIT: Duration = Duration::from_secs(600);

/// Why a dial fails, for hosts told to fail.
#[derive(Clone, Copy, Debug)]
enum Fail {
    Unreachable,
    NeedsUserAuth,
    Rejected,
    NetworkOff,
}

/// The far end of one connection: the test plays herdr-core on it.
struct Core {
    host: String,
    r: ReadHalf<DuplexStream>,
    w: WriteHalf<DuplexStream>,
    exit: Option<oneshot::Sender<Exit>>,
}

impl Core {
    /// Reads the hello line, prints a login banner, then answers.
    async fn hello_as(&mut self, reply: Value) {
        let mut line = Vec::new();
        loop {
            let b = self.r.read_u8().await.expect("the app sent a hello");
            if b == b'\n' {
                break;
            }
            line.push(b);
        }
        let hello: Value = serde_json::from_slice(&line).unwrap();
        assert_eq!(hello["method"], "hello");
        assert_eq!(hello["params"]["framed"], true);
        assert!(hello["params"].get("core").is_none());
        let reply = json!({"jsonrpc": "2.0", "id": hello["id"], "result": reply});
        let text = format!("Welcome to the workstation\n{reply}\n");
        self.w.write_all(text.as_bytes()).await.unwrap();
    }

    async fn hello(&mut self, version: &str) {
        self.hello_as(json!({
            "generation": 1, "version": version,
            "methods": ["events.subscribe", "terminal.list"],
        }))
        .await;
    }

    async fn request(&mut self) -> Value {
        loop {
            let f = wire::read_frame(&mut self.r)
                .await
                .unwrap()
                .expect("the app closed the connection");
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

    /// Answers `events.subscribe` with these terminals on channel 1.
    async fn subscribed(&mut self, terminals: Vec<Value>) {
        let req = self.request().await;
        assert_eq!(req["method"], "events.subscribe");
        let reply = json!({"jsonrpc": "2.0", "id": req["id"],
                           "result": {"channel": 1, "seq": 0, "terminals": terminals}});
        self.send(0, wire::DATA, reply.to_string().as_bytes()).await;
    }

    async fn connect(&mut self, version: &str, terminals: Vec<Value>) {
        self.hello(version).await;
        self.subscribed(terminals).await;
    }

    async fn event(&mut self, seq: u64, kind: &str, terminal: &str, data: Value) {
        let line = json!({"jsonrpc": "2.0", "method": "event",
                          "params": {"seq": seq, "type": kind, "terminal": terminal, "data": data}});
        self.send(1, wire::DATA, format!("{line}\n").as_bytes())
            .await;
    }

    /// The remote command ends: stdout closes, then its exit status and stderr.
    fn exit(mut self, status: u32, stderr: &str) {
        let exit = self.exit.take().unwrap();
        drop(self);
        let _ = exit.send(Exit {
            status: Some(status),
            stderr: stderr.to_owned(),
        });
    }

    /// Whether the app closed its side within `within`.
    async fn closed_by_app(&mut self, within: Duration) -> bool {
        let mut sink = Vec::new();
        tokio::time::timeout(within, self.r.read_to_end(&mut sink))
            .await
            .is_ok()
    }
}

#[derive(Default)]
struct Script {
    /// Hosts whose dials fail, and how.
    fail: HashMap<String, Fail>,
    /// The host key each host presents.
    keys: HashMap<String, String>,
    /// Every dial, by host, in order.
    dials: Vec<(String, Instant)>,
}

struct FakeDialer {
    script: Mutex<Script>,
    cores: mpsc::UnboundedSender<Core>,
}

impl FakeDialer {
    fn fail(&self, host: &str, how: Option<Fail>) {
        let mut s = self.script.lock().unwrap();
        match how {
            Some(how) => s.fail.insert(host.to_owned(), how),
            None => s.fail.remove(host),
        };
    }

    fn present(&self, host: &str, key: &str) {
        let mut s = self.script.lock().unwrap();
        s.keys.insert(host.to_owned(), key.to_owned());
    }

    fn dials(&self, host: &str) -> Vec<Instant> {
        let s = self.script.lock().unwrap();
        s.dials
            .iter()
            .filter(|(h, _)| h == host)
            .map(|(_, at)| *at)
            .collect()
    }
}

impl Dialer for FakeDialer {
    fn dial(
        &self,
        target: Target,
    ) -> Pin<Box<dyn Future<Output = Result<Link, DialError>> + Send>> {
        let mut s = self.script.lock().unwrap();
        s.dials.push((target.host.clone(), Instant::now()));
        let presented = s.keys.get(&target.host).cloned().unwrap_or_default();
        let result = match (s.fail.get(&target.host), &target.host_key) {
            (Some(Fail::Unreachable), _) => Err(DialError::Failed("connection refused".into())),
            (Some(Fail::NetworkOff), _) => Err(DialError::NetworkOff),
            // The host key is checked before anything is signed, as russh does.
            (_, None) => Err(DialError::HostKeyUnconfirmed { presented }),
            (_, Some(k)) if *k != presented => Err(DialError::HostKeyMismatch { presented }),
            (Some(Fail::NeedsUserAuth), _) => Err(DialError::NeedsUserAuth),
            (Some(Fail::Rejected), _) => Err(DialError::Rejected),
            (None, _) => {
                let (app, core) = tokio::io::duplex(1 << 20);
                let (app_r, app_w) = tokio::io::split(app);
                let (r, w) = tokio::io::split(core);
                let (exit_tx, exit_rx) = oneshot::channel();
                let _ = self.cores.send(Core {
                    host: target.host.clone(),
                    r,
                    w,
                    exit: Some(exit_tx),
                });
                Ok(Link {
                    reader: Box::new(app_r),
                    writer: Box::new(app_w),
                    exit: exit_rx,
                    session: Box::new(()),
                })
            }
        };
        Box::pin(async move { result })
    }
}

struct Rig {
    hub: Arc<HubCore>,
    dialer: Arc<FakeDialer>,
    cores: mpsc::UnboundedReceiver<Core>,
    snapshots: tokio::sync::watch::Receiver<HubSnapshot>,
    dir: PathBuf,
}

impl Drop for Rig {
    fn drop(&mut self) {
        self.hub.shutdown();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("hhub-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir(&dir).unwrap();
    dir
}

fn rig(name: &str) -> Rig {
    rig_in(scratch(name))
}

fn rig_in(dir: PathBuf) -> Rig {
    let (tx, cores) = mpsc::unbounded_channel();
    let dialer = Arc::new(FakeDialer {
        script: Mutex::new(Script::default()),
        cores: tx,
    });
    dialer.present(WS, WS_KEY);
    dialer.present(SRV, SRV_KEY);
    let hub = HubCore::start(dir.clone(), dialer.clone());
    let snapshots = hub.subscribe();
    Rig {
        hub,
        dialer,
        cores,
        snapshots,
        dir,
    }
}

impl Rig {
    /// A machine whose host key is already confirmed. Its host is its name.
    fn add_trusted(&self, name: &str, key: &str) {
        self.hub.add_machine(name, name, 22, "dev").unwrap();
        self.hub.confirm_host_key(name, key).unwrap();
    }

    async fn core(&mut self) -> Core {
        tokio::time::timeout(WAIT, self.cores.recv())
            .await
            .expect("a connection within the wait")
            .unwrap()
    }

    async fn until(&mut self, what: &str, ok: impl Fn(&HubSnapshot) -> bool) -> HubSnapshot {
        let got = tokio::time::timeout(WAIT, self.snapshots.wait_for(|s| ok(s))).await;
        match got {
            Ok(Ok(s)) => s.clone(),
            _ => panic!("never {what}; last: {:#?}", self.hub.snapshot()),
        }
    }

    async fn until_state(
        &mut self,
        machine: &str,
        ok: impl Fn(&MachineState) -> bool,
    ) -> HubSnapshot {
        let what = format!("the wanted state on {machine}");
        self.until(&what, |s| state(s, machine).is_some_and(&ok))
            .await
    }
}

fn state<'a>(s: &'a HubSnapshot, machine: &str) -> Option<&'a MachineState> {
    s.machines
        .iter()
        .find(|m| m.name == machine)
        .map(|m| &m.state)
}

fn view<'a>(s: &'a HubSnapshot, machine: &str) -> &'a MachineView {
    s.machines.iter().find(|m| m.name == machine).unwrap()
}

fn connected(s: &MachineState) -> bool {
    matches!(s, MachineState::Connected { .. })
}

fn agent(address: &str, word: &str, turn: u64) -> Value {
    json!({
        "address": address,
        "state": "live",
        "cwd": "/home/dev/src/website",
        "argv": ["claude"],
        "labels": {},
        "name": null,
        "parent": null,
        "reported": true,
        "status": {"state": word, "app": "claude"},
        "status_ms": 1_000,
        "turn": turn,
    })
}

fn rows_of(s: &HubSnapshot, machine: &str) -> Vec<(String, AgentAttention)> {
    s.agents
        .iter()
        .filter(|r| r.machine == machine)
        .map(|r| (r.address.clone(), r.attention))
        .collect()
}

#[tokio::test(start_paused = true)]
async fn nothing_connects_until_the_app_resumes() {
    let mut rig = rig("paused");
    rig.add_trusted(WS, WS_KEY);
    tokio::time::sleep(Duration::from_secs(120)).await;
    assert!(rig.dialer.dials(WS).is_empty());
    assert_eq!(state(&rig.hub.snapshot(), WS), Some(&MachineState::Paused));

    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![agent("t1", "working", 0)])
        .await;
    let s = rig.until_state(WS, connected).await;
    assert_eq!(rows_of(&s, WS), [("t1".into(), AgentAttention::Working)]);
    assert!(view(&s, WS).last_connected_ms.is_some());
}

// Scenario "Host key changed", and the first connection's confirmation.
#[tokio::test(start_paused = true)]
async fn a_new_machine_connects_only_after_its_host_key_is_confirmed() {
    let mut rig = rig("confirm");
    rig.hub.add_machine(WS, WS, 22, "dev").unwrap();
    rig.hub.resume();
    let s = rig
        .until_state(WS, |st| {
            matches!(st, MachineState::HostKeyUnconfirmed { .. })
        })
        .await;
    assert_eq!(
        state(&s, WS),
        Some(&MachineState::HostKeyUnconfirmed {
            presented: WS_KEY.into()
        })
    );
    // It waits for the person: no retries on its own.
    tokio::time::sleep(Duration::from_secs(120)).await;
    assert_eq!(rig.dialer.dials(WS).len(), 1);

    rig.hub.confirm_host_key(WS, WS_KEY).unwrap();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![]).await;
    let s = rig.until_state(WS, connected).await;
    assert_eq!(view(&s, WS).host_key.as_deref(), Some(WS_KEY));
}

#[tokio::test(start_paused = true)]
async fn a_changed_host_key_shows_both_and_only_forgetting_moves_on() {
    let mut rig = rig("mismatch");
    rig.add_trusted(WS, WS_KEY);
    rig.dialer.present(WS, "SHA256:someone-else");
    rig.hub.resume();
    let s = rig
        .until_state(WS, |st| matches!(st, MachineState::HostKeyMismatch { .. }))
        .await;
    assert_eq!(
        state(&s, WS),
        Some(&MachineState::HostKeyMismatch {
            confirmed: WS_KEY.into(),
            presented: "SHA256:someone-else".into(),
        })
    );
    tokio::time::sleep(Duration::from_secs(120)).await;
    assert_eq!(
        rig.dialer.dials(WS).len(),
        1,
        "no retries against a changed key"
    );
    // Retrying does not get past it either.
    rig.hub.retry();
    rig.until_state(WS, |st| matches!(st, MachineState::HostKeyMismatch { .. }))
        .await;

    rig.hub.forget_host_key(WS).unwrap();
    let s = rig
        .until_state(WS, |st| {
            matches!(st, MachineState::HostKeyUnconfirmed { .. })
        })
        .await;
    assert_eq!(view(&s, WS).host_key, None);
}

#[tokio::test(start_paused = true)]
async fn needing_the_fingerprint_waits_for_the_prompt_then_retry() {
    let mut rig = rig("auth");
    rig.add_trusted(WS, WS_KEY);
    rig.dialer.fail(WS, Some(Fail::NeedsUserAuth));
    rig.hub.resume();
    rig.until_state(WS, |st| *st == MachineState::NeedsUserAuth)
        .await;
    tokio::time::sleep(Duration::from_secs(120)).await;
    assert_eq!(
        rig.dialer.dials(WS).len(),
        1,
        "Rust never retries into a prompt"
    );

    // The app prompted; the key works again.
    rig.dialer.fail(WS, None);
    rig.hub.retry();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![]).await;
    rig.until_state(WS, connected).await;
}

#[tokio::test(start_paused = true)]
async fn failures_retry_after_1_s_backing_off_to_30_s() {
    let mut rig = rig("backoff");
    rig.add_trusted(WS, WS_KEY);
    rig.dialer.fail(WS, Some(Fail::Unreachable));
    rig.hub.resume();
    let s = rig
        .until_state(WS, |st| matches!(st, MachineState::Unreachable { .. }))
        .await;
    assert_eq!(view(&s, WS).last_connected_ms, None, "never connected");
    // Half a second past the seventh retry, so the test never races it.
    tokio::time::sleep(Duration::from_millis(
        (1 + 2 + 4 + 8 + 16 + 30 + 30) * 1000 + 500,
    ))
    .await;
    let dials = rig.dialer.dials(WS);
    let gaps: Vec<u64> = dials.windows(2).map(|w| (w[1] - w[0]).as_secs()).collect();
    assert_eq!(gaps, [1, 2, 4, 8, 16, 30, 30]);
}

// Scenario "herdr-core missing".
#[tokio::test(start_paused = true)]
async fn a_machine_without_herdr_core_says_so_and_keeps_retrying() {
    let mut rig = rig("missing");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    rig.core()
        .await
        .exit(127, "herdr-core is not installed here\n");
    rig.until_state(WS, |st| *st == MachineState::NotInstalled)
        .await;
    // Retried after a second, like any failure.
    let mut again = rig.core().await;
    again.connect("0.1.15", vec![]).await;
    rig.until_state(WS, connected).await;
}

#[tokio::test(start_paused = true)]
async fn a_command_that_ends_otherwise_is_unreachable_with_its_stderr() {
    let mut rig = rig("exit1");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    rig.core().await.exit(1, "some error\nsocket refused\n");
    let s = rig
        .until_state(WS, |st| matches!(st, MachineState::Unreachable { .. }))
        .await;
    let Some(MachineState::Unreachable { detail }) = state(&s, WS) else {
        unreachable!()
    };
    assert!(detail.contains("socket refused"), "{detail}");
}

// Scenario "Older core".
#[tokio::test(start_paused = true)]
async fn an_older_core_stays_disconnected_and_names_its_version() {
    let mut rig = rig("old");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.hello("0.1.14").await;
    let s = rig
        .until_state(WS, |st| matches!(st, MachineState::TooOld { .. }))
        .await;
    assert_eq!(
        state(&s, WS),
        Some(&MachineState::TooOld {
            have: "0.1.14".into(),
            need: herdr_proto::compat::MIN_CORE.into(),
        })
    );
    assert!(core.closed_by_app(Duration::from_secs(5)).await);
    tokio::time::sleep(Duration::from_secs(120)).await;
    assert_eq!(rig.dialer.dials(WS).len(), 1);
}

#[tokio::test(start_paused = true)]
async fn a_core_lacking_a_method_counts_as_too_old() {
    let mut rig = rig("lacking");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    rig.core()
        .await
        .hello_as(json!({"generation": 1, "version": "0.1.15", "methods": ["terminal.list"]}))
        .await;
    rig.until_state(
        WS,
        |st| matches!(st, MachineState::TooOld { have, .. } if have == "0.1.15"),
    )
    .await;
}

#[tokio::test(start_paused = true)]
async fn a_newer_protocol_generation_asks_for_an_app_update() {
    let mut rig = rig("generation");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    rig.core()
        .await
        .hello_as(json!({"generation": 2, "version": "1.0.0", "methods": []}))
        .await;
    rig.until_state(WS, |st| *st == MachineState::AppTooOld)
        .await;
}

// Scenario "Newer core".
#[tokio::test(start_paused = true)]
async fn a_newer_core_connects_with_the_untested_notice() {
    let mut rig = rig("newer");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("9.0.0", vec![]).await;
    let s = rig.until_state(WS, connected).await;
    assert_eq!(
        state(&s, WS),
        Some(&MachineState::Connected {
            version: "9.0.0".into(),
            untested: true,
        })
    );
}

#[tokio::test(start_paused = true)]
async fn events_move_rows_and_needing_you_comes_first() {
    let mut rig = rig("events");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect(
        "0.1.15",
        vec![agent("t1", "working", 0), agent("t2", "working", 0)],
    )
    .await;
    rig.until_state(WS, connected).await;

    core.event(1, "status", "t2", json!({
        "status": {"state": "blocked", "kind": "permission", "msg": "Run npm test?", "app": "claude"},
        "records": [], "reported": true,
    }))
    .await;
    let s = rig
        .until("t2 needs you", |s| {
            s.agents.first().is_some_and(|r| r.address == "t2")
        })
        .await;
    let top = &s.agents[0];
    assert_eq!(top.attention, AgentAttention::NeedsYou);
    assert_eq!(top.label, "Permission: Run npm test?");
    assert_eq!(top.name, "Claude Code");
    assert_eq!(top.folder, "website");
}

#[tokio::test(start_paused = true)]
async fn terminals_that_never_reported_are_other_terminals() {
    let mut rig = rig("others");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut shell = agent("t3", "idle", 0);
    shell["reported"] = json!(false);
    shell["status"] = Value::Null;
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![shell]).await;
    let s = rig.until_state(WS, connected).await;
    assert!(s.agents.is_empty());
    assert_eq!(s.others.len(), 1);
    assert_eq!(s.others[0].address, "t3");
    assert_eq!(s.others[0].machine, WS);
}

// Scenario "A second machine is off".
#[tokio::test(start_paused = true)]
async fn a_machine_that_drops_keeps_its_rows_as_of_its_last_connection() {
    let mut rig = rig("second");
    rig.add_trusted(WS, WS_KEY);
    rig.add_trusted(SRV, SRV_KEY);
    rig.hub.resume();
    let mut a = rig.core().await;
    let mut b = rig.core().await;
    if a.host != WS {
        std::mem::swap(&mut a, &mut b);
    }
    a.connect("0.1.15", vec![agent("t1", "working", 0)]).await;
    b.connect("0.1.15", vec![agent("t9", "blocked", 0)]).await;
    rig.until("both connected", |s| {
        state(s, WS).is_some_and(connected) && state(s, SRV).is_some_and(connected)
    })
    .await;

    // The server goes off: its connection ends and it cannot be reached again.
    rig.dialer.fail(SRV, Some(Fail::Unreachable));
    drop(b);
    let s = rig
        .until_state(SRV, |st| matches!(st, MachineState::Unreachable { .. }))
        .await;
    assert!(view(&s, SRV).last_connected_ms.is_some());
    assert!(state(&s, WS).is_some_and(connected));
    assert_eq!(rows_of(&s, WS), [("t1".into(), AgentAttention::Working)]);
    // Its last rows stay, for "as of hh:mm".
    assert_eq!(rows_of(&s, SRV), [("t9".into(), AgentAttention::NeedsYou)]);
}

// Scenario "Silent link", and recovery: resubscribe and fresh rows.
#[tokio::test(start_paused = true)]
async fn a_silent_link_reconnects_within_45_s_and_resubscribes() {
    let mut rig = rig("silent");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut first = rig.core().await;
    first
        .connect("0.1.15", vec![agent("t1", "working", 0)])
        .await;
    rig.until_state(WS, connected).await;
    let silent_from = Instant::now();

    // The path drops without closing: nothing arrives any more.
    let mut second = rig.core().await;
    assert!(Instant::now() - silent_from <= Duration::from_secs(45 + 1));
    second
        .connect("0.1.15", vec![agent("t1", "blocked", 0)])
        .await;
    let s = rig
        .until("the fresh snapshot", |s| {
            rows_of(s, WS) == [("t1".into(), AgentAttention::NeedsYou)]
        })
        .await;
    assert!(state(&s, WS).is_some_and(connected));
    drop(first);
}

#[tokio::test(start_paused = true)]
async fn a_lasting_connection_resets_the_backoff() {
    let mut rig = rig("reset");
    rig.add_trusted(WS, WS_KEY);
    rig.dialer.fail(WS, Some(Fail::Unreachable));
    rig.hub.resume();
    // Fail long enough to reach the 30 s step.
    tokio::time::sleep(Duration::from_secs(1 + 2 + 4 + 8 + 16 + 1)).await;
    rig.dialer.fail(WS, None);
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![]).await;
    rig.until_state(WS, connected).await;
    // Heartbeats keep it alive for a minute, then it drops.
    for seq in 0..4 {
        tokio::time::sleep(Duration::from_secs(15)).await;
        let beat = json!({"jsonrpc": "2.0", "method": "heartbeat", "params": {"seq": seq}});
        core.send(1, wire::DATA, format!("{beat}\n").as_bytes())
            .await;
    }
    let dropped = Instant::now();
    drop(core);
    rig.core().await;
    assert_eq!((Instant::now() - dropped).as_secs(), 1);
}

// Scenario "Pocketed phone".
#[tokio::test(start_paused = true)]
async fn suspending_closes_every_connection_until_resume() {
    let mut rig = rig("suspend");
    rig.add_trusted(WS, WS_KEY);
    rig.add_trusted(SRV, SRV_KEY);
    rig.hub.resume();
    let mut a = rig.core().await;
    let mut b = rig.core().await;
    if a.host != WS {
        std::mem::swap(&mut a, &mut b);
    }
    a.connect("0.1.15", vec![agent("t1", "working", 0)]).await;
    b.connect("0.1.15", vec![]).await;
    rig.until("both connected", |s| {
        state(s, WS).is_some_and(connected) && state(s, SRV).is_some_and(connected)
    })
    .await;

    rig.hub.suspend();
    assert!(
        a.closed_by_app(Duration::from_secs(2)).await,
        "workstation still open"
    );
    assert!(
        b.closed_by_app(Duration::from_secs(2)).await,
        "server still open"
    );
    let s = rig.hub.snapshot();
    assert_eq!(state(&s, WS), Some(&MachineState::Paused));
    assert!(view(&s, WS).last_connected_ms.is_some());
    // The rows stay, as of the last connection.
    assert_eq!(rows_of(&s, WS), [("t1".into(), AgentAttention::Working)]);
    let before = rig.dialer.dials(WS).len();
    tokio::time::sleep(Duration::from_secs(300)).await;
    assert_eq!(rig.dialer.dials(WS).len(), before);

    rig.hub.resume();
    let mut c1 = rig.core().await;
    c1.connect("0.1.15", vec![]).await;
    let mut c2 = rig.core().await;
    c2.connect("0.1.15", vec![]).await;
    rig.until("both back", |s| {
        state(s, WS).is_some_and(connected) && state(s, SRV).is_some_and(connected)
    })
    .await;
}

#[tokio::test(start_paused = true)]
async fn network_off_and_rejected_keys_are_named_and_retried() {
    let mut rig = rig("named");
    rig.add_trusted(WS, WS_KEY);
    rig.add_trusted(SRV, SRV_KEY);
    rig.dialer.fail(WS, Some(Fail::NetworkOff));
    rig.dialer.fail(SRV, Some(Fail::Rejected));
    rig.hub.resume();
    rig.until("both named", |s| {
        state(s, WS) == Some(&MachineState::NetworkOff)
            && state(s, SRV) == Some(&MachineState::KeyRejected)
    })
    .await;
    // The person grants Network, or installs the line: it connects by itself.
    rig.dialer.fail(WS, None);
    rig.dialer.fail(SRV, None);
    let mut c1 = rig.core().await;
    c1.connect("0.1.15", vec![]).await;
    let mut c2 = rig.core().await;
    c2.connect("0.1.15", vec![]).await;
    rig.until("both connected", |s| {
        state(s, WS).is_some_and(connected) && state(s, SRV).is_some_and(connected)
    })
    .await;
}

#[tokio::test(start_paused = true)]
async fn removing_a_machine_closes_it_and_drops_its_rows() {
    let mut rig = rig("remove");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![agent("t1", "working", 0)])
        .await;
    rig.until_state(WS, connected).await;

    rig.hub.remove_machine(WS).unwrap();
    assert!(core.closed_by_app(Duration::from_secs(2)).await);
    let s = rig.until("no machines", |s| s.machines.is_empty()).await;
    assert!(s.agents.is_empty());
    assert!(matches!(
        rig.hub.remove_machine(WS),
        Err(HubError::NotFound { .. })
    ));
}

#[tokio::test(start_paused = true)]
async fn moving_a_machine_reconnects_it_and_asks_for_the_new_host_key() {
    let mut rig = rig("move");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![]).await;
    rig.until_state(WS, connected).await;
    rig.dialer.present("new.example.org", "SHA256:new");
    rig.hub
        .update_machine(WS, "new.example.org", 22, "dev")
        .unwrap();
    assert!(core.closed_by_app(Duration::from_secs(2)).await);
    rig.until_state(WS, |st| {
        *st == MachineState::HostKeyUnconfirmed {
            presented: "SHA256:new".into(),
        }
    })
    .await;
}

// What survives an app restart: the list, host keys, last connected, and
// which finishes are still unseen (M1 plan, pick 2b).
#[tokio::test(start_paused = true)]
async fn a_restarted_hub_remembers_machines_and_unseen_finishes() {
    let dir = scratch("restart");
    {
        let mut rig = rig_in(dir.clone());
        rig.add_trusted(WS, WS_KEY);
        rig.hub.resume();
        let mut core = rig.core().await;
        core.connect("0.1.15", vec![agent("t1", "working", 0)])
            .await;
        rig.until_state(WS, connected).await;
        core.event(
            1,
            "status",
            "t1",
            json!({
                "status": {"state": "idle", "app": "claude"}, "records": [], "reported": true,
            }),
        )
        .await;
        core.event(2, "turn", "t1", json!({"turn": 1})).await;
        rig.until("t1 done", |s| {
            rows_of(s, WS) == [("t1".into(), AgentAttention::Done)]
        })
        .await;
        rig.hub.suspend();
        // Keep the directory for the second hub.
        rig.dir = scratch("restart-unused");
    }

    let mut rig = rig_in(dir);
    let s = rig.hub.snapshot();
    let ws = view(&s, WS);
    assert_eq!(ws.host_key.as_deref(), Some(WS_KEY));
    assert!(ws.last_connected_ms.is_some());
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![agent("t1", "idle", 1)]).await;
    let s = rig.until_state(WS, connected).await;
    assert_eq!(rows_of(&s, WS), [("t1".into(), AgentAttention::Done)]);

    // Opening it marks it seen.
    rig.hub.mark_seen(WS, "t1");
    rig.until("t1 seen", |s| {
        rows_of(s, WS) == [("t1".into(), AgentAttention::Idle)]
    })
    .await;
}

#[tokio::test(start_paused = true)]
async fn a_broken_list_file_is_kept_aside_and_said() {
    let dir = scratch("broken");
    std::fs::write(dir.join("machines.json"), "{ not json").unwrap();
    let rig = rig_in(dir.clone());
    let s = rig.hub.snapshot();
    assert!(s.machines.is_empty());
    assert!(
        s.notice
            .as_deref()
            .is_some_and(|n| n.contains("machine list")),
        "{:?}",
        s.notice
    );
    assert_eq!(
        std::fs::read_to_string(dir.join("machines.json.broken")).unwrap(),
        "{ not json"
    );
}

// Scenario "Restoring the list on a new install", through the hub.
#[tokio::test(start_paused = true)]
async fn imported_machines_ask_for_setup() {
    let old = rig("export");
    old.add_trusted(WS, WS_KEY);
    old.add_trusted(SRV, SRV_KEY);
    let export = old.hub.export_machines();
    drop(old);

    let mut rig = rig("import");
    assert_eq!(rig.hub.import_machines(&export).unwrap(), 2);
    rig.hub.resume();
    let s = rig
        .until("both ask for setup", |s| {
            s.machines.len() == 2
                && s.machines
                    .iter()
                    .all(|m| matches!(m.state, MachineState::HostKeyUnconfirmed { .. }))
        })
        .await;
    assert!(s.machines.iter().all(|m| m.host_key.is_none()));
    assert!(matches!(
        rig.hub.import_machines("nonsense"),
        Err(HubError::Unreadable { .. })
    ));
}

// Review findings (Codex, M1 step 4): stale task writes, and list changes the
// file did not take.

#[tokio::test(start_paused = true)]
async fn a_stopped_task_cannot_write_its_state() {
    let mut rig = rig("stale");
    rig.add_trusted(WS, WS_KEY);
    rig.hub.resume();
    let mut core = rig.core().await;
    core.connect("0.1.15", vec![]).await;
    rig.until_state(WS, connected).await;
    let old = rig.hub.state.lock().unwrap().live[WS].epoch;

    // Aborting takes effect at the task's next await; a task that is already
    // past it and waiting for the lock must not win after suspend.
    rig.hub.suspend();
    let late = MachineState::Connected {
        version: "0.1.15".into(),
        untested: false,
    };
    assert!(!rig.hub.set(WS, old, late));
    assert!(!rig.hub.observe(WS, old, &EventLines::default(), None));
    assert_eq!(state(&rig.hub.snapshot(), WS), Some(&MachineState::Paused));

    rig.hub.resume();
    let mut again = rig.core().await;
    again.connect("0.1.15", vec![]).await;
    rig.until_state(WS, connected).await;
}

/// Makes `dir` read-only for the test's lifetime; None (with a note) when the
/// user can write it anyway (root).
struct ReadOnly(PathBuf);

impl ReadOnly {
    fn new(dir: &std::path::Path) -> Option<ReadOnly> {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o500)).unwrap();
        let guard = ReadOnly(dir.to_owned());
        if std::fs::File::create(dir.join("probe")).is_ok() {
            eprintln!(
                "skipped: {} stays writable (running as root?)",
                dir.display()
            );
            return None;
        }
        Some(guard)
    }
}

impl Drop for ReadOnly {
    fn drop(&mut self) {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&self.0, std::fs::Permissions::from_mode(0o700));
    }
}

#[tokio::test(start_paused = true)]
async fn a_change_the_list_file_did_not_take_did_not_happen() {
    let dir = scratch("unsaved");
    {
        let mut rig = rig_in(dir.clone());
        rig.add_trusted(WS, WS_KEY);
        let Some(_ro) = ReadOnly::new(&dir) else {
            return;
        };
        assert!(matches!(
            rig.hub.remove_machine(WS),
            Err(HubError::Storage { .. })
        ));
        assert!(matches!(
            rig.hub.forget_host_key(WS),
            Err(HubError::Storage { .. })
        ));
        assert!(matches!(
            rig.hub.add_machine(SRV, SRV, 22, "dev"),
            Err(HubError::Storage { .. })
        ));
        let s = rig.hub.snapshot();
        assert_eq!(s.machines.len(), 1);
        assert_eq!(view(&s, WS).host_key.as_deref(), Some(WS_KEY));
        rig.dir = scratch("unsaved-unused");
    }
    // After a restart, the file agrees with what the app said.
    let rig = rig_in(dir);
    let s = rig.hub.snapshot();
    assert_eq!(s.machines.len(), 1);
    assert_eq!(view(&s, WS).host_key.as_deref(), Some(WS_KEY));
}

#[tokio::test(start_paused = true)]
async fn a_broken_list_that_cannot_be_set_aside_is_never_overwritten() {
    let dir = scratch("frozen");
    std::fs::write(dir.join("machines.json"), "{ not json").unwrap();
    let Some(ro) = ReadOnly::new(&dir) else {
        return;
    };
    let rig = rig_in(dir.clone());
    drop(ro);
    let s = rig.hub.snapshot();
    assert!(
        s.notice
            .as_deref()
            .is_some_and(|n| n.contains("nor set it aside")),
        "{:?}",
        s.notice
    );
    // Writable again, but the list stays frozen for this run.
    assert!(matches!(
        rig.hub.add_machine(WS, WS, 22, "dev"),
        Err(HubError::Storage { .. })
    ));
    assert!(matches!(
        rig.hub
            .import_machines(&rig_in(scratch("frozen-src")).hub.export_machines()),
        Err(HubError::Storage { .. })
    ));
    assert_eq!(
        std::fs::read_to_string(dir.join("machines.json")).unwrap(),
        "{ not json"
    );
}

#[tokio::test(start_paused = true)]
async fn each_broken_list_is_kept_under_its_own_name() {
    let dir = scratch("twice");
    std::fs::write(dir.join("machines.json"), "first").unwrap();
    {
        let mut first = rig_in(dir.clone());
        // Keep the directory for the second hub.
        first.dir = scratch("twice-unused");
    }
    std::fs::write(dir.join("machines.json"), "second").unwrap();
    let rig = rig_in(dir.clone());
    assert!(
        rig.hub
            .snapshot()
            .notice
            .as_deref()
            .is_some_and(|n| n.contains("machines.json.broken-2"))
    );
    let read = |name: &str| std::fs::read_to_string(dir.join(name)).unwrap();
    assert_eq!(read("machines.json.broken"), "first");
    assert_eq!(read("machines.json.broken-2"), "second");
}
