# Design: the chat view

## Context

The spike (`research/chat-view-spike-2026-11.md`, branch `spike/chat-view`,
never merged) built the whole path once: the `herdr-chat` crate (matching,
parsers, desk scripts, 37 tests on synthetic fixtures), a `ChatSpike` object
in the hub, and a debug-only Compose screen tried on the phone over four
rounds. Two independent reviews judged it sufficient and left a list for the
spec (the note's "Reviews" section). herdr-core 0.1.20 offers everything
needed: `terminal.get` (foreground process), `machine.exec` (scripts, 8 MB
output), `terminal.send` (with an `if` status guard), `terminal.put` (files
beside the terminal) and `events.subscribe`.

## Goals / Non-Goals

**Goals:** the spec's requirements, built on the spike's findings, with the
reviews' leftovers fixed: a stable row identity, a send target that cannot
change under a send, a guard that keeps running, oversized records skipped,
an expiry for a pending message.

**Non-Goals:** plan limits in the gauge, verb themes, the terminal's rows
while a command waits, switching sessions, `/` completion, a start hook on
the desk (proposal, decision 2 and 3); streaming answers word by word (the
logs hold finished blocks only); desks other than Linux.

## Decisions

### D1. Build on the spike, on a fresh branch
The change is built on `feat/chat-view` from `main`. `herdr-chat` is carried
over with its tests and fixtures, then changed test-first; `ChatSpike`
becomes `Chat` in `herdr-mobile`; the screen is rebuilt in `ui/chat` from the
spike's parts (Markdown, tool sheet, working line, viewer), each with its
tests and goldens. The debug-only entry and the spike names go. *Over*
merging the spike branch: its history holds probes and dropped tests, and
the row identity changes the screen's core.

### D2. Read with `machine.exec` and the tail script
`file.read` counts lines, stops at 8 MB and refuses files over 64 MB; seven
desk logs are larger. The tail script reads byte ranges, prints whole lines
only, drops records the chat never shows and replaces base64 over 300
characters with `@<line offset>:<index>`. It now also prints each line's
byte offset (for D3) and keeps the last Codex `token_count` record of each
read (for the gauge). A read spans at most 7.5 MB; a single record over that
is skipped by its offset and reported, so the live tail goes on (spike: it
stopped for good).

### D3. Rows keep the identity of the line that starts them
A row's key is (byte offset of its first line, index within that line). The
parser keeps it when a folded tool row gains older calls at its front after
reading back (the key stays its newest line's, not its first), and when a
queued copy and the later user record are merged (the earlier one's). The
screen keys rows, thumbnails and the open detail by it. *Over* positional
keys minus rows read back: both review cases shifted them.

### D4. The send target is fixed at the first check
Pressing send records (process id, process start, session id). The check
before photo uploads and the one right before typing compare against it, and
`terminal.send` gets `if: idle|working` (its own guard; `done` has none, a
known small window). An ambiguous Codex match is never sendable.

### D5. Polling follows the terminal's events
Each `status`, `turn` or `title` event triggers a read; between them, 0.5 s
while working or a message waits, 5 s otherwise; only while the chat is on
screen. The lookup runs when the foreground process changes, every 10 s, and
on every poll for 3 s after a `/` command. With the terminal view showing,
one lookup and one recent-window read decide the switch.

### D6. The health guard runs on every read
`Parser::health` is computed on everything held after each read. The
no-message rule only counts once the read reaches the start of the log or
2 MB of history without a message, so a recent window of bookkeeping lines
reads further back first. Broken flips the view to the terminal with a note;
Degraded shows a note at the top of the chat.

### D7. Codex commands are placed by turn
Codex items carry the turn id; a `CommandExecution` written after its turn's
`task_complete` is inserted before that turn's final `AgentMessage`. A
`user_shell` command becomes a person's row with its output.

### D8. The gauge reads what the log already holds
Codex: the last `token_count`'s `last_token_usage.input_tokens` against
`model_context_window`. Claude Code: input plus cache tokens of the last
assistant record; the log names the model but not its window (a 1M window
depends on the desk's settings), so the gauge shows tokens until a window is
known. Plan limits are out (Non-Goals): Claude Code's log has none.

### D9. Fading in only what arrives live
A row first seen after the first read is marked live; only live rows fade in
(alpha and a short reveal, at most 600 ms). Rows from the first open or read
back show at once.

### D10. Tests where they hold
Pure functions (parsing, matching, health, row keys, send gating, pending
expiry, verbs, stick rule, code boxes) get unit tests; screens get
Roborazzi goldens in both themes; scrolling and touch behaviour is checked
on the phone. Robolectric Compose interaction tests are not used: two in the
spike failed in about half of full-suite runs depending on test order.

## Risks / Trade-offs

- [Private log formats that change weekly] → the guard falls back to the
  terminal; `examples/check.rs` runs against the desk's real logs at each
  test round and its result goes in the tasks.
- [0.5 s polling while working: desk and phone cost unmeasured] → measure on
  the test phone (task 5.6); lower the rate while the screen is idle if large.
- [Codex shows a command only once it ends] → the working line still shows;
  no shimmer for Codex, stated in the spec by omission.
- [Exit loses the match; app restart after exit has no chat] → accepted for
  v1 (decision 3); a start hook is a 1.x issue.
- [Steering surprises someone who expected a queue] → the hint under the
  box while working (decision 1).
- [Live terminal under the chat] → the chat takes every touch and hides the
  terminal from accessibility; the terminal keeps running so the switch back
  is instant.

## Migration Plan

None: a new view. Rollback is a release without the switch.

## Open Questions

- Claude Code's context window per model (200k or 1M) is not in the log; the
  status line input has it. Settled with plan limits in 1.x.
- `/resume` inside either agent's own interface, and an image a person
  attached in Codex, are untested; tasks 5.x try them.
