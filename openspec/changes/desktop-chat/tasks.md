## 1. Isolated core move before the spike (D2)

Done on `refactor/shared-chat-core` (d29a7c6, review nits 2e48e57), not yet merged.

- [x] 1.1 Move `chat_core.rs` and its tests into `kudde-chat` as `core`; times as `u64` ms with `saturating_sub`; types `pub`; `Held` stays in `kudde-mobile`; backwards, equal and increasing clock tests first; preserve all moved assertions and mobile-only `Held` tests
- [x] 1.2 The phone passes milliseconds since a fixed start; all Rust and Android unit tests pass unchanged
- [x] 1.3 Commit the move on its own (`refactor`)

## 2. Spike (D1)

Measured on `spike/desktop-chat`; results in `research/desktop-chat-spike-2026-11.md`.

- [x] 2.1 Install the wasm32 target, Binaryen and `qjs` on the dev machine (approved, decision 4)
- [x] 2.2 Measure the real `ChatCore` converted through wasm32, `wasm-opt` and `wasm2js` under `qjs`: fails every limit (best 3,033 ms first open, 575 ms update; 852 ms and 158 ms capped to 512 KB)
- [x] 2.3 Measure the floors of both fallbacks (JavaScript parse, native program per call)
- [x] 2.4 A JavaScript reader against the real core: exact on 194 inputs, 435 lines; 2 MB first open 318–373 ms, 6 KB update 5 ms, older page about 560–600 ms, largest catch-up about 1.7 s
- [x] 2.5 Decision 7 to the owner: the JavaScript copy, locked 2026-11-20

In the app on the Windows desk, with Ezra at the screen. None of these block section 3.

- [ ] 2.6 A minimal mod: lookup of a WSL Claude Code session through `hc get` and the lookup script, the tail shown in `list`; probes for key-encoded clicks (a fold, the second of two thumbnails), at-bottom tracking and `scroll_to_bottom` over 20 growing updates
- [ ] 2.7 One picture: base64 read, `Buffer` decode, write under `data/.kudde-images/`, `image(path)`; shows within one render, survives a mod reload, and the write does not reload mods
- [ ] 2.8 Live update: a new reply appears without a click; check for a hidden-pane signal
- [ ] 2.9 In the 2.6 minimal mod, with the spike's `reader.js` and D5's line-split fix: record the app's JavaScript engine name and version; time the whole first-open path (exec reply to drawn rows) for 512 KB, 1 MB and 2 MB; a 6 KB update with 2 MB and with 16 MB retained (at most 50 ms); an older load, no single step over 50 ms, the swap included; the largest catch-up in slices; single records of 1, 4 and 7.8 MB. Type in another pane while a sliced catch-up runs and record which yield keeps input responsive. Run the parity corpus inside the app against the oracle's golden replies (dev-only command in `desk/kudde-dev/`; 0 differences). Set the slice byte budget and the retention high mark from these (D5); lower the 2 MB first read only if it misses 250 ms, and tell the owner; a single record still over 300 ms goes to the owner
- [ ] 2.10 Sending: round latency; hostile exact-text round trips through both real `hc()` and fixed-script `exec` (CRLF, quotes, trailing backslash, `%`, emoji, leading `-`, 30 KB, shell substitutions, backticks, delimiters, trailing newlines; blank input dispatches nothing); `hc` on PATH; second-Enter behaviour. Capture exactly what each route returns for success, `status_mismatch`, `blocked`, `finished`, `not_found`, `unavailable` and timeout: values, stdout/stderr, exit status and thrown/rejected errors; establish which prove nothing was typed. Unknown results stay unknown. What a native Windows machine returns to D12's three signals
- [ ] 2.11 Findings into `research/desktop-chat-spike-2026-11.md`; apply R1-G as answered (ship) if 2.10 leaves the gap; a UI or image failure goes to the owner with its own repair choice

## 3. Shared send and pending, then a phone release (D3)

Starts now: the spike is done and decision 7 is locked.

