//! Reading a log's new lines by byte offset. `file.read` counts lines, caps a
//! read at 8 MB and refuses files over 64 MB; most of a big log is request
//! records and base64 images the chat never shows. So the phone runs
//! [`SCRIPT`] through `machine.exec`: POSIX sh, tail, head and awk only.
//!
//! `sh -c SCRIPT sh <path> <start> [<end>]`: start is a byte position (a
//! previous [`Chunk::next`] to read on, or an earlier one to read back), or
//! `-N` for "the last N bytes" on the first open; end defaults to the file's
//! size. A first line cut by start (the byte before it is not a newline) is
//! left out. Prints `size S`, the kept lines, `from F` (where the first whole
//! line read starts) and `next N` (the byte after the last whole line read).
//! A partial last line (the agent mid-write) waits.
//!
//! A base64 `data` or `base64` field over 300 characters (an image) is
//! replaced by `"@<line>:<k>"`: the byte offset of its line and its index among
//! that line's base64 fields, which [`crate::image::LOG_SCRIPT`] fetches.

pub const SCRIPT: &str = r#"f=$1; o=$2; e=$3
[ -r "$f" ] || { echo missing; exit 0; }
s=$(wc -c < "$f" | tr -d ' ')
if [ "$o" -lt 0 ]; then o=$((s + o)); [ "$o" -lt 0 ] && o=0; fi
[ -n "$e" ] && [ "$e" -lt "$s" ] || e=$s
skip=0
if [ "$o" -gt 0 ] && [ "$(tail -c +$o "$f" | head -c 1 | od -An -c | tr -d ' ')" != '\n' ]; then skip=1; fi
echo "size $s"
n=$((e - o))
if [ "$n" -le 0 ]; then echo "from $o"; echo "next $o"; exit 0; fi
tail -c +$((o + 1)) "$f" | head -c "$n" | LC_ALL=C awk -v start="$o" -v skip="$skip" -v total="$n" '
BEGIN { from = -1 }
{
  len = length($0) + 1
  if (used + len > total) exit
  at = start + used
  used += len
  if (skip && NR == 1) next
  if (from < 0) from = at
  if (index($0, "{\"type\":\"api-request") == 1) next
  head = substr($0, 1, 100)
  if (index(head, "\"type\":\"response_item\"") || index(head, "\"type\":\"world_state\"") || index(head, "\"type\":\"token_usage_record\"")) next
  line = $0; out = ""; k = 0
  while (match(line, /"(data|base64)":"[A-Za-z0-9+\/=]+"/)) {
    key = substr(line, RSTART + 1, 4) == "data" ? "data" : "base64"
    if (RLENGTH > 300) out = out substr(line, 1, RSTART - 1) "\"" key "\":\"@" at ":" k "\""
    else out = out substr(line, 1, RSTART + RLENGTH - 1)
    line = substr(line, RSTART + RLENGTH); k++
  }
  print out line
}
END { if (from < 0) from = start + used; print "from " from; print "next " (start + used) }'
"#;

#[derive(Clone, Debug, PartialEq)]
pub struct Chunk {
    pub text: String,
    /// Where the first whole line read starts: the end bound for reading back.
    pub from: u64,
    pub next: u64,
    pub size: u64,
}

pub fn parse_chunk(out: &str) -> Option<Chunk> {
    let rest = out.strip_prefix("size ")?;
    let (size, rest) = rest.split_once('\n')?;
    let body = rest.strip_suffix('\n').unwrap_or(rest);
    let (body, last) = body.rsplit_once('\n')?;
    let next = last.strip_prefix("next ")?.trim().parse().ok()?;
    let (text, from) = match body.rsplit_once('\n') {
        Some((t, l)) => (format!("{t}\n"), l),
        None => (String::new(), body),
    };
    let from = from.strip_prefix("from ")?.trim().parse().ok()?;
    Some(Chunk {
        text,
        from,
        next,
        size: size.trim().parse().ok()?,
    })
}
