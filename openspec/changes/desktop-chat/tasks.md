## 0. Gallery check (design D0)

- [ ] 0.1 Throwaway public repository tagged `herdr-mod`: a mod folder with a generated ~1 MB JS file, a `RECIPE.md` and a `preview.png`; confirm herdr.dev lists it and a gallery install copies the file; delete it afterwards. On failure: decision 7 to Ezra, stop

## 1. Spike: does the converted core run in the mod (D1)

- [ ] 1.1 Install the wasm32 target, Binaryen and `qjs` on the dev machine (approved, decision 4)
- [ ] 1.2 On `spike/desktop-chat`: build `kudde-chat` for wasm32, lower its features with `wasm-opt`, convert with `wasm2js`; record the flags and the size (at most 1.5 MB)
- [ ] 1.3 A minimal mod: lookup of a WSL Claude Code session through `hc get` and the lookup script, the tail shown in `list`; probes for key-encoded clicks (a fold, the second of two thumbnails), at-bottom tracking and `scroll_to_bottom` over 20 growing updates
- [ ] 1.4 One picture: base64 read, `Buffer` decode, write under `data/.kudde-images/`, `image(path)`; shows within one render, survives a mod reload, and the write does not reload mods
- [ ] 1.5 Live update: a new reply appears without a click; check for a hidden-pane signal
- [ ] 1.6 Measure: time on the window's thread for a 2 MB first open (at most 300 ms, else a 512 KB cap and again) and one tick (at most 50 ms); round latency for the record. Hostile message round trip through the real `hc()` and `exec`; `hc` on the agent machine's PATH; whether `hc send` drops a second Enter
- [ ] 1.7 Findings to `research/desktop-chat-spike-2026-11.md`; on failure, decision 7 to Ezra and stop

## 2. One shared core for phone and desk (D2, D3), shipped as a phone release

- [ ] 2.1 Move `chat_core.rs` and its tests into `kudde-chat` as `core`; times as `u64` ms with `saturating_sub`; types `pub`; `Held` stays in `kudde-mobile`; a backwards-clock test first
- [ ] 2.2 The phone passes milliseconds since a fixed start; all Rust and Android unit tests pass unchanged
- [ ] 2.3 Commit the move on its own (`refactor`)
- [ ] 2.4 Tests first, ported from `chat.rs` and `ChatPending` tests: status to guard (done: re-check, no guard), `Sent::{Typed, Unsure, Refused}`, the Enter-again rule, the `/` and dialog waits, the pending states; then move them into `kudde_chat::send`
- [ ] 2.5 Tests first for the chat-view delta (a pasted Ctrl+C, Windows line endings); stripping in `kudde_chat::send`
- [ ] 2.6 Tests first, ported from Kotlin: the code-block splitter in `kudde_chat::markdown`; Kotlin calls it
- [ ] 2.7 `ChatPending.kt` and `chat.rs` call the shared code; Roborazzi goldens and phone checks on the test phone pass
- [ ] 2.8 Phone release with notes naming the control-character change; a 1.x issue asking herdr-core for an `--if done` guard

## 3. The core as JavaScript (D10, D11)

- [ ] 3.1 Tests first: `kudde-desk` JSON interface (every method, a bad version, a malformed request, a deliberate panic, emoji and a lone surrogate)
- [ ] 3.2 `serde` feature with `Serialize` on the view types; `view()` revision
- [ ] 3.3 `kudde-desk`: `kd_alloc`, `kd_free`, `kd_call`, exports for wasm32 only; methods take raw script output; the scripts handed out
- [ ] 3.4 `bridge.js`: fresh memory view per call, `kd_free` on every path, UTF-8, lone surrogates replaced; a trap reloads the module and rebuilds every chat (test: chat B works after chat A trapped)
- [ ] 3.5 Parity tests under `qjs`: fixtures give the same `view()` JSON as Rust; pending scenarios behave alike
- [ ] 3.6 `scripts/build-desk.sh [mods-dir]`; `core.js` ignored by git
- [ ] 3.7 CI: wasm32 build with the pinned `wasm-opt` flags, `wasm2js`, the `qjs` tests with a pinned `qjs`; host and Android jobs stay green

## 4. The desk chat (desktop-chat spec)

- [ ] 4.1 Find a key free on Windows, Linux and macOS; show Ezra the key and every pane text, verbatim, before building them in
- [ ] 4.2 Reword the scroll scenarios from 1.3's findings if they differ
- [ ] 4.3 Tests first, under `qjs` with a fake `hc`, `exec` and clock: the read schedule, single flight and stale replies, back-off, the paused pane, the waiting notice (blocked and no status), machine states (loading, unreachable, Mac, Windows), argv building with `--` and the guard per status, the draft across a prompt, the cache index and eviction, the drift and update notices, an unknown `hc get` shape
- [ ] 4.4 Opening beside the terminal, one pane per terminal, closing with it; "No conversation found for this terminal"; Tab and Enter
- [ ] 4.5 Rows: bubbles, markdown with code blocks, folded tool rows through `on_item_click` keys, the working line, theme colours; following the bottom, older messages at the top
- [ ] 4.6 Pictures: thumbnails, the larger view, the 6,000,000-byte cap and magic bytes, "Image not available", the cache
- [ ] 4.7 Replies: Enter and Shift+Enter, the shared send decisions, `hc send ... -- <text>`, pending states; the base64 path if 1.6 found arguments unsafe; the Enter-again rule if 1.6 found the drop
- [ ] 4.8 Waiting, read-only after exit, machine messages, drift and update notices, the paused pane
- [ ] 4.9 `herdr-app check desk/kudde` passes; on Windows with WSL agents try every scenario, and the manual list (theme, click to enlarge, opening twice, keyboard, the hostile round trip); record what was tried

## 5. Review and tidy

- [ ] 5.1 Codex adversarial review of the branch
- [ ] 5.2 `openspec validate desktop-chat --strict`

## 6. Gallery release (D11), after `chat-on-mac`

- [ ] 6.1 Create the gallery repository (public, `herdr-mod` topic) with Ezra's OK; release CI attaches `core.js` here and copies the mod folder there
- [ ] 6.2 `RECIPE.md` as D11 states, the version file for the update check, the tested herdr version
- [ ] 6.3 Draft the listing and preview (staged agents, invented names, no Mac screenshot, lowercase "kudde", kudde.dev link); show Ezra verbatim
- [ ] 6.4 With Ezra's OK: publish
- [ ] 6.5 Weekly CI job: new Claude Code or Codex version without a fixture opens an issue
