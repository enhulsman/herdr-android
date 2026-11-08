# Proposal: agents named after their session

## Plain summary

With several Claude Code sessions open, every row and every alert reads
"Claude Code", and every alert reads "Claude Code finished · WSL". You cannot
tell which session finished or which one needs you.

Claude Code already names each session: it sets the terminal's title to a short
summary of the conversation ("Pricing page copy", "Fix login redirect"), and the
desk app already writes where each terminal sits ("website / 2"). This change
uses both.

**What changes for you:**
- The Agents list: the row's name is the session title; the second line is
  "website / 2 · WSL" (space / tab · machine) instead of "website · WSL"
  (folder · machine). The terminal screen's title bar shows the same name.
- Alerts: "Pricing page copy finished · WSL" and "Pricing page copy needs you".
- The lock screen: decision 3; nothing changes for you if your phone shows
  sensitive content on the lock screen.
- Alerts change once `herdr-push` is updated on the machine; the list changes
  with the app alone.

**Decisions** (my pick for each):
1. **The name.** (a) A name you gave the terminal at the desk, else the
   session title, else the program ("Claude Code"). (b) Always the program,
   with the title as the second line. *Pick (a)*: the title is what tells
   sessions apart, and the program already shows as the row's icon.
2. **The second line.** (a) "space / tab · machine", falling back to
   "folder · machine" for a terminal the desk has not placed. (b) Keep
   "folder · machine". *Pick (a)*: several sessions often share a folder,
   but each has its own tab.
3. **The lock screen.** A title can say what you are working on. Android
   decides what a locked phone shows with its own setting, "sensitive
   notifications" (Settings, Notifications, on the lock screen). The app gives
   each alert a version without the title: "An agent needs you · WSL". (a)
   That: with sensitive content hidden, the lock screen shows "An agent needs
   you · WSL" and you see the title after unlocking; with it shown, the lock
   screen shows the title, as it shows every other app's content. (b) Also
   name the program on the lock screen ("Claude Code needs you · WSL"), which
   needs a new field in what the machine sends and a herdr-push update before
   it works. *Pick (a)*: simpler, and "an agent" says enough while locked.
4. **Terminals without an agent** ("Other terminals"). (a) Keep today's name
   (the program, "zsh" or "make"), with the new second line. (b) Use their
   title too. *Pick (a)*: shells set titles like "user@host: ~/src", which
   is noise. An agent's title is used only while it reports status, so a
   session that quit does not keep its old title.

**Not in this change:** renaming terminals from the phone; showing the title
inside the terminal screen beyond its title bar; the status notification
(its own change, next).

## Why

Named in use on 2026-11-08: with seven sessions open and three working, every
alert said "Claude Code finished · WSL".

## What changes

- `herdr-proto`: the name rule (`attention.rs`) uses the terminal's `title` for
  agents; a new `place` field on rows ("space / tab" from the desk's `where`
  label, else none, and then the folder shows).
- `herdr-mobile`: rows carry `place`; uniffi bindings regenerated.
- Android: the list's and terminal screen's second line, the alert texts,
  private notifications with a public version.
- `herdr-push`: sends the new name (it shares the rule); what it sends keeps
  the same fields.
- Release notes for the next version tell you to update `herdr-push`.

## Impact

- Specs: new capability `agent-names`; V1's agent-list and alerts sentences on
  the name point to it (amended in place in `android-client-v1`, as
  start-agents did).
- Machines: alerts keep the program's name until `herdr-push` is updated.
- None on the phone's settings: the "Needs you" channel stays (task 3.0).
