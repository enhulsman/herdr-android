//! The health guard (design D6): whole-log rules over what has been read,
//! and a per-turn rule for a format that changed after messages were shown.

use herdr_chat::{Agent, Health, Item, Parser};

fn j(s: &str) -> String {
    serde_json::to_string(s).unwrap()
}

// ---- Claude Code lines

fn asked(text: &str) -> String {
    format!("{{\"type\":\"user\",\"sessionId\":\"s\",\"message\":{{\"role\":\"user\",\"content\":{}}}}}", j(text))
}
fn said(id: &str, text: &str) -> String {
    format!(
        "{{\"type\":\"assistant\",\"sessionId\":\"s\",\"message\":{{\"id\":\"m-{id}\",\"role\":\"assistant\",\"content\":[{{\"type\":\"text\",\"text\":{}}}]}}}}",
        j(text)
    )
}
fn ran(id: &str, cmd: &str) -> String {
    format!(
        "{{\"type\":\"assistant\",\"sessionId\":\"s\",\"message\":{{\"id\":\"m-{id}\",\"role\":\"assistant\",\"content\":[{{\"type\":\"tool_use\",\"id\":\"{id}\",\"name\":\"Bash\",\"input\":{{\"command\":{}}}}}]}}}}",
        j(cmd)
    )
}
fn failed(id: &str, out: &str) -> String {
    format!(
        "{{\"type\":\"user\",\"sessionId\":\"s\",\"message\":{{\"role\":\"user\",\"content\":[{{\"type\":\"tool_result\",\"tool_use_id\":\"{id}\",\"is_error\":true,\"content\":{}}}]}}}}",
        j(out)
    )
}
/// A record this parser does not know, as a renamed message record would be.
fn renamed(n: usize) -> String {
    format!("{{\"type\":\"chat-turn\",\"sessionId\":\"s\",\"uuid\":\"r{n}\",\"body\":{{\"text\":\"Done.\"}}}}")
}
/// Bookkeeping: lines Claude Code writes around messages, never a row.
fn snapshot(n: usize) -> String {
    format!("{{\"type\":\"file-history-snapshot\",\"messageId\":\"m{n}\",\"snapshot\":{{}}}}")
}
fn compact_boundary() -> String {
    "{\"type\":\"system\",\"subtype\":\"compact_boundary\",\"sessionId\":\"s\",\"isSidechain\":false,\"content\":\"Conversation compacted\"}".into()
}
fn compact_summary() -> String {
    "{\"type\":\"user\",\"sessionId\":\"s\",\"isCompactSummary\":true,\"message\":{\"role\":\"user\",\"content\":\"This session is being continued from a previous conversation.\"}}".into()
}
fn interrupted() -> String {
    asked("[Request interrupted by user]")
}

// ---- Codex lines

fn codex_item(item: &str) -> String {
    format!("{{\"type\":\"event_msg\",\"payload\":{{\"type\":\"item_completed\",\"turn_id\":\"t\",\"item\":{item}}}}}")
}
fn codex_asked(text: &str) -> String {
    codex_item(&format!("{{\"type\":\"UserMessage\",\"id\":\"u\",\"content\":[{{\"type\":\"text\",\"text\":{}}}]}}", j(text)))
}
fn codex_said(text: &str) -> String {
    codex_item(&format!("{{\"type\":\"AgentMessage\",\"id\":\"a\",\"content\":[{{\"type\":\"Text\",\"text\":{}}}]}}", j(text)))
}
fn codex_ran(cmd: &str, source: &str) -> String {
    codex_item(&format!(
        "{{\"type\":\"CommandExecution\",\"id\":\"c\",\"command\":[\"/bin/bash\",\"-lc\",{c}],\"parsed_cmd\":[{{\"type\":\"unknown\",\"cmd\":{c}}}],\"aggregated_output\":\"\",\"exit_code\":0{source}}}",
        c = j(cmd)
    ))
}
fn codex_event(kind: &str) -> String {
    format!("{{\"type\":\"event_msg\",\"payload\":{{\"type\":\"{kind}\",\"turn_id\":\"t\"}}}}")
}
fn codex_renamed(n: usize) -> String {
    format!("{{\"type\":\"event_msg\",\"payload\":{{\"type\":\"turn_item_done\",\"n\":{n}}}}}")
}

