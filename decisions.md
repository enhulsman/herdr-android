# Decisions: herdr Android client

One line per decision, newest last. Date, decision, why.

- 2026-10-31: Design first (1-2 sessions), then a milestone-gated background build (M1-M4), each ending in a phone check by Ezra.
- 2026-10-31: The phone stays a viewer/steerer: no agents and no npm on it.
- 2026-10-31: Ask the herdr maintainers (GitHub discussion on herdrdev/herdr-app) before writing code.
- 2026-10-31: Alerts with the app closed go through UnifiedPush via the self-hosted ntfy (no Google FCM). Pattern follows MollySocket.
- 2026-10-31: The push watcher runs on each herdr-core machine (local connection, outbound to ntfy only), not on the VPS: a VPS watcher would need SSH into the workstation, and herdr-core access is an unrestricted shell.
- 2026-10-31: The push payload is encrypted with UnifiedPush web-push encryption (RFC 8291) and carries machine, agent and kind only; ntfy sees ciphertext. Chosen over an empty Molly-style wake-up so alerts show instantly and work without the phone reaching the machine.
- 2026-10-31: Release APKs are signed in GitHub Actions; the key sits in a protected environment whose signing step waits for Ezra's approval (from the phone). Key backup in Bitwarden. Why: a signing-key holder can ship an update that steals the app's SSH key, which is shell on the machines.
- 2026-10-31: Stack: Kotlin + Jetpack Compose UI over a Rust core that uses libghostty-vt (same terminal engine as the Mac app). The 2-hour test built libghostty-vt for Android arm64 cleanly. Fallback if it misbehaves on a device: Termux's terminal-emulator/terminal-view libraries (Apache 2.0).
- 2026-10-31: Themes: the Mac's six (graphite, ink, moss, paper, atom-one-dark, win98) copied as data; phone-local setting, follows system light/dark with defaults graphite/paper. No custom theme files in v1.
- 2026-10-31: Changes panel on the phone, view-only (no commit/stage/discard). Files panel after M4.
- 2026-10-31: Alerts use two Android notification channels, "Needs you" (loud, lock screen) and "Finished" (quiet, can be off); sound/vibration/DND via Android settings; per-machine mute in the app.
- 2026-10-31: The phone cannot SSH into the Mac (confirmed by Ezra). v1 connects to WSL only; the design stays multi-machine for other OpenSSH hosts. Mac alerts-only via an outbound watcher is a parked idea.
- 2026-10-31: Milestone order: M1 connect + agent list; M2 live terminal + keyboard together (keys row, compose key, phone size while typing); M3 alerts; M4 spaces drawer, wide layout, themes; M5 Changes. No chat-style composer: input is the native terminal plus keyboard.
- 2026-10-31: Typing on the phone resizes the terminal to the phone by default (no dialog); the desk takes its size back on its next input, as herdr itself does. Watching alone stays observe-only.
- 2026-10-31: A compose key in the keys row opens a text field (autocorrect, swipe, dictation) whose text is pasted into the terminal without Enter (bracketed paste), as Termux does.
- 2026-10-31: Phone key protection: try C first (Keystore key requires fingerprint/device credential, valid 30 min; one prompt on opening the app or tapping an alert). If the UX is poor at the M1 phone check, fall back to B (app lock after 15 min in the background, key itself unbound). Alerts never need the key.
- 2026-10-31: v1 = M1-M3 (connect + list, live terminal + keyboard, alerts) with themes graphite/paper following system; v1.1 = M4 (spaces, tiles, peek, wide layout, other four themes) and M5 (Changes). Mainly sets where the background build stops.
- 2026-10-31: Maintainer post (one GitHub discussion, drafted in Ezra's voice, shown verbatim before posting, posted by Ezra) asks five things: third-party Android client welcome / own mobile plans; client-owned flat doc names (`hdroid-push`) as a stable contract; viewer presence or last-input time on Terminal; push support in core; upstreaming the shared protocol code as a client crate. No Mac, client or setup details.
