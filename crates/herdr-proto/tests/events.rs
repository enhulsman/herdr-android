//! `EventLines`: the event stream applied to its snapshot (task M1.1).
//! The first two tests are herdr-app's (`hc.rs`), kept as they are there.

use herdr_proto::events::EventLines;
use serde_json::{Value, json};

fn event(seq: u64, kind: &str, t: &str, data: Value) -> String {
    format!(
        "{}\n",
        json!({"method": "event", "params": {"seq": seq, "type": kind, "terminal": t, "data": data}})
    )
}

fn rec(a: &str) -> Value {
    json!({"address": a, "state": "live", "title": "", "labels": {}, "status": null})
}

fn addresses(l: &EventLines) -> Vec<String> {
    l.live()["terminals"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["address"].as_str().unwrap().to_owned())
        .collect()
}

#[test]
fn the_event_stream_keeps_the_terminals_as_the_core_has_them() {
    let mut lines = EventLines::new(&json!({"seq": 5, "terminals": [rec("t1")]}));
    assert_eq!(addresses(&lines), ["t1"]);
    assert!(
        !lines
            .push(event(5, "title", "t1", json!({"title": "old"})).as_bytes())
            .terminals,
        "not newer than the snapshot"
    );
    let got = lines.push(
        format!(
            "{}{}",
            event(6, "created", "t2", rec("t2")),
            event(7, "title", "t1", json!({"title": "hi"}))
        )
        .as_bytes(),
    );
    assert!(got.terminals && !got.docs);
    assert_eq!(addresses(&lines), ["t1", "t2"]);
    assert_eq!(lines.live()["terminals"][0]["title"], "hi");
    lines.push(
        event(
            8,
            "status",
            "t2",
            json!({"status": {"state": "working"}, "records": {}, "reported": true}),
        )
        .as_bytes(),
    );
    lines.push(
        event(
            9,
            "foreground",
            "t2",
            json!({"pid": 7, "name": "vim", "argv": ["vim"], "cwd": "/src"}),
        )
        .as_bytes(),
    );
    assert_eq!(lines.live()["terminals"][1]["status"]["state"], "working");
    assert_eq!(lines.live()["terminals"][1]["foreground"]["cwd"], "/src");
    lines.push(
        event(
            10,
            "finished",
            "t1",
            json!({"reason": "exited", "exit_code": 0, "signal": null, "finished_ms": 1}),
        )
        .as_bytes(),
    );
    assert_eq!(addresses(&lines), ["t2"], "finished: no longer live");
    lines.push(event(11, "removed", "t2", json!({})).as_bytes());
    assert!(addresses(&lines).is_empty());
    let missed = format!(
        "{}\n",
        json!({"method": "missed", "params": {"seq": 20, "terminals": [rec("t9")]}})
    );
    let got = lines.push(missed.as_bytes());
    assert!(got.terminals && got.docs, "missed: everything again");
    assert_eq!(addresses(&lines), ["t9"]);
    assert!(
        lines
            .push(
                format!(
                    "{}\n",
                    json!({"method": "event", "params": {"seq": 21, "type": "doc"}})
                )
                .as_bytes()
            )
            .docs
    );
}

#[test]
fn document_and_missed_events_survive_arbitrary_frame_boundaries() {
    for line in [
        b"{\"method\":\"event\",\"params\":{\"type\":\"doc\"}}\n".as_slice(),
        b"{\"method\":\"missed\",\"params\":{\"terminals\":[]}}\n".as_slice(),
    ] {
        for at in 1..line.len() {
            let mut lines = EventLines::default();
            assert!(!lines.push(&line[..at]).docs);
            assert!(lines.push(&line[at..]).docs);
            assert_eq!(lines.pending_bytes(), 0);
        }
        let mut lines = EventLines::default();
        let mut refresh = false;
        for chunk in line.chunks(3) {
            refresh |= lines.push(chunk).docs;
        }
        assert!(refresh);
    }
}

