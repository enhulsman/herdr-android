//! One open terminal on the phone (M2.2, M2.3): attaches through its
//! machine's live connection, feeds the attach stream into a libghostty-vt
//! terminal and hands frames (format in `vt/mod.rs`) to the screen.
//!
//! - Watching attaches with `observe: true` and draws the desk's size; the
//!   first key, keys-row key or paste reattaches at the phone's size
//!   (`herdr_proto::viewer`). Each attach starts a fresh VT.
//! - A `size` event that is not the phone's own reattaches as a watcher.
//! - A flick scrolls the VT's own scrollback on the main screen; a full-screen
//!   program gets it as input instead (`vt/mouse.rs`), so it takes over like a
//!   key.
//! - After an observe attach the size from `terminal.get` is compared with the
//!   size before attaching; a change goes to the diagnostic log.
//! - A reconnect of the machine reattaches the terminal on the new
//!   connection. Dropping the command sender ends the task and detaches.

use std::sync::Arc;
use std::time::Duration;

use herdr_proto::rpc::{CallError, StreamItem};
use herdr_proto::viewer::{Attached, Mode, Viewer};
use serde_json::json;
use tokio::sync::{mpsc, watch};
use tokio::time::Instant;

use crate::hub::{Conn, HubCore};
use crate::keys_row::{Key, Mods};
use crate::vt;
pub use crate::{TermPhase, TermState};

/// Frames go out at most this often; output in between is coalesced.
const FRAME_GAP: Duration = Duration::from_millis(8);
/// A failed attach is tried again after this long, or on a reconnect.
const RETRY: Duration = Duration::from_secs(3);

/// What the screen asks of the open terminal.
#[derive(Debug)]
pub(crate) enum Cmd {
    /// A key press, typed or from the keys row, with its modifiers.
    Key(Key, Mods),
    /// Compose text, pasted without Enter.
    Paste(String),
    /// Scroll the view by rows; up is negative.
    Scroll(i32),
    /// Back to the bottom.
    Live,
    /// A tap on cell (col, row) of the phone's grid: a click for a program
    /// tracking the mouse, only while typing (watching draws the desk's size).
    Click(u16, u16),
    /// The phone's own grid size changed (keyboard shown or hidden).
    PhoneSize(u16, u16),
    /// `terminal.interrupt`; the report comes back through the sink.
    Interrupt,
}

/// Where frames, states and interrupt reports go, in order, from the task.
pub(crate) trait TermSink: Send + Sync + 'static {
    fn frame(&self, bytes: Vec<u8>);
    fn state(&self, state: TermState);
    fn interrupted(&self, report: String);
}

/// Opens `address` on `machine`. The terminal stays open until the returned
/// sender is dropped.
pub(crate) fn spawn(
    hub: &Arc<HubCore>,
    machine: &str,
    address: &str,
    phone: (u16, u16),
    sink: Arc<dyn TermSink>,
) -> mpsc::UnboundedSender<Cmd> {
    let (tx, rx) = mpsc::unbounded_channel();
    let task = Task {
        hub: hub.clone(),
        conns: hub.connection(machine),
        address: address.to_owned(),
        phone,
        cmds: rx,
        sink,
        size: (0, 0),
    };
    hub.runtime().spawn(task.run());
    tx
}

/// How one attach ended.
enum Over {
    /// The screen closed the terminal.
    Closed,
    /// The connection went away or changed: wait for the next one.
    Lost,
    /// The terminal ended or the attach failed: wait for a reconnect or retry.
    Pause,
}

struct Task {
    hub: Arc<HubCore>,
    conns: watch::Receiver<Option<Conn>>,
    address: String,
    phone: (u16, u16),
    cmds: mpsc::UnboundedReceiver<Cmd>,
    sink: Arc<dyn TermSink>,
    /// The size of the last frames, for states that do not change it.
    size: (u16, u16),
}

impl Task {
    fn state(&mut self, phase: TermPhase, size: Option<(u16, u16)>) {
        if let Some(size) = size {
            self.size = size;
        }
        self.sink.state(TermState {
            phase,
            cols: self.size.0,
            rows: self.size.1,
        });
    }

    async fn run(mut self) {
        loop {
            let Some(conn) = self.wait_for_connection().await else {
                return;
            };
            self.state(TermPhase::Attaching, None);
            let before = conn.sizes.borrow().get(&self.address).copied();
            let over = match Viewer::watch(&conn.client, &self.address, self.phone).await {
                Ok((viewer, attached)) => {
                    self.check_observe_size(&conn, before, &attached);
                    self.attached(&conn, viewer, attached, before).await
                }
                Err(e) => {
                    self.state(
                        TermPhase::Failed {
                            detail: e.to_string(),
                        },
                        None,
                    );
                    Over::Pause
                }
            };
            match over {
                Over::Closed => return,
                Over::Lost => {}
                Over::Pause => {
                    if !self.pause(conn.epoch).await {
                        return;
                    }
                }
            }
        }
    }

