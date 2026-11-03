//! The phone as a viewer of one terminal, and the size rule (design.md,
//! "The size rule"; terminal-view and terminal-input specs).
//!
//! - Watching attaches with `observe: true` and never sends input, RESIZE,
//!   FOCUS or FACTS on that attach: the terminal keeps the desk's size.
//! - The first input reattaches as an active viewer at the phone's size with
//!   `display` fields all null, so the desk's colours and cell size stay
//!   (herdr-core's `set_display` keeps what it knew for a null field).
//! - A `size` event that does not match what the phone draws means someone
//!   else changed the size: the phone attaches afresh as a watcher, and the
//!   caller starts a new VT from the first paint. Its next input takes the
//!   phone's size again, as herdr's own rule does between screens.
//! - Dropping the viewer detaches (CLOSE), so leaving the terminal stops leading.
//!
//! Every attach hands back an [`Attached`]: the caller discards its VT and
//! builds a fresh one at that size, then feeds it what [`Viewer::recv`] returns.

use crate::rpc::{CallError, Client, Stream, StreamItem};
use crate::wire;
use serde_json::{Value, json};

/// Lines of history each attach asks for (terminal-view spec, "Scrollback").
pub const HISTORY: usize = 2000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// Observe attach: the phone draws the terminal at its real size.
    Watching,
    /// Active attach at the phone's size: the phone typed.
    Typing,
}

/// A fresh attach: discard the VT and start one at `cols` x `rows`.
#[derive(Clone, Debug, PartialEq)]
pub struct Attached {
    pub cols: u16,
    pub rows: u16,
    pub mode: Mode,
    /// The terminal's record from the attach reply.
    pub terminal: Value,
}

pub struct Viewer {
    client: Client,
    terminal: String,
    phone: (u16, u16),
    mode: Mode,
    size: (u16, u16),
    /// None between a detach and the next attach's reply (or after it failed).
    stream: Option<Stream>,
    /// Sizes the phone sent as RESIZE whose `size` event has not come back
    /// yet, oldest first: their events are the phone's own, not the desk's.
    pending: std::collections::VecDeque<(u16, u16)>,
}

/// More unacknowledged phone resizes than this and the oldest are forgotten.
const PENDING_MAX: usize = 8;

/// `display` for the active attach: every field null keeps the desk's.
fn null_display() -> Value {
    json!({"fg": null, "bg": null, "cursor": null, "cell": null})
}

fn size_of_record(record: &Value, fallback: (u16, u16)) -> (u16, u16) {
    let get = |k: &str| {
        record[k]
            .as_u64()
            .and_then(|n| u16::try_from(n).ok())
            .filter(|n| *n > 0)
    };
    match (get("cols"), get("rows")) {
        (Some(c), Some(r)) => (c, r),
        _ => fallback,
    }
}

async fn attach(
    client: &Client,
    terminal: &str,
    mode: Mode,
    phone: (u16, u16),
) -> Result<(Stream, Attached), CallError> {
    // The core requires a size on every attach; an observer's never counts.
    let mut params = json!({
        "terminal": terminal,
        "observe": mode == Mode::Watching,
        "history": HISTORY,
        "cols": phone.0,
        "rows": phone.1,
    });
    if mode == Mode::Typing {
        params["display"] = null_display();
    }
    let reply = client.call("terminal.attach", params).await?;
    let Some(channel) = reply["channel"]
        .as_u64()
        .and_then(|c| u32::try_from(c).ok())
    else {
        return Err(CallError::Rpc {
            kind: None,
            message: "terminal.attach returned no channel".into(),
            hint: None,
        });
    };
    let stream = client.open_stream(channel);
    let record = reply["terminal"].clone();
    let fallback = if mode == Mode::Typing { phone } else { (0, 0) };
    let (cols, rows) = size_of_record(&record, fallback);
    if cols == 0 {
        return Err(CallError::Rpc {
            kind: None,
            message: "terminal.attach returned no size".into(),
            hint: None,
        });
    }
    Ok((
        stream,
        Attached {
            cols,
            rows,
            mode,
            terminal: record,
        },
    ))
}

