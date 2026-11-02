//! The connection hub: one task per machine keeps a connection to its
//! herdr-core, follows its event stream, and folds every machine's terminals
//! into one attention list, published as whole snapshots
//! (machine-connection spec: "Machines are an app-local list", "Connections
//! recover on their own", "Connections end in the background"; design.md §2).
//!
//! A task dials, checks the host key (before anything is signed), sends the
//! hello, checks the core's version, subscribes, and then applies events until
//! the connection ends. Failures the app can wait out (unreachable, Network
//! off, herdr-core missing, the key not installed yet) retry after 1 s backing
//! off to 30 s; ones that need the person (a fingerprint, a host key to
//! confirm or forget, a core or app to update) wait for [`HubCore::retry`],
//! [`HubCore::resume`] or the matching call. The hub starts suspended: the
//! app resumes it in the foreground and suspends it 60 s after leaving.

use std::collections::HashMap;
use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use herdr_proto::attention::{self, Tracker};
use herdr_proto::compat::{self, Compat};
use herdr_proto::events::EventLines;
use herdr_proto::handshake::{self, HandshakeError};
use herdr_proto::rpc::{Client, Ended, StreamItem};
use serde_json::{Value, json};
use tokio::io::AsyncReadExt;
use tokio::sync::watch;
use tokio::task::AbortHandle;
use tokio::time::Instant;

use crate::link::{DialError, Dialer, Exit, Target};
use crate::machines::{self, ListError, MachineList};

#[cfg(test)]
mod tests;

const LIST_FILE: &str = "machines.json";
const SEEN_FILE: &str = "seen.json";
/// Retry delays after a failure; the last one repeats.
const BACKOFF: [u64; 6] = [1, 2, 4, 8, 16, 30];
/// A connection that lasted this long starts the backoff over.
const STABLE: Duration = Duration::from_secs(10);
/// How long a closed command gets to report its exit status.
const EXIT_WAIT: Duration = Duration::from_secs(2);
const NOT_INSTALLED: &str = "herdr-core is not installed here";

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum MachineState {
    /// The app is in the background (or not resumed yet): no connection.
    Paused,
    Connecting,
    /// `untested`: the core is newer than the newest version the tests ran against.
    Connected {
        version: String,
        untested: bool,
    },
    /// Not reached, or the connection dropped; retrying.
    Unreachable {
        detail: String,
    },
    /// The key needs a fingerprint or device credential; prompt, then retry.
    NeedsUserAuth,
    /// First connection: confirm this host key to continue. `algorithm` is
    /// the presented key's type as `ssh-keygen -l` names it ("ED25519").
    HostKeyUnconfirmed {
        presented: String,
        algorithm: String,
    },
    /// The machine presented a different host key than the confirmed one;
    /// `algorithm` is the presented key's type (the confirmed one's is not kept).
    HostKeyMismatch {
        confirmed: String,
        presented: String,
        algorithm: String,
    },
    /// The machine does not accept the phone key yet; retrying.
    KeyRejected,
    /// "herdr-core not found on this machine"; retrying.
    NotInstalled,
    /// herdr-core `have` is older than this app supports (`need`).
    TooOld {
        have: String,
        need: String,
    },
    /// The core speaks a newer protocol generation: the app needs an update.
    AppTooOld,
    /// GrapheneOS's Network permission is off. It changes only in Android
    /// settings, so no timed retries: tried again on the return to the app.
    NetworkOff,
}

impl MachineState {
    /// A connection is up or being made.
    fn active(&self) -> bool {
        matches!(
            self,
            MachineState::Connecting | MachineState::Connected { .. }
        )
    }