    /// The current connection, waiting for one; None when the screen closed.
    async fn wait_for_connection(&mut self) -> Option<Conn> {
        let mut said = false;
        loop {
            if let Some(conn) = self.conns.borrow_and_update().clone() {
                return Some(conn);
            }
            if !said {
                self.state(TermPhase::Waiting, None);
                said = true;
            }
            tokio::select! {
                changed = self.conns.changed() => {
                    if changed.is_err() {
                        return None;
                    }
                }
                cmd = self.cmds.recv() => match cmd {
                    None => return None,
                    // Without a connection only the phone's size is worth keeping.
                    Some(Cmd::PhoneSize(c, r)) if c > 0 && r > 0 => self.phone = (c, r),
                    Some(_) => {}
                },
            }
        }
    }

    /// Waits for a reconnect or the retry delay; false when the screen closed.
    async fn pause(&mut self, epoch: u64) -> bool {
        let until = Instant::now() + RETRY;
        loop {
            tokio::select! {
                _ = tokio::time::sleep_until(until) => return true,
                changed = self.conns.changed() => {
                    if changed.is_err() {
                        return false;
                    }
                    if self.conns.borrow().as_ref().map(|c| c.epoch) != Some(epoch) {
                        return true;
                    }
                }
                cmd = self.cmds.recv() => match cmd {
                    None => return false,
                    Some(Cmd::PhoneSize(c, r)) if c > 0 && r > 0 => self.phone = (c, r),
                    Some(_) => {}
                },
            }
        }
    }

    /// "Core behaviour drift" (terminal-view spec): an observe attach must not
    /// change the terminal's size.
    fn check_observe_size(&self, conn: &Conn, before: Option<(u16, u16)>, attached: &Attached) {
        let Some(before) = before else { return };
        let (client, address, hub) = (conn.client.clone(), self.address.clone(), self.hub.clone());
        let mode = attached.mode;
        self.hub.runtime().spawn(async move {
            if mode != Mode::Watching {
                return;
            }
            let Ok(t) = client
                .call("terminal.get", json!({"terminal": address}))
                .await
            else {
                return;
            };
            let dim = |k: &str| t[k].as_u64().and_then(|n| u16::try_from(n).ok());
            if let (Some(c), Some(r)) = (dim("cols"), dim("rows"))
                && (c, r) != before
            {
                hub.note(&format!(
                    "desk size changed on observe attach: {}x{} -> {c}x{r}",
                    before.0, before.1
                ));
            }
        });
    }

    fn fresh_vt(&mut self, attached: &Attached) -> Option<vt::Terminal> {
        match vt::Terminal::new(attached.cols, attached.rows) {
            Ok(t) => {
                let phase = match attached.mode {
                    Mode::Watching => TermPhase::Watching,
                    Mode::Typing => TermPhase::Typing,
                };
                self.state(phase, Some((attached.cols, attached.rows)));
                Some(t)
            }
            Err(e) => {
                self.state(
                    TermPhase::Failed {
                        detail: e.to_string(),
                    },
                    None,
                );
                None
            }
        }
    }

