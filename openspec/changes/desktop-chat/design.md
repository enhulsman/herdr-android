# Design: kudde's chat in the herdr desktop app

## Context

The phone's chat (`chat-view`, archived 2026-11-16) is split in layers:
`kudde-chat` holds the parsers, the lookup decision, the desk scripts and
the send checks; the chat's state with no I/O (`ChatCore`) moved there from
`kudde-mobile` as `kudde_chat::core` (task 1); `kudde-mobile`'s `chat.rs`
does the calls and the send sequencing (`Sent`, the second Enter, the `/`
and dialog waits); Android's `ChatPending.kt` holds the pending-message
states and `ChatMarkdown.kt` parses Markdown blocks. The desk side is the
herdr app, whose mods are JavaScript run by QuickJS with gpui-kit for the
UI. A mod can run commands on any machine herdr-core knows
(`exec(cmd, args, {on})`, no stdin, 8 MiB output), call `hc`, write files
under the app's folder (`fs/promises`, `Buffer`) and show them with
`image(path)`. QuickJS has no WebAssembly.

The research is summarised in `research/desktop-chat-2026-11-19/BRIEF.md`
(the raw reviews are kept locally, not tracked). A council review of the
first draft (2026-11-19) corrected the reading schedule, the gallery shape,
sending and several API assumptions. Two review rounds on the amended spec
followed (R1 and its scrutiny). The spike then measured the planned route,
Rust converted to JavaScript through wasm, and it failed every limit;
Ezra chose the fallback on 2026-11-20 (decision 7): **the desk carries its
own JavaScript copy of the chat rules**. This design is written around that
choice. The measurements are in `research/desktop-chat-spike-2026-11.md`.

## Goals / Non-Goals

**Goals:** one set of chat rules for phone and desk, kept in Rust for the
phone and in a JavaScript copy for the desk that an exact comparison in CI
holds equal to the Rust; a desk chat that reads, updates live, shows
pictures and sends replies; agents on Linux (WSL included), seen from the
herdr app on Windows, Linux or macOS; nothing installed on any machine but
the mod folder; a gallery release through a separate repository.

**Non-Goals:** answering prompts from the chat; attaching pictures from the
desk, the context gauge, typed-in answers, `/` completion; screen reader
semantics (gpui-kit exposes none yet; keyboard access is in scope); agents on
macOS (`chat-on-mac`) or on Windows itself; a helper program on the agent
machines (decision 7 dropped it); running Rust in the mod.

## Decisions

### D0. The gallery question is answered by a survey, not a test listing
A survey of the gallery (`research/desktop-chat-2026-11-19/gallery-survey.md`)
found entries of deleted repositories still listed, so no test entry is
published: the first listing is the real one, made when the mod is ready.
The survey's other worry, a large generated file, no longer applies: the
mod is handwritten JavaScript. Task 7.2 still checks an install from a
local copy of the gallery folder before release.

### D1. What the spike settled, and what it left
Settled (tasks 2.1 to 2.5, all measured under the `qjs` command line, not
inside the app):
- The wasm route fails. The real `ChatCore`, built for wasm32, lowered with
  `wasm-opt` and converted with `wasm2js`, took at best 3,033 ms for a 2 MB
  first open and 575 ms for a 6 KB update, against 300 ms and 50 ms; capped
  to a 512 KB first read, still 852 ms and 158 ms. Compiler flags and bridge
  tricks did not close the gap: the costs are the core's work plus
  rebuilding and serializing the whole view on every call. Confidence: high
  for these measurements.
- A JavaScript reader works. `desk/spike/reader.js` on the spike branch,
  435 lines, mirrors the 1,803 lines of Rust parser and state
  (`lib.rs`, `core.rs`) and gives exactly the Rust core's view on 194
  inputs: both agents' fixtures, every complete log literal in the Rust
  sources and tests, split reads, older loads and state actions. Its
  timings: 2 MB first open 318–373 ms (one outlier at 703 ms under load),
  a 6 KB update on that history 5 ms, an older page about 560–600 ms, the
  largest catch-up (7,864,320 bytes) about 1.6–1.7 s. Confidence: high for
  the samples, moderate for how they repeat.
