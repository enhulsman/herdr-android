//! The watcher's connection to its core (alerts spec, "The watcher only uses an
//! existing core"): the configured socket, never `herdr-core connect`, which
//! would start a core. Each connection subscribes to events, reads the layout
//! and phone documents, and turns what [`Rules`] decides into pushes.

use std::collections::{BTreeMap, BTreeSet};
use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use herdr_proto::compat::{self, Compat};
use herdr_proto::events::EventLines;
use herdr_proto::handshake;
use herdr_proto::rpc::{CallError, Client, Ended, StreamItem};
use serde_json::{Value, json};
use tokio::io::AsyncReadExt as _;

use crate::deliver::{self, Allowlist, Post, Push};
use crate::phones::{self, Phone};
use crate::rules::{Action, Layout, Rules};
use crate::status::{self, Status};
use herdr_proto::layout;

/// Between attempts to reach a core that is not there.
pub const RETRY: Duration = Duration::from_secs(30);
/// A push every 6 hours, so the phone can tell silence from quiet.
pub const CANARY_MS: u64 = 6 * 60 * 60 * 1000;
/// The layout document herdr-app writes.
pub const LAYOUT_DOC: &str = "herdr-app";
/// What the watcher calls on a core.
pub const USES: &[&str] = &["events.subscribe", "doc.get", "doc.set"];

pub const NO_CORE: &str = "no core";
pub const ZERO_TERMINALS: &str = "core has zero terminals";
pub const NO_PHONES: &str = "no phone registered";

pub struct Settings {
    pub socket: PathBuf,
    pub allow: Allowlist,
    pub token: String,
    pub grace_ms: u64,
    pub state_file: PathBuf,
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as u64)
}

fn log(line: &str) {
    eprintln!("herdr-push: {line}");
}

/// The host of an endpoint, for logs and notes: topics stay out of them.
fn host(endpoint: &str) -> String {
    endpoint
        .parse::<ureq::http::Uri>()
        .ok()
        .and_then(|u| u.host().map(str::to_owned))
        .unwrap_or_else(|| "?".into())
}

/// What survives a restart: the alert record, handled test requests, the
/// last canary.
fn load_state(path: &Path) -> Value {
    std::fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or(Value::Null)
}

fn save_state(path: &Path, state: &Value) -> std::io::Result<()> {
    use std::io::Write as _;
    use std::os::unix::fs::{DirBuilderExt as _, OpenOptionsExt as _};
    if let Some(dir) = path.parent() {
        std::fs::DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(dir)?;
    }
    let tmp = path.with_extension("tmp");
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(&tmp)?;
    f.write_all(state.to_string().as_bytes())?;
    f.sync_all()?;
    std::fs::rename(&tmp, path)
}

pub struct Watcher {
    settings: Settings,
    post: Box<dyn Post + Send>,
    rules: Rules,
    status: Status,
    /// Per endpoint, the last `test` value answered.
    tests: BTreeMap<String, Value>,
    /// Endpoints seen in `hdroid-push` before: a new one is sent the alerts
    /// still open, which went out before it registered.
    known: BTreeSet<String>,
    canary_at: Option<u64>,
    phones: Vec<Phone>,
    doc_notes: Vec<String>,
    zero_terminals: bool,
    status_written_at: Option<u64>,
    status_dirty: bool,
}

