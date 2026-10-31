# Tasks: herdr for Android, version 1

Every milestone writes its tests first (from the scenarios in `specs/`), ends
with a tagged release installed through Obtainium, and a check on Ezra's phone.
A milestone starts only after the previous phone check passed.

## 0. Before code

- [ ] 0.1 Council review of this change; fold in findings
- [ ] 0.2 Visual design: `/design-brief` from the canvas and reference shots, renders, `design-tokens.json` signed off
- [ ] 0.3 Post the question to the herdr maintainers (GitHub discussion on herdrdev/herdr-app), with Q1 and Q2 from design.md
- [ ] 0.4 Ezra decides repo visibility; create the GitHub repo

## M0. Release loop (empty app)

- [ ] M0.1 Gradle project (Kotlin, Compose, minSdk 31), Cargo workspace (`herdr-proto`, `herdr-mobile`, `herdr-push`), uniffi bridge returning a version string
- [ ] M0.2 libghostty-vt build step: Zig 0.16.0 pinned by checksum, Ghostty at `1e5cdb8`, `-Dtarget=aarch64-linux-android` and `x86_64-linux-android`
- [ ] M0.3 Workflow: build, test, approval-gated `sign-and-release` job, release assets; actions pinned by SHA
- [ ] M0.4 Generate the signing key, store it in the protected environment and Bitwarden
- [ ] M0.5 Phone check: Obtainium installs v0.0.1, then updates to v0.0.2 keeping data

## M1. Connect and list agents

- [ ] M1.1 Tests: frame codec, RPC client, compat check, `EventLines`, attention model (ported upstream tests plus spec scenarios)
- [ ] M1.2 Tests: protocol suite against a scratch herdr-core 0.1.15 (own `HC_SESSION`, XDG dirs)
- [ ] M1.3 Spike Q3: russh with a Keystore signing callback; fall back per spec if it fails
- [ ] M1.4 Machines screen, key and `authorized_keys` line, host key confirm, reconnect
- [ ] M1.5 Agents home with ordering, kinds, progress, "as of" when disconnected, duplicate skipping
- [ ] M1.6 Phone check: WSL and Mac agents listed; Mac asleep shows unreachable

## M2. Terminal view

- [ ] M2.1 Spike Q4: libghostty-vt on the device, Canvas drawing at 200x60, 60 fps
- [ ] M2.2 Tests: observe attach never sends RESIZE/FOCUS/FACTS/DATA; screenshot comparison with the Mac at equal size
- [ ] M2.3 Terminal screen: fit width, pinch zoom, pan, scrollback with Live, select and copy, instant open
- [ ] M2.4 Phone check: open a Claude Code agent from the phone, the Mac does not reflow

## M3. Answering agents

- [ ] M3.1 Tests: composer states (`if: idle`, blocked, force), keys mapping, direct-typing size choice
- [ ] M3.2 Composer, keys row and shelf, interrupt on long-press esc
- [ ] M3.3 Direct typing with the size prompt
- [ ] M3.4 Next/previous attention swipe
- [ ] M3.5 Phone check: answer a permission prompt and a question from the phone

## M4. Alerts, spaces, wide layout

- [ ] M4.1 Tests: RFC 8291 vectors, watcher transitions (one per entry, none at start, skip remote), payload fields only
- [ ] M4.2 `herdr-push` with push-status heartbeat; systemd and launchd units; install notes
- [ ] M4.3 ntfy topic grant on the VPS; check Q5 (VAPID)
- [ ] M4.4 UnifiedPush registration, `herdr-android/push` doc writes, two channels, clear-when-handled, per-machine mute
- [ ] M4.5 Spaces drawer (read-only), tab overview tiles, sub-agent peek
- [ ] M4.6 Themes and light/dark; rail at 600 dp and wider
- [ ] M4.7 Phone check: app closed, agent blocks on WSL, notification opens it; watcher stopped shows the warning
- [ ] M4.8 `/docs-update` for the VPS ntfy grant and the per-machine watcher

## M5. Changes view

- [ ] M5.1 Tests: read-only command allowlist, truncation, binary files
- [ ] M5.2 Changes list and diff view
- [ ] M5.3 Phone check: review an agent's changes before approving
