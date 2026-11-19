//! The Changes view's hub half (OpenSpec change `changes-view`, design D5):
//! reads the agent's uncommitted changes through `machine.exec` with
//! `kudde-changes`' fixed calls, and tells the screen when to read again (a
//! status, turn or missed event of the terminal, or a restored connection).
//! It never sends anything to the terminal.

use super::{Conn, HubCore};
use herdr_proto::rpc::{CallError, Client, Stream, StreamItem};
use kudde_changes::{Exec, Kind, List, Outcome, Reader, Step, diff_call, parse_diff};
use serde_json::{Value, json};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// One file in the list.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ChangesEntry {
    /// The path from the repository's top, also the key for
    /// [`Changes::open`].
    pub path: String,
    /// The status word: changed, added, deleted, new, conflict, submodule or
    /// repository.
    pub kind: String,
    /// Lines added (a new file: its line count) and removed; None when not
    /// counted (binary, empty, submodule, repository).
    pub added: Option<u64>,
    pub removed: Option<u64>,
    /// Whether a tap opens its diff.
    pub opens: bool,
}

#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum ChangesList {
    /// Nothing read yet ("Reading the changes…").
    Reading,
    NotARepo,
    /// "Could not read the changes", git's first error line, Retry.
    Failed {
        line: Option<String>,
    },
    Listed {
        entries: Vec<ChangesEntry>,
        /// "Showing the first 1,000 changed files".
        capped: bool,
    },
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ChangesLine {
    pub text: String,
    /// Cut at 4,000 characters ("… (cut)").
    pub cut: bool,
}

#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum ChangesDiff {
    /// The open file's diff is being fetched.
    Reading,
    Lines {
        lines: Vec<ChangesLine>,
        /// How many of `lines` are file headers.
        headers: u32,
        /// "Too large to show on the phone".
        too_large: bool,
    },
    NoLongerChanged,
    Failed {
        line: Option<String>,
    },
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ChangesView {
    pub list: ChangesList,
    /// The open file's path and diff; None when the list shows.
    pub open: Option<String>,
    pub diff: Option<ChangesDiff>,
}

/// The folder to read: the foreground process's (the folder the title bar
/// names), else the terminal's own.
pub(crate) fn folder_of(terminal: &Value) -> Option<String> {
    [&terminal["foreground"]["cwd"], &terminal["cwd"]]
        .into_iter()
        .filter_map(Value::as_str)
        .find(|c| !c.is_empty())
        .map(str::to_owned)
}

/// A `machine.exec` reply as `kudde-changes` takes it.
pub(crate) fn exec_of(reply: Result<Value, CallError>) -> Exec {
    match reply {
        Ok(v) => Exec::from_result(&v),
        Err(e) if e.kind() == Some("spawn_failed") => Exec::SpawnFailed,
        Err(e) => Exec::Error(e.kind().unwrap_or("no answer").to_owned()),
    }
}

/// Whether a frame of the terminal's event stream asks for a read: a
/// status, turn or missed event (not a heartbeat, title or size change).
pub(crate) fn wants_read(frame: &[u8]) -> bool {
    frame
        .split(|&b| b == b'\n')
        .filter_map(|l| serde_json::from_slice::<Value>(l).ok())
        .any(|v| {
            v["method"] == "missed"
                || matches!(
                    v["params"]["type"].as_str(),
                    Some("status" | "turn" | "missed")
                )
        })
}

/// How long the phone waits for a call past git's own timeout.
const MARGIN: Duration = Duration::from_secs(10);

fn entry_of(e: &kudde_changes::Entry) -> ChangesEntry {
    ChangesEntry {
        path: e.path.display(),
        kind: word(e.kind).into(),
        added: e.counts.map(|c| c.0),
        removed: e.counts.map(|c| c.1),
        opens: e.kind != Kind::Repository && std::str::from_utf8(e.path.as_bytes()).is_ok(),
    }
}

fn list_of(o: &Option<Outcome<List>>) -> ChangesList {
    match o {
        None => ChangesList::Reading,
        Some(Outcome::NotARepo) => ChangesList::NotARepo,
        Some(Outcome::Failed(line)) => ChangesList::Failed { line: line.clone() },
        Some(Outcome::Ok(l)) => ChangesList::Listed {
            entries: l.entries.iter().map(entry_of).collect(),
            capped: l.capped,
        },
    }
}

