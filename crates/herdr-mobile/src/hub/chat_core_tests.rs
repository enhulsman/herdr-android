//! The chat's state, driven with made-up terminals, lookups and reads
//! (chat-view spec: lookup, /clear, exit, bounded reading, live updates,
//! held chats, health guard; design D3, D5, D6, D12).

use super::*;
use herdr_chat::tail::Chunk;

const S1: &str = "aaaaaaaa-1111-4111-8111-111111111111";
const S2: &str = "cccccccc-3333-4333-8333-333333333333";

fn path(s: &str) -> String {
    format!("/u/.claude/projects/-work-website/{s}.jsonl")
}
fn found(s: &str) -> Lookup {
    Lookup::Found { path: path(s), session: s.into() }
}
fn term(status: &str) -> Term {
    Term { agent: Some(Agent::Claude), pid: Some(4242), status: Some(status.into()) }
}
fn asked(text: &str) -> String {
    format!("{{\"type\":\"user\",\"sessionId\":\"s\",\"message\":{{\"role\":\"user\",\"content\":\"{text}\"}}}}")
}
fn said(id: &str, text: &str) -> String {
    format!(
        "{{\"type\":\"assistant\",\"sessionId\":\"s\",\"message\":{{\"id\":\"{id}\",\"role\":\"assistant\",\"content\":[{{\"type\":\"text\",\"text\":\"{text}\"}}]}}}}"
    )
}
/// Unknown records: what a renamed message record looks like to the parser.
fn renamed(text: &str) -> String {
    format!("{{\"type\":\"chat_turn\",\"sessionId\":\"s\",\"body\":\"{text}\"}}")
}

/// A read of `lines` starting at byte `at` of a log `size` bytes long.
fn chunk(lines: &[String], at: u64, size: u64) -> Chunk {
    let mut text = String::new();
    let mut offsets = Vec::new();
    for l in lines {
        offsets.push(at + text.len() as u64);
        text.push_str(l);
        text.push('\n');
    }
    let next = at + text.len() as u64;
    Chunk { text, offsets, skipped: None, from: at, next, size: size.max(next) }
}
fn end_of(c: &Chunk) -> u64 {
    c.next
}

fn texts(v: &View) -> Vec<String> {
    v.rows
        .iter()
        .map(|r| match &r.item {
            Item::User { text, .. } => format!("user {text}"),
            Item::Assistant { text } => format!("said {text}"),
            Item::Note { text } => format!("note {text}"),
            other => format!("{other:?}"),
        })
        .collect()
}

/// A chat that looked up S1 and read `lines` from the log's start.
fn opened(lines: &[String], now: Instant) -> (ChatCore, Chunk) {
    let mut c = ChatCore::new();
    c.take_lookup(&term("idle"), Some(found(S1)), now);
    c.see(&term("idle"), now);
    let ch = chunk(lines, 0, 0);
    assert_eq!(c.plan_read(), Some(Read { path: path(S1), start: "-2000000".into(), end: None }));
    c.take_read(Ok(ch.clone()), now);
    (c, ch)
}

// --- Lookups (D5; spec: "/clear and new sessions are followed") ---

#[test]
fn the_lookup_runs_on_open_on_a_new_process_every_10_s_and_for_5_s_after_a_slash_command() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    assert!(c.needs_lookup(&term("idle"), false, t0), "first poll");
    c.take_lookup(&term("idle"), Some(found(S1)), t0);
    assert!(!c.needs_lookup(&term("idle"), false, t0 + Duration::from_secs(9)));
    assert!(!c.needs_lookup(&term("working"), true, t0 + Duration::from_secs(9)), "an event while Found");
    assert!(c.needs_lookup(&term("idle"), false, t0 + Duration::from_secs(10)), "every 10 s");
    let mut other = term("idle");
    other.pid = Some(5150);
    assert!(c.needs_lookup(&other, false, t0 + Duration::from_secs(1)), "another process in front");
    c.sent("/clear", t0 + Duration::from_secs(2));
    assert!(c.needs_lookup(&term("idle"), false, t0 + Duration::from_secs(3)));
    assert!(c.needs_lookup(&term("idle"), false, t0 + Duration::from_millis(6_900)));
    c.take_lookup(&term("idle"), Some(found(S1)), t0 + Duration::from_millis(7_000));
    assert!(!c.needs_lookup(&term("idle"), false, t0 + Duration::from_millis(7_100)), "5 s are over");
    c.sent("Fix the /api route", t0 + Duration::from_secs(8));
    assert!(!c.needs_lookup(&term("idle"), false, t0 + Duration::from_secs(9)), "not a command");
}

