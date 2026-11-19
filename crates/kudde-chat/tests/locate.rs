//! Which log belongs to a terminal, from what the desk reports.

use kudde_chat::Agent;
use kudde_chat::locate::{self, EmptyGuard, Lookup, Probe};

fn claude_probe(pidfile: &str, proc_start: Option<u64>, logs: &[&str]) -> Probe {
    Probe {
        name: "claude".into(),
        claude_pidfile: Some(pidfile.into()),
        proc_start,
        claude_logs: logs.iter().map(|s| s.to_string()).collect(),
        codex_open: vec![],
    }
}

const PIDFILE: &str = r#"{"pid":4242,"sessionId":"aaaaaaaa-1111-4111-8111-111111111111","cwd":"/work/website","procStart":9001,"version":"2.1.323","kind":"interactive"}"#;

#[test]
fn claude_log_comes_from_the_pid_file() {
    let p = claude_probe(
        PIDFILE,
        Some(9001),
        &["/u/.claude/projects/-work-website/aaaaaaaa-1111-4111-8111-111111111111.jsonl"],
    );
    assert_eq!(
        locate::decide(&p),
        Lookup::Found {
            path: "/u/.claude/projects/-work-website/aaaaaaaa-1111-4111-8111-111111111111.jsonl"
                .into(),
            session: "aaaaaaaa-1111-4111-8111-111111111111".into(),
        }
    );
}

#[test]
fn claude_writes_proc_start_as_a_string() {
    // As Claude Code 2.1.322-2.1.323 write it: "procStart":"9001".
    let file = PIDFILE.replace("\"procStart\":9001", "\"procStart\":\"9001\"");
    let p = claude_probe(
        &file,
        Some(9001),
        &["/x/aaaaaaaa-1111-4111-8111-111111111111.jsonl"],
    );
    assert!(
        matches!(locate::decide(&p), Lookup::Found { .. }),
        "{:?}",
        locate::decide(&p)
    );
    let p = claude_probe(&file, Some(9002), &["/x/a.jsonl"]);
    assert_eq!(locate::decide(&p), Lookup::Missed("process start differs"));
}

#[test]
fn a_reused_pid_does_not_match() {
    // The file names a process that started at another time: a stale file whose
    // pid now belongs to someone else.
    let p = claude_probe(
        PIDFILE,
        Some(1234),
        &["/x/aaaaaaaa-1111-4111-8111-111111111111.jsonl"],
    );
    assert_eq!(locate::decide(&p), Lookup::Missed("process start differs"));
}

#[test]
fn a_session_with_no_log_yet_is_an_empty_conversation() {
    // Right after start or /clear, before the first message.
    let p = claude_probe(PIDFILE, Some(9001), &[]);
    assert_eq!(
        locate::decide(&p),
        Lookup::Empty {
            session: "aaaaaaaa-1111-4111-8111-111111111111".into()
        }
    );
}

#[test]
fn two_logs_with_the_same_session_id_are_refused() {
    let p = claude_probe(PIDFILE, Some(9001), &["/a/x.jsonl", "/b/x.jsonl"]);
    assert_eq!(locate::decide(&p), Lookup::Missed("several logs"));
}

fn codex_probe(open: &[(&str, u64)]) -> Probe {
    Probe {
        name: "codex".into(),
        claude_pidfile: None,
        proc_start: None,
        claude_logs: vec![],
        codex_open: open.iter().map(|(p, m)| (p.to_string(), *m)).collect(),
    }
}

const R1: &str = "/c/.codex/sessions/2026/11/13/rollout-2026-11-13T16-26-14-01a1d584-3784-7b73-991e-7e1205e3a546.jsonl";
const L1: &str = "/c/.codex/thread-writer-locks/01a1d584-3784-7b73-991e-7e1205e3a546.lock";
const R2: &str = "/c/.codex/sessions/2026/11/13/rollout-2026-11-13T16-31-13-01a1d588-c7a0-7f61-b91d-7bb85b1693dc.jsonl";
const L2: &str = "/c/.codex/thread-writer-locks/01a1d588-c7a0-7f61-b91d-7bb85b1693dc.lock";

#[test]
fn codex_log_is_the_rollout_it_holds_open() {
    assert_eq!(
        locate::decide(&codex_probe(&[(L1, 0), (R1, 100)])),
        Lookup::Found {
            path: R1.into(),
            session: "01a1d584-3784-7b73-991e-7e1205e3a546".into()
        }
    );
}

#[test]
fn after_new_codex_shows_the_new_thread_even_before_its_first_message() {
    // /new keeps the old rollout open and takes a lock for the new thread (the
    // lock's time is when /new ran); its rollout appears with the first message.
    assert_eq!(
        locate::decide(&codex_probe(&[(L1, 50), (R1, 100), (L2, 150)])),
        Lookup::Empty {
            session: "01a1d588-c7a0-7f61-b91d-7bb85b1693dc".into()
        }
    );
    assert_eq!(
        locate::decide(&codex_probe(&[(L1, 50), (R1, 100), (L2, 150), (R2, 200)])),
        Lookup::Found {
            path: R2.into(),
            session: "01a1d588-c7a0-7f61-b91d-7bb85b1693dc".into()
        }
    );
}

