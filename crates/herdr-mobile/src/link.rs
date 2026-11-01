//! One connection to a machine's herdr-core: an SSH exec channel running
//! [`REMOTE_COMMAND`], read and written as a byte stream for the handshake and
//! the RPC client (machine-connection spec, "Each machine is reached by the
//! herdr-core remote command over SSH"). The hub dials through [`Dialer`], so
//! its tests can stand in for SSH.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use russh::ChannelMsg;
use russh::keys::ssh_key::PublicKey;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, DuplexStream};
use tokio::sync::oneshot;

use crate::ssh::{REMOTE_COMMAND, SessionError, SshError, SshSigner, open_session};

/// Connecting and signing in; a host that drops SYNs would otherwise take
/// minutes to fail.
const DIAL_TIMEOUT: Duration = Duration::from_secs(20);
/// How much of the remote command's stderr is kept: its last words.
const STDERR_KEEP: usize = 4096;
/// Between the channel and the reader; a full pipe stops reading the channel.
const PIPE: usize = 64 * 1024;

/// Where to connect, and the host key to insist on.
#[derive(Clone, Debug)]
pub(crate) struct Target {
    pub host: String,
    pub port: u16,
    pub user: String,
    /// The confirmed host key; none until the person confirms one.
    pub host_key: Option<String>,
}

/// How the remote command ended.
#[derive(Debug)]
pub(crate) struct Exit {
    pub status: Option<u32>,
    pub stderr: String,
}

pub(crate) struct Link {
    /// The command's stdout.
    pub reader: Box<dyn AsyncRead + Send + Unpin>,
    /// Its stdin.
    pub writer: Box<dyn AsyncWrite + Send + Unpin>,
    /// Resolves once the command has ended.
    pub exit: oneshot::Receiver<Exit>,
    /// Keeps the SSH session open; dropping it and the halves ends it.
    pub session: Box<dyn Send>,
}

#[derive(Debug)]
pub(crate) enum DialError {
    /// The key needs a fingerprint or device credential first.
    NeedsUserAuth,
    /// No host key is confirmed yet: the machine presented this one.
    HostKeyUnconfirmed {
        presented: String,
    },
    /// The machine presented a different key than the confirmed one.
    HostKeyMismatch {
        presented: String,
    },
    /// The machine does not accept the phone key (the line is not installed).
    Rejected,
    /// The OS refused the socket: GrapheneOS's Network permission is off.
    NetworkOff,
    Failed(String),
}

pub(crate) type Dialing = Pin<Box<dyn Future<Output = Result<Link, DialError>> + Send>>;

pub(crate) trait Dialer: Send + Sync + 'static {
    fn dial(&self, target: Target) -> Dialing;
}

/// Dials over SSH with the phone key.
pub(crate) struct SshDialer {
    pub key: PublicKey,
    pub signer: Arc<dyn SshSigner>,
}

impl Dialer for SshDialer {
    fn dial(&self, target: Target) -> Dialing {
        let key = self.key.clone();
        let signer = self.signer.clone();
        Box::pin(async move {
            tokio::time::timeout(DIAL_TIMEOUT, dial(target, key, signer))
                .await
                .unwrap_or_else(|_| Err(DialError::Failed("timed out".into())))
        })
    }
}

fn failed(e: impl std::fmt::Display) -> DialError {
    DialError::Failed(e.to_string())
}

async fn dial(t: Target, key: PublicKey, signer: Arc<dyn SshSigner>) -> Result<Link, DialError> {
    let session = open_session(&t.host, t.port, &t.user, key, t.host_key, false, signer)
        .await
        .map_err(|e| match e {
            SessionError::HostKeyUnconfirmed { presented } => {
                DialError::HostKeyUnconfirmed { presented }
            }
            SessionError::Ssh(SshError::NeedsUserAuth) => DialError::NeedsUserAuth,
            SessionError::Ssh(SshError::HostKeyMismatch { presented }) => {
                DialError::HostKeyMismatch { presented }
            }
            SessionError::Ssh(SshError::Rejected) => DialError::Rejected,
            SessionError::Ssh(SshError::NetworkPermissionOff) => DialError::NetworkOff,
            SessionError::Ssh(e) => failed(e),
        })?;
    let channel = session
        .handle
        .channel_open_session()
        .await
        .map_err(failed)?;
    // The forced command runs whatever is asked; asking for the same command
    // keeps a machine without the restricted line working too.
    channel.exec(true, REMOTE_COMMAND).await.map_err(failed)?;
    let (read, write) = channel.split();
    let (pipe_in, pipe_out) = tokio::io::duplex(PIPE);
    let (exit_tx, exit) = oneshot::channel();
    tokio::spawn(pump(read, pipe_in, exit_tx));
    Ok(Link {
        reader: Box::new(pipe_out),
        writer: Box::new(Box::pin(write.make_writer())),
        exit,
        session: Box::new(session.handle),
    })
}

/// Copies the channel's stdout into the pipe, keeps the end of stderr, and
/// reports the exit status once the channel closes.
async fn pump(mut read: russh::ChannelReadHalf, out: DuplexStream, exit: oneshot::Sender<Exit>) {
    let mut out = Some(out);
    let mut stderr: Vec<u8> = Vec::new();
    let mut status = None;
    while let Some(msg) = read.wait().await {
        match msg {
            ChannelMsg::Data { data } => {
                let Some(pipe) = out.as_mut() else { continue };
                if pipe.write_all(&data).await.is_err() {
                    // The reader is gone: the app closed this connection.
                    return;
                }
            }
            ChannelMsg::ExtendedData { data, ext: 1 } => {
                stderr.extend_from_slice(&data);
                if stderr.len() > STDERR_KEEP {
                    stderr.drain(..stderr.len() - STDERR_KEEP);
                }
            }
            ChannelMsg::ExitStatus { exit_status } => status = Some(exit_status),
            ChannelMsg::Eof => out = None,
            _ => {}
        }
    }
    drop(out);
    let _ = exit.send(Exit {
        status,
        stderr: String::from_utf8_lossy(&stderr).into_owned(),
    });
}
