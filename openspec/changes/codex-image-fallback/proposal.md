# Codex image fallback

Owner decision: pair equal, nonzero numbers of viewed images and copies by
position. View completion order pairs with output image-part order, ignoring
text parts. Validate every copy before pairing any view of the call.

## Why
A deleted viewed image can still have a copy in its Codex session log.
The first build assumed related item/call ids, adjacent records and image/text
parts; the supplied structure report disproves those assumptions.

## What Changes
- Retain payload call and turn identity using structural JSON parsing, omitting
  custom tool inputs from retained chunks/history. The bounded shell response
  carries scrubbed inputs; byte cost is recorded in tasks.md. A fixed anchored
  prefix fast path drops known irrelevant canonical records and keeps only the
  last canonical token count; deviations still reach structural parsing.
- Associate ImageView events with the sole call open at the event, across
  intervening events; item ids are unrelated to call ids.
- Recover equal, nonzero numbers of viewed images and input_image parts in
  that call's first output by position, with matching nonempty turn ids.
  All other refusals remain; an invalid copy refuses the whole call.
- Reject raw references; validate the selected fetched image part; retain
  skipped-record barriers during incremental parsing and history reparsing.
- Recheck files before serving cached copies, keeping the two sources apart.
- Provide a counts-only example for the coordinator's later real-log check.

## Impact
- Affected spec: chat-view.
- Affected code: kudde-chat parsing, tail, image retrieval and example;
  kudde-mobile chat cache and tests.
- No Kotlin, UniFFI or dependency changes.