#[test]
fn new_in_the_same_second_as_the_old_threads_last_write_is_the_new_thread() {
    // A lock with no rollout yet came after the old thread's turn ended.
    assert_eq!(
        locate::decide(&codex_probe(&[(L1, 50), (R1, 100), (L2, 100)])),
        Lookup::Empty {
            session: "01a1d588-c7a0-7f61-b91d-7bb85b1693dc".into()
        }
    );
}

#[test]
fn after_resume_codex_shows_the_resumed_thread_before_anything_is_written_to_it() {
    // Seen with Codex 0.169.0: /resume of an older thread keeps the current
    // rollout open, writes nothing to the resumed one, and touches its lock.
    assert_eq!(
        locate::decide(&codex_probe(&[(L1, 300), (R1, 100), (L2, 150), (R2, 200)])),
        Lookup::Found {
            path: R1.into(),
            session: "01a1d584-3784-7b73-991e-7e1205e3a546".into()
        }
    );
    // Back to the newer thread by /resume: its lock is touched in turn.
    assert_eq!(
        locate::decide(&codex_probe(&[(L1, 300), (R1, 310), (L2, 400), (R2, 200)])),
        Lookup::Found {
            path: R2.into(),
            session: "01a1d588-c7a0-7f61-b91d-7bb85b1693dc".into()
        }
    );
}

#[test]
fn a_resume_in_the_same_second_as_the_other_threads_last_write_is_not_guessed() {
    let got = locate::decide(&codex_probe(&[(L1, 200), (R1, 100), (L2, 150), (R2, 200)]));
    assert!(matches!(got, Lookup::Missed(_)), "{got:?}");
}

#[test]
fn two_codex_rollouts_written_in_the_same_second_are_not_guessed() {
    // Whole-second times cannot tell which thread is in front (a resumed
    // older thread beside the current one): say so instead of picking.
    let got = locate::decide(&codex_probe(&[(L1, 50), (R1, 200), (L2, 150), (R2, 200)]));
    assert!(matches!(got, Lookup::Missed(_)), "{got:?}");
}

#[test]
fn a_shell_or_another_program_has_no_log() {
    let mut p = codex_probe(&[]);
    p.name = "zsh".into();
    assert_eq!(locate::decide(&p), Lookup::None);
}

#[test]
fn the_desk_script_output_parses_into_a_probe() {
    let out = format!("name codex\nfd 0 {L1}\nfd 100 {R1}\nfd 0 /dev/pts/3\n");
    let p = locate::parse_probe(&out);
    assert_eq!(p.name, "codex");
    assert_eq!(p.codex_open, vec![(L1.into(), 0), (R1.into(), 100)]);

    let out = format!("name claude\npidfile {PIDFILE}\nprocstart 9001\nlog /x/a.jsonl\n");
    let p = locate::parse_probe(&out);
    assert_eq!(p.claude_pidfile.as_deref(), Some(PIDFILE));
    assert_eq!(p.proc_start, Some(9001));
    assert_eq!(p.claude_logs, vec!["/x/a.jsonl".to_string()]);
}

#[test]
fn a_launcher_wrapped_agent_is_located_as_that_agent() {
    // An npm install runs as `node .../cli.js`: the process name is node.
    let mut p = claude_probe(
        PIDFILE,
        Some(9001),
        &["/x/aaaaaaaa-1111-4111-8111-111111111111.jsonl"],
    );
    p.name = "node".into();
    assert_eq!(locate::decide(&p), Lookup::None);
    assert!(matches!(
        locate::decide_for(Agent::Claude, &p),
        Lookup::Found { .. }
    ));
}

// ---- the four results (spec: the lookup's results)

#[test]
fn an_unreadable_session_file_or_none_at_all_is_missed() {
    let p = claude_probe("{\"pid\":4242,\"procStart\":9001}", Some(9001), &[]);
    assert!(
        matches!(locate::decide(&p), Lookup::Missed(_)),
        "no id: {:?}",
        locate::decide(&p)
    );
    let p = claude_probe("{\"pid\":42", Some(9001), &[]);
    assert!(matches!(locate::decide(&p), Lookup::Missed(_)), "not JSON");
    let mut p = claude_probe(PIDFILE, Some(9001), &[]);
    p.claude_pidfile = None;
    assert!(
        matches!(locate::decide(&p), Lookup::Missed(_)),
        "no session file"
    );
    assert!(
        matches!(locate::decide(&codex_probe(&[])), Lookup::Missed(_)),
        "no rollout open"
    );
}