/// A log being read: lines go in as the tail script gives them, with the
/// byte offset each starts at.
struct Log {
    text: String,
    at: Vec<u64>,
    lines: Vec<String>,
}

impl Log {
    fn new() -> Self {
        Log { text: String::new(), at: Vec::new(), lines: Vec::new() }
    }
    /// Lines written to the log; returns the index of the first.
    fn write(&mut self, lines: &[String]) -> usize {
        let first = self.lines.len();
        for l in lines {
            self.at.push(self.text.len() as u64);
            self.text.push_str(l);
            self.text.push('\n');
            self.lines.push(l.clone());
        }
        first
    }
    /// Lines `from..to` as one read.
    fn read(&self, p: &mut Parser, from: usize, to: usize) {
        let text: String = self.lines[from..to].iter().map(|l| format!("{l}\n")).collect();
        p.feed_lines(&text, &self.at[from..to]);
    }
    /// Write `lines` and read them at once.
    fn add(&mut self, p: &mut Parser, lines: &[String]) {
        let from = self.write(lines);
        self.read(p, from, self.lines.len());
    }
}

fn broken(p: &Parser) -> bool {
    matches!(p.health(), Health::Broken { .. })
}

/// A Claude Code session with a conversation on screen.
fn claude_session() -> (Log, Parser) {
    let mut log = Log::new();
    let mut p = Parser::new(Agent::Claude);
    log.add(&mut p, &[asked("Add a health route"), said("a", "Added `/health`.")]);
    assert_eq!(p.health(), Health::Ok);
    (log, p)
}

fn codex_session() -> (Log, Parser) {
    let mut log = Log::new();
    let mut p = Parser::new(Agent::Codex);
    log.add(
        &mut p,
        &[codex_event("task_started"), codex_asked("Add a health route"), codex_said("Added `/health`."), codex_event("task_complete")],
    );
    assert_eq!(p.health(), Health::Ok);
    (log, p)
}

/// The renamed-records turn: started while the chat is open, then a turn's
/// worth of records nothing can read, then the 5 s after it are read.
fn renamed_turn(log: &mut Log, p: &mut Parser, agent: Agent) {
    p.turn_started(false);
    let lines: Vec<String> = match agent {
        Agent::Claude => (0..6).map(renamed).collect(),
        Agent::Codex => (0..6).map(codex_renamed).collect(),
    };
    log.add(p, &lines);
    p.turn_ended();
}

// ---- whole-log rules

#[test]
fn a_recent_window_of_bookkeeping_only_is_not_broken_before_reading_back() {
    let mut log = Log::new();
    log.write(&[asked("Add a health route"), said("a", "Added `/health`.")]);
    let window = log.write(&(0..40).map(snapshot).collect::<Vec<_>>());
    // The last 2 MB: 40 bookkeeping lines, no message, start not read.
    let mut p = Parser::new(Agent::Claude);
    log.read(&mut p, window, log.lines.len());
    assert!(p.items().is_empty());
    assert_eq!(p.health(), Health::Ok, "judged before reading back");
    // Read back to the start: the older messages show, and it is fine.
    let mut back = Parser::new(Agent::Claude);
    log.read(&mut back, 0, log.lines.len());
    assert_eq!(back.items().len(), 2);
    assert_eq!(back.health(), Health::Ok);
}

#[test]
fn bookkeeping_only_from_the_start_is_broken() {
    for agent in [Agent::Claude, Agent::Codex] {
        let mut log = Log::new();
        let lines: Vec<String> = match agent {
            Agent::Claude => (0..40).map(snapshot).collect(),
            Agent::Codex => (0..40).map(codex_renamed).collect(),
        };
        log.write(&lines);
        let mut p = Parser::new(agent);
        log.read(&mut p, 0, 40);
        assert!(broken(&p), "{agent:?}: {:?}", p.health());
        // Whole-log Broken clears when those rules read Ok again.
        let more = match agent {
            Agent::Claude => vec![asked("Hi"), said("a", "Hello")],
            Agent::Codex => vec![codex_asked("Hi"), codex_said("Hello")],
        };
        log.add(&mut p, &more);
        assert_eq!(p.health(), Health::Ok, "{agent:?}");
    }
}

