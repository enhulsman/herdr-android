//! Which log belongs to a terminal, from what the desk reports.

use herdr_chat::locate::{self, Found, Probe};

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
        Found::Log {
            path: "/u/.claude/projects/-work-website/aaaaaaaa-1111-4111-8111-111111111111.jsonl".into(),
            session: "aaaaaaaa-1111-4111-8111-111111111111".into(),
        }
    );
}

#[test]
fn a_reused_pid_does_not_match() {
    // The file names a process that started at another time: a stale file whose
    // pid now belongs to someone else.
    let p = claude_probe(PIDFILE, Some(1234), &["/x/aaaaaaaa-1111-4111-8111-111111111111.jsonl"]);
    assert_eq!(locate::decide(&p), Found::Nothing("process start differs"));
}

#[test]
fn a_session_with_no_log_yet_is_an_empty_conversation() {
    // Right after start or /clear, before the first message.
    let p = claude_probe(PIDFILE, Some(9001), &[]);
    assert_eq!(
        locate::decide(&p),
        Found::Empty {
            session: "aaaaaaaa-1111-4111-8111-111111111111".into()
        }
    );
}

#[test]
fn two_logs_with_the_same_session_id_are_refused() {
    let p = claude_probe(PIDFILE, Some(9001), &["/a/x.jsonl", "/b/x.jsonl"]);
    assert_eq!(locate::decide(&p), Found::Nothing("several logs"));
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
        Found::Log {
            path: R1.into(),
            session: "01a1d584-3784-7b73-991e-7e1205e3a546".into()
        }
    );
}

#[test]
fn after_new_codex_shows_the_new_thread_even_before_its_first_message() {
    // /new keeps the old rollout open and takes a lock for the new thread; its
    // rollout appears with the first message.
    assert_eq!(
        locate::decide(&codex_probe(&[(L1, 0), (R1, 100), (L2, 0)])),
        Found::Empty {
            session: "01a1d588-c7a0-7f61-b91d-7bb85b1693dc".into()
        }
    );
    assert_eq!(
        locate::decide(&codex_probe(&[(L1, 0), (R1, 100), (L2, 0), (R2, 200)])),
        Found::Log {
            path: R2.into(),
            session: "01a1d588-c7a0-7f61-b91d-7bb85b1693dc".into()
        }
    );
}

#[test]
fn a_shell_or_another_program_has_no_log() {
    let mut p = codex_probe(&[]);
    p.name = "zsh".into();
    assert_eq!(locate::decide(&p), Found::Nothing("not an agent"));
}

#[test]
fn the_desk_script_output_parses_into_a_probe() {
    let out = format!(
        "name codex\nfd 0 {L1}\nfd 100 {R1}\nfd 0 /dev/pts/3\n"
    );
    let p = locate::parse_probe(&out);
    assert_eq!(p.name, "codex");
    assert_eq!(p.codex_open, vec![(L1.into(), 0), (R1.into(), 100)]);

    let out = format!("name claude\npidfile {PIDFILE}\nprocstart 9001\nlog /x/a.jsonl\n");
    let p = locate::parse_probe(&out);
    assert_eq!(p.claude_pidfile.as_deref(), Some(PIDFILE));
    assert_eq!(p.proc_start, Some(9001));
    assert_eq!(p.claude_logs, vec!["/x/a.jsonl".to_string()]);
}
