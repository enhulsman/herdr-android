# Design: kudde's chat in the herdr desktop app

## Context

The phone's chat (`chat-view`, archived 2026-11-16) is split in layers:
`kudde-chat` holds the parsers, the lookup decision, the desk scripts and the
send checks; `kudde-mobile/src/hub/chat_core.rs` holds the chat's state with
no I/O; `chat.rs` does the calls and the send sequencing (`Sent`, the second
Enter, the `/` and dialog waits); Android's `ChatPending.kt` holds the
pending-message states and `ChatScreen.kt` splits fenced code blocks. The
desk side is the herdr app, whose mods are JavaScript run by QuickJS with
gpui-kit for the UI. A mod can run commands on any machine herdr-core knows
(`exec(cmd, args, {on})`, no stdin, 8 MiB output), call `hc`, write files
under the app's folder (`fs/promises`, `Buffer`) and show them with
`image(path)`. QuickJS has no WebAssembly, so Rust reaches the mod only as
JavaScript.

The research is summarised in `research/desktop-chat-2026-11-19/BRIEF.md`
(the raw reviews are kept locally, not tracked). A council review of
the first draft of this change (2026-11-19) found the reading schedule,
the gallery shape, sending and several API assumptions wrong; this design is
the corrected one.

## Goals / Non-Goals

**Goals:** one chat core, including the send decisions, for phone and desk;
a desk chat that reads, updates live, shows pictures and sends replies;
agents on Linux (WSL included), seen from the herdr app on Windows, Linux or
macOS; nothing installed on any machine but the mod folder; a gallery
release through a separate repository.

**Non-Goals:** answering prompts from the chat; attaching pictures from the
desk, the context gauge, typed-in answers, `/` completion; screen reader
semantics (gpui-kit exposes none yet; keyboard access is in scope); agents on
macOS (`chat-on-mac`) or on Windows itself; a desk helper program or sidecar
(unless decision 7 calls for one).

## Decisions

### D0. The gallery question is answered by a survey, not a test listing
A survey of the gallery (`research/desktop-chat-2026-11-19/gallery-survey.md`)
found listed mods shipping generated files of 1.6 to 6.2 MB, one with a
RECIPE that says to rebuild from its source repository, and found entries of
deleted repositories still listed. So no test entry is published: the first
listing is the real one, made when the mod is ready. Whether an installing
agent copies a large generated file without trouble is checked on the desk
before release (task 7.2), not before the spike.

### D1. A spike decides the route
The isolated core move and its commit (tasks 1.1–1.3) come first. Preserve
all assertions and keep `Held` and its tests mobile-only. The measurement
harness in 2.2 requires that move; it does not require the production bridge.

**Owner pick R1-F (proposed):** keep the send, pending, markdown and phone
release work after the spike (section 3). Alternative: allow the pure send
and splitter moves alongside it. Pick: keep spike-first now that it measures
rather than builds. Confidence: moderate.

On a throwaway branch `spike/desktop-chat`, build a small wasm32 `cdylib`
harness around the actual moved `ChatCore`, with alloc/free, raw-script,
read, older-page and JSON/view calls as in D10. A parser-only timing is not
enough. Lower stable Rust's wasm32 features (sign-ext, nontrapping-fptoint,
bulk-memory and multivalue) with `wasm-opt`, convert with
`wasm2js`, and run in a minimal mod on the Windows desk. Record the flags.
Measure the existing core without first building resumable parsing, paged
views or a shared queue. The harness is not production bounding work.

Record time per MB and worst single-call time for parsing, retained-history
reparsing, changed and unchanged views, JSON encoding/decoding, a 2 MB first
open, the full 7,864,320-byte incremental read, older pages, maximum-size
single records and multiple panes. Record sizes and total window-thread time
as well as the longest step; exclude remote waiting time. Derive candidate
first-read, incremental-read, older-page and retained-history budgets from
these measurements, including a real 512 KB first-read configuration.
Re-run with those budgets where the existing core permits it. No invented
budget or unfinished parser counts as a passing result.