#[test]
fn a_log_mostly_in_no_known_shape_clears_once_most_lines_are_known() {
    let mut log = Log::new();
    let mut p = Parser::new(Agent::Claude);
    log.add(&mut p, &(0..6).map(|i| format!("not json {i}")).collect::<Vec<_>>());
    assert!(broken(&p));
    log.add(&mut p, &(0..8).map(|i| said(&i.to_string(), "ok")).collect::<Vec<_>>());
    assert!(!broken(&p), "{:?}", p.health());
}

// ---- per-turn rule: must trip

#[test]
fn renamed_message_records_after_messages_were_shown_break_the_view() {
    for (agent, (mut log, mut p)) in [(Agent::Claude, claude_session()), (Agent::Codex, codex_session())] {
        let shown = p.items().to_vec();
        renamed_turn(&mut log, &mut p, agent);
        assert!(broken(&p), "{agent:?}: {:?}", p.health());
        assert_eq!(p.items(), shown.as_slice(), "{agent:?}: what was read stays");
    }
}

#[test]
fn a_turn_broken_view_stays_broken_across_reads_with_no_new_row() {
    for (agent, (mut log, mut p)) in [(Agent::Claude, claude_session()), (Agent::Codex, codex_session())] {
        renamed_turn(&mut log, &mut p, agent);
        for n in 0..5 {
            let line = match agent {
                Agent::Claude => vec![renamed(100 + n), snapshot(n)],
                Agent::Codex => vec![codex_renamed(100 + n), codex_event("token_count")],
            };
            log.add(&mut p, &line);
            assert!(broken(&p), "{agent:?}, read {n}: {:?}", p.health());
        }
    }
}

#[test]
fn the_persons_own_message_or_shell_command_does_not_clear_it() {
    let (mut log, mut p) = claude_session();
    renamed_turn(&mut log, &mut p, Agent::Claude);
    log.add(&mut p, &[asked("Are you there?")]);
    assert!(broken(&p), "a person's row");
    log.add(&mut p, &[asked("<bash-input>ls</bash-input>"), asked("<bash-stdout>src</bash-stdout><bash-stderr></bash-stderr>")]);
    assert!(broken(&p), "a `!` command");
    assert!(matches!(p.items().last(), Some(Item::Tools { .. })), "both still read: {:?}", p.items());

    let (mut log, mut p) = codex_session();
    renamed_turn(&mut log, &mut p, Agent::Codex);
    log.add(&mut p, &[codex_asked("Are you there?")]);
    assert!(broken(&p), "a person's row");
    log.add(&mut p, &[codex_ran("ls", ",\"source\":\"user_shell\"")]);
    assert!(broken(&p), "a `!` command");
}

#[test]
fn a_new_row_the_agent_produced_clears_it() {
    // Its text.
    let (mut log, mut p) = claude_session();
    renamed_turn(&mut log, &mut p, Agent::Claude);
    log.add(&mut p, &[said("b", "Back.")]);
    assert_eq!(p.health(), Health::Ok);

    // A tool call of its own, even folded into the `!` command's row.
    let (mut log, mut p) = codex_session();
    renamed_turn(&mut log, &mut p, Agent::Codex);
    log.add(&mut p, &[codex_ran("ls", ",\"source\":\"user_shell\"")]);
    assert!(broken(&p));
    log.add(&mut p, &[codex_ran("cargo test", "")]);
    assert_eq!(p.health(), Health::Ok);

    // A note.
    let (mut log, mut p) = claude_session();
    renamed_turn(&mut log, &mut p, Agent::Claude);
    log.add(&mut p, &[compact_boundary()]);
    assert_eq!(p.health(), Health::Ok);
}

#[test]
fn a_turn_broken_view_is_still_broken_in_a_read_back() {
    let (mut log, mut p) = claude_session();
    renamed_turn(&mut log, &mut p, Agent::Claude);
    // Rows read back from before the break are no new rows.
    let mut back = Parser::new(Agent::Claude);
    back.carry_from(&p);
    log.read(&mut back, 0, log.lines.len());
    assert!(broken(&back), "{:?}", back.health());
    assert_eq!(back.items(), p.items());
    // One written after it is.
    log.add(&mut back, &[said("b", "Back.")]);
    assert_eq!(back.health(), Health::Ok);
}