#[test]
fn an_event_looks_up_again_while_empty_or_missed() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    c.take_lookup(&term("idle"), Some(Lookup::Empty { session: S1.into() }), t0);
    assert!(c.needs_lookup(&term("working"), true, t0 + Duration::from_secs(1)));
    assert!(!c.needs_lookup(&term("working"), false, t0 + Duration::from_secs(1)), "a timed poll waits for 10 s");
    c.take_lookup(&term("idle"), Some(Lookup::Missed("no session file")), t0);
    assert!(c.needs_lookup(&term("idle"), true, t0 + Duration::from_secs(1)));
}

#[test]
fn empty_shows_no_messages_and_becomes_the_conversation_once_its_log_exists() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    c.take_lookup(&term("idle"), Some(Lookup::Empty { session: S1.into() }), t0);
    let v = c.view();
    assert_eq!((v.found, v.rows.len(), v.health.clone()), (Kind::Empty, 0, Health::Ok));
    assert_eq!(c.plan_read(), None, "no log to read yet");
    assert_eq!(c.shown(), Some((Agent::Claude, S1.into())), "Empty follows the send rules");
    c.take_lookup(&term("working"), Some(found(S1)), t0);
    assert_eq!(c.plan_read().map(|r| r.path), Some(path(S1)));
    c.take_read(Ok(chunk(&[asked("Hi")], 0, 0)), t0);
    assert_eq!(texts(&c.view()), ["user Hi"]);
}

#[test]
fn empty_turns_missed_when_the_first_turn_ends_without_a_log() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    let empty = || Some(Lookup::Empty { session: S1.into() });
    c.take_lookup(&term("idle"), empty(), t0);
    c.see(&term("idle"), t0);
    c.see(&term("working"), t0 + Duration::from_secs(1));
    c.take_lookup(&term("working"), empty(), t0 + Duration::from_secs(1));
    assert_eq!(c.view().found, Kind::Empty, "still in its first turn");
    c.see(&term("idle"), t0 + Duration::from_secs(9));
    c.take_lookup(&term("idle"), empty(), t0 + Duration::from_secs(9));
    assert_eq!(c.view().found, Kind::Missed);
    // Another session (/clear) is Empty again until its own first turn ends.
    c.take_lookup(&term("idle"), Some(Lookup::Empty { session: S2.into() }), t0 + Duration::from_secs(10));
    assert_eq!(c.view().found, Kind::Empty);
}

#[test]
fn a_new_session_replaces_the_held_conversation() {
    let t0 = Instant::now();
    let (mut c, _) = opened(&[asked("Hi"), said("m1", "Hello")], t0);
    c.sent("/clear", t0);
    c.take_lookup(&term("idle"), Some(found(S2)), t0 + Duration::from_secs(1));
    assert_eq!(c.view().rows.len(), 0, "the held rows are gone");
    assert_eq!(c.plan_read(), Some(Read { path: path(S2), start: "-2000000".into(), end: None }), "the new log from its end");
    c.take_read(Ok(chunk(&[asked("/clear")], 0, 0)), t0 + Duration::from_secs(1));
    assert_eq!(c.shown(), Some((Agent::Claude, S2.into())));
}

#[test]
fn the_same_session_found_again_keeps_the_rows_and_reads_on() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    c.take_lookup(&term("idle"), Some(found(S1)), t0 + Duration::from_secs(10));
    assert_eq!(c.view().rows.len(), 1);
    assert_eq!(c.plan_read().unwrap().start, end_of(&first).to_string());
}

