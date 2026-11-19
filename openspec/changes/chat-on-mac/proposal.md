# Proposal: the chat for agents on a Mac

## Plain summary

**Decisions** (Ezra, 2026-11-19): agents on a Mac are part of v1, as this
change, split from `desktop-chat` after the council review; the gallery
release of the desk chat waits for it. No open questions; one fact must be
checked first (below).

**What changes for you:** agents running on a Mac get the chat: in the herdr
desktop app, from any computer, and on the phone, if the phone can reach the
Mac's herdr-core. Until now both said the agent could not be read.

**Checked first:** how the phone reaches a Mac desk. `decisions.md`
(2026-10-31) says the phone cannot SSH into the Mac. If no route exists, this
change covers the desk only and its phone scenarios are dropped before
building.

## Why

The lookup, tail and image scripts read `/proc` and use GNU `stat`, so an
agent on a Mac shows no chat, on the desk or the phone. Most of the herdr
gallery's audience runs agents on a Mac.

## What Changes

- The scripts branch once on `uname -s`; the Linux branch stays byte for
  byte as it is. The Darwin branch finds the same facts with `ps`, `lsof`,
  `stat -f` and `pgrep`.
- The lookup decision compares Claude Code's recorded process start on a
  Mac, and matches Codex by the files the process holds open.
- The phone checks the machine's system once, so "neither Linux nor macOS"
  is decided, not assumed.
- `desktop-chat`'s "not on a Mac yet" message goes; Mac agents read as Linux
  ones do.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `chat-view`: the lookup works on macOS, and the switch shows for agents on
  a Mac.
- `desktop-chat`: Mac agents are supported. Its delta is drafted against
  the pending desktop requirements and integrated after desktop-chat tasks
  2.8 and sections 3–4 pass, before the joint gallery release; archival is
  not a prerequisite.

## Impact

- `kudde-chat`: `locate.rs`, `image.rs`, `tail.rs` scripts and
  `locate::decide`; new `Probe` fields.
- Testing needs Ezra's MacBook with herdr-core running.
- Estimate: 2-3 sessions. Confidence: low.
