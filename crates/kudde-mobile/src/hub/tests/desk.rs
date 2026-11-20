//! D2 against the fake core: layout reads never write documents.
use super::*;

fn doc(name: &str) -> Value {
    json!({"spaces":[
        {"id":"s1","name":name,"folder":"~/website","tabs":[
            {"id":"main","name":"main","root":{"type":"row","children":[
                {"content":{"terminal":"t1"}},{"content":{"terminal":"t2"}}]}},
            {"id":"build","root":{"content":{"mod":"notes"}}}]},
        {"id":"s2","name":"api","tabs":[]}]})
}
fn shell(address: &str) -> Value {
    json!({"address":address,"state":"live","reported":false,"status":null})
}
async fn read(core: &mut Core) -> Value {
    loop {
        let req = core.request().await;
        assert_ne!(req["method"], "doc.set");
        match req["method"].as_str() {
            Some("doc.get") => {
                assert_eq!(req["params"]["name"], "herdr-app");
                return req;
            }
            Some("machine.exec") => core.reply(&req, starting::quiet_answer(&req)).await,
            _ => panic!("unexpected request: {req}"),
        }
    }
}
async fn answer(core: &mut Core, req: &Value, value: Value, version: u64) {
    core.reply(req, json!({"value":value,"version":version}))
        .await;
}
async fn ready(r: &mut Rig, core: &mut Core, value: Value) -> HubSnapshot {
    let req = read(core).await;
    answer(core, &req, value, 1).await;
    r.until("desk answered", |s| view(s, WS).desk.is_some())
        .await
}
async fn connect(r: &mut Rig, terminals: Vec<Value>) -> Core {
    r.add_trusted(WS, WS_KEY);
    r.hub.resume();
    let mut core = r.core().await;
    core.serve_start = true;
    core.connect("0.1.15", terminals).await;
    core
}
fn held(s: &HubSnapshot) -> &DeskView {
    view(s, WS).desk.as_ref().unwrap()
}
fn first_name(s: &HubSnapshot) -> &str {
    &held(s).spaces[0].name
}
fn places(s: &HubSnapshot, value: &Value) {
    let expected = herdr_proto::layout::spaces(value);
    let start = view(s, WS).start.as_ref().unwrap();
    assert_eq!(start.from_spaces, !expected.is_empty());
    if !expected.is_empty() {
        assert_eq!(
            start
                .places
                .iter()
                .map(|p| p.space_id.as_deref().unwrap())
                .collect::<Vec<_>>(),
            expected.iter().map(|s| s.id.as_str()).collect::<Vec<_>>()
        );
    }
}
// A barrier request proves all prior frames have been processed and rejects
// any doc.set or unexpected doc.get queued after the tested operation.
async fn barrier(r: &Rig, core: &mut Core) {
    let conn = r.hub.live_conn(WS).unwrap();
    let call = tokio::spawn(async move { conn.client.call("machine.info", json!({})).await });
    loop {
        let req = core.request().await;
        assert_ne!(req["method"], "doc.set");
        if req["method"] == "machine.exec" {
            core.reply(&req, starting::quiet_answer(&req)).await;
            continue;
        }
        assert_eq!(req["method"], "machine.info");
        core.reply(&req, json!({})).await;
        break;
    }
    call.await.unwrap().unwrap();
}
#[tokio::test(start_paused = true)]
async fn ordered_snapshot_and_none_before_answer() {
    let mut r = rig("desk-order");
    let mut c = connect(&mut r, vec![shell("t1"), shell("t2")]).await;
    let req = read(&mut c).await;
    r.until_state(WS, connected).await;
    assert!(view(&r.hub.snapshot(), WS).desk.is_none());
    answer(&mut c, &req, doc("website"), 1).await;
    let s = r.until("desk", |s| view(s, WS).desk.is_some()).await;
    let d = held(&s);
    assert_eq!(
        d.spaces.iter().map(|s| s.id.as_str()).collect::<Vec<_>>(),
        ["s1", "s2"]
    );
    let tabs = &d.spaces[0].tabs;
    assert_eq!(
        tabs.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(),
        ["main", "build"]
    );
    assert_eq!(
        tabs[0].cells[0].pane,
        DeskPaneView::Terminal {
            address: "t1".into()
        }
    );
    assert_eq!(
        tabs[0].cells[1].pane,
        DeskPaneView::Terminal {
            address: "t2".into()
        }
    );
    assert_eq!(tabs[0].cells[1].x, 0.5);
    assert_eq!(
        tabs[1].cells[0].pane,
        DeskPaneView::Placeholder {
            label: "notes".into()
        }
    );
    places(&s, &doc("website"));
    barrier(&r, &mut c).await;
}
#[tokio::test(start_paused = true)]
async fn events_versions_equal_and_error() {
    let mut r = rig("desk-version");
    let mut c = connect(&mut r, vec![]).await;
    let s = ready(&mut r, &mut c, doc("initial")).await;
    places(&s, &doc("initial"));
    c.event(1, "doc", "", json!({"name":"herdr-app"})).await;
    let early = read(&mut c).await;
    c.event(2, "doc", "", json!({"name":"herdr-app"})).await;
    let late = read(&mut c).await;
    answer(&mut c, &late, doc("nine"), 9).await;
    let s = r.until("nine", |s| first_name(s) == "nine").await;
    places(&s, &doc("nine"));
    answer(&mut c, &early, doc("eight"), 8).await;
    barrier(&r, &mut c).await;
    assert_eq!(first_name(&r.hub.snapshot()), "nine");
    c.event(3, "doc", "", json!({"name":"herdr-app"})).await;
    let eq = read(&mut c).await;
    answer(&mut c, &eq, doc("equal"), 9).await;
    r.until("equal", |s| first_name(s) == "equal").await;
    c.event(4, "doc", "", json!({"name":"herdr-app"})).await;
    let req = read(&mut c).await;
    c.refuse(&req, "failed", "cannot read").await;
    barrier(&r, &mut c).await;
    assert_eq!(first_name(&r.hub.snapshot()), "equal");
    assert!(
        std::fs::read_to_string(r.dir.join(LOG_FILE))
            .unwrap()
            .contains("reading herdr-app")
    );
    // The sheet still takes every answer, including late reads and errors.
    assert!(
        !view(&r.hub.snapshot(), WS)
            .start
            .as_ref()
            .unwrap()
            .from_spaces
    );
}
#[tokio::test(start_paused = true)]
async fn reconnect_retains_then_refreshes_and_removal_drops() {
    let mut r = rig("desk-reconnect");
    let mut c = connect(&mut r, vec![]).await;
    let s = ready(&mut r, &mut c, doc("initial")).await;
    places(&s, &doc("initial"));
    barrier(&r, &mut c).await;
    r.dialer.fail(WS, Some(Fail::Unreachable));
    c.exit(0, "");
    let s = r
        .until_state(WS, |s| matches!(s, MachineState::Unreachable { .. }))
        .await;
    assert_eq!(first_name(&s), "initial");
    assert!(view(&s, WS).start.is_none());
    r.dialer.fail(WS, None);
    r.hub.retry();
    let mut c = r.core().await;
    c.serve_start = true;
    c.connect("0.1.15", vec![]).await;
    let req = read(&mut c).await;
    assert_eq!(first_name(&r.hub.snapshot()), "initial");
    answer(&mut c, &req, doc("reconnected"), 2).await;
    let s = r
        .until("reconnected desk", |s| first_name(s) == "reconnected")
        .await;
    places(&s, &doc("reconnected"));
    barrier(&r, &mut c).await;
    r.hub.remove_machine(WS).unwrap();
    assert!(r.hub.snapshot().machines.is_empty());
    // There is no rename API: a changed name is remove + add.
    r.hub.add_machine("renamed", WS, 22, "dev").unwrap();
    assert!(view(&r.hub.snapshot(), "renamed").desk.is_none());
    r.hub.remove_machine("renamed").unwrap();
    r.hub.add_machine(WS, WS, 22, "dev").unwrap();
    assert!(view(&r.hub.snapshot(), WS).desk.is_none());
}
#[tokio::test(start_paused = true)]
async fn terminal_events_reseat_without_layout_read_and_keep_others() {
    let mut r = rig("desk-seating");
    let mut child = shell("t2");
    child["parent"] = json!("t1");
    let mut c = connect(&mut r, vec![shell("t1"), child]).await;
    let s = ready(&mut r, &mut c, doc("website")).await;
    places(&s, &doc("website"));
    assert_eq!(
        held(&s).spaces[0].tabs[0].cells[1].pane,
        DeskPaneView::Vacant
    );
    assert!(held(&s).loose.is_empty());
    assert!(s.others.iter().any(|t| t.address == "t2"));
    c.event(1, "created", "t3", shell("t3")).await;
    let s = r.until("loose created", |s| held(s).loose == ["t3"]).await;
    places(&s, &doc("website"));
    barrier(&r, &mut c).await;
}
#[tokio::test(start_paused = true)]
async fn notes_include_all_eligible_loose_and_missed_reads_again() {
    let mut r = rig("desk-notes");
    let mut child = shell("t9");
    child["parent"] = json!("t1");
    let mut c = connect(&mut r, vec![shell("t1"), child, shell("t2")]).await;
    let s = ready(&mut r, &mut c, Value::Null).await;
    assert_eq!(held(&s).note, Some(DeskNoteView::NoDocument));
    assert_eq!(held(&s).loose, ["t1", "t2"]);
    assert!(held(&s).spaces.is_empty());
    places(&s, &Value::Null);
    let missed = json!({"jsonrpc":"2.0","method":"missed","params":{"seq":1,"terminals":[shell("t1"),shell("t2")]}});
    c.send(1, wire::DATA, format!("{missed}\n").as_bytes())
        .await;
    let req = read(&mut c).await;
    answer(&mut c, &req, json!({"spaces":3}), 2).await;
    let s = r
        .until("unreadable", |s| {
            held(s).note == Some(DeskNoteView::Unreadable)
        })
        .await;
    assert!(held(&s).spaces.is_empty());
    assert_eq!(held(&s).loose, ["t1", "t2"]);
    places(&s, &json!({"spaces":3}));
    barrier(&r, &mut c).await;
}