    /// Waiting out a backoff: the hub tries again by itself.
    fn retrying(&self) -> bool {
        matches!(
            self,
            MachineState::Unreachable { .. }
                | MachineState::KeyRejected
                | MachineState::NotInstalled
        )
    }
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct MachineView {
    pub name: String,
    pub host: String,
    pub port: u16,
    pub user: String,
    pub host_key: Option<String>,
    pub state: MachineState,
    /// The last moment the app was connected, ms since the epoch: "unreachable
    /// · last connected ...", and "as of hh:mm" on its rows while not connected.
    pub last_connected_ms: Option<u64>,
    /// When the next attempt starts, ms since the epoch, while the state
    /// retries by itself (unreachable, key not accepted, herdr-core missing):
    /// "retrying in 30 s". None while connecting, connected, paused, or
    /// waiting for the person (Network off too), and while a retry runs (the state
    /// then keeps the failure: "retrying").
    #[uniffi(default = None)]
    pub next_retry_ms: Option<u64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum AgentAttention {
    NeedsYou,
    Error,
    Done,
    Working,
    Idle,
}

impl From<attention::Attention> for AgentAttention {
    fn from(a: attention::Attention) -> Self {
        match a {
            attention::Attention::NeedsYou => AgentAttention::NeedsYou,
            attention::Attention::Error => AgentAttention::Error,
            attention::Attention::Done => AgentAttention::Done,
            attention::Attention::Working => AgentAttention::Working,
            attention::Attention::Idle => AgentAttention::Idle,
        }
    }
}

/// One agent on the home list, in list order.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AgentRow {
    pub machine: String,
    pub address: String,
    pub name: String,
    pub attention: AgentAttention,
    pub label: String,
    pub progress: Option<u8>,
    pub since_ms: Option<u64>,
    pub folder: String,
    /// For the icon: "claude", "codex", ...; none when unknown.
    pub program: Option<String>,
    /// Needs you or error: the card's heading ("Permission", "Needs you", "Error").
    pub request_kind: Option<String>,
    /// Needs you or error: what the program asks, if it said.
    pub request: Option<String>,
}

impl From<attention::Row> for AgentRow {
    fn from(r: attention::Row) -> Self {
        AgentRow {
            machine: r.machine,
            address: r.address,
            name: r.name,
            attention: r.attention.into(),
            label: r.label,
            progress: r.progress,
            since_ms: r.since_ms,
            folder: r.folder,
            program: r.program,
            request_kind: r.request_kind,
            request: r.request,
        }
    }
}

/// A terminal that never reported status ("Other terminals").
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct OtherTerminal {
    pub machine: String,
    pub address: String,
    pub name: String,
    pub folder: String,
    pub program: Option<String>,
}

impl From<attention::Other> for OtherTerminal {
    fn from(o: attention::Other) -> Self {
        OtherTerminal {
            machine: o.machine,
            address: o.address,
            name: o.name,
            folder: o.folder,
            program: o.program,
        }
    }
}

/// Everything the app shows, whole.
#[derive(Clone, Debug, Default, PartialEq, uniffi::Record)]
pub struct HubSnapshot {
    pub machines: Vec<MachineView>,
    pub agents: Vec<AgentRow>,
    pub others: Vec<OtherTerminal>,
    /// A storage problem to tell the person about.
    pub notice: Option<String>,
}

#[derive(Debug, PartialEq, Eq, uniffi::Error)]
pub enum HubError {
    Invalid {
        detail: String,
    },
    Duplicate {
        name: String,
    },
    NotFound {
        name: String,
    },
    Unreadable {
        detail: String,
    },
    /// The machine list could not be saved; nothing changed.
    Storage {
        detail: String,
    },
}

impl std::fmt::Display for HubError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HubError::Invalid { detail } => f.write_str(detail),
            HubError::Duplicate { name } => {
                write!(f, "a machine named \"{name}\" is already listed")
            }
            HubError::NotFound { name } => write!(f, "no machine named \"{name}\""),
            HubError::Unreadable { detail } => write!(f, "not a machine list: {detail}"),
            HubError::Storage { detail } => f.write_str(detail),
        }
    }
}

impl std::error::Error for HubError {}

impl From<ListError> for HubError {
    fn from(e: ListError) -> Self {
        match e {
            ListError::Invalid(detail) => HubError::Invalid { detail },
            ListError::Duplicate(name) => HubError::Duplicate { name },
            ListError::NotFound(name) => HubError::NotFound { name },
            ListError::Unreadable(detail) => HubError::Unreadable { detail },
        }
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as u64)
}

struct Live {
    state: MachineState,
    /// Which task may write this machine's state; 0 when none may. A stopped
    /// or replaced task's late writes are ignored: aborting only takes effect
    /// at the task's next await.
    epoch: u64,
    task: Option<AbortHandle>,
    /// When the task's backoff ends, ms since the epoch; only while waiting.
    next_retry_ms: Option<u64>,
}

impl Live {
    fn paused() -> Live {
        Live {
            state: MachineState::Paused,
            epoch: 0,
            task: None,
            next_retry_ms: None,
        }
    }

