//! Task 2.3: git runs read-only and never blocks the agent (spec: "Git runs
//! from fixed argument lists and never blocks the agent").

#![cfg(unix)]

mod common;

use common::*;
use herdr_changes::{Kind, List, Outcome, diff_call, parse_diff};
use std::collections::BTreeMap;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path as FsPath, PathBuf};

/// Every file under `dir` with its size, modification time and content.
fn snapshot(dir: &FsPath) -> BTreeMap<PathBuf, (u64, i64, i64, Vec<u8>)> {
    fn walk(dir: &FsPath, out: &mut BTreeMap<PathBuf, (u64, i64, i64, Vec<u8>)>) {
        for e in std::fs::read_dir(dir).unwrap() {
            let p = e.unwrap().path();
            let m = std::fs::symlink_metadata(&p).unwrap();
            if m.is_dir() {
                out.insert(p.clone(), (0, 0, 0, Vec::new()));
                walk(&p, out);
            } else {
                let body = std::fs::read(&p).unwrap_or_default();
                out.insert(p, (m.len(), m.mtime(), m.mtime_nsec(), body));
            }
        }
    }
    let mut out = BTreeMap::new();
    walk(dir, &mut out);
    out
}

fn assert_same(before: &BTreeMap<PathBuf, (u64, i64, i64, Vec<u8>)>, dir: &FsPath) {
    let after = snapshot(dir);
    let added: Vec<_> = after.keys().filter(|k| !before.contains_key(*k)).collect();
    let gone: Vec<_> = before.keys().filter(|k| !after.contains_key(*k)).collect();
    let changed: Vec<_> = before
        .iter()
        .filter(|(k, v)| after.get(*k).is_some_and(|a| a != *v))
        .map(|(k, _)| k)
        .collect();
    assert!(
        added.is_empty() && gone.is_empty() && changed.is_empty(),
        "under {dir:?}: created {added:?}, removed {gone:?}, changed {changed:?}"
    );
}

/// A script that creates `marker` and then acts as `rest`.
fn script(dir: &FsPath, name: &str, marker: &FsPath, rest: &str) -> String {
    let p = dir.join(name);
    std::fs::write(
        &p,
        format!("#!/bin/sh\ntouch '{}'\n{rest}\n", marker.display()),
    )
    .unwrap();
    std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o755)).unwrap();
    p.to_str().unwrap().to_owned()
}

/// The list and every entry's diff.
fn open_all(l: &List) -> usize {
    let mut opened = 0;
    for e in &l.entries {
        let Some(c) = diff_call(l, e) else { continue };
        match parse_diff(e.kind, run(&c)) {
            Outcome::Ok(d) => {
                assert!(!d.is_empty(), "{}", e.path.display());
                opened += 1;
            }
            other => panic!("{}: {other:?}", e.path.display()),
        }
    }
    opened
}

#[test]
fn a_hostile_repository() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("hostile");
    let bin = s.dir.join("bin");
    let marks = s.dir.join("marks");
    std::fs::create_dir_all(&bin).unwrap();
    std::fs::create_dir_all(&marks).unwrap();
    let r = s.repo("website", &[]);
    let ext = script(&bin, "ext", &marks.join("external"), "exit 0");
    let attr = script(&bin, "attr", &marks.join("attr-driver"), "exit 0");
    let conv = script(&bin, "conv", &marks.join("textconv"), "cat \"$1\"");
    let mon = script(&bin, "mon", &marks.join("fsmonitor"), "exit 1");
    r.git(&["config", "diff.external", &ext]);
    r.git(&["config", "diff.drv.command", &attr]);
    r.git(&["config", "diff.conv.textconv", &conv]);
    r.git(&["config", "core.fsmonitor", &mon]);
    r.write(".gitattributes", "*.md diff=drv\n*.txt diff=conv\n");
    for f in ["--output=x", ":(glob)*", "HEAD", "notes.md", "a.txt"] {
        r.write(f, "one\n");
    }
    r.commit_all("hostile");
    // The commit itself ran the monitor: start counting from here.
    for m in std::fs::read_dir(&marks).unwrap() {
        std::fs::remove_file(m.unwrap().path()).unwrap();
    }
    for f in ["--output=x", ":(glob)*", "HEAD", "notes.md", "a.txt"] {
        r.write(f, "one\ntwo\n");
    }
    r.write("-new.txt", "fresh\n");
    // The agent holds the index lock.
    let lock = r.dir.join(".git/index.lock");
    std::fs::write(&lock, "").unwrap();
    let before = snapshot(&r.dir.join(".git"));

    let l = list(&r.dir);
    let mut got: Vec<(String, Kind)> = l
        .entries
        .iter()
        .map(|e| (e.path.display(), e.kind))
        .collect();
    got.sort_by(|a, b| a.0.cmp(&b.0));
    assert_eq!(
        got,
        [
            ("--output=x".into(), Kind::Changed),
            ("-new.txt".into(), Kind::New),
            (":(glob)*".into(), Kind::Changed),
            ("HEAD".into(), Kind::Changed),
            ("a.txt".into(), Kind::Changed),
            ("notes.md".into(), Kind::Changed),
        ]
    );
    assert_eq!(open_all(&l), 6);
    // No `--output=x` option took effect: no file named `x` appeared.
    assert!(!r.dir.join("x").exists());
    let marked: Vec<_> = std::fs::read_dir(&marks)
        .unwrap()
        .map(|m| m.unwrap().file_name())
        .collect();
    assert!(marked.is_empty(), "ran: {marked:?}");
    assert_same(&before, &r.dir.join(".git"));

    // The agent releases its lock and commits.
    std::fs::remove_file(&lock).unwrap();
    r.git(&["commit", "-q", "-am", "agent"]);

    // The setup is not vacuous: plain git runs the driver and the monitor.
    r.write("notes.md", "three\n");
    // (Plain `git diff HEAD` would fail here: the file named HEAD.)
    r.git(&["diff", "HEAD", "--"]);
    assert!(marks.join("external").exists() || marks.join("attr-driver").exists());
    assert!(marks.join("fsmonitor").exists());
}

