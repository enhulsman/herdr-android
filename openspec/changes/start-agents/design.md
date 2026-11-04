# Design: new terminals and agents from the phone

## Context

The phone talks to each machine's herdr-core over `herdr-core connect`, which
already allows `terminal.run`, `terminal.send` and `machine.exec`
(decisions.md: "an unrestricted shell"). The desk app (herdr-app) keeps its
layout in the core's `herdr-app` document (`{spaces: [{id, name, folder,
tabs}]}`) and adopts any live terminal labelled `herdr.space=<id>` that it does
not show yet: a new tab in that space, a split when `herdr.tab` names a tab,
or a new unnamed space when the id is unknown; the desk's view does not move
(`research/src/herdr-app/src/window.rs` 1953-2055). It does this only while it
runs, and sets `keep` on what it adopts.

The desk's own "new tab" on a machine is `terminal.run` with no `argv` (the
core starts that machine's shell, `/usr/bin/zsh` on WSL, interactive as it has
a terminal), the space's folder as `cwd`, the space label and `keep: true`
(hc.rs:372). Its local agent start is
`$SHELL -ic '"$@"; exec "$0" -i' $SHELL <cmd>` (`-lic`/`-li` on macOS,
window.rs:110-121, 2238-2244). Checked on WSL 2026-11-04: desk tabs have
`argv ["/usr/bin/zsh"]` and `herdr.space`; the core's own PATH does not reach
`claude` or `codex`, the interactive shell does; a missing `cwd` fails with
`spawn_failed`; `machine.exec` sees `SHELL=/usr/bin/zsh`; `zsh -ic` and
`bash -ic` expand an alias written literally in the command string, but not
one passed as `"$@"`.

## Goals / Non-Goals

**Goals:**
- Step 1: a new terminal in a space, from the phone, as the desk would make it.
- Step 2: agent shortcuts that start the agent's bare command in the person's
  interactive shell, aliases included, the shell staying after it quits.
- herdr-push alerts for a phone-started terminal before the desk has placed it.

**Non-Goals:**
- Typed commands, flags, a first message, templates, custom agent entries.
- Splits, renaming or closing anything.
- New spaces, except the first one on a machine that has none (D2).
- Agent shortcuts on fish, nu, PowerShell or Windows cores: "Terminal" only.

## Decisions

**D1. Starting.** Both starts are one `terminal.run` with the chosen folder as
`cwd`, `labels {"herdr.space": <id>}` and `keep: true`, as the desk's own
starts.
- *Terminal:* no `argv`, so the core starts the machine's shell, as the desk's
  new tab does.
- *Agent:* `argv ["sh", "-c", <wrapper>, "sh"]`, where `<wrapper>` is, for the
  table's word `<word>`:

  ```
  case $(uname -s) in Darwin) f=-lic i=-li;; *) f=-ic i=-i;; esac; exec "${SHELL:-sh}" $f "<word>; exec \"\$0\" $i" "${SHELL:-sh}"
  ```

  The outer `sh` picks the flags (login on macOS, as the desk), then replaces
  itself with the person's shell, whose command is the word followed by
  `exec "$0" -i`: the agent runs as if typed, so an alias for it applies, and
  when it exits the shell takes its place. `<word>` is only ever a table entry,
  one word of `[a-z0-9._-]` (D5), so inserting it literally needs no quoting.
  Nothing from the phone's input reaches it.

  Rejected: typing the command into a fresh shell (`terminal.send` after a
  prompt-ready wait): no sound signal for "prompt ready" (silent rc phases, rc
  prompts that would eat the command, the person typing at the same time), so
  it cannot be specified or tested; Ezra chose the direct start. Rejected: the
  desk's local form with the command as `"$@"`: it skips aliases. The desk's
  remote form (`sh -c '"$@"; exec "${SHELL:-sh}" -i'`) runs the agent with the
  core's PATH, which on WSL does not find `claude`.

**D2. Folder and space.** With a layout that lists spaces: the person picks a
space; `cwd` is its `folder`, with `""` meaning home and `~/...` expanded with
home; a folder not starting with `/` after that maps to home with a note in the
sheet. An unnamed space is shown by the desk's rule: the folder's name, `~` for
home (model.rs:234-243). With no document, an unreadable one, or zero spaces:
the person picks from the distinct `cwd`s of the machine's live terminals
(foreground `cwd` first, else the record's), home first, each once; the
terminal is labelled with a fresh space id (`sp` + 32 hex, the desk's form), so
the desk adopts it as a new unnamed space named after its folder. Home comes
from the check (D5); when it is unknown, no `cwd` is sent (the core's own
folder) and the sheet says "home folder unknown". The desk's "follow the
focused pane" folder is desk-side state, so the space's own folder is used.

**D3. The layout document on the phone.** `herdr-push/src/layout.rs` and its
`Layout` type move to `herdr-proto` and gain a reader for spaces (`id`, `name`,
`folder`), shared by the phone and herdr-push. Read leniently, as now: an
unreadable document means "no spaces" (D2's folder list and a fresh id).

**D4. (deleted: the prompt-ready wait; see D1.)**

**D5. Which agents.** The table is the desk's built-in one (`defaults/
programs.json`, entries with `run`: all 15), carried in the app with name, icon
and word, in the desk's order (`programs.rs:65`: claude, codex, pi, opencode,
cursor-agent, copilot, amp, gemini, grok, then agy, crush, hermes, kimi,
kiro-cli, qodercli). A test keeps every word to one `[a-z0-9._-]+`. When a
machine connects, the phone checks it in the background with one
`machine.exec`, `argv ["sh", "-c", <outer>, "sh"]`, `timeout: 5`, where
`<outer>` is

```
printf 'herdr-home:%s\n' "$HOME"; case $(uname -s) in Darwin) f=-lic;; *) f=-ic;; esac; exec "${SHELL:-sh}" $f 'for c in claude codex ...; do command -v "$c" >/dev/null 2>&1 && echo "herdr-has:$c"; done; echo herdr-done'
```

with the table's words inline. It runs in the same shell with the same flags
as D1's start, so what it finds is what runs; `command -v` reports aliases in
zsh and bash, and an alias counts as installed. Home is printed by the outer
`sh` before the `exec`, so it survives a failing inner shell; it is the rest of
its line after the marker, spaces kept. The other markers are found as
substrings (split on `herdr-has:`, search for `herdr-done`), as the desk does
(programs.rs:112), so prompt escapes without a newline do not break them.
Success is exit 0 with `herdr-done`: the result is cached per machine per app
start, also with zero agents. Anything else (timeout, other exit, no
`herdr-done`) shows no shortcuts and is retried on the next sheet open. A
`$SHELL` of fish, nu or pwsh, or a core with no `sh`, fails the check by
itself (the loop is `sh` syntax), so it gets no shortcuts. The
sheet reserves the shortcut row below "Terminal" with a placeholder until the
check answers and keeps its height until the sheet closes, so nothing shifts
under the thumb.

**D6. herdr-push alerts for unplaced space terminals.** `Rules::placed()`
counts a terminal as placed when the layout places it **or** it carries a
non-empty `herdr.space` label. A desk-made terminal has the label too, so
nothing changes for those; a terminal the desk parked (label emptied) stays
quiet, as now. The rule is written into V1's alerts requirement in place: "...
SHALL suppress sub-agents and terminals neither placed in a pane nor labelled
with a non-empty `herdr.space`", plus the two scenarios (desk app closed;
parked terminals stay quiet).

**D7. After the start**, the phone opens the new terminal in the existing
terminal screen at the phone's size (`openTerminal(..., true)`), as a tap on
a row does. The start button is disabled while a start is in flight. Errors
keep the sheet open: `spawn_failed` shows "Could not start in <folder>" and
the core's detail; any other refusal shows the core's own message
(`unavailable`, `machine_unknown`: "<machine> is not reachable now"). Nothing
is retried elsewhere.

**D8. Compatibility and icons.** `USES` gains `terminal.run`, `machine.exec`,
`doc.get` and `doc.set` (the last two already called). A test lists every
`call("...")` in `crates/*/src` and `herdr-proto`, plus the methods sent outside
`call` by hand (`terminal.attach`, `events.subscribe`), and checks each is in
`USES`. `program_of` recognises D1's wrapper (`argv ["sh", "-c", <wrapper>,
...]`) and names the shell instead of `sh`, until the agent reports its own
app.

## Risks / Trade-offs

- [The agent draws its first frame at the core's 200x120 before the phone
  attaches] → The phone attaches at once (D7); a resize at start, before any
  transcript, is accepted.
- [The person's alias for `claude` adds flags] → Wanted: it is what typing
  `claude` at the desk does; the phone sends only the bare word.
- [Not in shell history] → Accepted (Ezra, 2026-11-04).
- [An agent killed by SIGINT ends the `-c` list, so the shell does not follow
  and the terminal finishes] → `keep` only replaces lost programs; Claude Code
  and Codex exit normally on ctrl+c; the desk's form has the same property.
- [A core configured with its own `shell` that differs from `$SHELL`] →
  Accepted: shortcuts use `$SHELL`, as the check does; "Terminal" uses the
  core's.
- [The desk app is closed, so the terminal is not a tab yet] → It is adopted
  when the desk app runs; D6 keeps alerts working meanwhile.
- [The connection drops between the start and the open] → The terminal exists
  and shows on the desk; the phone lists it on reconnect and the person opens
  it from the list.
- [A stale space list: the space was closed on the desk since the phone read
  it] → The desk recreates it as an unnamed space from the label; accepted.
- [The check is slow or prints a lot] → Background at connect, cached,
  markers, `timeout: 5`.
- [The agent table drifts from the desk's] → Data copied with its source
  noted; a later desk version adding agents is a small update.

## Migration Plan

None: new screens and calls only. herdr-push's D6 ships with the next
`herdr-push` release; an older herdr-push keeps today's behaviour (quiet until
the desk places it).

## Open Questions

- The "New" sheet's look: a render in `design/` before building (task 3.1).