    fn stop(&mut self) {
        self.epoch = 0;
        self.next_retry_ms = None;
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}

struct State {
    list: MachineList,
    live: HashMap<String, Live>,
    tracker: Tracker,
    paused: bool,
    next_epoch: u64,
    saved_memory: String,
    notice: Option<String>,
    /// Why the list file must not be written: an unreadable one could not be
    /// set aside, and writing would destroy it.
    list_frozen: Option<String>,
}

pub(crate) struct HubCore {
    dir: PathBuf,
    dialer: Arc<dyn Dialer>,
    runtime: tokio::runtime::Handle,
    state: Mutex<State>,
    snapshots: watch::Sender<HubSnapshot>,
    /// Each machine's live connection, for terminal views to attach through.
    conns: Mutex<HashMap<String, watch::Sender<Option<Conn>>>>,
}

/// Every terminal's size as the event stream reports it, by address.
pub(crate) type Sizes = Arc<HashMap<String, (u16, u16)>>;

/// A machine's live connection: what an open terminal attaches through.
#[derive(Clone)]
#[cfg_attr(not(feature = "vt"), allow(dead_code))]
pub(crate) struct Conn {
    /// Which connection attempt this is; a reconnect has a new one.
    pub epoch: u64,
    pub client: Client,
    pub sizes: watch::Receiver<Sizes>,
}

fn sizes_of(lines: &EventLines) -> HashMap<String, (u16, u16)> {
    let dim = |t: &Value, k: &str| t[k].as_u64().and_then(|n| u16::try_from(n).ok());
    lines
        .terminals()
        .iter()
        .filter_map(|t| {
            let address = t["address"].as_str()?;
            Some((address.to_owned(), (dim(t, "cols")?, dim(t, "rows")?)))
        })
        .collect()
}

/// Withdraws a connection from terminal views when its attempt ends, however
/// it ends (an aborted task included), and closes it: a view's clone of the
/// client must not keep a suspended connection open.
struct ConnGuard {
    hub: Arc<HubCore>,
    name: String,
    epoch: u64,
    client: Client,
}

impl Drop for ConnGuard {
    fn drop(&mut self) {
        self.client.close();
        let conns = self.hub.conns.lock().unwrap();
        if let Some(tx) = conns.get(&self.name) {
            tx.send_if_modified(|c| {
                if c.as_ref().is_some_and(|c| c.epoch == self.epoch) {
                    *c = None;
                    true
                } else {
                    false
                }
            });
        }
    }
}

/// How one connection attempt ended.
struct Outcome {
    state: MachineState,
    retry: bool,
    /// How long it was connected, if it got that far.
    connected_for: Option<Duration>,
}

impl Outcome {
    fn retry(state: MachineState) -> Outcome {
        Outcome {
            state,
            retry: true,
            connected_for: None,
        }
    }

