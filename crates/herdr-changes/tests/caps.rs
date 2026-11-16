//! Task 2.4: the caps (spec: "The list shows what is not committed yet",
//! "Long diffs show their start and load in full on request").

mod common;

use common::*;
use herdr_changes::diff::{LINE_MAX, SHOWN_FIRST};
use herdr_changes::read::ENTRIES_MAX;
use herdr_changes::{Exec, Kind, Outcome, Reader, Step, parse_diff};

#[test]
fn many_files_list_the_first_thousand() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("many");
    let r = s.repo("website", &[]);
    r.write("README.md", "r\n");
    r.commit_all("first");
    for i in 0..20_000 {
        r.write(&format!("out/report-{i:05}.json"), "{}\n");
    }
    let l = list(&r.dir);
    assert_eq!(l.entries.len(), ENTRIES_MAX);
    assert!(l.capped);
    // The listed new files are counted, across more than one grep call.
    assert!(
        l.entries
            .iter()
            .all(|e| e.kind == Kind::New && e.counts == Some((1, 0)))
    );
}

#[test]
fn exactly_a_thousand_is_not_capped() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("thousand");
    let r = s.repo("website", &[]);
    r.write("README.md", "r\n");
    r.commit_all("first");
    for i in 0..ENTRIES_MAX {
        r.write(&format!("out/{i:04}.txt"), "x\n");
    }
    let l = list(&r.dir);
    assert_eq!(l.entries.len(), ENTRIES_MAX);
    assert!(!l.capped);
}

#[test]
fn a_cut_status_lists_its_complete_entries_and_the_note() {
    let mut r = Reader::new("/work/website");
    let Step::Run(_) = r.start() else { panic!() };
    let ran = |stdout: &[u8], truncated| Exec::Ran {
        code: Some(0),
        stdout: stdout.to_vec(),
        stderr: vec![],
        truncated,
        timed_out: false,
    };
    let Step::Run(_) = r.feed(ran(b"/work/website\n--\n", false)) else {
        panic!()
    };
    let Step::Run(_) = r.feed(ran(b"sha1\nabc\n", false)) else {
        panic!()
    };
    let Step::Run(grep) = r.feed(ran(b"?? a.txt\0?? b.txt\0?? c.t", true)) else {
        panic!()
    };
    assert_eq!(&grep.argv[grep.argv.len() - 2..], ["a.txt", "b.txt"]);
    let Step::Done(Outcome::Ok(l)) = r.feed(ran(b"a.txt\x001\nb.txt\x002\n", false)) else {
        panic!()
    };
    assert_eq!(names(&l), ["a.txt", "b.txt"]);
    assert_eq!(l.entries[1].counts, Some((2, 0)));
    assert!(l.capped);
}

#[test]
fn lockfile_churn_counts_its_lines_after_the_headers() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("churn");
    let r = s.repo("website", &[]);
    r.write("package-lock.json", "{}\n");
    r.commit_all("first");
    // One hunk: its header and 19,999 lines added, 1 removed = 20,001.
    let body: String = (0..19_999)
        .map(|i| format!("\"dep-{i}\": \"1.0.{i}\",\n"))
        .collect();
    r.write("package-lock.json", &body);
    let l = list(&r.dir);
    assert_eq!(entry(&l, "package-lock.json").counts, Some((19_999, 1)));
    let d = diff(&l, "package-lock.json");
    assert_eq!(d.headers, 4);
    assert_eq!(d.body_len(), 20_001);
    assert!(d.is_long());
    assert!(!d.too_large);
    assert_eq!(d.lines.len(), 4 + 20_001, "the whole diff is held");
    let short = r.dir.join("short.txt");
    std::fs::write(&short, "x\n".repeat(SHOWN_FIRST - 1)).unwrap();
    let l = list(&r.dir);
    let d = diff(&l, "short.txt");
    assert_eq!(d.body_len(), SHOWN_FIRST);
    assert!(!d.is_long(), "exactly 2,000 lines show without the control");
}

fn ran(stdout: &[u8], code: i32, truncated: bool) -> Exec {
    Exec::Ran {
        code: Some(code),
        stdout: stdout.to_vec(),
        stderr: vec![],
        truncated,
        timed_out: false,
    }
}

#[test]
fn a_cut_result_is_too_large() {
    let mut out = b"diff --git a/big.log b/big.log\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/big.log\n@@ -0,0 +1,900000 @@\n".to_vec();
    for _ in 0..3000 {
        out.extend_from_slice(b"+log line\n");
    }
    out.extend_from_slice(b"+log li");
    let Outcome::Ok(d) = parse_diff(Kind::New, ran(&out, 1, true)) else {
        panic!()
    };
    assert!(d.too_large);
    assert!(!d.is_long(), "no 'Show the whole diff' with 'Too large'");
    assert_eq!(d.body_len(), 3001, "the cut-off last line is dropped");
    assert_eq!(d.lines.last().unwrap().text, "+log line");
}

