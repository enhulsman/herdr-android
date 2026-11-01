//! JSON-RPC on channel 0 and streams on their own channels, over one framed
//! connection (machine-connection spec: "Calls time out honestly", "Streams
//! keep flowing and dead links are noticed").
//!
//! Adapted from herdr-app `src/hc.rs` (`Routes`, `Core::call_timeout`, the
//! reader's routing and early frames, `RpcError`), Apache-2.0. Changed: async
//! over any reader and writer (an SSH channel on the phone) instead of threads
//! over a child process; a timed-out call is "outcome unknown", not a failure;
//! CREDIT goes back as the consumer takes data; 45 s without any frame ends
//! the connection; early frames for unopened channels are capped.

use crate::wire;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::sync::{mpsc, oneshot, watch};

/// How long a call waits for its reply.
pub const CALL_TIMEOUT: Duration = Duration::from_secs(10);
/// `terminal.interrupt` waits longer: the core escalates through signals.
pub const INTERRUPT_TIMEOUT: Duration = Duration::from_secs(15);
/// No frame at all for this long: the link is dead. The core sends a
/// heartbeat on each event channel every 15 s.
pub const DEAD_LINK: Duration = Duration::from_secs(45);
/// What the app keeps for channels whose opening reply it has not handled
/// yet; a core that sends more is broken and the connection ends.
const EARLY_LIMIT: usize = 4 << 20;
/// Each kept frame counts this much on top of its payload, so floods of
/// empty frames hit the limit too.
const EARLY_ITEM_COST: usize = 64;
/// How long a closing connection waits for the transport to shut down.
const SHUTDOWN_WAIT: Duration = Duration::from_secs(1);

pub fn timeout_for(method: &str) -> Duration {
    if method == "terminal.interrupt" {
        INTERRUPT_TIMEOUT
    } else {
        CALL_TIMEOUT
    }
}

#[derive(Debug)]
pub enum CallError {
    /// The core answered with an error.
    Rpc {
        kind: Option<String>,
        message: String,
        hint: Option<String>,
    },
    /// No reply in time. The call may still have reached the terminal, so it
    /// did not fail: the outcome is unknown.
    OutcomeUnknown,
    /// The connection is gone (before or while waiting).
    Lost,
}

impl CallError {
    pub fn kind(&self) -> Option<&str> {
        match self {
            CallError::Rpc { kind, .. } => kind.as_deref(),
            _ => None,
        }
    }
}

impl std::fmt::Display for CallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CallError::Rpc { message, hint, .. } => {
                f.write_str(message)?;
                if let Some(hint) = hint {
                    write!(f, " ({hint})")?;
                }
                Ok(())
            }
            CallError::OutcomeUnknown => f.write_str("outcome unknown, check the terminal"),
            CallError::Lost => f.write_str("lost the connection"),
        }
    }
}

impl std::error::Error for CallError {}

/// Why a connection ended.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Ended {
    /// The stream ended, or the app closed it.
    Closed,
    /// No frame for [`DEAD_LINK`].
    DeadLink,
    /// The core sent something that is not framed mode (an oversized frame).
    Protocol(String),
    Io(String),
}

pub enum StreamItem {
    Data(Vec<u8>),
    /// The core ended the stream; the payload says why.
    Closed(Value),
    /// The connection dropped.
    Lost,
}

#[derive(Default)]
struct Routes {
    closed: bool,
    replies: HashMap<u64, oneshot::Sender<Result<Value, Value>>>,
    streams: HashMap<u32, mpsc::UnboundedSender<StreamItem>>,
    /// Frames for a channel whose opening reply hasn't been handled yet.
    early: HashMap<u32, Vec<(usize, StreamItem)>>,
    early_bytes: usize,
    /// Channels the app stopped reading: their frames are dropped until the
    /// core acknowledges the CLOSE.
    discard: HashSet<u32>,
}

struct Shared {
    out: mpsc::UnboundedSender<Vec<u8>>,
    routes: Mutex<Routes>,
    next_id: AtomicU64,
    ended: watch::Sender<Option<Ended>>,
    reader: Mutex<Option<tokio::task::AbortHandle>>,
}

impl Shared {
    fn send(&self, channel: u32, kind: u8, payload: &[u8]) {
        let _ = self.out.send(wire::encode(channel, kind, payload));
    }

    /// Ends the connection once: fails waiting calls, tells every stream, and
    /// stops the writer even when the transport no longer takes writes.
    fn end(&self, why: Ended) {
        {
            let mut r = self.routes.lock().unwrap();
            if r.closed {
                return;
            }
            r.closed = true;
            for (_, tx) in r.streams.drain() {
                let _ = tx.send(StreamItem::Lost);
            }
            r.replies.clear();
            r.early.clear();
        }
        self.ended.send_replace(Some(why));
        if let Some(reader) = self.reader.lock().unwrap().take() {
            reader.abort();
        }
    }

