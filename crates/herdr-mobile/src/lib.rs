uniffi::setup_scaffolding!();

mod hub;
pub mod keys_row;
mod link;
mod machines;
mod ssh;
mod synthetic;
#[cfg(feature = "vt")]
mod term;
#[cfg(feature = "vt")]
mod vt;

pub use ssh::{
    REMOTE_COMMAND, SignError, SshError, SshRun, SshSigner, authorized_keys_line,
    ssh_public_key_line, ssh_run_spike,
};

pub use hub::{
    AgentAttention, AgentRow, HubError, HubSnapshot, MachineState, MachineView, OtherTerminal,
};

use std::sync::{Arc, Mutex};

/// The core's crate version, shown by the app to prove the bridge works.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[derive(Debug, uniffi::Error)]
pub enum VtError {
    /// This build of the core does not include libghostty-vt (the `vt` feature).
    Unavailable,
    Engine {
        detail: String,
    },
}

impl std::fmt::Display for VtError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            VtError::Unavailable => {
                f.write_str("built without the terminal engine (the vt feature)")
            }
            VtError::Engine { detail } => f.write_str(detail),
        }
    }
}

impl std::error::Error for VtError {}

#[cfg(feature = "vt")]
impl From<vt::Failure> for VtError {
    fn from(f: vt::Failure) -> Self {
        VtError::Engine {
            detail: f.to_string(),
        }
    }
}

/// A libghostty-vt terminal for the device smoke test (task M0.5): feed it
/// output, read frames in the format documented in `vt/mod.rs`.
#[derive(uniffi::Object)]
pub struct TerminalSpike {
    #[cfg(feature = "vt")]
    inner: Mutex<vt::Terminal>,
}

#[uniffi::export]
impl TerminalSpike {
    #[uniffi::constructor]
    pub fn new(cols: u16, rows: u16) -> Result<Arc<Self>, VtError> {
        #[cfg(feature = "vt")]
        {
            Ok(Arc::new(TerminalSpike {
                inner: Mutex::new(vt::Terminal::new(cols, rows)?),
            }))
        }
        #[cfg(not(feature = "vt"))]
        {
            let _ = (cols, rows);
            Err(VtError::Unavailable)
        }
    }

    pub fn feed(&self, bytes: Vec<u8>) {
        #[cfg(feature = "vt")]
        self.lock().write(&bytes);
        #[cfg(not(feature = "vt"))]
        let _ = bytes;
    }

    pub fn frame(&self) -> Result<Vec<u8>, VtError> {
        #[cfg(feature = "vt")]
        {
            Ok(self.lock().frame()?)
        }
        #[cfg(not(feature = "vt"))]
        Err(VtError::Unavailable)
    }

    pub fn text(&self) -> Result<String, VtError> {
        #[cfg(feature = "vt")]
        {
            Ok(self.lock().text()?)
        }
        #[cfg(not(feature = "vt"))]
        Err(VtError::Unavailable)
    }
}

#[cfg(feature = "vt")]
impl TerminalSpike {
    fn lock(&self) -> std::sync::MutexGuard<'_, vt::Terminal> {
        // A panic while holding the lock cannot leave the terminal half-updated
        // in a way that matters for the spike, so a poisoned lock is reused.
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

/// Receives the hub's snapshots, whole, on a background thread. Implemented
/// in Kotlin; it should hand the snapshot to the UI thread and return.
#[uniffi::export(foreign)]
pub trait HubListener: Send + Sync {
    fn on_snapshot(&self, snapshot: HubSnapshot);
}

/// The app's connections to its machines (see `hub/mod.rs`). Starts
/// suspended: call [`Hub::resume`] in the foreground.
#[derive(uniffi::Object)]
pub struct Hub {
    core: Arc<hub::HubCore>,
    runtime: Mutex<Option<tokio::runtime::Runtime>>,
}

#[uniffi::export]
impl Hub {
    /// `data_dir` holds the machine list and seen turns (app-private, not
    /// backed up); `spki_der` is the phone key's public half.
    #[uniffi::constructor]
    pub fn new(
        data_dir: String,
        spki_der: Vec<u8>,
        signer: Arc<dyn SshSigner>,
        listener: Arc<dyn HubListener>,
    ) -> Result<Arc<Self>, HubError> {
        let key = ssh::public_key_from_spki(&spki_der).map_err(|e| HubError::Invalid {
            detail: e.to_string(),
        })?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("herdr-hub")
            .enable_all()
            .build()
            .map_err(|e| HubError::Invalid {
                detail: format!("could not start the connection runtime: {e}"),
            })?;
        let dialer = Arc::new(link::SshDialer { key, signer });
        let core = {
            let _entered = runtime.enter();
            hub::HubCore::start(data_dir.into(), dialer)
        };
        let mut snapshots = core.subscribe();
        runtime.spawn(async move {
            loop {
                let snapshot = snapshots.borrow_and_update().clone();
                let listener = listener.clone();
                // Kotlin code runs off the runtime's worker threads.
                if tokio::task::spawn_blocking(move || listener.on_snapshot(snapshot))
                    .await
                    .is_err()
                    || snapshots.changed().await.is_err()
                {
                    return;
                }
            }
        });
        Ok(Arc::new(Hub {
            core,
            runtime: Mutex::new(Some(runtime)),
        }))
    }