async fn replacement_endpoint(host: &str, port: u16, user: &str, case: &str) {
    let mut r = rig(case);
    let mut old = connect(&mut r, vec![shell("t1")]).await;
    let req = read(&mut old).await;
    answer(&mut old, &req, doc("old"), 100).await;
    r.until("old desk", |s| view(s, WS).desk.is_some()).await;
    barrier(&r, &mut old).await;
    r.hub.suspend();
    // An unchanged endpoint must keep its held desk.
    r.hub.update_machine(WS, WS, 22, "dev").unwrap();
    assert_eq!(first_name(&r.hub.snapshot()), "old");
    r.hub.update_machine(WS, host, port, user).unwrap();
    assert!(
        view(&r.hub.snapshot(), WS).desk.is_none(),
        "old desk leaked to replacement"
    );
    let key = if host == SRV { SRV_KEY } else { WS_KEY };
    r.hub.confirm_host_key(WS, key).unwrap();
    r.hub.resume();
    let mut core = r.core().await;
    core.serve_start = true;
    assert_eq!(core.host, host);
    core.connect("0.1.15", vec![shell("t1")]).await;
    let req = read(&mut core).await;
    assert!(view(&r.hub.snapshot(), WS).desk.is_none());
    answer(&mut core, &req, doc("replacement"), 1).await;
    let s = r
        .until("replacement desk", |s| {
            view(s, WS)
                .desk
                .as_ref()
                .is_some_and(|d| d.spaces[0].name == "replacement")
        })
        .await;
    assert_eq!(first_name(&s), "replacement");
    places(&s, &doc("replacement"));
    barrier(&r, &mut core).await;
}
#[tokio::test(start_paused = true)]
async fn changed_host_drops_old_desk_and_version() {
    replacement_endpoint(SRV, 22, "dev", "desk-new-host").await;
}
#[tokio::test(start_paused = true)]
async fn changed_port_drops_old_desk_and_version() {
    replacement_endpoint(WS, 23, "dev", "desk-new-port").await;
}
#[tokio::test(start_paused = true)]
async fn changed_user_drops_old_desk_and_version() {
    replacement_endpoint(WS, 22, "other", "desk-new-user").await;
}

