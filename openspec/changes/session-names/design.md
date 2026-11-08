# Design: agents named after their session

## Context

Checked on WSL 2026-11-08 over `herdr-core connect` (`terminal.list`,
`terminal.get`, `doc.get herdr-app`):

- Claude Code sets each terminal's `title` to a short summary of the session
  ("Pricing page copy"): plain text, no spinner glyphs, up to about 50
  characters. It is set early in the session and may lag its topic later.
  Shells without a prompt theme leave `title` empty; themed prompts set
  "user@host: ~/src" style titles.
- `name` is null unless someone named the terminal at the desk.
- Terminals the desk placed carry the labels `herdr.space`, `herdr.tab` and
  `where`, the last as the desk renders it: "herdr-android / APP",
  "Coding / 2". In the `herdr-app` document an unnamed space is `name: ""`
  (the desk shows its folder's name) and an unnamed tab is `name: ""` (the
  desk shows its number), so `where` is the desk's own text and the phone does
  not redo those rules. A terminal started from the phone has no `where` until
  the desk adopts it.
- The name today: `attention.rs` `name_of`: `name`, else the program's display
  name from `status.app`, else `argv[0]`'s file name, else the address.
  herdr-push uses the same `Tracker` rows, so alerts carry the same name.
- Alerts: the push payload's `name` is the phone's notification title
  (`Alerts.kt` `agentNote`). The "Needs you" channel was created with
  `lockscreenVisibility = VISIBILITY_PUBLIC`; notifications set no visibility.

## Decisions

### D1. The name rule

For a row with agent status: `name` (trimmed, non-empty), else `title`
(cleaned, non-empty), else the program's display name, else the address. For
"Other terminals": unchanged (`name`, else the program, else the address).
`title` is read only while the terminal reports status, so a session that quit
falls back to the program name, and a shell's prompt title never names a row.

Cleaning: control characters removed, whitespace runs collapsed to one space,
trimmed, cut to 80 characters on a char boundary with "…". The cap keeps the
push well inside its 4 KiB body; the screen ellipsizes as it does today.

### D2. The place

A new `place: Option<String>` on `Row` and `Other`: the `where` label, cleaned
as D1, when non-empty and while `herdr.space` is non-empty; else none. The
list's and the terminal screen's second line is "place · machine", else
"folder · machine" as today. No layout lookup: `where` is the desk's own
rendering (see Context), and a terminal the desk has not placed is described
by its folder.

Why the `herdr.space` condition: parking a terminal empties `herdr.space`
(herdr-push `rules.rs` 285-287), and nothing says `where` is cleared, so a
parked terminal would name a tab it left. Risk left: if the desk does not
rewrite `where` after a rename, or tab numbers shift after a reorder, the line
lags until it does. Checked at the phone check (task 4.2).

### D3. Alerts

Texts as today with the new name: "<name> needs you", body "<kind> ·
<machine>"; "<name> finished · <machine>". The payload is unchanged
(`name` already exists); herdr-push sends the D1 name. An older herdr-push
keeps sending the program name, and the phone shows what it receives.

Every agent notification, and the test alert, gets a fixed public version:
"An agent needs you · WSL", "An agent finished · WSL", "Test alert from WSL".
No payload field for the program: a field, a V1 amendment and a version-skew
fallback only to print "Claude Code" instead of "An agent" on the lock screen
is not worth it (review finding 3).

The lock screen. `Notification.visibility` defaults to `VISIBILITY_PRIVATE`,
and SystemUI consults a channel's lock-screen override only when it is
PRIVATE (redact) or SECRET (hide); the "Needs you" channel's PUBLIC override is
then effectively no override (moderate-high confidence, review finding 1).
So: what the lock screen shows is decided by the phone's own "sensitive
notifications" setting. With sensitive content hidden, Android shows the
public version; with it shown, the full notification. The app sets
`VISIBILITY_PRIVATE` and `setPublicVersion` explicitly on every notification.

Task 3.0, settled on the phone 2026-11-08 (`dumpsys notification`): the
existing `needs-you` channel shows `mLockscreenVisibility=-1000` (no override)
on both the release and the debug install, so Android never applied the
app's PUBLIC. The channel stays; no move, no settings reset. The phone has
`lock_screen_allow_private_notifications=0` (sensitive content hidden), so
today its lock screen shows agent alerts redacted, and with this change it
shows the public version.

### D4. Bindings and versions

`Row.place`, `Other.place` cross uniffi as `String?`. The payload does not
change. No core version bump: `title` and labels are already in
`terminal.list`.

### D5. Cutting

The 80-character cut is on a char boundary, not a grapheme boundary, so an
emoji sequence or a combining mark at the cut can render oddly. Accepted:
titles are short plain text in practice, and a grapheme crate is not worth a
new dependency.

## Open risks

- Titles lag the session's topic (Claude Code sets it early). Accepted: it is
  what the desk shows too.
- Agents other than Claude Code may not set a title; they keep the program
  name.
- Claude Code may prefix its title with a spinner or "✳" glyph during a turn;
  checked only between turns so far. If it does, the row would rename many
  times a second. Task 4.2 samples the title mid-turn; if glyphs show, a
  leading run of non-alphanumeric characters is stripped and a scenario added.
