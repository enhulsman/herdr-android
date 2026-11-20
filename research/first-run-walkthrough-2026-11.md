# First setup: what exists, what others do, what we take

Research for `openspec/changes/first-run-walkthrough/`, 2026-11-20. Code
and docs were read at the v1.0.0 commit. Web sources were read the same
day; "not verified" marks what could not be confirmed.

## 1. kudde today, from first launch to the first agent

Every screen and text a new person meets, in order.

| # | Screen | What it says | Where |
|---|---|---|---|
| 1 | Agents home, no machines | "No machines yet" · "Add a machine that runs herdr-core to see its agents here." · button "Add machine". The + in the top bar is dimmed; a tap says "No machine connected" | `AgentsScreen.kt:575-581`, `strings.xml:29,36,66-67`; golden `agents-empty-machines-*.png` |
| 1b | The same, phone without a screen lock | "Could not start the connections" · the key store's own message · "Try again" | `AgentsModel.kt:125-127`, `AgentsScreen.kt:583-589`, `HubHost.kt:53-64`, `KeyManager.kt:51-60` |
| 2 | Add machine | Fields "Name", "Host", "Port" (22), "User", no hints · "The machine needs an OpenSSH server. Tailscale SSH ignores the phone key." · button "Add machine" | `AddMachineScreen.kt:86-146`, `strings.xml:153-160`; render 8 shows the top of it |
| 3 | The same, after the tap | "Connecting to host:port…", fields locked | `AddMachineScreen.kt:133-138`, `strings.xml:162` |
| 3b | Field checks | "Give the machine a name." and eight more | `strings.xml:163-172`, `MachineForm.kt:51-83` |
| 3c | Not reached | The app leaves the form for the Machines screen; the card reads "Unreachable · retrying in 1 s" with the SSH library's text under it | `MachineFlows.kt:142-143`, `MachinesModel.kt:160-167` |
| 4 | Trust dialog | "Trust this machine?" · "First connection to host:port. Check that this fingerprint matches the machine's host key." · key type and `SHA256:` fingerprint · "After this, a different key stops the connection." · "Cancel", "Trust and connect" | `HostKeyViews.kt:62-76`, `strings.xml:175-179`; render 8 |
| 5 | System prompt, when the last unlock is over 30 minutes old | "Unlock the SSH key" · "To connect to your machines" | `strings.xml:4-5`, `UserAuthGate.kt:15-23` |
| 6 | Machines, key not installed | Card "Phone key not accepted · retrying in N s" · row "Add the phone key" / "One line for this machine's authorized_keys" | `MachinesModel.kt:180-183`, `strings.xml:131,150-151`; golden `machines-waiting-*.png` |
| 7 | Phone key | "Add this line to ~/.ssh/authorized_keys of the user you sign in as on NAME." · the line · "With this key, SSH runs only herdr-core: no terminal session, no forwarding. herdr-core can still run commands as this user, so treat the key like a shell login." · "Copy" / "Copied" | `PhoneKeyScreen.kt:73-93`, `strings.xml:195-201`; golden `phone-key-*.png` |
| 8 | Machines, herdr-core missing | "herdr-core not found on this machine · retrying in N s" | `MachinesModel.kt:184`, `strings.xml:133` |
| 9 | Machines, connected | "Connected · herdr-core x.y.z" · "Alerts: Off on this phone" with "Turn on" | `MachinesModel.kt:156-159,236-243`; render 7 |
| 10 | Agents home, connected, no agents | "No agents yet" · "Agents you start in herdr on your machines show up here, the ones that need you first." No button | `AgentsScreen.kt:591-594`, `strings.xml:31-32` |
| 11 | Agents home with agents | The list of renders 1 and 2 | `AgentsScreen.kt:177-189` |

Renders that cover this path: `7-machines` and `8-host-key` only. The
empty home, the form below its second field, the phone key screen and the
changed-key screen have no signed-off render; each file says so.

