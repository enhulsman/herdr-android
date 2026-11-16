//! Reading a log's new lines by byte offset. `file.read` counts lines, caps a
//! read at 8 MB and refuses files over 64 MB; most of a big log is request
//! records and base64 images the chat never shows. So the phone runs
//! [`SCRIPT`] through `machine.exec`: POSIX sh, tail, head, wc, od and awk only.
//!
//! `sh -c SCRIPT sh <path> <start> [<end>]`: start is a byte position (a
//! previous [`Chunk::next`] to read on, or an earlier one to read back), or
//! `-N` for "the last N bytes" on the first open; end defaults to the file's
//! size. A read spans at most [`READ_MAX`] bytes, and prints at most 8 MB
//! (the offsets make short records longer; the read stops early). A first line cut by start
//! (the byte before it is not a newline) is left out. Prints `size S`, each
//! kept line as `<its byte offset> <line>`, then `skip <offset> <length>` when
//! a record was skipped, `from F` (where the first whole line read starts)
//! and `next N` (the byte after the last whole line read). A partial last
//! line (the agent mid-write) waits.
//!
//! A base64 `data` or `base64` field over 300 characters (an image) is
//! replaced by `"@<line>:<k>"`: the byte offset of its line and its index among
//! that line's base64 fields, which [`crate::image::LOG_SCRIPT`] fetches.
//!
//! Of Codex's `token_count` records only the last of a read is kept (printed
//! after the other lines, with its own offset): the gauge needs no more.
//!
//! A whole record longer than [`READ_MAX`] can never fit a read: it is
//! skipped by its offset and length (no `skip` line for a record the chat
//! never shows, such as a request record), and reading goes on after it,
//! also when it starts after lines this read kept. A cut
//! first line that does not fit is passed over without a `skip` line, as it
//! is not a record of its own.

/// The most a read spans: 7.5 MB, under `machine.exec`'s output limit.
pub const READ_MAX: u64 = 7_864_320;

pub const SCRIPT: &str = r#"f=$1; o=$2; e=$3; max=7864320
[ -r "$f" ] || { echo missing; exit 0; }
s=$(wc -c < "$f" | tr -d ' ')
if [ "$o" -lt 0 ]; then o=$((s + o)); [ "$o" -lt 0 ] && o=0; fi
[ -n "$e" ] && [ "$e" -lt "$s" ] || e=$s
[ $((e - o)) -gt "$max" ] && e=$((o + max))
skip=0
if [ "$o" -gt 0 ] && [ "$(tail -c +$o "$f" | head -c 1 | od -An -c | tr -d ' ')" != '\n' ]; then skip=1; fi
nl=0
if [ "$s" -gt 0 ] && [ "$(tail -c 1 "$f" | od -An -c | tr -d ' ')" = '\n' ]; then nl=1; fi
echo "size $s"
n=$((e - o))
if [ "$n" -le 0 ]; then echo "from $o"; echo "next $o"; exit 0; fi
HC_TAIL_F=$f; export HC_TAIL_F
tail -c +$((o + 1)) "$f" | head -c "$n" | LC_ALL=C awk -v start="$o" -v skip="$skip" -v total="$n" -v size="$s" -v nl="$nl" -v max="$max" -v omax=8000000 '
function hidden(h,   h1) {
  if (index(h, "{\"type\":\"api-request") == 1) return 1
  h1 = substr(h, 1, 100)
  if (index(h1, "\"type\":\"response_item\"") || index(h1, "\"type\":\"world_state\"") || index(h1, "\"type\":\"token_usage_record\"")) return 1
  return index(h, "\"type\":\"event_msg\"") && index(h, "\"payload\":{\"type\":\"token_count\"")
}
BEGIN { from = -1 }
{
  len = length($0) + 1
  at = start + used
  if (used + len > total) { full = 1; cut = (skip && NR == 1); hd = substr($0, 1, 200); exit }
  used += len
  if (skip && NR == 1) next
  if (from < 0) from = at
  if (index($0, "{\"type\":\"api-request") == 1) next
  head = substr($0, 1, 100)
  if (index(head, "\"type\":\"response_item\"") || index(head, "\"type\":\"world_state\"") || index(head, "\"type\":\"token_usage_record\"")) next
  head = substr($0, 1, 200)
  if (index(head, "\"type\":\"event_msg\"") && index(head, "\"payload\":{\"type\":\"token_count\"")) {
    if (outb + length($0) + 22 > omax) { used -= len; exit }
    tc = at " " $0; next
  }
  line = $0; out = ""; k = 0
  while (match(line, /"(data|base64)":"[A-Za-z0-9+\/=]+"/)) {
    key = substr(line, RSTART + 1, 4) == "data" ? "data" : "base64"
    if (RLENGTH > 300) out = out substr(line, 1, RSTART - 1) "\"" key "\":\"@" at ":" k "\""
    else out = out substr(line, 1, RSTART + RLENGTH - 1)
    line = substr(line, RSTART + RLENGTH); k++
  }
  p = at " " out line
  # What is printed stays under what exec keeps: offsets make it longer than what was read.
  if (outb + length(p) + 1 + length(tc) > omax) {
    if (outb == 0) { print "skip " at " " len; outb = 1; exit }
    used -= len; exit
  }
  outb += length(p) + 1
  print p
}
END {
  if (full) {
    # A line that did not fit: measure it whole. A cut first line is passed
    # over; a whole record over the limit is skipped (with a note unless it
    # is one the chat never shows), and reading goes on after it.
    cmd = "tail -c +" (at + 1) " \"$HC_TAIL_F\" | head -n 1 | wc -c"
    cmd | getline whole; close(cmd); whole += 0
    done = (at + whole < size) || (at + whole == size && nl)
    if (done && cut) used += whole
    else if (done && whole > max) {
      if (!hidden(hd)) print "skip " at " " whole
      if (from < 0) from = at
      used += whole
    }
  }
  if (tc != "") print tc
  if (from < 0) from = start + used
  print "from " from; print "next " (start + used)
}'
"#;

#[derive(Clone, Debug, PartialEq)]
pub struct Chunk {
    /// The kept lines, each ending in a newline, without their offsets.
    pub text: String,
    /// Where each line of [`Chunk::text`] starts in the file, in its order.
    pub offsets: Vec<u64>,
    /// A record too large to read: its offset and length.
    pub skipped: Option<(u64, u64)>,
    /// Where the first whole line read starts: the end bound for reading back.
    pub from: u64,
    pub next: u64,
    pub size: u64,
}

pub fn parse_chunk(out: &str) -> Option<Chunk> {
    let mut lines = out.lines();
    let size = lines.next()?.strip_prefix("size ")?.trim().parse().ok()?;
    let (mut text, mut offsets, mut skipped, mut from, mut next) = (String::new(), Vec::new(), None, None, None);
    for l in lines {
        if let Some(r) = l.strip_prefix("from ") {
            from = Some(r.trim().parse().ok()?);
        } else if let Some(r) = l.strip_prefix("next ") {
            next = Some(r.trim().parse().ok()?);
        } else if let Some(r) = l.strip_prefix("skip ") {
            let (at, len) = r.trim().split_once(' ')?;
            skipped = Some((at.parse().ok()?, len.parse().ok()?));
        } else {
            let (at, line) = l.split_once(' ')?;
            offsets.push(at.parse().ok()?);
            text.push_str(line);
            text.push('\n');
        }
    }
    Some(Chunk { text, offsets, skipped, from: from?, next: next?, size })
}