    pub fn snapshot(&self) -> HubSnapshot {
        self.core.snapshot()
    }

    /// The app is in the foreground: connect every machine.
    pub fn resume(&self) {
        self.core.resume();
    }

    /// Every return to the foreground: machines waiting for the Network
    /// permission try again.
    pub fn foreground(&self) {
        self.core.foreground();
    }

    /// The app has been in the background for 60 s: close every connection.
    pub fn suspend(&self) {
        self.core.suspend();
    }

    /// Try again now, after a fingerprint prompt or when the person asks.
    pub fn retry(&self) {
        self.core.retry();
    }

    pub fn add_machine(
        &self,
        name: String,
        host: String,
        port: u16,
        user: String,
    ) -> Result<(), HubError> {
        self.core.add_machine(&name, &host, port, &user)
    }

    pub fn update_machine(
        &self,
        name: String,
        host: String,
        port: u16,
        user: String,
    ) -> Result<(), HubError> {
        self.core.update_machine(&name, &host, port, &user)
    }

    pub fn remove_machine(&self, name: String) -> Result<(), HubError> {
        self.core.remove_machine(&name)
    }

    pub fn confirm_host_key(&self, name: String, fingerprint: String) -> Result<(), HubError> {
        self.core.confirm_host_key(&name, &fingerprint)
    }

    pub fn forget_host_key(&self, name: String) -> Result<(), HubError> {
        self.core.forget_host_key(&name)
    }

    pub fn mark_seen(&self, machine: String, address: String) {
        self.core.mark_seen(&machine, &address);
    }

    /// The machine list as JSON, without host keys, for the system file picker.
    pub fn export_machines(&self) -> String {
        self.core.export_machines()
    }

    /// Adds the machines of an export that are not listed yet; returns how many.
    pub fn import_machines(&self, json: String) -> Result<u32, HubError> {
        self.core.import_machines(&json)
    }

