//! The protocol code against a real herdr-core (task M1.2): `herdr-core connect`
//! started as a child, as the remote command would, on a scratch core of its
//! own (`HC_SESSION` and XDG dirs under a temp dir, never the user's core).
//!
//! The binary comes from `HERDR_CORE_BIN` (scripts/fetch-herdr-core.sh, pinned
//! or latest). Without it every test says so and passes, unless
//! `HERDR_REQUIRE_CORE` is set (CI's core jobs set both).

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use herdr_proto::attention::{Attention, Tracker};
use herdr_proto::compat::{self, Compat};
use herdr_proto::events::EventLines;
use herdr_proto::handshake::{self, Hello};
use herdr_proto::rpc::{Client, Ended, Stream, StreamItem};
use herdr_proto::viewer::{Mode, Viewer};
use herdr_proto::wire;
use serde_json::{Value, json};
use tokio::io::AsyncReadExt as _;
use tokio::process::{Child, Command};

/// Long enough for a debug build on a loaded CI runner.
const WAIT: Duration = Duration::from_secs(20);

/// Makes a new directory only this user can enter. One that already exists
/// (left over, or made by someone else) is an error: a config inside it could
/// point the scratch core at the user's own.
fn fresh_dir(path: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    std::fs::DirBuilder::new().mode(0o700).create(path)
}

#[test]
fn an_existing_scratch_dir_is_refused() {
    let dir = std::env::temp_dir().join(format!("hcp-{}-pre", std::process::id()));
    std::fs::create_dir(&dir).unwrap();
    let again = fresh_dir(&dir);
    std::fs::remove_dir(&dir).unwrap();
    assert_eq!(again.unwrap_err().kind(), std::io::ErrorKind::AlreadyExists);
}

/// One scratch core: its own session name and dirs. Dropping it stops the core
/// (with the same environment, so only this one) and removes the dirs.
struct Core {
    bin: PathBuf,
    dir: PathBuf,
    session: String,
    /// The core's `$SHELL`: what it starts with no argv, and what the phone's
    /// wrapper and check exec.
    shell: String,
    /// Put before the core's PATH (stub programs).
    path_first: Option<PathBuf>,
}

impl Core {
    /// None (and says why) without `HERDR_CORE_BIN`.
    fn new(session: &str) -> Option<Core> {
        let Some(bin) = std::env::var_os("HERDR_CORE_BIN") else {
            assert!(
                std::env::var_os("HERDR_REQUIRE_CORE").is_none(),
                "HERDR_REQUIRE_CORE is set but HERDR_CORE_BIN is not"
            );
            eprintln!("skipped: HERDR_CORE_BIN is not set (scripts/fetch-herdr-core.sh)");
            return None;
        };
        // Short: the core's sockets go under it (108-byte socket path limit).
        let dir = std::env::temp_dir().join(format!("hcp-{}-{session}", std::process::id()));
        fresh_dir(&dir).unwrap_or_else(|e| panic!("scratch dir {}: {e}", dir.display()));
        std::fs::create_dir(dir.join("home")).unwrap();
        Some(Core {
            bin: std::fs::canonicalize(bin).unwrap(),
            dir,
            session: session.to_owned(),
            shell: "/bin/sh".into(),
            path_first: None,
        })
    }

    /// The scratch core's home, where a test puts rc files.
    fn home(&self) -> PathBuf {
        self.dir.join("home")
    }

    fn env(&self) -> Vec<(String, String)> {
        let d = |sub: &str| self.dir.join(sub).display().to_string();
        vec![
            ("HOME".into(), d("home")),
            ("PATH".into(), self.path()),
            ("SHELL".into(), self.shell.clone()),
            ("HC_SESSION".into(), self.session.clone()),
            ("XDG_RUNTIME_DIR".into(), d("r")),
            ("XDG_STATE_HOME".into(), d("s")),
            ("XDG_CONFIG_HOME".into(), d("c")),
            // Heartbeats every 15 s by default; the test waits for one.
            ("HC_HEARTBEAT_MS".into(), "300".into()),
        ]
    }