#[test]
fn a_desk_that_does_not_answer_keeps_the_chat_and_says_so() {
    let t0 = Instant::now();
    let (mut c, _) = opened(&[asked("Hi")], t0);
    c.take_lookup(&term("idle"), None, t0 + Duration::from_secs(10));
    let v = c.view();
    assert_eq!((v.found, v.rows.len()), (Kind::Found, 1));
    assert_eq!(v.problem.as_deref(), Some(FAILED_READ));
    // With nothing held it is Missed.
    let mut fresh = ChatCore::new();
    fresh.take_lookup(&term("idle"), None, t0);
    assert_eq!(fresh.view().found, Kind::Missed);
}

// --- Exit (spec: "After the agent exits, the chat stays read-only") ---

#[test]
fn an_exit_keeps_what_was_read_until_another_agent_starts() {
    let t0 = Instant::now();
    let (mut c, _) = opened(&[asked("Hi"), said("m1", "Hello")], t0);
    let shell = Term { agent: None, pid: Some(777), status: None };
    assert!(c.needs_lookup(&shell, false, t0), "another process in front");
    c.take_lookup(&shell, Some(Lookup::None), t0);
    let v = c.view();
    assert!(v.exited);
    assert_eq!(v.rows.len(), 2, "the conversation stays");
    // A new agent in the terminal with its own session.
    c.take_lookup(&term("idle"), Some(found(S2)), t0 + Duration::from_secs(5));
    let v = c.view();
    assert!(!v.exited);
    assert_eq!(v.rows.len(), 0);
    // Nothing held: a shell is just None.
    let mut none = ChatCore::new();
    none.take_lookup(&shell, Some(Lookup::None), t0);
    let v = none.view();
    assert_eq!((v.found, v.exited), (Kind::None, false));
}

// --- Reading (spec: "Reading is bounded and starts from the end") ---

#[test]
fn later_reads_take_only_new_lines_at_most_7_5_mb() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    let at = end_of(&first);
    assert_eq!(c.plan_read(), Some(Read { path: path(S1), start: at.to_string(), end: Some(at + READ_MAX) }));
    c.take_read(Ok(chunk(&[said("m1", "Hello")], at, 0)), t0);
    assert_eq!(texts(&c.view()), ["user Hi", "said Hello"]);
}

#[test]
fn a_record_too_large_is_a_note_in_its_place_and_reading_goes_on() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    let at = end_of(&first);
    let big = 9_000_000;
    let mut ch = chunk(&[], at, at + big);
    ch.skipped = Some((at, big));
    ch.from = at + big;
    ch.next = at + big;
    c.take_read(Ok(ch), t0);
    assert_eq!(c.plan_read().unwrap().start, (at + big).to_string(), "goes on after it");
    c.take_read(Ok(chunk(&[said("m1", "After")], at + big, 0)), t0);
    assert_eq!(texts(&c.view()), ["user Hi", format!("note {SKIPPED}").as_str(), "said After"]);
}

#[test]
fn a_failed_read_keeps_the_chat_until_a_read_succeeds() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    c.take_read(Err("Couldn't read the log: it took too long".into()), t0);
    let v = c.view();
    assert_eq!(v.problem.as_deref(), Some(FAILED_READ));
    assert_eq!(v.rows.len(), 1);
    assert_eq!(c.plan_read().unwrap().start, end_of(&first).to_string(), "the same read again");
    c.take_read(Ok(chunk(&[], end_of(&first), 0)), t0);
    assert_eq!(c.view().problem, None);
}

#[test]
fn rows_keep_their_keys_as_the_log_grows() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi"), said("m1", "Hello")], t0);
    let keys: Vec<Key> = c.view().rows.iter().map(|r| r.key).collect();
    assert_eq!(keys, [(0, 0), (first.offsets[1], 0)]);
    c.take_read(Ok(chunk(&[asked("More")], end_of(&first), 0)), t0);
    let now: Vec<Key> = c.view().rows.iter().map(|r| r.key).collect();
    assert_eq!(&now[..2], &keys[..]);
}

