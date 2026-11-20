# Design: the usage gauge

## Context

What exists today (read on main, 2026-11-20):

- `crates/kudde-chat/src/lib.rs`: `Gauge { used, window }` with
  `percent()`. Codex: the `token_count` arm (around line 992) takes
  `info.last_token_usage.input_tokens` and `info.model_context_window`
  and drops the rest of the event, `rate_limits` included. Claude Code:
  the `assistant` arm (around line 600) sums input and cache tokens, with
  `window: None`.
- `crates/kudde-chat/src/tail.rs`: the desk's tail keeps only the last
  `token_count` line of each read, whole, so `rate_limits` already reaches
  the parser; nothing new crosses the wire for Codex.
- `crates/kudde-chat/tests/gauge.rs`: the gauge's tests; its fixture
  already carries a `rate_limits` member, unread.
- `crates/kudde-mobile/src/hub/chat.rs`: `ChatPoll.gauge_used`,
  `gauge_window`, `gauge_percent` (uniffi).
- `android/.../ui/chat/ChatScreen.kt`: `Gauge` draws `gaugeLabel` as text
  in the title bar's trailing slot; a tap toggles `ChatState.gaugeOpen`,
  which shows `gaugeDetail` in a `Strip` under the bar.
  `ui/chat/ChatState.kt` holds `gaugeLabel` and `gaugeDetail`;
  `ChatStateTest.theGaugeShowsCodexsShareAndClaudeCodesTokens` and the
  goldens `gauge-claude` and `gauge-codex` in `ChatScreenshotTest` cover
  them.
- The spec: `openspec/specs/chat-view/spec.md`, "A context gauge shows in
  the top bar" (line 751), which says plan limits are not part of it.

What the agents write:

- Codex, every `token_count` event (rollouts of November 2026, a Team
  seat): `"rate_limits":{"limit_id":"codex","limit_name":null,"primary":
  {"used_percent":6.0,"window_minutes":10080,"resets_at":<unix>},
  "secondary":null,"credits":{...},"individual_limit":null,
  "spend_control_reached":null,"plan_type":"team",
  "rate_limit_reached_type":null}`. 300 minutes is the 5-hour window,
  10,080 the week. On the owner's Team seat only the weekly window was
  ever present (395 of 395 counts over three days). Either may be null.
- Claude Code: its log names the model (`"model":"claude-opus-5-5"`) but
  not the window, which depends on the desk's settings (200k or 1M), and
  holds no limits. Its status line command gets a JSON payload on stdin at
  each draw, with `session_id`, `context_window.context_window_size` and
  `rate_limits` (`five_hour` and `seven_day`, each `used_percentage` and
  `resets_at`, account-wide). The payload is not kept anywhere. The
  per-model weekly limit is not in it; only Anthropic's usage service has
  it (research/chat-view-spike-2026-11.md). Field names were checked
  against a desk tool that reads this payload today. Confidence: moderate
  that the names stay; the sensor reads them leniently.
