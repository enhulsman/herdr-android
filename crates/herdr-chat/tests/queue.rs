//! Claude Code's queue records (design D10, desk check 2.8 on 2.1.325): a
//! message typed while the agent works gets an `enqueue` record with its text
//! within 0.3 s; it leaves the queue by `remove` (with its text), `dequeue`
//! (the oldest, no text) or `popAll` (all, back into the input box).

use herdr_chat::{Agent, Parser};

fn op(operation: &str, content: Option<&str>) -> String {
    let content = content
        .map(|c| format!(",\"content\":{}", serde_json::to_string(c).unwrap()))
        .unwrap_or_default();
    format!(
        "{{\"type\":\"queue-operation\",\"operation\":\"{operation}\",\"timestamp\":\"2026-11-14T16:52:11.022Z\",\"sessionId\":\"s\"{content}}}"
    )
}
fn parse(lines: &[String]) -> Parser {
    let mut p = Parser::new(Agent::Claude);
    for l in lines {
        p.feed(&format!("{l}\n"));
    }
    p
}

#[test]
fn an_enqueued_message_is_queued_until_removed() {
    let p = parse(&[op("enqueue", Some("Say only: ok-Q6"))]);
    assert_eq!(p.queued(), ["Say only: ok-Q6"]);
    let mut lines = vec![op("enqueue", Some("Say only: ok-Q6"))];
    lines.push(
        "{\"type\":\"queue-operation\",\"operation\":\"remove\",\"timestamp\":\"2026-11-14T16:52:36.660Z\",\"sessionId\":\"s\",\"content\":\"Say only: ok-Q6\",\"reason\":\"absorbed_mid_turn\",\"commandUuid\":\"u\",\"deliveryId\":\"d\"}"
            .into(),
    );
    assert!(parse(&lines).queued().is_empty());
}

#[test]
fn remove_takes_the_matching_text_and_dequeue_the_oldest() {
    let p = parse(&[
        op("enqueue", Some("first")),
        op("enqueue", Some("second")),
        op("enqueue", Some("third")),
        op("remove", Some("second")),
    ]);
    assert_eq!(p.queued(), ["first", "third"]);
    let p = parse(&[
        op("enqueue", Some("first")),
        op("enqueue", Some("second")),
        op("dequeue", None),
    ]);
    assert_eq!(p.queued(), ["second"]);
}

#[test]
fn pop_all_empties_the_queue() {
    let p = parse(&[
        op("enqueue", Some("a")),
        op("enqueue", Some("b")),
        op("popAll", Some("a\nb")),
    ]);
    assert!(p.queued().is_empty());
}

#[test]
fn records_whose_start_was_not_read_are_ignored() {
    // A read starting mid-queue: the dequeue or remove of an unseen enqueue.
    let p = parse(&[
        op("dequeue", None),
        op("remove", Some("never seen")),
        op("enqueue", Some("later")),
    ]);
    assert_eq!(p.queued(), ["later"]);
}

#[test]
fn an_enqueue_without_text_holds_a_place_for_its_dequeue() {
    let p = parse(&[
        op("enqueue", None),
        op("enqueue", Some("typed")),
        op("dequeue", None),
    ]);
    assert_eq!(p.queued(), ["typed"]);
}

#[test]
fn a_queued_text_is_the_persons_own() {
    let p = parse(&[op("enqueue", Some("<hc from=\"t1\">Run the tests</hc>"))]);
    assert_eq!(p.queued(), ["Run the tests"]);
}

#[test]
fn queue_records_are_known_and_make_no_row() {
    let lines: Vec<String> = (0..10)
        .map(|i| op("enqueue", Some(&format!("m{i}"))))
        .collect();
    let p = parse(&lines);
    assert!(p.items().is_empty());
    assert_eq!(p.queued().len(), 10);
    assert_eq!(p.health(), herdr_chat::Health::Ok);
}