// --- Reading back ---

#[test]
fn reading_back_puts_older_rows_in_front_and_keeps_the_keys_held() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    c.take_lookup(&term("idle"), Some(found(S1)), t0);
    let older = chunk(&[asked("First"), said("m0", "One")], 0, 0);
    let recent = chunk(&[asked("Second"), said("m1", "Two")], end_of(&older), 0);
    c.take_read(Ok(recent.clone()), t0);
    let held: Vec<Key> = c.view().rows.iter().map(|r| r.key).collect();
    assert!(c.view().cut);
    assert_eq!(c.plan_older(), Some(Read { path: path(S1), start: "0".into(), end: Some(end_of(&older)) }));
    assert!(!c.take_older(Ok(older)));
    let v = c.view();
    assert_eq!(texts(&v), ["user First", "said One", "user Second", "said Two"]);
    assert_eq!(v.rows[2..].iter().map(|r| r.key).collect::<Vec<_>>(), held);
    assert!(!v.cut, "the start is read");
    assert_eq!(c.plan_older(), None);
}

#[test]
fn a_read_back_that_finds_no_whole_line_reads_wider() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    c.take_lookup(&term("idle"), Some(found(S1)), t0);
    let at = 5_000_000;
    c.take_read(Ok(chunk(&[asked("Recent")], at, 0)), t0);
    assert_eq!(c.plan_older().unwrap().start, (at - OLDER_BUDGET).to_string());
    // Nothing whole in the window: the script reports `from` at the end.
    let none = Chunk { text: String::new(), offsets: vec![], skipped: None, from: at, next: at, size: at + 100 };
    assert!(c.take_older(Ok(none)), "read again");
    assert_eq!(c.plan_older().unwrap().start, (at - 2 * OLDER_BUDGET).to_string());
}

/// A read of a real file through the tail script, as `machine.exec` runs it.
fn read_real(r: &Read) -> Result<Chunk, String> {
    let mut cmd = std::process::Command::new("sh");
    cmd.arg("-c").arg(herdr_chat::tail::SCRIPT).arg("sh").arg(&r.path).arg(&r.start);
    if let Some(e) = r.end {
        cmd.arg(e.to_string());
    }
    let out = cmd.env("LC_ALL", "C.UTF-8").output().map_err(|e| e.to_string())?;
    herdr_chat::tail::parse_chunk(&String::from_utf8_lossy(&out.stdout)).ok_or_else(|| "unexpected".into())
}