    fn wait(state: MachineState) -> Outcome {
        Outcome {
            state,
            retry: false,
            connected_for: None,
        }
    }
}

fn unreachable(detail: impl Into<String>) -> MachineState {
    MachineState::Unreachable {
        detail: detail.into(),
    }
}

const LOG_FILE: &str = "hub.log";
/// The log is rotated to `hub.log.1` past this size.
const LOG_MAX: u64 = 64 * 1024;

/// Notes something worth knowing later, but not worth showing, in
/// `hub.log` in app storage. Losing a line is fine.
fn log(dir: &Path, line: &str) {
    use std::io::Write;
    let path = dir.join(LOG_FILE);
    if std::fs::metadata(&path).is_ok_and(|m| m.len() > LOG_MAX) {
        let _ = std::fs::rename(&path, dir.join(format!("{LOG_FILE}.1")));
    }
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
    {
        let _ = writeln!(f, "{} {line}", now_ms());
    }
}

/// What loading the list file gave.
struct Loaded {
    list: MachineList,
    /// Why the list file must not be written, if so.
    frozen: Option<String>,
}

/// Loads the list without asking the person anything. An unreadable file is
/// moved aside, under a name no earlier copy has, and the list the previous
/// save kept takes its place; a missing file (a crash between a save's two
/// renames) is restored the same way. If the broken file cannot be moved, it
/// is never written over. What happened goes to the log.
fn load_list(dir: &Path) -> Loaded {
    let path = dir.join(LIST_FILE);
    let backup = machines::backup_path(&path);
    let mut frozen = None;
    if path.exists() {
        let e = match MachineList::load(&path) {
            Ok(list) => return Loaded { list, frozen },
            Err(e) => e,
        };
        let aside = (1..)
            .map(|n| match n {
                1 => format!("{LIST_FILE}.broken"),
                n => format!("{LIST_FILE}.broken-{n}"),
            })
            .find(|name| !dir.join(name).exists())
            .expect("some name is free");
        match std::fs::rename(&path, dir.join(&aside)) {
            Ok(()) => log(
                dir,
                &format!("unreadable machine list ({e}), kept as {aside}"),
            ),
            Err(io) => {
                log(
                    dir,
                    &format!("unreadable machine list ({e}) could not be set aside ({io})"),
                );
                frozen = Some(
                    "Could not save the machine list: the previous one could not be read."
                        .to_owned(),
                );
            }
        }
    } else if !backup.exists() {
        return Loaded {
            list: MachineList::default(),
            frozen,
        };
    }
    match MachineList::load(&backup) {
        Ok(list) if backup.exists() => {
            log(dir, &format!("machine list restored from {LIST_FILE}.bak"));
            if frozen.is_none()
                && let Err(e) = list.save(&path)
            {
                log(dir, &format!("could not write the restored list back: {e}"));
            }
            Loaded { list, frozen }
        }
        Ok(_) | Err(_) => {
            log(dir, "machine list could not be restored; starting empty");
            Loaded {
                list: MachineList::default(),
                frozen,
            }
        }
    }
}

fn load_seen(dir: &Path) -> Tracker {
    std::fs::read(dir.join(SEEN_FILE))
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .map(|memory| Tracker::restore(&memory))
        .unwrap_or_default()
}

fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let tmp = path.with_extension("tmp");
    let mut f = std::fs::File::create(&tmp)?;
    f.write_all(bytes)?;
    f.sync_all()?;
    drop(f);
    std::fs::rename(&tmp, path)
}

impl HubCore {
    /// Loads the list and seen turns from `dir` and starts suspended. Must be
    /// called inside the tokio runtime the hub's tasks run on.
    pub(crate) fn start(dir: PathBuf, dialer: Arc<dyn Dialer>) -> Arc<HubCore> {
        let Loaded { list, frozen } = load_list(&dir);
        let tracker = load_seen(&dir);
        let saved_memory = tracker.memory().to_string();
        let live = list
            .machines()
            .iter()
            .map(|m| (m.name.clone(), Live::paused()))
            .collect();
        let state = State {
            list,
            live,
            tracker,
            paused: true,
            next_epoch: 1,
            saved_memory,
            notice: None,
            list_frozen: frozen,
        };
        let snapshot = build_snapshot(&state);
        Arc::new(HubCore {
            dir,
            dialer,
            runtime: tokio::runtime::Handle::current(),
            state: Mutex::new(state),
            snapshots: watch::channel(snapshot).0,
            conns: Mutex::new(HashMap::new()),
        })
    }

    /// The machine's live connection, now and as it changes.
    #[cfg_attr(not(feature = "vt"), allow(dead_code))]
    pub(crate) fn connection(&self, name: &str) -> watch::Receiver<Option<Conn>> {
        let mut conns = self.conns.lock().unwrap();
        conns
            .entry(name.to_owned())
            .or_insert_with(|| watch::channel(None).0)
            .subscribe()
    }

    fn publish_conn(&self, name: &str, conn: Conn) {
        let mut conns = self.conns.lock().unwrap();
        conns
            .entry(name.to_owned())
            .or_insert_with(|| watch::channel(None).0)
            .send_replace(Some(conn));
    }

    /// Notes a line in the diagnostic log (`hub.log`).
    #[cfg_attr(not(feature = "vt"), allow(dead_code))]
    pub(crate) fn note(&self, line: &str) {
        log(&self.dir, line);
    }

    #[cfg_attr(not(feature = "vt"), allow(dead_code))]
    pub(crate) fn runtime(&self) -> &tokio::runtime::Handle {
        &self.runtime
    }

    pub(crate) fn subscribe(&self) -> watch::Receiver<HubSnapshot> {
        self.snapshots.subscribe()
    }

    pub(crate) fn snapshot(&self) -> HubSnapshot {
        self.snapshots.borrow().clone()
    }

    /// Runs `f` on the state, then publishes the result.
    fn with<R>(self: &Arc<Self>, f: impl FnOnce(&Arc<Self>, &mut State) -> R) -> R {
        let mut st = self.state.lock().unwrap();
        let r = f(self, &mut st);
        self.snapshots.send_replace(build_snapshot(&st));
        r
    }

