uniffi::setup_scaffolding!();

mod ssh;
mod synthetic;
#[cfg(feature = "vt")]
mod vt;

pub use ssh::{
    REMOTE_COMMAND, SignError, SshError, SshRun, SshSigner, authorized_keys_line,
    ssh_public_key_line, ssh_run_spike,
};

use std::sync::Arc;
#[cfg(feature = "vt")]
use std::sync::Mutex;

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
