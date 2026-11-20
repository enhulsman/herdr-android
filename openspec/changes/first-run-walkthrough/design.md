# Design: a clearer first setup

## Context

What a new person meets today, in order (all read 2026-11-20):

1. **The home, empty.** "No machines yet", "Add a machine that runs
   herdr-core to see its agents here." and "Add machine"
   (`AgentsScreen.kt:575-581`, `strings.xml:29,66-67`). Nothing says what is
   needed first. On a phone without a screen lock the first screen is an
   error instead: "Could not start the connections" with the key store's
   own message (`AgentsModel.kt:125-127`, `HubHost.kt:53-64`,
   `KeyManager.kt:51-60`).
2. **The form.** Name, Host, Port (22), User, no hints, one note about
   Tailscale SSH, "Add machine" (`AddMachineScreen.kt:86-146`,
   `strings.xml:153-162`). After the tap: "Connecting to host:port…". A
   machine that cannot be reached sends the person to the Machines screen
   (`MachineFlows.kt:142-143`), where the card reads "Unreachable" with the
   SSH library's own words and a countdown (`MachinesModel.kt:160-167`); a
   wrong address waits out a 20 second timeout (`link.rs:21`). The address
   cannot be corrected: the hub has `update_machine` (`hub/mod.rs:796`),
   and no screen calls it, so a typo means "Remove machine" and a new one.
3. **The host key.** "Trust this machine?" with the fingerprint (render 8,
   `HostKeyViews.kt:62-76`). It says to check the fingerprint and not how;
   the command is only in the README (`README.md:98-100`).
4. **The detour.** After "Trust and connect" the Machines screen shows
   "Phone key not accepted · retrying in 1 s" with a row "Add the phone
   key" (`MachinesModel.kt:180-183`). That row opens the line with "Copy"
   (`PhoneKeyScreen.kt:73-93`). How the line gets to the machine and which
   command appends it is again only in the README (`README.md:101-104`).
   The app then waits for a retry that backs off to 30 seconds
   (`hub/mod.rs:1101-1105`); the card has no "Try again" for this state.
5. **Connected.** The card reads "Connected · herdr-core x.y.z". Nothing
   leads back to the home; there, "No agents yet" has no action
   (`AgentsScreen.kt:591-594`), and a machine that still waits for the
   person shows only as a ring on the Machines button
   (`AgentsModel.kt:124,136-140,170-171`).

So the strict path works, but it is told as a failure to repair, and two of
its three desk actions are explained outside the app. Herdr's own app says
what is needed on one screen before asking for anything; that is the part
to learn from. Its key and host-key handling are not (see
`research/first-run-walkthrough-2026-11.md`).

Settled and unchanged here: the host key is confirmed before first trust
and before any signature (`ssh.rs:267-268,286-288`), the phone key lives in
secure hardware and is installed as a `restrict,command=` line
(`ssh.rs:137-155`), and the app never handles a password.

## Goals / Non-Goals

**Goals:**
- Before anything is asked: what setup needs, in three points.
- The strict path as three forward steps, with the two desk actions in one
  command and nothing explained only in the README.
- Every way setup stops says what happened and what to do, on the step.
- Setup ends on the first agent, or on the way to start one.

**Non-Goals:**
- Any change to what is trusted or signed, or to the key's restrictions.
- A demo mode, pairing by QR code, finding machines on the network, a
  password sign-in that installs the key (decisions 3 and 7 of the proposal).
- Alert setup inside the walkthrough: it needs `kudde-push` and an ntfy
  server, and keeps its place on the machine's card.
- The local network permission (D9).

## Decisions

### D1. The welcome is the empty home, not a gate

The welcome shows while the machine list is empty and at no other time.
There is no "seen" flag: nothing to store, nothing to reset, and a person
who removes their last machine gets it back. It replaces the "No machines
yet" block inside the Agents home; the top bar with the Machines button
stays, so importing a list and the phone's settings remain one tap away.
Android's guidance asks for exactly this: land in the real screen with one
clear action, show what the app is for before any permission, no slides.
*Over* a full-screen intro shown once: that needs state, hides the app's
own screen, and is the pattern the guidance warns about.

### D2. Address first, then one visit to the machine

