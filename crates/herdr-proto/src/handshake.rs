//! The `hello` that opens a connection to `herdr-core connect` and switches it
//! to framed mode (machine-connection spec, "Each machine is reached by the
//! herdr-core remote command over SSH").
//!
//! The banner skipping follows herdr-core `machines.rs` (`dial_once`),
//! Apache-2.0. Changed: the phone says no `core` or `calls_you` (it is a
//! viewer, not another core), noise is capped at 64 KiB, and bytes read past
//! the reply are handed back because they are already frames.

use crate::compat::GENERATION;
use serde_json::{Value, json};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Output a login may print before herdr-core's first JSON line.
pub const NOISE_LIMIT: usize = 64 * 1024;
/// The reply line itself (the method list) is small; this only bounds memory.
const REPLY_LIMIT: usize = 1 << 20;
/// As long as the desktop core waits for a remote hello.
pub const HELLO_TIMEOUT: Duration = Duration::from_secs(20);
/// The hello's id; the RPC client numbers its calls from 1.
const HELLO_ID: u64 = 0;

#[derive(Debug)]
pub enum HandshakeError {
    /// The output ended before a reply: the remote command failed (its exit
    /// status and stderr say why; see the SSH layer).
    Closed,
    /// More than [`NOISE_LIMIT`] bytes came before the reply.
    NoiseTooLong,
    Timeout,
    /// The core answered the hello with an error.
    Refused {
        kind: Option<String>,
        message: String,
    },
    Io(std::io::Error),
}

impl std::fmt::Display for HandshakeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HandshakeError::Closed => {
                f.write_str("the connection closed before herdr-core answered")
            }
            HandshakeError::NoiseTooLong => write!(
                f,
                "no answer from herdr-core in the first {NOISE_LIMIT} bytes"
            ),
            HandshakeError::Timeout => f.write_str("herdr-core did not answer"),
            HandshakeError::Refused { message, .. } => write!(f, "herdr-core refused: {message}"),
            HandshakeError::Io(e) => write!(f, "{e}"),
        }
    }
}

impl std::error::Error for HandshakeError {}

pub struct Hello {
    /// The hello result: generation, version, core, methods, features.
    pub result: Value,
    /// Bytes read past the reply line: the start of the framed stream.
    pub leftover: Vec<u8>,
}

/// The hello request line.
pub fn hello_line() -> Vec<u8> {
    let req = json!({"jsonrpc": "2.0", "id": HELLO_ID, "method": "hello",
                     "params": {"generation": GENERATION, "framed": true}});
    format!("{req}\n").into_bytes()
}

/// Sends the hello and reads up to its reply, skipping noise before it.
pub async fn handshake<R, W>(r: &mut R, w: &mut W) -> Result<Hello, HandshakeError>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    tokio::time::timeout(HELLO_TIMEOUT, async {
        w.write_all(&hello_line())
            .await
            .map_err(HandshakeError::Io)?;
        w.flush().await.map_err(HandshakeError::Io)?;
        read_reply(r).await
    })
    .await
    .unwrap_or(Err(HandshakeError::Timeout))
}

async fn read_reply<R: AsyncRead + Unpin>(r: &mut R) -> Result<Hello, HandshakeError> {
    let mut buf: Vec<u8> = Vec::new();
    let mut skipped = 0usize;
    let mut chunk = [0u8; 8192];
    loop {
        // Every complete line in the buffer: the reply, or noise to skip.
        while let Some(end) = buf.iter().position(|b| *b == b'\n') {
            let line: Vec<u8> = buf.drain(..=end).collect();
            if let Some(reply) = parse_reply(&line) {
                return finish(reply, buf);
            }
            skipped += line.len();
            if skipped > NOISE_LIMIT {
                return Err(HandshakeError::NoiseTooLong);
            }
        }
        // A partial line longer than the limits can be neither noise nor reply.
        if skipped + buf.len() > NOISE_LIMIT && !buf.starts_with(b"{") || buf.len() > REPLY_LIMIT {
            return Err(HandshakeError::NoiseTooLong);
        }
        let n = r.read(&mut chunk).await.map_err(HandshakeError::Io)?;
        if n == 0 {
            return Err(HandshakeError::Closed);
        }
        buf.extend_from_slice(&chunk[..n]);
    }
}

fn parse_reply(line: &[u8]) -> Option<Value> {
    let v: Value = serde_json::from_slice(line).ok()?;
    (v.get("id").and_then(Value::as_u64) == Some(HELLO_ID)
        && (v.get("result").is_some() || v.get("error").is_some()))
    .then_some(v)
}

fn finish(reply: Value, leftover: Vec<u8>) -> Result<Hello, HandshakeError> {
    if let Some(e) = reply.get("error") {
        return Err(HandshakeError::Refused {
            kind: e
                .pointer("/data/kind")
                .and_then(Value::as_str)
                .map(str::to_owned),
            message: e
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("hello failed")
                .to_owned(),
        });
    }
    Ok(Hello {
        result: reply["result"].clone(),
        leftover,
    })
}
