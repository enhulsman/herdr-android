# Proposal: accessibility basics

## Plain summary

**Decisions** (Ezra, 2026-11-17):
1. **Scope for v1.0.0.** The basics: the main paths (open an agent, type,
   answer an alert) work with TalkBack and at the largest font size. The
   terminal gets a plain fallback (TalkBack reads its visible text); the
   chat is where reading and answering by ear should end up.
2. **Later, each its own 1.x issue:** answering permission prompts in the
   chat (today the chat says the agent waits in the terminal), and a "read
   answers aloud" mode that speaks each new answer without TalkBack, for a
   spoken back-and-forth on the go.
3. **The terminal's text follows the system font size**, as it does now: at
   2x the phone grid is about 31 columns; pinch zoom still works.

**What changes for someone using the app:**
- At the largest font size, the terminal's title bar shows both lines and
  the keys row's labels fit their keys.
- With TalkBack on, the symbol keys are read by name ("dash", "slash",
  "tilde", "backslash", "pipe"), the terminal's visible text is read when
  TalkBack lands on it, and in the chat each answer that arrives while it is
  open is read once.

## Why

Issue #24: the app must stay usable with TalkBack and at large font sizes.
An audit on the test phone (2026-11-17) found most controls labelled and
the agent list and Machines screen fine at 2x, with four gaps: the
terminal title bar clips its second line at 2x, "ctrl" shows as "ctr", the
symbol keys have no spoken name, and TalkBack hears only "Terminal" on the
terminal, so a permission prompt there cannot be read.

## What changes

- Title bar: grows with its text instead of a fixed height.
- Keys row: a label too wide for its key at large font sizes shrinks to fit.
- Symbol keys: a spoken name each.
- Terminal: with a screen reader on, its node carries the visible text,
  cleaned of box drawing and spinner marks, refreshed at most once a second.
- Chat: with a screen reader on, each agent answer that arrives live is
  announced once.

Not in this change: the two 1.x items above; Higher text contrast stays as
it is (decided 2026-11-02).
