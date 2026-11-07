# Proposal: new terminals and agents from the phone

## Plain summary

Two steps in one change; step 1 is useful on its own and ships first.

**Step 1, New terminal.** From the Agents screen you open a new terminal in a
space: your normal shell, in the space's folder, as a new tab on the desk. It
opens on the phone at once, and you type whatever you would type at the desk,
`claude` included.

**Step 2, Agent shortcuts.** In the same sheet, a row of the agents installed
on that machine (Claude Code, Codex, ...). Tapping one starts it directly in
your own interactive shell, in the space's folder, so your aliases for it
apply. When the agent quits, the shell stays, as on the desk.

**Decisions** (Ezra, 2026-11-04: all five as picked, plus three follow-ups):
1. **Folder.** (a) Pick a space and start in its folder; on a machine without
   spaces, pick from the folders its open terminals are in. (b) Always pick
   from the open terminals' folders. (c) Type a path. *Pick (a)*, typing left
   for later.
2. **Where it shows on the desk.** (a) A new tab in that space; the desk's view
   does not move. (b) A split beside the space's last pane. *Pick (a)*: what
   the desk's own "new tab" does.
3. **Alerts while the desk app is closed.** herdr-push alerts only for
   terminals placed in the desk's layout, and the desk app places a new
   terminal only while it runs. (a) herdr-push also alerts for terminals
   labelled with a space that the desk has not placed yet. (b) Quiet until the
   desk app runs. *Pick (a)*: otherwise an agent started from the phone is
   silent exactly when you are away from the desk.
4. **Which agents step 2 offers.** (a) The agents the desk app knows (its
   built-in table, all 15 it can start: Claude Code, Codex, Pi, OpenCode,
   Cursor, Copilot, Amp, Gemini, Grok, Antigravity, Crush, Hermes, Kimi, Kiro,
   Qoder), showing only those the machine has: the phone asks the machine's
   interactive shell once, as the desk does. No setup. (b) A list you keep per
   machine in the app. (c) Both. *Pick (a)*; (c) later if a missing agent
   turns up. The desk app's own `programs.json` additions live on the desk
   machine, which the phone cannot reach, so they do not carry over.
5. **Guardrails for step 2.** (a) The bare command only (`claude`, `codex`,
   `cursor-agent`): no flags, nothing set from the phone, no first message
   (the terminal is open, Compose is right there), and the sheet always shows
   machine, space and folder before you tap. (b) Also a first message, sent
   once the agent reports it is ready. *Pick (a)*: the phone can already type
   anything into any terminal, so this adds no power; the guardrails only keep
   a tap from doing something you did not see.
6. **How an agent starts.** Directly, not by typing into a fresh shell. The
   agent's word goes literally into your interactive shell's command, so an
   alias such as `claude` with your own flags still applies. Lost: the start
   is not in your shell history. No setting to choose between the two.
7. **A machine without spaces.** When the desk's layout has no spaces, or
   there is no layout at all, the phone makes the first one: the desk shows it
   as a new space named after its folder, and it alerts like any other.

**What changes for you:** a "New" button on the Agents screen opens a sheet:
machine (when there are several), space, then either "Terminal" or one of the
agents installed there. The new terminal opens on the phone, at the phone's
size, and appears on the desk as a new tab.

## Why

Away from the desk, the phone can answer and steer agents but not start
anything, so an idea on the go waits until you are back. herdr-core already
starts terminals over the same connection (`terminal.run`), and the desk app
places a terminal labelled with a space by itself, so the phone needs no new
access and the desk no change. Decided 2026-11-03 (decisions.md), reversing
V1's "not planned: starting new agents"; shaped 2026-11-04: the new terminal is
the core, agents are a shortcut on top.

## What Changes

- Agents screen: a "New" entry opening a sheet (machine, space, Terminal or an
  agent). New screen: render in `design/` first.
- Step 1: `terminal.run` with no command (the machine's own shell) in the
  space's folder (absolute path), labelled with the space so the desk places
  it, and kept like a desk terminal. Refusals are shown in the sheet.
- Step 2: the installed agents, asked of the machine's interactive shell over
  `machine.exec` when the machine connects and cached; a tap starts the agent
  directly in that shell, which stays when the agent quits.
- A machine with no spaces gets a fresh space id, so the desk makes its first
  space.
- herdr-push: a terminal labelled with a non-empty space counts as placed.
- V1's "Not planned: starting new agents" is struck from the V1 proposal and
  the README roadmap.

## Capabilities

### New Capabilities
- `new-terminal`: starting a terminal in a space from the phone, where it
  lands on the desk, what the phone shows after, the errors, and the agent
  shortcuts (which agents, how they are found, how a tap starts one).

### Modified Capabilities
- None in `openspec/specs/` (V1 is not archived yet). The V1 alerts
  requirement "The watcher follows the shared attention model"
  (`openspec/changes/android-client-v1/specs/alerts/spec.md`) is amended in
  place when herdr-push changes (task 1.4), so the rule lives in one spec.

## Impact

- `crates/herdr-proto/src/compat.rs`: `terminal.run`, `machine.exec`,
  `doc.get` and `doc.set` join `USES` (the last two are already called); a
  test keeps `USES` complete.
- `crates/herdr-proto`: the layout reader (moved from herdr-push), the start
  wrapper and check strings, the agent table, `program_of` naming the shell
  for a wrapped start.
- `crates/herdr-mobile` hub: the start and check calls over uniffi.
- `crates/herdr-push/src/rules.rs`: the placement rule (decision 3).
- Android: the sheet on the Agents screen, then the existing terminal screen;
  agent names and icons from the existing program table (`program_id`).
- Tests: scratch-core tests with a real zsh and bash and their rc files in a
  temp home (aliases, quitting, Darwin flags, labels, `keep`, refusals); JVM
  tests for the sheet's choices.
- Findings behind this proposal: `local/spec-research/` (not tracked).
  Checked on WSL 2026-11-04: the core's own PATH does not reach `claude` or
  `codex`, the interactive shell does; a missing folder fails with
  `spawn_failed`; `machine.exec` sees `SHELL=/usr/bin/zsh`; `zsh -ic` and
  `bash -ic` expand an alias given literally in the command string, not one
  passed as `"$@"`. The desk app's agent table and its `command -v` check:
  `research/src/herdr-app/defaults/programs.json`, `src/programs.rs`.
