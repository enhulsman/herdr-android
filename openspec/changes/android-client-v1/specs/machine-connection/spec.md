# machine-connection Specification (delta)

## ADDED Requirements

### Requirement: Machines are an app-local list
The app SHALL keep its own list of machines (display name, SSH host as a
Tailscale name or address, port, user) and SHALL connect to each directly,
never through another machine's core. The list SHALL be exportable and
importable without keys.

#### Scenario: A second machine is off
- **WHEN** WSL and a second machine are listed and the second machine is off
- **THEN** WSL's agents are shown and the second machine is shown as unreachable with the time it was last connected

#### Scenario: Restoring the list on a new install
- **WHEN** Ezra imports an exported machine list into a fresh install
- **THEN** the machines appear without keys or host keys, and each asks for setup

### Requirement: Each machine is reached by the herdr-core remote command over SSH
The app SHALL open an SSH session and run the command the desktop core uses for
remote machines (find `herdr-core` on PATH, else `$HOME/.local/bin/herdr-core`,
then `connect`). It SHALL send `hello` with `framed: true` and without `core` or
`calls_you`, and SHALL skip up to 64 KiB of output before the first JSON line.

#### Scenario: herdr-core only in ~/.local/bin
- **WHEN** the non-interactive PATH lacks `herdr-core` but `~/.local/bin/herdr-core` exists
- **THEN** the connection succeeds

#### Scenario: herdr-core missing
- **WHEN** the command exits 127 with "herdr-core is not installed here" on stderr
- **THEN** the machine shows "herdr-core not found on this machine" and retries follow the backoff

#### Scenario: Shell noise before the protocol
- **WHEN** the login prints a banner line before herdr-core's first JSON line
- **THEN** the banner is skipped and the connection succeeds

### Requirement: The phone key is hardware-bound and needs the owner
The app SHALL generate its SSH key in the Android Keystore, non-exportable, and
SHALL require user authentication (fingerprint or device credential) for its use,
valid for 30 minutes. If the SSH library cannot sign through the Keystore, the
app SHALL use an Ed25519 key in app-private storage wrapped by a Keystore key
with the same rule. The key SHALL be excluded from backups.

#### Scenario: Opening from an alert
- **WHEN** Ezra taps an alert more than 30 minutes after the last authentication
- **THEN** one fingerprint prompt appears, then the terminal opens

#### Scenario: Unlocked phone in other hands
- **WHEN** someone opens the app on an unlocked phone without Ezra's fingerprint or device credential
- **THEN** no connection to any machine is made

#### Scenario: Key survives an update
- **WHEN** Obtainium updates the app to a newer release
- **THEN** the same public key is in use and machines connect without new setup

### Requirement: The key is restricted to herdr-core on each machine
The app SHALL generate the machine's `authorized_keys` line as `restrict` plus a
forced `command=` holding the quoted remote command, with a copy button. The
app SHALL support only machines whose SSH server is OpenSSH (Tailscale SSH ignores
`authorized_keys`).

#### Scenario: Other commands are refused
- **WHEN** the phone key is used to request any command other than the forced one, a pty, or a port forward on WSL
- **THEN** the forced herdr-core command runs instead or the request is refused, checked at the M1 phone check

#### Scenario: Quoting survives sshd
- **WHEN** the generated line is installed on a throwaway sshd in CI
- **THEN** a connection runs herdr-core's `connect` exactly as the desktop's remote command does

### Requirement: Host keys are confirmed once and then enforced
On the first connection the app SHALL show the host key fingerprint and connect
only after confirmation; later it SHALL refuse a different host key and say so.

#### Scenario: Host key changed
- **WHEN** a machine presents a host key different from the confirmed one
- **THEN** the app does not connect, shows both fingerprints, and offers only "Forget this machine's key" behind a confirmation

### Requirement: The protocol version is checked before use
After `hello` the app SHALL refuse a core older than it supports, naming both
versions. A newer core SHALL connect with no notice: CI runs the protocol suite
against the latest herdr-core release, and bugs in a newer one become issues
(phone check round 2).

#### Scenario: Older core
- **WHEN** a machine runs a herdr-core lacking a method the app needs
- **THEN** the machine shows "herdr-core x.y.z is older than this app supports" and stays disconnected

#### Scenario: Newer core
- **WHEN** a machine runs a newer herdr-core than tested
- **THEN** the app connects and shows only that core's version on that machine

### Requirement: Calls time out honestly
Every call SHALL have a timeout (10 s; `terminal.interrupt` 15 s). When a call that
may already have reached the terminal times out, the app SHALL report "outcome
unknown, check the terminal" and SHALL NOT report failure.

#### Scenario: Slow interrupt
- **WHEN** `terminal.interrupt` gets no reply within 15 s
- **THEN** the app shows "outcome unknown, check the terminal"

### Requirement: Streams keep flowing and dead links are noticed
The app SHALL return CREDIT as it consumes data on attach and event channels,
SHALL reject frames over 16 MiB before allocating them, and SHALL reconnect when
no frame arrives for 45 s.

#### Scenario: Long output
- **WHEN** a terminal prints 5 MiB of output while open on the phone
- **THEN** the view keeps updating to the end

#### Scenario: Silent link
- **WHEN** the network path drops without closing the connection
- **THEN** the app shows "reconnecting" within 45 s

### Requirement: Connections recover on their own
After a drop the app SHALL retry after 1 s backing off to 30 s, SHALL resubscribe
events, SHALL reattach an open terminal into a fresh VT, and SHALL refetch
documents after `missed` or a reconnect. Data shown while disconnected SHALL carry
"as of hh:mm".

#### Scenario: Network switch
- **WHEN** the phone moves from Wi-Fi to mobile data with a terminal open
- **THEN** the terminal shows "reconnecting", then the current screen, with no stale content and no size change

### Requirement: Connections end in the background
The app SHALL close its connections 60 s after it leaves the foreground,
unless Stay connected is on (status-notification change).

#### Scenario: Pocketed phone
- **WHEN** the app has been in the background for 60 s
- **THEN** no SSH session to any machine remains open

### Requirement: Each terminal is listed once
The app SHALL show each terminal only through its own machine's connection and
SHALL skip terminals a connected core lists for machines it added itself
(addresses `machine/t7`).

#### Scenario: A core with an added machine
- **WHEN** a connected core lists `other/t3` for a machine it added
- **THEN** the app does not show `other/t3` through that core
