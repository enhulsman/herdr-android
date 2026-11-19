## 1. Isolated core move before the spike (D2)

Write clock tests first, preserve every moved assertion and keep `Held` mobile-only.

- [ ] 1.1 Move `chat_core.rs` and its tests into `kudde-chat` as `core`; times as `u64` ms with `saturating_sub`; types `pub`; `Held` stays in `kudde-mobile`; backwards, equal and increasing clock tests first; preserve all moved assertions and mobile-only `Held` tests
- [ ] 1.2 The phone passes milliseconds since a fixed start; all Rust and Android unit tests pass unchanged
- [ ] 1.3 Commit the move on its own (`refactor`)

## 2. Spike: measure the actual converted core (D1)

The harness requires 1.1–1.3; it does not wait for the production bridge.

- [ ] 2.1 Install the wasm32 target, Binaryen and `qjs` on the dev machine (approved, decision 4)
- [ ] 2.2 After 1.1–1.3, on `spike/desktop-chat`: build a throwaway wasm32 `cdylib` harness around the actual moved `ChatCore`, with alloc/free, raw-script/read/older-page inputs and representative JSON/view calls (D10), not a parser-only build; lower its features with `wasm-opt`, convert with `wasm2js`; record the flags and the size (at most 1.5 MB)
- [ ] 2.3 A minimal mod: lookup of a WSL Claude Code session through `hc get` and the lookup script, the tail shown in `list`; probes for key-encoded clicks (a fold, the second of two thumbnails), at-bottom tracking and `scroll_to_bottom` over 20 growing updates
- [ ] 2.4 One picture: base64 read, `Buffer` decode, write under `data/.kudde-images/`, `image(path)`; shows within one render, survives a mod reload, and the write does not reload mods
- [ ] 2.5 Live update: a new reply appears without a click; check for a hidden-pane signal
- [ ] 2.6 Measure the existing core, without building resumable parsing, paged views or a work queue: time per MB, total window-thread time and worst synchronous call for parse, retained-history reparse, older pages, changed/unchanged views and JSON encode/decode; a 2 MB first open, full 7,864,320-byte incremental read, long histories, a maximum-size single record and multiple panes. Derive configurable desk first/incremental/older/retention budgets (including a real 512 KB first-read configuration); remeasure where possible. Targets: first open at most 300 ms total; ordinary steps at most 50 ms; one over-budget record at most 300 ms. Record failures honestly. Record round latency; hostile exact-text round trips through both real `hc()` and fixed-script `exec` (D1), `hc` on PATH and second-Enter behaviour. Capture exactly what each route returns for success, `status_mismatch`, `blocked`, `finished`, `not_found`, `unavailable` and timeout: values, stdout/stderr, exit status and thrown/rejected errors; establish which prove nothing was typed. Unknown results stay unknown. Do not build adapter fakes before these observations.
- [ ] 2.7 Findings to `research/desktop-chat-spike-2026-11.md`; record measured budgets and unresolved limits for the owner; on failure stop dependent work and apply decision 7, with a specific repair choice for UI/image failures; unfinished production bounding is not a spike failure

## 3. Shared send, pending and markdown, then a phone release (D3)

Section 3 waits for spike results and owner decisions.

- [ ] 3.1 Owner answers R1-B, R1-D and R1-F, and R1-G if the observed send results leave that question open; confirm the selected behaviour in the specs before implementation. Owner alternatives live only in design and proposal summaries.
- [ ] 3.2 Tests first, ported from `chat.rs` and `ChatPending` tests: status to guard (done: re-check, no guard), `Sent::{Typed, Unsure, Refused}`, the Enter-again rule, the `/` and dialog waits, the pending states, pure screen checks, Claude queue matching, Codex screen queue detection, per-message Enter state and immutable targets across upload/final check; then move them into `kudde_chat::send`
- [ ] 3.3 Tests first for canonical text before pending, classification and transport: CRLF/control-stripped text confirms in the log and queue; a control before `/usage` still waits for a dialog, and before `!` still classifies as a command; canonical input-box matching, normalized-empty no-op and attachment-only input, original draft restoration merged with a newer draft; core `sent()` receives canonical text, the final `@` space is added only for transport and ignored in matching; implement once in `kudde_chat::send` (owner pick R1-B)
- [ ] 3.4 Tests first, ported from Kotlin: the code-block splitter in `kudde_chat::markdown`; Kotlin calls it
- [ ] 3.5 `ChatPending.kt` and `chat.rs` call the shared code; Roborazzi goldens and phone checks on the test phone pass
- [ ] 3.6 Phone release with notes naming the control-character change; a 1.x issue asking herdr-core for an `--if done` guard

## 4. The core as JavaScript (D10, D11)

