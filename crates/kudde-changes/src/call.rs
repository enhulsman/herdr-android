//! The fixed argument lists (design D2). [`build`] is the only way to make a
//! [`Call`]; it takes a [`Shape`] and paths that came from the status output.

use crate::{Base, Path};
use serde_json::{Map, Value, json};

/// Follows `git` in every call: no pager, literal paths, no file system
/// monitor, no index refresh, paths unquoted, no colour.
pub const PREFIX: [&str; 10] = [
    "--no-pager",
    "--literal-pathspecs",
    "-c",
    "core.fsmonitor=false",
    "-c",
    "diff.autoRefreshIndex=false",
    "-c",
    "core.quotePath=false",
    "-c",
    "color.ui=never",
];

/// Set for every call: no optional locks, literal paths, no fetching of
/// missing objects, and git's messages untranslated (the outcome table).
pub const ENV: [(&str, &str); 5] = [
    ("GIT_OPTIONAL_LOCKS", "0"),
    ("GIT_LITERAL_PATHSPECS", "1"),
    ("GIT_NO_LAZY_FETCH", "1"),
    ("LC_ALL", "C"),
    ("LANGUAGE", "C"),
];

/// The most argument bytes one `grep` call carries (each argument counted
/// with one byte for its terminator), under Windows' command line limit.
pub const GREP_ARGS_MAX: usize = 24 * 1024;

/// One kind of git call.
#[derive(Clone, Copy, Debug)]
pub enum Shape<'a> {
    /// The repository's top, run from the agent's folder.
    Top,
    /// The object format and whether there is a first commit.
    Head,
    Status,
    /// Modes and line counts of tracked changes against `base`.
    Counts(Base),
    /// Line counts of new files, cut into calls of [`GREP_ARGS_MAX`].
    NewFileCounts(&'a [&'a Path]),
    Diff(Base, &'a Path),
    /// An untracked file as all added.
    NewFile(&'a Path),
}

/// One `machine.exec` call.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Call {
    pub argv: Vec<String>,
    pub cwd: String,
    pub timeout_s: u64,
}

impl Call {
    /// The environment set on top of the core's.
    pub fn env(&self) -> &'static [(&'static str, &'static str)] {
        &ENV
    }

    /// `machine.exec` parameters.
    pub fn params(&self) -> Value {
        let env: Map<String, Value> = ENV
            .iter()
            .map(|(k, v)| (k.to_string(), Value::from(*v)))
            .collect();
        json!({"argv": self.argv, "cwd": self.cwd, "env": env, "timeout": self.timeout_s})
    }
}

/// The calls for `shape`, run from `cwd`: the agent's folder for
/// [`Shape::Top`], the repository's top for the others. Empty when no path
/// can be passed: a folder entry, or a path that is not UTF-8.
pub fn build(shape: Shape, cwd: &str) -> Vec<Call> {
    let call = |timeout_s: u64, args: &[&str]| Call {
        argv: std::iter::once("git")
            .chain(PREFIX)
            .chain(args.iter().copied())
            .map(str::to_owned)
            .collect(),
        cwd: cwd.to_owned(),
        timeout_s,
    };
    match shape {
        Shape::Top => vec![call(20, &["rev-parse", "--show-toplevel", "--"])],
        Shape::Head => vec![call(
            20,
            &[
                "rev-parse",
                "--show-object-format",
                "--verify",
                "--quiet",
                "HEAD",
                "--",
            ],
        )],
        Shape::Status => vec![call(
            30,
            &[
                "status",
                "--porcelain=v1",
                "-z",
                "--no-renames",
                "--untracked-files=all",
                "--ignore-submodules=dirty",
                "--",
            ],
        )],
        Shape::Counts(base) => vec![call(
            30,
            &[
                "diff",
                base.arg(),
                "--raw",
                "--numstat",
                "-z",
                "--no-renames",
                "--ignore-submodules=dirty",
                "--no-ext-diff",
                "--no-textconv",
                "--",
            ],
        )],
        Shape::Diff(base, path) => file_arg(path)
            .map(|p| {
                call(
                    30,
                    &[
                        "diff",
                        base.arg(),
                        "--no-ext-diff",
                        "--no-textconv",
                        "--no-color",
                        "--submodule=short",
                        "--ignore-submodules=dirty",
                        "--",
                        p,
                    ],
                )
            })
            .into_iter()
            .collect(),
        Shape::NewFile(path) => file_arg(path)
            .map(|p| {
                call(
                    30,
                    &[
                        "diff",
                        "--no-index",
                        "--no-ext-diff",
                        "--no-textconv",
                        "--no-color",
                        "--",
                        "/dev/null",
                        p,
                    ],
                )
            })
            .into_iter()
            .collect(),
        Shape::NewFileCounts(paths) => {
            const HEAD: [&str; 9] = [
                "grep",
                "--untracked",
                "-I",
                "-c",
                "-z",
                "--no-color",
                "-e",
                "",
                "--",
            ];
            let mut calls = Vec::new();
            let mut cur = call(30, &HEAD);
            let empty = cur.argv.len();
            let mut size: usize = cur.argv.iter().map(|a| a.len() + 1).sum();
            for p in paths.iter().filter_map(|p| file_arg(p)) {
                let n = p.len() + 1;
                if cur.argv.len() > empty && size + n > GREP_ARGS_MAX {
                    calls.push(std::mem::replace(&mut cur, call(30, &HEAD)));
                    size = cur.argv.iter().map(|a| a.len() + 1).sum();
                }
                cur.argv.push(p.to_owned());
                size += n;
            }
            if cur.argv.len() > empty {
                calls.push(cur);
            }
            calls
        }
    }
}

/// A path that may be passed to a file shape: UTF-8 and not a folder.
fn file_arg(path: &Path) -> Option<&str> {
    path.arg().filter(|_| !path.is_folder())
}
