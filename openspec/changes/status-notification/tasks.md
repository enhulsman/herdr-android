## 1. Tests first

- [x] 1.1 `StatusTextTest`: every title and second line in the spec, one case per machine group and the precedence among them, `startError`, singular and plural, counts only from connected machines, Error counted as need you, Done counted as done (Ezra, 2026-11-12: a finish not yet opened in the app), Idle not counted, no names in any string, equal inputs give equal lines
- [x] 1.2 `ForegroundTrackerTest`: with `keepConnected` true the deadline calls `onBackgroundIdle` and never suspends, and a return calls no `onResume`; the deep-sleep catch-up with it true does nothing; with it false V1 behaviour is unchanged; the predicate is read at fire time (on when armed, off when fired: suspends)
- [x] 1.3 `UserAuthGateTest`, each with its armed precondition: (a) unlock then activity start, one silent retry, no prompt; (b) activity start then unlock, one silent retry; (c) a stale NeedsUserAuth after the retry is ignored; (d) Connecting dropped, prompt at the 10 s fallback; (e) armed, retry fails, exactly one prompt, then no loop; (f) option off, existing tests unchanged; (g) unlock with only a backoff machine, one plain retry; (h) cancel, background idle, return, failed silent retry, exactly one prompt; (i) cancel then a restart within seconds, no retry, no prompt; (j) unlock while not armed and the retry fails, no prompt
- [x] 1.4 `StatusNotifierTest`: posts only when the line changes; after `stop` nothing is posted; never touches alert tags
- [x] 1.5 Setting and Machines row: off by default, toggles in place, all three second lines; screenshot tests in paper and graphite (goldens machines-stay-on-* and machines-stay-unseen-*; the other Machines goldens re-recorded for the new row)
- [x] 1.6 `TerminalReopenTest`: idle while stopped closes and keeps the last frame; start after idle gives a new generation seeded from memory; start without idle does nothing; idle while started is ignored; two cycles in a row each detach and reopen

## 2. Build

- [x] 2.1 `StayConnectedSetting` (own preferences file, absent = off) and the Machines row with its strings
- [x] 2.2 `StatusText`, `StatusNotifier` and the "Status" channel in `AlertChannel` / `AndroidAlertSink`
- [x] 2.3 `StayConnectedService` (`specialUse`, `START_NOT_STICKY`, `startForeground` with id 2 in every `onStartCommand`, snapshot collection cancelled in `onDestroy`), manifest permissions and the subtype property; start on turn-on and activity start, stop on turn-off
- [x] 2.4 `ForegroundTracker` predicate and idle callback; `HerdrApp` wiring of `terminalIdle` and `userAuth.onBackgroundIdle`
- [x] 2.5 `UserAuthGate` silent retry, fallback and re-arm; the `ACTION_USER_PRESENT` receiver in the service (`RECEIVER_NOT_EXPORTED`)
- [x] 2.6 `TerminalRoute` generation, idle collector with the STARTED guard, seed from memory, no disk load while a frame is held
- [x] 2.7 Notification permission request on turn-on, through the Alerts flow

## 3. Review

- [x] 3.1 Codex adversarial review of the branch (key gate, service lifecycle): two findings. Fixed: a return after deep sleep past the deadline left the key prompt disarmed (tracker `onCaughtUp` re-arms it, D2). Not taken: "a foreground service loses the network in Doze without a battery-optimization exemption"; foreground-service priority is exempt from the Doze network rule, and 4.1 checks it on the phone
- [x] 3.2 `openspec validate status-notification --strict`

## 4. Phone check (debug build over adb)

- [x] 4.1 Screen off 30+ minutes and forced Doze: connections stay, counts update, no new SSH login
  - 2026-11-13, test phone (stock Android 17, charging, Doze forced with the battery reported unplugged): 32 min in deep idle, the same SSH connection throughout (no new login), the service in the foreground, counts updated twice in Doze ("1 done · 1 working", "1 needs you · 1 done"). A real night is the battery check (4.6).
- [x] 4.2 Key window closed while locked: "Disconnected · unlock to reconnect"; unlock without opening the app reconnects with no prompt; unlock with the app on top shows no prompt
  - 2026-11-13, test phone: FAIL first: the unlock never reached the app, as ACTION_USER_PRESENT comes from SystemUI and a not-exported receiver skips it. Fixed (UnlockReceiver, registered exported). Then PASS: key window closed (31 min locked), connection cut on the desk, "Disconnected · unlock to reconnect"; unlock with the app in the background reconnected within 3 s, with the app on top within 4 s, no prompt either time.
- [ ] 4.3 Wi-Fi to mobile data in the background: reconnects, no prompt
- [x] 4.4 Terminal left open, two cycles, screen on and screen off: desk size back, reopens on return, typing works; measure the screen-off detach delay
  - 2026-11-13, test phone, fake agent terminal: both cycles (screen off, and Home with the screen on) detached 62 s after leaving (60 s idle plus polling), the desk size came back, it reopened at the phone's size on return and typing arrived, on the same SSH connection. Seen once: the first typing after opening arrived with two letters swapped ("typde"); not reproduced in four more tries.
- [x] 4.5 An alert while connected arrives once, through ntfy
  - 2026-11-13, test phone: a real agent's "Finished" alert, sent while the phone was in forced Doze, showed exactly once; the test alert also passed.
- [ ] 4.6 Battery estimate, no pass mark (Ezra: it differs per phone): with the battery-investigation capture method (phone-side capture script and `batteryreport.py`, one variable at a time), one night with Stay connected on, otherwise the night-19 configuration, compared against that configuration's 2.08 and 1.93 %/h screen-off; and two or more day windows captured before plugging in, with the day's screen-off rate checked against the August 2.5-6.4 %/h daytime band (too noisy to price a small cost; it only catches a large one). Record the estimate with its confidence for the notice (5.1). A clearly large cost (well above the night noise of about 0.15 %/h, or day screen-off above the band): propose the Battery Saver and idle stops as a spec change
- [ ] 4.7 Silent link death: the desk drops the phone's traffic in its firewall for 10+ minutes with the screen off; on unlock the notification is right within about 50 s; record whether the link had died
- [ ] 4.8 GrapheneOS lock screen: record whether silent notifications show by default, and that the counts show
- [x] 4.9 Swipe the notification away: it comes back on the next change, connections kept
  - 2026-11-13, test phone: swiped away, it left the shade; it came back on the next change, with the service and the connection kept.

## 5. Records

- [ ] 5.1 The battery notice with the 4.6 estimate, worded as an estimate for one phone (placement per Ezra); android-client-v1 machine-connection "Connections end in the background": add "unless Stay connected is on"; decisions.md entry; `design-tokens.json` wording for the Status notification and the row (approved 2026-11-08)
- [ ] 5.2 Archive on the branch before any version bump