async fn parsing_rechecks_identity(change_epoch: bool) {
    let mut r = rig(if change_epoch {
        "desk-parse-epoch"
    } else {
        "desk-parse-version"
    });
    let mut core = connect(&mut r, vec![]).await;
    ready(&mut r, &mut core, doc("initial")).await;
    barrier(&r, &mut core).await;
    let hub = r.hub.clone();
    let conn = hub.live_conn(WS).unwrap();
    let task = tokio::spawn(async move {
        let during_parse = hub.clone();
        super::super::start::read_layout_with(
            hub,
            WS.into(),
            conn.epoch,
            conn.client,
            move |value| {
                assert!(
                    during_parse.state.try_lock().is_ok(),
                    "parser holds the hub lock"
                );
                during_parse.with(|_, st| {
                    if change_epoch {
                        st.live.get_mut(WS).unwrap().epoch += 1;
                    } else {
                        let newer = st.desks.get_mut(WS).unwrap();
                        newer.version = 9;
                        newer.seated.spaces[0].name = "newer".into();
                    }
                });
                herdr_proto::layout::desk(value)
            },
        )
        .await;
    });
    let req = read(&mut core).await;
    answer(&mut core, &req, doc("late"), 8).await;
    task.await.unwrap();
    assert_eq!(
        first_name(&r.hub.snapshot()),
        if change_epoch { "initial" } else { "newer" }
    );
}
#[tokio::test(start_paused = true)]
async fn parser_is_unlocked_and_rechecks_epoch() {
    parsing_rechecks_identity(true).await;
}
#[tokio::test(start_paused = true)]
async fn parser_is_unlocked_and_rechecks_version() {
    parsing_rechecks_identity(false).await;
}