Order: where the machine is, then the desk, then connected. Reaching the
machine first costs nothing in strictness: the first connection stops
after the host key and signs nothing (`ssh.rs:286-288`). It buys three
things. Wrong addresses, a VPN that is off and a closed port show up
before the person walks to the desk. The desk step can name the machine
and the user. And the app knows which host key type the machine presents,
so the command prints that one key.
*Over* key first: the command would have to print every host key, and an
unreachable machine would be found only after the desk visit.

### D3. One command, a readable one

Step 2's command, for a machine that presented an ED25519 key (the long
middle line is the app's line as `authorized_keys_line` builds it today):

```sh
(
  umask 077
  if [ -L "$HOME/.ssh" ] ||
     { [ -e "$HOME/.ssh" ] && { [ ! -d "$HOME/.ssh" ] ||
       [ -z "$(find "$HOME/.ssh" -prune -user "$(id -u)" -print)" ]; }; } ||
     [ -L "$HOME/.ssh/authorized_keys" ] ||
     { [ -e "$HOME/.ssh/authorized_keys" ] && { [ ! -f "$HOME/.ssh/authorized_keys" ] ||
       [ -z "$(find "$HOME/.ssh/authorized_keys" -prune -user "$(id -u)" -print)" ]; }; }
  then
    printf '%s\n' 'Setup stopped: ~/.ssh and authorized_keys must be owned by you and must not be links or other file types.' >&2
    exit 1
  fi
  if ! { mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh" &&
         touch "$HOME/.ssh/authorized_keys" && chmod 600 "$HOME/.ssh/authorized_keys" &&
         cat >> "$HOME/.ssh/authorized_keys" <<'KUDDE_KEY'

restrict,command="sh -c '…'" ecdsa-sha2-nistp256 AAAA… kudde
KUDDE_KEY
  }; then
    printf '%s\n' 'Setup failed: could not create, secure or write ~/.ssh/authorized_keys. Check your account permissions and disk space, then try again.' >&2
    exit 1
  fi
  ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
)
```

- One POSIX sh subshell command; also pasteable in bash and zsh on Linux
  and macOS. Uses their common `test -e` and `-L`, POSIX `find -user`,
  `id`, `mkdir`, `chmod`, `touch`, `cat` and `printf`; no GNU-only flags.
- Preflight refuses wrong ownership, links and non-directory/non-regular
  paths before modifying them. No `sudo` or `chown`. The account's home
  and ancestors must already satisfy sshd's StrictModes ownership/mode
  checks; this command does not change the home directory.
- Existing directories are explicitly secured with `chmod 700`. An
  owner-owned read-only file is made 600 before appending. All operations
  are chained: any failure exits before fingerprint printing.
- The quoted here-document passes the generated restricted line through
  unchanged. The leading blank line separates an existing unterminated
  key. Repeating the command may append another identical phone line;
  other key bytes remain untouched.
- The host file follows the presented algorithm: `ed25519`, `ecdsa`,
  `rsa`. For other algorithms replace the fingerprint invocation with:
  `for key_file in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf "$key_file" || exit 1; done`.
  Any fingerprint error exits nonzero. The Phone key variant omits only
  that invocation; the checked installation block remains identical.
- The displayed key above is abbreviated for this design. The generated
  command contains the complete `authorized_keys_line`. Reject a comment
  containing CR/LF and choose/check a delimiter absent as a whole line in
  that output (including the closing subshell syntax in command tests).
- Scratch results and the exact verification boundary are recorded in
  `research/first-run-walkthrough-2026-11.md`, section 6. macOS execution
  and StrictModes authentication remain builder checks, not claims from
  this spec-only run.

Built in Rust beside the line (`ssh.rs`), as
`setup_command(spki, comment, host_key_algorithm: Option<String>)`, and
exported like `authorized_keys_line`. Tests take the line back out of the
command and compare it with the generated line, and the throwaway-sshd
suite (`crates/kudde-mobile/tests/sshd.rs`) runs the command through each
shell before connecting.

Considered:
- *A base64 one-liner* (`echo … | base64 -d >> …`): survives any app that
  rewrites quotes, and works in fish. Rejected as the default because the
  person cannot read what they install, which is the point of showing a
  restricted line. Kept in mind if quote damage turns up in use.
- *`echo '…' >>` with escaped quotes*, as Herdr's app does for its plain
  key: one line, but the restricted line holds both kinds of quotes, and
  the escaping is unreadable.
