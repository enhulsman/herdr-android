# Tasks: herdr for Android

Every milestone writes its tests first (from the scenarios in `specs/`), ends with
a tagged release installed through Obtainium and a check on Ezra's phone, and
starts only after the previous phone check passed. Version 1 = M0-M3.

## 0. Before code

- [x] 0.1 ~~Maintainer post~~ dropped 2026-10-31: maintainers rarely answer Discussions (see `decisions.md`)
- [x] 0.2 Visual design: renders in `design/`, `design-tokens.json` and `design-brief.md`, signed off 2026-11-01
- [x] 0.3 Private `herdr-android` created 2026-11-01: `main` protected (no force push, signed commits), tag ruleset for `v*`; `release` environment (tags `v*` only) is set up in M0.4

## M0. Release loop and early spikes

- [x] M0.1 Gradle project (Kotlin, Compose, minSdk 31), Cargo workspace (`herdr-proto`, `herdr-mobile`, `herdr-push`), uniffi bridge returning a version string
- [ ] M0.2 libghostty-vt build: Zig 0.16.0 pinned by checksum, Ghostty `1e5cdb8`, Android targets; bindings regenerated from its headers
- [x] M0.3 Workflow per the release spec: build job (main-ancestry check, tests, cargo-deny, sums), sign job without checkout in environment `release`; lockfiles and Gradle verification
- [x] M0.4 `release` environment (deployment policy: branch `main` only, see the release spec), signing key as its secret and in Bitwarden; certificate fingerprint committed
- [ ] M0.5 Spike A, device smoke test: load the `.so` on the Pixel, feed a recorded synthetic Claude Code stream, read the grid, draw one 200x60 frame. Fail -> switch to the Termux fallback and amend design.md before M1
- [ ] M0.6 Spike B, russh signing through a Keystore key with 30-minute user authentication. Fail -> wrapped Ed25519 key; write the key type into design.md
- [ ] M0.7 Spike C, push end to end: watcher-side `ece` encryption, POST with the `up*` token to ntfy, decrypt in the UnifiedPush connector on the phone; record whether VAPID is needed and that the binary body arrives intact
- [ ] M0.8 Phone check: Obtainium (read-only token, background install off) installs v0.0.1, updates to v0.0.2 keeping data and the Keystore key

## M1. Connect and list agents

- [ ] M1.1 Tests: frame codec, RPC client, compat check, `EventLines`, attention table, timeouts, CREDIT, frame cap, dead link, shell noise, plain-to-framed switch
- [ ] M1.2 Tests: protocol suite against scratch cores, pinned and latest; throwaway-sshd test of the `authorized_keys` line
- [ ] M1.3 Machines screen: key, restricted line, host key confirm, reconnect, lifecycle, export/import
- [ ] M1.4 Agents home: attention order, kinds, progress, names, "Other terminals", "as of", duplicate skipping
- [ ] M1.5 Phone check: WSL agents listed; refused-command check on WSL; fingerprint UX judged (keep C or fall back to B); dim text and paper status colours judged outdoors (keep the Mac values or switch to the AA values in `design-tokens.json`)

## M2. Live terminal and keyboard

- [ ] M2.1 Tests: cell-grid goldens from herdr-app's VT pipeline; observe attach sends nothing; size-event reattach; typing takes phone size; null display keeps desk colours (incl. cell size); every keys-row key encodes
- [ ] M2.2 Terminal view: frame path, fit width, zoom, pan, scrollback with Live, selection from local screen, instant-open cache
- [ ] M2.3 Input: terminal keyboard, keys row and shelf, sticky modifiers, compose key with bracketed paste, interrupt
- [ ] M2.4 Attention swipe between agents
- [ ] M2.5 Phone check: answer a permission prompt and a long dictated prompt from the phone; desk size returns on desk input; p95 frame time and instant open measured

## M3. Alerts

- [ ] M3.1 Tests: RFC 8291 vectors, transition rules with grace delay, persistence across restart, `missed` diff, allowlist and token scope, payload fields, doc names against a scratch core
- [ ] M3.2 `herdr-push`: existing-core connection, rules, canary, cleared, test alert, status document; systemd unit and install notes
- [ ] M3.3 ntfy write-only `up*` token on the VPS
- [ ] M3.4 App: UnifiedPush registration, `hdroid-push` writes, three channels, cleared handling, silence check, status display, mute, permission flow
- [ ] M3.5 Phone check: app closed, agent blocks on WSL, alert opens it; answered at the desk clears it; test alert; WSL off overnight gives one quiet notice
- [ ] M3.6 `/docs-update` for the ntfy token and the WSL watcher

## Version 1.1

### M4. Spaces, wide layout, themes
- [ ] M4.1 Tests: lenient layout parsing (mod pane, unknown node, malformed tab), focus rule, address normalisation
- [ ] M4.2 Spaces drawer, tab overview tiles, sub-agent peek
- [ ] M4.3 Six themes with per-mode choice; rail at 600 dp and wider
- [ ] M4.4 Phone check

### M5. Changes view
- [ ] M5.1 Tests: fixed git template, hostile repository, truncation, binary files
- [ ] M5.2 Changes list and diff view
- [ ] M5.3 Phone check: review an agent's changes before approving