/// Opens a real log at its end and reads back until the start or a stop.
fn read_back_all(name: &str, log: &str) -> View {
    let dir = std::env::temp_dir().join(format!("herdr-core-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let f = dir.join(name);
    std::fs::write(&f, log).unwrap();
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    let l = Lookup::Found { path: f.to_str().unwrap().into(), session: S1.into() };
    c.take_lookup(&term("idle"), Some(l), t0);
    let r = c.plan_read().unwrap();
    c.take_read(read_real(&r), t0);
    // Each outer turn is one Chat::older (the person reaching the top).
    for _ in 0..10 {
        let mut reads = 0;
        while let Some(r) = c.plan_older() {
            reads += 1;
            assert!(reads < 40, "a read back that never ends");
            if !c.take_older(read_real(&r)) {
                break;
            }
        }
    }
    c.view()
}

/// One JSON line of about `bytes` bytes.
fn padded(kind: &str, bytes: usize) -> String {
    format!("{{\"type\":\"{kind}\",\"pad\":\"{}\"}}", "x".repeat(bytes))
}

#[test]
fn reading_back_crosses_a_request_record_over_the_read_limit() {
    // Found in the review: an older message, a 9 MB api-request record, a recent one.
    let log = format!("{}\n{}\n{}\n", asked("Old"), padded("api-request", 9_000_000), asked("Recent"));
    let v = read_back_all("big-request.jsonl", &log);
    assert_eq!(texts(&v), ["user Old", "user Recent"]);
    assert!(!v.cut, "the start is read");
}

#[test]
fn reading_back_crosses_a_message_over_the_read_limit_with_a_note() {
    let log = format!("{}\n{}\n{}\n", asked("Old"), padded("user", 9_000_000), asked("Recent"));
    let v = read_back_all("big-message.jsonl", &log);
    assert_eq!(texts(&v), ["user Old".to_string(), format!("note {SKIPPED}"), "user Recent".to_string()]);
}

#[test]
fn reading_back_crosses_many_records_it_does_not_show() {
    // 10 MB of request records, each small: every window holds only them.
    let reqs: String = (0..20).map(|_| padded("api-request", 500_000) + "\n").collect();
    let log = format!("{}\n{reqs}{}\n", asked("Old"), asked("Recent"));
    let v = read_back_all("many-requests.jsonl", &log);
    assert_eq!(texts(&v), ["user Old", "user Recent"]);
}

#[test]
fn a_failed_read_back_stops_with_a_note_at_the_top() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    c.take_lookup(&term("idle"), Some(found(S1)), t0);
    c.take_read(Ok(chunk(&[asked("Recent")], 4000, 0)), t0);
    assert!(!c.take_older(Err("Couldn't read the log: it took too long".into())));
    let v = c.view();
    assert_eq!(texts(&v)[0], format!("note {OLDER_FAILED}"));
    assert!(!v.cut);
    assert_eq!(c.plan_older(), None, "does not ask again and again");
}

// --- Health, per-turn rule (D6) ---

#[test]
fn a_turn_that_brings_no_row_is_broken_5_s_after_it_ends() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi"), said("m1", "Hello")], t0);
    let mut at = end_of(&first);
    c.see(&term("working"), t0 + Duration::from_secs(1));
    let ch = chunk(&[renamed("Hi again"), renamed("Hello again")], at, 0);
    at = end_of(&ch);
    c.take_read(Ok(ch), t0 + Duration::from_secs(2));
    c.see(&term("idle"), t0 + Duration::from_secs(3));
    c.take_read(Ok(chunk(&[], at, 0)), t0 + Duration::from_secs(4));
    assert_eq!(c.view().health, Health::Ok, "within the 5 s");
    c.see(&term("idle"), t0 + Duration::from_secs(8));
    c.take_read(Ok(chunk(&[], at, 0)), t0 + Duration::from_secs(8));
    assert!(matches!(c.view().health, Health::Broken { .. }), "{:?}", c.view().health);
    assert_eq!(c.view().rows.len(), 2, "keeps what was read");
}

#[test]
fn a_turn_whose_row_comes_within_5_s_of_its_end_is_ok() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    let at = end_of(&first);
    c.see(&term("working"), t0 + Duration::from_secs(1));
    c.see(&term("idle"), t0 + Duration::from_secs(3));
    c.take_read(Ok(chunk(&[said("m1", "Hello")], at, 0)), t0 + Duration::from_secs(5));
    c.see(&term("idle"), t0 + Duration::from_secs(9));
    c.take_read(Ok(chunk(&[], at, 0)), t0 + Duration::from_secs(9));
    assert_eq!(c.view().health, Health::Ok);
}

#[test]
fn a_turn_started_by_a_command_from_the_chat_is_not_held_to_the_rule() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    let at = end_of(&first);
    c.sent("/compact", t0 + Duration::from_secs(1));
    c.see(&term("working"), t0 + Duration::from_secs(2));
    c.see(&term("idle"), t0 + Duration::from_secs(4));
    c.see(&term("idle"), t0 + Duration::from_secs(10));
    c.take_read(Ok(chunk(&[], at, 0)), t0 + Duration::from_secs(10));
    assert_eq!(c.view().health, Health::Ok);
}

