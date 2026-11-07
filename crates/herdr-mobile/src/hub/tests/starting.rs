//! The start sheet's hub half (start-agents tasks 2.2-2.4): what a connected
//! machine offers (places, agents), the check's caching and retry, and the
//! start call with its refusals, against a scripted core.

use super::*;
use crate::hub::start::{AgentsView, PlaceNote, StartPlace, StartView};
use base64::Engine;
use herdr_proto::start as proto;

/// The start sheet's reads: the desk's layout and the installed-agents check.
pub(super) fn is_start_read(req: &Value) -> bool {
    req["method"] == "machine.exec"
        || (req["method"] == "doc.get" && req["params"]["name"] == "herdr-app")
}

/// A machine with no layout and a check that finds nothing.
pub(super) fn quiet_answer(req: &Value) -> Value {
    if req["method"] == "machine.exec" {
        exec_out("herdr-home:/home/dev\nherdr-done\n", Some(0))
    } else {
        json!({"value": null, "version": 0})
    }
}

fn exec_out(stdout: &str, code: Option<i32>) -> Value {
    let b64 = base64::engine::general_purpose::STANDARD.encode(stdout);
    json!({"stdout": b64, "stderr": "", "code": code, "timed_out": code.is_none(),
           "truncated": false, "ms": 30})
}

fn found(words: &[&str]) -> Value {
    let mut out = String::from("herdr-home:/home/dev\n\x1b]0;zsh\x07");
    for w in words {
        out.push_str(&format!("herdr-has:{w}\n"));
    }
    out.push_str("herdr-done\n");
    exec_out(&out, Some(0))
}

const LAYOUT: &str = r#"{"spaces": [
    {"id": "s1", "name": "website", "folder": "/home/dev/src/website", "tabs": []},
    {"id": "s2", "name": "", "folder": "~/src/api", "tabs": []}
]}"#;

fn layout() -> Value {
    serde_json::from_str(LAYOUT).unwrap()
}

impl Core {
    /// Answers the layout read and the check, in whichever order they come.
    async fn serves_start(&mut self, layout: Value, exec: Value) {
        self.serve_start = true;
        let (mut doc, mut check) = (Some(layout), Some(exec));
        while doc.is_some() || check.is_some() {
            let req = self.request().await;
            let result = match req["method"].as_str() {
                Some("doc.get") if req["params"]["name"] == "herdr-app" => {
                    json!({"value": doc.take().expect("one layout read"), "version": 1})
                }
                Some("machine.exec") => {
                    assert_eq!(req["params"], proto::check_params(&words()), "{req}");
                    check.take().expect("one check")
                }
                _ => panic!("not a start read: {req}"),
            };
            self.reply(&req, result).await;
        }
    }

    async fn reply(&mut self, req: &Value, result: Value) {
        let reply = json!({"jsonrpc": "2.0", "id": req["id"], "result": result});
        self.send(0, wire::DATA, reply.to_string().as_bytes()).await;
    }

    async fn refuse(&mut self, req: &Value, kind: &str, message: &str) {
        let reply = json!({"jsonrpc": "2.0", "id": req["id"],
                           "error": {"code": -32000, "message": message, "data": {"kind": kind}}});
        self.send(0, wire::DATA, reply.to_string().as_bytes()).await;
    }

    /// The next request, which must be this method.
    async fn expect(&mut self, method: &str) -> Value {
        let req = self.request().await;
        assert_eq!(req["method"], method, "{req}");
        req
    }
}

fn words() -> Vec<&'static str> {
    proto::AGENTS.iter().map(|a| a.word).collect()
}

fn start_of(s: &HubSnapshot, machine: &str) -> Option<StartView> {
    view(s, machine).start.clone()
}

fn agent_words(v: &StartView) -> Option<Vec<String>> {
    match &v.agents {
        AgentsView::Ready { agents } => Some(agents.iter().map(|a| a.word.clone()).collect()),
        _ => None,
    }
}

fn space(id: &str, folder: &str) -> StartPlace {
    StartPlace {
        title: "website".into(),
        folder: Some(folder.into()),
        space_id: Some(id.into()),
        note: PlaceNote::None,
    }
}

/// A connected workstation whose start reads the test serves.
async fn connected_ws(r: &mut Rig, terminals: Vec<Value>, doc: Value, exec: Value) -> Core {
    r.add_trusted(WS, WS_KEY);
    r.hub.resume();
    let mut core = r.core().await;
    core.serve_start = true;
    core.connect("0.1.15", terminals).await;
    core.serves_start(doc, exec).await;
    core
}