- Decision 7 is locked: the JavaScript copy. The program on each agent
  machine is dropped.

Left, because it needs the app on the Windows desk with Ezra at the screen
(tasks 2.6 to 2.11): the UI probes (key-encoded clicks, at-bottom tracking,
`scroll_to_bottom` over 20 growing updates), one picture through the cache
(D7), a live update without a click and whether a hidden pane gets a
signal, the reader's timings inside the app's QuickJS (the command line and
the app share no measured calibration), the send results per route and the
hostile round trip (D4), and what a native Windows machine returns (D12).

A UI or image failure needs an owner decision about its own repair; a
different reader would not fix it.

### D2. The chat core in `kudde-chat`, times in milliseconds
Done in task 1, kept here as the rule the desk's copy follows.
`chat_core.rs` and its tests moved to `kudde-chat/src/core.rs`
(`kudde_chat::core`). Every `Instant` became a `u64` of milliseconds on a
clock the caller supplies, and every difference uses `saturating_sub`, so a
clock that goes back never panics or wraps; a test drives a backwards clock.
`Held<T>` stayed in `kudde-mobile`. The phone passes milliseconds since a
fixed `Instant` taken at hub start.

The desk's clock is `max(last, Date.now())`, so it never goes backwards.
Clamped subtraction alone prevents underflow but would not keep the three
timers advancing after a backwards jump.

### D3. The send and pending decisions move into Rust, as the reference
The pure parts of sending move into `kudde_chat::send`:
- status to guard, one to one as `check_again` returns it (`send.rs:60-77`):
  idle → `--if idle`, working → `--if working`, done → the pid, process
  start and session re-check, then no guard (herdr-core has no `done`
  guard; a 1.x issue asks for one);
- the outcome `Sent::{Typed, Unsure, Refused}`: Unsure (a timeout) is never
  resent;
- the Enter-again rule and the `/` command and dialog waits from `chat.rs`;
- `ChatPending.kt`'s states ("Sending…", "Queued", "Not in the conversation
  yet" after 30 s, "Sent to the terminal");
- stripping control characters other than newline and tab, CR included, so
  CRLF becomes LF. A raw `\x03` or `\x1b` is not text the agent's box
  accepts from typing; it would arrive as a key (interrupt, escape).
  Everything typeable, `/` and `!` included, still goes as typed.

With decision 7 the desk cannot call this code; it carries a copy (D10).
The move still pays: today these rules are split across `chat.rs`,
`ChatPending.kt`, `ChatState.kt` and `ChatRoute.kt`, and the desk's copy
needs one reference to be compared against, not four files in two
languages. The phone gets one place for its fixes.

Canonicalization (removing controls to get the text that will be sent)
happens once, before pending creation or `/` and `!` classification.
Pending bubbles, log/queue matching and input-box matching use that
canonical text; transport-only quoting or image-path prefixes are added
afterwards and never change pending's text. The core's `sent()` receives
that same cleaned text; the space after a final `@` word is added only for
transport and ignored in matching. Keep the original draft and attachments
separately for a definitive refusal, using the phone's existing merge with
anything typed meanwhile.

**Owner pick R1-B (proposed):** text that becomes blank after normalization,
with no attachments, leaves the original draft in the box, creates no
pending bubble and dispatches nothing. An attachment-only message keeps the
phone's existing path. Alternative: show a new refusal for blank text.

The shared send module owns pure screen checks, Claude queue-text matching,
Codex screen-queue detection, slash/dialog waits and per-message second-Enter
state; callers supply agent kind, canonical text, image count, row anchor,
status, screen lines and caller-clock milliseconds. The platform adapters
read the screen and execute actions; they do not duplicate the decisions.
Retain the displayed target through uploads and the final check, and retain
that target for a second Enter. Keep the phone's guarded space-send rule,
once per message, with fresh checks; never replace it with bare keys.

The phone calls these through uniffi; `ChatPending.kt` keeps only drawing.
D2 and D3 ship as one phone release, its notes naming the control-character
change. The release does not wait for the desk, and the desk's send copy
(task 4.6) waits for this module, not for the release.

