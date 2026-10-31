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
