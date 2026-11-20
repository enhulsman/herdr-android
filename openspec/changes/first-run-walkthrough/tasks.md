Tests come first in every section: write the section's tests, see them
fail, then build. Each task is one unattended run and names its check.
**[owner]** needs his answer or his eyes; **[phone]** needs a phone.
Sections 2 and 3 need neither; execute 2.1 → 2.2 → 2.3 → 2.4 → 2.5,
then 3.1 → 3.2 → 3.3 → 3.4 → 3.5 (3.2 tests deliberately fail until 3.3).
They implement logic and test seams with numbered stub screens only, not
approved production screens. They keep the
proposed wording in `strings.xml` only, where 1.1 can change it in one
place. Section 4 waits for 1.1 and 1.3. All tasks below remain unchecked until
implemented and verified; this fix round completes their instructions only.
No unattended task settles an owner decision or ships the proposed texts.
Do not introduce dependencies or new stored fields.

Checks, from the repository root unless said otherwise:
- Rust: `cargo test --locked --features kudde-mobile/vt,kudde-mobile/spike`
- sshd: `KUDDE_REQUIRE_SSHD=1 cargo test --locked -p kudde-mobile --features spike --test sshd`
- Android, from `android/`: `./gradlew :app:testDebugUnitTest`
- Goldens, from `android/`: `./gradlew :app:verifyRoborazziDebug`

## 1. Decisions and renders

- [ ] 1.1 **[owner]** Answers to the proposal's decisions 1 to 7, and approval of every text in `design.md` "Texts" and of the command in D3, word for word. Record them in `decisions.md`; correct `design.md`, the spec and `strings.xml` to the approved wording. Check: `openspec validate first-run-walkthrough --strict`
- [ ] 1.2 Draw renders 13 to 18 and the changed renders 1 and 8 in `design/renders/screens.html`, graphite and paper, from the table in `design.md` "New and changed screens", with invented names only; add their components to `design-tokens.json` and their rows to `design-brief.md`. Check: each opens with `?s=<id>`; `python3 -m json.tool design-tokens.json`
- [ ] 1.3 **[owner]** Sign off the renders; save them to `design/checkpoints/` as 13 to 18, and 1 and 8 again

## 2. The core (Rust), no phone needed

- [ ] 2.1 Tests for the setup command in `ssh.rs`: the middle line taken out of the command equals `authorized_keys_line` for the same key; the fingerprint invocation before the closing subshell names `ssh_host_ed25519_key.pub`, `ssh_host_ecdsa_key.pub` or `ssh_host_rsa_key.pub` for ED25519, ECDSA and RSA, the loop over `ssh_host_*_key.pub` for any other type, and no host key part for none; the delimiter never occurs in the line; empty, CR or LF comments are refused. Assert the D3 preflight, 700/600 corrections before append, checked failure path and both variants' identical installation block. Check: Rust
- [ ] 2.2 Build `setup_command(spki, comment, host_key_algorithm)` beside `authorized_keys_line` and export it (design D3). Check: Rust
- [ ] 2.3 Tests first in `crates/kudde-mobile/tests/sshd.rs`, then extend the fixture: run the exact generated D3 command through sh, bash and zsh (missing shells fail under `KUDDE_REQUIRE_SSHD=1`, otherwise explicit skip). For each shell cover fresh HOME, owner-owned existing `.ssh` 0775, `authorized_keys` 0400, another key without final newline, and the phone line already present. Assert status 0, directory 700, file 600, exact restricted line on its own line, other key bytes preserved, fingerprint equals the fixture's public host key, and rerun preserves other keys. Substitute only the host public-key path. Add wrong-owner (where feasible), symlink and wrong-type refusals plus deterministic mkdir, chmod and append failures (e.g. private PATH wrappers delegating except the selected operation): every failure is nonzero with D3 stderr and no fingerprint; missing ownership capability is an explicit skipped case, never an untested pass. Run as an ordinary user so 0400 is meaningful. Authentication checks MUST use `StrictModes yes` and `AuthorizedKeysFile` pointing at the command-produced `$HOME/.ssh/authorized_keys`, not today's separate fixture file. `SetEnv HOME` alone does not change the account home sshd validates: choose a fixture file path and parent modes/ownership that StrictModes accepts for the actual login account, and document that check. Prove both existing and phone keys authenticate, the forced command runs `connect`, and pty/forwarding restrictions remain. Do not weaken existing restriction assertions. Check: sshd

