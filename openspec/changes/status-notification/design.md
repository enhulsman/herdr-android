# Design: stay connected, with a status notification

## Context

The hub lives in `HerdrApp`, one per process. `ForegroundTracker` counts
started activities and, 60 s after the last one stops, calls
`HubHost.suspend`, which stops every machine (`hub/mod.rs` `suspend`); the
machines then read `Paused`. Android cuts the app's sockets about 5 s after it
leaves the foreground anyway (decisions.md, 2026-11-02), so today every return
reconnects.

The phone key signs only within 30 minutes of an authentication. A signature is
needed at SSH login only; each machine is one SSH link, and push sync and
terminals are channels on it, so a link that stays up never needs the key
again (research §2.7). When signing fails the machine sits in `NeedsUserAuth`
(`Outcome::wait`) and the hub does not retry by itself. `UserAuthGate` turns
that into one fingerprint prompt, and only while an activity is started; its
`armed` flag stops a failed retry from looping the prompt and is re-armed by a
snapshot with no machine needing the key or connecting, in V1 the `Paused`
snapshot of a suspend.

A foreground service of type `specialUse` keeps the process at a priority the
background and Doze network rules leave alone, with no daily time limit
(research §2.1-2.3). The CPU still sleeps between the core's 15 s heartbeats;
those wake-ups are the battery cost (§2.4).

Snapshots reach Kotlin through a latest-value channel (`lib.rs`, `watch`), so
an intermediate state such as `Connecting` can be dropped, and a snapshot
queued before a call can arrive after it.

Ezra's seven decisions are in the proposal's plain summary; the wording in the
spec was approved 2026-11-08. This design places them in the code.

## Goals / Non-Goals

**Goals:**
- A "Stay connected" row on the Machines screen, off by default, that keeps the
  hub connected while the app is in the background.
- A quiet, ongoing notification with counts only, derived from the snapshot.
- Reconnecting in the background without a prompt, and once the key's window
  has closed, waiting for the phone to be unlocked.
- An open terminal detached after 60 s in the background, reopened on return.

**Non-Goals:**
- Starting at boot or after the process was killed: the next app open starts it.
- A "Turn off" action on the notification, a Live Updates chip, alerts from the
  live connection.
- Battery limits (Battery Saver, idle hours, Wi-Fi only): only if the
  measurement asks for them (decision 5).
- Any change to herdr-core, herdr-push or the Rust hub.

## Decisions

### D1. The service runs whenever the option is on and the app has been opened

`StayConnectedService` is started from the foreground: when the person turns
the option on, and on every activity start while it is on. Android forbids
starting a foreground service from the background (Android 12+), and an
activity start is always allowed. It stops when the option is turned off. The
notification is therefore also visible while the app is open (Ezra,
2026-11-08).

*Alternative:* start the service only as the app leaves the foreground.
Rejected: it rests on a grace period Android may give an app that just left the
top (moderate confidence it exists, unknown on GrapheneOS), and a missed start
silently falls back to today's behaviour.

The service holds no state of its own. `onStartCommand` calls
`startForeground` every time, with notification id 2 (alerts use 1,
`AndroidAlertSink.ID`) and `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`. It collects
`HubHost.snapshot` (a `StateFlow`) and `HubHost.startError` in a coroutine
scope it cancels in `onDestroy`; it never uses `addSnapshotListener`, whose
list only grows. A `StatusNotifier(sink)` posts the line when it changes;
`stop()` cancels the notification and makes later posts no-ops.
`START_NOT_STICKY`: if Android or the person (the foreground-service task
manager) stops the process, nothing restarts it.

### D2. The tracker's 60 s deadline: suspend when off, background idle when on

`ForegroundTracker` gains `keepConnected: () -> Boolean` and
`onBackgroundIdle: () -> Unit` (defaults `{ false }` and `{}`, so V1's
construction and tests stay as they are).

- The deadline fires: `keepConnected()` is read then, at fire time. True:
  `onBackgroundIdle()`, and `suspended` stays false. False: suspend as today.
- The catch-up branch of `activityStarted` (deadline passed, neither callback
  ran, as after deep sleep): true, disarm and call `onCaughtUp`, with no idle
  and no suspend, since the person is back and the terminal never detached;
  `onCaughtUp` re-arms the key prompt (D6), as the 60 s did pass (found in
  the Codex review, task 3.1); false: suspend then resume as today.
- With the option on, `suspended` is therefore false whenever the hub is not
  suspended, and a return calls no `onResume` and so no resume-kick.
  `onForeground` still runs (it only retries `NetworkOff` machines).
- Toggle edges: the toggle is in the app, so in the foreground, where the hub
  is resumed. A deadline armed while on that fires after the option was turned
  off suspends (V1).