#[tokio::test(start_paused = true)]
async fn a_connected_machine_offers_its_spaces_and_installed_agents() {
    let mut r = rig("st-offer");
    let _core = connected_ws(&mut r, vec![], layout(), found(&["codex", "claude"])).await;
    let s = r
        .until("agents checked", |s| {
            start_of(s, WS).is_some_and(|v| matches!(v.agents, AgentsView::Ready { .. }))
                && start_of(s, WS).is_some_and(|v| !v.places.is_empty())
        })
        .await;
    let v = start_of(&s, WS).unwrap();
    // Table order, not the order the shell answered in.
    assert_eq!(agent_words(&v), Some(vec!["claude".into(), "codex".into()]));
    let AgentsView::Ready { agents } = &v.agents else {
        unreachable!()
    };
    assert_eq!(
        (agents[0].name.as_str(), agents[0].program.as_str()),
        ("Claude Code", "claude")
    );
    assert!(v.from_spaces);
    // The sheet writes folders under home as `~/...`.
    assert_eq!(v.home.as_deref(), Some("/home/dev"));
    assert_eq!(
        v.places,
        vec![
            StartPlace {
                title: "website".into(),
                folder: Some("/home/dev/src/website".into()),
                space_id: Some("s1".into()),
                note: PlaceNote::None,
            },
            StartPlace {
                title: "api".into(),
                folder: Some("/home/dev/src/api".into()),
                space_id: Some("s2".into()),
                note: PlaceNote::None,
            },
        ]
    );
}

// Codex review: before the layout answered, a machine with spaces looked like
// one without, and a start then made a new space instead of joining one.
#[tokio::test(start_paused = true)]
async fn nothing_is_offered_until_the_layout_is_read() {
    let mut r = rig("st-unread");
    r.add_trusted(WS, WS_KEY);
    r.hub.resume();
    let mut core = r.core().await;
    core.serve_start = true;
    core.connect("0.1.15", vec![]).await;
    // Hold the layout read; answer the check.
    let mut held = None;
    while held.is_none() {
        let req = core.request().await;
        match req["method"].as_str() {
            Some("doc.get") => held = Some(req),
            Some("machine.exec") => core.reply(&req, found(&["claude"])).await,
            _ => panic!("not a start read: {req}"),
        }
    }
    let s = r
        .until("connected", |s| state(s, WS).is_some_and(connected))
        .await;
    assert_eq!(start_of(&s, WS), None);
    tokio::time::sleep(std::time::Duration::from_secs(5)).await;
    assert_eq!(start_of(&r.hub.snapshot(), WS), None);
    let req = held.unwrap();
    core.reply(&req, json!({"value": layout(), "version": 1}))
        .await;
    r.until("spaces", |s| start_of(s, WS).is_some_and(|v| v.from_spaces))
        .await;
}

#[tokio::test(start_paused = true)]
async fn a_disconnected_machine_offers_nothing() {
    let mut r = rig("st-off");
    r.add_trusted(WS, WS_KEY);
    r.dialer.fail(WS, Some(Fail::Unreachable));
    r.hub.resume();
    let s = r
        .until_state(WS, |s| {
            !matches!(s, MachineState::Connecting | MachineState::Paused)
        })
        .await;
    assert_eq!(view(&s, WS).start, None);
}

#[tokio::test(start_paused = true)]
async fn without_spaces_the_places_are_home_then_live_folders() {
    let mut r = rig("st-folders");
    let mut t1 = agent("t1", "idle", 0);
    t1["cwd"] = json!("/home/dev/src/api");
    let mut t2 = agent("t2", "idle", 0);
    t2["cwd"] = json!("/home/dev/src/api");
    let _core = connected_ws(&mut r, vec![t1, t2], Value::Null, found(&[])).await;
    let s = r
        .until("home known", |s| {
            start_of(s, WS).is_some_and(|v| {
                v.places
                    .first()
                    .is_some_and(|p| p.folder.as_deref() == Some("/home/dev"))
            })
        })
        .await;
    let v = start_of(&s, WS).unwrap();
    assert!(!v.from_spaces);
    let got: Vec<(String, Option<String>, Option<String>)> = v
        .places
        .iter()
        .map(|p| (p.title.clone(), p.folder.clone(), p.space_id.clone()))
        .collect();
    assert_eq!(
        got,
        vec![
            ("~".into(), Some("/home/dev".into()), None),
            ("api".into(), Some("/home/dev/src/api".into()), None),
        ]
    );
    assert_eq!(agent_words(&v), Some(vec![]));
}