- *The app signs in with a password once and installs the line* (Termius,
  JuiceSSH, Herdr's app since 1.0.6): no desk visit. Rejected: a full
  login through the app, and hardened machines have passwords off.
- *A helper on the machine that shows a QR code* (Whip, Moshi, Sherpr):
  the strongest check, since no eye compares anything. It needs a program
  on the machine, the camera and a scanner. Its own change if wanted.

Known limits: fish has no here-documents (the step says "For sh, bash and
zsh."); an `sshd` with host keys outside `/etc/ssh` prints nothing to
compare, which the README covers.

### D4. Trust stays render 8's dialog

"Compare the fingerprint" opens the signed-off confirm, with two changes
to its text: the first paragraph says the command printed the host key,
and a note says what a different fingerprint means. A deliberate dialog
for the one act that cannot be undone by a retry fits the strict path, and
it needs no new look. Every way to that dialog now passes step 2, so the
command is always one "Cancel" away (`MachineFlows.openHostKey` opens step
2 for `HostKeyUnconfirmed`; a changed key keeps its own screen).
*Over* the fingerprint inline on step 2: clearer by one tap, but a second
presentation of the same act and a retired render.
Not proposed: typing the last characters of the fingerprint, which would
force the comparison; it is friction the owner did not ask for, and the
QR helper is the better form of that idea.

### D5. The hub says which kind of failure

`MachineState::Unreachable { detail }` gains `kind: ReachKind`
(`NoAnswer`, `Refused`, `NameNotFound`, `NoRoute`, `Other`,
`CoreStartupClosed`). Socket failure kinds are set where the
SSH error is mapped (`ssh.rs:223-237`, `link.rs:93-102,107-126`):

| Kind | From |
|---|---|
| `NoAnswer` | the dial timeout, `io::ErrorKind::TimedOut` |
| `Refused` | `ConnectionRefused` |
| `NameNotFound` | a failed lookup (how tokio reports it on Android is to be pinned by a test with a `.invalid` name) |
| `NoRoute` | `HostUnreachable`, `NetworkUnreachable` |
| `Other` | everything else, with `detail` as today |
| `CoreStartupClosed` | the narrow authenticated, pre-hello command close in D6; created in the hub, not mapped from a socket error |

`detail` stays for `Other` and for logs. One function turns a kind into
its two lines of text; the setup step and the machine card both call it.
The home's notice line keeps today's "unreachable" (render 1). `CoreStartupClosed` uses Other's reason wording when the grace flag is false. A failure after the connection was up (`hub/mod.rs`
1284-1299) is `Other`.

### D6. Setup is a view of the machine's state

`Screen.AddMachine` and `Screen.PhoneKey` become `Screen.Setup`. The step
is worked out from the machine, and only ever moves forward by itself:

- no machine named: step 1;
- a machine with no confirmed host key (`MachineView.host_key`) that is
  not presenting one (`HostKeyUnconfirmed`): step 1, with its address,
  user and port filled in;
- connected: step 3, held until the person leaves it, so a connection
  that drops while it shows does not bounce them back;
- anything else: step 2.

The flow keeps the furthest step it has shown for the machine, so the
"connecting" moments after a return from the background stay on step 2.
With that, a process death, 60 seconds in the background and a tapped
alert all land right with what `MachineFlows.saver` already keeps (screen
and machine), plus the step, whether this visit added the machine (back
from step 1 then removes one that was never reached), and the typed
fields (`rememberSaveable`, as now).

Only the first *eligible startup close* after explicit host confirmation
is held back. It is a candidate startup race, not proof that the core was
starting. Add `ReachKind::CoreStartupClosed` only for a closed stream before
hello after successful SSH authentication, with no nonzero exit status,
no nonblank diagnostic stderr and no missing-core marker. The existing
`command_ended` timeout/no exit result qualifies; exit 127 or the missing
marker stays `NotInstalled`, and other diagnostic exits stay `Other`.
Network/dial errors and handshake parse errors never qualify.

The hub owns the one-attempt grace, not a UI flag. On explicit confirmation,
arm only the first attempt of the newly spawned epoch. At its settlement,
set a new `MachineView.startup_retry_pending` boolean only if the outcome
is `CoreStartupClosed` and a retry was scheduled. Consume the allowance
regardless of that first outcome. Keep the boolean through the scheduled
retry's `attempting` call (which retains the old failure and clears
`next_retry_ms`); clear it atomically at the next settlement, even if the
state and detail repeat. Old-epoch outcomes cannot alter it. Clear it on
pause, restart, manual retry or connection; do not persist it or arm it
on resume/process restore. It defaults false in bindings and test fixtures.
No UI inference from error strings, deadlines, `Connecting`, elapsed time
or number of snapshots. Skipped/repeated snapshots cannot renew grace.
`NetworkOff`, `HostKeyMismatch`, auth, key rejection, missing core and
version failures always show immediately; mismatch uses the existing
changed-key screen and confirmed forget flow.

