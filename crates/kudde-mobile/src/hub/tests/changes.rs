//! Task 2.5: the Changes view's hub object against a scripted core
//! (changes-view design D5; spec "The screen follows the agent while it
//! shows", "Outside a git repository the screen says so").

use super::*;
use crate::hub::changes::{
    Changes, ChangesDiff, ChangesEntry, ChangesList, ChangesView, exec_of, folder_of, wants_read,
};
use base64::Engine;
use kudde_changes::{Base, Shape, build};
use herdr_proto::rpc::CallError;
use std::sync::Arc;

const ADDR: &str = "t1";
const TOP: &str = "/home/dev/src/website";
const SUB: u32 = 7;

fn exec_out(stdout: &[u8], code: i32) -> Value {
    let b64 = base64::engine::general_purpose::STANDARD.encode(stdout);
    json!({"stdout": b64, "stderr": "", "code": code, "timed_out": false,
           "truncated": false, "ms": 30})
}

fn terminal(fg_cwd: &str) -> Value {
    json!({"address": ADDR, "cwd": TOP, "state": "live",
           "foreground": {"pid": 42, "name": "claude", "argv": ["claude"], "cwd": fg_cwd}})
}

impl Core {
    /// Answers `events.subscribe` for the terminal on channel [`SUB`].
    async fn changes_subscribed(&mut self) {
        let req = self.expect("events.subscribe").await;
        assert_eq!(req["params"]["terminal"], ADDR, "{req}");
        self.reply(&req, json!({"channel": SUB, "seq": 0, "terminals": []}))
            .await;
    }

    /// The next request is `machine.exec` with exactly these calls' params.
    async fn exec(&mut self, shape: Shape<'_>, cwd: &str, stdout: &[u8], code: i32) {
        let req = self.expect("machine.exec").await;
        let want = build(shape, cwd).remove(0).params();
        assert_eq!(req["params"], want, "{req}");
        self.reply(&req, exec_out(stdout, code)).await;
    }

    async fn term_event(&mut self, kind: &str) {
        let line = json!({"jsonrpc": "2.0", "method": "event",
                          "params": {"seq": 1, "type": kind, "terminal": ADDR, "data": {}}});
        self.send(SUB, wire::DATA, format!("{line}\n").as_bytes())
            .await;
    }

    /// One read of a repository whose status is `status` (untracked only:
    /// no counts call) and whose grep finds `grep`.
    async fn serves_read(&mut self, fg: &str, status: &[u8], grep: Option<&[u8]>) {
        let req = self.expect("terminal.get").await;
        assert_eq!(req["params"]["terminal"], ADDR);
        self.reply(&req, terminal(fg)).await;
        self.exec(Shape::Top, fg, format!("{TOP}\n--\n").as_bytes(), 0)
            .await;
        self.exec(Shape::Head, TOP, b"sha1\nabc\n", 0).await;
        self.exec(Shape::Status, TOP, status, 0).await;
        if let Some(out) = grep {
            let req = self.expect("machine.exec").await;
            assert_eq!(req["params"]["argv"][11], "grep", "{req}");
            self.reply(&req, exec_out(out, 0)).await;
        }
    }

    /// No request arrives within 10 s (under the hub's 45 s dead-link limit).
    async fn quiet(&mut self) {
        let got = tokio::time::timeout(Duration::from_secs(10), self.request()).await;
        assert!(got.is_err(), "unexpected request: {:?}", got.unwrap());
    }
}

async fn connected(r: &mut Rig) -> (Core, Arc<Changes>) {
    r.add_trusted(WS, WS_KEY);
    r.hub.resume();
    let mut core = r.core().await;
    core.connect("0.1.15", vec![agent(ADDR, "blocked", 1)])
        .await;
    r.until_state(WS, connected_state).await;
    let ch = Arc::new(Changes::new(r.hub.clone(), WS.into(), ADDR.into()));
    (core, ch)
}

fn connected_state(s: &MachineState) -> bool {
    matches!(s, MachineState::Connected { .. })
}

fn spawn<T: Send + 'static>(
    f: impl Future<Output = T> + Send + 'static,
) -> tokio::task::JoinHandle<T> {
    tokio::spawn(f)
}

fn new_file(path: &str, n: u64) -> ChangesEntry {
    ChangesEntry {
        path: path.into(),
        kind: "new".into(),
        added: Some(n),
        removed: Some(0),
        opens: true,
    }
}

