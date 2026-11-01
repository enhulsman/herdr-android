//! Framed mode: `[channel u32 LE][kind u8][len u32 LE][payload]` (schema/README.md).
//!
//! Adapted from herdr-app `src/hc.rs` (constants, `encode`, `read_frame`) and
//! herdr-core `wire.rs` (`decode_header`, the 16 MiB cap), Apache-2.0. Changed:
//! async reads, and an oversized header fails before the payload is allocated.

use tokio::io::{AsyncRead, AsyncReadExt};

pub const HEADER: usize = 9;
/// The largest payload a frame may carry.
pub const MAX_PAYLOAD: usize = 16 << 20;

/// Channel 0: one JSON-RPC message; other channels: stream bytes.
pub const DATA: u8 = 0;
/// u32 LE: the receiver consumed this many bytes; the sender may send more.
pub const CREDIT: u8 = 1;
/// The channel is over (a JSON reason from the core, empty from a client).
pub const CLOSE: u8 = 2;
/// Viewer to core: u16 LE cols, u16 LE rows.
pub const RESIZE: u8 = 3;
/// Viewer to core: 1 gained focus, 0 lost it.
pub const FOCUS: u8 = 4;
/// Viewer to core: JSON display facts.
pub const FACTS: u8 = 5;

/// Credit a stream starts with before the viewer acknowledges anything.
pub const WINDOW: usize = 256 * 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Frame {
    pub channel: u32,
    pub kind: u8,
    pub payload: Vec<u8>,
}

pub fn encode(channel: u32, kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER + payload.len());
    out.extend_from_slice(&channel.to_le_bytes());
    out.push(kind);
    out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    out.extend_from_slice(payload);
    out
}

/// Channel, kind and payload length; an error for a payload over [`MAX_PAYLOAD`].
pub fn decode_header(h: &[u8; HEADER]) -> Result<(u32, u8, usize), String> {
    let channel = u32::from_le_bytes(h[0..4].try_into().unwrap());
    let len = u32::from_le_bytes(h[5..9].try_into().unwrap()) as usize;
    if len > MAX_PAYLOAD {
        return Err(format!("frame of {len} bytes exceeds {MAX_PAYLOAD}"));
    }
    Ok((channel, h[4], len))
}

/// Reads one frame; `Ok(None)` on a clean end of stream between frames.
/// An oversized header is `InvalidData`; a stream cut mid-frame is `UnexpectedEof`.
pub async fn read_frame(r: &mut (impl AsyncRead + Unpin)) -> std::io::Result<Option<Frame>> {
    let mut header = [0u8; HEADER];
    let mut got = 0;
    while got < HEADER {
        match r.read(&mut header[got..]).await? {
            0 if got == 0 => return Ok(None),
            0 => return Err(std::io::ErrorKind::UnexpectedEof.into()),
            n => got += n,
        }
    }
    let (channel, kind, len) = decode_header(&header)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
    let mut payload = vec![0; len];
    r.read_exact(&mut payload).await?;
    Ok(Some(Frame {
        channel,
        kind,
        payload,
    }))
}
