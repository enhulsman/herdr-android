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
beside the terminal) and `events.subscribe`. The proposal was reviewed in
five rounds before Ezra's decisions; the fix plan is kept locally
(`local/spec-research/chat-view-scrutiny-fix-plan.md`, not tracked).

## Goals / Non-Goals

**Goals:** the spec's requirements, built on the spike's findings, with the
reviews' leftovers fixed: a stable row identity, a send target that cannot
change under a send, a guard that keeps running and catches a format change
after messages were shown, oversized records skipped, a pending message
whose state follows the agent.

**Non-Goals:** plan limits in the gauge, verb themes, the terminal's rows
while a command waits, switching sessions, `/` completion, a start hook on
the desk, the Codex image fallback from the log's copy (proposal, decisions
2, 3 and 7); streaming answers word by word (the logs hold finished blocks
only); desks other than Linux.

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
read (for D8). A read spans at most 7.5 MB; a single record over that is
skipped by its offset and reported, so the live tail goes on (spike: it
stopped for good).

### D3. Rows keep the key they were first seen with
Every row records the offsets of all its source lines. Its key (first line
offset, index within the line) is set when the row is first seen and never
derived again. A read-back parses all held text again in one parser, so
tool results still meet their calls; afterwards each new row is matched to
the held rows by those offsets: a new row containing a held key's line takes
that key, the first if several; one containing none keys itself. *Over*
positional keys minus rows read back, or keys derived per parse: both
review cases (a folded row gaining older calls, a queued copy meeting its
user record) shifted them.

### D4. The send target is fixed at the first check
Pressing send records (process id, process start, session id). The check
before photo uploads and the one right before typing compare against it.
`terminal.send` gets `if` with the exact status seen (idle or working); for
done herdr-core has no guard, a known small window. Missed is never
sendable. A message while working is sent like any other (decision 1).

### D5. Polling follows the terminal's events
With the chat on screen: each `status`, `turn` or `title` event triggers a
read; between them, 0.5 s while working or a message waits, 5 s otherwise.
The lookup runs when the foreground process changes, every 10 s, and on
every poll for 5 s after a `/` command. With the terminal view showing:
one lookup and one recent-window read decide the switch, repeated on each
event while the result is Empty or Missed with an agent in front (one
`machine.exec` per event; an ambiguous Codex match pays it for good,
measured in task 5.6). An Empty session whose id was misread stays Empty
until its first real turn ends; meanwhile sends still reach the right
terminal, so the harm is an empty chat for a while.

### D6. The health guard has two kinds of rule
The whole-log rules (spec) cannot see a message record renamed after
messages were shown: old lines dominate and unknown record types are
ignored. The per-turn rule covers it: a turn that ran while the chat was
open and brought no new row of any kind within 5 s of its end is Broken;
turns started by `!` or `/` from the chat are skipped. Each Broken clears by
its own rule: whole-log Broken when those rules read Ok; per-turn Broken
only on a new row the agent produced, or a session change, so the person's
own message never brings the box back into a log that cannot be read.

### D7. Codex commands are placed by turn
Codex items carry the turn id; a `CommandExecution` written after its turn's
`task_complete` is inserted before that turn's final `AgentMessage`. A
`user_shell` command becomes a person's row with its output.

### D8. The gauge reads what the log already holds
Codex: the last `token_count`'s `last_token_usage.input_tokens` against
`model_context_window` (desk check: 22k of 475k in a November rollout).
Codex's own interface reckons its percentage with a fixed allowance for
its instructions, so the two may differ by a few percent; the chat shows
the plain ratio. Claude Code: input plus cache tokens of the last assistant
record; the log names the model but not its window (a 1M window depends on
the desk's settings), so the gauge shows tokens. Plan limits are out
(Non-Goals): Claude Code's log has none.

### D9. Fading in only what arrives live
A row first seen after the first read is marked live; only live rows fade in
(alpha and a short reveal, at most 600 ms). Rows from the first open or read
back show at once.

### D10. A pending message's state comes from the log and the screen
The terminal stays attached under the chat, so the app has its screen text.
A pending message reads "Queued" while the agent works and that screen
shows the message's first line in the agent's input area or queue (where
each agent shows a queued message is looked up on the desk first, task
2.8); otherwise "Sending…". The 30 s expiry starts at sending for a message
not shown as queued, and when the agent stops working for one that was.
Claude Code's queued command record ends the wait like a user record.

### D11. Tests where they hold
Pure functions (parsing, matching, lookup results, health, row keys, send
gating, pending state and expiry, waiting line, verbs, stick rule, code
boxes) get unit tests; screens get Roborazzi goldens in both themes;
scrolling and touch behaviour is checked on the phone. Robolectric Compose
interaction tests are not used: two in the spike failed in about half of
full-suite runs depending on test order.

## Risks / Trade-offs

- [Private log formats that change weekly] → the guard falls back to
  read-only with the terminal button; `examples/check.rs` runs against the
  desk's real logs at each test round and its result goes in the tasks.
- [0.5 s polling and event lookups: desk and phone cost unmeasured] →
  measure on the test phone (task 5.6); lower the rate while the screen is
  idle if large.
- [Codex shows a command only once it ends] → the working line still shows;
  the spec states the exception.
- [A dialog opened at the desk, or after Dismiss, while the status reads
  done] → a send may land in it; herdr-core has no guard for done. Stated
  as a known limit in the spec.
- [Exit loses the match; app restart after exit has no chat] → accepted for
  v1 (decision 3); a start hook is a 1.x issue.
- [Queued detection reads the screen] → a long or wrapped message may not
  match its first line on screen; it then reads "Sending…" and expires 30 s
  after sending, offering the terminal: the safe side.
- [Live terminal under the chat] → the chat takes every touch and hides the
  terminal from accessibility; the terminal keeps running so the switch back
  is instant.

## Migration Plan

None: a new view. Rollback is a release without the switch.

## Open Questions

- Claude Code's context window per model (200k or 1M) is not in the log; the
  status line input has it. Settled with plan limits in 1.x.
- Whether Codex slash commands open dialogs and what they write (task 5.5).
- `/resume` inside either agent's own interface, and an image a person
  attached in Codex, are untested; task 5.5 tries them.