    /// Saves a changed list and only then makes it the list: a change the
    /// file did not take did not happen.
    fn commit_list(&self, st: &mut State, next: MachineList) -> Result<(), HubError> {
        if let Some(why) = &st.list_frozen {
            return Err(HubError::Storage {
                detail: why.clone(),
            });
        }
        next.save(&self.dir.join(LIST_FILE))
            .map_err(|e| HubError::Storage {
                detail: format!("Could not save the machine list: {e}."),
            })?;
        st.list = next;
        Ok(())
    }

    /// Saves bookkeeping (last connected times); a failure only notes it.
    fn save_list(&self, st: &mut State) {
        if st.list_frozen.is_some() {
            return;
        }
        if let Err(e) = st.list.save(&self.dir.join(LIST_FILE)) {
            st.notice = Some(format!("Could not save the machine list: {e}."));
        }
    }

    fn save_seen(&self, st: &mut State) {
        let memory = st.tracker.memory().to_string();
        if memory == st.saved_memory {
            return;
        }
        match write_atomic(&self.dir.join(SEEN_FILE), memory.as_bytes()) {
            Ok(()) => st.saved_memory = memory,
            Err(e) => st.notice = Some(format!("Could not save which agents you have seen: {e}.")),
        }
    }

    /// Starts (or restarts) a machine's task, ending any connection it had.
    fn spawn(self: &Arc<Self>, st: &mut State, name: &str) {
        let epoch = st.next_epoch;
        st.next_epoch += 1;
        let Some(live) = st.live.get_mut(name) else {
            return;
        };
        live.stop();
        live.epoch = epoch;
        live.state = MachineState::Connecting;
        let task = self
            .runtime
            .spawn(run_machine(self.clone(), name.to_owned(), epoch));
        live.task = Some(task.abort_handle());
    }

    /// Restarts a machine unless the app is in the background.
    fn restart(self: &Arc<Self>, st: &mut State, name: &str) {
        if !st.paused {
            self.spawn(st, name);
        }
    }

    /// Restarts every machine that is not connected or connecting.
    fn kick(self: &Arc<Self>, st: &mut State) {
        let idle: Vec<String> = st
            .live
            .iter()
            .filter(|(_, l)| !l.state.active())
            .map(|(n, _)| n.clone())
            .collect();
        for name in idle {
            self.spawn(st, &name);
        }
    }

    pub(crate) fn resume(self: &Arc<Self>) {
        self.with(|hub, st| {
            st.paused = false;
            hub.kick(st);
        });
    }

    /// The app is back in the foreground, also within its 60 s in the
    /// background: machines waiting for Network (granted in Android settings
    /// meanwhile) try again. Nothing while suspended; the resume connects all.
    pub(crate) fn foreground(self: &Arc<Self>) {
        self.with(|hub, st| {
            if st.paused {
                return;
            }
            let waiting: Vec<String> = st
                .live
                .iter()
                .filter(|(_, l)| l.state == MachineState::NetworkOff)
                .map(|(n, _)| n.clone())
                .collect();
            for name in waiting {
                hub.spawn(st, &name);
            }
        });
    }

    /// Closes every connection now (the app's 60 s in the background are up).
    pub(crate) fn suspend(self: &Arc<Self>) {
        self.with(|hub, st| {
            st.paused = true;
            let now = now_ms();
            let mut was_connected = false;
            for (name, live) in st.live.iter_mut() {
                live.stop();
                if matches!(live.state, MachineState::Connected { .. })
                    && let Some(m) = st.list.get_mut(name)
                {
                    m.last_connected_ms = Some(now);
                    was_connected = true;
                }
                live.state = MachineState::Paused;
            }
            if was_connected {
                hub.save_list(st);
            }
        });
    }

    /// Tries again every machine waiting on the person or a backoff (after a
    /// fingerprint prompt, say). Nothing happens in the background.
    pub(crate) fn retry(self: &Arc<Self>) {
        self.with(|hub, st| {
            if !st.paused {
                hub.kick(st);
            }
        });
    }

    /// Stops every task for good (the app object is going away).
    pub(crate) fn shutdown(&self) {
        let mut st = self.state.lock().unwrap();
        st.paused = true;
        for live in st.live.values_mut() {
            live.stop();
        }
    }

