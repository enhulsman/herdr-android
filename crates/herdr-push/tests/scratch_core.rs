//! The watcher against a real herdr-core (task M3.1): a scratch core of its own
//! (`HC_SESSION` and XDG dirs under a temp dir, never the user's core), the
//! watcher on its socket, pushes caught by a recorder instead of ntfy.
//!
//! The binary comes from `HERDR_CORE_BIN` (scripts/fetch-herdr-core.sh). Without
//! it the core tests say so and pass, unless `HERDR_REQUIRE_CORE` is set.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use herdr_proto::handshake;
use herdr_proto::rpc::Client;
use herdr_push::deliver::{Allowlist, Post};
use herdr_push::watch::{self, Settings, Watcher};
use herdr_push::webpush::base64url;
use serde_json::{Value, json};
use tokio::io::AsyncReadExt as _;
use tokio::process::{Child, Command};

const WAIT: Duration = Duration::from_secs(20);

// RFC 8291 Appendix A receiver keys: the recorder decrypts what it was sent.
const UA_PUBLIC: &str =
    "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
const UA_PRIVATE: &str = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
const AUTH: &str = "BTBZMqHH6r4Tts7J_aSIgg";
const ENDPOINT: &str = "https://ntfy.example.org/upTEST?up=1";

fn fresh_dir(path: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    std::fs::DirBuilder::new().mode(0o700).create(path)
}

fn scratch(name: &str) -> PathBuf {
    // Short: the core's sockets go under it (108-byte socket path limit).
    let dir = std::env::temp_dir().join(format!("hpw-{}-{name}", std::process::id()));
    fresh_dir(&dir).unwrap_or_else(|e| panic!("scratch dir {}: {e}", dir.display()));
    dir
}

struct Core {
    bin: PathBuf,
    dir: PathBuf,
    session: String,
    _connect: Option<Child>,
}

