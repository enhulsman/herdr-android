# alerts Specification (delta)

## ADDED Requirements

### Requirement: Alerts arrive through UnifiedPush, not Google
The app SHALL register as a UnifiedPush receiver with the distributor on the
phone (ntfy) and SHALL NOT use Firebase Cloud Messaging. The app SHALL write its
endpoint and web-push keys (`p256dh`, `auth`) into the `herdr-android/push`
document on each connected machine, and SHALL update them when the endpoint
changes.

#### Scenario: First registration
- **WHEN** the app registers and is connected to WSL and the Mac
- **THEN** both machines' `herdr-android/push` documents hold the phone's current endpoint and keys

#### Scenario: Endpoint rotated
- **WHEN** the distributor issues a new endpoint
- **THEN** the app replaces the old entry on every machine on its next connection

### Requirement: herdr-push sends one alert per state change
`herdr-push` SHALL run next to each herdr-core, connected to it locally, and
SHALL send an alert when a local terminal enters `blocked` or `done`. It SHALL
send one alert per entry into a state, SHALL NOT alert for states already
present when it starts, and SHALL ignore terminals of machines its core added.

#### Scenario: Permission prompt
- **WHEN** an agent on WSL becomes blocked
- **THEN** exactly one alert is sent to each registered endpoint

#### Scenario: Watcher restart
- **WHEN** `herdr-push` restarts while two agents are blocked
- **THEN** no alerts are sent for those two

### Requirement: Alert contents are encrypted and minimal
Each alert SHALL be encrypted with web-push encryption (RFC 8291, aes128gcm) to
the phone's keys, and SHALL carry only `{v, machine, terminal, name, state,
kind?, at}`. It SHALL NOT carry screen text or the program's `msg`.

#### Scenario: What ntfy sees
- **WHEN** an alert passes through ntfy
- **THEN** its body is ciphertext and decrypting it with the phone's keys yields only the listed fields

### Requirement: Two notification channels
The app SHALL post alerts to two Android notification channels: "Needs you"
(high importance, sound, shown on the lock screen) for blocked, and "Finished"
(low importance, silent by default) for done. Sound, vibration and Do Not
Disturb SHALL be left to Android's channel settings. Tapping an alert SHALL open
that agent's terminal.

#### Scenario: Blocked while the app is closed
- **WHEN** the app is not running and an agent becomes blocked
- **THEN** a "Needs you" notification appears with the agent name, machine and request kind, and tapping it opens the terminal

#### Scenario: Finished turned off
- **WHEN** Ezra has switched off the "Finished" channel in Android settings
- **THEN** done alerts show nothing and blocked alerts still arrive

### Requirement: Alerts clear when handled
The app SHALL remove an agent's notification once the phone sees that the agent
is no longer blocked (answered here or at the desk).

#### Scenario: Answered at the desk
- **WHEN** Ezra answers a prompt on the Mac while the phone is connected
- **THEN** the phone's "Needs you" notification for that agent disappears

### Requirement: Mute per machine
The app SHALL let Ezra mute alerts per machine; alerts from a muted machine
SHALL be dropped on the phone without a notification.

#### Scenario: Mac muted
- **WHEN** the Mac is muted and an agent there becomes blocked
- **THEN** no notification appears, and the agent still rises to the top of the list

### Requirement: A silent watcher is noticed
`herdr-push` SHALL write `herdr-android/push-status` with its version and the
time, every 5 minutes. When the phone is connected to a machine and that time is
more than 15 minutes old, the machine SHALL show "Alerts from this machine are
not running".

#### Scenario: Watcher stopped
- **WHEN** `herdr-push` on WSL has been stopped for 20 minutes and the app is connected to WSL
- **THEN** WSL shows the warning