The performance target is at most 300 ms total window-thread work for first
open, and at most 50 ms for any single synchronous step. One record larger
than the measured byte budget is the sole exception: its step may take up
to 300 ms, including on later reads. D5's approach remains an owner choice,
waiting for these measurements. The first stand-in timings were about 6–8 s
for a 2 MB first open and over 1 s per update; these do not establish that
QuickJS in the mod meets the target. No pass is assumed. Confidence: low on
feasibility until measured in the actual mod.

Also check:
- a real Claude Code session from WSL shows with a picture and updates
  without a click; `core.js` is at most 1.5 MB after `-Oz` and stripping;
- a fold opens, the second thumbnail enlarges, and the chat stays at the
  bottom through 20 growing updates (D6);
- a cached PNG shows within one render, survives mod reload, and writing
  under `data/.kudde-images/` does not reload mods (D7);
- hostile messages round-trip through both real `hc()` and fixed-script
  `exec`: CRLF, quotes, trailing backslash, `%`, emoji, leading `-`, 30 KB,
  shell substitutions, backticks, delimiters and trailing newlines. Compare
  exact received text after control stripping; blank input dispatches nothing;
- what each route actually returns for success, `status_mismatch`, `blocked`,
  `finished`, `not_found`, `unavailable` and timeout: values, exit status,
  stdout, stderr and thrown/rejected errors. Note which failures prove no
  input, including whether `unavailable` can occur after typing. Missing
  evidence is recorded as unknown, never replaced with an invented shape;
- `hc` on the agent machine's PATH, whether a second Enter is dropped,
  round latency, and whether a hidden pane gets a signal.

Findings go to the owner before dependent work. Decision 7 still applies to
failed spikes: size/speed evidence informs the parser/program choice; a UI
or image failure also needs an owner decision about its specific repair,
because changing parser routes alone does not fix it. Do not route a failure
to decision 7 merely because production bounding was not built in the harness.

### D2. The chat core moves into `kudde-chat`, times in milliseconds
`chat_core.rs` and its tests move to `kudde-chat/src/core.rs`
(`kudde_chat::core`), with the moved types made `pub`. Every `Instant`
becomes a `u64` of milliseconds on a clock the caller supplies, and every
difference uses `saturating_sub` (`chat_core.rs:199, 308, 374` today), so a
clock that goes back never panics or wraps; a test
drives a backwards clock. `Held<T>` stays in `kudde-mobile` (phone only).
The phone passes milliseconds since a fixed `Instant` taken at hub start.
The desk must supply a clock that never goes backwards; clamping subtraction
alone prevents underflow but does not keep its three timers advancing.
The move changes no behaviour: the moved tests pass apart from the clock
type, and the hub's tests pass untouched.

### D3. The send and pending decisions move too
After D2, the pure parts of sending move into `kudde_chat::send`:
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

Canonicalization (removing controls to get the text that will be sent)
happens once, before pending creation or `/` and `!`
classification. Pending bubbles, log/queue matching and input-box matching
use that canonical text; transport-only quoting or image-path prefixes are
added afterwards and never change pending's text. The core's `sent()` receives
that same cleaned text; the space after a final `@` word is added only for
transport and ignored in matching. Keep the original draft
and attachments separately for a definitive refusal, using the phone's
existing merge with anything typed meanwhile.

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
The code-block splitter moves from `ChatScreen.kt` into
`kudde_chat::markdown` with its tests, and Kotlin calls it. D2, D3 and the
splitter ship as one phone release, its notes naming the control-character
change, before production desk tasks depend on them; the measurement harness can use the isolated core move before that release.