#[test]
fn turns_unseen_while_the_chat_was_away_are_not_judged() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    let at = end_of(&first);
    c.see(&term("working"), t0 + Duration::from_secs(1));
    c.leave();
    // Back after the turn ended: idle is the first status seen.
    c.take_lookup(&term("idle"), Some(found(S1)), t0 + Duration::from_secs(30));
    c.see(&term("idle"), t0 + Duration::from_secs(30));
    while c.take_read(Ok(chunk(&[], at, 0)), t0 + Duration::from_secs(30)) {}
    c.see(&term("idle"), t0 + Duration::from_secs(40));
    c.take_read(Ok(chunk(&[], at, 0)), t0 + Duration::from_secs(40));
    assert_eq!(c.view().health, Health::Ok);
}

// --- The terminal view (D5; spec "Live updates follow the agent": terminal view on screen) ---

#[test]
fn the_terminal_view_looks_up_on_opening_and_again_only_for_another_process() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    assert!(c.needs_watch(&term("idle"), false), "on opening");
    c.take_watch_lookup(&term("idle"), Some(found(S1)), t0);
    assert!(!c.needs_watch(&term("working"), true), "an event while Found");
    assert!(!c.needs_watch(&term("working"), false), "no lookup every 10 s");
    let mut other = term("idle");
    other.pid = Some(5150);
    assert!(c.needs_watch(&other, false), "another process in front");
    c.take_watch_lookup(&other, Some(Lookup::None), t0);
    assert!(!c.needs_watch(&other, true), "a shell in front: an event looks up nothing");
}

#[test]
fn the_terminal_view_looks_up_on_each_event_while_empty_or_missed_with_an_agent_in_front() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    c.take_watch_lookup(&term("idle"), Some(Lookup::Empty { session: S1.into() }), t0);
    assert!(c.needs_watch(&term("working"), true));
    assert!(!c.needs_watch(&term("working"), false), "only on an event");
    c.take_watch_lookup(&term("idle"), Some(Lookup::Missed("no session file")), t0);
    assert!(c.needs_watch(&term("idle"), true));
    let shell = Term { agent: None, ..term("idle") };
    assert!(!c.needs_watch(&shell, true), "no agent in front");
}

#[test]
fn empty_turns_missed_when_its_first_turn_ends_in_the_terminal_view() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    let empty = || Some(Lookup::Empty { session: S1.into() });
    c.take_watch_lookup(&term("idle"), empty(), t0);
    c.watch_see(&term("idle"));
    c.watch_see(&term("working"));
    c.take_watch_lookup(&term("working"), empty(), t0);
    assert_eq!(c.view().found, Kind::Empty, "still in its first turn");
    c.watch_see(&term("idle"));
    c.take_watch_lookup(&term("idle"), empty(), t0);
    assert_eq!(c.view().found, Kind::Missed);
}

#[test]
fn a_turn_watched_from_the_terminal_view_is_not_judged_by_the_chat() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    let at = end_of(&first);
    c.leave();
    c.watch_see(&term("working"));
    c.watch_see(&term("idle"));
    // Back in the chat: the turn ended unseen by it and brought no row.
    c.take_lookup(&term("idle"), Some(found(S1)), t0 + Duration::from_secs(30));
    c.see(&term("idle"), t0 + Duration::from_secs(30));
    while c.take_read(Ok(chunk(&[], at, 0)), t0 + Duration::from_secs(30)) {}
    c.see(&term("idle"), t0 + Duration::from_secs(40));
    c.take_read(Ok(chunk(&[], at, 0)), t0 + Duration::from_secs(40));
    assert_eq!(c.view().health, Health::Ok);
}

#[test]
fn a_lookup_from_the_terminal_view_leaves_the_chat_its_own_lookup_on_return() {
    let t0 = Instant::now();
    let (mut c, _) = opened(&[asked("Hi")], t0);
    c.leave();
    let mut other = term("idle");
    other.pid = Some(5150);
    c.take_watch_lookup(&other, Some(found(S1)), t0 + Duration::from_secs(1));
    assert!(c.needs_lookup(&other, false, t0 + Duration::from_secs(2)), "a lookup on return");
}