    pub(crate) fn add_machine(
        self: &Arc<Self>,
        name: &str,
        host: &str,
        port: u16,
        user: &str,
    ) -> Result<(), HubError> {
        self.with(|hub, st| {
            let mut next = st.list.clone();
            next.add(name, host, port, user)?;
            hub.commit_list(st, next)?;
            st.live.insert(name.to_owned(), Live::paused());
            hub.restart(st, name);
            Ok(())
        })
    }

    /// Changes where a machine is; a new host or port needs its host key confirmed again.
    pub(crate) fn update_machine(
        self: &Arc<Self>,
        name: &str,
        host: &str,
        port: u16,
        user: &str,
    ) -> Result<(), HubError> {
        self.with(|hub, st| {
            let mut next = st.list.clone();
            next.update(name, host, port, user)?;
            hub.commit_list(st, next)?;
            hub.restart(st, name);
            Ok(())
        })
    }

    pub(crate) fn remove_machine(self: &Arc<Self>, name: &str) -> Result<(), HubError> {
        self.with(|hub, st| {
            let mut next = st.list.clone();
            next.remove(name)?;
            hub.commit_list(st, next)?;
            if let Some(mut live) = st.live.remove(name) {
                live.stop();
            }
            st.tracker.forget_machine(name);
            hub.save_seen(st);
            Ok(())
        })
    }

    /// The person confirmed this host key: connect with it from now on.
    pub(crate) fn confirm_host_key(
        self: &Arc<Self>,
        name: &str,
        fingerprint: &str,
    ) -> Result<(), HubError> {
        if !fingerprint.starts_with("SHA256:") || fingerprint.contains(char::is_whitespace) {
            return Err(HubError::Invalid {
                detail: "a host key fingerprint looks like SHA256:...".into(),
            });
        }
        self.with(|hub, st| {
            hub.set_host_key(st, name, Some(fingerprint.to_owned()))?;
            hub.restart(st, name);
            Ok(())
        })
    }

    /// "Forget this machine's key": the next connection asks for confirmation.
    pub(crate) fn forget_host_key(self: &Arc<Self>, name: &str) -> Result<(), HubError> {
        self.with(|hub, st| {
            hub.set_host_key(st, name, None)?;
            hub.restart(st, name);
            Ok(())
        })
    }

    fn set_host_key(
        &self,
        st: &mut State,
        name: &str,
        key: Option<String>,
    ) -> Result<(), HubError> {
        let mut next = st.list.clone();
        let m = next.get_mut(name).ok_or_else(|| HubError::NotFound {
            name: name.to_owned(),
        })?;
        m.host_key = key;
        self.commit_list(st, next)
    }

    /// The person opened this agent: a finish is no longer unseen.
    pub(crate) fn mark_seen(self: &Arc<Self>, machine: &str, address: &str) {
        self.with(|hub, st| {
            st.tracker.mark_seen(machine, address);
            hub.save_seen(st);
        });
    }

    pub(crate) fn export_machines(&self) -> String {
        self.state.lock().unwrap().list.export()
    }

    /// Adds the machines of an export not listed yet; returns how many.
    pub(crate) fn import_machines(self: &Arc<Self>, text: &str) -> Result<u32, HubError> {
        self.with(|hub, st| {
            let mut next = st.list.clone();
            let added = next.import(text)?;
            hub.commit_list(st, next)?;
            let new: Vec<String> = st
                .list
                .machines()
                .iter()
                .map(|m| m.name.clone())
                .filter(|n| !st.live.contains_key(n))
                .collect();
            for name in &new {
                st.live.insert(name.clone(), Live::paused());
                hub.restart(st, name);
            }
            Ok(added as u32)
        })
    }

    /// Where the machine is, if it is still listed.
    fn target(&self, name: &str) -> Option<Target> {
        let st = self.state.lock().unwrap();
        st.list.get(name).map(|m| Target {
            host: m.host.clone(),
            port: m.port,
            user: m.user.clone(),
            host_key: m.host_key.clone(),
        })
    }

    /// An attempt starts. A retry keeps the failure on screen ("retrying")
    /// so the card does not blank its detail and links in between; anything
    /// else shows as connecting. False when `epoch` no longer owns the
    /// machine: the task was replaced and should stop.
    fn attempting(self: &Arc<Self>, name: &str, epoch: u64) -> bool {
        self.with(|_, st| match st.live.get_mut(name) {
            Some(live) if live.epoch == epoch => {
                if !live.state.retrying() {
                    live.state = MachineState::Connecting;
                }
                live.next_retry_ms = None;
                true
            }
            _ => false,
        })
    }

