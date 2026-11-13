//! The conversation the chat view shows, from each agent's log format.

use herdr_chat::{Agent, Health, Image, Item, Parser, ToolCall};

const CLAUDE: &str = include_str!("fixtures/claude.jsonl");
const CODEX: &str = include_str!("fixtures/codex.jsonl");

fn parse(agent: Agent, text: &str) -> Parser {
    let mut p = Parser::new(agent);
    p.feed(text);
    p
}

fn user(text: &str) -> Item {
    Item::User {
        text: text.into(),
        images: vec![],
    }
}
fn said(text: &str) -> Item {
    Item::Assistant { text: text.into() }
}

#[test]
fn claude_session_becomes_messages_with_folded_tool_calls() {
    let p = parse(Agent::Claude, CLAUDE);
    let items = p.items();
    assert_eq!(items[0], Item::Note { text: "/model".into() });
    assert_eq!(items[1], user("Fix the **retry** in the upload job"));
    assert_eq!(items[2], said("Looking at the job first."));
    // Three tool calls in a row, across assistant messages, fold into one item.
    let Item::Tools { calls } = &items[3] else {
        panic!("tools expected, got {:?}", items[3])
    };
    assert_eq!(calls.len(), 3);
    assert_eq!(
        calls[0],
        ToolCall {
            name: "Bash".into(),
            title: "Find the retry code".into(),
            input: "grep -n retry src/upload.rs".into(),
            output: "12: retry(3)".into(),
            done: true,
            failed: false,
            images: vec![],
        }
    );
    assert_eq!(calls[1].title, "Read src/upload.rs");
    // An image the agent read: its bytes stayed on the desk, the tail script
    // left where they are (the line's byte offset, the field's index on it).
    assert_eq!(calls[1].output, "fn upload() {}");
    assert_eq!(
        calls[1].images,
        vec![Image::InLog {
            line: 2048,
            n: 0,
            media_type: "image/png".into()
        }]
    );
    assert_eq!(calls[2].title, "cargo test");
    assert!(calls[2].failed && calls[2].done);
    assert_eq!(
        items[4],
        said("The retry ran **once**:\n\n- fixed the loop\n- added a test")
    );
    // herdr-core's note tag and Claude Code's paste wrapper are taken off.
    assert_eq!(items[5], user("Two lines\nfrom the phone"));
    assert_eq!(
        items[6],
        Item::User {
            // Claude Code's "[Image #1]" placeholders are taken out of the text.
            text: "What is in these screenshots?".into(),
            images: vec![
                Image::InLog {
                    line: 4096,
                    n: 0,
                    media_type: "image/jpeg".into()
                },
                // Short enough that the tail script kept it.
                Image::Inline {
                    media_type: "image/png".into(),
                    data: "iVBORw0KGgo=".into()
                },
            ]
        }
    );
    assert_eq!(
        items[7],
        Item::Note {
            text: "Conversation compacted".into()
        }
    );
    assert_eq!(items.len(), 8, "{items:#?}");
    assert_eq!(p.health(), Health::Ok);
    assert_eq!(p.title(), Some("Upload retry fix"));
    assert_eq!(p.version(), Some("2.1.323"));
}

#[test]
fn sidechains_thinking_and_meta_records_are_left_out() {
    let p = parse(Agent::Claude, CLAUDE);
    let all = format!("{:?}", p.items());
    for hidden in ["subagent chatter", "private", "Caveat", "api-request"] {
        assert!(!all.contains(hidden), "{hidden} leaked");
    }
}

#[test]
fn a_tool_call_without_its_result_yet_is_running() {
    let cut: String = CLAUDE
        .lines()
        .take_while(|l| !l.contains("\"tu_1\",\"type\":\"tool_result\""))
        .map(|l| format!("{l}\n"))
        .collect();
    let p = parse(Agent::Claude, &cut);
    let Some(Item::Tools { calls }) = p.items().last() else {
        panic!("{:?}", p.items())
    };
    assert!(!calls[0].done);
}

#[test]
fn feeding_in_chunks_gives_the_same_items_as_feeding_at_once() {
    for (agent, text) in [(Agent::Claude, CLAUDE), (Agent::Codex, CODEX)] {
        let whole = parse(agent, text);
        let mut parts = Parser::new(agent);
        for line in text.lines() {
            parts.feed(&format!("{line}\n"));
        }
        assert_eq!(whole.items(), parts.items());
    }
}

#[test]
fn codex_session_uses_its_completed_items() {
    let p = parse(Agent::Codex, CODEX);
    let items = p.items();
    assert_eq!(items[0], user("Add a health route\nwith a test"));
    assert_eq!(items[1], said("Checking the router."));
    let Item::Tools { calls } = &items[2] else {
        panic!("{:?}", items[2])
    };
    assert_eq!(calls.len(), 3);
    assert_eq!(calls[0].name, "Shell");
    assert_eq!(calls[0].title, "rg -n route src");
    assert_eq!(calls[0].output, "src/main.rs:4: route");
    assert_eq!(calls[1].name, "Edit");
    assert_eq!(calls[1].title, "Edit src/main.rs");
    assert!(calls[2].failed);
    assert_eq!(items[3], said("Added `/health`."));
    // An image Codex viewed: a file URL, kept as the file's path.
    let Item::Tools { calls } = &items[4] else {
        panic!("{:?}", items[4])
    };
    assert_eq!(calls[0].title, "View shots/home page.png");
    assert_eq!(
        calls[0].images,
        vec![Image::File {
            path: "/work/api/shots/home page.png".into()
        }]
    );
    assert_eq!(items.len(), 5, "{items:#?}");
    // The injected AGENTS.md "user" message is not the person's.
    assert!(!format!("{items:?}").contains("AGENTS.md"));
    assert_eq!(p.version(), Some("0.169.0"));
}

