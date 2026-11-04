//! Starting terminals and agents from the phone (start-agents change, task 1.1).

use herdr_proto::attention::program_of;
use herdr_proto::layout::{Space, spaces};
use herdr_proto::start::{
    AGENTS, Check, Folder, SPACE_LABEL, agent_argv, check_argv, folder_choices, fresh_space_id,
    parse_check, resolve_folder, run_params, space_title, wrapper,
};
use serde_json::{Value, json};

const HOME: &str = "/home/me";

// ---- the layout's spaces (design D2, D3)

#[test]
fn spaces_are_read_with_id_name_and_folder() {
    let doc = json!({"spaces": [
        {"id": "s1", "name": "website", "folder": "/home/me/src/website", "tabs": []},
        {"id": "s2", "folder": "~/src/api"},
        {"id": "s3", "name": "", "folder": ""},
        {"name": "no id"},
        {"id": 4},
    ]});
    assert_eq!(
        spaces(&doc),
        vec![
            Space {
                id: "s1".into(),
                name: "website".into(),
                folder: "/home/me/src/website".into()
            },
            Space {
                id: "s2".into(),
                name: "".into(),
                folder: "~/src/api".into()
            },
            Space {
                id: "s3".into(),
                name: "".into(),
                folder: "".into()
            },
        ]
    );
}

#[test]
fn no_document_an_unreadable_one_or_zero_spaces_have_no_spaces() {
    for doc in [
        Value::Null,
        json!("text"),
        json!({"spaces": 3}),
        json!({}),
        json!({"spaces": []}),
    ] {
        assert_eq!(spaces(&doc), vec![], "{doc}");
    }
}

// ---- folders (design D2)

#[test]
fn a_space_folder_resolves_against_home() {
    assert_eq!(
        resolve_folder("/srv/site", Some(HOME)),
        Folder::Path("/srv/site".into())
    );
    assert_eq!(resolve_folder("", Some(HOME)), Folder::Path(HOME.into()));
    assert_eq!(resolve_folder("~", Some(HOME)), Folder::Path(HOME.into()));
    assert_eq!(
        resolve_folder("~/src/api", Some(HOME)),
        Folder::Path("/home/me/src/api".into())
    );
    assert_eq!(
        resolve_folder("~/src/api", Some("/home/me/")),
        Folder::Path("/home/me/src/api".into())
    );
    assert_eq!(
        resolve_folder("src/api", Some(HOME)),
        Folder::HomeInstead(HOME.into())
    );
    assert_eq!(
        resolve_folder("/home/me with space/x", Some(HOME)),
        Folder::Path("/home/me with space/x".into())
    );
}

#[test]
fn without_a_known_home_only_absolute_folders_resolve() {
    assert_eq!(
        resolve_folder("/srv/site", None),
        Folder::Path("/srv/site".into())
    );
    for f in ["", "~", "~/src", "src"] {
        assert_eq!(resolve_folder(f, None), Folder::HomeUnknown, "{f}");
    }
}

fn term(address: &str, cwd: &str, fg: Option<&str>) -> Value {
    json!({"address": address, "state": "live", "cwd": cwd,
           "foreground": fg.map(|c| json!({"pid": 1, "name": "zsh", "argv": ["zsh"], "cwd": c}))})
}

#[test]
fn folder_choices_are_home_first_then_each_live_folder_once() {
    let terminals = [
        term("t1", "/home/me/src/api", None),
        term("t2", "/home/me", Some("/home/me/src/api")),
        term("t3", "/home/me/src/website", Some("/home/me/src/docs")),
        term("t4", "/home/me", None),
        json!({"address": "t5", "state": "finished", "cwd": "/tmp/gone", "foreground": null}),
    ];
    assert_eq!(
        folder_choices(&terminals, Some(HOME)),
        vec!["/home/me", "/home/me/src/api", "/home/me/src/docs"]
    );
}

