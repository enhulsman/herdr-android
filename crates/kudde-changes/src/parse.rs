//! Parsing what git prints.

use crate::Path;
use std::collections::HashMap;

/// One `--raw` record of the counts call.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Raw {
    pub old_mode: String,
    pub new_mode: String,
    /// The status letter: M, A, D, T or U.
    pub letter: u8,
}

/// The counts call (`diff --raw --numstat -z --no-renames`): raw records by
/// path, and line counts by path (None for a binary file).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Counts {
    pub raw: HashMap<Vec<u8>, Raw>,
    pub numstat: HashMap<Vec<u8>, Option<(u64, u64)>>,
}

pub fn parse_counts(out: &[u8]) -> Counts {
    let mut c = Counts::default();
    let mut tokens = out.split(|&b| b == 0);
    while let Some(t) = tokens.next() {
        if t.is_empty() {
            continue;
        }
        if t[0] == b':' {
            let Some(path) = tokens.next() else { break };
            let fields: Vec<&[u8]> = t[1..].split(|&b| b == b' ').collect();
            if let [om, nm, _, _, letter, ..] = fields[..]
                && !letter.is_empty()
            {
                c.raw.insert(
                    path.to_vec(),
                    Raw {
                        old_mode: String::from_utf8_lossy(om).into_owned(),
                        new_mode: String::from_utf8_lossy(nm).into_owned(),
                        letter: letter[0],
                    },
                );
            }
        } else {
            let mut parts = t.splitn(3, |&b| b == b'\t');
            let (Some(a), Some(r), Some(path)) = (parts.next(), parts.next(), parts.next()) else {
                continue;
            };
            let num = |b: &[u8]| std::str::from_utf8(b).ok()?.parse::<u64>().ok();
            c.numstat.insert(path.to_vec(), num(a).zip(num(r)));
        }
    }
    c
}

/// The new-file counts call (`grep -c -z`): `path NUL count LF` per file
/// with at least one text line.
pub fn parse_grep_counts(out: &[u8]) -> HashMap<Vec<u8>, u64> {
    let mut counts = HashMap::new();
    let mut rest = out;
    while let Some(nul) = rest.iter().position(|&b| b == 0) {
        let path = &rest[..nul];
        rest = &rest[nul + 1..];
        let Some(lf) = rest.iter().position(|&b| b == b'\n') else {
            break;
        };
        if let Some(n) = std::str::from_utf8(&rest[..lf])
            .ok()
            .and_then(|s| s.parse().ok())
        {
            counts.insert(path.to_vec(), n);
        }
        rest = &rest[lf + 1..];
    }
    counts
}

/// One record of `status --porcelain=v1 -z --no-renames`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StatusEntry {
    /// The two status letters: index, then work tree.
    pub xy: [u8; 2],
    pub path: Path,
}

/// The records of `status --porcelain=v1 -z --no-renames`, in git's order.
/// A record cut off at the end (output truncated) is dropped.
pub fn parse_status(out: &[u8]) -> Vec<StatusEntry> {
    let mut entries = Vec::new();
    let mut rest = out;
    while let Some(end) = rest.iter().position(|&b| b == 0) {
        let rec = &rest[..end];
        rest = &rest[end + 1..];
        if rec.len() > 3 && rec[2] == b' ' {
            entries.push(StatusEntry {
                xy: [rec[0], rec[1]],
                path: Path::from_git(&rec[3..]),
            });
        }
    }
    entries
}
