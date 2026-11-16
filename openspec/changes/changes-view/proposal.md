# Proposal: the Changes view

## Plain summary

**Decisions** (Ezra, 2026-11-16: 1-4 and 6 as picked; 5 and 7 changed as
written here):
1. **Where Changes opens from.** (a) A third option in the title bar's
   Terminal / Chat switch. (b) A small Changes button in the title bar, in
   both views, that opens a full screen over the current view; back returns.
   (c) Only from a blocked agent's card. *Pick (b)*: Changes is a look before
   going back, not a third way to work with the agent, and the bar has no
   room for a three-way switch beside the name and state.
2. **What counts as the agent's changes.** (a) What is not committed yet,
   against the last commit (as the Mac's Changes panel shows). (b) (a) plus
   the branch's commits that are not on main yet. (c) (a) plus the commits
   made since this agent started. *Pick (a)*, (b) as a 1.x issue: (a) answers
   "what did it just do", and (b) and (c) need guessing at the main branch.
   An agent that commits as it goes shows "No changes" after its turn; the
   empty screen says so.
3. **The edit a permission prompt asks about.** It is not on disk yet, so git
   cannot show it. (a) Changes shows what is on disk only. (b) Changes also
   shows the pending edit as "Proposed", read from the agent's log as the chat
   does. (c) (a), and the chat's tool sheet learns to show an edit as a diff
   (a 1.x issue). *Pick (c)*: Changes stays a plain git view that works for
   every agent, and the pending edit shows where the agent's calls already
   are.
4. **Long lines on a phone.** (a) Wrapped, with a mark where a line
   continues. (b) Scrolled sideways per diff, as the Mac does. *Pick (a)*:
   one-thumb reading, and no sideways drag fighting the swipe between
   images and views.
5. **New files git does not track yet.** Shown as all added, cut off like
   other long diffs (as the Mac does), and any cut-off diff can be loaded in
   full: an agent's new file is often the change you most want to read.
6. **Design first.** (a) One render (file list and a diff, both themes)
   signed off before building, as for the other screens. (b) Built from the
   design tokens and judged on the phone. *Pick (a)*: a small screen, cheap
   to render, and it settles 1 and 4 by sight.
7. **A folder that is not a git repository, or a desk without git.** The
   button shows and opens to "Not a git repository", so a newcomer sees why
   there is nothing to list.

**What changes for you:** an agent's title bar gets a Changes button. It
opens a list of the files the agent changed (changed, added, deleted, new),
each with its count of added and removed lines; tap one to read its diff,
coloured as on the desk. Nothing on the screen changes the repository: no
commit, no stage, no discard. It refreshes when the agent's state changes.
Very long diffs show their first 2,000 lines, with a control to load the
whole diff. Outside a git repository the screen says "Not a git repository".

## Why

Before letting an agent go on, you want to see what it did. In the terminal
that means asking the agent or scrolling its output. The Changes view is
part of v1.0.0 (`decisions.md`, 2026-11-13). It was specified in
October as milestone M5 inside `android-client-v1`; that spec predates the
chat view and is too narrow for its own first requirement: its fixed git
commands cannot show a new file's contents, go wrong when the agent's folder
is below the repository's top, still read a file named like `:(glob)*` as a
pattern, and have nothing to fetch "load more" with.

## What Changes

- A Changes button in the terminal screen's title bar, in both the terminal
  and the chat; outside a git repository (or without git on the desk) it
  opens to "Not a git repository".
- A Changes screen: the changed files with their status and line counts
  (first 1,000 files), and per file its diff, wrapped, coloured from the
  theme's diff colours; new files as all added; binary files listed without
  content; long diffs cut at 2,000 lines, with a control that loads the
  whole diff.
- Git runs through `machine.exec` as a fixed set of argument lists, never
  through a shell, read-only (no optional locks, no external diff or text
  conversion, no colour, literal paths), from the repository's top.
- Reads on opening and when the agent's state changes; the desk keeps its
  terminal size while Changes shows.
- The `changes-view` delta moves out of `android-client-v1` into this change,
  with V1's tasks M5.1-M5.3.

## Capabilities

### New Capabilities
- `changes-view`: the button and when it shows, which changes are listed,
  the diffs and their limits, the read-only git calls, refreshing, and the
  empty and error states.

### Modified Capabilities
- None in `openspec/specs/`. `android-client-v1` loses its `changes-view`
  delta and its M5 tasks.

## Impact

- Rust: a module that builds the git argument lists and parses status,
  line counts and diffs, with tests against real git in a scratch
  repository (hostile repository included).
- herdr-mobile: a `Changes` object (folder from `terminal.get`, calls
  through `machine.exec`, refresh on events).
- Android: the Changes screen, the button in the title bar, goldens in both
  themes; a render in `design/` first (decision 6).
- herdr-core: nothing new; `machine.exec` and `terminal.get` are already
  used. Works on any desk with git on its PATH, not only Linux.
- Size: about 15 tasks over 3 to 5 sessions (moderate confidence for the
  code, low for the total).
