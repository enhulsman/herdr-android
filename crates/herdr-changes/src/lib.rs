//! The Changes view's git calls (OpenSpec change `changes-view`, design D1-D4):
//! the fixed argument lists git runs from, the parsing of what it prints, and
//! the outcome of each call. Pure functions: herdr-mobile runs the calls
//! through herdr-core's `machine.exec`.

pub mod call;
pub mod diff;
pub mod parse;
pub mod read;

pub use call::{Call, Shape, build};
pub use diff::{Diff, Line, parse_diff};
pub use parse::{StatusEntry, parse_status};
pub use read::{Entry, Exec, Kind, List, Outcome, Reader, Step, diff_call};

/// A path as git printed it in the status output, from the repository's top.
/// Only [`parse_status`] makes one, so a call never carries a path that did
/// not come from git (design D2).
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Path(Vec<u8>);

impl Path {
    pub(crate) fn from_git(bytes: &[u8]) -> Path {
        Path(bytes.to_vec())
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }

    /// The path for display; bytes that are not UTF-8 show as U+FFFD.
    pub fn display(&self) -> String {
        String::from_utf8_lossy(&self.0).into_owned()
    }

    /// An untracked folder git did not descend into: a repository of its own.
    pub fn is_folder(&self) -> bool {
        self.0.last() == Some(&b'/')
    }

    /// The path as an argument: None when it is not UTF-8, which `argv`
    /// (JSON strings) cannot carry.
    pub(crate) fn arg(&self) -> Option<&str> {
        std::str::from_utf8(&self.0).ok()
    }
}

/// The repository's object format, from the `head` call.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ObjectFormat {
    Sha1,
    Sha256,
}

/// What the changes are taken against: the last commit, or with no first
/// commit the empty tree, so every file counts as added from the disk.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Base {
    Head,
    EmptyTree(ObjectFormat),
}

impl Base {
    pub fn arg(&self) -> &'static str {
        match self {
            Base::Head => "HEAD",
            Base::EmptyTree(ObjectFormat::Sha1) => "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
            Base::EmptyTree(ObjectFormat::Sha256) => {
                "6ef19b41225c5369f1c104d45d8d85efa9b057b53b14b4b9b939dd74decc5321"
            }
        }
    }
}