- [x] 3.1 Owner answers R1-B and N1 (Markdown), both as picked (Ezra, 2026-11-20); confirm the selected behaviour in the specs before 3.3 and 3.4. Owner alternatives live only in design and proposal summaries
- [x] 3.2 (33310d4 on `refactor/shared-chat-core`) Tests first, ported from `chat.rs` and `ChatPending` tests: status to guard (done: re-check, no guard), `Sent::{Typed, Unsure, Refused}`, the Enter-again rule, the `/` and dialog waits, the pending states, pure screen checks, Claude queue matching, Codex screen queue detection, per-message Enter state and immutable targets across upload/final check; then move them into `kudde_chat::send`
- [x] 3.3 Tests first for canonical text before pending, classification and transport: CRLF/control-stripped text confirms in the log and queue; a control before `/usage` still waits for a dialog, and before `!` still classifies as a command; canonical input-box matching, normalized-empty no-op and attachment-only input, original draft restoration merged with a newer draft; core `sent()` receives canonical text, the final `@` space is added only for transport and ignored in matching; implement once in `kudde_chat::send` (owner pick R1-B)
- [x] 3.4 Markdown per N1 (answered as picked): nothing changes on the phone (the desk's part is 4.7); if the owner picks the port instead, port `markdownBlocks` to Rust with the `ChatRowsTest` cases first, Kotlin calls it, Roborazzi goldens unchanged
- [ ] 3.5 `ChatPending.kt` and `chat.rs` call the shared code; Roborazzi goldens and phone checks on the test phone pass
- [ ] 3.6 Phone release with notes naming the control-character change; a 1.x issue asking herdr-core for an `--if done` guard

## 4. The desk's JavaScript copy (D5, D10)

- [ ] 4.1 Tests first in `kudde-chat`: `with_budgets` defaults equal `new()`; `trim_to` equals `leave()`'s trim; the view revision bumps once per input-taking call (a sliced read once, at its last slice) and never stays put across a row change; the trim-due rule (high and low marks, "at bottom" input); phone Rust and Android unit tests pass unchanged. Then `crates/kudde-chat-oracle` (native, `publish = false`, grown from the spike's vehicle): JSON requests with budgets and actions (`trim_to` included) in, the real core's view, the lookup decision and, after 3.2, the send decisions out; prints the script texts. `serde` feature on `kudde-chat`'s view types. Host and Android `--workspace` builds stay green
- [ ] 4.2 The parity suite, grown from the spike's harness: D10's inputs, run at the phone's and at the desk's budgets, exact comparison, any difference fails; it runs before `desk/kudde/chat/` exists and fails to import it; it also writes the golden replies for the in-app run
- [ ] 4.3 `reader.js` from the spike's reader until the suite passes with 0 differences, with the regex line split and end trim replaced by string operations; `scripts.js` byte-identical to the Rust constants
- [ ] 4.4 Tests first for D5: a read fed in slices gives the same view and revision as one call (parity on sliced inputs); all slices of one read finish in one loop round; under `qjs`, no step over 50 ms on the largest catch-up or an older load (its swap included), and a 6 KB update with 16 MB retained at most 50 ms; linear key keeping, retention trimming while at the bottom and row reuse through per-row touch tracking, each with 0 parity differences; an unchanged revision builds no rows; the budgets from 2.9 are configurable
- [ ] 4.5 `lookup.js`: the lookup decision on recorded script outputs (Found, Missed, None, ambiguous) in the parity suite
- [ ] 4.6 After 3.2 and 3.3: `send.js`, with the send scenarios of D10 in the parity suite on a driven clock
- [ ] 4.7 `fences.js` with the fence cases of `ChatRowsTest` as tests (owner pick N1)
- [ ] 4.8 CI: a job with `qjs` pinned to the closest match of the app's engine runs the parity suite and the JavaScript tests on pull requests touching `crates/kudde-chat`, `crates/kudde-chat-oracle`, `desk/`, the fixtures, `Cargo.lock`, the `qjs` pin or the harness scripts, counted in `ci-ok`; the docs-only skip never skips it for those paths; if the app's engine builds headless, a job on that exact engine replaces or joins it
- [ ] 4.9 `desk/kudde/README.md` for maintainers: a new log shape gets a fixture first, both readers change in the same pull request, the suite is never relaxed to pass