#[test]
fn a_line_over_four_thousand_characters_is_cut() {
    let long = format!("+{}", "é".repeat(LINE_MAX + 50));
    let exact = format!("+{}", "a".repeat(LINE_MAX - 1));
    let out = format!(
        "diff --git a/m.js b/m.js\nindex 1..2 100644\n--- a/m.js\n+++ b/m.js\n@@ -1 +1,2 @@\n{long}\n{exact}\n"
    );
    let Outcome::Ok(d) = parse_diff(Kind::Changed, ran(out.as_bytes(), 0, false)) else {
        panic!()
    };
    let cut = &d.lines[5];
    assert!(cut.cut);
    assert_eq!(cut.text.chars().count(), LINE_MAX);
    assert!(cut.text.starts_with("+é"));
    let kept = &d.lines[6];
    assert!(!kept.cut);
    assert_eq!(kept.text.chars().count(), LINE_MAX);
}

#[test]
fn a_reverted_file_has_an_empty_diff() {
    let Outcome::Ok(d) = parse_diff(Kind::Changed, ran(b"", 0, false)) else {
        panic!()
    };
    assert!(d.is_empty());
}

#[test]
fn diff_outcomes() {
    // A new file's diff exits 1 and must start with `diff --git`.
    assert!(matches!(
        parse_diff(
            Kind::New,
            ran(b"error: Could not access 'gone'\n", 1, false)
        ),
        Outcome::Failed(None)
    ));
    // A tracked file's diff succeeds only with exit 0.
    assert!(matches!(
        parse_diff(Kind::Changed, ran(b"diff --git a/x b/x\n", 1, false)),
        Outcome::Failed(_)
    ));
    assert_eq!(
        parse_diff(Kind::Changed, Exec::SpawnFailed),
        Outcome::Failed(None)
    );
    assert_eq!(
        parse_diff(
            Kind::Changed,
            Exec::Ran {
                code: None,
                stdout: vec![],
                stderr: b"\nfatal: took too long\n".to_vec(),
                truncated: false,
                timed_out: true,
            }
        ),
        Outcome::Failed(Some("fatal: took too long".into()))
    );
}

#[test]
fn a_diff_of_many_short_lines_is_held_only_up_to_the_line_cap() {
    // 8 MB of near-empty lines would be about four million records on the phone (review 2026-11-16).
    use herdr_changes::diff::LINES_MAX;
    assert_eq!(LINES_MAX, 50_000);
    let mut out = b"diff --git a/blank.txt b/blank.txt\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/blank.txt\n@@ -0,0 +1,3000000 @@\n".to_vec();
    out.extend(std::iter::repeat_n(b"+\n".as_slice(), 3_000_000).flatten());
    let Outcome::Ok(d) = parse_diff(Kind::New, ran(&out, 1, false)) else {
        panic!()
    };
    assert!(d.too_large, "past the line cap reads 'Too large to show on the phone'");
    assert_eq!(d.body_len(), LINES_MAX);
    assert!(!d.is_long());
    assert_eq!(d.headers, 5);
}

#[test]
fn a_diff_at_the_line_cap_is_whole() {
    use herdr_changes::diff::LINES_MAX;
    let mut out = b"diff --git a/a b/a\nindex 1..2 100644\n--- a/a\n+++ b/a\n@@ -1 +1,49999 @@\n".to_vec();
    out.extend(std::iter::repeat_n(b"+x\n".as_slice(), LINES_MAX - 1).flatten());
    let Outcome::Ok(d) = parse_diff(Kind::Changed, ran(&out, 0, false)) else {
        panic!()
    };
    assert!(!d.too_large);
    assert_eq!(d.body_len(), LINES_MAX);
    assert!(d.is_long());
}

/// The cwd the reader asks for after `rev-parse --show-toplevel` printed `out`.
fn top_after(out: &[u8]) -> Result<String, Outcome<herdr_changes::List>> {
    let mut r = Reader::new("/work/website/app");
    let Step::Run(_) = r.start() else { panic!() };
    match r.feed(ran(out, 0, false)) {
        Step::Run(call) => Ok(call.cwd),
        Step::Done(o) => Err(o),
    }
}

#[test]
fn a_repository_top_keeps_a_newline_in_its_name() {
    assert_eq!(top_after(b"/work/site\nother\n--\n"), Ok("/work/site\nother".into()));
    assert_eq!(top_after(b"/work/website\n--\n"), Ok("/work/website".into()));
    // Without the echoed `--` (older git), the last line feed alone ends it.
    assert_eq!(top_after(b"/work/website\n"), Ok("/work/website".into()));
}

#[test]
fn a_repository_top_that_is_not_utf8_cannot_be_read() {
    assert_eq!(top_after(b"/work/caf\xe9\n--\n"), Err(Outcome::Failed(None)));
    assert_eq!(top_after(b"\n--\n"), Err(Outcome::Failed(None)));
}