### D4. The desk calls `hc` and the same scripts as the phone
Terminal facts come from `hc get <terminal> --json` through the mod's
`hc()`; its fields are checked strictly, and an unknown shape shows "This
herdr version is not supported yet" with "Open terminal". The scripts run as
`exec("sh", ["-c", SCRIPT, "sh", ...args], {on: machine})`. The mod holds
the script texts in its own module; the parity suite checks them byte for
byte against the Rust constants (`locate::SCRIPT`, the tail script,
`image::FILE_SCRIPT`), so desk and phone run the same text. A send is
`hc send <t> [--if <guard>] -- <text>` after the D3 re-check, which compares
the immutable displayed target (terminal, agent kind, pid, process start and
session). Both first and final checks read fresh facts: Claude uses its
session file and process facts; Codex reads open rollout and lock files with
their times (`/proc` on Linux, `lsof` on Darwin), using the lookup decision
again. Missing, unreadable or ambiguous facts refuse before dispatch, never
reuse the periodic lookup. These checks narrow but do not remove the gap
before typing; herdr-core's status guard is not an atomic session guard.

Refusal on uncertain identity and keeping an unsure send pending are already
settled by `openspec/specs/chat-view/spec.md`, requirement "Sending goes only
to the agent whose log is shown". They are not owner questions.

The desk adapter returns `Typed`, `Unsure` or `Refused` using the results
recorded in task 2.10. `Typed` needs the recorded success result, not
merely exit zero. Local failures before dispatch are `Refused`. Identifiable
`status_mismatch`, `blocked`, `finished` and `not_found` rejections that
herdr-core returns before typing are `Refused`, restoring the draft as on
the phone. `unavailable` is `Refused` only when the recorded result proves
rejection before any input; otherwise it is `Unsure`. Timeout, disconnect,
malformed output and unidentified failures after dispatch are `Unsure`.
Exit status, stderr or a rejected promise alone cannot prove nothing was
typed. Apply this mapping to the route selected by the probe, never retry a
send through the other route. Unsure never restores the draft or triggers
another send or second Enter.

**Owner pick R1-G (conditional, after task 2.10):** if neither route lets
the desk tell a guard refusal (for example "the agent just started
working") from a lost answer, either desk replies wait until herdr-core
reports refusals in a form the desk can read, which needs an upstream change
that may take long or never come (`decisions.md`, 2026-10-31: maintainers
rarely answer), or desk replies ship and such a refusal looks unsure: the
bubble stays, reads "Not in the conversation yet" after 30 s, and Ezra
retypes the message. Pick: ship; the desk recognises herdr's refusal text
wherever the probe shows it, and only the rest looks unsure. Confidence:
moderate. Do not invent error shapes or silently change refusals.

If the probe finds Windows argument passing unsafe, the text goes base64 as
an argument to a fixed script on the agent machine that decodes `"$1"`,
re-checks and runs `hc send` there; the text is never part of the script.
Whether the desk needs the Enter-again rule follows the probe's finding.

### D5. When the desk reads, and how it stays within the limits
The product limits stand: at most 300 ms of window-thread work in total on
first open, at most 50 ms for any single synchronous step, and one record
larger than the byte budget may take up to 300 ms. Desk budgets are
configurable; the phone's values are unchanged.

**Budgets live in the Rust core too**, so every configuration the desk
ships has a reference (D10). `ChatCore::with_budgets(first, older, retain)`
takes them; `new()` keeps today's values (`FIRST_BUDGET`, `OLDER_BUDGET`, no
retention), so the phone does not change and its tests pass unchanged.
`trim_to(bytes)` reuses `leave()`'s trimming; the phone never calls it. The
view gains a revision in the Rust `View`: one conservative bump per
state-changing call that took input, and a sliced read bumps once, when its
last slice completes, so sliced and whole reads give the same revision. It
may bump without a visible change and never misses one. The phone copies
`View` field by field into its own type and ignores the revision; neither
uniffi nor Kotlin changes. Nothing in the desk's view is desk-only, so the
parity suite strips nothing.