- Claude Code's `statusLine` setting: `{"type":"command","command":"...",
  "padding":0,"refreshInterval":2}` in `~/.claude/settings.json` (or
  `$CLAUDE_CONFIG_DIR`). A project's own settings may set a `statusLine`
  that wins over the user's.

How desk pieces ship: `kudde-push` is a released, signed binary for
x86_64 and aarch64, installed by hand to `~/.local/bin` (docs/kudde-push.md),
with config in `~/.config/kudde-push/` and state in
`~/.local/state/kudde-push/`. The phone's SSH key is restricted to
`herdr-core connect` (`crates/kudde-mobile/src/ssh.rs`,
`REMOTE_COMMAND`); every desk read goes through herdr-core's
`machine.exec` with a fixed script and arguments, as the chat's tail and
images do (`hub/chat.rs`, `image_argv`).

## Goals / Non-Goals

Goals: the ring, the sheet, Codex's limits from the log, Claude Code's
window and limits through an opt-in sensor that leaves the person's status
line as it was.

Non-goals: credits, spend, plan name, any button that acts on the plan;
per-model limits (need the account's login); usage on the Agents list or
machine cards (decision 5); the gauge in the terminal (decision 6);
history or charts of usage; alerts when a limit nears.

## D1. Data flow per agent

**Codex.** Log → tail (unchanged) → `Parser` keeps `limits: Option<Limits>`
beside `gauge` → `chat_core::View.limits` → `ChatPoll` → app. The ring
needs no new desk read.

```rust
pub struct Limits { pub at: Option<i64>, pub name: Option<String>, pub windows: Vec<Window> }
pub struct Window { pub minutes: Option<u32>, pub used_percent: f64, pub resets_at: Option<i64> }
```

The `token_count` arm reads `p["rate_limits"]`: when an object, `primary`
then `secondary`, each kept when `used_percent` is a number;
`window_minutes` and `resets_at` when numbers; `limit_name` when a
non-empty string. `at` is the record's `timestamp` (RFC 3339, UTC, as
Codex writes it), parsed by a small function in `kudde-chat` (no new
crate); an unparsable timestamp gives `at: None`, shown as never old.
`rate_limits` null or not an object keeps the earlier `limits`. A reading
with no window left keeps the earlier one too.

Decision 8 (pick): while the sheet is open, on its opening and then at
most once a minute, one `machine.exec` reads the freshest reading of the
machine's other Codex logs:

```
["sh", "-c", CODEX_LIMITS_SCRIPT, "sh", "<sessions root>"]
```

The sessions root is derived from the open log's path (Codex keeps
`<root>/YYYY/MM/DD/rollout-*.jsonl`); a path of another shape skips the
scan. The script lists rollouts changed in the last 24 hours, keeps the
20 newest, and prints for each the last line of its final 64 KB that
holds `"rate_limits":{`. Output is at most about 30 KB. Each line goes
through the same reader as the parser's `token_count` arm; the reading
with the latest `at` wins over the open log's own when it is newer.

**Claude Code.** The sensor (D2) writes
`~/.local/state/kudde-push/usage/claude/<session>.json`. While a Claude
Code chat is open, `kudde-mobile` runs through `machine.exec`:

```
["sh", "-c", USAGE_SCRIPT, "sh", "<session>"]
```

`USAGE_SCRIPT` prints `none` when the folder is missing; otherwise
`session` and the session file's first 4,096 bytes (or `session -` when
there is none), then `newest` and the first 4,096 bytes of the folder's
newest file by modification time (or `newest -`). One call gives the
window and the freshest account limits (decision 8, pick); the windows
of whichever reading has the later `at` are shown. The session id is
the one the chat already holds (`Log.session`), checked against
`^[0-9A-Za-z-]{1,64}$` in Rust before the argv is built. Reads: when the
chat opens on a Claude Code log, when the sheet opens, and every 60 s
while the chat shows (the chat's own poll loop counts the time; no new
timer thread). The result is parsed in Rust into the same `Limits` and a
`window: Option<u64>`, which `Gauge` takes for its share. The paths are
fixed under `$HOME`, not `$XDG_STATE_HOME`, because herdr-core's exec and
Claude Code may run with different environments.

`ChatPoll` gains, over uniffi:

```rust
pub limits: Vec<ChatWindow>,          // in reported order
pub limits_at: Option<i64>,           // Unix seconds of the reading
pub limits_name: Option<String>,      // Codex limit_name
pub usage_note: Option<UsageNote>,    // why there are no limits, when none
pub struct ChatWindow { pub minutes: Option<u32>, pub used_percent: f64, pub resets_at: Option<i64> }
pub enum UsageNote { CodexNotYet, SensorOff, SensorNoLimits, SensorNoReading }
pub sensor_window_missing: bool,      // sensor on, no window for this session yet
```

`gauge_window` and `gauge_percent` now also carry the sensor's window for
Claude Code. Names, rounding, ages and reset logic are the app's (D4),
so the phone's clock and locale decide them in one place.

## D2. The sensor, in `kudde-push`

A new `crates/kudde-push/src/usage.rs` and the commands
`kudde-push usage on|off|status` and
`kudde-push usage sensor --original <file>`. `main.rs`
dispatches on `usage` before it reads the alert config, so none of them
needs ntfy, a token or a core. The service and its config do not change.

**Settings edit.** No JSON library writes the file. A small scanner finds
the top-level object's members and their byte spans (strings with
escapes, nesting, whitespace); `serde_json` only validates the whole file
first. `on` replaces the `statusLine` member's value span, or inserts a
member after the opening brace when there is none; `off` puts the kept
text back into the same span, or removes the member and its comma. So
bytes outside the entry never move, and on-then-off is the identity
(spec "On and off again").

**Kept originals.** One per settings file, under the fixed folder
`$HOME/.config/kudde-push/usage/` (never `$XDG_CONFIG_HOME`; folder 700,
files 600, as the text may hold a token in a command line). The file is
named `statusline-<key>.json`, where `<key>` is a 64-bit FNV-1a hash of
the settings file's canonical path in hex (a few lines of code, no new
crate); the file also records that path, and a mismatch counts as no
kept original. Content: `{"v":1,"settings":"<canonical path>",
"present":true,"text":"<exact value text>"}` or `"present":false`. The
sensor command names the kept file itself, so a sensor never guesses
which settings file started it: `'<abs kudde-push>' usage sensor
--original '<kept file>'`, each path in single quotes with `'` written
as `'\''`. The new value copies the original's members other than `type`
and `command`.

**Is it the sensor's?** By value, never by bytes: the member is parsed,
`type` must be `command`, and the command is split with POSIX shell
quoting rules; it is the sensor's when the first word's file name is
`kudde-push` and the next two are `usage sensor`. So a moved kudde-push,
another install path, or Claude Code reformatting the file (other order,
other spacing) is still recognised (scrutiny findings 1 and 7). `on`
over the sensor's entry rewrites only the command (new path, same kept
file) and never touches the kept file. `on` with a kept file whose text
differs from a non-sensor entry stops (the person changed it by hand);
`on` whose entry equals the kept text proceeds as a fresh `on`. `off`
whose entry equals the kept text deletes the kept file and reports "not
on". `off` with the sensor's entry and a missing or unparsable kept file
changes nothing. The readings folder is deleted only when no kept file
remains. The file
path is resolved through symbolic links first and the temporary file is
made beside the target, then renamed over it; its mode is the target's.
Before the rename the target is read again and compared with what was
read; a difference restarts once, then fails (Claude Code rewrites this
file itself, for example on `/config`).