    /// Hands a frame to its call or stream; an error ends the connection.
    fn route(&self, frame: wire::Frame) -> Result<(), String> {
        let mut r = self.routes.lock().unwrap();
        if frame.channel == 0 {
            if frame.kind != wire::DATA {
                return Ok(());
            }
            let Ok(msg) = serde_json::from_slice::<Value>(&frame.payload) else {
                return Ok(());
            };
            let Some(id) = msg.get("id").and_then(Value::as_u64) else {
                return Ok(());
            };
            if let Some(tx) = r.replies.remove(&id) {
                let _ = tx.send(match msg.get("error") {
                    Some(e) => Err(e.clone()),
                    None => Ok(msg.get("result").cloned().unwrap_or(Value::Null)),
                });
            }
            return Ok(());
        }
        let size = EARLY_ITEM_COST + frame.payload.len();
        let item = match frame.kind {
            wire::DATA => StreamItem::Data(frame.payload),
            wire::CLOSE => {
                StreamItem::Closed(serde_json::from_slice(&frame.payload).unwrap_or(Value::Null))
            }
            _ => return Ok(()),
        };
        let closing = matches!(item, StreamItem::Closed(_));
        if r.discard.contains(&frame.channel) {
            if closing {
                r.discard.remove(&frame.channel);
            }
            return Ok(());
        }
        match r.streams.get(&frame.channel) {
            Some(tx) => {
                let _ = tx.send(item);
                if closing {
                    r.streams.remove(&frame.channel);
                }
            }
            None if r.early_bytes + size <= EARLY_LIMIT => {
                r.early_bytes += size;
                r.early.entry(frame.channel).or_default().push((size, item));
            }
            None => return Err("too many frames for channels nobody opened".into()),
        }
        Ok(())
    }
}

/// One framed connection to a core. Cheap to clone; the connection closes when
/// the last clone is dropped or [`Client::close`] is called.
#[derive(Clone)]
pub struct Client {
    handle: Arc<Handle>,
}

struct Handle {
    shared: Arc<Shared>,
}

impl Drop for Handle {
    fn drop(&mut self) {
        self.shared.end(Ended::Closed);
    }
}

impl Client {
    /// Starts the reader and writer tasks on the current tokio runtime.
    /// `reader` must start at the first frame (after the hello reply).
    pub fn start<R, W>(reader: R, writer: W) -> Client
    where
        R: AsyncRead + Unpin + Send + 'static,
        W: AsyncWrite + Unpin + Send + 'static,
    {
        let (out, frames) = mpsc::unbounded_channel();
        let shared = Arc::new(Shared {
            out,
            routes: Mutex::new(Routes::default()),
            next_id: AtomicU64::new(1),
            ended: watch::channel(None).0,
            reader: Mutex::new(None),
        });
        tokio::spawn(write_frames(
            writer,
            frames,
            Arc::downgrade(&shared),
            shared.ended.subscribe(),
        ));
        let reading = tokio::spawn(read_frames(reader, shared.clone()));
        *shared.reader.lock().unwrap() = Some(reading.abort_handle());
        if shared.routes.lock().unwrap().closed {
            reading.abort();
        }
        Client {
            handle: Arc::new(Handle { shared }),
        }
    }

    fn shared(&self) -> &Arc<Shared> {
        &self.handle.shared
    }

    /// Calls a method with its usual timeout ([`timeout_for`]).
    pub async fn call(&self, method: &str, params: Value) -> Result<Value, CallError> {
        self.call_timeout(method, params, timeout_for(method)).await
    }