fn diff_of(o: Outcome<kudde_changes::Diff>) -> ChangesDiff {
    match o {
        Outcome::Ok(d) if d.is_empty() => ChangesDiff::NoLongerChanged,
        Outcome::Ok(d) => ChangesDiff::Lines {
            headers: d.headers as u32,
            too_large: d.too_large,
            lines: d
                .lines
                .into_iter()
                .map(|l| ChangesLine {
                    text: l.text,
                    cut: l.cut,
                })
                .collect(),
        },
        Outcome::NotARepo | Outcome::Failed(None) => ChangesDiff::Failed { line: None },
        Outcome::Failed(line) => ChangesDiff::Failed { line },
    }
}

async fn run(c: &Client, call: &kudde_changes::Call) -> Exec {
    let wait = Duration::from_secs(call.timeout_s) + MARGIN;
    exec_of(c.call_timeout("machine.exec", call.params(), wait).await)
}

/// The status word of an entry.
pub(crate) fn word(kind: Kind) -> &'static str {
    match kind {
        Kind::Changed => "changed",
        Kind::Added => "added",
        Kind::Deleted => "deleted",
        Kind::New => "new",
        Kind::Conflict => "conflict",
        Kind::Submodule => "submodule",
        Kind::Repository => "repository",
    }
}

/// What is held between calls.
#[derive(Default)]
struct Held {
    list: Option<Outcome<List>>,
    open: Option<String>,
    diff: Option<ChangesDiff>,
}

#[derive(uniffi::Object)]
pub struct Changes {
    core: Arc<HubCore>,
    machine: String,
    address: String,
    held: Mutex<Held>,
    /// This terminal's events, and the connection they came on.
    events: tokio::sync::Mutex<Option<(u64, Stream)>>,
    /// The screen left; the next read clears it.
    left: AtomicBool,
}

impl Changes {
    pub(crate) fn new(core: Arc<HubCore>, machine: String, address: String) -> Self {
        Changes {
            core,
            machine,
            address,
            held: Mutex::new(Held::default()),
            events: tokio::sync::Mutex::new(None),
            left: AtomicBool::new(false),
        }
    }

    pub(crate) fn view(&self) -> ChangesView {
        let h = self.held.lock().unwrap();
        ChangesView {
            list: list_of(&h.list),
            open: h.open.clone(),
            diff: h.diff.clone(),
        }
    }

    /// The screen left (the screen off or gone) since the last read began.
    pub(crate) fn has_left(&self) -> bool {
        self.left.load(Ordering::SeqCst)
    }

    /// Subscribes to the terminal's events on `conn` unless already.
    async fn subscribe(&self, conn: &Conn) -> bool {
        let mut events = self.events.lock().await;
        if events
            .as_ref()
            .is_some_and(|(epoch, _)| *epoch == conn.epoch)
        {
            return true;
        }
        match conn
            .client
            .subscribe(json!({"terminal": self.address}))
            .await
        {
            Ok((_, stream)) => {
                *events = Some((conn.epoch, stream));
                true
            }
            Err(_) => {
                *events = None;
                false
            }
        }
    }

    /// Subscribes (if not yet) and reads the list, then the open diff. With
    /// no connection the held view stays.
    pub(crate) async fn read_async(&self) -> ChangesView {
        self.left.store(false, Ordering::SeqCst);
        let Some(conn) = self.core.live_conn(&self.machine) else {
            return self.view();
        };
        // Subscribed first, so a change during the read asks for one more.
        self.subscribe(&conn).await;
        let c = &conn.client;
        let got = c
            .call("terminal.get", json!({"terminal": self.address}))
            .await;
        let outcome = match got.ok().as_ref().and_then(folder_of) {
            None => Outcome::Failed(None),
            Some(folder) => {
                let mut reader = Reader::new(&folder);
                let mut step = reader.start();
                loop {
                    // Leaving stops a read between calls: nothing more runs while
                    // the screen is off (review 2026-11-16).
                    if self.has_left() {
                        return self.view();
                    }
                    match step {
                        Step::Run(call) => step = reader.feed(run(c, &call).await),
                        Step::Done(o) => break o,
                    }
                }
            }
        };
        if self.has_left() {
            return self.view();
        }
        self.held.lock().unwrap().list = Some(outcome);
        self.fetch_open(c).await;
        self.view()
    }