#[test]
fn an_unknown_codex_item_is_counted_not_shown() {
    let p = parse(Agent::Codex, CODEX);
    assert_eq!(p.health(), Health::Degraded { unknown: 1 });
}

#[test]
fn a_line_that_is_not_json_breaks_the_view() {
    let mut p = parse(Agent::Claude, CLAUDE);
    p.feed("{\"type\":\"user\", this is not json\n");
    assert!(matches!(p.health(), Health::Broken { .. }));
}

#[test]
fn a_message_record_without_its_fields_breaks_the_view() {
    let mut p = Parser::new(Agent::Claude);
    p.feed("{\"type\":\"assistant\",\"uuid\":\"x\",\"message\":{\"parts\":[]}}\n");
    assert!(matches!(p.health(), Health::Broken { .. }));
}

#[test]
fn a_log_in_the_other_agents_format_breaks_the_view() {
    assert!(matches!(
        parse(Agent::Claude, CODEX).health(),
        Health::Broken { .. }
    ));
    assert!(matches!(
        parse(Agent::Codex, CLAUDE).health(),
        Health::Broken { .. }
    ));
}

#[test]
fn an_empty_log_is_fine_and_empty() {
    let p = parse(Agent::Claude, "");
    assert!(p.items().is_empty());
    assert_eq!(p.health(), Health::Ok);
}

#[test]
fn image_data_emptied_without_a_reference_is_missing() {
    let mut p = Parser::new(Agent::Claude);
    p.feed("{\"type\":\"user\",\"uuid\":\"u\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"\"}}]}}\n");
    assert_eq!(
        p.items(),
        &[Item::User {
            text: String::new(),
            images: vec![Image::Missing]
        }]
    );
}

const AGENTS: &str = include_str!("fixtures/claude-agents.jsonl");

#[test]
fn a_subagents_report_is_its_own_item_named_after_its_task() {
    let p = parse(Agent::Claude, AGENTS);
    let items = p.items();
    assert_eq!(items[0], user("Ask an agent about the docs"));
    let Item::Tools { calls } = &items[1] else {
        panic!("{:?}", items[1])
    };
    assert_eq!(calls[0].title, "Check the upload docs");
    // The harness frame and the two-space indent are taken off.
    assert_eq!(
        items[2],
        Item::AgentMessage {
            from: "Check the upload docs".into(),
            text: "The docs say **three** retries.\n\n- backoff doubles".into(),
        }
    );
    // A finished background task is a note, never the person's own bubble.
    assert_eq!(
        items[3],
        Item::Note {
            text: "Agent \"Check the upload docs\" finished".into()
        }
    );
    // Another session's message: no frame, named by its sender.
    assert_eq!(
        items[4],
        Item::AgentMessage {
            from: "docs-writer".into(),
            text: "Can you check the README wording?".into(),
        }
    );
}

#[test]
fn a_shell_command_the_person_ran_shows_as_a_tool_call() {
    let p = parse(Agent::Claude, AGENTS);
    let Item::Tools { calls } = &p.items()[5] else {
        panic!("{:?}", p.items())
    };
    assert_eq!(calls.len(), 2, "two commands in a row fold together");
    assert_eq!((calls[0].name.as_str(), calls[0].title.as_str()), ("Shell", "! ls src"));
    assert_eq!(calls[0].output, "main.rs\nupload.rs");
    assert!(calls[0].done && !calls[0].failed);
    assert_eq!(calls[1].output, "cat: nope: No such file");
    assert!(calls[1].failed, "only stderr: shown as failed");
    assert_eq!(p.items().len(), 6, "{:#?}", p.items());
    assert!(!format!("{:?}", p.items()).contains("<bash-"));
}

#[test]
fn image_paths_sent_before_the_text_show_as_the_images() {
    // Codex 0.169 keeps a pasted path as text and opens it with its viewer.
    let mut p = Parser::new(Agent::Codex);
    p.feed("{\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"item\":{\"type\":\"UserMessage\",\"id\":\"u\",\"content\":[{\"type\":\"text\",\"text\":\"/files/t1/a.png\\n/files/t1/b photo.JPG\\nWhat is this?\\n/not/leading.png\"}]}}}\n");
    assert_eq!(
        p.items(),
        &[Item::User {
            text: "What is this?\n/not/leading.png".into(),
            images: vec![
                Image::File { path: "/files/t1/a.png".into() },
                Image::File { path: "/files/t1/b photo.JPG".into() },
            ]
        }]
    );
}
