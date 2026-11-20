//! Canonical text is prepared before pending, classification or transport.
use kudde_chat::core::ChatCore;
use kudde_chat::send::{Draft, canonical_text};

#[test]
fn controls_are_removed_but_typeable_text_newlines_and_tabs_survive() {
    let controls: String = (0..=159)
        .filter_map(char::from_u32)
        .filter(|c| c.is_control() && *c != '\n' && *c != '\t')
        .collect();
    assert_eq!(
        canonical_text(&format!("{controls} /usage\r\n!echo\t😀\n")),
        " /usage\n!echo\t😀\n"
    );
    let text = r#"  -quotes ' \\" % `$(echo hi)` / ! @notes.md\n\n"#;
    assert_eq!(canonical_text(text), text);
}

#[test]
fn normalized_empty_keeps_original_draft_and_prepares_nothing() {
    for text in ["", " \t\r\n", "\x03\x1b\u{7f}", " \x03\r\n"] {
        let mut draft = Draft {
            text: text.into(),
            attachments: Vec::<String>::new(),
        };
        assert!(draft.prepare().is_none());
        assert_eq!(draft.text, text);
        assert!(draft.attachments.is_empty());
    }
}

#[test]
fn attachment_only_input_keeps_upload_and_confirmation_behavior() {
    let mut draft = Draft {
        text: "\x03\r".into(),
        attachments: vec!["photo"],
    };
    let out = draft.prepare().unwrap();
    assert_eq!(out.text(), "");
    assert_eq!(out.attachments(), &["photo"]);
    assert!(draft.text.is_empty() && draft.attachments.is_empty());
    assert_eq!(out.transport(&["/tmp/a.png".into()]), "/tmp/a.png");
    let p = out.pending(1, &[], "idle".into(), 0);
    assert_eq!(p.images, 1);
    let row = kudde_chat::core::Row {
        key: (0, 0),
        item: kudde_chat::Item::User {
            text: "".into(),
            images: vec![kudde_chat::Image::Missing],
        },
    };
    assert!(p.matches(&row));
}

#[test]
fn refusal_restores_original_text_and_attachments_before_newer_draft() {
    let original = "old\r\n\x03draft";
    let mut draft = Draft {
        text: original.into(),
        attachments: vec!["old photo"],
    };
    let out = draft.prepare().unwrap();
    assert_eq!(out.text(), "old\ndraft");
    draft.text = "new draft".into();
    draft.attachments.push("new photo");
    out.restore(&mut draft);
    assert_eq!(draft.text, format!("{original}\nnew draft"));
    assert_eq!(draft.attachments, vec!["old photo", "new photo"]);
    // An empty newer box must not add a newline; whitespace-only original
    // text follows the phone's existing merge rule for attachment-only sends.
    let out = draft.prepare().unwrap();
    out.restore(&mut draft);
    assert_eq!(draft.text, format!("{original}\nnew draft"));
    let out = Draft {
        text: " \r".into(),
        attachments: vec!["photo"],
    }
    .prepare()
    .unwrap();
    out.restore(&mut draft);
    assert_eq!(draft.text, format!("{original}\nnew draft"));
}

#[test]
fn final_at_space_and_image_prefix_are_only_transport() {
    let mut draft = Draft {
        text: "\x03Read\r\n@notes.md".into(),
        attachments: vec!["photo"],
    };
    let out = draft.prepare().unwrap();
    let p = out.pending(1, &[], "working".into(), 0);
    assert_eq!(p.text, "Read\n@notes.md");
    assert_eq!(
        out.transport(&["/tmp/a.png".into()]),
        "/tmp/a.png\nRead\n@notes.md "
    );
    assert!(p.queued_in(&["/tmp/a.png\nRead\n@notes.md ".into()]));
    assert!(kudde_chat::send::box_holds(
        kudde_chat::Agent::Codex,
        &["› [Image #1] Read".into(), "@notes.md ".into()],
        &p.text,
        p.images
    ));
    for (text, expected) in [
        ("@", "@ "),
        ("Read @a ", "Read @a "),
        ("Read @a\n", "Read @a\n"),
        ("read a@b", "read a@b"),
        ("  hi\n\n", "  hi\n\n"),
    ] {
        let out = Draft {
            text: text.into(),
            attachments: Vec::<()>::new(),
        }
        .prepare()
        .unwrap();
        assert_eq!(out.transport(&[]), expected);
    }
}

#[test]
fn core_receives_canonical_command_even_with_an_image_prefix() {
    for command in ["\x03/usage", "\x1b!echo hi"] {
        let out = Draft {
            text: command.into(),
            attachments: vec!["photo"],
        }
        .prepare()
        .unwrap();
        let mut core = ChatCore::new();
        let t = kudde_chat::core::Term {
            agent: Some(kudde_chat::Agent::Claude),
            pid: Some(42),
            status: Some("idle".into()),
        };
        core.take_lookup(
            &t,
            Some(kudde_chat::locate::Lookup::Empty {
                session: "s1".into(),
            }),
            0,
        );
        assert!(!core.needs_lookup(&t, false, 1001));
        out.sent(&mut core, 1000);
        // A slash command requests a fresh lookup despite the image prefix;
        // a bang command retains its separate command grace (core tests).
        assert_eq!(
            core.needs_lookup(&t, false, 1001),
            out.text().starts_with('/')
        );
        assert!(
            out.transport(&["/tmp/a.png".into()])
                .starts_with("/tmp/a.png\n")
        );
    }
}
