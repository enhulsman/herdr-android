//! A read of the list (design D2): top, head, status, counts and new-file
//! counts, one call after another, as a step machine the caller drives with
//! `machine.exec` results. No I/O here.

use crate::call::{Shape, build};
use crate::parse::{Counts, StatusEntry, parse_counts, parse_grep_counts, parse_status};
use crate::{Base, Call, ObjectFormat, Path};

/// The most entries a list holds (spec: "Showing the first 1,000 changed
/// files").
pub const ENTRIES_MAX: usize = 1000;

/// What `machine.exec` gave for one call.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Exec {
    Ran {
        /// None when it was ended (a timeout or a signal).
        code: Option<i32>,
        stdout: Vec<u8>,
        stderr: Vec<u8>,
        truncated: bool,
        timed_out: bool,
    },
    /// The core could not start git: no git on the desk, or the folder is
    /// gone.
    SpawnFailed,
    /// Any other error of the call, by its kind.
    Error(String),
}

impl Exec {
    /// A `machine.exec` result (`stdout` and `stderr` in base64).
    pub fn from_result(result: &serde_json::Value) -> Exec {
        use base64::Engine;
        let b64 = |k: &str| {
            result[k]
                .as_str()
                .and_then(|s| base64::engine::general_purpose::STANDARD.decode(s).ok())
                .unwrap_or_default()
        };
        Exec::Ran {
            code: result["code"].as_i64().map(|c| c as i32),
            stdout: b64("stdout"),
            stderr: b64("stderr"),
            truncated: result["truncated"] == true,
            timed_out: result["timed_out"] == true,
        }
    }
}

/// How a read or a diff ended.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Outcome<T> {
    Ok(T),
    /// "Not a git repository".
    NotARepo,
    /// "Could not read the changes", with git's first error line when it
    /// wrote one.
    Failed(Option<String>),
}

/// An entry's status word.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Changed,
    Added,
    Deleted,
    New,
    Conflict,
    Submodule,
    /// An untracked folder that is a repository of its own; it does not open.
    Repository,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub path: Path,
    pub kind: Kind,
    /// Lines added and removed; for a new file its line count and 0. None
    /// for a binary, empty or not yet counted file, a submodule and a
    /// repository.
    pub counts: Option<(u64, u64)>,
    /// The old and new file mode from `--raw` (tracked entries).
    pub modes: Option<(String, String)>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct List {
    pub top: String,
    pub base: Base,
    pub entries: Vec<Entry>,
    /// More entries than [`ENTRIES_MAX`], or the status output was cut.
    pub capped: bool,
}

/// What to do next.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Step {
    Run(Call),
    Done(Outcome<List>),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Stage {
    Top,
    Head,
    Status,
    Counts,
    Grep,
    Done,
}

/// One read of the list.
pub struct Reader {
    folder: String,
    stage: Stage,
    top: String,
    base: Base,
    status: Vec<StatusEntry>,
    capped: bool,
    entries: Vec<Entry>,
    grep: std::vec::IntoIter<Call>,
}

/// A successful call's stdout and whether it was cut, or how the read ends.
type Checked<T> = Result<T, Outcome<List>>;

/// git's first error line, if it wrote one.
fn first_line(stderr: &[u8]) -> Option<String> {
    String::from_utf8_lossy(stderr)
        .lines()
        .map(str::trim_end)
        .find(|l| !l.is_empty())
        .map(str::to_owned)
}

/// Design D2's outcome table: `ok` says which exit codes succeed.
pub(crate) fn check(exec: Exec, top: bool, ok: &[i32]) -> Result<(Vec<u8>, bool), Outcome<()>> {
    match exec {
        Exec::SpawnFailed if top => Err(Outcome::NotARepo),
        Exec::SpawnFailed | Exec::Error(_) => Err(Outcome::Failed(None)),
        Exec::Ran {
            code,
            stdout,
            stderr,
            truncated,
            timed_out,
        } => {
            if !timed_out && code.is_some_and(|c| ok.contains(&c)) {
                return Ok((stdout, truncated));
            }
            if top
                && !timed_out
                && code == Some(128)
                && stderr.starts_with(b"fatal: not a git repository")
            {
                return Err(Outcome::NotARepo);
            }
            Err(Outcome::Failed(first_line(&stderr)))
        }
    }
}

fn end<T>(o: Outcome<()>) -> Outcome<T> {
    match o {
        Outcome::Ok(()) | Outcome::Failed(None) => Outcome::Failed(None),
        Outcome::NotARepo => Outcome::NotARepo,
        Outcome::Failed(line) => Outcome::Failed(line),
    }
}

fn is_conflict(xy: [u8; 2]) -> bool {
    matches!(&xy, b"DD" | b"AU" | b"UD" | b"UA" | b"DU" | b"AA" | b"UU")
}

impl Reader {
    pub fn new(folder: &str) -> Reader {
        Reader {
            folder: folder.to_owned(),
            stage: Stage::Top,
            top: String::new(),
            base: Base::Head,
            status: Vec::new(),
            capped: false,
            entries: Vec::new(),
            grep: Vec::new().into_iter(),
        }
    }

    fn run(&mut self, stage: Stage, shape: Shape) -> Step {
        self.stage = stage;
        let cwd = if stage == Stage::Top {
            &self.folder
        } else {
            &self.top
        };
        let mut calls = build(shape, cwd);
        Step::Run(calls.remove(0))
    }

    /// The first call.
    pub fn start(&mut self) -> Step {
        self.run(Stage::Top, Shape::Top)
    }