How the JavaScript reader meets the limits, from the spike's numbers (about
0.15–0.19 ms per KB under the command line) and the round-3 review's
measurements of the same reader:
- **First open.** The desk reads the same 2 MB as the phone
  (`FIRST_BUDGET`). The spike's reader split the tail output with a regular
  expression and trimmed line ends with another; plain string operations
  instead (task 4.3, under the parity suite) brought a 2 MB open from about
  295 ms to about 143 ms under `qjs`. Task 2.9 times the whole first open in
  the app; only if 2 MB then misses 250 ms is the desk's first read lowered,
  and the owner is told how many fewer messages that shows. If even 512 KB
  misses 300 ms, that is a failure for the owner, not a budget to tune.
- **Updates.** A 6 KB update measured 5 ms on a 2 MB history. The
  JavaScript view is a plain object shared with the pane, so nothing is
  serialized or decoded; the wasm route's main cost is gone. The cost grows
  with held history (about 29 ms at 16 MB before the measures below);
  row reuse and the retention budget keep it bounded.
- **Older messages and large catch-ups.** The core's rules reparse all held
  lines when older ones load (about 560–600 ms for 4 MB), and the largest
  catch-up read is 7,864,320 bytes (about 1.7 s). Both run in slices,
  yielding to input and other panes in between. An older load builds its
  new state aside, key keeping included, in slices; the swap itself is an
  assignment, so no half state is drawn and no single step is long. The view
  after slices equals the view of one call; the parity suite checks this on
  sliced inputs.
