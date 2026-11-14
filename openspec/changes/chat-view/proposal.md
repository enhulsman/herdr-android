# Proposal: the chat view

## Plain summary

**Decisions** (Ezra's; my pick for each):
1. **A message sent while the agent works.** Both agents take it into the
   running turn and answer only it (spike, section 4). (a) Send it at once
   as a steer, and say so under the message box while the agent works:
   "Steers the current turn". (b) Hold it in the chat until the agent is
   idle, then send. *Pick (a)*: it is what the agent's own input box does
   ("steer like the CLI"), and (b) needs a queue the desk never sees.
2. **What is in this change.** In: everything the spike showed on the phone
   (reading, live updates, sending, `/` and `!` commands, images both ways,
   the tool sheet, the working line), plus three of your later ideas: new
   answers coming in as if typed, swiping between a message's images, and a
   context gauge in the top bar (the session's context only). Later, each as
   its own issue: plan limits in the gauge (5-hour, weekly, per model; Claude
   Code needs something on the desk to hand them over), verb themes,
   the terminal's bottom rows while a command waits, switching between an
   agent's sessions, and `/` command completion. *Pick: as listed*: the
   later ones need a desk-side piece, a settings screen, or a design of
   their own.
3. **After the agent exits.** (a) Keep showing what was read, without the
   message box, until the terminal starts another agent; opened fresh after
   the exit, the terminal shows. (b) Also offer an optional start hook that
   labels the terminal with its session, so the chat survives app restarts.
   *Pick (a) now*, the hook as a 1.x issue: it is a desk setting, and
   Codex's was never tested.
4. **A sent message that never shows in the log.** (a) After 30 seconds it
   reads "Not in the conversation yet" with a button to the terminal, and
   stays until it arrives or you dismiss it. (b) It stays "Sending…" for
   ever, as in the spike. *Pick (a)*: when the chat cannot follow, send the
   person to the terminal.
5. **Where the setting lives that turns the chat off.** (a) No setting: the
   switch hides by itself when the log cannot be read. (b) A switch in
   Settings as well. *Pick (a)*: the terminal stays the first view, and the
   chat never opens unasked except where you left it open.

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
  `machine.exec` script; nothing to set up on the desk.
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
