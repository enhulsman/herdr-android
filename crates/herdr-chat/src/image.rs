//! Fetching one image on demand, so the tail stays small. Claude Code keeps
//! images as base64 inside its log; the tail script empties them and leaves
//! `"@<line>:<k>"`, and [`LOG_SCRIPT`] prints that one field. Codex records an
//! image it viewed as a file path; [`FILE_SCRIPT`] prints the file.
//! Both run through `machine.exec` (POSIX sh, tail, head, awk, wc, cat), whose
//! output is capped at 8 MB.

/// `sh -c LOG_SCRIPT sh <log> <line> <k>`: the base64 text of the `k`th base64
/// field on the line starting at byte `line`; nothing when it is not there.
pub const LOG_SCRIPT: &str = r#"f=$1; o=$2; want=$3
[ -r "$f" ] || exit 0
tail -c +$((o + 1)) "$f" | head -n 1 | LC_ALL=C awk -v want="$want" '
{
  line = $0; k = 0
  while (match(line, /"(data|base64)":"[A-Za-z0-9+\/=]+"/)) {
    if (k == want) {
      m = substr(line, RSTART, RLENGTH); sub(/^"[a-z0-9]+":"/, "", m); sub(/"$/, "", m)
      printf "%s", m; exit
    }
    k++; line = substr(line, RSTART + RLENGTH)
  }
}'
"#;

/// The largest file [`FILE_SCRIPT`] prints (`machine.exec` keeps 8 MB).
pub const FILE_MAX: u64 = 8_000_000;

/// `sh -c FILE_SCRIPT sh <path>`: the file's bytes; nothing when it is gone,
/// not a plain file, or over [`FILE_MAX`].
pub const FILE_SCRIPT: &str = r#"f=$1
[ -f "$f" ] && [ -r "$f" ] || exit 0
s=$(wc -c < "$f" | tr -d ' ')
[ "$s" -le 8000000 ] || exit 0
cat "$f"
"#;

/// The image bytes from [`LOG_SCRIPT`]'s output; `None` when it found nothing.
pub fn decode(out: &[u8]) -> Option<Vec<u8>> {
    use base64::Engine;
    let text = std::str::from_utf8(out).ok()?.trim();
    if text.is_empty() {
        return None;
    }
    base64::engine::general_purpose::STANDARD.decode(text).ok()
}