    fn path(&self) -> String {
        match &self.path_first {
            Some(first) => format!("{}:/usr/bin:/bin", first.display()),
            None => "/usr/bin:/bin".into(),
        }
    }

    /// A command that runs a shell line (given as its last argument) in this
    /// core's environment: a machine added `via` it reaches this core.
    fn via_target(&self) -> String {
        let bin_dir = self.bin.parent().unwrap().display().to_string();
        let mut vars: Vec<String> = self
            .env()
            .into_iter()
            .filter(|(k, _)| k != "PATH")
            .map(|(k, v)| format!("{k}={v}"))
            .collect();
        vars.push(format!("PATH={bin_dir}:/usr/bin:/bin"));
        format!("env -i {} sh -c", vars.join(" "))
    }

    /// Where the core listens: `$XDG_RUNTIME_DIR/herdr-core/<session>.sock`.
    fn socket(&self) -> PathBuf {
        self.dir.join(format!("r/herdr-core/{}.sock", self.session))
    }

    fn version(&self) -> String {
        let out = std::process::Command::new(&self.bin)
            .arg("--version")
            .output()
            .unwrap();
        String::from_utf8_lossy(&out.stdout).trim().to_owned()
    }

    /// `herdr-core connect` (which starts the core), the hello, and a client.
    async fn connect(&self) -> Connection {
        let mut child = Command::new(&self.bin)
            .arg("connect")
            .env_clear()
            .envs(self.env())
            .current_dir(self.dir.join("home"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let mut out = child.stdout.take().unwrap();
        let mut input = child.stdin.take().unwrap();
        let hello = handshake::handshake(&mut out, &mut input).await.unwrap();
        let Hello { result, leftover } = hello;
        let client = Client::start(std::io::Cursor::new(leftover).chain(out), input);
        Connection {
            hello: result,
            client,
            _child: child,
        }
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

struct Connection {
    hello: Value,
    client: Client,
    _child: Child,
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .unwrap()
}

/// The subscription's lines applied until `done` holds for them, or panics
/// after [`WAIT`] with what it saw.
async fn until(
    events: &mut Stream,
    lines: &mut EventLines,
    mut done: impl FnMut(&EventLines) -> bool,
) {
    let waited = tokio::time::timeout(WAIT, async {
        while !done(lines) {
            match events.recv().await {
                StreamItem::Data(d) => {
                    lines.push(&d);
                }
                other => panic!("event stream ended: {}", describe(&other)),
            }
        }
    })
    .await;
    assert!(
        waited.is_ok(),
        "timed out; terminals: {:#}",
        Value::from(lines.terminals().to_vec())
    );
}

fn describe(item: &StreamItem) -> String {
    match item {
        StreamItem::Data(d) => format!("{} bytes", d.len()),
        StreamItem::Closed(v) => format!("closed {v}"),
        StreamItem::Lost => "lost".into(),
    }
}

fn find<'a>(lines: &'a EventLines, address: &str) -> Option<&'a Value> {
    lines.terminals().iter().find(|t| t["address"] == address)
}

/// OSC 7501 for a shell's printf: fields joined by `:`, title/msg base64.
fn report(fields: &str) -> String {
    format!(r"printf '\033]7501;{fields}\033\\'")
}

#[test]
fn hello_names_the_cores_version_and_passes_the_compat_check() {
    let Some(core) = Core::new("hello") else {
        return;
    };
    let version = core.version();
    runtime().block_on(async {
        let c = core.connect().await;
        let have = c.hello["version"].as_str().unwrap().to_owned();
        assert!(
            version.contains(&have),
            "--version {version:?}, hello {have}"
        );
        assert_eq!(c.hello["generation"], compat::GENERATION);
        assert_eq!(c.hello["framed"], true);
        // Tested against itself it is fine; it is at least the oldest supported.
        assert_eq!(compat::check(&c.hello), Compat::Ok);
        for method in compat::USES {
            assert!(
                c.hello["methods"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|m| m == method),
                "{method} missing"
            );
        }
        // A call in framed mode after the hello.
        let status = c.client.call("server.status", json!({})).await.unwrap();
        assert_eq!(status["version"], have.as_str());
    });
}

/// A program reporting through OSC 7501 moves its row from Working to Needs
/// you (with its permission message) to Done, and the heartbeat keeps coming.
#[test]
fn status_reports_drive_the_rows_through_events() {
    let Some(core) = Core::new("status") else {
        return;
    };
    runtime().block_on(async {
        let c = core.connect().await;
        let (snapshot, mut events) = c.client.subscribe(json!({})).await.unwrap();
        let mut lines = EventLines::new(&snapshot);
        let mut tracker = Tracker::default();
        // First contact: nothing yet, so nothing is counted as seen already.
        tracker.observe("box", lines.terminals(), 0);

        // One FIFO per step: with a single FIFO, the shell's second `read` could
        // open it while the first writer was still closing, hit end-of-file at
        // once and run to idle without the second step, so `blocked` was never
        // reported and the wait for it timed out on a loaded runner.
        let fifos = [core.dir.join("home/step1"), core.dir.join("home/step2")];
        let script = format!(
            "{working}; read _ < {first}; {blocked}; read _ < {second}; {idle}; sleep 60",
            working = report("state=working:app=claude"),
            // msg is base64: "Run npm test?"
            blocked = report("state=blocked:kind=permission:app=claude:msg=UnVuIG5wbSB0ZXN0Pw=="),
            idle = report("state=idle:app=claude"),
            first = fifos[0].display(),
            second = fifos[1].display(),
        );
        for fifo in &fifos {
            assert!(
                std::process::Command::new("mkfifo")
                    .arg(fifo)
                    .status()
                    .unwrap()
                    .success()
            );
        }
        let run = c
            .client
            .call("terminal.run", json!({"argv": ["sh", "-c", script]}))
            .await
            .unwrap();
        let address = run["address"].as_str().unwrap().to_owned();
        let state_is = |lines: &EventLines, word: &str| {
            find(lines, &address).is_some_and(|t| t.pointer("/status/state") == Some(&json!(word)))
        };
        let step = |n: usize| {
            let fifo = fifos[n].clone();
            tokio::task::spawn_blocking(move || std::fs::write(fifo, "\n").unwrap())
        };

        until(&mut events, &mut lines, |l| state_is(l, "working")).await;
        tracker.observe("box", lines.terminals(), 1);
        let row = tracker
            .rows()
            .into_iter()
            .find(|r| r.address == address)
            .unwrap();
        assert_eq!(row.attention, Attention::Working);
        assert_eq!(row.name, "Claude Code");

        step(0).await.unwrap();
        until(&mut events, &mut lines, |l| state_is(l, "blocked")).await;
        tracker.observe("box", lines.terminals(), 2);
        let row = tracker
            .rows()
            .into_iter()
            .find(|r| r.address == address)
            .unwrap();
        assert_eq!(row.attention, Attention::NeedsYou);
        assert_eq!(row.label, "Permission: Run npm test?");
        assert!(row.since_ms.is_some());

        step(1).await.unwrap();
        until(&mut events, &mut lines, |l| {
            state_is(l, "idle") && find(l, &address).is_some_and(|t| t["turn"] == 1)
        })
        .await;
        tracker.observe("box", lines.terminals(), 3);
        let row = tracker
            .rows()
            .into_iter()
            .find(|r| r.address == address)
            .unwrap();
        assert_eq!(row.attention, Attention::Done, "working to idle, unseen");

        // Heartbeats (every 300 ms here) carry the current seq and change nothing.
        // A sixth event once arrived before the heartbeat on a CI runner (seq 6
        // against 5; not seen locally, kind unknown, logged below if it comes
        // again), so each line is pushed on its own and only the heartbeat's
        // line is held to that.
        let (beat, seq_before, seq_after) = tokio::time::timeout(WAIT, async {
            loop {
                let StreamItem::Data(d) = events.recv().await else {
                    panic!("event stream ended")
                };
                for line in d.split_inclusive(|b| *b == b'\n') {
                    let before = lines.seq();
                    let pushed = lines.push(line);
                    // A heartbeat split across reads is skipped; the next will do.
                    let text = String::from_utf8_lossy(line);
                    if line.ends_with(b"\n") && text.contains("\"heartbeat\"") {
                        return (pushed, before, lines.seq());
                    }
                    if lines.seq() != before {
                        eprintln!("event before the heartbeat: {}", text.trim_end());
                    }
                }
            }
        })
        .await
        .expect("a heartbeat within the wait");
        assert!(!beat.terminals);
        assert_eq!(seq_after, seq_before);
    });
}

/// Long output: 5 MiB through an attach stream arrives whole, because the
/// client returns CREDIT as it reads (the core starts with 256 KiB).
#[test]
fn five_mib_of_output_reach_the_end_through_an_attach_stream() {
    let Some(core) = Core::new("attach") else {
        return;
    };
    const SIZE: usize = 5 << 20;
    runtime().block_on(async {
        let c = core.connect().await;
        // Waits for a line, so the viewer is attached before the output starts.
        let script = format!("read _; head -c {SIZE} /dev/zero | tr '\\0' x; printf END; sleep 60");
        let run = c
            .client
            .call("terminal.run", json!({"argv": ["sh", "-c", script]}))
            .await
            .unwrap();
        let address = run["address"].as_str().unwrap();
        let attach = c
            .client
            .call(
                "terminal.attach",
                json!({"terminal": address, "cols": 120, "rows": 40}),
            )
            .await
            .unwrap();
        let channel = attach["channel"].as_u64().unwrap() as u32;
        let mut stream = c.client.open_stream(channel);
        c.client.send(channel, wire::DATA, b"go\r");

        let mut xs = 0usize;
        let mut tail: Vec<u8> = Vec::new();
        let done = tokio::time::timeout(Duration::from_secs(60), async {
            loop {
                match stream.recv().await {
                    StreamItem::Data(d) => {
                        xs += d.iter().filter(|b| **b == b'x').count();
                        tail.extend_from_slice(&d);
                        let keep = tail.len().saturating_sub(16);
                        tail.drain(..keep);
                        if tail.windows(3).any(|w| w == b"END") {
                            return;
                        }
                    }
                    other => panic!("attach stream ended: {}", describe(&other)),
                }
            }
        })
        .await;
        assert!(done.is_ok(), "stalled after {xs} of {SIZE} bytes");
        assert_eq!(xs, SIZE, "a repaint replaced part of the output");
    });
}

/// `server.stop` answers, then the connection ends and the core is gone.
#[test]
fn server_stop_ends_the_connection() {
    let Some(core) = Core::new("stop") else {
        return;
    };
    runtime().block_on(async {
        let c = core.connect().await;
        let stopped = c.client.call("server.stop", json!({})).await.unwrap();
        assert_eq!(stopped["stopped"], true);
        let ended = tokio::time::timeout(WAIT, c.client.ended()).await.unwrap();
        assert_eq!(ended, Ended::Closed);
        // Nothing listens on the scratch socket any more. (Not `server
        // status`: on Linux it starts a core when none runs.)
        let socket = core.socket();
        let gone = tokio::time::timeout(WAIT, async {
            while std::os::unix::net::UnixStream::connect(&socket).is_ok() {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        })
        .await;
        assert!(
            gone.is_ok(),
            "the core still listens at {}",
            socket.display()
        );
    });
}

/// A core with an added machine lists that machine's terminals as `other/t1`;
/// the phone reaches every machine itself, so it drops them (machine-connection
/// spec, "Each terminal is listed once").
#[test]
fn terminals_of_a_machine_the_core_added_are_skipped() {
    let Some(core) = Core::new("dup") else {
        return;
    };
    let Some(other) = Core::new("dupo") else {
        return;
    };
    runtime().block_on(async {
        let c = core.connect().await;
        c.client
            .call(
                "machine.add",
                json!({"name": "other", "via": other.via_target(), "temporary": true}),
            )
            .await
            .unwrap();
        let mine = c
            .client
            .call("terminal.run", json!({"argv": ["sleep", "60"]}))
            .await
            .unwrap();
        let theirs = c
            .client
            .call(
                "terminal.run",
                json!({"argv": ["sleep", "60"], "on": "other"}),
            )
            .await
            .unwrap();
        let theirs = theirs["address"].as_str().unwrap().to_owned();
        assert!(theirs.starts_with("other/"), "{theirs}");

        let (snapshot, mut events) = c.client.subscribe(json!({})).await.unwrap();
        let listed: Vec<&Value> = snapshot["terminals"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| &t["address"])
            .collect();
        assert!(
            listed.contains(&&json!(theirs)),
            "the core lists {listed:?}"
        );
        let mut lines = EventLines::new(&snapshot);
        assert!(find(&lines, &theirs).is_none());
        assert!(find(&lines, mine["address"].as_str().unwrap()).is_some());

        // Events for it are skipped too: one more there, then one here.
        let late = c
            .client
            .call(
                "terminal.run",
                json!({"argv": ["sleep", "60"], "on": "other"}),
            )
            .await
            .unwrap();
        let here = c
            .client
            .call("terminal.run", json!({"argv": ["sleep", "60"]}))
            .await
            .unwrap();
        let here = here["address"].as_str().unwrap().to_owned();
        until(&mut events, &mut lines, |l| find(l, &here).is_some()).await;
        assert!(find(&lines, late["address"].as_str().unwrap()).is_none());
        assert!(
            lines
                .terminals()
                .iter()
                .all(|t| !t["address"].as_str().unwrap().contains('/'))
        );
    });
    drop(core);
}

/// `terminal.get`'s size for a terminal.
async fn size_of(client: &Client, address: &str) -> (u64, u64) {
    let t = client
        .call("terminal.get", json!({"terminal": address}))
        .await
        .unwrap();
    (t["cols"].as_u64().unwrap(), t["rows"].as_u64().unwrap())
}

/// Reads the attach stream until `marker` has arrived; returns all of it.
async fn read_until(viewer: &mut Viewer, marker: &[u8]) -> Vec<u8> {
    let mut seen = Vec::new();
    let got = tokio::time::timeout(WAIT, async {
        while !seen.windows(marker.len()).any(|w| w == marker) {
            match viewer.recv().await {
                StreamItem::Data(d) => seen.extend_from_slice(&d),
                other => panic!("attach stream ended: {}", describe(&other)),
            }
        }
    })
    .await;
    assert!(
        got.is_ok(),
        "no {:?} in {:?}",
        String::from_utf8_lossy(marker),
        String::from_utf8_lossy(&seen)
    );
    seen
}

/// The size rule end to end (task M2.1; terminal-view "Opening an agent",
/// terminal-input "Typing on the phone", "Back at the desk", "Desk colours
/// kept"): a desk viewer at 180x50 with its own colours and cell size, the
/// phone watching, typing, and the desk typing again.
#[test]
fn the_desk_keeps_its_size_and_colours_until_the_phone_types() {
    let Some(core) = Core::new("size") else {
        return;
    };
    runtime().block_on(async {
        let desk = core.connect().await;
        let phone = core.connect().await;
        let (snapshot, mut events) = phone.client.subscribe(json!({})).await.unwrap();
        let mut lines = EventLines::new(&snapshot);

        // Waits for the phone's line, asks the terminal for its colours and
        // cell size (answered by the core from the size leader's display
        // facts), prints the answers with ESC as `E`, then waits for the desk.
        let script = concat!(
            "read _; stty raw -echo; ",
            r"printf '\033]10;?\033\\\033]11;?\033\\\033[16t'; sleep 1; ",
            "dd bs=4096 count=1 2>/dev/null | tr '\\033' E; ",
            r"printf '\r\nEND\r\n'; stty sane; read _; sleep 60"
        );
        let run = desk
            .client
            .call("terminal.run", json!({"argv": ["sh", "-c", script]}))
            .await
            .unwrap();
        let address = run["address"].as_str().unwrap().to_owned();
        let display =
            json!({"fg": "#102030", "bg": "#405060", "cursor": "#708090", "cell": [11, 23]});
        let attach = desk
            .client
            .call(
                "terminal.attach",
                json!({"terminal": address, "cols": 180, "rows": 50, "display": display}),
            )
            .await
            .unwrap();
        let desk_channel = attach["channel"].as_u64().unwrap() as u32;
        let _desk_stream = desk.client.open_stream(desk_channel);
        assert_eq!(size_of(&phone.client, &address).await, (180, 50));

        // Watching: drawn at the desk's size, which stays.
        let (mut viewer, attached) = Viewer::watch(&phone.client, &address, (60, 30))
            .await
            .unwrap();
        assert_eq!(
            (attached.cols, attached.rows, attached.mode),
            (180, 50, Mode::Watching)
        );
        assert_eq!(size_of(&phone.client, &address).await, (180, 50));

        // Typing: the phone's size, the desk's colours and cell size.
        let attached = viewer.input(b"go\r").await.unwrap().expect("reattached");
        assert_eq!(
            (attached.cols, attached.rows, attached.mode),
            (60, 30, Mode::Typing)
        );
        assert_eq!(size_of(&phone.client, &address).await, (60, 30));
        let answers = read_until(&mut viewer, b"END").await;
        let answers = String::from_utf8_lossy(&answers);
        assert!(answers.contains("10;rgb:1010/2020/3030"), "fg: {answers:?}");
        assert!(answers.contains("11;rgb:4040/5050/6060"), "bg: {answers:?}");
        assert!(answers.contains("E[6;23;11t"), "cell size: {answers:?}");
        until(&mut events, &mut lines, |l| {
            find(l, &address).is_some_and(|t| t["cols"] == 60)
        })
        .await;
        assert_eq!(
            viewer.size_changed(60, 30).await.unwrap(),
            None,
            "its own size"
        );

        // Back at the desk: its input takes its size back; the phone watches again.
        desk.client.send(desk_channel, wire::DATA, b"ok\r");
        until(&mut events, &mut lines, |l| {
            find(l, &address).is_some_and(|t| t["cols"] == 180)
        })
        .await;
        assert_eq!(size_of(&phone.client, &address).await, (180, 50));
        let attached = viewer
            .size_changed(180, 50)
            .await
            .unwrap()
            .expect("reattached");
        assert_eq!(
            (attached.cols, attached.rows, attached.mode),
            (180, 50, Mode::Watching)
        );
        assert_eq!(
            size_of(&phone.client, &address).await,
            (180, 50),
            "watching again"
        );

        // Leaving: the phone holds no viewer, the desk keeps leading.
        drop(viewer);
        desk.client
            .send(desk_channel, wire::RESIZE, &[100, 0, 40, 0]);
        until(&mut events, &mut lines, |l| {
            find(l, &address).is_some_and(|t| t["cols"] == 100)
        })
        .await;
        assert_eq!(size_of(&phone.client, &address).await, (100, 40));
    });
}

// ---- starting terminals and agents (start-agents task 2.1) ----
//
// Real shells with their rc files in the scratch home, never stub scripts in
// their place: what the phone relies on is how zsh and bash treat aliases and
// `-c`. zsh is skipped (and says so) where it is not installed, unless
// HERDR_REQUIRE_ZSH is set (CI installs it).

use herdr_proto::start;

const ZSH: &str = "/usr/bin/zsh";
const BASH: &str = "/bin/bash";

fn have(shell: &str) -> bool {
    if Path::new(shell).exists() {
        return true;
    }
    assert!(
        std::env::var_os("HERDR_REQUIRE_ZSH").is_none() || shell != ZSH,
        "HERDR_REQUIRE_ZSH is set but {shell} is missing"
    );
    eprintln!("skipped for {shell}: not installed");
    false
}

fn write_exe(path: &Path, text: &str) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, text).unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).unwrap();
}