- **Long-open panes.** A desk pane may stay open all day on a busy agent.
  Three build measures keep every step bounded, all under the parity suite
  (output identical, only cost changes):
  1. key keeping (`keepKeys`) parses held offsets once and looks rows up by
     offset: linear in rows, not rows times held rows;
  2. retention applies while the log grows. A trim is due only when held
     history passes a high mark (starting at 8 MB, set from task 2.9) and
     the pane follows the bottom; the pane passes that "at bottom" flag into
     the core's trim-due rule as an input. It trims down to a low mark (the
     first-read budget, 2 MB), so a reparse happens once per ~6 MB of
     growth, not on every update; the reparse runs aside in slices and is
     swapped in. Older pages the user loaded are dropped then like any other
     history above the low mark, as the phone's `leave()` does. The rule
     lives in the core, in Rust (`with_budgets`' `retain`, with both marks)
     and in the JavaScript alike, so the parity suite checks it. The rows
     dropped are ones the user is not looking at, and they come back on
     scroll as older messages do;
  3. the view reuses unchanged row objects through the parser's per-row
     touch tracking (`touch()`, `sources`), so an update copies only the
     rows it changed.
- **Slices.** A read's slices are cut by a byte budget calibrated in task
  2.9 (the spec's large-record exception refers to this configured byte
  budget), and all slices of one read finish within one round of the pane's
  loop before the next read is planned.
- **One large record.** Claude Code embeds pictures as base64 inside
  records. The review measured a 7.8 MB record at 0.8–1.1 s with the
  spike's reader, and 176–437 ms after the line split and trim above. The
  next lever is any `parseJSON` change that keeps the parity suite's integer
  and float edges, for example `JSON.parse` with source-text access if the
  app's engine has it (7 ms, against about 375 ms for the current number
  pass). Task 2.9 times records of 1, 4 and 7.8 MB in the app; a record
  still over 300 ms goes back to the owner (for example, show it as "Some
  messages can't be shown" instead of parsing it).

Checking an unchanged chat costs almost nothing: the pane compares the
view's revision and rebuilds rows only when it changed.

One async loop per pane does all its work, so a read never overlaps another;
each round carries a generation and a stale reply is dropped. The pane reads:
- on opening, and on a status change of its agent;
- every 1 s while the agent works, for 7 s after it stops working
  (`TURN_GRACE` plus 2 s, because the final record is written about a second
  after the turn ends and the core judges the turn's end on lines read after
  it), and while a sent message is pending;
- every 5 s otherwise, as a size probe of the log (`stat`), reading the tail
  only when the size changed; with the 10 s lookup (`RELOCATE`) as now.
A hidden pane keeps this schedule, which still costs one remote round trip
every 5 s; if task 2.8 finds a hidden signal, a hidden pane probes every
15 s instead. Round latency only slows reading: after three rounds over
1.5 s, at most one read every 3 s, until a round is fast again. Time spent on
the window's thread is the only thing that may stop a pane: ten reads in a
row whose longest synchronous step exceeds 50 ms show
"The chat is paused: it was slowing the window" with
"Open terminal". Total time across responsive steps does not count as
one long step. The single-large-record exception still counts toward this
backstop.

### D6. Rows, clicks and scrolling
The chat column is gpui-kit's `list`, which measures each row. Handlers
inside a `list` row throw, so every click goes through `on_item_click` with
keys that name the part (`<row key>#fold`, `<row key>#img2`), and fold state
lives in the pane, not in the row. `list` does not check key uniqueness, so
the pane builds keys from the reader's row keys only. The scroll handle has
no position getter: the pane tracks "at the bottom" from wheel and scrollbar
events and its own `scroll_to_bottom` calls. If the probes in task 2.6 fail,
the fallback is a scroll column of the last 200 rows with a "Show earlier
messages" row, and the spec's scroll scenarios are reworded from what the
probe found before task 5 starts.

### D7. Pictures go through a cache file
A base64 variant of `image::FILE_SCRIPT` caps the raw file at 6,000,000 bytes
(`base64 -w0`; about 8 MB of output, under `exec`'s 8 MiB); a larger file
shows "Image not available". The mod decodes with `Buffer`, checks the magic
bytes (PNG, JPEG, GIF, WebP), and writes `data/.kudde-images/<hash>.<ext>`
(a hidden folder, so the save does not reload mods), shown with that
app-home-relative path. The key hashes the session and the image reference
(a Codex image: path and file time, as on the phone). `fs/promises` has no
`stat`, so `data/.kudde-images/index.json` records each file's size and last
use; index writes retry on a stale snapshot (three tries, reading again in
between). Eviction runs after a write, keeps the cache at 200 MB, deletes
only names in the index, and skips files used in the last minute. A click
opens the picture in a dialog overlay at window size.

### D8. Markdown
There is no code-block splitter on the phone to move: `ChatScreen.kt` calls
`markdownBlocks` in `ChatMarkdown.kt`, a line-based parser for headings,
paragraphs, list items, fenced code, tables and quotes, with Compose inline
styling. The earlier text of decision 6 and D3 named a splitter that does
not exist (scrutiny round 2, N1).

**Owner pick N1 (proposed):** the phone keeps `markdownBlocks` unchanged;
the desk gets a small JavaScript function, `fences(text)`, that splits prose
from fenced code by the phone's fence rule (a fence may start after leading
whitespace; an unclosed fence runs to the end), with the fence cases of
`ChatRowsTest` copied as its tests. Prose goes to `TextView.markdown`; code
is drawn as monospace blocks on `theme().raised`; tables show as monospace
text in this change. Alternative: port the whole parser to Rust and have
the phone call it. Under decision 7 that gives the desk nothing (it would
still need a JavaScript copy) and puts the phone's goldens at risk in the
phone release. Confidence: high that the alternative costs more; the pick
leaves two Markdown readers that could disagree on a fence.

### D9. A waiting agent sends you to the terminal
While the agent's status is `blocked`, or it reports no status, the reply box
is replaced by "Waiting for you in the terminal" and "Open terminal", which
runs `pane.focus {terminal}`. A draft typed before stays and comes back.

### D10. The JavaScript copy, and the comparison that keeps it equal
`desk/kudde/chat/` holds the desk's copy of the chat rules as plain ES
modules with no gpui-kit import:
- `reader.js`: the parser, retained conversation, keys and view, grown from
  the spike's reader (it mirrors `lib.rs` and `core.rs`);
- `lookup.js`: the lookup decision on the scripts' raw output
  (`locate::lookup` and `decide`);
- `send.js`: the send decisions of D3, once `kudde_chat::send` exists;
- `scripts.js`: the script texts of D4;
- `fences.js`: D8's fence split.

The copy covers about 2,400 lines of Rust rules (`lib.rs` 1,233, `core.rs`
570, `locate.rs` 258, `tail.rs` 150, `send.rs` 96, `image.rs` 58, counted on
the spike branch), much of the last four being shell text that is shared
verbatim rather than copied. D3's move adds the logic now in `chat.rs` and
`ChatPending.kt`. The spike's reader shows the size of a copy: 435 lines
for the 1,803 lines of parser and state.

**The contract is an exact comparison.** `crates/kudde-chat-oracle` is a
small native binary, built from the spike's vehicle, never shipped
(`publish = false`), that runs the real Rust core, lookup decision and send
decisions on JSON requests and prints the script texts. `kudde-chat` gains
a `serde` feature with `Serialize` on the view types for it. The parity
suite, grown from the spike's harness, runs every input through the oracle
and through the JavaScript copy under a pinned `qjs`, and compares the whole
replies: no removed fields, no tolerance, no sorting; only the order of
members inside a JSON object is ignored. Its inputs:
- every fixture under `crates/kudde-chat/tests/fixtures` and every complete
  log literal in the crate's sources and tests, lifted automatically;
- the Codex ordering sequences, split reads at widths 1, 2, 3, 7 and 4096,
  older loads, sliced reads (D5), and the state actions (session changes,
  exits, turn grace, lookup clocks, leave and return);
- recorded lookup script outputs, including Missed, None and ambiguous;
- the send scenarios: guard per status, the Enter-again rule, `/` and
  dialog waits, the pending states on a driven clock, canonical text and
  blank input;
- the language edges the spike handled: integers above 2^53, floats such as
  `1.0` and negative zero, UTF-8 split inside a character, invalid UTF-8,
  lone surrogate escapes, sorted map keys.

The oracle takes the budgets and the `trim_to` action, and the suite runs
the whole corpus twice: at the phone's values and at the desk's (D5).

A difference fails CI. A new log shape gets a fixture first; both readers
change in the same pull request. The suite cannot prove the two equal on
formats nobody has recorded; D13 makes unknown records visible to users.

**Which engine.** The app embeds quickjs-jit
(`research/source-findings-2026-10-31.md`); CI runs a pinned QuickJS-ng.
Exactness depends on engine built-ins: regular expressions on surrogates,
number formatting, `JSON.parse`, `BigInt`. So task 2.9 records the app's
engine name and version and runs the parity corpus inside the app, through
a dev-only command of the minimal mod, against the oracle's expected replies
bundled as golden files; it needs 0 differences. The dev command and the
golden files live in `desk/kudde-dev/`, outside the folder a release copies
to the gallery, and task 7.2 checks they are absent from the gallery copy.
Task 5.10 repeats the in-app run before each release. `qjs` in CI is pinned
to the closest available match; if the app's engine builds headless, a CI
job on that exact engine replaces or joins it.

The pane holds a reader per chat. A JavaScript exception in one chat's
reader drops that reader; the pane rebuilds it from a fresh lookup and
re-reads its rows. Other chats are not affected.

### D11. Where the mod lives, how it is shipped
The integration milestone is desktop-chat task 3.6 and sections 4–5
complete, with tests passing; archival is not a prerequisite for Mac work.
`chat-on-mac` writes its desktop delta against this change's requirements
before joint release. Apply the base desktop requirements, then the Mac
modifications; finish chat-on-mac tasks 3.1–3.3 and both reviews/validations,
then section 7, then archive desktop-chat followed by chat-on-mac.

`desk/kudde/` holds `mod.json`, `main.js`, the pane modules and `chat/`,
with `"focusable": true`: Tab reaches the reply box and "Open terminal",
Enter toggles a focused fold. Nothing is generated or built: a release
copies the folder to the gallery repository as it is. Its `RECIPE.md`
says: if the mod fails on a newer herdr version, report it with a link to
this repository's issues; the mod runs programs on your machines (`sh`,
`hc`), reads agent logs and pictures outside its folder, and fetches a
version file once a day. It names the tested herdr version. The preview
and RECIPE follow the public-repository rules (staged agents, invented
names, no Mac screenshots).

