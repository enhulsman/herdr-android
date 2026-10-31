# alerts Specification (delta)

## ADDED Requirements

### Requirement: Alerts arrive through UnifiedPush, not Google
The app SHALL register as a UnifiedPush receiver with the phone's distributor
(ntfy), using the connector's built-in web-push keys, and SHALL NOT use Firebase
Cloud Messaging. It SHALL request the Android notification permission at alert
setup.

#### Scenario: Permission denied
- **WHEN** Ezra denies the notification permission
- **THEN** every machine shows alerts as off, with a way to grant it

### Requirement: The phone registers itself in a core document
The app SHALL write its endpoint and keys into the `hdroid-push` document on each
connected machine and SHALL update them when the endpoint changes. Client document
names SHALL satisfy herdr-core's name rule (1-32 of `a-z 0-9 - _ .`, starting with
a letter or digit).

#### Scenario: First registration
- **WHEN** the app registers while connected to WSL
- **THEN** WSL's `hdroid-push` holds the current endpoint and keys

#### Scenario: Names pass the core's rule
- **WHEN** the tests write `hdroid-push` and `hdroid-push-status` to a scratch core
- **THEN** both `doc.set` calls succeed

#### Scenario: Endpoint changed while away
- **WHEN** the distributor issues a new endpoint while the phone cannot reach WSL
- **THEN** WSL shows "alerts not registered since endpoint change" until the phone next connects and rewrites the entry

### Requirement: The watcher only uses an existing core
`herdr-push` SHALL connect only to an existing herdr-core, taking session and
socket from its config file, SHALL never start a core, and SHALL report "no core"
or "core has zero terminals" in its log and status document.

#### Scenario: Wrong environment
- **WHEN** the systemd unit starts without the session's socket path configured
- **THEN** the watcher reports "no core" and does not start a new core

### Requirement: The watcher follows the shared attention model
`herdr-push` SHALL alert on entry into needs-you and finished as defined by
`herdr-proto::attention`, SHALL suppress sub-agents and terminals not placed in a
pane, and SHALL alert for unplaceable terminals when the layout cannot be read.
A grace delay (default 30 s, configurable) SHALL precede each alert and SHALL
cancel it when the state moves on.

#### Scenario: Answered at the desk within the delay
- **WHEN** an agent becomes blocked and Ezra answers at the desk 10 s later
- **THEN** no alert is sent

#### Scenario: Blocked while away
- **WHEN** an agent becomes blocked and stays blocked for 30 s
- **THEN** exactly one alert is sent to each registered endpoint

#### Scenario: Layout unreadable
- **WHEN** the `herdr-app` document cannot be parsed and an agent becomes blocked
- **THEN** the alert is sent and the status document notes "layout unreadable"

### Requirement: Alerts are neither lost nor repeated across restarts
The watcher SHALL persist the last alerted `(state, turn)` per terminal, SHALL on
start alert for blocked terminals never alerted, and SHALL diff a fresh snapshot
against that record after `missed`.

#### Scenario: Watcher restart
- **WHEN** the watcher restarts while one agent was already alerted and another became blocked during the restart
- **THEN** only the second agent is alerted

### Requirement: Alerts are encrypted, minimal, and sent only to the allowlisted host
Each push SHALL be encrypted with RFC 8291 aes128gcm to the phone's keys and SHALL
carry only `{v, type, machine, terminal?, name?, state?, kind?, at}`, never screen
text or `msg`. The watcher SHALL POST only to endpoints whose scheme and host match
its allowlist, over https, without following redirects, with a 10 s timeout and a
4 KiB body cap, and SHALL send its ntfy access token only to that host.

#### Scenario: What ntfy sees
- **WHEN** an alert passes through ntfy
- **THEN** its body is ciphertext that decrypts with the phone's keys to the listed fields only

#### Scenario: Foreign endpoint
- **WHEN** `hdroid-push` contains an endpoint on another host
- **THEN** that host receives neither the POST nor the token, and the status document reports the ignored entry

### Requirement: Three notification channels
The app SHALL post to three Android channels: "Needs you" (high importance, sound,
lock screen), "Finished" (low importance, silent by default) and "Alert health"
(low importance). Sound, vibration and Do Not Disturb SHALL be left to Android.
Tapping an agent alert SHALL open its terminal.

#### Scenario: Blocked while the app is closed
- **WHEN** the app is not running and an agent stays blocked past the delay
- **THEN** a "Needs you" notification shows the agent name, machine and kind, and tapping it opens the terminal

#### Scenario: Finished turned off
- **WHEN** Ezra has switched off "Finished" in Android settings
- **THEN** finished alerts show nothing and "Needs you" alerts still arrive

### Requirement: Alerts clear when handled anywhere
The watcher SHALL send a `cleared` push when an alerted terminal leaves needs-you,
and the app SHALL remove that agent's notification on receiving it.

#### Scenario: Answered at the desk, app closed
- **WHEN** a "Needs you" notification is showing, the app is closed, and Ezra answers at the desk
- **THEN** the notification disappears

### Requirement: Silence is noticed without claiming a cause
The watcher SHALL send a `canary` push every 6 hours. The app SHALL record the
last push of any kind per machine and SHALL, from a periodic local check that runs
with the app closed, post one "Alert health" notice "No word from <machine> since
hh:mm: the machine is off or alerts are broken." after 13 hours of silence,
cleared by the next push from that machine.

#### Scenario: Machine off overnight
- **WHEN** WSL is off for 20 hours
- **THEN** one quiet "Alert health" notice appears, no "Needs you" alert, and it clears when WSL's next push arrives

#### Scenario: Dead topic
- **WHEN** ntfy accepts pushes for a topic the phone no longer listens to
- **THEN** the "Alert health" notice appears after 13 hours

### Requirement: Delivery status is shown as what it is
The watcher SHALL write `hdroid-push-status` with its version, the time and HTTP
status of its last push accepted or refused by ntfy, per-endpoint status and notes,
on change or every 30 minutes. The app SHALL show "Last accepted by ntfy hh:mm" or
"Alerts failing: <status>" per machine and SHALL NOT call an accepted push delivered.

#### Scenario: Token revoked
- **WHEN** ntfy answers the watcher with 403
- **THEN** the machine shows "Alerts failing: 403" on the phone's next connection

### Requirement: A test alert proves the path
The app SHALL offer "Send test alert" per machine: it writes a request into
`hdroid-push`, the watcher sends a `test` push through the real path, and the app
reports whether it arrived within 30 s.

#### Scenario: Test passes
- **WHEN** Ezra sends a test alert from the WSL machine screen
- **THEN** a test notification arrives and the screen shows "test received"

### Requirement: Mute per machine
The app SHALL let Ezra mute alerts per machine; pushes from a muted machine SHALL be
dropped without a notification and SHALL still count for alert health.

#### Scenario: Muted machine
- **WHEN** WSL is muted and an agent there stays blocked past the delay
- **THEN** no notification appears, and the agent is at the top of the list when the app is opened
