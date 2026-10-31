# spaces Specification (delta)

All requirements here belong to version 1.1 (M4).

## ADDED Requirements

### Requirement: Spaces drawer from the shared layout
The app SHALL read each machine's `herdr-app` document and SHALL show a drawer
(left-edge swipe) with machine, space and tab, each with a status dot rolled up
from the agents inside it. Tapping a tab SHALL open the first terminal pane in tree
order, preferring a pane that needs Ezra, then one finished-unseen.

#### Scenario: Opening a tab
- **WHEN** Ezra taps a tab whose second pane is blocked
- **THEN** the second pane's terminal opens

#### Scenario: Nothing waiting
- **WHEN** Ezra taps a tab where no pane needs him
- **THEN** the first terminal pane in tree order opens

### Requirement: The layout is read leniently and never written
The app SHALL NOT write the `herdr-app` document. It SHALL parse it per tab and per
node, show unknown node types and mod panes as placeholder tiles, skip a malformed
tab with a note, follow changes through `doc` events filtered by name, and refetch
after `missed` or a reconnect. Terminal addresses SHALL be normalised so `t7` and
`<this core>/t7` name the same terminal.

#### Scenario: Renamed on the Mac
- **WHEN** a tab is renamed in the Mac app
- **THEN** the drawer shows the new name within two seconds of the event, and the app made no `doc.set` call

#### Scenario: Mod pane and unknown node
- **WHEN** a tab contains a mod pane and a node of an unknown type
- **THEN** the tab shows both as placeholders and its terminal panes work

#### Scenario: Malformed tab
- **WHEN** one tab in the document is malformed
- **THEN** the other tabs show normally and the drawer notes one unreadable tab

### Requirement: Tab overview shows splits as tiles
A pinch-in on a terminal SHALL show its tab's split layout as tiles with each pane's
name and status; tapping a tile SHALL open that pane. Splits SHALL never be drawn
side by side at phone width.

#### Scenario: Three panes
- **WHEN** a tab has three panes and Ezra pinches in
- **THEN** three tiles appear in the tab's arrangement, and tapping one opens it

### Requirement: Peek at sub-agents without a pane
A terminal with child terminals that have no pane SHALL list them; tapping one
SHALL open it in a bottom sheet (half height, draggable to full) with the same
watch-only view.

#### Scenario: Sub-agent peek
- **WHEN** an agent has spawned a sub-agent without a pane
- **THEN** Ezra can open it in a sheet and close the sheet back to the parent
