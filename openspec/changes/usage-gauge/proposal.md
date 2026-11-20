# Proposal: the usage gauge

## Plain summary

**Decided (Ezra, 2026-11-20):** the chat's context gauge becomes a small
ring in the title bar, and a tap opens a sheet with the context and the
plan's limits (the 5-hour and the weekly window, each with how much is
used and when it resets), for Codex and for Claude Code. The reference is
the Claude mobile app's Usage sheet; kudde's sheet is its own, in kudde's
colours and type, without credits, spend or buttons to buy or reset
anything. The ring and the sheet were first planned in `spaces-navigation`;
that change now only keeps a place for the gauge in the title bar.

This closes issue #37, "Plan limits and the context percentage in the
gauge's sheet" (milestone 1.x). Its "Done when": *"the gauge's sheet shows
how much of the 5-hour and weekly windows is used, for both agents, and
the context as a percentage."* One limit to that: the sheet shows the
windows each agent reports. On a Team seat Codex has reported only the
weekly window so far, so a Codex sheet may show one window.

**Decisions that are yours** (my pick first; every text on screen and every
new picture waits for your approval, see "Texts" and "New on screen"):

1. **Where the Claude Code sensor lives and how it goes on.**
   *Pick: a part of `kudde-push`, turned on by one command at the machine,
   `kudde-push usage on`, and off again by `kudde-push usage off`.* Claude
   Code hands its window size and limits only to its status line, each
   time it draws it, and keeps them nowhere else. So something on the
   desk has to sit in the status line, pass everything on to your own
   status line unchanged, and keep the few numbers the phone needs.
   `kudde-push` is already kudde's signed program on the desk, with an
   install guide; in Rust it can change one entry of Claude Code's
   settings file and put back the exact original. Nothing changes until
   you run the command, and `off` puts back what was there.
   Other options: (b) an optional line in the first-run setup command
   (the brief suggested this); it runs before the machine is trusted and
   would need `jq` or Python on the desk to edit a JSON file, so I did not
   pick it; (c) the phone turns it on with a tap, through herdr-core;
   easiest, but then kudde writes an agent's own settings from the phone,
   which it has never done.
   Cost of (a): someone without alerts downloads `kudde-push` for this; it
   runs no service unless alerts are set up.
2. **What the ring shows when the share is not known** (Claude Code
   without the sensor). *Pick: an empty ring*, the track only; the tokens
   are spoken and shown in the sheet. The gauge keeps one shape and one
   place for every agent.
   Other options: (b) the empty ring with "283k" beside it; (c) no ring,
   the tokens as text, as today.
3. **Which limits the sheet shows when an agent reports several.**
   *Pick: every window the agent reports*, in its own order, each named by
   its length ("5 hours", "Week") and, when Codex gives a limit a name, by
   that name too. Per-model weekly limits (as "Fable only" in Claude's
   app) do not come with the status line; only Anthropic's usage service
   has them, and asking it needs the account's login. So they stay out.
   Other options: (b) only the 5-hour and weekly windows, other windows
   left out; (c) also ask the usage service from the desk (the login
   stays on the desk, but kudde would call Anthropic for you).
4. **How old a limit reading may be.** *Pick: never hidden, but marked.*
   A reading older than 15 minutes shows "As of 14:02". Once a window's
   reset time has passed, its share is dropped and the row says when it
   reset, until a newer reading comes.
   Other options: (b) hide readings older than an hour; (c) show every
   reading as it is, without its age.
5. **Usage anywhere else.** *Pick: no, only the ring and its sheet in the
   chat.* Other options: (b) the account's limits on the machine card;
   (c) a mark on an agent row when its context is nearly full.
6. **The ring in the terminal too.** *Pick: no, in the chat only, as the
   gauge is today.* The terminal's title bar is the tighter one (map,
   switch, menu). Other option: the ring also in the terminal's title bar
   of an agent whose chat is offered.
7. **A warning colour.** *Pick: none*, the ring and the bars are drawn in
   the text colour at every level. The state colours (working, needs you,
   done) mean what an agent is doing, and a full ring already says full.
   Other option: the working colour from 80% and the needs-you colour from
   95%.
8. **Which session's reading the limits come from**, for both agents.
   *Pick: the freshest reading among the machine's sessions of that
   agent.* Limits are the account's, not the session's, so an idle
   session's old reading should not hide a newer one next to it. Codex:
   the newest of the machine's Codex logs of the last day (at most 20),
   read only while the sheet is open. Claude Code: the sensor's newest
   file, read in the same call as the session's own. Shown with its age
   (4).
   Other option: the open session's own reading only; fewer reads, but
   an idle session shows an old reading, marked "As of".