impl Watcher {
    pub fn new(settings: Settings, post: Box<dyn Post + Send>) -> Watcher {
        let state = load_state(&settings.state_file);
        let tests = state["tests"]
            .as_object()
            .map(|t| t.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
            .unwrap_or_default();
        // Missing in a state from before it was kept: the phones then present
        // count as new once, and get what is open again.
        let known = state["known"]
            .as_array()
            .map(|k| {
                k.iter()
                    .filter_map(|e| e.as_str().map(str::to_owned))
                    .collect()
            })
            .unwrap_or_default();
        Watcher {
            known,
            rules: Rules::new(settings.grace_ms, &state["rules"]),
            canary_at: state["canary_at"].as_u64(),
            settings,
            post,
            status: Status::default(),
            tests,
            phones: Vec::new(),
            doc_notes: Vec::new(),
            zero_terminals: false,
            status_written_at: None,
            status_dirty: true,
        }
    }

    /// Watches until the process ends, reconnecting after [`RETRY`].
    pub async fn run(mut self) {
        loop {
            match self.connection().await {
                Ok(why) => log(&format!("disconnected: {why}")),
                Err(why) => log(&why),
            }
            tokio::time::sleep(RETRY).await;
        }
    }

    fn save(&self) {
        let state = json!({
            "rules": self.rules.record(),
            "tests": self.tests,
            "known": self.known,
            "canary_at": self.canary_at,
        });
        if let Err(e) = save_state(&self.settings.state_file, &state) {
            log(&format!(
                "cannot save {}: {e}",
                self.settings.state_file.display()
            ));
        }
    }

    /// One connection: Ok with why it ended, Err when there was no usable core.
    pub async fn connection(&mut self) -> Result<String, String> {
        let socket = &self.settings.socket;
        let stream = tokio::net::UnixStream::connect(socket)
            .await
            .map_err(|e| format!("{NO_CORE} at {}: {e}", socket.display()))?;
        let (mut r, mut w) = stream.into_split();
        let hello = handshake::handshake(&mut r, &mut w)
            .await
            .map_err(|e| format!("{NO_CORE}: hello failed: {e}"))?;
        match compat::check(&hello.result) {
            Compat::Ok | Compat::Missing { .. } => {}
            other => return Err(format!("core not usable: {other:?}")),
        }
        let methods = &hello.result["methods"];
        if let Some(m) = USES.iter().find(|m| {
            !methods
                .as_array()
                .is_some_and(|a| a.iter().any(|x| x == **m))
        }) {
            return Err(format!("core lacks {m}"));
        }
        let version = hello.result["version"].as_str().unwrap_or("?").to_owned();
        let client = Client::start(Cursor::new(hello.leftover).chain(r), w);
        let (snapshot, mut events) = client
            .subscribe(json!({}))
            .await
            .map_err(|e| format!("subscribe failed: {e}"))?;
        log(&format!("watching herdr-core {version}"));
        let mut lines = EventLines::new(&snapshot);
        self.read_docs(&client).await;
        self.observe(&lines, &client).await;

        loop {
            let now = now_ms();
            let wake = [
                self.rules.next_due(),
                Some(self.canary_due()),
                Some(self.status_due()),
            ]
            .into_iter()
            .flatten()
            .min()
            .unwrap_or(now);
            let sleep = Duration::from_millis(wake.saturating_sub(now));
            tokio::select! {
                item = events.recv() => match item {
                    StreamItem::Data(d) => {
                        let pushed = lines.push(&d);
                        if pushed.docs {
                            self.read_docs(&client).await;
                        }
                        if pushed.docs || pushed.terminals {
                            self.observe(&lines, &client).await;
                        }
                    }
                    StreamItem::Closed(_) => return Ok("herdr-core ended the event stream".into()),
                    StreamItem::Lost => {
                        return Ok(match client.ended().await {
                            Ended::DeadLink => "no answer for 45 s".into(),
                            Ended::Closed => "the connection closed".into(),
                            Ended::Protocol(e) | Ended::Io(e) => e,
                        });
                    }
                },
                _ = tokio::time::sleep(sleep) => {}
            }
            let now = now_ms();
            let due = self.rules.due(now);
            self.act(due);
            if self.canary_due() <= now {
                self.push(&Push::Canary, None);
                self.canary_at = Some(now);
                self.save();
            }
            self.write_status(&client).await;
        }
    }

    fn canary_due(&self) -> u64 {
        // Never sent: now, so a fresh install proves its path at once.
        self.canary_at.map_or(0, |at| at + CANARY_MS)
    }

    fn status_due(&self) -> u64 {
        self.status_written_at
            .map_or(0, |at| at + status::REFRESH_MS)
    }

    async fn observe(&mut self, lines: &EventLines, client: &Client) {
        let actions = self.rules.observe(lines.terminals(), now_ms());
        self.act(actions);
        let zero = lines.live()["terminals"]
            .as_array()
            .is_none_or(|a| a.is_empty());
        if zero != self.zero_terminals {
            self.zero_terminals = zero;
            self.refresh_notes();
        }
        self.write_status(client).await;
    }

    fn act(&mut self, actions: Vec<Action>) {
        if actions.is_empty() {
            return;
        }
        for action in actions {
            self.push(&push_of(action), None);
        }
        self.save();
    }

    /// Sends to every registered phone, or only to `only`.
    fn push(&mut self, push: &Push, only: Option<&str>) {
        let phones: Vec<Phone> = self
            .phones
            .iter()
            .filter(|p| only.is_none_or(|e| p.subscription.endpoint == e))
            .cloned()
            .collect();
        let at = now_ms();
        let (allow, token) = (&self.settings.allow, &self.settings.token);
        let post = &mut self.post;
        let outcomes = tokio::task::block_in_place(|| {
            deliver::send(push, &phones, allow, token, post.as_mut(), at)
        });
        let what = match push {
            Push::Alert {
                terminal, state, ..
            } => format!("alert {terminal} {}", state.as_str()),
            Push::Cleared { terminal } => format!("cleared {terminal}"),
            Push::Canary => "canary".into(),
            Push::Test => "test".into(),
        };
        for (endpoint, outcome) in &outcomes {
            log(&format!("{what} to {}: {outcome:?}", host(endpoint)));
        }
        self.status_dirty |= self.status.record(&outcomes, at);
    }

    async fn read_docs(&mut self, client: &Client) {
        let layout = match client.call("doc.get", json!({"name": LAYOUT_DOC})).await {
            Ok(r) => layout::placed(&r["value"]),
            Err(e) => Layout::Unreadable(format!("{} ({e})", layout::UNREADABLE)),
        };
        self.rules.set_layout(layout.clone());
        let (phones, mut notes) = match client.call("doc.get", json!({"name": phones::DOC})).await {
            Ok(r) => phones::phones(&r["value"]),
            Err(e) => (
                Vec::new(),
                vec![format!("{} unreadable ({e})", phones::DOC)],
            ),
        };
        if let Layout::Unreadable(why) = layout {
            notes.push(why);
        }
        for p in &phones {
            if !self.settings.allow.allows(&p.subscription.endpoint) {
                notes.push(format!(
                    "ignored phone on {}: host not allowed",
                    host(&p.subscription.endpoint)
                ));
            }
        }
        self.phones = phones;
        self.doc_notes = notes;
        self.status_dirty |= self
            .status
            .keep_endpoints(self.phones.iter().map(|p| p.subscription.endpoint.as_str()));
        self.refresh_notes();

        // A phone new here missed the alerts that are still open: it gets them now.
        let fresh: Vec<String> = self
            .phones
            .iter()
            .map(|p| p.subscription.endpoint.clone())
            .filter(|e| !self.known.contains(e))
            .collect();
        let current: BTreeSet<String> = self
            .phones
            .iter()
            .map(|p| p.subscription.endpoint.clone())
            .collect();
        if !fresh.is_empty() || self.known != current {
            for endpoint in &fresh {
                for action in self.rules.open_alerts() {
                    self.push(&push_of(action), Some(endpoint));
                }
            }
            self.known = current;
            self.save();
        }

        // "Send test alert": each new value asks for one test push to that phone.
        let asked: Vec<(String, Value)> = self
            .phones
            .iter()
            .filter_map(|p| Some((p.subscription.endpoint.clone(), p.test.clone()?)))
            .filter(|(e, t)| self.tests.get(e) != Some(t))
            .collect();
        for (endpoint, test) in asked {
            self.push(&Push::Test, Some(&endpoint));
            self.tests.insert(endpoint, test);
            self.save();
        }
    }

    fn refresh_notes(&mut self) {
        let mut notes = self.doc_notes.clone();
        if self.phones.is_empty() {
            notes.push(NO_PHONES.into());
        }
        if self.zero_terminals {
            notes.push(ZERO_TERMINALS.into());
        }
        self.status_dirty |= self.status.set_notes(notes);
    }

    async fn write_status(&mut self, client: &Client) {
        let now = now_ms();
        if !self.status_dirty && self.status_due() > now {
            return;
        }
        match write_doc(client, status::DOC, self.status.doc()).await {
            Ok(()) => {
                self.status_dirty = false;
                self.status_written_at = Some(now);
            }
            Err(e) => {
                log(&format!("cannot write {}: {e}", status::DOC));
                // Try again at the next wake, not in a tight loop.
                self.status_written_at = Some(now.saturating_sub(status::REFRESH_MS) + 60_000);
            }
        }
    }
}

fn push_of(action: Action) -> Push {
    match action {
        Action::Alert {
            terminal,
            name,
            state,
            kind,
        } => Push::Alert {
            terminal,
            name,
            state,
            kind,
        },
        Action::Cleared { terminal } => Push::Cleared { terminal },
    }
}

/// `doc.set` at the current version, rereading once on a conflict.
pub async fn write_doc(client: &Client, name: &str, value: Value) -> Result<(), CallError> {
    let mut tries = 0;
    loop {
        let version = client.call("doc.get", json!({"name": name})).await?["version"]
            .as_u64()
            .unwrap_or(0);
        let set = json!({"name": name, "value": value, "version": version});
        match client.call("doc.set", set).await {
            Err(CallError::Rpc { kind: Some(k), .. }) if k == "conflict" && tries == 0 => {
                tries += 1;
            }
            other => return other.map(|_| ()),
        }
    }
}