    pub async fn call_timeout(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, CallError> {
        let shared = self.shared();
        let id = shared.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        {
            let mut r = shared.routes.lock().unwrap();
            if r.closed {
                return Err(CallError::Lost);
            }
            r.replies.insert(id, tx);
        }
        let req = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        shared.send(0, wire::DATA, req.to_string().as_bytes());
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(Ok(v))) => Ok(v),
            Ok(Ok(Err(e))) => Err(CallError::Rpc {
                kind: e
                    .pointer("/data/kind")
                    .and_then(Value::as_str)
                    .map(str::to_owned),
                message: e
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("core call failed")
                    .to_owned(),
                hint: e
                    .pointer("/data/hint")
                    .and_then(Value::as_str)
                    .map(str::to_owned),
            }),
            Ok(Err(_)) => Err(CallError::Lost),
            Err(_) => {
                shared.routes.lock().unwrap().replies.remove(&id);
                Err(CallError::OutcomeUnknown)
            }
        }
    }

    /// `events.subscribe`: its reply (the snapshot: `seq`, `terminals`) and the event stream.
    pub async fn subscribe(&self, params: Value) -> Result<(Value, Stream), CallError> {
        let result = self.call("events.subscribe", params).await?;
        let Some(channel) = result.get("channel").and_then(Value::as_u64) else {
            return Err(CallError::Rpc {
                kind: None,
                message: "events.subscribe returned no channel".into(),
                hint: None,
            });
        };
        let stream = self.open_stream(channel as u32);
        Ok((result, stream))
    }

    /// Starts reading a channel a reply named, with whatever already arrived on it.
    pub fn open_stream(&self, channel: u32) -> Stream {
        let shared = self.shared().clone();
        let (tx, rx) = mpsc::unbounded_channel();
        {
            let mut r = shared.routes.lock().unwrap();
            r.discard.remove(&channel);
            let mut open = true;
            for (size, item) in r.early.remove(&channel).unwrap_or_default() {
                r.early_bytes -= size;
                open &= !matches!(item, StreamItem::Closed(_));
                let _ = tx.send(item);
            }
            if r.closed {
                let _ = tx.send(StreamItem::Lost);
            } else if open {
                r.streams.insert(channel, tx);
            }
        }
        Stream {
            channel,
            rx,
            shared,
        }
    }

    /// Sends one frame (keys, RESIZE, FOCUS, FACTS, CLOSE) as is.
    pub fn send(&self, channel: u32, kind: u8, payload: &[u8]) {
        self.shared().send(channel, kind, payload);
    }

    /// Resolves once the connection has ended, with why.
    pub async fn ended(&self) -> Ended {
        let mut rx = self.shared().ended.subscribe();
        let got = rx.wait_for(Option::is_some).await;
        got.map(|v| v.clone().unwrap()).unwrap_or(Ended::Closed)
    }

    pub fn is_ended(&self) -> bool {
        self.shared().routes.lock().unwrap().closed
    }

    /// Closes the connection: the writer shuts down, calls fail as lost.
    pub fn close(&self) {
        self.shared().end(Ended::Closed);
    }
}

/// One channel's frames. Data handed out by [`Stream::recv`] counts as
/// consumed: its CREDIT goes back to the core at once.
pub struct Stream {
    channel: u32,
    rx: mpsc::UnboundedReceiver<StreamItem>,
    shared: Arc<Shared>,
}

impl Stream {
    pub fn channel(&self) -> u32 {
        self.channel
    }

    pub async fn recv(&mut self) -> StreamItem {
        match self.rx.recv().await {
            Some(StreamItem::Data(d)) => {
                if !d.is_empty() {
                    self.shared
                        .send(self.channel, wire::CREDIT, &(d.len() as u32).to_le_bytes());
                }
                StreamItem::Data(d)
            }
            Some(item) => item,
            None => StreamItem::Lost,
        }
    }
}

impl Drop for Stream {
    /// An open stream detaches on the core (a viewer, a subscription); its
    /// frames still in flight are dropped until the core's CLOSE.
    fn drop(&mut self) {
        let mut r = self.shared.routes.lock().unwrap();
        if r.streams.remove(&self.channel).is_some() {
            r.discard.insert(self.channel);
            drop(r);
            self.shared.send(self.channel, wire::CLOSE, &[]);
        }
    }
}

async fn read_frames<R: AsyncRead + Unpin>(mut reader: R, shared: Arc<Shared>) {
    let why = loop {
        match tokio::time::timeout(DEAD_LINK, wire::read_frame(&mut reader)).await {
            Ok(Ok(Some(frame))) => {
                if let Err(e) = shared.route(frame) {
                    break Ended::Protocol(e);
                }
            }
            Ok(Ok(None)) => break Ended::Closed,
            Ok(Err(e)) if e.kind() == std::io::ErrorKind::InvalidData => {
                break Ended::Protocol(e.to_string());
            }
            Ok(Err(e)) => break Ended::Io(e.to_string()),
            Err(_) => break Ended::DeadLink,
        }
    };
    shared.end(why);
}

/// Writes queued frames until the connection ends. Ending interrupts a write
/// the transport is not taking, so a dead path never keeps the task (and the
/// transport) alive; frames still queued then are dropped.
async fn write_frames<W: AsyncWrite + Unpin>(
    mut writer: W,
    mut frames: mpsc::UnboundedReceiver<Vec<u8>>,
    shared: std::sync::Weak<Shared>,
    mut ended: watch::Receiver<Option<Ended>>,
) {
    let writing = async {
        while let Some(frame) = frames.recv().await {
            let written = async {
                writer.write_all(&frame).await?;
                writer.flush().await
            }
            .await;
            if let Err(e) = written {
                return Some(e);
            }
        }
        None
    };
    let failed = tokio::select! {
        failed = writing => failed,
        _ = ended.wait_for(Option::is_some) => None,
    };
    if let Some(e) = failed
        && let Some(shared) = shared.upgrade()
    {
        shared.end(Ended::Io(e.to_string()));
    }
    let _ = tokio::time::timeout(SHUTDOWN_WAIT, writer.shutdown()).await;
}