#[test]
fn the_folder_is_the_foreground_processs() {
    assert_eq!(
        folder_of(&terminal("/home/dev/src/website/app")).as_deref(),
        Some("/home/dev/src/website/app")
    );
    let mut t = terminal("");
    assert_eq!(folder_of(&t).as_deref(), Some(TOP));
    t["foreground"] = Value::Null;
    assert_eq!(folder_of(&t).as_deref(), Some(TOP));
    assert_eq!(folder_of(&json!({})), None);
}

#[test]
fn exec_replies() {
    let spawn_failed = CallError::Rpc {
        kind: Some("spawn_failed".into()),
        message: "spawn_failed".into(),
        hint: None,
    };
    assert_eq!(exec_of(Err(spawn_failed)), kudde_changes::Exec::SpawnFailed);
    assert!(matches!(
        exec_of(Err(CallError::Lost)),
        kudde_changes::Exec::Error(_)
    ));
    assert!(matches!(
        exec_of(Ok(exec_out(b"x", 0))),
        kudde_changes::Exec::Ran { code: Some(0), .. }
    ));
}

#[test]
fn which_events_read() {
    let line = |kind: &str| {
        format!(
            "{}\n",
            json!({"jsonrpc": "2.0", "method": "event", "params": {"type": kind, "terminal": ADDR}})
        )
    };
    assert!(wants_read(line("status").as_bytes()));
    assert!(wants_read(line("turn").as_bytes()));
    assert!(wants_read(line("missed").as_bytes()));
    assert!(!wants_read(line("title").as_bytes()));
    assert!(!wants_read(line("size").as_bytes()));
    let hb = json!({"jsonrpc": "2.0", "method": "heartbeat", "params": {}});
    assert!(!wants_read(format!("{hb}\n").as_bytes()));
    // Two lines in one frame: one of them is enough.
    let two = format!("{}{}", line("title"), line("turn"));
    assert!(wants_read(two.as_bytes()));
}

// Opening reads from the folder the title bar names, from the top; nothing
// but reads reach the core.
#[tokio::test(start_paused = true)]
async fn opening_reads_the_list() {
    let mut r = rig("ch-open");
    let (mut core, ch) = connected(&mut r).await;
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    core.serves_read(
        "/home/dev/src/website/app",
        b"?? notes.md\0?? vendor/sdk/\0",
        Some(b"notes.md\x003\n"),
    )
    .await;
    let v = read.await.unwrap();
    assert_eq!(
        v,
        ChangesView {
            list: ChangesList::Listed {
                entries: vec![
                    new_file("notes.md", 3),
                    ChangesEntry {
                        path: "vendor/sdk/".into(),
                        kind: "repository".into(),
                        added: None,
                        removed: None,
                        opens: false,
                    },
                ],
                capped: false,
            },
            open: None,
            diff: None,
        }
    );
    core.quiet().await;
}

#[tokio::test(start_paused = true)]
async fn no_git_on_the_desk_is_not_a_repository() {
    let mut r = rig("ch-spawn");
    let (mut core, ch) = connected(&mut r).await;
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    let req = core.expect("terminal.get").await;
    core.reply(&req, terminal(TOP)).await;
    let req = core.expect("machine.exec").await;
    core.refuse(&req, "spawn_failed", "spawn_failed").await;
    assert_eq!(read.await.unwrap().list, ChangesList::NotARepo);
}

#[tokio::test(start_paused = true)]
async fn a_failure_offers_retry_and_retry_reads_again() {
    let mut r = rig("ch-retry");
    let (mut core, ch) = connected(&mut r).await;
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    let req = core.expect("terminal.get").await;
    core.reply(&req, terminal(TOP)).await;
    let req = core.expect("machine.exec").await;
    let b64 = base64::engine::general_purpose::STANDARD;
    core.reply(
        &req,
        json!({"stdout": "", "stderr": b64.encode("fatal: detected dubious ownership in repository\n"),
               "code": 128, "timed_out": false, "truncated": false}),
    )
    .await;
    assert_eq!(
        read.await.unwrap().list,
        ChangesList::Failed {
            line: Some("fatal: detected dubious ownership in repository".into())
        }
    );
    // Retry: the same read again, on the subscription already held.
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.serves_read(TOP, b"", None).await;
    assert_eq!(
        read.await.unwrap().list,
        ChangesList::Listed {
            entries: vec![],
            capped: false
        }
    );
}

