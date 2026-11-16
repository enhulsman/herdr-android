//! Task 2.2: the list and diffs against real git (spec: "The list shows what
//! is not committed yet", "A file's diff is readable on the phone",
//! "Outside a git repository the screen says so").

mod common;

use common::*;
use herdr_changes::{Base, Exec, Kind, ObjectFormat, Outcome, Reader, Step};

fn base_repo(s: &Scratch) -> Repo {
    let r = s.repo("website", &[]);
    r.write("README.md", "website\n");
    r.commit_all("first");
    r
}

#[test]
fn agent_in_a_subfolder() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("subfolder");
    let r = base_repo(&s);
    r.write("app/main.rs", "fn main() {}\n");
    r.write("docs/notes.md", "one\n");
    r.commit_all("files");
    r.write("app/main.rs", "fn main() {\n    run();\n}\n");
    r.write("docs/notes.md", "one\ntwo\n");
    let l = list(&r.dir.join("app"));
    assert_eq!(l.top, r.top());
    assert_eq!(l.base, Base::Head);
    assert_eq!(names(&l), ["app/main.rs", "docs/notes.md"]);
    assert_eq!(entry(&l, "app/main.rs").counts, Some((3, 1)));
    assert_eq!(entry(&l, "docs/notes.md").counts, Some((1, 0)));
    assert!(!l.capped);
}

#[test]
fn staged_unstaged_and_untracked() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("three");
    let r = base_repo(&s);
    r.write("staged.txt", "a\nb\n");
    r.write("unstaged.txt", "a\n");
    r.commit_all("two");
    r.write("staged.txt", "a\nB\nc\n");
    r.git(&["add", "staged.txt"]);
    r.write("unstaged.txt", "a\nb\n");
    r.write("untracked.txt", "1\n2\n3\n");
    let l = list(&r.dir);
    type Row = (String, Kind, Option<(u64, u64)>);
    let got: Vec<Row> = l
        .entries
        .iter()
        .map(|e| (e.path.display(), e.kind, e.counts))
        .collect();
    assert_eq!(
        got,
        [
            ("staged.txt".into(), Kind::Changed, Some((2, 1))),
            ("unstaged.txt".into(), Kind::Changed, Some((1, 0))),
            ("untracked.txt".into(), Kind::New, Some((3, 0))),
        ]
    );
}

#[test]
fn committed_work_leaves_an_empty_list() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("committed");
    let r = base_repo(&s);
    r.write("a.txt", "a\n");
    r.commit_all("a");
    let l = list(&r.dir);
    assert!(l.entries.is_empty());
    assert!(!l.capped);
}

#[test]
fn a_rename_is_a_deleted_and_a_new_or_added_entry() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("rename");
    let r = base_repo(&s);
    r.write("old.txt", "x\ny\n");
    r.write("moved.txt", "m\n");
    r.commit_all("files");
    r.git(&["mv", "old.txt", "new.txt"]);
    std::fs::rename(r.dir.join("moved.txt"), r.dir.join("there.txt")).unwrap();
    let l = list(&r.dir);
    assert_eq!(entry(&l, "old.txt").kind, Kind::Deleted);
    assert_eq!(entry(&l, "old.txt").counts, Some((0, 2)));
    assert_eq!(entry(&l, "new.txt").kind, Kind::Added);
    assert_eq!(entry(&l, "new.txt").counts, Some((2, 0)));
    assert_eq!(entry(&l, "moved.txt").kind, Kind::Deleted);
    assert_eq!(entry(&l, "there.txt").kind, Kind::New);
    assert_eq!(l.entries.len(), 4);
}

fn no_first_commit(format: &str, want: ObjectFormat) {
    let s = Scratch::new(&format!("nocommit-{format}"));
    let r = s.repo("fresh", &[&format!("--object-format={format}")]);
    r.write("two.txt", "1\n2\n");
    r.git(&["add", "two.txt"]);
    r.write("two.txt", "1\n2\n3\n4\n");
    r.write("gone.txt", "g\n");
    r.git(&["add", "gone.txt"]);
    std::fs::remove_file(r.dir.join("gone.txt")).unwrap();
    r.write("loose.txt", "l\n");
    let l = list(&r.dir);
    assert_eq!(l.base, Base::EmptyTree(want));
    // git's order: tracked entries, then untracked ones.
    assert_eq!(names(&l), ["two.txt", "loose.txt"]);
    let two = entry(&l, "two.txt");
    assert_eq!((two.kind, two.counts), (Kind::Added, Some((4, 0))));
    assert_eq!(entry(&l, "loose.txt").kind, Kind::New);
    let d = diff(&l, "two.txt");
    let added: Vec<&str> = texts(&d)
        .into_iter()
        .filter(|t| t.starts_with('+') && !t.starts_with("+++"))
        .collect();
    assert_eq!(added, ["+1", "+2", "+3", "+4"]);
}