#[test]
fn turn_lost_restarted_keep_and_size_apply_as_the_schema_says() {
    let mut t1 = rec("t1");
    t1["pid"] = json!(42);
    t1["foreground"] = json!({"pid": 43, "name": "node", "argv": [], "cwd": "/w"});
    t1["turn"] = json!(0);
    let mut lines = EventLines::new(&json!({"seq": 0, "terminals": [t1]}));
    lines.push(event(1, "turn", "t1", json!({"turn": 3, "state": "idle"})).as_bytes());
    assert_eq!(lines.terminals()[0]["turn"], 3);
    lines.push(
        event(
            2,
            "lost",
            "t1",
            json!({"reason": "signaled", "exit_code": null, "signal": 9, "finished_ms": null}),
        )
        .as_bytes(),
    );
    let t = &lines.terminals()[0];
    assert_eq!(t["state"], "live");
    assert_eq!(t["reason"], "signaled");
    assert!(t["pid"].is_null());
    assert!(t.get("foreground").is_none());
    let mut fresh = rec("t1");
    fresh["pid"] = json!(50);
    lines.push(event(3, "restarted", "t1", fresh).as_bytes());
    assert_eq!(lines.terminals()[0]["pid"], 50);
    lines.push(event(4, "keep", "t1", json!({"keep": true})).as_bytes());
    lines.push(event(5, "size", "t1", json!({"cols": 100, "rows": 30})).as_bytes());
    let t = &lines.terminals()[0];
    assert_eq!(
        (t["keep"].clone(), t["cols"].clone(), t["rows"].clone()),
        (json!(true), json!(100), json!(30))
    );
    // A labels event with `record` brings in a terminal that newly matches.
    lines.push(
        event(
            6,
            "labels",
            "t2",
            json!({"labels": {"a": "b"}, "record": rec("t2")}),
        )
        .as_bytes(),
    );
    assert_eq!(addresses(&lines), ["t1", "t2"]);
}

#[test]
fn heartbeats_and_garbage_change_nothing_and_seq_only_rises() {
    let mut lines = EventLines::new(&json!({"seq": 10, "terminals": [rec("t1")]}));
    let got = lines.push(
        b"{\"jsonrpc\":\"2.0\",\"method\":\"heartbeat\",\"params\":{\"seq\":10}}\nnot json\n\n",
    );
    assert!(!got.terminals && !got.docs);
    assert!(
        !lines
            .push(event(9, "removed", "t1", json!({})).as_bytes())
            .terminals
    );
    assert_eq!(addresses(&lines), ["t1"]);
    assert_eq!(lines.seq(), 10);
    lines.push(event(11, "title", "t1", json!({"title": "x"})).as_bytes());
    assert_eq!(lines.seq(), 11);
}

#[test]
fn a_core_with_an_added_machine() {
    // The connected core also lists terminals on machines it added itself;
    // the app reaches those machines directly, so it never shows them here.
    let snapshot = json!({"seq": 1, "terminals": [rec("t1"), rec("other/t3")]});
    let mut lines = EventLines::new(&snapshot);
    assert_eq!(addresses(&lines), ["t1"]);
    let got = lines.push(event(2, "created", "other/t4", rec("other/t4")).as_bytes());
    assert!(!got.terminals);
    lines.push(
        event(
            3,
            "status",
            "other/t3",
            json!({"status": {"state": "blocked"}, "records": {}, "reported": true}),
        )
        .as_bytes(),
    );
    lines.push(
        event(
            4,
            "labels",
            "other/t5",
            json!({"labels": {}, "record": rec("other/t5")}),
        )
        .as_bytes(),
    );
    assert_eq!(addresses(&lines), ["t1"]);
    let missed = format!(
        "{}\n",
        json!({"method": "missed", "params": {"seq": 9, "terminals": [rec("other/t3"), rec("t2")]}})
    );
    lines.push(missed.as_bytes());
    assert_eq!(addresses(&lines), ["t2"]);
    assert!(
        lines
            .terminals()
            .iter()
            .all(|t| !t["address"].as_str().unwrap().contains('/'))
    );
}

#[test]
fn an_endless_line_is_dropped_instead_of_growing_forever() {
    let mut lines = EventLines::default();
    let chunk = vec![b'x'; 1 << 20];
    for _ in 0..20 {
        lines.push(&chunk);
    }
    assert!(lines.pending_bytes() <= EventLines::MAX_LINE);
    // The stream recovers at the next line.
    lines.push(b"\n");
    assert!(
        lines
            .push(b"{\"method\":\"event\",\"params\":{\"type\":\"doc\"}}\n")
            .docs
    );
}