// Events during a read give one more read, never more; titles and
// heartbeats none.
#[tokio::test(start_paused = true)]
async fn status_and_turn_events_ask_for_one_read() {
    let mut r = rig("ch-events");
    let (mut core, ch) = connected(&mut r).await;
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    let req = core.expect("terminal.get").await;
    // While the read runs: three events.
    core.term_event("status").await;
    core.term_event("turn").await;
    core.term_event("status").await;
    core.reply(&req, terminal(TOP)).await;
    core.exec(Shape::Top, TOP, format!("{TOP}\n--\n").as_bytes(), 0)
        .await;
    core.exec(Shape::Head, TOP, b"sha1\nabc\n", 0).await;
    core.exec(Shape::Status, TOP, b"", 0).await;
    read.await.unwrap();
    assert!(ch.wait_change_async(10_000).await);
    assert!(
        !ch.wait_change_async(10_000).await,
        "one read for the burst"
    );
    core.term_event("title").await;
    assert!(!ch.wait_change_async(10_000).await);
    let c = ch.clone();
    let wait = spawn(async move { c.wait_change_async(10_000).await });
    tokio::time::sleep(Duration::from_secs(5)).await;
    core.term_event("turn").await;
    assert!(wait.await.unwrap());
    core.quiet().await;
}

// An open diff is fetched again after each read that still lists its file,
// and reads "No longer changed" once it is gone.
#[tokio::test(start_paused = true)]
async fn an_open_diff_follows_the_file() {
    let mut r = rig("ch-diff");
    let (mut core, ch) = connected(&mut r).await;
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    core.serves_read(TOP, b"?? notes.md\0", Some(b"notes.md\x001\n"))
        .await;
    read.await.unwrap();

    let diff = b"diff --git a/notes.md b/notes.md\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/notes.md\n@@ -0,0 +1 @@\n+one\n";
    let path = kudde_changes::parse_status(b"?? notes.md\0").remove(0).path;
    let c = ch.clone();
    let open = spawn(async move { c.open_async(Some("notes.md".into())).await });
    core.exec(Shape::NewFile(&path), TOP, diff, 1).await;
    let v = open.await.unwrap();
    assert_eq!(v.open.as_deref(), Some("notes.md"));
    let Some(ChangesDiff::Lines {
        lines,
        headers,
        too_large,
    }) = v.diff
    else {
        panic!("{v:?}")
    };
    assert_eq!((lines.len(), headers, too_large), (7, 5, false));
    assert_eq!(lines[6].text, "+one");

    // The agent goes on: the list is read, and the open diff again.
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.serves_read(TOP, b"?? notes.md\0", Some(b"notes.md\x002\n"))
        .await;
    let diff2 = [&diff[..], b"+two\n"].concat();
    core.exec(Shape::NewFile(&path), TOP, &diff2, 1).await;
    let v = read.await.unwrap();
    let Some(ChangesDiff::Lines { lines, .. }) = v.diff else {
        panic!("{v:?}")
    };
    assert_eq!(lines.len(), 8);

    // The agent removes it: no diff call, "No longer changed".
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.serves_read(TOP, b"", None).await;
    let v = read.await.unwrap();
    assert_eq!(v.diff, Some(ChangesDiff::NoLongerChanged));
    assert_eq!(v.open.as_deref(), Some("notes.md"));

    // Back to the list: no call.
    let v = ch.open_async(None).await;
    assert_eq!((v.open, v.diff), (None, None));
    core.quiet().await;
}

#[tokio::test(start_paused = true)]
async fn a_tracked_file_diffs_against_head() {
    let mut r = rig("ch-tracked");
    let (mut core, ch) = connected(&mut r).await;
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    let req = core.expect("terminal.get").await;
    core.reply(&req, terminal(TOP)).await;
    core.exec(Shape::Top, TOP, format!("{TOP}\n--\n").as_bytes(), 0)
        .await;
    core.exec(Shape::Head, TOP, b"sha1\nabc\n", 0).await;
    core.exec(Shape::Status, TOP, b" M src/app.ts\0", 0).await;
    core.exec(
        Shape::Counts(Base::Head),
        TOP,
        b":100644 100644 aaa 000 M\0src/app.ts\x002\t1\tsrc/app.ts\0",
        0,
    )
    .await;
    let v = read.await.unwrap();
    assert_eq!(
        v.list,
        ChangesList::Listed {
            entries: vec![ChangesEntry {
                path: "src/app.ts".into(),
                kind: "changed".into(),
                added: Some(2),
                removed: Some(1),
                opens: true,
            }],
            capped: false
        }
    );
    let path = kudde_changes::parse_status(b" M src/app.ts\0")
        .remove(0)
        .path;
    let c = ch.clone();
    let open = spawn(async move { c.open_async(Some("src/app.ts".into())).await });
    core.exec(Shape::Diff(Base::Head, &path), TOP, b"", 0).await;
    // An empty diff: changed back before it was opened.
    assert_eq!(open.await.unwrap().diff, Some(ChangesDiff::NoLongerChanged));
}