#[test]
fn no_first_commit_sha1() {
    if has_git() {
        no_first_commit("sha1", ObjectFormat::Sha1);
    }
}

#[test]
fn no_first_commit_sha256() {
    if has_git() {
        no_first_commit("sha256", ObjectFormat::Sha256);
    }
}

fn submodule_repo(s: &Scratch) -> (Repo, Repo, String, String) {
    let lib = s.repo("lib", &[]);
    lib.write("lib.rs", "one\n");
    lib.commit_all("one");
    let old = lib.git(&["rev-parse", "HEAD"]).trim().to_owned();
    let r = base_repo(s);
    r.git(&[
        "submodule",
        "add",
        "-q",
        lib.dir.to_str().unwrap(),
        "vendor/lib",
    ]);
    r.commit_all("submodule");
    let inner = Repo {
        dir: r.dir.join("vendor/lib"),
    };
    inner.write("lib.rs", "two\n");
    inner.commit_all("two");
    let new = inner.git(&["rev-parse", "HEAD"]).trim().to_owned();
    (r, inner, old, new)
}

#[test]
fn a_submodule_at_a_new_commit() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("submodule");
    let (r, _inner, old, new) = submodule_repo(&s);
    for log in [false, true] {
        if log {
            r.git(&["config", "diff.submodule", "log"]);
        }
        let l = list(&r.dir);
        let e = entry(&l, "vendor/lib");
        assert_eq!(e.kind, Kind::Submodule);
        assert_eq!(e.counts, None);
        let d = diff(&l, "vendor/lib");
        let t = texts(&d);
        assert!(
            t.contains(&format!("-Subproject commit {old}").as_str()),
            "{t:?}"
        );
        assert!(
            t.contains(&format!("+Subproject commit {new}").as_str()),
            "{t:?}"
        );
    }
}

#[test]
fn a_submodule_with_only_work_inside_is_not_listed() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("subdirty");
    let (r, inner, _, _) = submodule_repo(&s);
    r.commit_all("move submodule");
    inner.write("lib.rs", "dirty\n");
    assert!(list(&r.dir).entries.is_empty());
}

#[test]
fn an_untracked_clone_is_a_repository_that_does_not_open() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("nested");
    let r = base_repo(&s);
    let inner = Repo {
        dir: r.dir.join("clone"),
    };
    std::fs::create_dir_all(&inner.dir).unwrap();
    inner.git(&["init", "-q"]);
    inner.write("x.txt", "x\n");
    r.write("README.md", "website\nmore\n");
    let l = list(&r.dir);
    let e = entry(&l, "clone/");
    assert_eq!((e.kind, e.counts), (Kind::Repository, None));
    assert!(herdr_changes::diff_call(&l, e).is_none());
    assert_eq!(entry(&l, "README.md").counts, Some((1, 0)));
}

#[test]
fn a_conflict_shows_its_markers() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("conflict");
    let r = base_repo(&s);
    r.write("f.txt", "a\n");
    r.commit_all("f");
    r.git(&["checkout", "-q", "-b", "other"]);
    r.write("f.txt", "b\n");
    r.commit_all("b");
    r.git(&["checkout", "-q", "main"]);
    r.write("f.txt", "c\n");
    r.commit_all("c");
    let merge = std::process::Command::new("git")
        .current_dir(&r.dir)
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .args([
            "-c",
            "user.name=T",
            "-c",
            "user.email=t@example.org",
            "merge",
            "-q",
            "other",
        ])
        .output()
        .unwrap();
    assert!(!merge.status.success());
    let l = list(&r.dir);
    assert_eq!(entry(&l, "f.txt").kind, Kind::Conflict);
    let d = diff(&l, "f.txt");
    assert!(texts(&d).contains(&"+<<<<<<< HEAD"), "{:?}", texts(&d));
}