### D4. The desk calls `hc` and the same scripts as the phone
Terminal facts come from `hc get <terminal> --json` through the mod's
`hc()`; its fields are checked strictly, and an unknown shape shows "This
herdr version is not supported yet" with "Open terminal". The scripts run as
`exec("sh", ["-c", SCRIPT, "sh", ...args], {on: machine})`; `kudde-desk`
hands them to the mod, so desk and phone run the same text. A send is
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
recorded in spike task 2.6. `Typed` needs the recorded success result, not
merely exit zero. Local failures before dispatch are `Refused`. Identifiable
`status_mismatch`, `blocked`, `finished` and `not_found` rejections that
herdr-core returns before typing are `Refused`, restoring the draft as on
the phone. `unavailable` is `Refused` only when the recorded result proves
rejection before any input; otherwise it is `Unsure`. Timeout, disconnect,
malformed output and unidentified failures after dispatch are `Unsure`.
Exit status, stderr or a rejected promise alone cannot prove nothing was
typed. Apply this mapping to the route selected by the spike, never retry a
send through the other route. Unsure never restores the draft or triggers
another send or second Enter.

**Owner pick R1-G (conditional, waiting for spike results):** if either route
cannot distinguish guard refusals, require structured errors before shipping
desk replies. Alternative: accept that those sends look unsure and the draft
does not return. Pick: preserve phone behaviour; no external work is authorized
here. Confidence: moderate. Record the gap and return it to the owner before
building the adapter; do not invent error shapes or silently change refusals.

If the spike finds Windows argument passing unsafe, the text goes base64 as
an argument to a fixed script on the agent machine that decodes `"$1"`,
re-checks and runs `hc send` there; the text is never part of the script.
Whether the desk needs the Enter-again rule follows the spike's finding.

### D5. When the desk reads
**Owner pick R1-D (open, waiting for spike speed measurements):** keep at
most N MB of history in desk memory, with older messages reloaded on scroll;
choose N and read/page sizes from the measured costs. A single giant record
may take up to 300 ms. Alternatives: build an incremental parser so even
large records yield, or return to decision 7's program off the window thread.
Pick: try capped retention first if measurements support it. Confidence: low
until measured. This changes which history stays in memory, not which
messages can be read; pictures remain supported.

The product limits are 300 ms total window-thread work on first open,
50 ms per synchronous step, with only a single over-budget record allowed
up to 300 ms. Keep desk read and retention budgets configurable and phone
defaults unchanged. Backlog and older messages can appear over several steps,
without lost or duplicated rows, yielding to input and other panes between
steps. Checking an unchanged chat should cost almost nothing. Build and test
the chosen bounding once in production (tasks 4.2–4.4), not in the spike.
The spike records a failure or unresolved feasibility if its measurements
cannot support the limits; it does not have to implement the alternatives.

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
every 5 s; if the spike finds a hidden signal, a hidden pane probes every
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
the pane builds keys from the core's row keys only. The scroll handle has no
position getter: the pane tracks "at the bottom" from wheel and scrollbar
events and its own `scroll_to_bottom` calls. If the spike's probes fail, the
fallback is a scroll column of the last 200 rows with a "Show earlier
messages" row, and the spec's scroll scenarios are reworded from what the
spike found before task 5 starts.

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
Prose goes to `TextView.markdown`. Fenced code blocks come from the shared
splitter (D3) and are drawn as monospace blocks on `theme().raised`. Tables
show as monospace text in this change.

### D9. A waiting agent sends you to the terminal
While the agent's status is `blocked`, or it reports no status, the reply box
is replaced by "Waiting for you in the terminal" and "Open terminal", which
runs `pane.focus {terminal}`. A draft typed before stays and comes back.

### D10. The JSON boundary
`kudde-desk` exports `kd_alloc(len)`, `kd_free(ptr, len)` and
`kd_call(ptr, len) -> ptr`; requests and replies are UTF-8 JSON, the reply
prefixed with its 4-byte length, and `bridge.js` calls `kd_free` on every
reply, error paths included. The exports are compiled for wasm32 only, so
host and Android `--workspace` builds stay green (`#[unsafe(no_mangle)]`,
edition 2024). Requests carry `{"v": 1}`; a mismatch is an error the pane
shows. Methods take the scripts' raw output (the core already parses it:
`locate::lookup`), never pre-parsed Rust types. The view types derive
`Serialize` behind a `serde` feature of `kudde-chat`. `view()` carries a
revision, and the mod asks for rows only when it changed. `bridge.js`
takes a fresh view of the memory after every call (memory can grow), uses
`Buffer` for UTF-8 if the spike confirms it, and replaces lone surrogates
before encoding. A trap (a Rust panic) discards the whole module instance:
the bridge loads a fresh one and rebuilds every chat it held from a fresh
lookup, re-reading their rows.