    /// Runs one attach. `seen` is the size the event stream reported when the
    /// attach was asked for: a size reported since then (even before the
    /// attach reply) is applied, so a resize during the attach is not lost.
    async fn attached(
        &mut self,
        conn: &Conn,
        mut viewer: Viewer,
        attached: Attached,
        mut seen: Option<(u16, u16)>,
    ) -> Over {
        let Some(mut vt) = self.fresh_vt(&attached) else {
            return Over::Pause;
        };
        let mut sizes = conn.sizes.clone();
        // Look at the current size once at the start, whatever was marked seen.
        sizes.mark_changed();
        let mut dirty = false;
        let mut next_frame = Instant::now();
        loop {
            tokio::select! {
                item = viewer.recv() => match item {
                    StreamItem::Data(d) => {
                        vt.write(&d);
                        dirty = true;
                    }
                    StreamItem::Closed(_) => {
                        self.flush(&mut vt, &mut dirty);
                        self.state(TermPhase::Ended { reason: "The terminal closed".into() }, None);
                        return Over::Pause;
                    }
                    StreamItem::Lost => {
                        // The connection ended, or a reattach failed.
                        if conn.client.is_ended() {
                            return Over::Lost;
                        }
                        self.state(TermPhase::Failed { detail: "The attach ended".into() }, None);
                        return Over::Pause;
                    }
                },
                changed = self.conns.changed() => {
                    if changed.is_err() {
                        return Over::Closed;
                    }
                    if self.conns.borrow().as_ref().map(|c| c.epoch) != Some(conn.epoch) {
                        return Over::Lost;
                    }
                }
                changed = sizes.changed() => {
                    if changed.is_err() {
                        continue;
                    }
                    let size = sizes.borrow_and_update().get(&self.address).copied();
                    if size == seen {
                        continue;
                    }
                    seen = size;
                    if let Some((c, r)) = size {
                        let before = (c, r);
                        match viewer.size_changed(c, r).await {
                            Ok(Some(a)) => {
                                self.check_observe_size(conn, Some(before), &a);
                                let Some(t) = self.fresh_vt(&a) else { return Over::Pause };
                                vt = t;
                                dirty = false;
                            }
                            Ok(None) => {}
                            Err(e) => return self.failed(e),
                        }
                    }
                }
                cmd = self.cmds.recv() => {
                    let Some(cmd) = cmd else { return Over::Closed };
                    match cmd {
                        Cmd::Key(key, mods) => {
                            let bytes = vt.encode_key(key, mods);
                            if let Err(over) = self.input(&mut viewer, &mut vt, &mut dirty, &bytes).await {
                                return over;
                            }
                        }
                        Cmd::Paste(text) => {
                            let bytes = vt::keys::paste_bytes(&text);
                            if let Err(over) = self.input(&mut viewer, &mut vt, &mut dirty, &bytes).await {
                                return over;
                            }
                        }
                        // A full-screen program scrolls itself, as on the desk; the
                        // main screen scrolls the phone's own scrollback.
                        Cmd::Scroll(rows) => match vt.program_scroll(rows) {
                            Some(bytes) => {
                                if let Err(over) = self.input(&mut viewer, &mut vt, &mut dirty, &bytes).await {
                                    return over;
                                }
                            }
                            None => {
                                vt.scroll(rows);
                                dirty = true;
                            }
                        },
                        Cmd::Live => {
                            vt.scroll_to_live();
                            dirty = true;
                        }
                        Cmd::Click(col, row) if viewer.mode() == Mode::Typing => {
                            if let Some(bytes) = vt.program_click(col, row)
                                && let Err(over) = self.input(&mut viewer, &mut vt, &mut dirty, &bytes).await
                            {
                                return over;
                            }
                        }
                        Cmd::Click(..) => {}
                        Cmd::PhoneSize(c, r) => {
                            if c > 0 && r > 0 {
                                self.phone = (c, r);
                            }
                            if viewer.resize_phone(c, r) && vt.resize(c, r).is_ok() {
                                self.state(TermPhase::Typing, Some((c, r)));
                                dirty = true;
                            }
                        }
                        Cmd::Interrupt => self.interrupt(conn),
                    }
                }
                _ = tokio::time::sleep_until(next_frame), if dirty => {
                    self.flush(&mut vt, &mut dirty);
                    next_frame = Instant::now() + FRAME_GAP;
                }
            }
        }
    }

    fn flush(&self, vt: &mut vt::Terminal, dirty: &mut bool) {
        if !*dirty {
            return;
        }
        *dirty = false;
        if let Ok(frame) = vt.frame() {
            self.sink.frame(frame);
        }
    }

    /// Typed input: back to the bottom, then through the viewer, which
    /// reattaches at the phone's size on the first input.
    async fn input(
        &mut self,
        viewer: &mut Viewer,
        vt: &mut vt::Terminal,
        dirty: &mut bool,
        bytes: &[u8],
    ) -> Result<(), Over> {
        if bytes.is_empty() {
            return Ok(());
        }
        vt.scroll_to_live();
        *dirty = true;
        match viewer.input(bytes).await {
            Ok(Some(a)) => {
                let Some(t) = self.fresh_vt(&a) else {
                    return Err(Over::Pause);
                };
                *vt = t;
                *dirty = false;
                Ok(())
            }
            Ok(None) => Ok(()),
            Err(e) => Err(self.failed(e)),
        }
    }

    fn failed(&mut self, e: CallError) -> Over {
        self.state(
            TermPhase::Failed {
                detail: e.to_string(),
            },
            None,
        );
        Over::Pause
    }

    fn interrupt(&self, conn: &Conn) {
        let (client, address, sink) =
            (conn.client.clone(), self.address.clone(), self.sink.clone());
        self.hub.runtime().spawn(async move {
            let r = client
                .call("terminal.interrupt", json!({"terminal": address}))
                .await;
            sink.interrupted(interrupt_report(r));
        });
    }
}

/// What the screen shows after an interrupt (terminal-input spec,
/// "Interrupt from the keys row").
fn interrupt_report(r: Result<serde_json::Value, CallError>) -> String {
    match r {
        Ok(v) => match v["outcome"].as_str() {
            Some("stopped") => "Stopped".into(),
            Some("exited") => "The program exited".into(),
            Some("still_working") => "Still working".into(),
            _ => match v["key"].as_str() {
                Some(key) => format!("Sent {key}, outcome unknown"),
                None => "Outcome unknown".into(),
            },
        },
        Err(CallError::OutcomeUnknown) => "Outcome unknown".into(),
        Err(e) => format!("Interrupt failed: {e}"),
    }
}
