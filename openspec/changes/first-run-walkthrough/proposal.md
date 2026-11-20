# Proposal: a clearer first setup

## Plain summary

**Decisions that are yours** (my pick first in each; nothing is built into
the app's screens before 1, 2 and 8 are answered):

1. **The order of setup.** *Where the machine is, then one visit to the
   machine, then connected* (pick). The phone reaches the machine first,
   which signs nothing and trusts nothing, so a wrong address or a VPN that
   is off shows before you walk to the desk. Or: the key first, then the
   address. Or: today's order, with better words only.
2. **One command at the machine.** *One command you paste there adds the
   phone's key, limited as today, and prints the machine's host key, so
   the fingerprint to compare is on the desk's screen* (pick). The command
   is in `design.md` D3, word for word, for your approval. Or: two
   commands, one per step. Or: the bare line as today, with the commands
   staying in the README.
3. **A demo mode: not in this change** (pick). Herdr's app has one: two
   made-up machines whose terminals answer. You did not ask for it, it is
   a feature of its own size (a pretend hub and pretend terminals), and it
   skips exactly the part that is unclear in kudde, which is setup. Or: in,
   as its own issue after this one. Or: in, here.
4. **The welcome is the empty home.** *It shows whenever no machine is
   listed, and never again once one is* (pick). Or: a full-screen intro
   shown once.
5. **The form.** *Address, User, Port, and an optional Name that defaults
   to the address* (pick); "Host" becomes "Address". Or: Name stays
   required, as today.
6. **One link out of the app.** *"Setup guide", to the README's "Setting
   up" now and to kudde.dev's Setup page once the site has one* (pick).
   The app has no link today. Or: no link, and the texts name the README.
7. **Left out on purpose; say so if you want one in:**
   - Pairing by QR code with a helper on the machine (other herdr clients
     do this; it needs a program on the machine, the camera and a
     scanner): its own change.
   - The app signing in with a password once to install its key: no.
   - Alert setup as a fourth step: no, one line points to it.
   - Android 17's local network permission: it applies only to apps that
     target SDK 37. kudde targets 36 and is not affected. It comes with
     the move to 37, as its own issue; `design.md` D9 has what is known.
8. **The words and the look.** Every text on the new screens is in
   `design.md` under "Texts", and needs your approval. Six new renders
   (13 to 18) and two changed ones (1 and 8) need your sign-off.

**What changes for the person using the app:** the first screen says what
kudde is and the three things setup needs, with one button to start.
Setup is three steps. Step 1 asks where the machine is and says in plain
words why it cannot be reached, if so. Step 2 shows one command to paste
at the machine, with Copy and Share; it checks ownership, corrects the SSH
directory and file modes, adds the phone's key and only then prints
the fingerprint, which you compare on the phone before you trust the
machine. Step 3 says the machine is connected and shows your agents, or
how to start the first one. If you leave half way, the app brings you back
to the same step. A phone without a screen lock is told to set one,
instead of seeing an error. What is trusted, and when, stays exactly as
strict as it is.

## Why

The owner tried Herdr's own Android app and found its first setup clearer
than kudde's. It is: one screen says what is needed before anything is
asked. kudde opens on "No machines yet", then a form without hints, then a
fingerprint with no word on where to find the other one, then "Phone key
not accepted", then a line to copy with no word on what to do with it. The
strict path works, but the app tells it as a failure to repair, and the
two desk actions are explained only in the README. Version 1.0 means
"someone else can set the app up without help" (decisions.md, 2026-11-13);
the README still does part of that work.

Herdr's app is quicker because it does less: it trusts the host key on
first sight and its suggested command installs an unrestricted key. Those
are not to copy. The job is to make the strict path read as plainly as
theirs.

## What Changes

- The Agents home, empty: a welcome with what kudde does, what setup
  needs in three points, "Set up a machine" and "Import a machine list".
- A phone without a screen lock: "Set a screen lock first" and a button,
  in place of "Could not start the connections".
- "Add machine" becomes a three-step setup, the same for every machine:
  "Where is it?", "At the machine", "Connected".
- Step 1: field hints, an optional name, and a reason in words when the
  machine cannot be reached (no answer, port refused, name not found, no
  route), with the address editable in place.
- Step 2: one command that appends the restricted key line, sets the file
  modes and prints the host key's fingerprint; Copy and Share; then the
  signed-off trust dialog, which now says where the other fingerprint is
  and what a different one means.
- Confirmed host keys survive User edits even before the first connection;
  only one eligible core-startup close waits for a retry. Actionable failures
  appear immediately, including a changed host key.
- After trust: "did not accept the phone key" and "herdr-core was not
  found" on the step, each with what to do and "Try again"; the step moves
  on by itself when the machine connects.
- Step 3: the machine is connected, how many agents run there, "Show my
  agents" or "Start a terminal or agent".
- The home shows a machine whose setup is unfinished, with a way back in;
  "No agents yet" gets a "New terminal" button.
- The hub reports which kind of failure it met, so the screens need not
  read error texts.
- README: "Setting up" follows the app's steps; the broken herdr-core
  link is corrected.

## Capabilities

### New Capabilities
- `first-run`: the welcome, the screen-lock notice, the three setup
  steps, the setup command, what each failure says, the way back into an
  unfinished setup, and setup with a screen reader and large text.

### Modified Capabilities
- None. `machine-connection` (in `android-client-v1`) keeps every rule:
  host keys confirmed before first trust, the restricted line, no
  signature before trust. This change adds the command around that line
  and changes words on the trust dialog.

## Impact

- Rust (`crates/kudde-mobile`): `setup_command` beside
  `authorized_keys_line`; `MachineState::Unreachable` gains a kind; both
  cross the uniffi boundary. The throwaway-sshd tests run the command in
  sh, bash and zsh.
- Android: `MachineFlows` (one `Setup` screen in place of `AddMachine`
  and `PhoneKey`), the form, the home's empty states and notices, the
  machine card's texts and links, `strings.xml`. No new permission, no
  new dependency.
- Design: renders 13 to 18, changed renders 1 and 8, `design-tokens.json`
  entries for the new components, goldens in both themes.
- Docs: README "Setting up" and "What you need"; release notes.
- Stored data: none changes. People who already have machines never see
  the welcome.
- Phone checks: a full first setup on the test phone, and one look at
  Android 17 for the local network question. The screen-lock notice is
  checked in Robolectric only: removing a test phone's lock would destroy
  its key.
- Estimate: 7 to 10 builder sessions, plus the renders and two phone
  checks. Confidence: moderate.

## Sources

`research/first-run-walkthrough-2026-11.md` (what exists today with paths,
Herdr's app, other clients and SSH apps, Android's guidance); closed
issues #20 and #21, which checked first-run errors and the docs but did
not reshape the flow.