**Chaining.** Claude Code runs the entry's command. When
`KUDDE_USAGE_SENSOR=1` is already set, the sensor exits 0 at once (a
sensor inside a sensor). Otherwise it reads the kept file named by
`--original`; when that holds a `command` entry it spawns
`/bin/sh -c <command>` with the inherited environment plus
`KUDDE_USAGE_SENSOR=1`, the inherited working folder, stdout and stderr
inherited (bytes pass untouched, no buffering by kudde-push), stdin a
pipe. A writer thread copies stdin to the pipe as it arrives, to the end,
whatever its size, keeping only the first 1 MB for the save (over 1 MB:
nothing saved, nothing cut). A broken pipe (an original that never reads
or stops early) ends the copying to the child quietly; stdin is still
read to its end so Claude Code's write never fails. After the copy the
main thread saves the reading (D3) inside `catch_unwind`, waits, and
exits with the child's status (a signal-killed child gives 128 +
signal). In sensor mode kudde-push writes nothing of its own to stdout
or stderr, errors included; a test-only cargo feature injects a failure
and a panic into the save to prove it. Claude Code's own timeout applies
to the whole; the sensor adds none.

`/bin/sh -c` is assumed to be how Claude Code runs a status line command
on Linux: **to verify in task 3.0** (a throwaway status line that prints
its parent's command line), before any sensor code. If it is another
shell, the sensor runs the kept command the same way, one constant, and
this paragraph is corrected. Confidence until then: moderate.

**Cost.** One process start and one small write per draw. With
`refreshInterval` 2 that is one start every 2 s per Claude Code session.
The save writes only when the reading's content changed or 60 s passed,
so an idle session writes once a minute at most. Budget (task 3.3): the
sensor adds under 10 ms at the median and under 50 ms at worst to a
draw; a miss stops the build and goes back to the owner.

## D3. The file on the desk

`$HOME/.local/state/kudde-push/usage/claude/<session_id>.json`, folder
700 (made by `usage on`), file 600, written to a temporary name and
renamed:

```json
{"v":1,"at":1795201907,"window":1000000,
 "windows":[{"id":"five_hour","used_percent":15,"resets_at":1795208400},
            {"id":"seven_day","used_percent":70,"resets_at":1795500000}]}
```

`windows` keeps the payload's order; the phone maps `five_hour` to 300
minutes and `seven_day` to 10,080, and puts the 5-hour one first. A
window with another id has no known length, so the phone leaves it out;
the file keeps it, so a later app can name it without a new sensor. A
`session_id` outside `[0-9A-Za-z-]{1,64}` saves nothing.
Files older than 7 days are removed on a save. Size: under 300 bytes.

**Privacy.** The limits are the account's, and the window is the
session's. They are written only on the person's own desk, under their
home, readable by them only, and read by their own phone over their own
SSH through herdr-core. kudde-push sends none of it to ntfy or anywhere
else, and the sensor opens no network connection. Nothing from the
payload but the numbers above is written: not the prompt, the folders,
the transcript path, the model or the cost.

## D4. The ring and the sheet, in Compose Foundation

The app uses Compose Foundation and no Material, and takes no new
dependency.

- **Ring.** `ContextRing` in `ui/chat/`: a `Box` of 48 dp with a `Canvas`
  of 18 dp: a 2.5 dp track arc in `colors.border`, and the share as an arc
  in `colors.fg` from the top, clockwise, capped at full. Sizes come from
  the render. Semantics: `role = Button`, `stateDescription =
  gaugeSpoken(...)`, `onClick(label = "Show usage")`,
  `progressBarRangeInfo` only with a share; `clearAndSetSemantics` so it
  is one node. It takes `Gauge`'s place in the `TitleBar` trailing slot
  (or the place `spaces-navigation` keeps for it).
- **Sheet.** `UsageSheet` in `ui/chat/UsageSheet.kt`, drawn over the chat
  like `ToolSheet` (`ui/chat/ChatToolSheet.kt`): scrim, 28 dp top corners,
  the same sheet colour (`sheetColor`), drag handle, `sheetSettle` reused
  for a swipe down; one stop at its content height, at most the screen
  minus the safe top, scrolling inside. Back closes it (`BackHandler`, as
  the tool sheet). `ChatState.usageOpen` replaces `gaugeOpen`; the `Strip`
  for the gauge goes.
- **Bars.** A row: name in `rowName`, share and reset in `secondary` dim;
  under it a 6 dp bar, track `colors.raised`, fill `colors.fg`, 3 dp
  corners. No state colours (decision 7).
- **Text.** Pure functions in `ChatState.kt`, tested without Compose:
  `gaugeSpoken`, `tokens` (gains millions), `windowName(minutes)`,
  `resetLine(resetsAt, now, zone, is24h)`, `asOfLine(at, now, ...)`,
  `windowSpoken(...)`, `usageNoteText(note)`. Time and the 12/24-hour
  choice come from the phone (`DateFormat.is24HourFormat`), passed in, so
  tests fix them.
- **Live.** The sheet draws from the same `ChatPoll` as the chat, so a
  new reading updates it in place; "now" for ages ticks once a minute
  while the sheet is open.

## Risks / Trade-offs

- **Claude Code's payload is not a documented contract for our use.** A
  renamed field drops the window or the limits; the phone then shows
  tokens only, as today. The sensor's tests pin today's names; the phone
  check (task 5.1) reads a real payload's numbers.
- **A project status line wins.** In a project whose own settings set a
  `statusLine`, the sensor does not run; those sessions show tokens only,
  and the sheet's notes say a project's own status line can be the
  reason. Also in the docs; not worked around.
- **Readings from other sessions** (decision 8). Limits are
  account-wide, so the freshest reading of any session of the agent on
  that machine is shown. Two accounts on one machine (another
  `CLAUDE_CONFIG_DIR`, another `CODEX_HOME` login) would mix; rare on one
  desk, and the reading's age still shows. With the other option the
  open session's own reading is shown, marked "As of" when old.
- **The uninstall order.** Removing kudde-push while the sensor is on
  leaves Claude Code's status line calling a missing program (it shows
  nothing). The docs say to run `usage off` first (task 3.5); `usage
  status` from a new place names the missing path.
- **Clock skew.** Ages and resets are judged by the phone's clock against
  the desk's and Codex's timestamps. Minutes of skew move "As of" by
  minutes; acceptable.
- **Editing the person's settings.** The narrowest edit possible (one
  member, text kept, compare before rename), and only on their command.
  A bug here touches every Claude Code session on the desk, so the
  settings tests are the largest part of the sensor's tests, and the
  change goes through a second-model review (task 5.3).
- **Process start every draw.** A Rust binary starts in a few
  milliseconds; the person's own command dominates. Measured in task 3.3.
- **Shared title bar with `spaces-navigation`.** Both touch
  `ChatScreen.kt`'s `TitleBar` slot. Whichever lands second fits into
  the first's bar; the goldens of both are updated then.