    /// Fetches the open file's diff, or says it is no longer changed.
    async fn fetch_open(&self, c: &Client) {
        let call = {
            let mut h = self.held.lock().unwrap();
            let Some(open) = h.open.clone() else { return };
            let Some(Outcome::Ok(list)) = &h.list else {
                return;
            };
            match list.entries.iter().find(|e| e.path.display() == open) {
                None => {
                    h.diff = Some(ChangesDiff::NoLongerChanged);
                    return;
                }
                Some(e) => match diff_call(list, e) {
                    None => {
                        h.diff = Some(ChangesDiff::Failed { line: None });
                        return;
                    }
                    Some(call) => (open, e.kind, call),
                },
            }
        };
        let (path, kind, call) = call;
        let got = parse_diff(kind, run(c, &call).await);
        let mut h = self.held.lock().unwrap();
        // The person may have gone back, or on to another file, meanwhile.
        if h.open.as_deref() == Some(path.as_str()) {
            h.diff = Some(diff_of(got));
        }
    }

    /// Opens `path`'s diff (None: back to the list) and fetches it.
    pub(crate) async fn open_async(&self, path: Option<String>) -> ChangesView {
        {
            let mut h = self.held.lock().unwrap();
            h.diff = path.as_ref().map(|_| ChangesDiff::Reading);
            h.open = path;
        }
        if self.held.lock().unwrap().open.is_some() {
            match self.core.live_conn(&self.machine) {
                Some(conn) => self.fetch_open(&conn.client).await,
                None => {
                    self.held.lock().unwrap().diff = Some(ChangesDiff::Failed { line: None });
                }
            }
        }
        self.view()
    }

    pub(crate) async fn wait_change_async(&self, timeout_ms: u32) -> bool {
        let deadline = tokio::time::Instant::now() + Duration::from_millis(timeout_ms.into());
        loop {
            if self.left.load(Ordering::SeqCst) {
                tokio::time::sleep_until(deadline).await;
                return false;
            }
            let mut events = self.events.lock().await;
            if let Some((_, stream)) = events.as_mut() {
                // What came during the read: one read for all of it.
                let mut wanted = false;
                let mut gone = false;
                while let Ok(item) = tokio::time::timeout(Duration::ZERO, stream.recv()).await {
                    match item {
                        StreamItem::Data(d) => wanted |= wants_read(&d),
                        StreamItem::Closed(_) | StreamItem::Lost => {
                            gone = true;
                            break;
                        }
                    }
                }
                if wanted {
                    return true;
                }
                if !gone {
                    let next = async {
                        loop {
                            match stream.recv().await {
                                StreamItem::Data(d) if wants_read(&d) => return true,
                                StreamItem::Data(_) => {}
                                StreamItem::Closed(_) | StreamItem::Lost => return false,
                            }
                        }
                    };
                    match tokio::time::timeout_at(deadline, next).await {
                        Ok(true) => return true,
                        Ok(false) => {}
                        Err(_) => return false,
                    }
                }
                *events = None;
            }
            drop(events);
            // The subscription is gone: once the connection is back,
            // subscribe again and read.
            if let Some(conn) = self.core.live_conn(&self.machine)
                && self.subscribe(&conn).await
            {
                return true;
            }
            let now = tokio::time::Instant::now();
            if now >= deadline {
                return false;
            }
            tokio::time::sleep((deadline - now).min(Duration::from_secs(1))).await;
        }
    }

    /// The screen left: the subscription goes.
    pub(crate) async fn leave_async(&self) {
        self.left.store(true, Ordering::SeqCst);
        *self.events.lock().await = None;
    }
}

#[uniffi::export]
impl Changes {
    /// Reads the list and the open diff. Blocks: call it off the main thread.
    pub fn read(&self) -> ChangesView {
        let core = self.core.clone();
        core.runtime().block_on(self.read_async())
    }

    /// Opens a file's diff, or with None goes back to the list. Blocks.
    pub fn open(&self, path: Option<String>) -> ChangesView {
        let core = self.core.clone();
        core.runtime().block_on(self.open_async(path))
    }

    /// Waits up to `timeout_ms` for a reason to read again: a status, turn
    /// or missed event of the terminal, or the connection restored. Events
    /// that came during a read give one true, never more. Blocks.
    pub fn wait_change(&self, timeout_ms: u32) -> bool {
        let core = self.core.clone();
        core.runtime().block_on(self.wait_change_async(timeout_ms))
    }

    /// The screen went off or was left: no events are held until the next
    /// read.
    pub fn leave(&self) {
        let core = self.core.clone();
        core.runtime().block_on(self.leave_async())
    }
}
