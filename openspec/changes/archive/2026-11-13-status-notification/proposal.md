# Proposal: stay connected, with a status notification

## Plain summary

**Decisions** (Ezra, 2026-11-08: the pick for each, "OK with 1-7"):
1. **What the notification shows.** (a) Counts: "2 need you · 3 working";
   a tap opens the app. (b) Only "Connected to WSL". (c) Counts plus the first
   agent's name. *Pick (a)*: useful at a glance, and no agent or project names
   on the lock screen.
2. **The connection drops while the phone is locked and the key's 30 minutes
   have passed.** The phone key only signs within 30 minutes of an unlock
   (2026-11-02), so it cannot reconnect then. (a) Stay disconnected, say
   "Disconnected · unlock to reconnect", and leave alerts to ntfy. (b) A loud
   "unlock to reconnect" notification. (c) Let the key sign without an unlock
   while this is on. *Pick (a)*: (b) duplicates ntfy, (c) undoes the key
   decision. A connection that stays up never needs the key again.
3. **Alerts.** (a) The notification never alerts; ntfy stays the only alert
   path. (b) The live connection alerts and the pushes are held back. *Pick
   (a)*: one alert path you can trust, no duplicates.
4. **On or off.** (a) Off by default, switched on in the app. (b) On by
   default. *Pick (a)*.
5. **Battery.** (a) Measure the drain at a phone check against a daily figure
   you set, and only then decide on limits. (b) Stop by itself on Battery Saver
   or after some hours of no activity. (c) Only on Wi-Fi or while charging.
   *Pick (a), adding (b) if the figure is bad.* The cost is unknown: herdr-core
   sends a heartbeat every 15 seconds, which wakes the phone (confidence: low
   on the size).
6. **Status-bar chip (Android 16 Live Updates).** (a) No. (b) A needs-you count
   in the chip. *Pick (a)*: Android's guidelines rule out alerts and ambient
   status in the chip, and GrapheneOS support is unverified.
7. **A terminal left open when you switch away.** (a) Detach it after 60
   seconds and keep only the agent list live. (b) Keep it attached. *Pick (a)*:
   the desk keeps its own size, and it costs less.

**What changes for you:** with the option on, the app keeps its connection to
your machines while it is in the background. A quiet notification shows how
many agents need you and how many are working. Coming back to the app is
instant, with no reconnect and no key prompt. Alerts still come through ntfy,
as now.

## Why

Android ends the app's connections about 5 seconds after it leaves the
foreground (2026-11-02), so every return reconnects, and now and then asks for
the key. A foreground service with a visible notification is the way Android
allows a connection to stay open. Decided 2026-11-03 as a post-V1 feature.

## What Changes

- An option on the Machines screen: "Stay connected", off by default.
- A foreground service of type `specialUse` holds the hub's connections while
  the option is on; it has no time limit, unlike `dataSync` (6 hours a day on
  Android 15+). A held connection survives Doze while the service runs
  (Android's own rules; on GrapheneOS to be checked on the phone).
- A fourth notification channel, "Status", quiet; the notification text comes
  from the agent list.
- Reconnecting in the background never prompts; without the key it waits for
  an unlock and says so (decision 2).

## Capabilities

### New Capabilities
- `stay-connected`: the option, the service and its notification, what it
  shows, reconnecting in the background and with an expired key, what happens
  to an open terminal, and how it relates to ntfy alerts.

### Modified Capabilities
- None in `openspec/specs/` (V1 is not archived yet). V1's machine-connection
  rule "the connection ends 60 seconds after the app leaves the foreground" no
  longer holds while the option is on; `stay-connected` states the exception.

## Impact

- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, the service
  with its `specialUse` subtype.
- `ForegroundTracker` / `HubHost`: no suspend while the option is on.
- `UserAuthGate`: a background mode that shows text instead of a prompt.
- Notifications: the Status channel; the text as a pure function of the hub
  snapshot, tested like `Alerts`.
- Tests: JVM/Robolectric for the tracker, the gate and the text; on the phone:
  forced Doze (`dumpsys deviceidle force-idle`) and a battery measurement
  (`dumpsys batterystats`).
- Findings behind this proposal:
  `local/spec-research/spec-status-notification-research.md` (not tracked).