#[test]
fn folder_choices_without_home_list_only_live_folders() {
    let terminals = [term("t1", "/home/me/src/api", None)];
    assert_eq!(folder_choices(&terminals, None), vec!["/home/me/src/api"]);
    assert_eq!(folder_choices(&[], None), Vec::<String>::new());
}

#[test]
fn an_unnamed_space_is_called_after_its_folder() {
    let named = Space {
        id: "s1".into(),
        name: "website".into(),
        folder: "/x".into(),
    };
    let unnamed = Space {
        id: "s2".into(),
        name: "".into(),
        folder: "/home/me/src/api".into(),
    };
    let at_home = Space {
        id: "s3".into(),
        name: "".into(),
        folder: "".into(),
    };
    let tilde = Space {
        id: "s4".into(),
        name: "".into(),
        folder: "~/src/docs".into(),
    };
    assert_eq!(space_title(&named, Some(HOME)), "website");
    assert_eq!(space_title(&unnamed, Some(HOME)), "api");
    assert_eq!(space_title(&at_home, Some(HOME)), "~");
    assert_eq!(space_title(&tilde, Some(HOME)), "docs");
}

#[test]
fn a_fresh_space_id_has_the_desks_form_and_differs_each_time() {
    let a = fresh_space_id();
    let b = fresh_space_id();
    assert_eq!(a.len(), 34, "{a}");
    assert!(a.starts_with("sp"));
    assert!(
        a[2..]
            .chars()
            .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()),
        "{a}"
    );
    assert_ne!(a, b);
}

// ---- the agent table (design D5)

#[test]
fn the_table_is_the_desks_fifteen_agents_in_its_order() {
    let words: Vec<&str> = AGENTS.iter().map(|a| a.word).collect();
    assert_eq!(
        words,
        [
            "claude",
            "codex",
            "pi",
            "opencode",
            "cursor-agent",
            "copilot",
            "amp",
            "gemini",
            "grok",
            "agy",
            "crush",
            "hermes",
            "kimi",
            "kiro-cli",
            "qodercli",
        ]
    );
    assert_eq!(AGENTS[0].name, "Claude Code");
    assert_eq!(AGENTS[0].program, "claude");
    assert_eq!(AGENTS[4].program, "cursor");
}

#[test]
fn every_word_is_one_shell_safe_word() {
    for a in AGENTS {
        assert!(!a.word.is_empty());
        assert!(
            a.word
                .chars()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "._-".contains(c)),
            "{}",
            a.word
        );
    }
}

// ---- the start (design D1)

#[test]
fn the_wrapper_is_exactly_the_designed_line() {
    assert_eq!(
        wrapper("claude"),
        r#"case $(uname -s) in Darwin) f=-lic i=-li;; *) f=-ic i=-i;; esac; exec "${SHELL:-sh}" $f "claude; exec \"\$0\" $i" "${SHELL:-sh}""#
    );
    assert_eq!(
        agent_argv("codex"),
        vec!["sh".to_owned(), "-c".into(), wrapper("codex"), "sh".into()]
    );
}

#[test]
fn run_params_for_a_terminal_and_an_agent() {
    assert_eq!(
        run_params(Some("/home/me/src/website"), "s1", None),
        json!({"cwd": "/home/me/src/website", "labels": {SPACE_LABEL: "s1"}, "keep": true})
    );
    assert_eq!(
        run_params(None, "s1", Some("claude")),
        json!({"argv": agent_argv("claude"), "labels": {"herdr.space": "s1"}, "keep": true})
    );
}