**At the desk, from the README** (`README.md:71-107`), before and during
the above: install herdr-core as the user the agents run under; have an
OpenSSH server the phone can reach, not Tailscale SSH; compare the host
key with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`; get the
copied line to the machine "in a note or message to yourself"; run
`mkdir -m 700 -p ~/.ssh && cat >> ~/.ssh/authorized_keys`, paste, Enter,
Ctrl-D. Installing the app itself is `docs/install.md`.

**Where people get stuck** (from the code and docs; nobody has been
watched doing this, so the order is a judgment):

1. Nothing on the first screen says what is needed. The list is in the
   README.
2. The README's link to herdr-core under "What you need" points at a
   repository that does not exist (`README.md:75`; the right one is in
   line 10). Checked: 404.
3. A phone without a screen lock opens on an error with the key store's
   wording. Confidence: high from the code; the exact message is from
   Android's source, not seen on a phone.
4. The form has no hints, "Host" is jargon, and a name must be invented.
5. A typo in the address means a 20 second wait (`link.rs:21`), a jump to
   another screen, a raw reason, and no way to edit: remove and add again.
6. The trust dialog says to compare and not how.
7. The key step arrives as a failure ("not accepted"), after trust, and
   the line comes without a command or a way to the desk. The card has no
   "Try again" in this state; the retry backs off to 30 seconds
   (`hub/mod.rs:1101-1105`).
8. The README's command does not set the file's mode. On an account whose
   new files are group-writable, `sshd` ignores the file and the phone
   only says "not accepted". Confidence: moderate (standard StrictModes
   behaviour; not reproduced here).
9. With Tailscale SSH on the machine, a different server answers. The
   form warns in one line; nothing later names it.
10. "herdr-core not found" does not say how to install it or as whom.
11. The first connection starts herdr-core and can read "Unreachable"
    once (`README.md:106-107`).
12. After "Connected" nothing leads to the agents, "No agents yet" has no
    action, and an unfinished machine shows on the home only as a ring on
    the Machines button (`AgentsModel.kt:124,136-140`).

Closed issues #20 and #21 checked these error cases and the docs one by
one; neither changed the order of the flow.

## 2. Herdr's own Android app

From a walkthrough on a test phone (private notes, not in this
repository) and its public pages.

- **One welcome screen.** A headline, a sentence, three numbered points
  (the phone must reach the computer; you connect one machine by its
  address and your user; what that machine knows comes along), then two
  buttons: connect, or try a demo.
- **A three-field form** with a hint in every field and one line of help
  under it.
- **A demo**: two made-up machines whose terminals answer, with a banner
  on every list while it runs.
- **After connecting**, a card shows the host key and offers to find
  further machines.

Why it feels clear: it says what is needed before it asks for anything;
each screen has one job; the form tells you what to type; you can see the
app working before you deal with SSH.

Where it is weaker, and why it can be shorter than kudde:

- The host key is accepted on first sight and shown afterwards. kudde
  shows it first and connects only after a confirm.
- The phone's key is a plain key; its suggested command appends it with
  no restrictions, which is a full login. kudde's key lives in secure
  hardware, and its line allows herdr-core only.
- The key step is not in the flow: it is reached from a failure screen or
  from Settings.
- On Android 17 it asks for local network access with no word of its own,
  and a denial ends in a long wait and "couldn't reach" without naming
  the permission.
- Back from the connect form drops out of the demo.

Its public pages add (https://herdr.dev/mobile/, and its App Store
listing): since 1.0.6 a machine can also be added "by signing in once",
and after the first machine it offers the machines herdr already uses
there, the SSH config's hosts and Tailscale devices. Not verified: its
Play listing would not load.

kudde's stricter behaviour is settled and stays. What is worth taking is
the shape: say what is needed first, one job per screen, hints, and no
step that is only explained elsewhere.

## 3. Other apps

**Other herdr clients**

- Whip (https://github.com/kosumic/whip): a helper on the machine shows a
  QR code; the phone scans it and stores the host key as verified; the
  key is approved in the machine's terminal. By hand: compare the
  fingerprint, then "Trust host"; a changed key is refused.
- Sherpr (https://codeberg.org/alevui/sherpr): a helper prints a QR code
  and a pasteable text, and installs the phone's key. By hand: a plain
  key line, and the host key trusted on first sight.
- Moshi (https://getmoshi.app/docs/install,
  https://getmoshi.app/docs/connections): "Easy Pair" prints a QR code
  holding the host key's fingerprint; the stored key is then labelled as
  verified by the code, versus trusted on first connection. Its changed-
  key screen shows the `ssh-keygen -lf` command. Its setup command checks
  for a Tailscale SSH conflict first.

**Agent apps with an account or relay** (Claude Code remote control,
https://code.claude.com/docs/en/remote-control; Happy,
https://happy.engineering/docs/quick-start/): one command at the desk, a
QR code with a typed fallback, and the session appearing by itself. They
are easy because a service in the middle does the trusting, which kudde
does not have and does not want.

**SSH apps**

- Termius (https://docs.termius.com/keychain/ssh-keys-and-certificates):
  "Export to host" appends the public key over a password login.
- Blink (https://docs.blink.sh/basics/ssh-keys): `ssh-copy-id` from its
  own shell.
- Prompt (https://help.panic.com/general/ssh-keys/) and ConnectBot
  (https://connectbot.org/guides/ssh-keys/): "copy public key", and the
  rest is the person's. ConnectBot asks to compare the fingerprint on the
  first connection (https://connectbot.org/guides/add-host/).
- Secure ShellFish (https://secureshellfish.app/help/ssh-keys): an
  "Install Key" tool for several servers at once.

Those that install the key before the first real connection (the QR
helpers) or with a password once avoid the "it failed, now fix it" turn.
Those that only copy a public key do not. None found installs a
restricted line. Not verified: the first-connection dialogs of Termius,
Blink and Prompt.

**Two devices, one step**

- RFC on cross-device flows
  (https://www.rfc-editor.org/rfc/rfc10027.html, section 6): rely on the
  person's judgment as little as possible, keep the context visible, give
  a clear way to decline; teaching people is not enough on its own.
- GitHub's device flow
  (https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)
  and Tailscale's device approval
  (https://tailscale.com/kb/1099/device-approval): the waiting side names
  its state and moves on by itself the moment the other side acts.

## 4. Android's guidance

- **Onboarding**
  (https://developer.android.com/design/ui/mobile/guides/patterns/onboarding):
  avoid friction before the app's content; show what the app is for
  before any permission; ask for a permission at the moment of need, not
  in bulk; question whether a walkthrough is needed at all; show progress
  in steps; a look at real content persuades more than slides.
- **Empty states**: Material 3 has no page for them that could be found;
  the older Material pages
  (https://m1.material.io/patterns/empty-states.html) say not to leave
  people with nothing to do. Not verified that Material 3 has no
  replacement.
- **Notifications**
  (https://developer.android.com/develop/ui/views/notifications/notification-permission):
  ask from a user action, in context, after people know the app; two
  denials are final, then only settings help. kudde already asks at
  "Turn on" for alerts (`MachinesRoute.kt:168-186`); the walkthrough
  leaves that where it is.
- **Local network**
  (https://developer.android.com/privacy-and-security/local-network-permission,
  https://developer.android.com/about/versions/17/behavior-changes-17):
  `ACCESS_LOCAL_NETWORK`, new in Android 17 (API 37), in the "Nearby
  devices" group. It is enforced only for apps that target SDK 37; an app
  that targets 36 keeps its access and is told not to declare or request
  it. Nothing prompts by itself: an app must ask. Blocked TCP connections
  time out; blocked UDP fails at once. Local means private and link-local
  ranges on Wi-Fi or Ethernet; cellular and VPN interfaces are excluded
  (https://developer.android.com/privacy-and-security/local-network-definition).
  Not verified on a phone: that Tailscale traffic is exempt, and whether
  a person can switch access off for an app that targets 36.
  kudde targets 36 (`android/app/build.gradle.kts:24`), so this is work
  for the move to 37, not for now.
- **Screen lock**: `KeyguardManager.isDeviceSecure` tells whether one is
  set; the documented way to send someone to set one is
  `Settings.ACTION_BIOMETRIC_ENROLL` with device credential allowed
  (https://developer.android.com/identity/sign-in/biometric-auth).
  Without a lock, creating a key that needs authentication throws, with
  the message "Secure lock screen must be enabled…" (Android source,
  `KeyStore2ParameterUtils.java`). Removing the lock later destroys such
  a key.
- **Copy and share**
  (https://developer.android.com/develop/ui/views/touch-and-input/copy-paste,
  https://developer.android.com/training/sharing/send): Android 13 and
  newer confirm a copy themselves, so no toast; share text with
  `ACTION_SEND` through the system sheet, with a title for text that is
  hard to read.

## 5. What we take, and why

| Taken | From | Why |
|---|---|---|
| Say what is needed, in three points, before asking for anything | Herdr's app; Android's guidance | It is the one thing the owner named |
| The welcome is the real home's empty state, with one main action | Android's guidance | No slides, no stored "seen" flag, comes back when the list is empty |
| Hints in the fields, a name that fills itself | Herdr's app | Fewer things to invent |
| The key reaches the machine before the first real connection | Whip, Sherpr, Moshi | Removes the "not accepted, now fix it" turn |
| One command at the machine, whose output carries the host key | Moshi, Whip (with helpers); kudde's README (by hand) | The comparison has both halves on screen; nothing is explained only in the docs |
| The whole command, copyable and shareable, restrictions visible | Herdr's app shows a command; kudde shows what it installs | A line with no instruction is where people stop |
| The waiting step moves on by itself, and names its state | GitHub, Tailscale | The person is at the desk, not looking at the phone |
| Name the one fix for each failure | Moshi's doctor, herdr's own CLI warnings | "Unreachable" plus a library error is not a next step |
| End on the first agent, or on how to start one | Android's guidance | Setup is not done at "Connected" |

| Left | Why |
|---|---|
| Trusting the host key on first sight | Settled: kudde confirms first |
| An unrestricted key, or a password sign-in that installs the key | Settled: restricted key, no passwords through the app |
| A demo mode | Not asked for; a feature of its own size; listed as the owner's decision |
| A QR helper on the machine | The strongest check, but needs a program on the machine, the camera and a scanner; its own change if wanted |
| Finding machines on the network or from another machine's list | Not part of a first setup; kudde lists machines itself by design |
| Asking for local network access | Not applicable at target SDK 36 |

Later ideas noted on the way: accept `user@host` pasted into Address; a
clear message and a way out when the screen lock was removed and the key
is gone.


## 6. Specification fix round: four findings checked, 2026-11-20

The starting premise still holds: this is the unimplemented first-run change
on `spec/first-run`. All four findings hold (confidence: high from the code
and shell reproductions below). Only specification/research files changed;
no application code, test source, settings or task completion boxes changed.

| Finding | Evidence read in this checkout | Specification correction |
|---|---|---|
| 1 — holds | `crates/kudde-mobile/src/hub/mod.rs:827-839` saves confirmation before reconnecting; `:1021-1025` records successful connection later. `crates/kudde-mobile/src/machines.rs:227-234` preserves the pin on User edits. | Design D6 and the reach-failure requirement preserve pins with unchanged host/port even before the first success. Name locks once pinned; remove/add is only for unpinned, never-connected machines. Scenarios and tasks 2.4, 3.2, 3.3 cover KeyRejected and NotInstalled, unchanged host/port, locked Name and refusal of B after confirming A. |
| 2 — holds | `crates/kudde-mobile/src/hub/mod.rs:972-1002` retains the failure at retry start, clears its deadline, then settles again; `:1138-1158` waits without retry for NetworkOff and mismatch. `:1162-1178` distinguishes missing core from early command closure. | Design D5/D6, the after-trust requirement and tasks 2.4–2.5/3.2–3.4 add a narrow CoreStartupClosed classification and hub-owned transient startup_retry_pending flag. Only the first eligible post-confirmation outcome gets one retry; next settlement clears it even for an identical failure. Actionable first outcomes always show. |
| 3 — holds | Original D3 runs chmod/fingerprint independently of append. Reproduced with an owner-owned 0400 file: append denied, key absent, yet fingerprint stdout and exit 0. | D3 now secures the file before append and exits before printing on any install failure. Command requirements/scenarios and tasks 2.1–2.3 cover correction and failures. |
| 4 — holds | Original mkdir leaves an existing 0775 directory unchanged. `crates/kudde-mobile/tests/sshd.rs:178-185` sets StrictModes no and reads a separate authorized_keys file. | D3 explicitly chmods the directory to 700; wrong owners/types/links are refused. Task 2.3 requires StrictModes yes against the command-produced file, with valid actual-login home/ancestor permissions. |

### Executed shell checks

Ran the revised D3 block as the ordinary current user in disposable HOME
directories inside the worktree, then removed every scratch directory. Used
a synthetic public Ed25519 blob (no private key), a harmless restricted test
line, and substituted only the fingerprint path for the scratch public file.
Asserted exact stdout against ssh-keygen, status, modes, line boundaries and
preservation of existing bytes. Repeated each successful case once more.

| Scratch account | sh | bash | zsh | Observed result in every shell |
|---|---|---|---|---|
| Fresh, no .ssh | pass | pass | pass | exit 0; directory 700, file 600; exact phone line and fingerprint |
| Existing .ssh 0775 | pass | pass | pass | exit 0; directory corrected to 700, file 600; fingerprint |
| Existing authorized_keys 0400 | pass | pass | pass | exit 0; file corrected to 600 before append; old key retained; fingerprint |
| Another line without final newline | pass | pass | pass | exit 0; old bytes retained, phone line on its own line; fingerprint |
| Phone line already present | pass | pass | pass | exit 0; existing bytes retained; duplicate restricted line permitted; fingerprint |
| .ssh is an ordinary file (creation blocked) | pass | pass | pass | exit 1; plain type refusal, no fingerprint |
| authorized_keys is a symlink | pass | pass | pass | exit 1; plain refusal, no fingerprint; target unchanged |
| Append fails (private PATH cat wrapper exits 1) | pass | pass | pass | exit 1; plain installation failure, no fingerprint |
| mkdir fails (private PATH mkdir wrapper exits 1) | pass | pass | pass | exit 1; plain installation failure, no fingerprint |
| chmod fails (private PATH chmod wrapper exits 1) | pass | pass | pass | exit 1; plain installation failure, no fingerprint |

30 shell/case checks passed, plus successful-case reruns. An independent
old-command baseline reproduced finding 3; mkdir's existing-directory mode
behaviour accounts for finding 4. This is shell/filesystem evidence only:
no sshd was started, no private key was generated, no real account file was
touched, and no phone or infrastructure was contacted. Wrong-owner execution,
real disk/ACL failures and macOS execution were not tested. Portability
confidence: moderate from the common shell/utility subset, pending macOS
execution. Future task 2.3 supplies the StrictModes authentication evidence;
this run does not claim it.

### Choices left with the owner

Proposal decisions 1–8 and all render/text approvals stay open. No text was
approved or shipped. New terminal refusals M11 and M12 are listed word for
word under design Texts, marked as needing owner approval; the rest of the
on-screen wording is unchanged. The transient hub flag and locked-Name use
of the existing update operation are build decisions, not new stored data
or a rename feature. Tasks 2.1–2.5 and 3.1–3.5 are ready as instructions;
their unchecked boxes correctly mean implementation has not happened.