/// A scratch core running `shell`, whose rc makes `claude` an alias for a
/// marker program that notes its folder in `$HOME/ran` and exits 0.
fn core_with_alias(session: &str, shell: &str) -> Option<Core> {
    let mut core = Core::new(session)?;
    core.shell = shell.to_owned();
    let home = core.home();
    let marker = home.join("bin/marker");
    write_exe(&marker, "#!/bin/sh\npwd > \"$HOME/ran\"\nexit 0\n");
    let alias = format!("alias claude={}\n", marker.display());
    std::fs::write(home.join(".zshrc"), &alias).unwrap();
    std::fs::write(home.join(".bashrc"), &alias).unwrap();
    std::fs::create_dir_all(home.join("src/website")).unwrap();
    Some(core)
}

/// Polls `check` until it holds, or panics after [`WAIT`].
async fn eventually<T>(what: &str, mut check: impl AsyncFnMut() -> Option<T>) -> T {
    let deadline = tokio::time::Instant::now() + WAIT;
    loop {
        if let Some(v) = check().await {
            return v;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "timed out waiting for {what}"
        );
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

async fn record(client: &Client, address: &str) -> Value {
    client
        .call("terminal.get", json!({"terminal": address}))
        .await
        .unwrap()
}

#[test]
fn a_new_terminal_runs_the_shell_in_the_folder_labelled_and_kept() {
    let Some(mut core) = Core::new("newterm") else {
        return;
    };
    core.shell = BASH.into();
    let folder = core.home().join("src/website");
    std::fs::create_dir_all(&folder).unwrap();
    let folder = folder.display().to_string();
    runtime().block_on(async {
        let c = core.connect().await;
        let run = c
            .client
            .call("terminal.run", start::run_params(Some(&folder), "s1", None))
            .await
            .unwrap();
        let address = run["address"].as_str().unwrap();
        let rec = record(&c.client, address).await;
        assert_eq!(rec["labels"][start::SPACE_LABEL], "s1", "{rec:#}");
        assert_eq!(rec["keep"], true, "{rec:#}");
        assert_eq!(rec["cwd"], folder.as_str(), "{rec:#}");
        assert_eq!(rec["argv"][0], BASH, "{rec:#}");
    });
}

#[test]
fn a_missing_folder_is_spawn_failed_and_starts_nothing() {
    let Some(core) = Core::new("nofolder") else {
        return;
    };
    let gone = core.home().join("gone").display().to_string();
    runtime().block_on(async {
        let c = core.connect().await;
        let before = c.client.call("terminal.list", json!({})).await.unwrap();
        let err = c
            .client
            .call(
                "terminal.run",
                start::run_params(Some(&gone), "s1", Some("claude")),
            )
            .await
            .unwrap_err();
        assert_eq!(err.kind(), Some("spawn_failed"), "{err:?}");
        let after = c.client.call("terminal.list", json!({})).await.unwrap();
        assert_eq!(before, after);
    });
}

/// The shortcut runs the person's alias in the folder, and their shell stays.
fn agent_start_runs_the_alias_and_the_shell_stays(shell: &str, session: &str) {
    if !have(shell) {
        return;
    }
    let Some(core) = core_with_alias(session, shell) else {
        return;
    };
    let home = core.home();
    let folder = home.join("src/website").display().to_string();
    let name = Path::new(shell)
        .file_name()
        .unwrap()
        .to_str()
        .unwrap()
        .to_owned();
    runtime().block_on(async {
        let c = core.connect().await;
        let run = c
            .client
            .call(
                "terminal.run",
                start::run_params(Some(&folder), "s1", Some("claude")),
            )
            .await
            .unwrap();
        let address = run["address"].as_str().unwrap().to_owned();
        let ran = home.join("ran");
        let pwd = eventually("the alias's marker", async || {
            std::fs::read_to_string(&ran).ok()
        })
        .await;
        assert_eq!(pwd.trim(), folder);
        // The marker exited 0: the person's shell takes its place, same folder.
        let rec = eventually("the shell in the foreground", async || {
            let rec = record(&c.client, &address).await;
            (rec.pointer("/foreground/name") == Some(&json!(name))).then_some(rec)
        })
        .await;
        assert_eq!(rec["state"], "live", "{rec:#}");
        assert_eq!(rec["foreground"]["cwd"], folder.as_str(), "{rec:#}");
        assert_eq!(rec["keep"], true);
        assert_eq!(rec["labels"][start::SPACE_LABEL], "s1");
    });
}

#[test]
fn agent_start_with_zsh() {
    agent_start_runs_the_alias_and_the_shell_stays(ZSH, "agzsh");
}

#[test]
fn agent_start_with_bash() {
    agent_start_runs_the_alias_and_the_shell_stays(BASH, "agbash");
}

async fn check(c: &Connection, words: &[&str]) -> start::Check {
    let result = c
        .client
        .call("machine.exec", start::check_params(words))
        .await
        .unwrap();
    start::parse_exec_result(&result, words)
}

fn check_finds_aliases_and_home(shell: &str, session: &str) {
    if !have(shell) {
        return;
    }
    let Some(core) = core_with_alias(session, shell) else {
        return;
    };
    let home = core.home().display().to_string();
    runtime().block_on(async {
        let c = core.connect().await;
        // `claude` first: nothing may take it for the script's own name.
        let got = check(&c, &["claude", "nope", "sh"]).await;
        assert_eq!(got.home.as_deref(), Some(home.as_str()));
        assert_eq!(got.found, Some(vec!["claude".to_owned(), "sh".to_owned()]));
    });
}

#[test]
fn check_with_zsh() {
    check_finds_aliases_and_home(ZSH, "chzsh");
}

#[test]
fn check_with_bash() {
    check_finds_aliases_and_home(BASH, "chbash");
}

#[test]
fn a_shell_that_cannot_run_the_check_gives_home_and_no_agents() {
    let Some(mut core) = Core::new("chfail") else {
        return;
    };
    // As fish does with `sh` syntax: complains and exits non-zero.
    let shell = core.home().join("bin/notsh");
    write_exe(
        &shell,
        "#!/bin/sh\necho 'notsh: Unsupported use of do' >&2\nexit 127\n",
    );
    core.shell = shell.display().to_string();
    let home = core.home().display().to_string();
    runtime().block_on(async {
        let c = core.connect().await;
        let got = check(&c, &["claude"]).await;
        assert_eq!(got.home.as_deref(), Some(home.as_str()));
        assert_eq!(got.found, None);
    });
}

#[test]
fn a_check_that_hangs_times_out_as_a_failure() {
    let Some(mut core) = Core::new("chslow") else {
        return;
    };
    let shell = core.home().join("bin/slowsh");
    write_exe(&shell, "#!/bin/sh\nsleep 30\n");
    core.shell = shell.display().to_string();
    runtime().block_on(async {
        let c = core.connect().await;
        let started = std::time::Instant::now();
        let got = check(&c, &["claude"]).await;
        assert_eq!(got.found, None);
        assert!(
            started.elapsed() < Duration::from_secs(15),
            "{:?}",
            started.elapsed()
        );
    });
}

#[test]
fn on_macos_the_check_and_the_agent_use_a_login_shell() {
    if !have(BASH) {
        return;
    }
    let Some(mut core) = core_with_alias("darwin", BASH) else {
        return;
    };
    let home = core.home();
    let stubs = home.join("stubs");
    write_exe(&stubs.join("uname"), "#!/bin/sh\necho Darwin\n");
    // A shell that notes its first flag, then is bash.
    let shell = stubs.join("loginsh");
    write_exe(
        &shell,
        &format!("#!/bin/sh\necho \"$1\" >> \"$HOME/flags\"\nexec {BASH} \"$@\"\n"),
    );
    core.shell = shell.display().to_string();
    core.path_first = Some(stubs);
    // A login bash reads .bash_profile, not .bashrc.
    std::fs::copy(home.join(".bashrc"), home.join(".bash_profile")).unwrap();
    let folder = home.join("src/website").display().to_string();
    let flags = home.join("flags");
    runtime().block_on(async {
        let c = core.connect().await;
        let got = check(&c, &["claude"]).await;
        assert_eq!(got.found, Some(vec!["claude".to_owned()]));
        assert_eq!(std::fs::read_to_string(&flags).unwrap(), "-lic\n");
        std::fs::remove_file(&flags).unwrap();
        c.client
            .call(
                "terminal.run",
                start::run_params(Some(&folder), "s1", Some("claude")),
            )
            .await
            .unwrap();
        let noted = eventually("the shell after the agent", async || {
            std::fs::read_to_string(&flags)
                .ok()
                .filter(|f| f.lines().count() == 2)
        })
        .await;
        assert_eq!(noted, "-lic\n-li\n");
    });
}