    /// Sets a machine's state if `epoch` still owns it, and when the next
    /// attempt starts if it retries; false as for [`HubCore::attempting`].
    fn settle(
        self: &Arc<Self>,
        name: &str,
        epoch: u64,
        state: MachineState,
        next_retry_ms: Option<u64>,
    ) -> bool {
        self.with(|_, st| match st.live.get_mut(name) {
            Some(live) if live.epoch == epoch => {
                live.state = state;
                live.next_retry_ms = next_retry_ms;
                true
            }
            _ => false,
        })
    }

    /// Takes the machine's terminals after a snapshot or a push.
    fn observe(
        self: &Arc<Self>,
        name: &str,
        epoch: u64,
        lines: &EventLines,
        connected: Option<MachineState>,
    ) -> bool {
        self.with(|hub, st| {
            let Some(live) = st.live.get_mut(name).filter(|l| l.epoch == epoch) else {
                return false;
            };
            if let Some(state) = connected {
                live.state = state;
                live.next_retry_ms = None;
                if let Some(m) = st.list.get_mut(name) {
                    m.last_connected_ms = Some(now_ms());
                }
                hub.save_list(st);
            }
            st.tracker.observe(name, lines.terminals(), now_ms());
            hub.save_seen(st);
            true
        })
    }

    /// The connection ended: remember when, for "last connected".
    fn disconnected(self: &Arc<Self>, name: &str, epoch: u64) {
        self.with(|hub, st| {
            if st.live.get(name).is_some_and(|l| l.epoch == epoch)
                && let Some(m) = st.list.get_mut(name)
            {
                m.last_connected_ms = Some(now_ms());
                hub.save_list(st);
            }
        });
    }
}

fn build_snapshot(st: &State) -> HubSnapshot {
    HubSnapshot {
        machines: st
            .list
            .machines()
            .iter()
            .map(|m| {
                let live = st.live.get(&m.name);
                let state = live.map_or(MachineState::Paused, |l| l.state.clone());
                MachineView {
                    name: m.name.clone(),
                    host: m.host.clone(),
                    port: m.port,
                    user: m.user.clone(),
                    host_key: m.host_key.clone(),
                    next_retry_ms: live
                        .and_then(|l| l.next_retry_ms)
                        .filter(|_| state.retrying()),
                    state,
                    last_connected_ms: m.last_connected_ms,
                }
            })
            .collect(),
        agents: st.tracker.rows().into_iter().map(AgentRow::from).collect(),
        others: st
            .tracker
            .others()
            .into_iter()
            .map(OtherTerminal::from)
            .collect(),
        notice: st.notice.clone(),
    }
}

#[derive(Default)]
struct Backoff {
    step: usize,
}

impl Backoff {
    fn next(&mut self) -> Duration {
        let secs = BACKOFF[self.step.min(BACKOFF.len() - 1)];
        self.step += 1;
        Duration::from_secs(secs)
    }

    fn reset(&mut self) {
        self.step = 0;
    }
}

async fn run_machine(hub: Arc<HubCore>, name: String, epoch: u64) {
    let mut backoff = Backoff::default();
    loop {
        let Some(target) = hub.target(&name) else {
            return;
        };
        if !hub.attempting(&name, epoch) {
            return;
        }
        let outcome = attempt(&hub, &name, epoch, target).await;
        if outcome.connected_for.is_some_and(|d| d >= STABLE) {
            backoff.reset();
        }
        let delay = outcome.retry.then(|| backoff.next());
        let next_retry_ms = delay.map(|d| now_ms() + d.as_millis() as u64);
        if !hub.settle(&name, epoch, outcome.state, next_retry_ms) {
            return;
        }
        let Some(delay) = delay else {
            return;
        };
        tokio::time::sleep(delay).await;
    }
}

fn dial_failure(e: DialError, target: &Target) -> Outcome {
    match e {
        DialError::NeedsUserAuth => Outcome::wait(MachineState::NeedsUserAuth),
        DialError::HostKeyUnconfirmed {
            presented,
            algorithm,
        } => Outcome::wait(MachineState::HostKeyUnconfirmed {
            presented,
            algorithm,
        }),
        DialError::HostKeyMismatch {
            presented,
            algorithm,
        } => Outcome::wait(MachineState::HostKeyMismatch {
            confirmed: target.host_key.clone().unwrap_or_default(),
            presented,
            algorithm,
        }),
        DialError::Rejected => Outcome::retry(MachineState::KeyRejected),
        DialError::NetworkOff => Outcome::wait(MachineState::NetworkOff),
        DialError::Failed(detail) => Outcome::retry(unreachable(detail)),
    }
}