`HerdrApp` passes `stayConnected::isOn`, `hubHost::suspend`, an idle
handler that bumps `terminalIdle` (D5) and calls `userAuth.onBackgroundIdle()`
(D6), and `userAuth.onBackgroundIdle()` as `onCaughtUp`.

The alarm side stays the inexact listener alarm plus the Handler, as in V1.
`setAndAllowWhileIdle` has no listener overload; it would need a
`PendingIntent` and a receiver. The cost is that with the screen off the
detach can come late (Risks).

### D3. Notification text is a pure function of the snapshot

`StatusText.of(snapshot, startError): StatusLine(title, text?)`, tested on the
JVM like `Alerts`. Machines fall into four groups:

| Group | States |
|---|---|
| connected | `Connected` |
| key | `NeedsUserAuth` |
| retrying | `Connecting`, `Unreachable`, `KeyRejected`, `NotInstalled`, `Paused` |
| needs the app | `HostKeyUnconfirmed`, `HostKeyMismatch`, `TooOld`, `AppTooOld`, `NetworkOff` |

- `startError` set: "Not connected · open the app".
- At least one connected: the title is the counts, from agents on connected
  machines only (other rows are stale). "Need you" counts `NeedsYou` and
  `Error` (both alert on the "Needs you" channel); "working" counts `Working`.
  The second line: "Connected" when all machines are connected; "Connected to
  n of m machines · unlock to reconnect" when another machine waits for the
  key; else "Connected to n of m machines".
- None connected, in this order: any key, "Disconnected · unlock to
  reconnect"; any retrying, "Reconnecting"; any needs the app, "Not connected ·
  open the app"; no machines, "No machines". No second line.

No agent, project, folder or machine names, so the lock screen shows the same
text (`VISIBILITY_PUBLIC`). The notifier reposts only when `StatusLine`
changes, so progress ticks do not hit Android's update rate limit. A
notification the person swiped away (Android 14+) comes back with the next
change.

### D4. A fourth channel, "Status", low importance

`AlertChannel` gains `Status("status")`, created with the other three,
`IMPORTANCE_LOW`: no sound or vibration, an icon in the status bar. The
notification is ongoing, silent, `CATEGORY_SERVICE`; a tap opens the app as it
was left (no agent). `IMPORTANCE_MIN` would hide the status-bar icon but risks
Android's own "running in the background" notice for a foreground service (low
confidence on current versions); the person can lower the channel in Android
settings.

If notifications are not allowed, turning the option on asks for the
permission through the existing Alerts flow (`MachinesRoute`). Refused, the
service still runs, unseen, and the row reads "On · notifications are off"
(also when the Status channel is blocked).

### D5. An open terminal detaches after background idle and reopens on return

`TerminalSession` and `TerminalOpener` are single-use (`close()` is final; the
opener opens once), so a reopen is a new session:

- `HerdrApp.terminalIdle: StateFlow<Int>`, bumped by the idle handler (D2).
- `TerminalRoute` keeps `var generation by remember(key)`. The session, the
  opener and the cache-load `DisposableEffect` key on `(key, generation)`;
  `phone` stays `remember(key)`.