    /// The result of the last call; the next step.
    pub fn feed(&mut self, exec: Exec) -> Step {
        match self.advance(exec) {
            Ok(step) => step,
            Err(o) => {
                self.stage = Stage::Done;
                Step::Done(o)
            }
        }
    }

    fn advance(&mut self, exec: Exec) -> Checked<Step> {
        let ok = |exec, top, codes: &[i32]| check(exec, top, codes).map_err(end);
        match self.stage {
            Stage::Top => {
                let (out, _) = ok(exec, true, &[0])?;
                // The top, then the `--` that rev-parse echoes; a top may hold
                // a line feed of its own, and one that is not UTF-8 cannot be
                // passed to machine.exec as it is.
                let top = out
                    .strip_suffix(b"\n--\n")
                    .or_else(|| out.strip_suffix(b"\n"))
                    .unwrap_or(&out);
                match std::str::from_utf8(top) {
                    Ok(t) if !t.is_empty() => self.top = t.to_owned(),
                    _ => return Err(Outcome::Failed(None)),
                }
                Ok(self.run(Stage::Head, Shape::Head))
            }
            Stage::Head => {
                let (code, out) = match exec {
                    Exec::Ran {
                        code: Some(1),
                        ref stdout,
                        timed_out: false,
                        ..
                    } => (1, stdout.clone()),
                    other => (0, ok(other, false, &[0])?.0),
                };
                let text = String::from_utf8_lossy(&out);
                let mut lines = text.lines();
                let format = match lines.next() {
                    Some("sha1") => ObjectFormat::Sha1,
                    Some("sha256") => ObjectFormat::Sha256,
                    _ => return Err(Outcome::Failed(None)),
                };
                let id = lines.next().is_some_and(|l| !l.trim().is_empty());
                self.base = match (code, id) {
                    (0, true) => Base::Head,
                    (1, false) => Base::EmptyTree(format),
                    _ => return Err(Outcome::Failed(None)),
                };
                Ok(self.run(Stage::Status, Shape::Status))
            }
            Stage::Status => {
                let (out, truncated) = ok(exec, false, &[0])?;
                self.capped = truncated;
                self.status = parse_status(&out);
                if self.status.iter().any(|e| e.xy != *b"??") {
                    let base = self.base;
                    Ok(self.run(Stage::Counts, Shape::Counts(base)))
                } else {
                    self.list(&Counts::default())
                }
            }
            Stage::Counts => {
                let (out, _) = ok(exec, false, &[0])?;
                self.list(&parse_counts(&out))
            }
            Stage::Grep => {
                let (out, _) = ok(exec, false, &[0, 1])?;
                let counts = parse_grep_counts(&out);
                for e in self.entries.iter_mut().filter(|e| e.kind == Kind::New) {
                    if let Some(&n) = counts.get(e.path.as_bytes()) {
                        e.counts = Some((n, 0));
                    }
                }
                self.next_grep()
            }
            Stage::Done => Err(Outcome::Failed(None)),
        }
    }

    /// The entries from status and counts, capped; then the new files' counts.
    fn list(&mut self, counts: &Counts) -> Checked<Step> {
        let mut entries = Vec::new();
        for s in std::mem::take(&mut self.status) {
            let key = s.path.as_bytes().to_vec();
            let numstat = counts.numstat.get(&key).copied().flatten();
            let raw = counts.raw.get(&key);
            let modes = raw.map(|r| (r.old_mode.clone(), r.new_mode.clone()));
            let (kind, counts) = if s.xy == *b"??" {
                if s.path.is_folder() {
                    (Kind::Repository, None)
                } else {
                    (Kind::New, None)
                }
            } else if is_conflict(s.xy) {
                (Kind::Conflict, numstat)
            } else {
                // A tracked entry with no difference against the base (added
                // then deleted, or staged and changed back) is not listed.
                let Some(r) = raw else { continue };
                if r.old_mode == "160000" || r.new_mode == "160000" {
                    (Kind::Submodule, None)
                } else {
                    let kind = match r.letter {
                        b'A' => Kind::Added,
                        b'D' => Kind::Deleted,
                        _ => Kind::Changed,
                    };
                    (kind, numstat)
                }
            };
            if entries.len() == ENTRIES_MAX {
                self.capped = true;
                break;
            }
            entries.push(Entry {
                path: s.path,
                kind,
                counts,
                modes,
            });
        }
        self.entries = entries;
        let new: Vec<&Path> = self
            .entries
            .iter()
            .filter(|e| e.kind == Kind::New)
            .map(|e| &e.path)
            .collect();
        self.grep = build(Shape::NewFileCounts(&new), &self.top).into_iter();
        self.next_grep()
    }

    fn next_grep(&mut self) -> Checked<Step> {
        match self.grep.next() {
            Some(call) => {
                self.stage = Stage::Grep;
                Ok(Step::Run(call))
            }
            None => {
                self.stage = Stage::Done;
                Err(Outcome::Ok(List {
                    top: std::mem::take(&mut self.top),
                    base: self.base,
                    entries: std::mem::take(&mut self.entries),
                    capped: self.capped,
                }))
            }
        }
    }
}

/// The call that fetches `entry`'s diff, None for an entry that does not
/// open (a repository) or whose path cannot be passed.
pub fn diff_call(list: &List, entry: &Entry) -> Option<Call> {
    let shape = match entry.kind {
        Kind::Repository => return None,
        Kind::New => Shape::NewFile(&entry.path),
        _ => Shape::Diff(list.base, &entry.path),
    };
    build(shape, &list.top).into_iter().next()
}
