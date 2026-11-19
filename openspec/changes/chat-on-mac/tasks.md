## 1. Facts first

- [ ] 1.1 Find how the phone reaches a Mac's herdr-core; if it cannot, drop the phone scenarios from the delta and note it in `decisions.md`
- [ ] 1.2 On the MacBook: what Claude Code writes in `~/.claude/sessions/<pid>.json` (`procStart` included), what `ps -o comm=` and `etime` print, and what Codex holds open; write it down; revise the spec per design D2 if needed

## 2. Scripts and decision

- [ ] 2.1 Golden test: the Linux branch of every script is byte-identical
- [ ] 2.2 Tests first with fake tools, covering every chat-view scenario in the delta
- [ ] 2.3 The Darwin branch in the lookup, tail and image scripts; new `Probe` fields; `decide` and `claude()` follow 1.2
- [ ] 2.4 The phone's `uname -s` check, cached on success
- [ ] 2.5 Tests first for D3 fresh send checks: Codex `/new` and `/resume` at either boundary with unchanged pid/start, ambiguous/unreadable open files, Claude session changes, target retained through uploads and second Enter; run `lsof` for each Codex check

## 3. Desk and phone

- [ ] 3.1 After desktop-chat 2.8 and sections 3–4 pass (including 3.8 and 4.10), integrate the desktop delta drafted here against its pending requirements: Mac agents supported, "not on a Mac yet" removed; no archival prerequisite; show the revised Windows notice verbatim to the owner (R1-E) before building it in
- [ ] 3.2 Try on the MacBook: local agents in the desk chat, Mac agents from Windows, two agents in one folder, and the phone's switch if 1.1 found a route
- [ ] 3.3 `openspec validate chat-on-mac --strict` and `openspec validate desktop-chat --strict`; review both before desktop-chat section 6; archive desktop-chat then chat-on-mac after joint release