- [ ] 2.4 Tests for failure kinds: each `ReachKind` from its error (the dial timeout, `ConnectionRefused`, `HostUnreachable`, `NetworkUnreachable`, a lookup of a `.invalid` name, anything else, and `CoreStartupClosed` only for the exact pre-hello conditions in D6; cover missing marker/127, nonzero status, diagnostic stderr, handshake parse failure and post-connect drop as ineligible); hub tests with the fake dialer that `MachineState::Unreachable` carries the kind, and that a drop after a connection is `Other`. Fake-dialer/command-exit tests must drive actual `confirm_host_key` → first settlement → `attempting` → second settlement: first eligible close has `startup_retry_pending=true`, same state retained with null retry deadline at retry start, second identical close clears the flag. First `NetworkOff`/mismatch (no retry), rejected key, missing core, auth and version failures are never hidden. Confirmed pin A with null last-connected survives User update after KeyRejected and NotInstalled; B is still mismatch, while host/port changes clear A. Check: Rust
- [ ] 2.5 Build the tested `ReachKind` through `SshError`, `DialError::Failed` and `MachineState::Unreachable` (CoreStartupClosed originates at command exit, not dial). Add the D6 hub-owned, default-false `MachineView.startup_retry_pending` and epoch-scoped first-attempt allowance; clear on next settlement, pause, restart, manual retry and connection. Add tests for these resets and stale-epoch callbacks before implementation. Regenerate the uniffi bindings; the Kotlin side compiles with the kind ignored for now. Check: Rust, then Android

## 3. The app's logic (JVM tests), no phone needed