"Try again" calls the hub's `retry`. Host and port unchanged means the
confirmed pin is preserved, even with `lastConnectedMs == null`. Remove/add
is allowed only for an unpinned, never-connected machine, to follow an edited
address with its derived name. If pinned or previously connected, call
`update_machine`, keep the listed name and lock Name; User remains editable.
The existing `machines.rs:227-231` clears the pin for host/port changes.
No new rename operation or stored-data migration is needed. Test a pin
confirmed before `KeyRejected` and before `NotInstalled`: editing User keeps
A and a subsequent B must still be a mismatch. The locked Name is tested
in each case, not treated as permission to remove/add.

Name: `validateMachine` gains the derived name (text before the first dot
of a host name; the whole text for an IPv4 or IPv6 address), checked
against the same rules and reported on the Name field.

### D7. The screen lock check

`isDeviceSecure` is read when the home is composed and on every return.
False wins over the hub's start error (which, with no lock, is the key
store's "Secure lock screen must be enabled…"). The action sends
`ACTION_BIOMETRIC_ENROLL` with device credential allowed (API 30, which
also offers a PIN), then `ACTION_SECURITY_SETTINGS`, then
`ACTION_SETTINGS`. `HubHost.ensureStarted` already runs on each return
(`HubHost.kt:78-97`).

Not in this change, recorded for an issue: removing the screen lock later
destroys the key for good (`AndroidKeystoreBackend.kt:65`), and the app
then says only "the phone key can no longer be used".

### D8. Copy and Share

Copy as today (`PhoneKeyScreen.kt:110-114`): the button reads "Copied",
no toast, the clip not sensitive. Share: `ACTION_SEND`, `text/plain`,
`EXTRA_TEXT` the command, `EXTRA_TITLE` "kudde setup command",
`Intent.createChooser`. No new permission, no new dependency.

### D9. The local network permission is not in this change

Android 17's `ACCESS_LOCAL_NETWORK` applies to apps that target SDK 37.
kudde targets 36 (`android/app/build.gradle.kts:24`), keeps its access
through `INTERNET`, and per Android's guide must not declare or request
the permission yet. Herdr's app met the prompt because it targets the
newer SDK. So nothing can be built or tested for it now. One phone check
confirms it (tasks 6.3).

It becomes real with the move to target 37, as its own change. For that
change, already known: no dialog appears by itself, a blocked TCP
connection just times out, VPN traffic is said to be exempt (not tested on
a phone), and the request belongs on "Continue" in step 1 for a
local-range address, with this reason shown first: "Android asks before an
app may reach devices on your Wi-Fi. kudde uses this only to connect to
the machines you add." After a denial the step should name the permission
at once and not run a 20 second attempt.

### D10. One link out

