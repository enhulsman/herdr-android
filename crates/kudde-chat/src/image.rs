//! Fetching one image on demand, so the tail stays small. Claude Code keeps
//! images as base64 inside its log; the tail script empties them and leaves
//! `"@<line>:<k>"`, and [`LOG_SCRIPT`] prints that one field. Codex records an
//! image it viewed as a file path; [`FILE_SCRIPT`] prints the file. A paired
//! data-URL copy uses [`LOG_SCRIPT`] if that file cannot be read. Log reads
//! stay within [`crate::tail::READ_MAX`].
//! Both run through `machine.exec` (POSIX sh, tail, head, awk, wc, cat), whose
//! output is capped at 8 MB.

/// `sh -c LOG_SCRIPT sh <log> <line> <k>`: the base64 text of the `k`th base64
/// field on the line starting at byte `line`; nothing when it is not there.
pub const LOG_SCRIPT: &str = r#"f=$1; o=$2; want=$3
[ -r "$f" ] || exit 0
tail -c +$((o + 1)) "$f" | head -c 7864320 | head -n 1 | LC_ALL=C awk -v want="$want" '
{
  # Tokenize strings and containers so a selected field cannot borrow the
  # type of another part, or a type-looking string inside input_text.
  k = 0; depth = 0; key = ""; codex = index($0, "\"custom_tool_call_output\"")
  for (i = 1; i <= length($0); i++) {
    c = substr($0, i, 1)
    if (c == "{" || c == "[") { depth++; typ[depth] = ""; chosen[depth] = ""; selected[depth] = ""; key = "" }
    else if (c == "}" || c == "]") {
      if (chosen[depth] != "" && (!codex || (selected[depth] == "image_url" && typ[depth] == "input_image"))) { printf "%s", chosen[depth]; exit }
      depth--; key = ""
    } else if (c == "\"") {
      value = ""
      for (i++; i <= length($0); i++) {
        c = substr($0, i, 1)
        if (c == "\\") { value = value c substr($0, i+1, 1); i++ }
        else if (c == "\"") break
        else value = value c
      }
      j = i + 1; while (substr($0, j, 1) ~ /[ \t\r\n]/) j++
      if (substr($0, j, 1) == ":") { key = value; i = j }
      else {
        if (key == "type") typ[depth] = value
        data = value
        eligible = (key == "data" || key == "base64") && value ~ /^[A-Za-z0-9+\/=]+$/
        if (key == "image_url" && sub(/^data:image\/[a-zA-Z0-9.+-]+;base64,/, "", data) && data ~ /^[A-Za-z0-9+\/=]+$/) eligible = 1
        if (eligible) { if (k == want) { chosen[depth] = data; selected[depth] = key }; k++ }
        key = ""
      }
    }
  }
}'
"#;

/// The largest file [`FILE_SCRIPT`] prints (`machine.exec` keeps 8 MB).
pub const FILE_MAX: u64 = 8_000_000;

/// `sh -c FILE_SCRIPT sh <path> <held>`: the file's time (modification
/// second and size) on a line, then its bytes unless that time is `held`;
/// nothing when it is gone, not a plain file, or over [`FILE_MAX`]. Codex
/// reuses a screenshot's path, so an image is held by path and time.
pub const FILE_SCRIPT: &str = r#"f=$1; held=$2
[ -f "$f" ] && [ -r "$f" ] || exit 0
s=$(wc -c < "$f" | tr -d ' ')
[ "$s" -le 8000000 ] || exit 0
t="$(stat -c %Y "$f") $s"
printf '%s\n' "$t"
[ "$t" = "$held" ] || cat "$f"
"#;

/// [`FILE_SCRIPT`]'s output: the file's time, and its bytes when they were
/// sent; `None` when the file was not there.
pub fn file_reply(out: &[u8]) -> Option<(String, Option<Vec<u8>>)> {
    let nl = out.iter().position(|&b| b == b'\n')?;
    let tag = std::str::from_utf8(&out[..nl]).ok()?.to_owned();
    let rest = &out[nl + 1..];
    Some((tag, (!rest.is_empty()).then(|| rest.to_vec())))
}

/// The image bytes from [`LOG_SCRIPT`]'s output; `None` when it found nothing.
pub fn decode(out: &[u8]) -> Option<Vec<u8>> {
    use base64::Engine;
    let text = std::str::from_utf8(out).ok()?.trim();
    if text.is_empty() || text.len() as u64 > FILE_MAX.div_ceil(3) * 4 {
        return None;
    }
    base64::engine::general_purpose::STANDARD
        .decode(text)
        .ok()
        .filter(|b| b.len() as u64 <= FILE_MAX)
}
