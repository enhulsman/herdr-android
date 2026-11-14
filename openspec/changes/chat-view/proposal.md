# Proposal: the chat view

## Plain summary

**Decisions** (Ezra, 2026-11-14: OK with 1-7, with 1 and 4 changed as
written here):
1. **A message sent while the agent works.** Sent as the agent's own input
   box takes it (pasted, then Enter); the app neither holds nor interrupts.
   Claude Code queues it until it can take it in; Codex takes it into the
   turn. No hint under the box; the bubble shows "Queued" while the agent
   holds it (decision 4).
2. **What is in this change.** In: everything the spike showed on the phone
   (reading, live updates, sending, `/` and `!` commands, images both ways,
   the tool sheet, the working line), plus three of your later ideas: new
   answers coming in as if typed, swiping between a message's images, and a
   context gauge in the top bar (Codex: percent and tokens, both in its log;
   Claude Code: tokens only, its log has no window size). Later, each as
   its own issue: plan limits in the gauge (5-hour, weekly, per model; Claude
   Code needs something on the desk to hand them over), verb themes,
   the terminal's bottom rows while a command waits, switching between an
   agent's sessions, and `/` command completion. *Pick: as listed*: the
   later ones need a desk-side piece, a settings screen, or a design of
   their own.
3. **After the agent exits, or its log turns unreadable.** Keep showing
   what was read, without the message box, with a button to the terminal;
   opened fresh afterwards, the terminal shows. The optional start hook
   that labels the terminal with its session is a 1.x issue: a desk
   setting, and Codex's was never tested.
4. **A sent message that does not show in the log.** It reads "Queued"
   while the agent works and the terminal shows it waiting; otherwise
   "Sending…". 30 seconds after sending (or after the agent stops working,
   when it was queued) without arriving, it reads "Not in the conversation
   yet" with a button to the terminal. A message queued behind a long
   command never expires while the command runs.
5. **No setting to turn the chat off.** The switch hides by itself when the
   log cannot be read; the terminal stays the first view.
6. **After a `/` command that may have opened a dialog.** While the
   waiting line shows and the agent's status has not changed, the box
   refuses with "Answer the dialog in the terminal first", until the
   command shows up, the status changes, or you tap Dismiss: the spike saw
   a dialog take the next sends.
7. **Codex images whose file is gone** (over half of the month's): shown as
   "Image not available". The log keeps a copy, but it pairs with its image
   only by order within one tool call; that fallback is a 1.x issue.

**What changes for you:** an agent's title bar gets a switch labelled
Terminal and Chat. The chat shows the conversation as the Claude app does:
your messages as bubbles, the agent's answers as formatted text, tool calls
folded into one line you can open, images as thumbnails you can open full
screen, and a line with hopping sheep while the agent works. You answer from
a message box, photos included, and `/` and `!` commands work as in the
agent's own box. When the app cannot read an agent's log, the switch is not
there and the terminal is all you see, as today.

## Why

Reading an agent's answers in a phone-sized terminal is slow, and images an
agent read or was sent cannot be seen at all. Ezra reversed "no chat-style
composer" on 2026-11-13 (`decisions.md`), and the chat view is part of
v1.0.0. The spike (`research/chat-view-spike-2026-11.md`, branch
`spike/chat-view`) found it works for Claude Code and Codex with what
herdr-core already offers, and tried it on the phone over four rounds.

## What Changes

- A Terminal / Chat switch in the terminal screen's title bar, shown only
  while the agent's log is found and reads cleanly; the chosen view stays
  when switching agents.
- Finding the log: Claude Code's per-process session file (checked against
  the process start time) and Codex's open rollout, through one
  `machine.exec` script; nothing to set up on the desk. A new session shows
  an empty chat before its first record; a session that cannot be read
  shows the terminal.
- Reading: a tail script over `machine.exec` (never `file.read`), from the
  end on first open, older messages on scrolling up, new lines after the
  terminal's events and every 0.5 s while the agent works.
- Parsing in Rust, one module per agent, with a health guard that keeps
  running and falls back to the terminal; rows with a stable identity.
- Sending with `terminal.send`, only to the agent whose log is shown and
  only while it is idle, done or working; `/` and `!` as typed; a sent
  message shown at once.
- Images: thumbnails and a full-screen viewer with swiping between a
  message's images; photos attached from the phone.
- The working line, the shimmer on a running command, the tool sheet, the
  context gauge, and new answers fading in as if typed.

## Capabilities

### New Capabilities
- `chat-view`: the switch and when it shows, matching a terminal to its
  log, reading and live updates, what the chat shows and how, sending and
  its guards, commands, images, the working line, the context gauge, and
  the fallbacks to the terminal.

### Modified Capabilities
- None in `openspec/specs/` (V1 is not archived yet). V1's `terminal-view`
  title bar gains the switch; `chat-view` states it.

## Impact

- New crate `herdr-chat` (parsers, matching, the desk scripts) carried over
  from the spike with its tests; the spike's `ChatSpike` object in
  `herdr-mobile` becomes a `Chat` object with a stable row identity.
- herdr-core API used: `terminal.get`, `terminal.send` (with its `if`
  guard), `terminal.put`, `machine.exec`, `events.subscribe`. No new
  herdr-core feature is needed.
- Android: the chat screen and its parts (`ui/chat`), the switch in
  `TerminalRoute`; the debug-only spike screen goes.
- Desk cost: a `machine.exec` every 0.5 s while an agent works with the
  chat open; battery cost not measured yet (a task).
- Linux desks only (`/proc`); on others the switch never shows.
