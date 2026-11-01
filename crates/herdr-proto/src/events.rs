//! The event stream applied to its snapshot (schema/README.md, Events).
//!
//! Adapted from herdr-app `src/hc.rs` (`EventLines`, `Pushed`), Apache-2.0.
//! Changed: terminals the core lists for machines it added itself
//! (`machine/t7`) are dropped, since the phone reaches every machine directly
//! (machine-connection spec, "Each terminal is listed once"); a line that never
//! ends is dropped instead of growing without bound.

use serde_json::{Value, json};

/// Event streams are lines; DATA frame boundaries can fall anywhere in a line.
#[derive(Default)]
pub struct EventLines {
    pending: Vec<u8>,
    /// Inside a line longer than [`EventLines::MAX_LINE`]: dropping to its end.
    skipping: bool,
    /// The last event applied: older ones were in the snapshot.
    seq: u64,
    /// Every terminal the stream describes, in its order.
    terminals: Vec<Value>,
}

/// What a push changed.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Pushed {
    /// A layout document changed, or events were missed: read them again.
    pub docs: bool,
    pub terminals: bool,
}

/// A terminal on a machine the connected core added (`other/t3`).
fn elsewhere(address: &Value) -> bool {
    address.as_str().is_some_and(|a| a.contains('/'))
}

fn own(terminals: &Value) -> Vec<Value> {
    terminals
        .as_array()
        .into_iter()
        .flatten()
        .filter(|t| !elsewhere(&t["address"]))
        .cloned()
        .collect()
}

impl EventLines {
    /// The longest line kept (a `missed` snapshot is one line): a frame's worth.
    pub const MAX_LINE: usize = crate::wire::MAX_PAYLOAD;

    /// From `events.subscribe`'s reply: its `seq` and `terminals`.
    pub fn new(snapshot: &Value) -> Self {
        EventLines {
            seq: snapshot["seq"].as_u64().unwrap_or(0),
            terminals: own(&snapshot["terminals"]),
            ..Default::default()
        }
    }

    /// Every terminal, live and finished, in the core's order.
    pub fn terminals(&self) -> &[Value] {
        &self.terminals
    }

    /// The live terminals, as `terminal.list` puts them.
    pub fn live(&self) -> Value {
        let live: Vec<&Value> = self
            .terminals
            .iter()
            .filter(|t| t["state"] == "live")
            .collect();
        json!({ "terminals": live })
    }

    pub fn seq(&self) -> u64 {
        self.seq
    }

    /// Bytes of an unfinished line waiting for the rest.
    pub fn pending_bytes(&self) -> usize {
        self.pending.len()
    }

    pub fn push(&mut self, bytes: &[u8]) -> Pushed {
        let mut pushed = Pushed::default();
        let mut rest = bytes;
        while let Some(end) = rest.iter().position(|b| *b == b'\n') {
            let (head, tail) = (&rest[..end], &rest[end + 1..]);
            rest = tail;
            if std::mem::take(&mut self.skipping) {
                self.pending.clear();
                continue;
            }
            self.pending.extend_from_slice(head);
            let line = std::mem::take(&mut self.pending);
            if let Ok(line) = serde_json::from_slice::<Value>(&line) {
                self.apply_line(&line, &mut pushed);
            }
        }
        if !self.skipping {
            self.pending.extend_from_slice(rest);
            if self.pending.len() > Self::MAX_LINE {
                self.pending = Vec::new();
                self.skipping = true;
            }
        }
        pushed
    }

    fn apply_line(&mut self, line: &Value, pushed: &mut Pushed) {
        let p = &line["params"];
        match line["method"].as_str() {
            Some("missed") => {
                self.terminals = own(&p["terminals"]);
                self.seq = p["seq"].as_u64().unwrap_or(self.seq);
                pushed.docs = true;
                pushed.terminals = true;
            }
            Some("event") if p["seq"].as_u64().is_none_or(|s| s > self.seq) => {
                self.seq = p["seq"].as_u64().unwrap_or(self.seq);
                if p["type"] == "doc" {
                    pushed.docs = true;
                } else if !elsewhere(&p["terminal"]) {
                    pushed.terminals |= self.apply(p);
                }
            }
            _ => {}
        }
    }

    /// One event's change to the terminal it names.
    fn apply(&mut self, p: &Value) -> bool {
        let (kind, address, data) = (
            p["type"].as_str().unwrap_or_default(),
            &p["terminal"],
            &p["data"],
        );
        let at = self.terminals.iter().position(|t| &t["address"] == address);
        let whole = match kind {
            "created" | "restarted" => Some(data),
            "labels" => data.get("record"),
            _ => None,
        };
        if let Some(record) = whole {
            match at {
                Some(i) => self.terminals[i] = record.clone(),
                None => self.terminals.push(record.clone()),
            }
            return true;
        }
        let Some(i) = at else { return false };
        if kind == "removed" {
            self.terminals.remove(i);
            return true;
        }
        let t = &mut self.terminals[i];
        let mut set = |keys: &[&str]| keys.iter().for_each(|k| t[*k] = data[*k].clone());
        match kind {
            "finished" | "lost" => {
                set(&["reason", "exit_code", "signal", "finished_ms"]);
                t["state"] = if kind == "lost" { "live" } else { "finished" }.into();
                if kind == "lost" {
                    t["pid"] = Value::Null;
                }
                t.as_object_mut().map(|r| r.remove("foreground"));
            }
            "status" => set(&["status", "records", "reported"]),
            "turn" => set(&["turn"]),
            "title" => set(&["title"]),
            "labels" => set(&["labels"]),
            "keep" => set(&["keep"]),
            "size" => set(&["cols", "rows"]),
            "foreground" => t["foreground"] = data.clone(),
            _ => return false,
        }
        true
    }
}