#[test]
fn nothing_written_under_git_for_a_list_and_three_diffs() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("nowrite");
    let r = s.repo("website", &[]);
    for f in ["a.txt", "b.txt", "c.txt"] {
        r.write(f, "one\n");
    }
    r.commit_all("three");
    // Changed after the index was written: a status that may write would
    // refresh the index here.
    std::thread::sleep(std::time::Duration::from_millis(1100));
    for f in ["a.txt", "b.txt", "c.txt"] {
        r.write(f, "one\ntwo\n");
    }
    r.write("new.txt", "n\n");
    let before = snapshot(&r.dir.join(".git"));
    let l = list(&r.dir);
    assert_eq!(open_all(&l), 4);
    assert_same(&before, &r.dir.join(".git"));
}

#[test]
fn nothing_written_in_a_linked_worktree() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("worktree");
    let r = s.repo("website", &[]);
    r.write("a.txt", "one\n");
    r.commit_all("one");
    let wt = s.dir.join("website-wt");
    r.git(&["worktree", "add", "-q", wt.to_str().unwrap()]);
    let w = Repo { dir: wt.clone() };
    w.write("a.txt", "one\ntwo\n");
    w.write("b.txt", "b\n");
    let before = snapshot(&r.dir.join(".git"));
    let l = list(&wt);
    assert_eq!(l.top, wt.to_str().unwrap());
    assert_eq!(open_all(&l), 2);
    assert_same(&before, &r.dir.join(".git"));
}

#[test]
fn a_clean_filter() {
    if !has_git() {
        return;
    }
    let s = Scratch::new("filter");
    let bin = s.dir.join("bin");
    std::fs::create_dir_all(&bin).unwrap();
    let r = s.repo("website", &[]);
    let clean = script(&bin, "clean", &s.dir.join("cleaned"), "tr a-z A-Z");
    r.git(&["config", "filter.up.clean", &clean]);
    r.git(&["config", "filter.up.smudge", "cat"]);
    r.write(".gitattributes", "*.dat filter=up\n");
    r.write("data.dat", "one\n");
    r.commit_all("filtered");
    std::thread::sleep(std::time::Duration::from_millis(1100));
    r.write("data.dat", "one\ntwo\n");
    let index = r.dir.join(".git/index");
    let before = std::fs::metadata(&index).unwrap();
    let before_body = std::fs::read(&index).unwrap();
    let l = list(&r.dir);
    assert_eq!(entry(&l, "data.dat").kind, Kind::Changed);
    let d = diff(&l, "data.dat");
    assert!(!d.is_empty());
    let after = std::fs::metadata(&index).unwrap();
    assert_eq!(std::fs::read(&index).unwrap(), before_body);
    assert_eq!(
        (after.mtime(), after.mtime_nsec()),
        (before.mtime(), before.mtime_nsec())
    );
    assert!(!r.dir.join(".git/index.lock").exists());
}