- A collector, `LaunchedEffect(key, generation) { app.terminalIdle.drop(1)
  .collect { ... } }`, runs while the activity is stopped (a coroutine, not a
  composition read). On a bump, if the lifecycle is at least STARTED it does
  nothing (the person is back, mirroring the tracker's catch-up rule);
  otherwise it closes the session (detach, cache store as today), keeps the
  frame `close()` returns as `lastFrame` in route memory, and sets `detached`.
- `LifecycleEventEffect(ON_START)`: if `detached`, `generation++` and
  `detached = false`. The new session is seeded with `showCached(lastFrame)`
  from memory; the disk load is skipped while `lastFrame` is held, so no
  Keystore decrypt and no dependence on the key window. The phone-grid
  `LaunchedEffect(phone, generation)` calls `onPhoneGrid` again at the last
  size, and the new opener opens.
- The decisions live in a small `TerminalReopen` holder tested on the JVM.

The desk takes its size back at the detach. Reconnects inside the 60 s stay as
today: the Rust core reattaches the open terminal (`term.rs`).

### D6. Background authentication: retry silently, prompt only if that fails

`UserAuthGate(retry, stayConnected: () -> Boolean, scheduler: Scheduler)`. The
existing `host`, `showing`, `armed` and `needsUserAuth` stay; new are
`retrying`, `fallback` and `waiting` (some machine in `NeedsUserAuth` or in a
backoff: `Unreachable`, `KeyRejected`, `NotInstalled`).

- `onSnapshot`: as today, plus: while `retrying`, a `NeedsUserAuth` snapshot
  is ignored (it may be stale, and the `Connecting` in between may have been
  dropped); the first snapshot without `NeedsUserAuth` (`kick` moves every
  waiting machine to `Connecting` in one publish) ends `retrying` and cancels
  the fallback. A `NeedsUserAuth` after that is the real result and takes the
  normal prompt path.
- `maybePrompt()` also requires `!retrying`.
- `onHost(h)` with the option on: if `h != null && needsUserAuth && armed &&
  !showing && !retrying`, `silentRetry()` and no prompt. Otherwise, and always
  with the option off, as today. With a host present the gate would already
  have prompted, so this only fires after a real absence.
- `onUnlocked()` (`ACTION_USER_PRESENT`): `retrying`, nothing;
  `needsUserAuth`, `silentRetry()` whatever `armed` is (the unlock opened the
  window; if it still fails, `maybePrompt` respects `armed`); else `waiting`,
  one plain `retry()`, which also restarts backoffs stalled by CPU sleep.
- `silentRetry()`: `retrying = true`, `retry()`, and a fallback at +10 s that
  sets `retrying = false` and calls `maybePrompt()`; this covers a dropped
  `Connecting`, and a `retry` that did nothing. The fallback runs on the
  Handler and stalls in deep sleep. That is deliberate: with no host it only
  clears `retrying`, and the next unlock or start retries. Do not move it to
  an alarm.
- `onBackgroundIdle()` (option on): `armed = true`, the role V1's `Paused`
  snapshot plays. So a cancelled prompt does not switch the automatic prompt
  off for the rest of the process (V1's "Opening from an alert"), while a
  device-credential screen's restart or a rotation shortly after a cancel
  neither dials nor prompts.

The service registers the receiver in `onCreate` with `RECEIVER_NOT_EXPORTED`
and unregisters it in `onDestroy`; with the option off there is no receiver and
the hub is suspended in the background. `retry` kicks every non-active machine,
which is harmless. An unlock by a trust agent or a weak biometric fires
`USER_PRESENT` without a key token: the silent retry fails, and the prompt
comes on the next activity start.

Both orders of `USER_PRESENT` and the activity start give one silent retry: the
first starts it, the second sees `retrying`.

## Risks / Trade-offs

- [Battery: 15 s heartbeats wake the phone all day] → measure a night and day
  windows at the phone check and ship the estimate as a notice (tasks 4.6,
  5.1); add Battery Saver and idle stops only for a clearly large cost
  (decision 5).
- [GrapheneOS may close sockets despite the service] → phone check under
  forced Doze (`dumpsys deviceidle force-idle`) and with the screen off for
  30+ minutes.
- [Timers stall while the CPU sleeps] → tokio's timers (backoff, the 45 s
  dead-link timer, the SSH keepalive) and the Handler count awake time only. A
  link that dies silently can read "Connected" until the phone wakes and the
  dead-link timer runs out (about 50 s of awake time), and a backoff can wait
  for a wake. Mitigation: a retry on every unlock (D6). Accepted otherwise: the
  notification is a hint; ntfy is the alert path (decision 3).
- [The screen-off detach comes late] → the desk keeps the phone's size until
  about a minute after the screen next turns on, unless the app is reopened
  first. Accepted (D2).
- [Unlock in the background may not open the key window] → low (Keystore auth
  tokens are per user, moderate confidence); the gate's prompt on the next
  activity start covers it. Phone check 4.2.
- [A process kill ends it silently] → acceptable: the notification goes with
  it, so its absence is the signal.

## Migration Plan

No stored data changes; the option's preference is new and absent means off.
android-client-v1's machine-connection rule "Connections end in the
background" gains "unless Stay connected is on" (that change is not archived
yet, so it is edited in place, and the specs never contradict). Rollback is
turning the option off, or an app version without the service.

## Open Questions

None. Battery has no pass mark, as the cost differs per phone (Ezra,
2026-11-08). The phone check measures one phone and the result ships as an
estimate in a notice (tasks 4.6, 5.1). References from Ezra's August 2026
battery investigation on that phone: a normal night at about 2.0 %/h screen-off
(1.93 and 2.08), daytime screen-off at 2.5-6.4 %/h, and screen-on use at
9-16 %/h. One day reading cannot price a cost below about 1 %/h, so the night
reading carries the estimate. An open `herdr --remote` session cost +0.3 to
+1.0 %/h at night. That was a different client, the herdr CLI's terminal UI in
Termux redrawing over SSH, so it is a reference point for "a held connection to
the workstation", not a prediction. This feature's 15 s heartbeat is roughly
500 packets an hour against that session's roughly 3,500 (low confidence that
the cost scales with packets, and none that the two clients behave alike).
