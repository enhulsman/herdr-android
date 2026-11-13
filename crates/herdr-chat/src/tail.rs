//! Reading a log's new lines by byte offset. `file.read` counts lines, caps a
//! read at 8 MB and refuses files over 64 MB; most of a big log is request
//! records and base64 images the chat never shows. So the phone runs
//! [`SCRIPT`] through `machine.exec`: POSIX sh, tail, head and awk only.
//!
//! `sh -c SCRIPT sh <path> <offset>`: offset is a byte position from a previous
//! [`Chunk::next`], or `-N` for "the last N bytes, from a line start" on the
//! first open. Prints `size S`, the kept lines, then `next N`: the byte after
//! the last whole line read. A partial last line (the agent mid-write) waits.

pub const SCRIPT: &str = r#"f=$1; o=$2
[ -r "$f" ] || { echo missing; exit 0; }
s=$(wc -c < "$f" | tr -d ' ')
skip=0
if [ "$o" -lt 0 ]; then o=$((s + o)); skip=1; [ "$o" -le 0 ] && { o=0; skip=0; }; fi
echo "size $s"
n=$((s - o))
if [ "$n" -le 0 ]; then echo "next $o"; exit 0; fi
tail -c +$((o + 1)) "$f" | head -c "$n" | LC_ALL=C awk -v start="$o" -v skip="$skip" -v total="$n" '
{
  len = length($0) + 1
  if (used + len > total) exit
  used += len
  if (skip && NR == 1) next
  if (index($0, "{\"type\":\"api-request") == 1) next
  head = substr($0, 1, 100)
  if (index(head, "\"type\":\"response_item\"") || index(head, "\"type\":\"world_state\"") || index(head, "\"type\":\"token_usage_record\"")) next
  line = $0; out = ""
  while (match(line, /"data":"[A-Za-z0-9+\/=]+"/)) {
    if (RLENGTH > 300) out = out substr(line, 1, RSTART - 1) "\"data\":\"\""
    else out = out substr(line, 1, RSTART + RLENGTH - 1)
    line = substr(line, RSTART + RLENGTH)
  }
  print out line
}
END { print "next " (start + used) }'
"#;

#[derive(Clone, Debug, PartialEq)]
pub struct Chunk {
    pub text: String,
    pub next: u64,
    pub size: u64,
}

pub fn parse_chunk(out: &str) -> Option<Chunk> {
    let rest = out.strip_prefix("size ")?;
    let (size, rest) = rest.split_once('\n')?;
    let body = rest.strip_suffix('\n').unwrap_or(rest);
    let (text, last) = match body.rsplit_once('\n') {
        Some((t, l)) => (format!("{t}\n"), l),
        None => (String::new(), body),
    };
    let next = last.strip_prefix("next ")?.trim().parse().ok()?;
    Some(Chunk {
        text,
        next,
        size: size.trim().parse().ok()?,
    })
}