### D11. Where the mod lives, how it is built and shipped
The integration milestone is desktop-chat task 3.6 and sections 4–5
complete, with tests passing; archival is not a prerequisite for Mac work.
`chat-on-mac` writes its desktop delta against this change's requirements
before joint release. Apply the base desktop requirements, then the Mac
modifications; finish chat-on-mac tasks 3.1–3.3 and both reviews/validations,
then section 7, then
archive desktop-chat followed by chat-on-mac. Decisions 1–8 stand.

`desk/kudde/` holds `mod.json`, `main.js` and the handwritten modules, with
`"focusable": true`: Tab reaches the reply box and "Open terminal", Enter
toggles a focused fold. `scripts/build-desk.sh [mods-dir]` builds
`kudde-desk`, lowers and converts it to `desk/kudde/core.js` (ignored by git)
and, given a folder, copies the mod there. A release builds the mod, attaches
`core.js` to this repository's release, and copies the folder, `core.js`
included, to the gallery repository. Its `RECIPE.md` says: `core.js` is
generated, do not read it (as openGPUI's RECIPE does for its build), check its sha256 against the matching release
asset here; if the files fail on a newer herdr version, report it with a link
to this repository's issues (rebuilding is possible with the stated toolchain
and command, but not expected); the mod runs programs on your machines
(`sh`, `hc`), reads agent logs and pictures outside its folder, and fetches
a version file once a day. It names the tested herdr version. The preview
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

### D13. Drift and updates
Gallery copies never update themselves. The core counts records and blocks
it does not know over everything the chat holds (the parser's running
`unknown` against `lines`); at three or more and over 5%, the pane shows
"Some messages can't be shown" with "Open terminal", building on the
Degraded health (`lib.rs:325`). Once a day the mod fetches a version file
from the gallery repository and, when its own version is older, shows "A
newer kudde chat is available". A weekly CI job compares the latest Claude
Code and Codex versions with `fixtures/VERSIONS` and opens an issue for a
new one; its fixture is recorded on the desk by hand.

## Testing

- The core's, send's and splitter's tests move with them and stay Rust
  tests, plus a backwards-clock test (D2, D3).
- `kudde-desk`: JSON interface tests for every method, a bad version, a
  malformed request, a deliberate panic (chat B in the same instance still
  works after chat A trapped), emoji and a lone surrogate.
- Parity under a pinned `qjs` in CI: chat-view fixtures replayed through
  `core.js` give the same `view()` JSON as through Rust, and the pending
  scenarios (sending, "Not in the conversation yet", Unsure) behave alike.
- The mod's logic (when to read, single flight, back-off, the waiting
  notice, machine states, argv building and stripping, the cache index) sits
  in plain modules with no gpui-kit import, tested under `qjs` with a fake
  `hc`, `exec` and clock (`now()` passed in).
- Manual, on the Windows desk before each release and recorded: theme
  switching, click to enlarge, opening twice, keyboard access, the hostile
  message round trip through the real `hc()` and `exec`.
- `herdr-app check desk/kudde` passes before any try in the app.

## Risks / Trade-offs

- **QuickJS speed** (low confidence): measured by the spike on the window's
  thread, with a capped first read as the first remedy.
- **Log drift for gallery users**: one parser means one fix, but copies do
  not update; D13 makes it visible to them and to us.
- **Windows process arguments**: tested in the spike and before each
  release; a base64 fallback exists.
- **A send to a done agent** has no herdr-core guard, as on the phone (#71).
- **A gallery listing cannot be reliably taken down** (D0): the listing
  waits until the mod is ready, and its text is shown to Ezra first.

## Open Questions

- The key that opens the chat: one free in the default `keys.json` on all
  three platforms (task 5.1); proposed to Ezra with the pane's texts.