- [ ] 4.1 Tests first: `kudde-desk` JSON interface (every method, a bad version, a malformed request, a deliberate panic, emoji and a lone surrogate)
- [ ] 4.2 Tests first for the owner-selected D5 limits: first-open total, worst step for full incremental reads, capped retained history, older reload/reparse, over-budget single record, cheap unchanged revision, no lost/duplicated rows, phone defaults unchanged, configurable desk budgets and yielding between panes. Test the measured limits before production bridge/core changes; do not prescribe an incremental parser, paged API or shared queue unless chosen by the owner
- [ ] 4.3 `serde` feature with `Serialize` on the view types; `view()` revision
- [ ] 4.4 `kudde-desk`: `kd_alloc`, `kd_free`, `kd_call`, exports for wasm32 only; methods take raw script output; the scripts handed out; implement the selected bounding once here, preserving phone behaviour
- [ ] 4.5 `bridge.js`: fresh memory view per call, `kd_free` on every path, UTF-8, lone surrogates replaced; a trap reloads the module and rebuilds every chat (test: chat B works after chat A trapped)
- [ ] 4.6 Parity tests under `qjs`: fixtures give the same `view()` JSON as Rust; pending scenarios behave alike
- [ ] 4.7 `scripts/build-desk.sh [mods-dir]`; `core.js` ignored by git
- [ ] 4.8 CI: a `--check` that rebuilds `core.js` and compares the bytes; wasm32 build with the pinned `wasm-opt` flags, `wasm2js`, the `qjs` tests with a pinned `qjs`; host and Android jobs stay green

## 5. The desk chat (desktop-chat spec)

- [ ] 5.1 Find a key free on Windows, Linux and macOS; show Ezra the key and every pane text, verbatim, before building them in
- [ ] 5.2 Reword the scroll scenarios from 2.3's findings if they differ
- [ ] 5.3 Tests first, under `qjs` with a fake `hc`, `exec` and clock: the read schedule, single flight and stale replies, back-off, the paused pane, the waiting notice (blocked and no status), machine states (loading, unreachable, Mac, Windows), argv building with `--` and the guard per status, fresh Claude and Codex checks at both send boundaries (`/new`, `/resume`, unchanged pid/start, unreadable and ambiguous open files), the draft across a prompt, the cache index and eviction, the drift and update notices, an unknown `hc get` shape
- [ ] 5.4 Opening beside the terminal, one pane per terminal, closing with it; "No conversation found for this terminal"; Tab and Enter
- [ ] 5.5 Rows: bubbles, markdown with code blocks, folded tool rows through `on_item_click` keys, the working line, theme colours; following the bottom, older messages at the top
- [ ] 5.6 Pictures: thumbnails, the larger view, the 6,000,000-byte cap and magic bytes, "Image not available", the cache
- [ ] 5.7 Tests first using the exact results recorded in 2.6 (including each refusal and `unavailable` before/after possible input), through fake direct `hc()` and the selected `exec` route: recorded success result → Typed; pre-dispatch failure/recorded pre-input rejection → Refused; delivery then timeout/disconnect, malformed reply, nonzero exit/stderr and rejected promise without no-input evidence → Unsure; pending remains, no draft restoration, route retry or second Enter; definitive refusal restores original plus newer draft
- [ ] 5.8 Replies: Enter and Shift+Enter, the shared send decisions, `hc send ... -- <text>`, pending states; the base64 path if 2.6 found arguments unsafe; the Enter-again rule if 2.6 found the drop
- [ ] 5.9 Waiting, read-only after exit, machine messages, drift and update notices, the paused pane
- [ ] 5.10 `herdr-app check desk/kudde` passes; on Windows with WSL agents try every scenario, and the manual list (theme, click to enlarge, opening twice, keyboard, the hostile round trip); record what was tried

## 6. Review and tidy

- [ ] 6.1 Codex adversarial review of the branch
- [ ] 6.2 `openspec validate desktop-chat --strict`

## 7. Gallery release (D11), after `chat-on-mac`

Mac integration starts once 3.6 and sections 4–5
pass, without waiting for archival. Complete chat-on-mac tasks 3.1–3.3 (integration, checks and review) before this section; validate both changes, release, then
archive desktop-chat followed by chat-on-mac.

- [ ] 7.1 Create the gallery repository (public, `herdr-mod` topic) with Ezra's OK; release CI attaches `core.js` here and copies the mod folder there
- [ ] 7.2 `RECIPE.md` as D11 states, the version file for the update check, the tested herdr version; a Claude in the herdr app installs the mod from a local copy of the gallery folder by the guide's steps, and `core.js` arrives intact (no public listing for this)
- [ ] 7.3 Draft the listing and preview (staged agents, invented names, no Mac screenshot, lowercase "kudde", kudde.dev link); show Ezra verbatim
- [ ] 7.4 With Ezra's OK: publish
- [ ] 7.5 Weekly CI job: new Claude Code or Codex version without a fixture opens an issue
