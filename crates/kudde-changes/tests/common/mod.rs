//! Real git in temporary repositories (design D8). Every test skips with a
//! message when git is missing. Global and system git config are shut out,
//! so a test's repository config is the only config.

#![allow(dead_code)]

use kudde_changes::{
    Diff, Entry, Exec, List, Outcome, Reader, Step, call::ENV, diff_call, parse_diff,
};
use std::path::{Path as FsPath, PathBuf};
use std::process::Command;

pub fn has_git() -> bool {
    let ok = Command::new("git")
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false);
    if !ok {
        eprintln!("skipped: git is not installed");
    }
    ok
}

/// Environment for every git this file starts: no global or system config,
/// no inherited repository.
fn isolate(c: &mut Command) -> &mut Command {
    c.env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env_remove("GIT_DIR")
        .env_remove("GIT_WORK_TREE")
        .env_remove("GIT_INDEX_FILE")
        .env_remove("GIT_CEILING_DIRECTORIES")
}

pub struct Scratch {
    pub dir: PathBuf,
}

impl Scratch {
    pub fn new(name: &str) -> Scratch {
        let dir = std::env::temp_dir().join(format!("kudde-changes-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        Scratch {
            dir: dir.canonicalize().unwrap(),
        }
    }

    /// A repository in `rel` (created) with `init_args`.
    pub fn repo(&self, rel: &str, init_args: &[&str]) -> Repo {
        let dir = self.dir.join(rel);
        std::fs::create_dir_all(&dir).unwrap();
        let r = Repo { dir };
        let mut args = vec!["init", "-q", "-b", "main"];
        args.extend_from_slice(init_args);
        r.git(&args);
        r
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

pub struct Repo {
    pub dir: PathBuf,
}

impl Repo {
    pub fn top(&self) -> String {
        self.dir.to_str().unwrap().to_owned()
    }

    /// Runs git in the repository and asserts it succeeded.
    pub fn git(&self, args: &[&str]) -> String {
        let out = isolate(Command::new("git").current_dir(&self.dir))
            .args([
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "protocol.file.allow=always",
            ])
            .args(args)
            .output()
            .unwrap();
        assert!(
            out.status.success(),
            "git {args:?}: {}",
            String::from_utf8_lossy(&out.stderr)
        );
        String::from_utf8_lossy(&out.stdout).into_owned()
    }

    pub fn write(&self, rel: &str, bytes: impl AsRef<[u8]>) {
        let p = self.dir.join(rel);
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, bytes).unwrap();
    }

    pub fn commit_all(&self, msg: &str) {
        self.git(&["add", "-A"]);
        self.git(&["commit", "-q", "-m", msg]);
    }
}

/// Runs a call as herdr-core's `machine.exec` would: argv as given, the
/// call's cwd and env on top of this process's (plus `extra`).
pub fn run_with(call: &kudde_changes::Call, extra: &[(&str, &str)]) -> Exec {
    let mut c = Command::new(&call.argv[0]);
    isolate(&mut c)
        .args(&call.argv[1..])
        .current_dir(&call.cwd)
        .envs(ENV.iter().copied())
        .envs(extra.iter().copied());
    match c.output() {
        Ok(o) => Exec::Ran {
            code: o.status.code(),
            stdout: o.stdout,
            stderr: o.stderr,
            truncated: false,
            timed_out: false,
        },
        Err(_) => Exec::SpawnFailed,
    }
}

pub fn run(call: &kudde_changes::Call) -> Exec {
    run_with(call, &[])
}

pub fn read_with(folder: &FsPath, extra: &[(&str, &str)]) -> Outcome<List> {
    let mut r = Reader::new(folder.to_str().unwrap());
    let mut step = r.start();
    let mut calls = 0;
    loop {
        match step {
            Step::Run(c) => {
                calls += 1;
                assert!(calls < 100, "a read that does not end");
                step = r.feed(run_with(&c, extra));
            }
            Step::Done(o) => return o,
        }
    }
}

pub fn read(folder: &FsPath) -> Outcome<List> {
    read_with(folder, &[])
}

pub fn list(folder: &FsPath) -> List {
    match read(folder) {
        Outcome::Ok(l) => l,
        other => panic!("read: {other:?}"),
    }
}

pub fn entry<'a>(l: &'a List, path: &str) -> &'a Entry {
    l.entries
        .iter()
        .find(|e| e.path.as_bytes() == path.as_bytes())
        .unwrap_or_else(|| panic!("{path} not in {:?}", names(l)))
}

pub fn names(l: &List) -> Vec<String> {
    l.entries.iter().map(|e| e.path.display()).collect()
}

pub fn diff(l: &List, path: &str) -> Diff {
    let e = entry(l, path);
    let c = diff_call(l, e).expect("a diff call");
    match parse_diff(e.kind, run(&c)) {
        Outcome::Ok(d) => d,
        other => panic!("diff {path}: {other:?}"),
    }
}

pub fn texts(d: &Diff) -> Vec<&str> {
    d.lines.iter().map(|l| l.text.as_str()).collect()
}