- [ ] 3.1 Tests in `MachineFormTest`: the name from the address (first label of a host name, a whole IPv4 or IPv6 address, trimmed); a derived name that is taken, too long or empty is reported on Name; a typed name wins; today's checks unchanged. Then build it in `MachineForm.kt`. Check: Android
- [ ] 3.2 Tests in `MachineFlowsTest` for `Screen.Setup` (spec: three steps, survives leaving, compared before trusted): the step from the machine's state; "Continue" lists and waits; reached opens step 2; a failure stays on the step; remove/add only for an unpinned never-connected machine; update a pinned or previously connected machine, keeping Name locked and preserving pin A for User edits with host/port unchanged; explicitly cover null lastConnectedMs after both KeyRejected and NotInstalled, locked Name, B still mismatch and host/port edits clearing the pin; trust confirms the shown fingerprint only and never without the dialog; only `CoreStartupClosed` with hub `startup_retry_pending=true` is hidden; replay actual hub snapshots (same failure at retry start, next_retry_ms set → null → set, pending true → true → false) and show the second identical failure without Connecting; repeated or skipped snapshots cannot rearm it; immediate first NetworkOff/mismatch/auth/rejection/missing-core/version/reach outcomes show, using the existing changed-key confirm flow; pause/restore/manual retry do not rearm grace; connected opens step 3 by itself, also after a hub retry; step 3 holds through a drop; back from each step; restore after process death on each step; a tapped alert mid-setup; the card's "Check the host key" and "Add the phone key" open step 2. Check: Android
- [ ] 3.3 Build `Screen.Setup` in `MachineFlows.kt` in place of `AddMachine` and `PhoneKey` (design D6), with stub screens that show the step's number, so the app still runs. Implement 3.2's pin-preserving update rule and render waiting solely from the hub's pending flag; never count snapshots or assume Connecting separates attempts. Check: Android
- [ ] 3.4 Tests in `MachineStatusTest` and `AgentsModelTest`: the two lines of text per failure kind (CoreStartupClosed uses Other's reason text once grace ends), the same on the step and the card, with the card's "Change the address" row; the home's empty state for no machines, no screen lock, a start failure with a lock, an unfinished machine, connected without agents; the "finish setup" notice for each waiting state and none for a connected or connecting machine. Then build `reachText`, `AgentsEmpty.NoScreenLock`, `AgentsEmpty.Unfinished` and the notice. Check: Android
- [ ] 3.5 Tests, then build: the screen-lock check as a seam the home model takes (`isDeviceSecure`), read on each return, and the three intents in order with each one missing (design D7); the share intent's action, type, text and title, and Copy's clip, both byte for byte the command (design D8). Check: Android

## 4. The screens (after 1.1 and 1.3)

- [ ] 4.1 Golden tests listed for renders 13 to 18, 1 and 8 in graphite and paper, steps 1 to 3 at font size 2x, and step 1 with each failure kind; semantics tests: each step announces "Step n of 3" and its heading, the command is one node named "Setup command" that is not a live region, a failure is announced once. Check: Android (they fail until 4.2 to 4.6)
- [ ] 4.2 The welcome and the screen-lock notice on the home; "Import a machine list" wired to the Machines screen's import; "Setup guide" if decision 6 says yes. Check: Android, goldens against renders 13 and 14 **[owner]**
- [ ] 4.3 Step 1: the fields in the new order with hints and help, "Continue", the waiting lines, the failure block with "Try again". Check: Android, goldens against render 15 **[owner]**
- [ ] 4.4 Step 2: the sentence with machine and user, the command box, Copy and Share, the notes, "Compare the fingerprint", the dialog's new texts, the states after trust with "Try again", "Unlock" and "Change the address". Check: Android, goldens against renders 16, 17 and 8 **[owner]**
- [ ] 4.5 Step 3: connected, the agent count following the snapshot, "Show my agents", "Start a terminal or agent" opening the New sheet with the machine chosen, "Done", the alerts line. Check: Android, goldens against render 18 **[owner]**
- [ ] 4.6 The home's "finish setup" notice and "Continue setup"; "New terminal" under "No agents yet"; the Machines card's texts and links; the Phone key row with the command without the host key part and "Copy only the key line". Remove what is no longer reached (`AddMachineScreen`'s old order, `PhoneKeyScreen`'s old texts, strings no longer used). Check: Android, goldens against render 1 **[owner]**

## 5. Docs

- [ ] 5.1 README: "Setting up" step 2 rewritten to the app's three steps, with the command described and not repeated; "What you need" gains the screen lock; the herdr-core link at `README.md:75` corrected to `herdrdev/herdr-core`; a short "When setup stops" list (host keys outside `/etc/ssh`, fish, an account with a group-writable default mode, `scripts/check-restricted-key.sh`). Public wording rules apply. Check: `git diff --stat` touches only `README.md`; the pre-commit denylist passes
- [ ] 5.2 Release notes draft, shown to the owner word for word before it ships **[owner]**

## 6. On a phone

- [ ] 6.1 **[phone]** Test phone, debug build with its data cleared, against the desk with the fake agent: the welcome; step 1 with a wrong address, then the right one; the command shared to a notes app and pasted at the desk; the fingerprint compared; step 3; "Show my agents". Then the same with the command not run (the "did not accept" text, then fixed at the desk without touching the phone), and with the app left for three minutes on step 2. Note the reason texts the phone gives for a name that does not exist and for a closed port. Follow `docs/phone-testing.md`; afterwards remove the test line from the desk's `authorized_keys`
- [ ] 6.2 **[phone]** **[owner]** TalkBack through the three steps (he listens; adb taps cannot drive TalkBack), and one pass at font size 2x; restore both settings
- [ ] 6.3 **[phone]** Android 17 test phone, kudde as built (target SDK 36): it connects to a machine on the Wi-Fi with no system dialog; note whether Android's settings offer a "Nearby devices" switch for kudde and what turning it off does. Result into `research/first-run-walkthrough-2026-11.md` and into the issue for the move to target SDK 37
- [ ] 6.4 Not on a phone: the screen-lock notice is checked in Robolectric only (3.5). Do not remove a test phone's screen lock: that destroys its phone key

## 7. Review and close

- [ ] 7.1 Codex adversarial review of the branch, with the focus: the command's quoting, nothing signed before trust, the setup flow's state after process death
- [ ] 7.2 `openspec validate first-run-walkthrough --strict`
- [ ] 7.3 Open the follow-up issues named in the proposal's decision 7 and in design D7, after showing the owner their text **[owner]**
