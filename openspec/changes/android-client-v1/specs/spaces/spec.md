# spaces Specification (delta)

## ADDED Requirements

### Requirement: Spaces drawer from the shared layout
The app SHALL read each machine's `herdr-app` document and SHALL show a drawer
(left-edge swipe) with machine, space and tab, each with a status dot rolled up
from the agents inside it. Tapping a tab SHALL open its focused pane's terminal.

#### Scenario: Finding the build tab
- **WHEN** Ezra opens the drawer and taps the "api" space's "build" tab
- **THEN** that tab's terminal opens

### Requirement: The layout is read-only on the phone
The app SHALL NOT write the `herdr-app` document. It SHALL follow changes
through `doc` events and refetch after `missed` or a reconnect.

#### Scenario: Renamed on the Mac
- **WHEN** a tab is renamed on the Mac
- **THEN** the drawer shows the new name within two seconds, and the app made no `doc.set` call

#### Scenario: Unknown fields
- **WHEN** the document contains fields this app version does not know
- **THEN** the drawer still shows the spaces and tabs it does know

### Requirement: Tab overview shows splits as tiles
A pinch-in on a terminal SHALL show its tab's split layout as tiles, each with
the pane's name and status; tapping a tile SHALL open that pane. Splits SHALL
never be drawn side by side on a phone-width screen.

#### Scenario: Three panes
- **WHEN** a tab has three panes and Ezra pinches in
- **THEN** three tiles are shown in the tab's arrangement, and tapping one opens it full screen

### Requirement: Peek at sub-agents without a pane
A terminal with child terminals that have no pane SHALL show them in a list;
tapping one SHALL open it in a bottom sheet (half height, draggable to full)
using the same observe-only view.

#### Scenario: Sub-agent peek
- **WHEN** an agent has spawned a sub-agent without a pane
- **THEN** Ezra can open it in a sheet and close the sheet back to the parent