#[test]
fn turn_broken_outranks_degraded() {
    let (mut log, mut p) = codex_session();
    log.add(&mut p, &[codex_item("{\"type\":\"ShinyNewThing\",\"id\":\"x\"}")]);
    assert!(matches!(p.health(), Health::Degraded { .. }));
    renamed_turn(&mut log, &mut p, Agent::Codex);
    assert!(broken(&p), "{:?}", p.health());
}

// ---- per-turn rule: must not trip

#[test]
fn an_interrupt_before_any_text_is_not_broken() {
    // The person's message was read before the status turned to working.
    let (mut log, mut p) = claude_session();
    log.add(&mut p, &[asked("Write the docs")]);
    p.turn_started(false);
    log.add(&mut p, &[interrupted()]);
    p.turn_ended();
    assert_eq!(p.health(), Health::Ok);
    assert_eq!(p.items().last(), Some(&Item::Note { text: "Interrupted".into() }));

    let (mut log, mut p) = codex_session();
    log.add(&mut p, &[codex_event("task_started"), codex_asked("Write the docs")]);
    p.turn_started(false);
    log.add(&mut p, &[codex_event("turn_aborted")]);
    p.turn_ended();
    assert_eq!(p.health(), Health::Ok);
    assert_eq!(p.items().last(), Some(&Item::Note { text: "Interrupted".into() }));
}

#[test]
fn compact_typed_in_the_terminal_is_not_broken() {
    let (mut log, mut p) = claude_session();
    p.turn_started(false);
    log.add(
        &mut p,
        &[
            asked("<command-name>/compact</command-name>\n<command-message>compact</command-message>\n<command-args></command-args>"),
            compact_boundary(),
            compact_summary(),
        ],
    );
    p.turn_ended();
    assert_eq!(p.health(), Health::Ok);
}

#[test]
fn a_turn_of_tool_calls_only_ending_in_a_failure_is_not_broken() {
    let (mut log, mut p) = claude_session();
    p.turn_started(false);
    log.add(&mut p, &[ran("t1", "cargo build"), failed("t1", "error[E0425]")]);
    p.turn_ended();
    assert_eq!(p.health(), Health::Ok);

    // Its calls fold into a tool row from before the turn: no new row, but
    // the row changed.
    let (mut log, mut p) = claude_session();
    log.add(&mut p, &[ran("t1", "cargo build")]);
    p.turn_started(false);
    log.add(&mut p, &[failed("t1", "error"), ran("t2", "cargo build"), failed("t2", "error")]);
    p.turn_ended();
    assert_eq!(p.health(), Health::Ok);
}

#[test]
fn a_codex_shell_command_typed_in_the_terminal_is_not_broken() {
    let (mut log, mut p) = codex_session();
    p.turn_started(false);
    log.add(&mut p, &[codex_ran("ls", ",\"source\":\"user_shell\"")]);
    p.turn_ended();
    assert_eq!(p.health(), Health::Ok);
}

#[test]
fn turns_started_from_the_chat_by_a_shell_or_slash_command_are_skipped() {
    for (agent, (mut log, mut p)) in [(Agent::Claude, claude_session()), (Agent::Codex, codex_session())] {
        // `/usage` writes nothing until its dialog closes; a `!` command in
        // Codex may write after the status settles.
        p.turn_started(true);
        let bookkeeping = match agent {
            Agent::Claude => snapshot(1),
            Agent::Codex => codex_event("token_count"),
        };
        log.add(&mut p, &[bookkeeping]);
        p.turn_ended();
        assert_eq!(p.health(), Health::Ok, "{agent:?}");
    }
}

#[test]
fn a_turn_starting_before_the_last_one_was_judged_counts_from_the_first() {
    let (mut log, mut p) = claude_session();
    p.turn_started(false);
    log.add(&mut p, &[said("b", "On it.")]);
    // Working again within the 5 s, nothing new since.
    p.turn_started(false);
    p.turn_ended();
    assert_eq!(p.health(), Health::Ok);
}

#[test]
fn an_end_without_a_start_judges_nothing() {
    let (_, mut p) = claude_session();
    p.turn_ended();
    assert_eq!(p.health(), Health::Ok);
}