/// The command closed its output before answering: why, from how it exited.
async fn command_ended(exit: tokio::sync::oneshot::Receiver<Exit>) -> Outcome {
    let Ok(Ok(Exit { status, stderr })) = tokio::time::timeout(EXIT_WAIT, exit).await else {
        return Outcome::retry(unreachable(
            "the connection closed before herdr-core answered",
        ));
    };
    if status == Some(127) || stderr.contains(NOT_INSTALLED) {
        return Outcome::retry(MachineState::NotInstalled);
    }
    let last = stderr.lines().map(str::trim).rfind(|l| !l.is_empty());
    let detail = match (last, status) {
        (Some(line), _) => line.to_owned(),
        (None, Some(code)) => format!("herdr-core exited with status {code}"),
        (None, None) => "the connection closed before herdr-core answered".to_owned(),
    };
    Outcome::retry(unreachable(detail))
}

async fn attempt(hub: &Arc<HubCore>, name: &str, epoch: u64, target: Target) -> Outcome {
    let link = match hub.dialer.dial(target.clone()).await {
        Ok(link) => link,
        Err(e) => return dial_failure(e, &target),
    };
    let (mut reader, mut writer, _session) = (link.reader, link.writer, link.session);
    let hello = match handshake::handshake(&mut reader, &mut writer).await {
        Ok(hello) => hello,
        Err(HandshakeError::Closed) => return command_ended(link.exit).await,
        Err(e) => return Outcome::retry(unreachable(e.to_string())),
    };
    let version = hello.result["version"].as_str().unwrap_or("?").to_owned();
    let untested = match compat::check(&hello.result, compat::NEWEST_TESTED) {
        Compat::Ok => false,
        Compat::Untested { .. } => true,
        Compat::TooOld { have, need } => return Outcome::wait(MachineState::TooOld { have, need }),
        Compat::Missing { have, .. } => {
            return Outcome::wait(MachineState::TooOld {
                have,
                need: compat::MIN_CORE.to_owned(),
            });
        }
        Compat::NewerGeneration { .. } => return Outcome::wait(MachineState::AppTooOld),
    };

    let client = Client::start(Cursor::new(hello.leftover).chain(reader), writer);
    let (snapshot, mut events) = match client.subscribe(json!({})).await {
        Ok(subscribed) => subscribed,
        Err(e) => return Outcome::retry(unreachable(e.to_string())),
    };
    let mut lines = EventLines::new(&snapshot);
    let connected_at = Instant::now();
    if !hub.observe(
        name,
        epoch,
        &lines,
        Some(MachineState::Connected { version, untested }),
    ) {
        return Outcome::wait(MachineState::Paused);
    }
    let (sizes, sizes_rx) = watch::channel::<Sizes>(Arc::new(sizes_of(&lines)));
    hub.publish_conn(
        name,
        Conn {
            epoch,
            client: client.clone(),
            sizes: sizes_rx,
        },
    );
    let _guard = ConnGuard {
        hub: hub.clone(),
        name: name.to_owned(),
        epoch,
        client: client.clone(),
    };
    let why = loop {
        match events.recv().await {
            StreamItem::Data(d) => {
                if lines.push(&d).terminals && !hub.observe(name, epoch, &lines, None) {
                    return Outcome::wait(MachineState::Paused);
                }
                let now = sizes_of(&lines);
                sizes.send_if_modified(|s| {
                    if **s == now {
                        false
                    } else {
                        *s = Arc::new(now);
                        true
                    }
                });
            }
            StreamItem::Closed(_) => break "herdr-core ended the event stream".to_owned(),
            StreamItem::Lost => {
                break match client.ended().await {
                    Ended::DeadLink => "no answer for 45 s".to_owned(),
                    Ended::Closed => "the connection closed".to_owned(),
                    Ended::Protocol(e) | Ended::Io(e) => e,
                };
            }
        }
    };
    hub.disconnected(name, epoch);
    Outcome {
        state: unreachable(why),
        retry: true,
        connected_for: Some(connected_at.elapsed()),
    }
}