#[tokio::test(start_paused = true)]
async fn a_failed_check_offers_terminal_only_and_runs_again_when_the_sheet_opens() {
    let mut r = rig("st-retry");
    let mut core = connected_ws(
        &mut r,
        vec![],
        layout(),
        exec_out(
            "herdr-home:/home/dev\nfish: Unsupported use of 'do'.\n",
            Some(127),
        ),
    )
    .await;
    let s = r
        .until("check failed", |s| {
            start_of(s, WS).is_some_and(|v| v.agents == AgentsView::Unavailable)
        })
        .await;
    // Home still came through, from the outer sh.
    assert_eq!(
        start_of(&s, WS).unwrap().places[1].folder.as_deref(),
        Some("/home/dev/src/api")
    );
    r.hub.start_sheet_opened(WS);
    let req = core.expect("machine.exec").await;
    core.reply(&req, found(&["claude"])).await;
    r.until("check ran again", |s| {
        start_of(s, WS).and_then(|v| agent_words(&v)) == Some(vec!["claude".into()])
    })
    .await;
}

#[tokio::test(start_paused = true)]
async fn a_check_that_answered_is_kept_across_reconnects() {
    let mut r = rig("st-cache");
    let core = connected_ws(&mut r, vec![], layout(), found(&["claude"])).await;
    r.until("checked", |s| {
        start_of(s, WS).and_then(|v| agent_words(&v)).is_some()
    })
    .await;
    core.exit(0, "");
    let mut core = r.core().await;
    core.serve_start = true;
    core.connect("0.1.15", vec![]).await;
    // Only the layout is read again; the sheet opening asks nothing more.
    let req = core.expect("doc.get").await;
    assert_eq!(req["params"]["name"], "herdr-app");
    core.reply(&req, json!({"value": layout(), "version": 1}))
        .await;
    let s = r
        .until("reconnected with agents", |s| {
            connected(state(s, WS).unwrap())
                && start_of(s, WS).and_then(|v| agent_words(&v)).is_some()
        })
        .await;
    assert_eq!(
        agent_words(&start_of(&s, WS).unwrap()),
        Some(vec!["claude".into()])
    );
    r.hub.start_sheet_opened(WS);
    let hub = r.hub.clone();
    let started =
        tokio::spawn(async move { hub.start_terminal(WS, space("s1", "/x"), None).await });
    let req = core.expect("terminal.run").await;
    core.reply(&req, json!({"address": "t9"})).await;
    assert_eq!(started.await.unwrap(), Ok("t9".to_owned()));
}

#[tokio::test(start_paused = true)]
async fn a_layout_change_is_read_again() {
    let mut r = rig("st-doc");
    let mut core = connected_ws(&mut r, vec![], Value::Null, found(&[])).await;
    r.until("folders", |s| {
        start_of(s, WS).is_some_and(|v| !v.places.is_empty())
    })
    .await;
    core.event(1, "doc", "", json!({"name": "herdr-app"})).await;
    let req = core.expect("doc.get").await;
    assert_eq!(req["params"]["name"], "herdr-app");
    core.reply(&req, json!({"value": layout(), "version": 2}))
        .await;
    r.until("spaces", |s| start_of(s, WS).is_some_and(|v| v.from_spaces))
        .await;
}

#[tokio::test(start_paused = true)]
async fn starting_in_a_space_sends_the_designed_run_and_returns_the_address() {
    let mut r = rig("st-run");
    let mut core = connected_ws(&mut r, vec![], layout(), found(&["claude"])).await;
    r.until("checked", |s| {
        start_of(s, WS).and_then(|v| agent_words(&v)).is_some()
    })
    .await;
    let hub = r.hub.clone();
    let started = tokio::spawn(async move {
        hub.start_terminal(
            WS,
            space("s1", "/home/dev/src/website"),
            Some("claude".into()),
        )
        .await
    });
    let req = core.expect("terminal.run").await;
    assert_eq!(
        req["params"],
        proto::run_params(Some("/home/dev/src/website"), "s1", Some("claude"))
    );
    core.reply(&req, json!({"address": "t9", "keep": true}))
        .await;
    assert_eq!(started.await.unwrap(), Ok("t9".to_owned()));
}

#[tokio::test(start_paused = true)]
async fn a_start_without_spaces_makes_a_new_space() {
    let mut r = rig("st-fresh");
    let mut core = connected_ws(&mut r, vec![], Value::Null, found(&[])).await;
    r.until("home", |s| {
        start_of(s, WS).is_some_and(|v| !v.places.is_empty())
    })
    .await;
    let place = StartPlace {
        title: "~".into(),
        folder: Some("/home/dev".into()),
        space_id: None,
        note: PlaceNote::None,
    };
    let hub = r.hub.clone();
    let started = tokio::spawn(async move { hub.start_terminal(WS, place, None).await });
    let req = core.expect("terminal.run").await;
    let id = req["params"]["labels"]["herdr.space"].as_str().unwrap();
    assert!(id.starts_with("sp") && id.len() == 34, "{id}");
    assert_eq!(req["params"]["cwd"], "/home/dev");
    assert!(req["params"].get("argv").is_none(), "{req}");
    core.reply(&req, json!({"address": "t3"})).await;
    assert_eq!(started.await.unwrap(), Ok("t3".to_owned()));
}