### D12. Machines the chat cannot read, or cannot reach
The mod reads the machine's state from `machines()` first: while `loading`
it waits; `unavailable` shows "Can't reach <machine>; retrying". On a
`ready` machine it runs `uname -s` once and caches only a success. `Linux`
is supported. `Darwin` shows "kudde's chat can't read agents on a Mac yet."
until `chat-on-mac` lands. A confirmed Windows machine shows
"kudde's chat can read agents on Linux.
This agent runs on Windows itself; agents in WSL work." For another unsupported
system, or if `sh` cannot start and Windows is not established, show "kudde's
chat can read agents on Linux. This machine is not supported." Each has
"Open terminal".

Windows is established by, in order: an operating-system field in
`machines()` if it has one; `uname -s` starting with `MINGW`, `MSYS` or
`CYGWIN`; `exec("cmd", ["/c", "ver"])` succeeding when `sh` cannot start.
Task 2.10 records what a native Windows machine returns to each and keeps
only the signals that work.

### D13. Drift and updates
Gallery copies never update themselves. The reader counts records and
blocks it does not know over everything the chat holds (the parser's running
`unknown` against `lines`); at three or more and over 5%, the pane shows
"Some messages can't be shown" with "Open terminal", building on the
Degraded health (`lib.rs:325`). Once a day the mod fetches a version file
from the gallery repository and, when its own version is older, shows "A
newer kudde chat is available". A weekly CI job compares the latest Claude
Code and Codex versions with `fixtures/VERSIONS` and opens an issue for a
new one; its fixture is recorded on the desk by hand. A fix for a new
format lands in the Rust and the JavaScript in the same pull request (D10).