#[test]
fn the_lookup_is_none_without_an_agent_and_missed_when_the_desk_does_not_answer() {
    assert_eq!(locate::lookup(None, Some("name zsh\n")), Lookup::None);
    assert!(matches!(
        locate::lookup(Some(Agent::Claude), None),
        Lookup::Missed(_)
    ));
    let out = format!("name claude\npidfile {PIDFILE}\nprocstart 9001\nlog /x/a.jsonl\n");
    assert_eq!(
        locate::lookup(Some(Agent::Claude), Some(&out)),
        Lookup::Found {
            path: "/x/a.jsonl".into(),
            session: "aaaaaaaa-1111-4111-8111-111111111111".into()
        }
    );
}

#[test]
fn two_agents_in_one_folder_each_find_their_own_log() {
    let other = PIDFILE
        .replace(
            "aaaaaaaa-1111-4111-8111-111111111111",
            "bbbbbbbb-5555-4555-8555-555555555555",
        )
        .replace("4242", "4343")
        .replace("9001", "9100");
    let a = claude_probe(
        PIDFILE,
        Some(9001),
        &["/u/.claude/projects/-work-website/aaaaaaaa-1111-4111-8111-111111111111.jsonl"],
    );
    let b = claude_probe(
        &other,
        Some(9100),
        &["/u/.claude/projects/-work-website/bbbbbbbb-5555-4555-8555-555555555555.jsonl"],
    );
    let session = |l: Lookup| match l {
        Lookup::Found { session, .. } => session,
        other => panic!("{other:?}"),
    };
    assert_eq!(
        session(locate::decide(&a)),
        "aaaaaaaa-1111-4111-8111-111111111111"
    );
    assert_eq!(
        session(locate::decide(&b)),
        "bbbbbbbb-5555-4555-8555-555555555555"
    );
}

#[test]
fn the_agent_in_front_by_name_or_by_the_script_node_runs() {
    assert_eq!(
        locate::agent_in_front("claude", &["claude", "--continue"]),
        Some(Agent::Claude)
    );
    assert_eq!(
        locate::agent_in_front("codex", &["codex"]),
        Some(Agent::Codex)
    );
    let npm = [
        "node",
        "/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js",
    ];
    assert_eq!(locate::agent_in_front("node", &npm), Some(Agent::Claude));
    assert_eq!(
        locate::agent_in_front("node", &["node", "/opt/bin/codex", "--no-daemon"]),
        Some(Agent::Codex)
    );
    assert_eq!(
        locate::agent_in_front("node", &["node", "/opt/lib/@openai/codex/bin/codex.js"]),
        Some(Agent::Codex)
    );
    // Node names its main thread: /proc/<pid>/comm reads MainThread.
    let nvm = [
        "/home/u/.nvm/versions/node/v24.15.0/bin/node",
        "/home/u/.npm-global/bin/claude",
    ];
    assert_eq!(
        locate::agent_in_front("MainThread", &nvm),
        Some(Agent::Claude)
    );
    assert_eq!(
        locate::agent_in_front("node", &["node", "server.js"]),
        None,
        "another node program"
    );
    assert_eq!(
        locate::agent_in_front("MainThread", &["/usr/bin/node", "src/cli.mjs", "--live"]),
        None
    );
    assert_eq!(locate::agent_in_front("zsh", &["-zsh"]), None);
    assert_eq!(
        locate::agent_in_front("vim", &["vim", "claude.md"]),
        None,
        "an argument named after one is not it"
    );
    assert_eq!(
        locate::agent_in_front("less", &["less", "/usr/local/bin/codex"]),
        None,
        "only node's script counts"
    );
}

// ---- Empty turns Missed after the first turn (spec)

const SESSION: &str = "aaaaaaaa-1111-4111-8111-111111111111";

fn empty() -> Lookup {
    Lookup::Empty {
        session: SESSION.into(),
    }
}

#[test]
fn empty_turns_missed_when_the_first_turn_ends_without_a_log() {
    let mut g = EmptyGuard::default();
    assert_eq!(g.apply(empty()), empty(), "before any turn");
    g.turn_ended(&empty());
    assert!(
        matches!(g.apply(empty()), Lookup::Missed(_)),
        "a misread id: the terminal, not an empty chat for good"
    );
    assert!(
        matches!(g.apply(empty()), Lookup::Missed(_)),
        "and it stays so"
    );
}

#[test]
fn empty_that_finds_its_log_or_a_new_session_is_not_missed() {
    let mut g = EmptyGuard::default();
    g.turn_ended(&empty());
    let found = Lookup::Found {
        path: "/x/a.jsonl".into(),
        session: SESSION.into(),
    };
    assert_eq!(g.apply(found.clone()), found);
    // /clear: a new session id, Empty again until its own first turn ends.
    let fresh = Lookup::Empty {
        session: "cccccccc-3333-4333-8333-333333333333".into(),
    };
    assert_eq!(g.apply(fresh.clone()), fresh);
    // A turn that ended while the log was found judges nothing.
    let mut g = EmptyGuard::default();
    g.turn_ended(&found);
    assert_eq!(g.apply(empty()), empty());
}
