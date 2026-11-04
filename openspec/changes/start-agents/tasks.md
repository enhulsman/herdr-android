Tests come first in every section: write the section's tests (listed in its
first task), see them fail, then build. Scratch-core tests use a real zsh and a
real bash with `HOME` (and `ZDOTDIR`) pointed at a temp folder holding the rc
file, never a stub script; the core's `shell` / `$SHELL` select them
(config.rs:33-38).

## 1. Shared core (Rust)

- [x] 1.1 Tests: layout reader (real document with spaces, unnamed space, empty and `~/` and relative folders, zero spaces, none, unreadable); folder list (distinct cwds, foreground first, home first, home unknown); fresh space id (`sp` + 32 hex); agent table (15 entries in the desk's order, every word one `[a-z0-9._-]+`); D1 wrapper string asserted whole for a word; D5 check string; check parse (home with spaces, markers glued to an escape prefix, `claude` first, no `herdr-done` = failure, zero agents = success); `program_of` names the shell for a wrapped start; compat completeness (every `call("...")` in `crates/*/src` plus `terminal.attach`, `events.subscribe` is in `USES`); herdr-push `placed()` both scenarios
- [x] 1.2 Move `herdr-push/src/layout.rs` and `Layout` to `herdr-proto`; add the spaces reader, lenient as now
- [x] 1.3 Folder resolution and folder list without spaces (design D2); fresh space id
- [x] 1.4 herdr-push `placed()`: a non-empty `herdr.space` label counts as placed (design D6); amend V1's alerts requirement in `openspec/changes/android-client-v1/specs/alerts/spec.md` in place with the two scenarios
- [x] 1.5 Agent table from the desk's `defaults/programs.json` (name, icon, word, order), source noted; wrapper and check strings and the check parser (design D1, D5); `program_of`
- [x] 1.6 `compat.rs`: add `terminal.run`, `machine.exec`, `doc.get`, `doc.set` to `USES`

## 2. Starting (herdr-mobile hub)

- [x] 2.1 Scratch-core tests: Terminal start (label, absolute cwd, `keep` on the record, no argv); agent start with zsh and with bash (rc defines `claude` only as an alias for a marker program: the marker runs; the marker exits 0: the shell remains, same folder); Darwin (stub `uname` printing `Darwin` first on the core's PATH, a shell that records its flags: check and start use `-lic`, the remaining shell `-li`); check against the real shells (alias counts, `claude` first in the list, home printed, a failing inner shell still yields home, fish-like failure gives no shortcuts, 5 s timeout); `spawn_failed` for a missing folder (no terminal created); zero spaces gets a fresh label
- [x] 2.2 `new_terminal(machine, space | folder, agent?)`: one `terminal.run` per design D1 and D2; refusals mapped to the sheet's text (design D7)
- [x] 2.3 Installed-agents check over `machine.exec` (design D5): background at connect, `timeout: 5`, successes cached per machine per app start, failures retried on the next sheet open
- [x] 2.4 uniffi surface for the sheet (machines, spaces or folders, home state, check state, agents)

## 3. App (Android)

- [ ] 3.1 Render in `design/`: the "New" entry on the Agents screen and the sheet (machine, space or folder, Terminal, the reserved agent row, error and "home folder unknown" states); Ezra signs it off
- [ ] 3.2 JVM tests: one machine skips the choice; nothing connected disables "New" with a reason; the agent row holds its place until the check answers and keeps its height; double tap starts once; errors keep the sheet open with the text; success closes the sheet and opens the terminal
- [ ] 3.3 "New" entry and the sheet per the render; start button disabled while in flight
- [ ] 3.4 On success open the terminal screen at phone size (design D7); on failure keep the sheet with the reason
- [ ] 3.5 Roborazzi screenshots for the sheet's states

## 4. Close-out

- [ ] 4.1 Strike "Not planned: starting new agents" from the V1 proposal; in the README move the item from "Planned (not yet specified)" to "Next (specified)" as "New terminals and agents from the phone: open a terminal in a space, or start an installed agent there"
- [ ] 4.2 Phone check: a terminal in a space appears as a desk tab; the Claude Code shortcut starts through the alias and alerts with the desk app closed; quitting it leaves the shell; a machine with zero spaces gets a new space; the missing-folder error
- [ ] 4.3 Release notes draft (shown verbatim before it ships)
