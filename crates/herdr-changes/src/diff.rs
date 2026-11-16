//! A fetched diff (design D3), cut for the phone.

use crate::read::{Exec, Kind, Outcome, check};

/// A line longer than this many characters is cut, also in the whole diff.
pub const LINE_MAX: usize = 4000;
/// Lines shown before "Show the whole diff (N lines)".
pub const SHOWN_FIRST: usize = 2000;
/// Lines after the file headers held at most; past this the diff is "Too
/// large to show on the phone" (8 MB of near-empty lines would otherwise be
/// millions of records).
pub const LINES_MAX: usize = 50_000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Line {
    /// The line as git printed it, without its line feed; a carriage return
    /// before the line feed is kept (the screen marks it).
    pub text: String,
    /// The line was longer than [`LINE_MAX`] characters and was cut.
    pub cut: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Diff {
    /// Every line, file headers included.
    pub lines: Vec<Line>,
    /// How many of [`Diff::lines`] are file headers (`diff --git` up to and
    /// including `+++`, or the extended header when there is no `+++`).
    pub headers: usize,
    /// The output passed what one call returns, or [`LINES_MAX`] lines:
    /// [`Diff::lines`] is its start ("Too large to show on the phone").
    pub too_large: bool,
}

impl Diff {
    /// The lines after the file headers: the N of "Show the whole diff".
    pub fn body_len(&self) -> usize {
        self.lines.len() - self.headers
    }

    /// Whether the screen first shows [`SHOWN_FIRST`] lines with the control.
    pub fn is_long(&self) -> bool {
        !self.too_large && self.body_len() > SHOWN_FIRST
    }

    /// No difference: the file is no longer changed.
    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }
}

/// The outcome of a diff call for an entry of `kind` (design D2's table).
pub fn parse_diff(kind: Kind, exec: Exec) -> Outcome<Diff> {
    let new = kind == Kind::New;
    let ok: &[i32] = if new { &[0, 1] } else { &[0] };
    let (out, truncated) = match check(exec, false, ok) {
        Ok(r) => r,
        Err(Outcome::Failed(line)) => return Outcome::Failed(line),
        Err(_) => return Outcome::Failed(None),
    };
    if new && !out.starts_with(b"diff --git") {
        return Outcome::Failed(None);
    }
    // Never more than the cap and the longest header are split off.
    let mut split = out.split(|&b| b == b'\n');
    let mut parts: Vec<&[u8]> = split
        .by_ref()
        .take(LINES_MAX + EXTENDED.len() + 3)
        .collect();
    let more = split.next().is_some();
    // The part after the last line feed: empty, or a line cut off mid-way.
    if !more && parts.last().is_some_and(|p| p.is_empty() || truncated) {
        parts.pop();
    }
    let mut lines: Vec<Line> = parts.into_iter().map(line).collect();
    let headers = headers(&lines);
    let mut too_large = truncated;
    if lines.len() - headers > LINES_MAX {
        lines.truncate(headers + LINES_MAX);
        too_large = true;
    }
    Outcome::Ok(Diff {
        lines,
        headers,
        too_large,
    })
}

fn line(bytes: &[u8]) -> Line {
    let text = String::from_utf8_lossy(bytes);
    match text.char_indices().nth(LINE_MAX) {
        Some((at, _)) => Line {
            text: text[..at].to_owned(),
            cut: true,
        },
        None => Line {
            text: text.into_owned(),
            cut: false,
        },
    }
}

/// Lines of git's extended header (between `diff --git` and `---`).
const EXTENDED: [&str; 12] = [
    "diff --git ",
    "old mode ",
    "new mode ",
    "deleted file mode ",
    "new file mode ",
    "index ",
    "similarity index ",
    "dissimilarity index ",
    "rename from ",
    "rename to ",
    "copy from ",
    "copy to ",
];

fn headers(lines: &[Line]) -> usize {
    let extended = lines
        .iter()
        .take_while(|l| EXTENDED.iter().any(|p| l.text.starts_with(p)))
        .count();
    match (lines.get(extended), lines.get(extended + 1)) {
        (Some(a), Some(b)) if a.text.starts_with("--- ") && b.text.starts_with("+++ ") => {
            extended + 2
        }
        _ => extended,
    }
}