impl Viewer {
    /// Opens a terminal to watch. `phone` is the phone's own size in cells,
    /// used once the phone types.
    pub async fn watch(
        client: &Client,
        terminal: &str,
        phone: (u16, u16),
    ) -> Result<(Viewer, Attached), CallError> {
        let (stream, attached) = attach(client, terminal, Mode::Watching, phone).await?;
        let viewer = Viewer {
            client: client.clone(),
            terminal: terminal.to_owned(),
            phone,
            mode: Mode::Watching,
            size: (attached.cols, attached.rows),
            stream: Some(stream),
            pending: std::collections::VecDeque::new(),
        };
        Ok((viewer, attached))
    }

    pub fn mode(&self) -> Mode {
        self.mode
    }

    /// The size the phone draws (its VT's size).
    pub fn size(&self) -> (u16, u16) {
        self.size
    }

    /// The attach stream: first paint, then live output. `Lost` when a
    /// reattach failed.
    pub async fn recv(&mut self) -> StreamItem {
        match &mut self.stream {
            Some(s) => s.recv().await,
            None => StreamItem::Lost,
        }
    }

    /// Detaches the current attach (its CLOSE goes before the new attach on
    /// the same connection) and attaches afresh.
    async fn reattach(&mut self, mode: Mode) -> Result<Attached, CallError> {
        let (stream, attached) = attach(&self.client, &self.terminal, mode, self.phone).await?;
        self.stream = Some(stream);
        self.mode = mode;
        self.size = (attached.cols, attached.rows);
        self.pending.clear();
        Ok(attached)
    }

    /// The phone's own size changed (the keyboard showed or hid). While
    /// typing it goes to the core as RESIZE on the active attach and the
    /// caller resizes its VT (`true`); while watching it is only kept for the
    /// next active attach. A zero or unchanged size does nothing.
    pub fn resize_phone(&mut self, cols: u16, rows: u16) -> bool {
        if cols == 0 || rows == 0 || (cols, rows) == self.phone {
            return false;
        }
        self.phone = (cols, rows);
        if self.mode != Mode::Typing {
            return false;
        }
        let Some(stream) = &self.stream else {
            return false;
        };
        let payload = [cols.to_le_bytes(), rows.to_le_bytes()].concat();
        self.client.send(stream.channel(), wire::RESIZE, &payload);
        self.size = (cols, rows);
        if self.pending.len() == PENDING_MAX {
            self.pending.pop_front();
        }
        self.pending.push_back((cols, rows));
        true
    }

    /// The core's `size` event for this terminal. The phone's own size
    /// change needs nothing; any other means a fresh VT: `Some` after
    /// attaching again, as a watcher.
    pub async fn size_changed(
        &mut self,
        cols: u16,
        rows: u16,
    ) -> Result<Option<Attached>, CallError> {
        // The phone's own resize coming back (this one, or an earlier one the
        // phone has since replaced): it and everything older are acknowledged.
        if let Some(i) = self.pending.iter().position(|p| *p == (cols, rows)) {
            self.pending.drain(..=i);
            return Ok(None);
        }
        if (cols, rows) == self.size {
            return Ok(None);
        }
        self.detach_current();
        self.reattach(Mode::Watching).await.map(Some)
    }

    /// Takes over at the phone's size without input (a tap to read and click):
    /// while watching (or after a failed reattach) it reattaches as an active
    /// viewer (`Some`: start a fresh VT); while typing it does nothing.
    pub async fn lead(&mut self) -> Result<Option<Attached>, CallError> {
        if self.mode != Mode::Watching {
            return Ok(None);
        }
        self.detach_current();
        Ok(Some(self.reattach(Mode::Typing).await?))
    }

    /// Keys, a keys-row key or a compose paste, already encoded. The first
    /// input while watching (or after a failed reattach) reattaches as an
    /// active viewer at the phone's size (`Some`: start a fresh VT), then the
    /// bytes go to the terminal. Empty input is not input: nothing happens.
    pub async fn input(&mut self, bytes: &[u8]) -> Result<Option<Attached>, CallError> {
        if bytes.is_empty() {
            return Ok(None);
        }
        let attached = self.lead().await?;
        if let Some(stream) = &self.stream {
            self.client.send(stream.channel(), wire::DATA, bytes);
        }
        Ok(attached)
    }

    /// Ends the current attach now (dropping its stream sends CLOSE), before
    /// the next one is asked for. Until an attach succeeds the phone leads
    /// nothing, so it counts as watching: the next input attaches actively.
    fn detach_current(&mut self) {
        self.stream = None;
        self.mode = Mode::Watching;
    }
}