9. **The words and the pictures.** Every text below waits for your
   approval, and the new renders for your sign-off (task 1.2), before any
   screen is built.

**What changes for you:**

- In the chat, the "47%" or "283k" in the title bar becomes a small ring.
  It fills as the conversation fills the model's context window.
- A tap on the ring opens a sheet: the context ("223k of 475k tokens",
  "47% used") with a bar, then the plan's limits: each window with a bar,
  "41% used" and "Resets Tue 07:00". The sheet closes with Back, a tap
  above it or a swipe down. The line under the title bar that a tap showed
  until now goes.
- Codex: the ring and the limits work at once; they are in its log.
- Claude Code: without the sensor, nothing changes but the look: tokens
  only, an empty ring, no limits, and the sheet says how to get them.
  With `kudde-push usage on` on the machine, the ring fills and the limits
  show, as long as the session's status line has drawn once.
- With TalkBack the ring reads "Context, 47% used, button", and each row
  of the sheet is read as one line.
- On the desk, after `kudde-push usage on`, Claude Code's status line
  looks exactly as before; `kudde-push usage off` puts your settings back.

## Why

The gauge shows tokens and, for Codex, a percentage, but not what stops a
working day: the 5-hour and weekly limits. Codex writes them into its log
on every token count; kudde reads that line already and drops them.
Claude Code writes neither its window size nor its limits to its log
(decisions.md, 2026-11-14), so its gauge can show only tokens. Both were
parked for 1.x in the chat view's decisions (2026-11-14 and 2026-11-16)
and became #37. The owner asked for it as a ring and a sheet, as the
Claude app has.

## What Changes

- `kudde-chat` reads Codex's `rate_limits` from the last `token_count`:
  each window's used share, length and reset time, and the limit's name.
  Credits, plan type and spend stay unread.
- `kudde-push` gains `usage on`, `usage off`, `usage status` and the
  sensor itself, `usage sensor`, which Claude Code runs as its status line
  command. The sensor runs your own status line command with the same
  input and prints exactly what it prints. It keeps the session's window
  size and the account's limits in a small file per session on the desk.
- `kudde-mobile` reads that file for an open Claude Code chat through
  herdr-core, as it reads logs, and hands the window and the limits to
  the app with the gauge.
- The chat's gauge becomes a ring; a tap opens the usage sheet instead of
  the line under the title bar.
- Docs: a "Usage in the chat" section in `docs/kudde-push.md`.

## Capabilities

### New Capabilities
- `usage-sensor`: the desk piece for Claude Code: turning it on and off,
  passing the status line through unchanged, what it keeps and where, and
  payloads without the fields.

### Modified Capabilities
- `chat-view`: "A context gauge shows in the top bar" is rewritten (the
  ring, the share for Claude Code with the sensor, a tap opens the sheet);
  new requirements for the ring, the sheet, Codex's limits, Claude Code's
  sensor data, old and missing readings, and screen readers.

## Texts (each needs the owner's approval)

Drafted; none is final. `<n>` is a number, `<time>` the phone's clock
format, `<day>` a short weekday.

