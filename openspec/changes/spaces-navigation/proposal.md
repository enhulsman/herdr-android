# Proposal: the desk's order on the phone

## Plain summary

**Decisions that are yours** (my pick first; every text on screen and every
new picture waits for your approval, see "Texts" and "New on screen"):

1. **What a sideways swipe on the title bar walks.**
   *Pick: the desk's order, all the way.* The panes of this tab left to
   right and top to bottom, then the space's next tab, then the machine's
   next space, then the next machine. A plain shell in a pane is a stop too,
   as on the desk. It stops at the ends. Up and down stay the walk through
   the agents that need you.
   Other options: (b) the desk's order inside the tab only, stopping at the
   tab's ends, as Herdr's own app does; (c) keep today's walk, the Agents
   list by state.
   Why (a): one gesture then reaches every terminal in the order you know
   from the desk. This reverses part of your choice of 2026-11-03 ("left and
   right walk every agent in list order").
2. **Where Spaces lives.**
   *Pick: a second view of the home screen*, chosen with a switch in the top
   bar (Agents / Spaces); the app opens on the one used last.
   Other options: (b) the drawer from the left edge, as the old spec says;
   (c) a bottom bar with two tabs.
   Why (a): on phones with gesture navigation a swipe from the left edge is
   Android's Back, so a drawer needs a button anyway, and the home screen
   has no bottom bar to add one tab to.
3. **What the Spaces view lists.**
   *Pick: every terminal as a row*, under its space and its tab, in the
   desk's order, with a small map of the tab's splits on the tab's line; a
   tap on that line opens the tab as tiles. This is #46 (the list grouped by
   space), so #46 closes with this change.
   Other options: (b) only spaces with their tab maps, a tap opening the
   tiles, as Herdr's app; (c) rows only, no tiles.
   Why (a): names are what you look for on a phone, and the rows are the
   ones the Agents view already has.
4. **No pinch for the tab overview.**
   *Pick: a tap on the tab's map in the title bar opens it.* The old spec
   gave it to a pinch on the terminal, but the pinch already zooms there.
   Other option: pinch in once the terminal is at its smallest.
5. **No swipe on the terminal itself.**
   *Pick: the title bar only.* The terminal already uses a drag to scroll
   and pan, a tap to click and a long press to select, and the chat scrolls
   wide tables sideways.
   Other option, later if the title bar is too far to reach: a two-finger
   sideways swipe on the terminal.
6. **Sub-agent peek stays out.**
   *Pick: its own small change later.* It needs a second live terminal in a
   sheet and has nothing to do with order. It stays specified in
   `android-client-v1`, and #27 stays open for it alone.
   Other option: build it here, after everything else.

**What changes for you:**

- The home screen gets a switch: **Agents** (as today, by who needs you) and
  **Spaces** (your machines' spaces, tabs and terminals in the desk's
  order). Each tab has a small map of its splits; a terminal that needs you
  shows its card in its place.
- In a terminal, a sideways swipe on the title bar takes you to the
  neighbour on the desk, not to the next agent of the list by state. A
  stronger tick tells you when you enter another tab.
- When a tab has more than one pane, the title bar shows the tab's map with
  your pane marked. A tap on it shows the tab as tiles; a tap on a tile
  opens that pane.
- Swiping past terminals no longer resizes them on the desk: a terminal is
  taken over only once you stay on it for 0.4 seconds, or at once when you
  type, tap or send text there.
- When the desk reorders, renames, adds or closes something, the phone
  follows within two seconds. The terminal you have open never changes
  under you.
- A machine out of reach keeps its spaces as last seen, marked "as of".
- With TalkBack, the title bar has the actions "Next terminal" and
  "Previous terminal", and everything can be reached by taps.
- Nothing is ever changed on the desk: no renaming, moving or closing.

**Found wrong on the way** (details in
`research/spaces-navigation-2026-11.md`, section 1):

- Triage called #27 ready against the existing spec. It is not: the left
  edge is Android's Back, the pinch is already the zoom, and no signed-off
  render shows a drawer, a tile or a peek sheet.
- The existing spec does not contain your idea. It has no swipe in the
  desk's order and nothing that shows where the open terminal sits.
- Today's sideways swipe does not follow the desk at all. It follows the
  Agents list by state.

## Why

On the desk, terminals have a place: this space, that tab, left of that
pane. The phone shows them as one list by state, and its sideways swipe
walks that list. So the neighbour of the terminal you are in is unknown,
and a build pane or a shell beside an agent cannot be reached by swiping at
all. Herdr's own Android app shows the desk's structure (tabs as small
maps, panes as dots in the header, a swipe to the next pane), and kudde
should learn from it. kudde already reads the layout document, but keeps
only the spaces' names.

## What Changes

- `herdr-proto` reads the layout document in order: spaces, tabs, and each
  tab's panes with their place among the splits. It stays lenient and never
  writes.
- The hub keeps each machine's layout with its document version, follows
  `doc` events, drops late answers, keeps the last layout while a machine
  is not connected, and puts it in the snapshot.
- The home screen gains the Spaces view and a switch; the app remembers the
  view.