## 5. The desk chat (desktop-chat spec)

- [ ] 5.1 Find a key free on Windows, Linux and macOS; show Ezra the key and every pane text, verbatim, before building them in (the Linux-only unsupported-machine notices included)
- [ ] 5.2 Reword the scroll scenarios from 2.6's findings if they differ
- [ ] 5.3 Tests first, under `qjs` with a fake `hc`, `exec` and clock: the read schedule, single flight and stale replies, back-off, slicing and yielding between panes, the paused pane, the waiting notice (blocked and no status), machine states (loading, unreachable, Mac, Windows by D12's signals), argv building with `--` and the guard per status, fresh Claude and Codex checks at both send boundaries (`/new`, `/resume`, unchanged pid/start, unreadable and ambiguous open files), the draft across a prompt, the cache index and eviction, the drift and update notices, an unknown `hc get` shape, a reader that throws (that chat rebuilds, another chat is untouched), and the clock wrapper (`max(last, Date.now())` never decreases; the three timers still advance after a backwards jump)
- [ ] 5.4 Opening beside the terminal, one pane per terminal, closing with it; "No conversation found for this terminal"; Tab and Enter
- [ ] 5.5 Rows: bubbles, markdown with code blocks, folded tool rows through `on_item_click` keys, the working line, theme colours; following the bottom, older messages at the top
- [ ] 5.6 Pictures: thumbnails, the larger view, the 6,000,000-byte cap and magic bytes, "Image not available", the cache
- [ ] 5.7 Tests first using the exact results recorded in 2.10 (including each refusal and `unavailable` before/after possible input), through fake direct `hc()` and the selected `exec` route: recorded success result → Typed; pre-dispatch failure/recorded pre-input rejection → Refused; delivery then timeout/disconnect, malformed reply, nonzero exit/stderr and rejected promise without no-input evidence → Unsure; pending remains, no draft restoration, route retry or second Enter; definitive refusal restores original plus newer draft
- [ ] 5.8 Replies: Enter and Shift+Enter, the shared send decisions through `send.js`, `hc send ... -- <text>`, pending states; the base64 path if 2.10 found arguments unsafe; the Enter-again rule if 2.10 found the drop
- [ ] 5.9 Waiting, read-only after exit, machine messages, drift and update notices, the paused pane
- [ ] 5.10 `herdr-app check desk/kudde` passes; on Windows with WSL agents try every scenario, and the manual list (theme, click to enlarge, opening twice, keyboard, the hostile round trip, first-open and step timings against D5, the parity corpus in the app); record what was tried

## 6. Review and tidy

- [ ] 6.1 Codex adversarial review of the branch
- [ ] 6.2 `openspec validate desktop-chat --strict`

## 7. Gallery release (D11), after `chat-on-mac`

Mac integration starts once 3.6 and sections 4–5
pass, without waiting for archival. Complete chat-on-mac tasks 3.1–3.3 (integration, checks and review) before this section; validate both changes, release, then
archive desktop-chat followed by chat-on-mac.

- [ ] 7.1 Create the gallery repository (public, `herdr-mod` topic) with Ezra's OK; release CI copies the mod folder there
- [ ] 7.2 `RECIPE.md` as D11 states, the version file for the update check, the tested herdr version; a Claude in the herdr app installs the mod from a local copy of the gallery folder by the guide's steps, every file arrives intact, and `desk/kudde-dev/` (the dev command and golden replies) is absent (no public listing for this)
- [ ] 7.3 Draft the listing and preview (staged agents, invented names, no Mac screenshot, lowercase "kudde", kudde.dev link); show Ezra verbatim
- [ ] 7.4 With Ezra's OK: publish
- [ ] 7.5 Weekly CI job: new Claude Code or Codex version without a fixture opens an issue