impl Core {
    /// A started scratch core, or None (and says why) without `HERDR_CORE_BIN`.
    async fn start(session: &str) -> Option<Core> {
        let Some(bin) = std::env::var_os("HERDR_CORE_BIN") else {
            assert!(
                std::env::var_os("HERDR_REQUIRE_CORE").is_none(),
                "HERDR_REQUIRE_CORE is set but HERDR_CORE_BIN is not"
            );
            eprintln!("skipped: HERDR_CORE_BIN is not set (scripts/fetch-herdr-core.sh)");
            return None;
        };
        let dir = scratch(session);
        std::fs::create_dir(dir.join("home")).unwrap();
        let mut core = Core {
            bin: std::fs::canonicalize(bin).unwrap(),
            dir,
            session: session.to_owned(),
            _connect: None,
        };
        // `connect` starts the core, as the phone's remote command would; the
        // watcher itself then uses only the socket.
        let mut child = Command::new(&core.bin)
            .arg("connect")
            .env_clear()
            .envs(core.env())
            .current_dir(core.dir.join("home"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let mut out = child.stdout.take().unwrap();
        let mut input = child.stdin.take().unwrap();
        handshake::handshake(&mut out, &mut input).await.unwrap();
        core._connect = Some(child);
        Some(core)
    }

    fn env(&self) -> Vec<(String, String)> {
        let d = |sub: &str| self.dir.join(sub).display().to_string();
        vec![
            ("HOME".into(), d("home")),
            ("PATH".into(), "/usr/bin:/bin".into()),
            ("SHELL".into(), "/bin/sh".into()),
            ("HC_SESSION".into(), self.session.clone()),
            ("XDG_RUNTIME_DIR".into(), d("r")),
            ("XDG_STATE_HOME".into(), d("s")),
            ("XDG_CONFIG_HOME".into(), d("c")),
        ]
    }

    fn socket(&self) -> PathBuf {
        self.dir.join(format!("r/herdr-core/{}.sock", self.session))
    }

    /// A client of our own on the socket, to set up documents and terminals.
    async fn client(&self) -> Client {
        let stream = tokio::net::UnixStream::connect(self.socket())
            .await
            .unwrap();
        let (mut r, mut w) = stream.into_split();
        let hello = handshake::handshake(&mut r, &mut w).await.unwrap();
        Client::start(std::io::Cursor::new(hello.leftover).chain(r), w)
    }
}

impl Drop for Core {
    fn drop(&mut self) {
        let _ = std::process::Command::new(&self.bin)
            .args(["server", "stop"])
            .env_clear()
            .envs(self.env())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

#[derive(Clone, Default)]
struct Recorder(Arc<Mutex<Vec<(String, String, Value)>>>);

impl Post for Recorder {
    fn post(&mut self, endpoint: &str, token: &str, body: Vec<u8>) -> Result<u16, String> {
        let keys = ece::EcKeyComponents::new(
            base64url(UA_PRIVATE).unwrap(),
            base64url(UA_PUBLIC).unwrap(),
        );
        let plain = ece::decrypt(&keys, &base64url(AUTH).unwrap(), &body).unwrap();
        let payload = serde_json::from_slice(&plain).unwrap();
        self.0
            .lock()
            .unwrap()
            .push((endpoint.into(), token.into(), payload));
        Ok(200)
    }
}

impl Recorder {
    fn types(&self) -> Vec<String> {
        self.0
            .lock()
            .unwrap()
            .iter()
            .map(|(_, _, p)| {
                let t = p["type"].as_str().unwrap_or("?");
                match p["state"].as_str() {
                    Some(s) => format!("{t} {s}"),
                    None => t.to_owned(),
                }
            })
            .collect()
    }

    async fn until(&self, want: &str) -> Value {
        let found = tokio::time::timeout(WAIT, async {
            loop {
                let hit = self.0.lock().unwrap().iter().find_map(|(_, _, p)| {
                    let t = p["type"].as_str().unwrap_or("?");
                    let label = match p["state"].as_str() {
                        Some(s) => format!("{t} {s}"),
                        None => t.to_owned(),
                    };
                    (label == want).then(|| p.clone())
                });
                if let Some(p) = hit {
                    return p;
                }
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        })
        .await;
        found.unwrap_or_else(|_| panic!("no {want} push; got {:?}", self.types()))
    }
}

fn settings(socket: PathBuf, state_dir: &Path) -> Settings {
    Settings {
        socket,
        allow: Allowlist::parse(&["https://ntfy.example.org".into()]).unwrap(),
        token: "tk_scratch".into(),
        grace_ms: 300,
        state_file: state_dir.join("state.json"),
    }
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .unwrap()
}

fn report(fields: &str) -> String {
    format!(r"printf '\033]7501;{fields}\033\\'")
}

#[test]
fn without_a_core_the_watcher_says_so_and_starts_none() {
    let dir = scratch("nocore");
    let socket = dir.join("r/herdr-core/default.sock");
    let err = runtime().block_on(async {
        let mut w = Watcher::new(
            settings(socket.clone(), &dir),
            Box::new(Recorder::default()),
        );
        w.connection().await.unwrap_err()
    });
    assert!(err.starts_with(watch::NO_CORE), "{err}");
    assert!(!socket.exists());
    assert!(!dir.join("r").exists(), "something made the runtime dir");
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn both_document_names_pass_the_cores_rule() {
    runtime().block_on(async {
        let Some(core) = Core::start("docs").await else {
            return;
        };
        let c = core.client().await;
        for name in ["hdroid-push", "hdroid-push-status"] {
            watch::write_doc(&c, name, json!({"x": 1}))
                .await
                .unwrap_or_else(|e| panic!("{name}: {e}"));
            let got = c.call("doc.get", json!({"name": name})).await.unwrap();
            assert_eq!(got["value"], json!({"x": 1}), "{name}");
        }
    });
}

#[test]
fn a_blocked_agent_alerts_clears_and_reports_its_status() {
    runtime().block_on(async {
        let Some(core) = Core::start("watch").await else {
            return;
        };
        let c = core.client().await;
        let phone = json!({"endpoint": ENDPOINT, "p256dh": UA_PUBLIC, "auth": AUTH,
                           "machine": "workstation"});
        watch::write_doc(&c, "hdroid-push", json!({"phones": [phone.clone()]}))
            .await
            .unwrap();

        let rec = Recorder::default();
        let w = Watcher::new(
            settings(core.socket(), &core.dir.join("home")),
            Box::new(rec.clone()),
        );
        let watching = tokio::spawn(async move {
            let mut w = w;
            w.connection().await
        });
        // A fresh install proves its path at once.
        rec.until("canary").await;

        let fifos = [core.dir.join("home/step1"), core.dir.join("home/step2")];
        for fifo in &fifos {
            assert!(
                std::process::Command::new("mkfifo")
                    .arg(fifo)
                    .status()
                    .unwrap()
                    .success()
            );
        }
        let script = format!(
            "{working}; read _ < {first}; {blocked}; read _ < {second}; {idle}; sleep 60",
            working = report("state=working:app=claude"),
            // msg is base64: "Run npm test?"; it must never reach a push.
            blocked = report("state=blocked:kind=permission:app=claude:msg=UnVuIG5wbSB0ZXN0Pw=="),
            idle = report("state=idle:app=claude"),
            first = fifos[0].display(),
            second = fifos[1].display(),
        );
        let run = c
            .call("terminal.run", json!({"argv": ["sh", "-c", script]}))
            .await
            .unwrap();
        let address = run["address"].as_str().unwrap().to_owned();
        let step = |n: usize| {
            let fifo = fifos[n].clone();
            tokio::task::spawn_blocking(move || std::fs::write(fifo, "\n").unwrap())
        };

        // Working is quiet; blocked alerts after the delay.
        tokio::time::sleep(Duration::from_millis(500)).await;
        step(0).await.unwrap();
        let alert = rec.until("alert blocked").await;
        let at = alert["at"].as_u64().unwrap();
        assert_eq!(
            alert,
            json!({"v": 1, "type": "alert", "machine": "workstation", "terminal": address,
                   "name": "Claude Code", "state": "blocked", "kind": "permission", "at": at})
        );

        // Answered: the alert clears, then the finish alerts.
        step(1).await.unwrap();
        rec.until("cleared").await;
        rec.until("alert done").await;
        for (endpoint, token, _) in rec.0.lock().unwrap().iter() {
            assert_eq!(
                (endpoint.as_str(), token.as_str()),
                (ENDPOINT, "tk_scratch")
            );
        }

        // "Send test alert" from the phone.
        let mut asked = phone.clone();
        asked["test"] = json!("1");
        watch::write_doc(&c, "hdroid-push", json!({"phones": [asked]}))
            .await
            .unwrap();
        rec.until("test").await;

        let status = tokio::time::timeout(WAIT, async {
            loop {
                let s = c
                    .call("doc.get", json!({"name": "hdroid-push-status"}))
                    .await
                    .unwrap();
                if s["value"]["endpoints"][ENDPOINT]["status"] == "accepted 200" {
                    return s["value"].clone();
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        })
        .await
        .expect("status document");
        assert_eq!(status["version"], env!("CARGO_PKG_VERSION"));
        assert_eq!(status["last_push_status"], 200);
        // The scratch core has no herdr-app layout: every agent may alert.
        assert_eq!(status["notes"], json!(["no layout document"]));

        let counts = rec.types();
        assert_eq!(
            counts.iter().filter(|t| *t == "alert blocked").count(),
            1,
            "{counts:?}"
        );
        watching.abort();
    });
}

/// Codex review, M3: an agent that blocked before any phone registered still
/// alerts the phone that registers later, once; a restart repeats nothing.
#[test]
fn a_phone_registering_after_the_block_still_gets_the_alert_once() {
    runtime().block_on(async {
        let Some(core) = Core::start("late").await else {
            return;
        };
        let c = core.client().await;
        let home = core.dir.join("home");
        let script = format!(
            "{blocked}; sleep 60",
            blocked = report("state=blocked:kind=permission:app=claude"),
        );
        c.call("terminal.run", json!({"argv": ["sh", "-c", script]}))
            .await
            .unwrap();

        let rec = Recorder::default();
        let w = Watcher::new(settings(core.socket(), &home), Box::new(rec.clone()));
        let watching = tokio::spawn(async move {
            let mut w = w;
            w.connection().await
        });
        // Past the 300 ms delay with nobody to tell.
        tokio::time::sleep(Duration::from_millis(1_500)).await;
        assert!(rec.types().is_empty(), "{:?}", rec.types());

        let phone = json!({"endpoint": ENDPOINT, "p256dh": UA_PUBLIC, "auth": AUTH,
                           "machine": "workstation"});
        watch::write_doc(&c, "hdroid-push", json!({"phones": [phone]}))
            .await
            .unwrap();
        let alert = rec.until("alert blocked").await;
        assert_eq!(alert["kind"], "permission");
        watching.abort();
        let _ = watching.await;

        // A restart: the phone already has it.
        let again = Recorder::default();
        let w = Watcher::new(settings(core.socket(), &home), Box::new(again.clone()));
        let watching = tokio::spawn(async move {
            let mut w = w;
            w.connection().await
        });
        tokio::time::sleep(Duration::from_millis(1_500)).await;
        let types = again.types();
        assert!(!types.contains(&"alert blocked".to_owned()), "{types:?}");
        watching.abort();
    });
}