"Setup guide" on the welcome, and under "herdr-core was not found", opens
the repository's README at "Setting up" in the browser, and kudde.dev's
Setup page once the site (#83) serves one. One constant. The app has no
other link today; this one is decision 6 of the proposal.

## New and changed screens (each needs the owner's sign-off)

All within the existing language: chrome background, Geist, muted and dim
text, the outlined and solid buttons, `MonoBox`, `SubScreenTopBar`. No new
colour, no icon, no illustration, no logo on the welcome.

| Render | Screen | Built from |
|---|---|---|
| 13 | Welcome on the home | the home's top bar; title at `machineName`; body `body` muted; "What you need" as the "This phone" caption; three points as two-line rows (`rowName`, then `secondary` dim) with the numeral in mono, muted, in the place a row's mark has; solid then outlined button |
| 14 | No screen lock | the same block: title, body, one outlined button |
| 15 | Step 1 | render 8's form: label over field; "Step 1 of 3" as a caption under the top bar, the heading at `machineName`; hints in dim inside the field; help lines in `secondary` dim; the failure as two lines, the first in the error colour, with outlined "Try again" |
| 16 | Step 2 | caption and heading as step 1; the sentence in `DialogText`; the command in `MonoBox`, scrolling inside a height of at most 8 lines so the buttons stay near; "Copy" and "Share" side by side, outlined; notes in `DialogNote`; solid "Compare the fingerprint" |
| 17 | Step 2 after trust, key not accepted | render 16 with the failure block in place of the solid button |
| 18 | Step 3 | caption and heading; a machine card's head (dot, name, status line); the agent line; solid and outlined button; the alerts line in `secondary` dim |
| 8 (changed) | Trust dialog | the same dialog, new first paragraph, one more `DialogNote` |
| 1 (changed) | Home with an unfinished machine | the machine notice line of render 1, reading "workstation · finish setup" |

Goldens for each in graphite and paper, and steps 1 to 3 at font size 2x.

## Texts (every line needs the owner's approval)

`%1$s` and the like are filled in by the app. "Was" names the string it
replaces in `strings.xml`.

**Home**

| Id | Text | Was |
|---|---|---|
| W1 | Set up your first machine | No machines yet |
| W2 | kudde lists the coding agents on your own machines and lets you answer them. It connects to each machine directly, over SSH. | Add a machine that runs herdr-core to see its agents here. |
| W3 | What you need | new |
| W4 | 1 · A machine that runs herdr-core / Linux or WSL, with an OpenSSH server. | new |
| W5 | 2 · A way for the phone to reach it / The same Wi-Fi, or a VPN such as Tailscale. | new |
| W6 | 3 · A few minutes at that machine / You run one command there and compare one fingerprint. | new |
| W7 | Set up a machine | Add machine |
| W8 | Import a machine list | new on this screen |
| W9 | Setup guide | new (decision 6) |
| L1 | Set a screen lock first | Could not start the connections |
| L2 | kudde keeps its SSH key in the phone's secure hardware. Android allows that only on a phone with a PIN, pattern or password. | the key store's message |
| L3 | Set a screen lock | Try again |
| U1 | %1$s · finish setup | new |
| U2 | %1$s is not connected yet / Its setup is not finished. / Continue setup | No agents yet |
| U3 | New terminal (button under "No agents yet") | new on this block |
| U4 | Continue setup / Opens the step this machine is at (card row, when herdr-core is missing or too old) | no row |

**Step 1**

| Id | Text | Was |
|---|---|---|
| S1 | Set up a machine | Add machine |
| S2 | Step %1$d of 3 | new |
| A1 | Where is it? | new |
| A2 | Address · hint: workstation or 192.0.2.10 · help: The machine's name on your network or VPN, or its IP address. | Host |
| A3 | User · hint: your login name there | User |
| A4 | Port | Port |
| A5 | Name · hint: the derived name, or "optional" · help: How kudde lists this machine. Leave it empty to use the address. | Name |
| A6 | Tailscale SSH does not work with the phone key: use an address and port where OpenSSH answers. | The machine needs an OpenSSH server. Tailscale SSH ignores the phone key. |
| A7 | Continue | Add machine |
| A8 | Connecting to %1$s… | unchanged |
| A9 | Still no answer. This can take 20 seconds. | new |

**When the machine cannot be reached** (on the step and on the machine's card)

| Id | Text | Was |
|---|---|---|
| F1 | No answer from %1$s. / Check that the phone is on the same Wi-Fi or VPN as the machine, and that the address is right. | Unreachable · timed out |
| F2 | %1$s answered, but nothing listens on port %2$d. / Check that the OpenSSH server is running there, and the port. | Unreachable · connection failed: … |
| F3 | The phone cannot find a machine called %1$s. / Check the spelling. With a VPN such as Tailscale, check that it is on, on this phone. | Unreachable · connection failed: … |
| F4 | This network has no way to %1$s. / Join the same Wi-Fi as the machine, or turn the VPN on. | Unreachable · connection failed: … |
| F5 | Could not connect to %1$s: %2$s | Unreachable · … |
| F6 | Try again | unchanged |
| F7 | Change the address (step 2 after trust; a row on the card of a machine that cannot be reached) | new |

**Step 2**

| Id | Text | Was |
|---|---|---|
| M1 | At the machine | new |
| M2 | On %1$s, signed in as %2$s, paste this into a terminal and press Enter: | Add this line to ~/.ssh/authorized_keys of the user you sign in as on %1$s. |
| M3 | the command of D3, including its refusals below | the line |
| M11 | Setup stopped: ~/.ssh and authorized_keys must be owned by you and must not be links or other file types. | new terminal stderr; needs owner approval |
| M12 | Setup failed: could not create, secure or write ~/.ssh/authorized_keys. Check your account permissions and disk space, then try again. | new terminal stderr; needs owner approval |
| M4 | Copy / Copied | unchanged |
| M5 | Share | new |
| M6 | Sending it to yourself in a note or a message is fine: nothing in it is secret. | new |
| M7 | It adds this phone's key, then prints the machine's host key. With this key, SSH runs only herdr-core: no terminal session, no forwarding. herdr-core can still run commands as that user, so treat the key like a shell login. | With this key, SSH runs only herdr-core: … |
| M8 | For sh, bash and zsh. | new |
| M9 | Compare the fingerprint | Check the host key (card row) |
| M10 | Setup command (screen reader name) · kudde setup command (share title) | new |
| D1 | First connection to %1$s. The command you ran there printed its host key. Trust it only if the fingerprint is the same: | First connection to %1$s. Check that this fingerprint matches the machine's host key. |
| D2 | Not the same? Do not trust it. Something else answered at that address: Tailscale SSH, or another SSH server on that port. | new |
| K1 | %1$s did not accept the phone key. / Run the command above there, signed in as %2$s, then try again. If it went through a chat or notes app, check that its quotes arrived unchanged. | Phone key not accepted |
| K2 | herdr-core was not found on %1$s. / Install it there as %2$s, then try again. The setup guide has the command. | herdr-core not found on this machine |

Unchanged in the dialog: "Trust this machine?", "After this, a different
key stops the connection.", "Cancel", "Trust and connect". Unchanged after
trust: "Phone key locked", "Unlock", and the too-old texts.

**Step 3, Machines, Phone key**

| Id | Text | Was |
|---|---|---|
| C1 | Connected | new |
| C2 | %1$s is connected. | new |
| C3 | 1 agent is running there. / %1$d agents are running there. | new |
| C4 | Show my agents | new |
| C5 | No agents are running there yet. One you start at your desk in herdr shows up here by itself. | new |
| C6 | Start a terminal or agent | new |
| C7 | Done | new |
| C8 | Alerts while the app is closed are turned on per machine, under Machines. | new |
| P1 | One command to run on this machine (card row "Add the phone key") | One line for this machine's authorized_keys |
| P2 | To let this phone into a machine, run this there, signed in as the user your agents run under: | Add this line to ~/.ssh/authorized_keys of the user you sign in as on each machine. |
| P3 | Copy only the key line | Copy |

## Risks / Trade-offs

- **A chat or notes app rewrites the command's quotes.** The key is then
  not accepted, which is safe, and K1 names the cause. The base64 form
  (D3) is the fallback if it happens in use.
- **The person taps "Trust and connect" without comparing.** As today; the
  dialog now says where the other fingerprint is and what a difference
  means. The QR helper would remove the judgment; it is not in this change.
- **Two words for one thing while both exist.** "Add machine" stays on the
  Machines screen and opens "Set up a machine". Accepted: the first is a
  list action, the second the flow's title.
- **Render 8 changes text.** Its golden and checkpoint are re-recorded
  and signed off again.
- **Step derived from state can surprise.** A machine that connects by a
  background retry jumps to step 3 while the person reads step 2. Wanted:
  it is the "advances by itself" people expect from pairing flows.
- **The name lookup's error text differs per platform**, so
  `NameNotFound` may fall to `Other` on some phones. `Other` still shows
  the reason; a phone check records what the test phones report.

## Migration

None for stored data: the machine list, host keys and the phone key are
untouched. An installed app with machines never sees the welcome. The
README's "Setting up" is rewritten to the app's steps, and its herdr-core
link (`README.md:75`, which points at a repository that does not exist)
is corrected.