- A tab map (a small drawing of a tab's splits) on each tab's line and in
  the title bar.
- **BREAKING for habit, not for data:** the sideways swipe on the title bar
  walks the desk's order instead of the list by state. Plain shells in a
  pane become stops.
- A terminal reached by a step is attached only after 400 ms on screen, or
  at once on input; a compose text is cleared only when it was handed to
  an attached terminal.
- The title bar gets four screen-reader actions; a step is announced.
- A tab overview with tiles, opened from a tab's line and from the title
  bar's map.
- The `spaces` requirements for the drawer, lenient reading and the tab
  overview leave `android-client-v1` for this change, rewritten; the peek
  requirement stays there. The sideways sentence of `agent-list`, "Jump to
  the next agent that needs you", is amended in place to point here.

## Capabilities

### New Capabilities
- `spaces`: the desk's order on the phone: reading the layout in order,
  following it, the Spaces view, tab maps, the sideways walk and its rules,
  what happens when the desk changes or a machine is out of reach, the tab
  overview, and the ways without a swipe.

### Modified Capabilities
- None in `openspec/specs/`. Two requirements of the open change
  `android-client-v1` are amended in place (task 1.1), as `session-names`
  and `changes-view` did before.

## Texts (each needs the owner's approval)

| Where | Text |
|---|---|
| Home switch | "Agents" · "Spaces" |
| Group of terminals in no pane | "Not in a space" |
| A space with unreadable tabs | "1 tab could not be read" · "%d tabs could not be read" |
| A machine whose document is not a layout | "Layout could not be read" |
| Spaces view, nothing to list: title | "No spaces yet" |
| Spaces view, nothing to list: body | "Spaces you make in the herdr app on your machines show up here, in the desk's order." |
| Placeholder tile | "Desk only" |
| Compose text sent while the terminal cannot be opened | "Not sent: the terminal is not connected yet" |
| Spoken: a space header | "website, 1 needs you, 2 working" (name, then each state that has agents, in the order needs you, done, working, idle) |
| Spoken: a tab map | "Tab main, 3 terminals, 1 needs you" (the last part only when someone needs you) |
| Spoken: the title bar's map | "Tab overview, terminal 2 of 3" |
| Spoken: the switch | "Agents, selected" · "Spaces, selected" |
| Screen-reader actions | "Next terminal" · "Previous terminal" · "Next agent that needs you" · "Previous agent that needs you" |
| Spoken after a step | "<name>, <place>" as the title bar shows them (for example "Pricing page copy, website / 2 · workstation") |
| Spoken at the end | "No more terminals" |

## New on screen (each needs the owner's sign-off against a render)

All five use only colours, type and marks that exist in
`design-tokens.json`; none has a render yet (task 4.1 makes them).

1. **The home switch.** The top bar's title becomes two words in the screen
   title's type, "Agents" and "Spaces"; the chosen one in the text colour,
   the other dim. While Spaces shows and an agent needs you, "Agents"
   carries the count in the needs-you colour, as a section header does.
2. **The tab map.** A drawing about 28 by 20 dp of the tab's panes as
   cells with a 1 dp border and 2 dp corners. A cell is filled in the
   needs-you colour when its agent needs you, in the done colour when it
   finished unseen, outlined in the working colour while it works, and
   outlined in the border colour otherwise. A placeholder cell is dashed. In
   the title bar the open terminal's cell is filled in the text colour.
3. **The Spaces list.** Machine name as a section header (when more than
   one machine); a space header (name 16 sp, the state mark at the right in
   the row's 18 dp slot); a tab line (map, then the tab's name in the
   secondary type); under it the existing needs-you card, agent row and
   other-terminal row.
4. **The title bar with the map**, as a 48 dp button before the Changes
   button, in the terminal and in the chat. The chat's bar also holds the
   gauge and the view switch; the render must show it at 412 dp and at 130%
   font size, and you choose what gives way if it does not fit.
5. **The tab overview.** A sub-screen with the back bar the Changes screen
   has, titled with the space's name; the space's tabs as a row of tab maps
   with names; the chosen tab's tiles in popover colour with the program
   mark, name and state mark.

## Impact

- Rust: `crates/herdr-proto/src/layout.rs` (ordered reading, geometry),
  `crates/kudde-mobile/src/hub/` (layout per machine, versions, snapshot,
  the uniffi types).
- Android: a new `ui/spaces/` package; `ui/agents/AgentsHome.kt` (the
  walk), `AgentsRoute.kt` and `AgentsScreen.kt` (the switch),
  `ui/terminal/TerminalScreen.kt` and `TerminalRoute.kt` (map, actions,
  dwell), `ui/chat/ChatScreen.kt` (map in the shared bar).
- Design: four renders, five component entries in `design-tokens.json`,
  checkpoints, goldens.
- Specs: `android-client-v1` amended in place (two requirements).
- Shares files with #28 (wide layout: `AgentsRoute.kt`, `TerminalRoute.kt`)
  and `desktop-chat` (the hub's chat code moves; this change does not touch
  it). Build them one after the other.
- No herdr-core feature is needed; the tested range stays as it is.
- Not in this change: sub-agent peek, live pictures in tiles, renaming or
  closing anything, a swipe on the terminal itself, the wide layout, and
  showing which pane the desk has in focus (the document does not say).
  Until the peek change, a sub-agent's terminal that sits in a pane shows
  as an empty cell in Spaces; the Agents view lists what it lists today.
- Estimate: 10 to 13 builder runs and one design session with the owner.
  Confidence: moderate for the Rust and the walk, low for the screens until
  the renders are signed off.
