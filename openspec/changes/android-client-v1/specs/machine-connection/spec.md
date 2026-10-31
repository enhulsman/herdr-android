# machine-connection Specification (delta)

## ADDED Requirements

### Requirement: Machines are an app-local list
The app SHALL keep its own list of machines, each with a display name, an SSH
host (a Tailscale name or address), a port and a user. The app SHALL connect to
each listed machine directly and SHALL NOT depend on one machine's core to reach
another.

#### Scenario: Two machines, one asleep
- **WHEN** WSL and the Mac are listed and the Mac is asleep
- **THEN** WSL's agents are shown and the Mac is shown as unreachable, with the time it was last connected

### Requirement: Each machine is reached by the herdr-core remote command over SSH
The app SHALL open an SSH session to the machine and run the same command the
desktop core uses for remote machines, which finds `herdr-core` on the PATH or
falls back to `$HOME/.local/bin/herdr-core`, then runs `connect`. The app SHALL
send `hello` with `framed: true` and SHALL NOT send the `core` or `calls_you`
fields.

#### Scenario: herdr-core only in ~/.local/bin
- **WHEN** the machine's non-interactive PATH lacks `herdr-core` but `~/.local/bin/herdr-core` exists
- **THEN** the connection succeeds

#### Scenario: herdr-core missing
- **WHEN** neither location holds `herdr-core`
- **THEN** the machine shows "herdr-core not found on this machine" and no retry storm follows (backoff applies)

### Requirement: The phone key lives in the Android Keystore
The app SHALL generate its SSH key pair on first use inside the Android Keystore
so the private key cannot be exported. If that proves impossible with the SSH
library, the app SHALL instead keep an Ed25519 key in app-private storage
encrypted by a Keystore key. The app SHALL show the public key as a complete
`authorized_keys` line restricted to the herdr-core command (`restrict` plus a
forced `command=`), with a copy button.

#### Scenario: Setting up a new machine
- **WHEN** Ezra adds a machine
- **THEN** the app shows the restricted `authorized_keys` line to paste on that machine, and connects once it is installed

#### Scenario: Key is never exported
- **WHEN** the app's data is backed up through Android backup
- **THEN** the private key is not part of the backup (backup excluded, Keystore-bound)

### Requirement: Host keys are confirmed once and then enforced
On the first connection to a machine the app SHALL show the host key fingerprint
and connect only after Ezra confirms it. On later connections the app SHALL
refuse to connect if the host key differs, and SHALL say so on the machine.

#### Scenario: Host key changed
- **WHEN** a machine presents a host key different from the confirmed one
- **THEN** the app does not connect, shows "host key changed" with both fingerprints, and offers only "Forget this machine's key" behind a confirmation

### Requirement: The protocol version is checked before use
After `hello`, the app SHALL compare the core's protocol generation and the
methods it uses against the version it was built and tested for. On a mismatch
it SHALL show the core's version and the tested version and SHALL NOT call
methods the core lacks.

#### Scenario: Older core
- **WHEN** a machine runs a herdr-core that lacks a method the app needs
- **THEN** the machine shows "herdr-core x.y.z is older than this app supports (tested: 0.1.15)" and stays disconnected

### Requirement: Connections recover on their own
When a connection drops, the app SHALL retry after 1 s, backing off to at most
30 s, SHALL resubscribe to events, SHALL reattach an open terminal with
`resumed: true`, and SHALL refetch documents after a `missed` event or a
reconnect. While disconnected, data shown for that machine SHALL carry
"as of hh:mm".

#### Scenario: Network switch
- **WHEN** the phone moves from Wi-Fi to mobile data while a terminal is open
- **THEN** the terminal shows "reconnecting", then resumes without changing the terminal's size and without a duplicate first paint

### Requirement: Remote terminals of another core are not listed twice
The app SHALL show each terminal only through its own machine's connection, and
SHALL skip terminals a connected core lists for machines it added itself
(addresses of the form `machine/t7`).

#### Scenario: Mac core has WSL added
- **WHEN** the Mac's core lists `workstation/t3` and the app is also connected to WSL directly
- **THEN** t3 appears once, under WSL
