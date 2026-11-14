## 1. Branch and carry-over

- [ ] 1.1 Branch `feat/chat-view` from `main`; bring `research/chat-view-spike-2026-11.md` and this change
- [ ] 1.2 Carry `crates/herdr-chat` over from `spike/chat-view` with its tests and fixtures; all pass unchanged

## 2. Tests first (Rust)

- [ ] 2.1 Tail script: prints each line's byte offset; keeps the last Codex `token_count` of a read; skips a record over 7.5 MB by offset and reports it, and the next read goes on after it
- [ ] 2.2 Row identity: a folded tool row gaining older calls after a read-back keeps its key; a queued copy and its later user record give one row with the earlier key; read back in pieces gives the same keys as one read
- [ ] 2.3 Health: Broken and Degraded per rule, checked on every read; a recent window of 40 bookkeeping lines is not Broken before reading back; a message record renamed after messages were shown turns Broken
- [ ] 2.4 Codex order: a command written after `task_complete` sits before that turn's answer; a `user_shell` command is a person's row with its output
- [ ] 2.5 Gauge numbers: Codex percent from `token_count`; Claude Code tokens from the last assistant record; none when neither exists
- [ ] 2.6 Matching cases from the spec: two agents in one folder, reused pid, Codex `/new`, same-second rollouts, `node` launch
- [ ] 2.7 Send target: a changed process, start time or session between the first check and typing refuses; an ambiguous match is never sendable; the `if` guard is passed

## 3. Build (Rust)

- [ ] 3.1 Tail script changes (2.1) and their desk test in `tests/desk.rs`
- [ ] 3.2 Parser: row keys, health on every read, Codex turn order, gauge numbers
- [ ] 3.3 `Chat` in `herdr-mobile` from `ChatSpike`: polling by events (D5), the send target (D4), lookup rules, oversized-record notes, failed-read state
- [ ] 3.4 Real-log check: `examples/check.rs` over the desk's Claude Code and Codex logs, all Ok; record the counts here

## 4. Android (tests first in each)

- [ ] 4.1 Pure functions: pending expiry at 30 s, steer hint while working, `busy`/`shimmers`/verbs, `nextStick`, code boxes, image swipe order within a row, live-row fade marking
- [ ] 4.2 Chat screen in `ui/chat` from the spike's parts, keyed by row identity; goldens in paper and graphite for: conversation, pending, not-arrived, waiting line, working line, tool sheet and detail, viewer, degraded note, exited, gauge
- [ ] 4.3 Switch in `TerminalRoute`: shown per the spec, chosen view remembered, the hand-opened "Looking for the conversation…" and "No conversation found" states, Broken note
- [ ] 4.4 Sending: refusal texts with terminal buttons, draft kept; photo attach with EXIF upright, scaling, JPEG re-encode
- [ ] 4.5 Viewer with swipe across a message's images; Codex images cached by path and file time
- [ ] 4.6 Fade-in for live rows; context gauge in the title bar with its tap detail
- [ ] 4.7 Remove the debug-only spike entry and names

## 5. Review and phone check

- [ ] 5.1 `openspec validate chat-view --strict`
- [ ] 5.2 Codex adversarial review of the branch (send gating, matching, reading bounds)
- [ ] 5.3 Test phone, debug build: every spec scenario that needs a device (two agents in a folder, `/clear`, exit, 84 MB log, blocked send, photo send, swipe, keyboard in a working session, fade-in)
- [ ] 5.4 Owner's daily phone (install only, Ezra drives): a working session with the keyboard open; a day of real use
- [ ] 5.5 Untested cases from the spike: `/resume` inside each agent, a person-attached image in Codex, a mirrored EXIF photo
- [ ] 5.6 Desk and battery cost of 0.5 s polling: an hour of a working agent with the chat open on the test phone; record it here

## 6. Records

- [ ] 6.1 `decisions.md`: the proposal's decisions as settled; README feature line; 1.x issues for plan limits, verb themes, terminal rows while waiting, session switching, `/` completion, the start hook
