## 1. Branch and carry-over

- [x] 1.1 Branch `feat/chat-view` from `main`; bring `research/chat-view-spike-2026-11.md` and this change
- [ ] 1.2 Carry `crates/herdr-chat` over from `spike/chat-view` with its tests and fixtures; all pass unchanged

## 2. Tests first (Rust), and desk checks they rest on

- [ ] 2.1 Tail script: prints each line's byte offset; keeps the last Codex `token_count` of a read; skips a record over 7.5 MB by offset and reports it, and the next read goes on after it
- [ ] 2.2 Row identity (D3): every row records all its source line offsets; (a) a folded tool row gaining older calls on read-back keeps its key and place; (b) a person's row on screen meeting its older queued copy on read-back: one row, the on-screen key; (c) a log read in one go and read back in pieces gives the same rows in the same order; a merged row keeps its key and both offsets
- [ ] 2.3 Health (D6): whole-log rules per the spec; a recent window of 40 bookkeeping lines is not Broken before reading back. Per-turn rule must trip: message records renamed after messages were shown; stays Broken across five later reads with no new row; a person's row leaves it Broken; an agent row clears it. Must not trip: an interrupt before any text, `/compact`, a turn of tool calls only ending in a failure, a Codex `!` command, a `/` command from the chat
- [ ] 2.4 Codex order: a command written after `task_complete` sits before that turn's answer; a `user_shell` command is a person's row with its output. Find why a Codex turn listed `sleep 20` twice under "Ran 2 commands" and test the fix
- [ ] 2.5 Gauge numbers: Codex input tokens and window from `token_count`; Claude Code input plus cache tokens from the last assistant record; none when neither exists
- [ ] 2.6 Lookup results: Found, Empty (Claude Code session file with an absent log; Codex lock newer than every rollout), Missed (unreadable id, ambiguous same-second rollouts, no session), None; Empty turning Missed when the first turn ends without a log; the matching cases from the spec (two agents in one folder, reused pid, `node` launch)
- [ ] 2.7 Send target (D4): a changed process, start time or session between the first check and typing refuses; Missed is never sendable; `if` carries the status seen for idle and working and is absent for done
- [ ] 2.8 Desk check in throwaway terminals: where Claude Code and Codex show a message typed while they work (input area, queue line), and whether Claude Code writes its queued command record when queued or when taken in; record it in the research note
- [ ] 2.9 Claude Code local command output (`/usage`'s `local_command` records) parses into a note

## 3. Build (Rust)

- [ ] 3.1 Tail script changes (2.1) and their desk test in `tests/desk.rs`
- [ ] 3.2 Parser: row keys and source offsets, both health rules with their clearing, Codex turn order, gauge numbers, local command notes
- [ ] 3.3 `Chat` in `herdr-mobile` from `ChatSpike`: lookup results, polling and lookups by events (D5), the send target (D4), oversized-record notes, failed-read state
- [ ] 3.4 Real-log check: `examples/check.rs` over the desk's Claude Code and Codex logs, all Ok; record the counts here

## 4. Android (tests first in each)

- [ ] 4.1 Pure functions: pending state (Sending…, Queued from the screen text, expiry 30 s after sending or after work stops, arrival by text or by the `!` command's shell call, also when added to the last folded row); the waiting line (3 s, Dismiss, ends on its row or a status change, refuses sends while the status is unchanged); lookup cadence (events, 10 s, 5 s after `/`, events while Empty or Missed); `busy`/`shimmers`/verbs; `nextStick`; code boxes; image swipe order within a row; live-row fade marking
- [ ] 4.2 Chat screen in `ui/chat` from the spike's parts, keyed by row identity; goldens in paper and graphite for: conversation, no messages yet, sending, queued, not arrived, waiting line, refused send, working line, tool sheet and detail, viewer, image not available, degraded note, broken read-only, exited, gauge for each agent
- [ ] 4.3 Switch in `TerminalRoute`: shown per the spec, chosen view remembered, the hand-opened "Looking for the conversation…" and "No conversation found" states
- [ ] 4.4 Sending: refusal texts with terminal buttons, draft kept; photo attach with EXIF upright, scaling, JPEG re-encode
- [ ] 4.5 Viewer with swipe across a message's images; Codex images cached by path and file time; "Image not available" placeholder
- [ ] 4.6 Fade-in for live rows; context gauge in the title bar with its tap detail
- [ ] 4.7 Remove the debug-only spike entry and names

## 5. Review and phone check

- [ ] 5.1 `openspec validate chat-view --strict`
- [ ] 5.2 Codex adversarial review of the branch (send gating, matching, reading bounds, health clearing)
- [ ] 5.3 Test phone, debug build: every spec scenario that needs a device (two agents in a folder, new session watched in the terminal, `/clear` within 5 s, exit, 84 MB log within 2 s, blocked send, `/usage` refusal and Dismiss, a message queued behind a minute-long command, photo send, swipe, keyboard in a working session, taps on the margin above the box and between rows reach nothing); tune the fade with Ezra. A missed target is recorded here and becomes a spec change
- [ ] 5.4 Owner's daily phone (install only, Ezra drives): a working session with the keyboard open; a day of real use
- [ ] 5.5 Untested cases: Codex slash commands (dialogs, status, what they write; amend the spec if it differs from Claude Code's), `/resume` inside each agent, a person-attached image in Codex, a mirrored EXIF photo
- [ ] 5.6 Desk and battery cost of 0.5 s polling and event lookups: an hour of a working agent with the chat open on the test phone, and an hour with a Missed agent in terminal view; record it here

## 6. Records

- [ ] 6.1 `decisions.md`: the proposal's decisions as settled; README feature line
- [ ] 6.2 Draft the 1.x issues verbatim (plan limits, verb themes, terminal rows while a command waits, session switching, `/` completion, the start hook, the Codex image fallback by order within a tool call); create them after Ezra's OK