#[test]
fn a_wrapped_start_is_named_after_what_runs_in_it() {
    let rec = |fg: Option<&str>| {
        json!({"address": "t1", "argv": agent_argv("claude"), "status": null,
               "foreground": fg.map(|n| json!({"pid": 1, "name": n, "argv": [n], "cwd": "/"}))})
    };
    // The agent's own report wins, as for any terminal.
    let mut reported = rec(Some("node"));
    reported["status"] = json!({"state": "idle", "app": "claude-code"});
    assert_eq!(program_of(&reported).as_deref(), Some("claude"));
    // Before it reports: what runs now, the shell once the agent quit.
    assert_eq!(program_of(&rec(Some("zsh"))).as_deref(), Some("zsh"));
    // Nothing known yet: the agent it was started for, never `sh`.
    assert_eq!(program_of(&rec(None)).as_deref(), Some("claude"));
    assert_eq!(
        program_of(&json!({"argv": ["sh", "-c", "echo hi"]})).as_deref(),
        Some("sh")
    );
}

// ---- the installed-agents check (design D5)

#[test]
fn the_check_is_exactly_the_designed_line_with_words_inline() {
    let argv = check_argv(&["claude", "codex"]);
    assert_eq!(argv.len(), 4);
    assert_eq!(
        (argv[0].as_str(), argv[1].as_str(), argv[3].as_str()),
        ("sh", "-c", "sh")
    );
    assert_eq!(
        argv[2],
        r#"printf 'herdr-home:%s\n' "$HOME"; case $(uname -s) in Darwin) f=-lic;; *) f=-ic;; esac; exec "${SHELL:-sh}" $f 'for c in claude codex; do command -v "$c" >/dev/null 2>&1 && echo "herdr-has:$c"; done; echo herdr-done'"#
    );
}

const WORDS: &[&str] = &["claude", "codex", "pi"];

#[test]
fn a_clean_answer_gives_home_and_the_found_agents_in_table_order() {
    let out = "herdr-home:/home/me\nherdr-has:codex\nherdr-has:claude\nherdr-done\n";
    assert_eq!(
        parse_check(out, Some(0), WORDS),
        Check {
            home: Some(HOME.into()),
            found: Some(vec!["claude".into(), "codex".into()])
        }
    );
}

#[test]
fn prompt_noise_and_glued_escapes_do_not_hide_markers() {
    let out = "herdr-home:/home/me with space\n\x1b]0;zsh\x07Welcome!\n\x1b[?2004hherdr-has:claude\n\x1b]7;file://x\x07herdr-done\x1b[?2004l";
    assert_eq!(
        parse_check(out, Some(0), WORDS),
        Check {
            home: Some("/home/me with space".into()),
            found: Some(vec!["claude".into()])
        }
    );
}

#[test]
fn zero_agents_is_a_success() {
    assert_eq!(
        parse_check("herdr-home:/home/me\nherdr-done\n", Some(0), WORDS),
        Check {
            home: Some(HOME.into()),
            found: Some(vec![])
        }
    );
}

#[test]
fn a_failed_inner_shell_still_gives_home() {
    // fish: the loop is not its syntax; the outer sh printed home first.
    let out = "herdr-home:/home/me\nfish: Unsupported use of 'do'.\n";
    assert_eq!(
        parse_check(out, Some(127), WORDS),
        Check {
            home: Some(HOME.into()),
            found: None
        }
    );
    assert_eq!(
        parse_check(out, Some(0), WORDS),
        Check {
            home: Some(HOME.into()),
            found: None
        }
    );
}

#[test]
fn a_timeout_or_a_signal_is_a_failure() {
    let out = "herdr-home:/home/me\nherdr-has:claude\nherdr-done\n";
    assert_eq!(parse_check(out, None, WORDS).found, None);
    assert_eq!(parse_check(out, Some(1), WORDS).found, None);
}

#[test]
fn unknown_words_and_empty_home_are_ignored() {
    let out = "herdr-home:\nherdr-has:rm\nherdr-has:pi\nherdr-done";
    assert_eq!(
        parse_check(out, Some(0), WORDS),
        Check {
            home: None,
            found: Some(vec!["pi".into()])
        }
    );
}
