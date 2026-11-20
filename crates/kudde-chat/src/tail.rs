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
//! Codex image output records are kept, with data-URL `image_url` fields
//! replaced by the same references (including small copies), so call-local
//! pairing never transfers the image bytes in a tail response.
//!
//! [`parse_chunk`] classifies JSON structurally, reduces Codex call records to
//! payload identities, drops irrelevant response records and retains only the
//! last token count. The shell first trims only exact, anchored known Codex
//! prefixes and keeps the last matching token count. Deviations reach Rust.
//! The bounded wire response includes scrubbed call inputs;
//! those inputs are omitted before history or the chat parser sees the chunk.
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
# Recognize only the canonical key order at byte zero, within 200 bytes.
# Any deviation costs bytes instead of losing a potentially relevant record.
function codex(h,   p) {
  if (!match(h, /^\{"timestamp":"[^"\\]*","ordinal":(0|[1-9][0-9]*),"type":/)) return 0
  p=substr(h,RLENGTH+1)
  if (p ~ /^"(world_state|token_usage_record)","payload":\{/) return 1
  if (p ~ /^"response_item","payload":\{"type":"(message|reasoning|additional_tools|function_call|function_call_output|configuration_update)"[,}]/) return 1
  if (p ~ /^"event_msg","payload":\{"type":"token_count"[,}]/) return 2
  return 0
}
function hidden(h) {
  return index(h, "{\"type\":\"api-request") == 1 || codex(h) == 1
}
# Decode ASCII escapes in keys before deciding whether a value is an image URL.
function keytext(s,   out,i,c,h,a,b) {
  out = ""
  for (i=1; i<=length(s); i++) {
    c=substr(s,i,1)
    if (c == "\\" && substr(s,i+1,3) == "u00") {
      h=tolower(substr(s,i+4,2)); a=index("0123456789abcdef",substr(h,1,1))-1; b=index("0123456789abcdef",substr(h,2,1))-1
      if (a>=0 && b>=0) { out=out sprintf("%c",a*16+b); i+=5; continue }
    }
    if (c == "\\") { i++; c=substr(s,i,1) }
    out=out c
  }
  return out
}
function scrub(s,   i,j,k,c,value,key,out,last) {
  last=1; key=""; out=""
  for (i=1; i<=length(s); i++) {
    c=substr(s,i,1)
    if (c == "{" || c == "[") key=""
    if (c != "\"") continue
    k=i
    for (i++; i<=length(s); i++) {
      c=substr(s,i,1)
      if (c == "\\") i++
      else if (c == "\"") break
    }
    value=substr(s,k+1,i-k-1)
    j=i+1; while (substr(s,j,1) ~ /[ \t\r\n]/) j++
    if (substr(s,j,1) == ":") key=keytext(value)
    else {
      if (key == "image_url" && value !~ /^data:image\/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+\/=]+$/) {
        out=out substr(s,last,k-last) "\"\""; last=i+1
      }
      key=""
    }
  }
  return out substr(s,last)
}
BEGIN { from = -1 }
{
  len = length($0) + 1
  at = start + used
  if (used + len > total) { full = 1; cut = (skip && NR == 1); hd = substr($0, 1, 200); exit }
  used += len
  if (skip && NR == 1) next
  if (from < 0) from = at
  hd = substr($0, 1, 200)
  if (hidden(hd)) next
  kind = codex(hd)
  # Raw log references have no provenance, including escaped keys and values.
  line = (index($0, "image_url") || index($0, "\\u")) ? scrub($0) : $0
  out = ""; k = 0
  while (match(line, /"(data|base64)":"[A-Za-z0-9+\/=]+"|"image_url":"data:image\/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+\/=]+"/)) {
    key = substr(line, RSTART + 1, 9) == "image_url" ? "image_url" : (substr(line, RSTART + 1, 4) == "data" ? "data" : "base64")
    if (RLENGTH > 300 || key == "image_url") out = out substr(line, 1, RSTART - 1) "\"" key "\":\"@" at ":" k "\""
    else out = out substr(line, 1, RSTART + RLENGTH - 1)
    line = substr(line, RSTART + RLENGTH); k++
  }
  p = at " " out line
  # Reserve the pending count inside the same output budget.
  # What is printed stays under what exec keeps: offsets make it longer than what was read.
  if (outb - (kind == 2 ? tcb : 0) + length(p) + 1 > omax) {
    if (outb == 0) { print "skip " at " " len; outb = 1; exit }
    used -= len; exit
  }
  if (kind == 2) outb -= tcb
  outb += length(p) + 1
  if (kind == 2) { tc = p; tcb = length(p) + 1 }
  else print p
}
END {
  if (tc != "") print tc
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
  if (from < 0) from = start + used
  print "from " from; print "next " (start + used)
}'
"#;

#[derive(Clone, Debug, PartialEq)]
pub struct Chunk {
    /// The kept lines after structural filtering and call reduction, without offsets.
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
    let (mut text, mut offsets, mut skipped, mut from, mut next) =
        (String::new(), Vec::new(), None, None, None);
    let mut token_count = None;
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
            let at = at.parse().ok()?;
            // Classify complete JSON by paths, never by text inside tool input.
            // The remote shell trims only fixed canonical prefixes; deviations
            // still need structural classification here.
            let record = serde_json::from_str::<serde_json::Value>(line).ok();
            let mut header = None;
            if let Some(v) = &record {
                let kind = v["type"].as_str().unwrap_or("");
                let p = &v["payload"];
                match kind {
                    "response_item" if p["type"] == "custom_tool_call" => {
                        header = Some(serde_json::json!({
                            "type": "response_item",
                            "payload": {
                                "type": "custom_tool_call",
                                "call_id": p["call_id"],
                                "internal_chat_message_metadata_passthrough": {
                                    "turn_id": p["internal_chat_message_metadata_passthrough"]["turn_id"]
                                }
                            }
                        }).to_string());
                    }
                    "response_item" if p["type"] != "custom_tool_call_output" => continue,
                    "world_state" | "token_usage_record" => continue,
                    "event_msg" if p["type"] == "token_count" => {
                        // The shell emits its buffered canonical count last;
                        // a later noncanonical count may already have arrived.
                        if token_count.is_none_or(|(previous, _)| at > previous) {
                            token_count = Some((at, line));
                        }
                        continue;
                    }
                    _ => {}
                }
            }
            offsets.push(at);
            text.push_str(header.as_deref().unwrap_or(line));
            text.push('\n');
        }
    }
    if let Some((at, line)) = token_count {
        offsets.push(at);
        text.push_str(line);
        text.push('\n');
    }
    Some(Chunk {
        text,
        offsets,
        skipped,
        from: from?,
        next: next?,
        size,
    })
}

/// Retain uncertainty as a parser record, including when held history is reparsed.
/// Kept separate from the filtered chunk so raw reads need no synthetic rows.
pub fn with_barrier(mut chunk: Chunk) -> Chunk {
    if let Some((at, _)) = chunk.skipped {
        chunk.offsets.push(at);
        chunk
            .text
            .push_str("{\"type\":\"kudde_skipped\",\"payload\":{}}\n");
    }
    chunk
}