// A restored connection asks for a read; leaving drops the subscription.
#[tokio::test(start_paused = true)]
async fn a_restored_connection_reads_and_leaving_unsubscribes() {
    let mut r = rig("ch-reconnect");
    let (mut core, ch) = connected(&mut r).await;
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    core.serves_read(TOP, b"", None).await;
    read.await.unwrap();

    core.exit(0, "");
    let c = ch.clone();
    let wait = spawn(async move {
        let mut changed = false;
        for _ in 0..30 {
            if c.wait_change_async(10_000).await {
                changed = true;
                break;
            }
        }
        changed
    });
    let mut core = r.core().await;
    core.connect("0.1.15", vec![agent(ADDR, "blocked", 1)])
        .await;
    core.changes_subscribed().await;
    assert!(wait.await.unwrap(), "a read after the connection came back");

    ch.leave_async().await;
    let f = loop {
        let f = wire::read_frame(&mut core.r).await.unwrap().unwrap();
        if f.channel == SUB {
            break f;
        }
    };
    assert_eq!(f.kind, wire::CLOSE, "the subscription is dropped");
    // Events sent after leaving are not held for the next visit.
    core.term_event("turn").await;
    assert!(!ch.wait_change_async(1_000).await);
}

/// Review 2026-11-16: a diff fetched for one file never lands under another.
#[tokio::test(start_paused = true)]
async fn a_slow_diff_of_the_last_file_does_not_land_under_the_next() {
    let mut r = rig("ch-stale");
    let (mut core, ch) = connected(&mut r).await;
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    core.serves_read(TOP, b"?? a.md\0?? b.md\0", Some(b"a.md\x001\nb.md\x001\n"))
        .await;
    read.await.unwrap();

    let diff = |p: &str, line: &str| {
        format!(
            "diff --git a/{p} b/{p}\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/{p}\n@@ -0,0 +1 @@\n+{line}\n"
        )
    };
    // a.md is opened; its diff is slow.
    let c = ch.clone();
    let open_a = spawn(async move { c.open_async(Some("a.md".into())).await });
    let req_a = core.expect("machine.exec").await;
    assert_eq!(
        req_a["params"]["argv"].as_array().unwrap().last().unwrap(),
        "a.md"
    );
    // Back, then b.md, whose diff comes first.
    ch.open_async(None).await;
    let c = ch.clone();
    let open_b = spawn(async move { c.open_async(Some("b.md".into())).await });
    let req_b = core.expect("machine.exec").await;
    assert_eq!(
        req_b["params"]["argv"].as_array().unwrap().last().unwrap(),
        "b.md"
    );
    core.reply(&req_b, exec_out(diff("b.md", "bee").as_bytes(), 1))
        .await;
    open_b.await.unwrap();
    core.reply(&req_a, exec_out(diff("a.md", "ay").as_bytes(), 1))
        .await;
    open_a.await.unwrap();

    let v = ch.view();
    assert_eq!(v.open.as_deref(), Some("b.md"));
    let Some(ChangesDiff::Lines { lines, .. }) = v.diff else {
        panic!("{v:?}")
    };
    assert_eq!(lines.last().unwrap().text, "+bee");
}

/// Review 2026-11-16: the screen going off mid-read stops the read's
/// remaining calls; the next read starts again.
#[tokio::test(start_paused = true)]
async fn leaving_during_a_read_stops_its_calls() {
    let mut r = rig("ch-leave-mid");
    let (mut core, ch) = connected(&mut r).await;
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    let req = core.expect("terminal.get").await;
    core.reply(&req, terminal(TOP)).await;
    core.exec(Shape::Top, TOP, format!("{TOP}\n--\n").as_bytes(), 0)
        .await;
    let head = core.expect("machine.exec").await;
    let c = ch.clone();
    let leave = spawn(async move { c.leave_async().await });
    // The flag is set before leave waits for anything.
    while !ch.has_left() {
        tokio::task::yield_now().await;
    }
    core.reply(&head, exec_out(b"sha1\nabc\n", 0)).await;
    let v = read.await.unwrap();
    leave.await.unwrap();
    assert_eq!(
        v.list,
        ChangesList::Reading,
        "nothing listed from a read cut short"
    );
    core.quiet().await;

    // Back on: a read runs whole.
    let c = ch.clone();
    let read = spawn(async move { c.read_async().await });
    core.changes_subscribed().await;
    core.serves_read(TOP, b"", None).await;
    let v = read.await.unwrap();
    assert_eq!(
        v.list,
        ChangesList::Listed {
            entries: vec![],
            capped: false
        }
    );
}