    /// Opens a terminal, at the phone's size when `at_phone_size` (the app,
    /// since phone check round 2), else to watch; `phone_cols` x `phone_rows`
    /// is the phone's own grid. The listener hears frames and states.
    pub fn open_terminal(
        &self,
        machine: String,
        address: String,
        phone_cols: u16,
        phone_rows: u16,
        at_phone_size: bool,
        listener: Arc<dyn TerminalListener>,
    ) -> Arc<TerminalView> {
        #[cfg(feature = "vt")]
        {
            let (tx, rx) = std::sync::mpsc::channel::<SinkItem>();
            // One thread per open terminal keeps the listener's calls in order.
            let spawned = std::thread::Builder::new()
                .name("herdr-term".into())
                .spawn(move || {
                    while let Ok(item) = rx.recv() {
                        match item {
                            SinkItem::Frame(f) => listener.on_frame(f),
                            SinkItem::State(s) => listener.on_state(s),
                            SinkItem::Interrupt(r) => listener.on_interrupt(r),
                        }
                    }
                });
            let cmds = spawned.ok().map(|_| {
                term::spawn(
                    &self.core,
                    &machine,
                    &address,
                    (phone_cols, phone_rows),
                    at_phone_size,
                    Arc::new(ListenerSink(tx)),
                )
            });
            Arc::new(TerminalView {
                cmds: Mutex::new(cmds),
                keys: Mutex::new(keys_row::KeysRow::default()),
            })
        }
        #[cfg(not(feature = "vt"))]
        {
            let _ = (machine, address, phone_cols, phone_rows, at_phone_size);
            listener.on_state(TermState {
                phase: TermPhase::Failed {
                    detail: VtError::Unavailable.to_string(),
                },
                cols: 0,
                rows: 0,
            });
            Arc::new(TerminalView {
                keys: Mutex::new(keys_row::KeysRow::default()),
            })
        }
    }
}

impl Drop for Hub {
    fn drop(&mut self) {
        self.core.shutdown();
        let runtime = self.runtime.get_mut().unwrap_or_else(|e| e.into_inner());
        if let Some(runtime) = runtime.take() {
            // Never block the thread that dropped the last reference.
            runtime.shutdown_background();
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TermPhase {
    /// The machine is not connected; the terminal reattaches when it is.
    Waiting,
    Attaching,
    Watching,
    Typing,
    /// The terminal itself ended.
    Ended {
        reason: String,
    },
    /// The attach failed; tried again shortly.
    Failed {
        detail: String,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TermState {
    pub phase: TermPhase,
    /// The grid the frames have; 0 before the first attach.
    pub cols: u16,
    pub rows: u16,
}

/// Receives an open terminal's frames, states and interrupt reports, in
/// order, on one background thread. Implemented in Kotlin.
#[uniffi::export(foreign)]
pub trait TerminalListener: Send + Sync {
    fn on_frame(&self, frame: Vec<u8>);
    fn on_state(&self, state: TermState);
    fn on_interrupt(&self, report: String);
}

/// A keys-row key (terminal-input spec, "A keys row for keys the phone
/// keyboard lacks").
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum RowKeyTap {
    Esc,
    Tab,
    ShiftTab,
    Ctrl,
    Alt,
    Up,
    Down,
    Left,
    Right,
    Enter,
    CtrlC,
    /// A symbol key such as `|`; only its first character counts.
    Symbol {
        text: String,
    },
}

/// The sticky modifiers waiting for the next key.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct Armed {
    pub ctrl: bool,
    pub alt: bool,
}

/// One open terminal (see `term.rs`). Closing it, or dropping it, detaches.
#[derive(uniffi::Object)]
pub struct TerminalView {
    #[cfg(feature = "vt")]
    cmds: Mutex<Option<tokio::sync::mpsc::UnboundedSender<term::Cmd>>>,
    keys: Mutex<keys_row::KeysRow>,
}

#[cfg(feature = "vt")]
struct ListenerSink(std::sync::mpsc::Sender<SinkItem>);

#[cfg(feature = "vt")]
enum SinkItem {
    Frame(Vec<u8>),
    State(TermState),
    Interrupt(String),
}

#[cfg(feature = "vt")]
impl term::TermSink for ListenerSink {
    fn frame(&self, bytes: Vec<u8>) {
        let _ = self.0.send(SinkItem::Frame(bytes));
    }
    fn state(&self, state: TermState) {
        let _ = self.0.send(SinkItem::State(state));
    }
    fn interrupted(&self, report: String) {
        let _ = self.0.send(SinkItem::Interrupt(report));
    }
}

impl TerminalView {
    #[cfg(feature = "vt")]
    fn send(&self, cmd: term::Cmd) {
        let cmds = self
            .cmds
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(tx) = cmds.as_ref() {
            let _ = tx.send(cmd);
        }
    }

    #[cfg(feature = "vt")]
    fn press(&self, key: keys_row::Key, mods: keys_row::Mods) {
        self.send(term::Cmd::Key(key, mods));
    }

    #[cfg(not(feature = "vt"))]
    fn press(&self, _key: keys_row::Key, _mods: keys_row::Mods) {}

    fn keys(&self) -> std::sync::MutexGuard<'_, keys_row::KeysRow> {
        self.keys
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn armed_now(&self) -> Armed {
        let m = self.keys().armed();
        Armed {
            ctrl: m.ctrl,
            alt: m.alt,
        }
    }
}

#[uniffi::export]
impl TerminalView {
    /// A keys-row tap; returns the modifiers now held (the row shows them).
    pub fn tap(&self, key: RowKeyTap) -> Armed {
        use keys_row::RowKey;
        let row = match key {
            RowKeyTap::Esc => RowKey::Esc,
            RowKeyTap::Tab => RowKey::Tab,
            RowKeyTap::ShiftTab => RowKey::ShiftTab,
            RowKeyTap::Ctrl => RowKey::Ctrl,
            RowKeyTap::Alt => RowKey::Alt,
            RowKeyTap::Up => RowKey::Up,
            RowKeyTap::Down => RowKey::Down,
            RowKeyTap::Left => RowKey::Left,
            RowKeyTap::Right => RowKey::Right,
            RowKeyTap::Enter => RowKey::Enter,
            RowKeyTap::CtrlC => RowKey::CtrlC,
            RowKeyTap::Symbol { text } => match text.chars().next() {
                Some(c) => RowKey::Symbol(c),
                None => return self.armed_now(),
            },
        };
        let pressed = self.keys().tap(row);
        if let Some((k, m)) = pressed {
            self.press(k, m);
        }
        self.armed_now()
    }

    /// Text from the terminal keyboard; each character takes any armed
    /// modifier. Returns the modifiers still held.
    pub fn type_text(&self, text: String) -> Armed {
        for c in text.chars() {
            let key = match c {
                '\n' | '\r' => keys_row::RowKey::Enter,
                c => keys_row::RowKey::Typed(c),
            };
            let pressed = self.keys().tap(key);
            if let Some((k, m)) = pressed {
                self.press(k, m);
            }
        }
        self.armed_now()
    }

    pub fn backspace(&self) -> Armed {
        let mods = {
            let mut keys = self.keys();
            let m = keys.armed();
            *keys = keys_row::KeysRow::default();
            m
        };
        self.press(keys_row::Key::Backspace, mods);
        self.armed_now()
    }

    pub fn armed(&self) -> Armed {
        self.armed_now()
    }

    /// Compose text, as a bracketed paste without Enter.
    pub fn paste(&self, text: String) {
        #[cfg(feature = "vt")]
        self.send(term::Cmd::Paste(text));
        #[cfg(not(feature = "vt"))]
        let _ = text;
    }

    /// Scrolls the view by rows; up is negative.
    pub fn scroll(&self, rows: i32) {
        #[cfg(feature = "vt")]
        self.send(term::Cmd::Scroll(rows));
        #[cfg(not(feature = "vt"))]
        let _ = rows;
    }

    pub fn live(&self) {
        #[cfg(feature = "vt")]
        self.send(term::Cmd::Live);
    }

    /// Takes over at the phone's size without typing (a tap while watching).
    pub fn lead(&self) {
        #[cfg(feature = "vt")]
        self.send(term::Cmd::Lead);
    }

    /// A tap on cell (col, row) of the phone's grid: a click for a program
    /// that tracks the mouse, while the phone types (else nothing).
    pub fn click(&self, col: u16, row: u16) {
        #[cfg(feature = "vt")]
        self.send(term::Cmd::Click(col, row));
        #[cfg(not(feature = "vt"))]
        let _ = (col, row);
    }

    /// The phone's own grid size: sent while typing, kept while watching.
    pub fn phone_size(&self, cols: u16, rows: u16) {
        #[cfg(feature = "vt")]
        self.send(term::Cmd::PhoneSize(cols, rows));
        #[cfg(not(feature = "vt"))]
        let _ = (cols, rows);
    }

    pub fn interrupt(&self) {
        #[cfg(feature = "vt")]
        self.send(term::Cmd::Interrupt);
    }

    /// Detaches; the view sends nothing after this.
    pub fn close(&self) {
        #[cfg(feature = "vt")]
        self.cmds
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
    }
}

/// About `len` bytes of an invented agent session for a terminal `cols` wide.
#[uniffi::export]
pub fn synthetic_session(cols: u16, len: u32) -> Vec<u8> {
    synthetic::session(cols, len as usize)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_version_matches_the_repository_version_file() {
        assert_eq!(core_version(), include_str!("../../../VERSION").trim());
    }

    #[cfg(not(feature = "vt"))]
    #[test]
    fn without_the_engine_the_terminal_says_so() {
        assert!(matches!(
            TerminalSpike::new(80, 24),
            Err(VtError::Unavailable)
        ));
    }

    #[cfg(feature = "vt")]
    #[test]
    fn the_exported_terminal_reads_back_what_it_was_fed() {
        let t = TerminalSpike::new(20, 3).unwrap();
        t.feed(b"hello\r\nworld".to_vec());
        assert_eq!(t.text().unwrap(), "hello\nworld");
        assert!(t.frame().unwrap().len() > vt::HEADER_LEN);
    }
}