#[cfg(unix)]
#[test]
fn a_mode_only_change() {
    use std::os::unix::fs::PermissionsExt;
    if !has_git() {
        return;
    }
    let s = Scratch::new("mode");
    let r = base_repo(&s);
    r.write("run.sh", "echo hi\n");
    r.commit_all("script");
    let p = r.dir.join("run.sh");
    std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o755)).unwrap();
    let l = list(&r.dir);
    let e = entry(&l, "run.sh");
    assert_eq!(e.kind, Kind::Changed);
    assert_eq!(e.counts, Some((0, 0)));
    assert_eq!(e.modes, Some(("100644".into(), "100755".into())));
    let d = diff(&l, "run.sh");
    let t = texts(&d);
    assert!(
        t.contains(&"old mode 100644") && t.contains(&"new mode 100755"),
        "{t:?}"
    );
    assert_eq!(d.body_len(), 0);
}

#[test]
fn crlf_lines_keep_their_carriage_return() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("crlf");
    let r = base_repo(&s);
    r.write("w.txt", "one\ntwo\n");
    r.commit_all("w");
    r.write("w.txt", "one\r\ntwo\r\n");
    let l = list(&r.dir);
    assert_eq!(entry(&l, "w.txt").counts, Some((2, 2)));
    let d = diff(&l, "w.txt");
    let body: Vec<&str> = texts(&d)[d.headers..].to_vec();
    assert_eq!(
        body,
        ["@@ -1,2 +1,2 @@", "-one", "-two", "+one\r", "+two\r"]
    );
}

#[test]
fn a_binary_file() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("binary");
    let r = base_repo(&s);
    r.write("logo.png", b"\x89PNG\x00\x01");
    r.commit_all("logo");
    r.write("logo.png", b"\x89PNG\x00\x02\x03");
    let l = list(&r.dir);
    assert_eq!(entry(&l, "logo.png").counts, None);
    let d = diff(&l, "logo.png");
    assert!(
        texts(&d).contains(&"Binary files a/logo.png and b/logo.png differ"),
        "{:?}",
        texts(&d)
    );
}

#[test]
fn new_file_line_counts() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("newcounts");
    let r = base_repo(&s);
    r.git(&["config", "color.grep", "always"]);
    r.write("with space.txt", "a\nb\n");
    r.write("line\nbreak.txt", "a\n");
    r.write("-n.txt", "a\nb\nc\n");
    r.write("no-newline.txt", "a\nb");
    r.write("crlf.txt", "a\r\nb\r\n");
    r.write("blob.bin", b"\x00\x01\x02");
    r.write("empty.txt", "");
    let l = list(&r.dir);
    for (p, c) in [
        ("with space.txt", Some((2, 0))),
        ("line\nbreak.txt", Some((1, 0))),
        ("-n.txt", Some((3, 0))),
        ("no-newline.txt", Some((2, 0))),
        ("crlf.txt", Some((2, 0))),
        ("blob.bin", None),
        ("empty.txt", None),
    ] {
        let e = entry(&l, p);
        assert_eq!((e.kind, e.counts), (Kind::New, c), "{p}");
    }
    let d = diff(&l, "-n.txt");
    assert_eq!(
        &texts(&d)[d.headers..],
        ["@@ -0,0 +1,3 @@", "+a", "+b", "+c"]
    );
    let d = diff(&l, "empty.txt");
    assert_eq!(d.body_len(), 0);
    assert!(texts(&d).contains(&"new file mode 100644"));
    let d = diff(&l, "blob.bin");
    assert!(texts(&d).contains(&"Binary files /dev/null and b/blob.bin differ"));
}

#[test]
fn a_plain_folder_and_a_missing_one_are_not_a_repository() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("plain");
    let plain = s.dir.join("plain");
    std::fs::create_dir_all(&plain).unwrap();
    // A ceiling keeps git from finding a repository above the scratch folder.
    let ceiling = [("GIT_CEILING_DIRECTORIES", s.dir.to_str().unwrap())];
    assert_eq!(read_with(&plain, &ceiling), Outcome::NotARepo);
    assert_eq!(read_with(&s.dir.join("gone"), &ceiling), Outcome::NotARepo);
}

