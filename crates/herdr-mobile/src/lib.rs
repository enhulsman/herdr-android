uniffi::setup_scaffolding!();

mod hub;
mod link;
mod machines;
mod ssh;
mod synthetic;
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