#[tokio::test(start_paused = true)]
async fn a_missing_folder_says_where_it_could_not_start() {
    let mut r = rig("st-spawn");
    let mut core = connected_ws(&mut r, vec![], layout(), found(&[])).await;
    r.until("ready", |s| start_of(s, WS).is_some_and(|v| v.from_spaces))
        .await;
    let hub = r.hub.clone();
    let started = tokio::spawn(async move {
        hub.start_terminal(WS, space("s1", "/home/dev/gone"), None)
            .await
    });
    let req = core.expect("terminal.run").await;
    core.refuse(&req, "spawn_failed", "No such file or directory")
        .await;
    assert_eq!(
        started.await.unwrap(),
        Err(HubError::Invalid {
            detail: "Could not start in /home/dev/gone: No such file or directory".into()
        })
    );
}

#[tokio::test(start_paused = true)]
async fn other_refusals_show_the_cores_own_reason() {
    let mut r = rig("st-refuse");
    let mut core = connected_ws(&mut r, vec![], layout(), found(&[])).await;
    r.until("ready", |s| start_of(s, WS).is_some_and(|v| v.from_spaces))
        .await;
    for (kind, message, want) in [
        (
            "unavailable",
            "unavailable",
            format!("{WS} is not reachable now"),
        ),
        (
            "machine_unknown",
            "machine_unknown",
            format!("{WS} is not reachable now"),
        ),
        (
            "config",
            "bad shell in config.toml",
            "bad shell in config.toml".to_owned(),
        ),
    ] {
        let hub = r.hub.clone();
        let started =
            tokio::spawn(async move { hub.start_terminal(WS, space("s1", "/x"), None).await });
        let req = core.expect("terminal.run").await;
        core.refuse(&req, kind, message).await;
        assert_eq!(
            started.await.unwrap(),
            Err(HubError::Invalid { detail: want })
        );
    }
}

#[tokio::test(start_paused = true)]
async fn a_second_start_while_one_runs_is_refused() {
    let mut r = rig("st-double");
    let mut core = connected_ws(&mut r, vec![], layout(), found(&[])).await;
    r.until("ready", |s| start_of(s, WS).is_some_and(|v| v.from_spaces))
        .await;
    let hub = r.hub.clone();
    let first = tokio::spawn(async move { hub.start_terminal(WS, space("s1", "/x"), None).await });
    let req = core.expect("terminal.run").await;
    let again = r.hub.start_terminal(WS, space("s1", "/x"), None).await;
    assert_eq!(
        again,
        Err(HubError::Invalid {
            detail: "A start is already running".into()
        })
    );
    core.reply(&req, json!({"address": "t4"})).await;
    assert_eq!(first.await.unwrap(), Ok("t4".to_owned()));
    // Free again once it answered.
    let hub = r.hub.clone();
    let next = tokio::spawn(async move { hub.start_terminal(WS, space("s1", "/x"), None).await });
    let req = core.expect("terminal.run").await;
    core.reply(&req, json!({"address": "t5"})).await;
    assert_eq!(next.await.unwrap(), Ok("t5".to_owned()));
}

#[tokio::test(start_paused = true)]
async fn only_an_installed_table_agent_starts() {
    let mut r = rig("st-agent");
    let _core = connected_ws(&mut r, vec![], layout(), found(&["codex"])).await;
    r.until("checked", |s| {
        start_of(s, WS).and_then(|v| agent_words(&v)).is_some()
    })
    .await;
    for word in ["claude", "rm", "codex; rm -rf ~"] {
        let got = r
            .hub
            .start_terminal(WS, space("s1", "/x"), Some(word.into()))
            .await;
        assert!(
            matches!(got, Err(HubError::Invalid { .. })),
            "{word}: {got:?}"
        );
    }
}

#[tokio::test(start_paused = true)]
async fn a_machine_that_is_not_connected_starts_nothing() {
    let r = rig("st-nocon");
    r.add_trusted(WS, WS_KEY);
    r.dialer.fail(WS, Some(Fail::Unreachable));
    r.hub.resume();
    let got = r.hub.start_terminal(WS, space("s1", "/x"), None).await;
    assert_eq!(
        got,
        Err(HubError::Invalid {
            detail: format!("{WS} is not connected")
        })
    );
    let got = r
        .hub
        .start_terminal("nowhere", space("s1", "/x"), None)
        .await;
    assert_eq!(
        got,
        Err(HubError::NotFound {
            name: "nowhere".into()
        })
    );
}