#[test]
fn not_a_repository_under_a_german_locale() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("german");
    let plain = s.dir.join("plain");
    std::fs::create_dir_all(&plain).unwrap();
    let speaks = std::process::Command::new("git")
        .args(["rev-parse", "--show-toplevel"])
        .current_dir(&plain)
        .env("GIT_CEILING_DIRECTORIES", &s.dir)
        .env("LC_ALL", "de_DE.UTF-8")
        .env("LANGUAGE", "de")
        .output()
        .unwrap();
    let msg = String::from_utf8_lossy(&speaks.stderr);
    if msg.starts_with("fatal: not a git repository") {
        eprintln!("skipped: git does not speak German here (no de_DE locale or translations)");
        return;
    }
    let env = [
        ("GIT_CEILING_DIRECTORIES", s.dir.to_str().unwrap()),
        ("LANG", "de_DE.UTF-8"),
        ("LC_MESSAGES", "de_DE.UTF-8"),
    ];
    assert_eq!(read_with(&plain, &env), Outcome::NotARepo);
}

#[test]
fn a_repository_owned_by_another_user_is_a_failure() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("owner");
    let r = base_repo(&s);
    // git's own test switch for "owned by someone else".
    match read_with(&r.dir, &[("GIT_TEST_ASSUME_DIFFERENT_OWNER", "1")]) {
        Outcome::Failed(Some(line)) => {
            assert!(
                line.starts_with("fatal: detected dubious ownership"),
                "{line}"
            )
        }
        Outcome::Ok(_) => eprintln!("skipped: this git ignores GIT_TEST_ASSUME_DIFFERENT_OWNER"),
        other => panic!("{other:?}"),
    }
}

fn drive(feed: Vec<Exec>) -> Outcome<herdr_changes::List> {
    let mut r = Reader::new("/work/website");
    let mut step = r.start();
    let mut feed = feed.into_iter();
    loop {
        match step {
            Step::Run(_) => step = r.feed(feed.next().expect("more results than calls")),
            Step::Done(o) => return o,
        }
    }
}

fn ran(code: Option<i32>, stdout: &str, stderr: &str, timed_out: bool) -> Exec {
    Exec::Ran {
        code,
        stdout: stdout.into(),
        stderr: stderr.into(),
        truncated: false,
        timed_out,
    }
}

#[test]
fn outcome_table() {
    // No git on the desk, or the folder is gone.
    assert_eq!(drive(vec![Exec::SpawnFailed]), Outcome::NotARepo);
    // Not a repository: exit 128 and git's untranslated message.
    assert_eq!(
        drive(vec![ran(
            Some(128),
            "",
            "fatal: not a git repository (or any of the parent directories): .git\n",
            false
        )]),
        Outcome::NotARepo
    );
    // Exit 128 with another message is a failure with its first line.
    assert_eq!(
        drive(vec![ran(Some(128), "", "fatal: bad config\nmore\n", false)]),
        Outcome::Failed(Some("fatal: bad config".into()))
    );
    // A timeout with nothing written.
    assert_eq!(drive(vec![ran(None, "", "", true)]), Outcome::Failed(None));
    // Another error of the call.
    assert_eq!(
        drive(vec![Exec::Error("unavailable".into())]),
        Outcome::Failed(None)
    );
    // Head: exit 1 with no id is "no first commit"; exit 1 with an id is not.
    let top = ran(Some(0), "/work/website\n", "", false);
    let st = ran(Some(0), "", "", false);
    assert_eq!(
        drive(vec![
            top.clone(),
            ran(Some(1), "sha1\n", "", false),
            st.clone()
        ]),
        Outcome::Ok(herdr_changes::List {
            top: "/work/website".into(),
            base: Base::EmptyTree(ObjectFormat::Sha1),
            entries: vec![],
            capped: false,
        })
    );
    assert!(matches!(
        drive(vec![top.clone(), ran(Some(2), "", "fatal: x\n", false)]),
        Outcome::Failed(Some(_))
    ));
    // A failing status after a found top is a failure, not "not a repository".
    assert!(matches!(
        drive(vec![
            top.clone(),
            ran(Some(0), "sha1\nabc\n", "", false),
            Exec::SpawnFailed
        ]),
        Outcome::Failed(None)
    ));
    // Grep: exit 1 (no text line found) is a success. Counts are not run
    // with no tracked entry.
    let status = ran(Some(0), "?? blob.bin\0", "", false);
    assert!(matches!(
        drive(vec![
            top.clone(),
            ran(Some(0), "sha1\nabc\n", "", false),
            status.clone(),
            ran(Some(1), "", "", false),
        ]),
        Outcome::Ok(_)
    ));
    assert!(matches!(
        drive(vec![
            top,
            ran(Some(0), "sha1\nabc\n", "", false),
            status,
            ran(Some(2), "", "fatal: grep broke\n", false),
        ]),
        Outcome::Failed(Some(_))
    ));
}