| Where | Text |
|---|---|
| Spoken: the ring, share known | "Context, 47% used" |
| Spoken: the ring, share not known | "Context, 283k tokens used" |
| Spoken: what a tap on the ring does | "Show usage" |
| Sheet: title | "Usage" |
| Sheet: under the title | "<agent> on <machine>", for example "Codex on workstation" |
| Sheet: first section | "Context" |
| Context, share known | "223k of 475k tokens" · "47% used" |
| Context, share not known | "283k tokens used" |
| Context, Claude Code without the sensor | "The window size comes with the usage sensor." |
| Context, sensor on, no reading for this session | "No window size for this session yet. It comes when Claude Code draws its status line, unless this project sets its own." |
| Sheet: second section | "Plan limits" |
| A window's name | "5 hours" · "Week" · "<n> hours" · "<n> days" |
| A window with a Codex limit name | "Week · <name>" |
| A window's share | "41% used" |
| Reset today | "Resets <time>", for example "Resets 15:00" |
| Reset on a later day | "Resets <day> <time>", for example "Resets Tue 07:00" |
| A reading older than 15 minutes | "As of <time>" (on another day: "As of <day> <time>") |
| A window whose reset has passed | "Reset at <time>, no reading since" |
| Codex, no limits in its log yet | "Codex has not reported its limits yet." |
| Claude Code, no sensor on the machine | "Claude Code shares its limits only with its status line. Run kudde-push usage on at the machine to see them here." |
| Claude Code, sensor on, no limits in what it gets | "This Claude Code does not report its limits to its status line." |
| Claude Code, sensor on, no reading at all yet | "No reading yet. It comes when Claude Code draws its status line, unless this project sets its own." |
| Spoken: a window row | "5 hours, 41% used, resets at 15:00" |
| Spoken: the sheet opening | "Usage" |
| Desk: `usage on` | "Usage sensor on for <settings>. Claude Code's status line now runs through kudde-push and shows what it showed before. Undo: kudde-push usage off" |
| Desk: `usage on`, already on | "Usage sensor is already on for <settings>." |
| Desk: `usage on`, kudde-push moved | "Usage sensor on for <settings>, now run from <path>." |
| Desk: `usage on`, an older kept status line | "Not changed: an earlier status line from a previous usage on is kept in <file>. Delete that file if you no longer need it, then run kudde-push usage on again." |
| Desk: `usage off` | "Usage sensor off for <settings>. Your status line is back as it was." |
| Desk: `usage off`, not on | "Usage sensor is not on for <settings>." |
| Desk: `usage off`, status line changed since | "Not changed: the status line in <settings> is no longer the sensor. Your earlier status line is kept in <file>." |
| Desk: `usage off`, kept status line lost | "Not changed: the earlier status line kept in <file> is missing or unreadable, so it cannot be put back. Change statusLine in <settings> by hand." |
| Desk: settings file not readable | "Not changed: <settings> is not valid JSON." |
| Desk: settings file kept changing | "Not changed: <settings> kept changing while kudde-push wrote it. Try again." |
| Desk: `usage status` | "on: <settings>" · "off: <settings>" |
| Desk: `usage status`, program missing | "on: <settings>, but it runs <path>, which is missing. Run kudde-push usage on to repair it." |
| Desk: `usage status`, kept status line missing | "on: <settings>, but the kept status line <file> is missing. kudde-push usage off cannot put it back." |

## New on screen (each needs the owner's sign-off against a render)

All of them use only colours and type in `design-tokens.json`. None has a
render yet (task 1.1 draws them, 1.2 is the sign-off). Render numbers are
the next free ones in `design/renders/screens.html` when they are drawn:
13 to 23 are taken on main, and `spaces-navigation` and
`first-run-walkthrough` plan some too.

1. **The ring.** About 18 dp across in a 48 dp touch area, in the chat's
   title bar where the gauge text is today (or in the place
   `spaces-navigation` keeps for it). A track in the border colour and
   the used share in the text colour; with no share, the track only.
   No number in the bar.
2. **The usage sheet**, Codex: context with a bar, one weekly window.
3. **The usage sheet**, Claude Code with the sensor: context with a bar,
   the 5-hour and weekly windows, one of them marked "As of".
4. **The usage sheet**, Claude Code without the sensor: tokens only, and
   the line on how to turn it on.
5. Each in graphite and paper, at 412 dp and at 130% font size.

## Impact

- Rust: `crates/kudde-chat/src/lib.rs` (`Limits` beside `Gauge`, read from
  `token_count`); `crates/kudde-mobile/src/hub/chat.rs` and `chat_core.rs`
  (the sensor read for Claude Code, new fields on `ChatPoll`);
  `crates/kudde-push/src/` (a new `usage.rs`: settings edit, sensor,
  file).
- Android: `ui/chat/ChatScreen.kt` (the ring in place of `Gauge`, the
  strip goes), `ui/chat/ChatState.kt` (spoken values, sheet state), a new
  `ui/chat/UsageSheet.kt`; `ChatScreenshotTest` goldens.
- Design: three renders, the entries `contextRing` and `usageSheet` in
  `design-tokens.json`, rows in `design-brief.md`.
- Docs: `docs/kudde-push.md`, release notes.
- Shares the chat's title bar with `spaces-navigation`; build one after
  the other. Whichever lands second fits into the other's bar.
- Stored data: none on the phone. On the desk, only after `usage on`: one
  entry in Claude Code's settings, the original kept under
  `~/.config/kudde-push/usage/`, and a small file per Claude Code session.
- No new dependency, no herdr-core feature.
- Estimate: 6 to 8 builder runs and one design session with the owner.
  Confidence: moderate for the Rust and the sensor, low for the screens
  until the renders are signed off.