// --- Held chats (D12; spec: "A chat is held while the terminal view shows") ---

#[test]
fn on_return_a_chat_looks_up_and_reads_only_what_was_written_since() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    let at = end_of(&first);
    c.leave();
    assert!(c.needs_lookup(&term("idle"), false, t0 + Duration::from_secs(1)), "a lookup on return");
    c.take_lookup(&term("idle"), Some(found(S1)), t0 + Duration::from_secs(1));
    // First the log's size: an empty read at the held end.
    assert_eq!(c.plan_read(), Some(Read { path: path(S1), start: at.to_string(), end: Some(at) }));
    assert!(c.take_read(Ok(chunk(&[], at, at + 5000)), t0), "then the new lines");
    assert_eq!(c.plan_read().unwrap().start, at.to_string());
    c.take_read(Ok(chunk(&[said("m1", "Two answers")], at, 0)), t0);
    assert_eq!(texts(&c.view()), ["user Hi", "said Two answers"]);
}

#[test]
fn on_return_past_more_than_2_mb_the_last_2_mb_are_read_as_on_a_first_open() {
    let t0 = Instant::now();
    let (mut c, first) = opened(&[asked("Hi")], t0);
    let at = end_of(&first);
    c.leave();
    c.take_lookup(&term("idle"), Some(found(S1)), t0);
    assert!(c.take_read(Ok(chunk(&[], at, at + FIRST_BUDGET + 1)), t0));
    assert_eq!(c.plan_read().unwrap().start, "-2000000");
    assert_eq!(c.view().rows.len(), 0, "the held rows are not kept across the gap");
}

#[test]
fn rows_read_back_beyond_the_last_2_mb_are_dropped_on_leaving() {
    let t0 = Instant::now();
    let mut c = ChatCore::new();
    c.take_lookup(&term("idle"), Some(found(S1)), t0);
    let recent_at = 3_000_000;
    let recent = chunk(&[asked("Recent"), said("m1", "Yes")], recent_at, 0);
    c.take_read(Ok(recent.clone()), t0);
    let held: Vec<Key> = c.view().rows.iter().map(|r| r.key).collect();
    c.take_older(Ok(chunk(&[asked("Old")], 0, 0)));
    assert_eq!(c.view().rows.len(), 3);
    c.leave();
    let v = c.view();
    assert_eq!(texts(&v), ["user Recent", "said Yes"]);
    assert_eq!(v.rows.iter().map(|r| r.key).collect::<Vec<_>>(), held);
    assert!(v.cut);
}

#[test]
fn the_ninth_terminal_drops_the_one_viewed_longest_ago() {
    let mut h: Held<u32> = Held::new();
    for i in 0..8 {
        h.open(&format!("m/t{i}"), || i);
    }
    assert_eq!(h.open("m/t0", || 99), 0, "held, not made again");
    h.open("m/t8", || 8);
    let keys = h.keys();
    assert_eq!(keys.len(), HELD);
    assert!(!keys.contains(&"m/t1".to_string()), "t1 was viewed longest ago");
    assert!(keys.contains(&"m/t0".to_string()));
    assert_eq!(h.open("m/t1", || 11), 11, "read again when next opened");
}

#[test]
fn a_memory_trim_keeps_only_the_chat_on_screen() {
    let mut h: Held<u32> = Held::new();
    for i in 0..4 {
        h.open(&format!("m/t{i}"), || i);
    }
    h.trim(Some("m/t2"));
    assert_eq!(h.keys(), ["m/t2"]);
    h.trim(None);
    assert!(h.keys().is_empty());
}

#[test]
fn the_view_names_what_claude_code_holds_queued() {
    let t0 = Instant::now();
    let q = "{\"type\":\"queue-operation\",\"operation\":\"enqueue\",\"sessionId\":\"s\",\"content\":\"Say SECOND\"}".to_string();
    let (c, _) = opened(&[asked("Hi"), q], t0);
    assert_eq!(c.view().queued, ["Say SECOND"]);
}