## Testing

- The core's and send's tests stay Rust tests, plus a backwards-clock test
  (D2, D3).
- The parity suite (D10) under a pinned `qjs` in CI, at the phone's and the
  desk's budgets, on every pull request that touches `crates/kudde-chat`,
  `crates/kudde-chat-oracle`, `desk/`, the fixtures, `Cargo.lock`, the `qjs`
  pin or the harness scripts, as part of `ci-ok`; and inside the app before
  each release.
- The mod's logic (when to read, single flight, back-off, slicing and
  yielding, the waiting notice, machine states, argv building and
  stripping, the cache index, the clock wrapper) sits in plain modules with
  no gpui-kit import, tested under `qjs` with a fake `hc`, `exec` and clock
  (`now()` passed in). `fences.js` carries the phone's fence cases.
- Manual, on the Windows desk before each release and recorded: theme
  switching, click to enlarge, opening twice, keyboard access, the hostile
  message round trip through the real `hc()` and `exec`, first-open and
  step timings against D5's limits, and the parity corpus in the app.
- `herdr-app check desk/kudde` passes before any try in the app.

## Risks / Trade-offs

- **Two copies of the rules** (high confidence this is the main cost): every
  log-format fix lands twice. The exact comparison in CI catches a copy
  that falls behind on any recorded shape, not on shapes nobody recorded.
- **QuickJS speed in the app** (unknown until task 2.9): the command-line
  numbers do not transfer. A 2 MB first open measured about 143 ms with the
  line-split fix, under `qjs`.
- **Engine mismatch**: CI proves parity on QuickJS-ng; the app ships
  quickjs-jit; the in-app corpus run (D10) before each release covers it.
- **One large record**: 176–437 ms at 7.8 MB with the line-split fix, under
  `qjs`; see D5; the owner decides if one still exceeds 300 ms in the app.
- **Log drift for gallery users**: copies do not update; D13 makes it
  visible to them and to us.
- **Windows process arguments**: tested in task 2.10 and before each
  release; a base64 fallback exists.
- **A send to a done agent** has no herdr-core guard, as on the phone (#71).
- **A gallery listing cannot be reliably taken down** (D0): the listing
  waits until the mod is ready, and its text is shown to Ezra first.

## Open Questions

- The key that opens the chat: one free in the default `keys.json` on all
  three platforms (task 5.1); proposed to Ezra with the pane's texts.
