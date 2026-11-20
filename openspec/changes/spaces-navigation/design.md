# Design: spaces-navigation

Research and sources: `research/spaces-navigation-2026-11.md`. Line numbers
are the repository's on 2026-11-20.

## D1. One flat, ordered layout from Rust

`crates/herdr-proto/src/layout.rs` gains two pure functions beside
`spaces()` and `placed()`, which keep their signatures and results
(kudde-push and the "New terminal" sheet depend on them):

```rust
pub struct Desk { pub spaces: Vec<DeskSpace> }
pub struct DeskSpace { pub id: String, pub name: String, pub unreadable_tabs: u32, pub tabs: Vec<DeskTab> }
pub struct DeskTab { pub id: String, pub name: String, pub cells: Vec<DeskCell> }   // cells in desk order
pub struct DeskCell { pub pane: DeskPane, pub x: f32, pub y: f32, pub w: f32, pub h: f32 }  // 0.0..=1.0 of the tab
pub enum DeskPane { Terminal { address: String }, Placeholder { label: String }, Vacant }
pub enum DeskNote { NoDocument, Unreadable }

/// The document alone: order, names, geometry. `Vacant` only for a repeated terminal.
pub fn desk(doc: &Value) -> Result<Desk, DeskNote>;
/// The desk against the machine's own terminal records (`EventLines::terminals()`):
/// returns the desk with ineligible terminal cells turned `Vacant`, and the
/// loose terminals.
pub fn seat(desk: &Desk, terminals: &[Value]) -> (Desk, Vec<String>);
```

`desk(doc)`:

- **Whole document.** `null` gives `NoDocument`. Not an object, or `spaces`
  present and not an array, gives `Unreadable`. No `spaces` key gives a desk
  without spaces. (The same three cases as `placed()`, `layout.rs:56-70`.)
- **Spaces.** In the array's order. An entry without a non-empty string
  `id` is skipped, as in `spaces()` (`:35`). The name is the `name`, else
  `start::space_title` of its folder. `tabs` missing or `null`: no tabs.
  `tabs` not an array: no tabs and `unreadable_tabs = 1`.
- **Tabs.** In the array's order. A tab is unreadable, and only counted,
  when its `root` is not an object, when a node sits deeper than 64 levels
  (the existing `MAX_DEPTH`), or when it has more than 64 cells. The name
  is the `name` when non-empty after trimming, else the tab's position
  among the space's listed tabs starting at 1, unreadable ones counted
  (this matches the desk's `where` label, "website / 2").
- **Unique ids.** A tab id that is empty or already used in this document
  becomes `<id>#<position>`; the same for a repeated space id. Kotlin keys
  list items by these ids, and a repeated key would crash the list.
- **Nodes**, depth first, children in listed order:
  - an object with `content.terminal` as a non-empty string: a `Terminal`
    cell, its address cut to the part after the last `/`; a terminal
    already seen in this document becomes `Vacant`;
  - an object with `content.mod`: a `Placeholder` labelled with the mod's
    name (the label is carried for later; this change shows "Desk only");
  - an object with a `children` array: a container. `type` `row`, or any
    unknown type, splits its width; `column` and `stack` split the height;
  - any other object, and an empty container: a `Placeholder` with an
    empty label; a child that is not an object is skipped.
  - `hidden` is ignored: the phone shows one terminal at a time, and
    hiding is a look of the desk's window. Low stakes: one extra cell.
- **Geometry.** The root has the rectangle (0, 0, 1, 1). A container gives
  each child a share of its width or height in proportion to the child's
  weight. The weight is `size.weight` when it is a JSON number that is
  finite and greater than zero, clamped to 0.01..=1000.0; otherwise 1.0
  (no `size`, `{px}`, zero, negative, not a number). Shares are computed in
  `f64` (a weight is at most 1000, so no sum can overflow), then
  each rectangle is clamped so that `0 <= x`, `0 <= y`, `x + w <= 1`,
  `y + h <= 1`, `w >= 0`, `h >= 0`, and stored as `f32`. A side may come
  out as 0 after deep nesting of extreme weights; that is allowed (the map
  draws nothing for it and the tile-fit rule falls back to rows), but no
  field is ever NaN, infinite or negative.

`seat(desk, terminals)`:

- A terminal is **eligible** when its record has `state == "live"` and a
  `parent` that is null or absent. Status and `reported` do not matter.
  This is deliberately not the Agents view's rule: `is_other`
  (`crates/herdr-proto/src/attention.rs:138-142`) lists a live terminal
  with a parent while it never reported, and the exported row carries no
  parent (`crates/kudde-mobile/src/hub/mod.rs:207-215`), so Kotlin could
  not tell. The Agents view is not changed.
- A `Terminal` cell whose address has no eligible record becomes `Vacant`.
- The loose list is every eligible terminal's address that is in no
  `Terminal` cell, in the order of `terminals`.
- Records are matched on their `address` as given; `EventLines` already
  drops other machines' terminals (`crates/herdr-proto/src/events.rs:33`).

Known limit: a sub-agent's terminal that the person kept as a pane on the
desk shows as a vacant cell on the phone. Showing sub-agents belongs to the
peek change.

Alternative: send the tree and walk it in Kotlin. Rejected: two
implementations of order, and a recursive record over uniffi.

## D2. The hub keeps a layout per machine, outside the connection

Today `read_layout` stores the spaces in the connection's own state
(`crates/kudde-mobile/src/hub/start.rs:178-191`), which is cleared when the
connection ends (`hub/mod.rs:324`), and it stores whichever answer arrives
last.

- `State` (`hub/mod.rs:333`) gains `desks: HashMap<String, HeldDesk>`,
  keyed by machine, beside `checks`:
  `HeldDesk { version: u64, read: Result<Desk, DeskNote>, seated: DeskView }`.
  It is not saved to disk. `remove_machine` (`:812`), a rename, and a change
  of host, port or user drop the entry and its version: another server's
  version numbers start over. The document is parsed outside the hub's lock;
  the epoch and version are checked again before the result is published.
- `read_layout` keeps setting `places.spaces` exactly as today, and also
  takes `got["version"]` (a `u64`; `DocGetResult` in
  `research/hc.openrpc-0.1.15.json`) and `desk(&got["value"])`. It replaces
  the held entry only when the new version is not lower than the held one;
  the epoch check stays. A `doc.get` error other than a lost connection
  keeps the held entry and notes the failure, as today.
- `seat` runs again whenever the layout is replaced and whenever the
  machine's terminals are observed (`observe`, `hub/mod.rs:1009`), with
  `lines.terminals()`. While the machine is not connected nothing is
  re-seated: the last view stays.
- The snapshot: `MachineView` (`hub/mod.rs:119`) gains
  `#[uniffi(default = None)] pub desk: Option<DeskView>`, `None` until a
  layout answer arrived in this run.

```rust
#[derive(uniffi::Record)] pub struct DeskView { pub spaces: Vec<DeskSpaceView>, pub loose: Vec<String>, pub note: Option<DeskNoteView> }
#[derive(uniffi::Record)] pub struct DeskSpaceView { pub id: String, pub name: String, pub unreadable_tabs: u32, pub tabs: Vec<DeskTabView> }
#[derive(uniffi::Record)] pub struct DeskTabView { pub id: String, pub name: String, pub cells: Vec<DeskCellView> }
#[derive(uniffi::Record)] pub struct DeskCellView { pub pane: DeskPaneView, pub x: f32, pub y: f32, pub w: f32, pub h: f32 }
#[derive(uniffi::Enum)]   pub enum DeskPaneView { Terminal { address: String }, Placeholder { label: String }, Vacant }
#[derive(uniffi::Enum)]   pub enum DeskNoteView { NoDocument, Unreadable }
```

- With a note, `spaces` is empty and `loose` holds every eligible
  terminal.
- Names, states and requests are not copied in. Kotlin joins `Terminal`
  cells and loose addresses to the snapshot's agent and other-terminal rows
  by machine and address, so the attention model stays the single source of
  state. Eligibility is decided in Rust only; Kotlin never treats a
  terminal as a stop because a row for it exists.
- A `Terminal` cell or loose address with no row in either list (a short
  gap between two snapshots) has no row and is not a stop.
- Tests use the hub's fake core (`crates/kudde-mobile/src/hub/tests.rs`,
  `Core`, `rig`; `tests/starting.rs:293-325` already answers `doc.get` for
  `herdr-app` and sends a `doc` event).

## D3. The walk is computed when the finger lifts

The list by state needs a frozen order (`AgentsHome.walk`,
`ui/agents/AgentsHome.kt:57`), because opening an agent re-sorts that list.
Desk order does not move when a terminal is opened, so the sideways walk
reads the current snapshot at each step:

```
stops(snapshot): for each machine in snapshot order, if connected and it has a desk:
    for space, tab, cell in desk order: a Terminal cell's address, if a row exists
    then the machine's loose addresses, if a row exists
next(stops, current) / previous(stops, current); null at an end
```

- Which terminals may be stops is decided in Rust (`seat`, D1); "a row
  exists" only covers the gap between two snapshots.
- A current terminal that is in no stop list (its machine just went out of
  reach, or it is a sub-agent's terminal opened from "Other terminals") has
  no next or previous: the end haptic.
- `AgentsHome.tab` (`:153`) changes to this walk; `step` (`:129`), `walk`,
  `behind` and `ahead` stay for the up and down swipe. A sideways step
  still clears `behind` and `ahead`, as today.
- A step enters another tab when the tab id, the space id or the machine of
  the two stops differ, or one of them is loose and the other is not. That
  decides the stronger haptic (`HapticFeedbackConstants.CONTEXT_CLICK`; the
  light one stays `CLOCK_TICK`, the end stays `REJECT`,
  `ui/terminal/TerminalScreen.kt:282-284`).

Existing tests in `AgentsHomeTest` that assert the sideways walk follows
the list by state encode the old rule. They are rewritten to the new rule,
with the amended spec sentence as the reason; the up and down tests are
not touched.

## D4. No pager, no swipe on the terminal, no gesture exclusion

- The step stays an instant switch with a haptic, as today. A pager would
  need the neighbours' screens drawn, would take sideways movement from the
  terminal's pan and the chat's tables, and the design brief rules out
  orchestrated motion.
- The gesture stays where it is, on the title bar
  (`TerminalScreen.kt:265-292`). The drag handler already acts only when a
  drag ends, so a drag the system takes for Back (it is cancelled) does
  nothing. The change adds two guards there: refuse while the compose sheet
  holds text, and nothing else.
- The app sets no gesture exclusion. Android limits it to 200 dp an edge
  and it would break Back on the one screen where Back matters most.
- Selecting text is state of one terminal's screen
  (`remember(key) { TerminalScreenState }`,
  `ui/terminal/TerminalRoute.kt`), so a step ends it by itself.

## D5. Arriving by a step waits 400 ms before attaching

Opening a terminal takes over at the phone's size at once
(`ui/terminal/TerminalRoute.kt:68-74`), which reflows the program on the
desk, and leaving gives the size back. Walking past five panes would reflow
five desk terminals. So a terminal reached by a step is attached only after
400 ms (`STEP_DWELL_MS`); the cached last screen shows meanwhile, as for
any open.

**Where the wait lives.** `AgentsHome` records how the open terminal was
reached (a step, or anything else). `TerminalOpener`
(`TerminalRoute.kt`, tested in `TerminalOpenerTest`) already takes its
times as arguments (`onPhoneGrid(size, nowMs)`, `tick(nowMs)`), so the wait
goes there and is tested with exact times: constructed with the arrival
time and a dwell of 400 or 0, it opens on the first `onPhoneGrid` or `tick`
whose time is at least arrival + dwell, and it gets `openNow(nowMs)`. The
route calls `tick` when the wait is due. Leaving disposes the route; a
disposed opener never opens.

**Input during the wait** (the review's finding, verified). Without a view,
`TerminalSession.send` drops typing, keys, taps and scrolls
(`ui/terminal/TerminalSession.kt:87-100`), and `paste` answers "sent"
(`:96`), after which `sendCompose` clears the text
(`ui/terminal/TerminalScreen.kt:151-155`). The chip that shows while
attaching opens the keyboard (`:527`, `:539-542`). Today that gap lasts one
layout pass; the wait would make it 400 ms. So:

- `TerminalSession` gets a hook the route sets to `opener.openNow`. `send`
  and `paste` call it first when there is no view. `openTerminal` returns
  the view in the same call, so the input that ended the wait is delivered
  right after, once, in order.
- `paste` answers one of three things: sent, refused by the core (a line
  break, as today), or not attached. `sendCompose` clears the text only on
  "sent"; "not attached" keeps the sheet and text and shows the new
  message, not the one about line breaks.
- A tap on the terminal during the wait is `lead()` (the screen is not in
  typing state yet, `TerminalScreen.kt:430-437`), so it opens the terminal
  and sends no click at a cell of a screen that is not live.
- A step is refused while the compose sheet holds text (D4), so a draft
  cannot be lost by stepping during the wait either.

Not changed: marking a finished agent seen stays on showing, as for every
step today. A terminal with no cached screen shows the terminal background
for up to 400 ms plus the attach; judge at the phone check, the constant is
the only knob. Under the chat the terminal is watched at desk size anyway;
the wait still saves an attach per terminal passed. If the app is stopped
during the wait, the existing reopen path opens the terminal on return.

Alternative: attach watch-only at once and take the phone's size after the
wait. Rejected: the screen would show the desk's small grid and then jump
to the phone's.

## D6. Spaces as a view of the home screen, built from existing rows

- `AgentsRoute` chooses between `AgentsScreen` and a new `SpacesScreen` by a
  phone-local setting stored like the chat view's choice
  (`ui/chat/ChatViewSetting.kt`). Both share the top bar, the machine and
  storage notices, the "New terminal" entry and the empty states for no
  machines and a failed start.
- `SpacesModel.kt` turns a snapshot into the list: machine header, space
  header, tab line, rows. Rows are the existing `AgentRowUi` and
  `OtherRowUi`, drawn by the existing composables, which become visible to
  the new package.
- List items are keyed by machine and address (rows), machine and tab id
  (tab lines), machine and space id (headers), so Compose keeps the first
  visible item in place when the desk changes and `animateItem` gives the
  200 ms move, off with reduced motion as in `AgentsScreen.kt:196-198`.
- The list state is hoisted above the open terminal so Back returns to the
  same place.
- A space's state is the most urgent among its rows; the header's spoken
  text counts each state.

## D7. The tab map is one composable, drawn from rectangles

`TabMap(cells, marked, states)` draws each cell's rectangle with a 1 dp gap.
It is used at about 28 by 20 dp on a tab line and in the title bar, and
larger above the row fallback of the overview. State is never shown by the
map alone: on a tab line the rows below carry the marks and words, and the
map has a spoken name. In the title bar the map is hidden for a tab of one
pane, so the bar is unchanged for most terminals.

## D8. The tab overview is a sub-screen with name tiles

Tiles show the program mark, the name and the state mark, not a live
picture: live tiles would attach to every pane of a tab, and a tab is
looked at for a second or two. Whether tiles fit is a pure function of the
cells and the area (every tile at least 96 by 64 dp); otherwise rows. Back
is handled as the Changes screen handles it. Live pictures, or the cached
last screen in a tile, can be a later issue.

## D9. Accessibility

- The title bar's merged node gets `customActions` for the four steps; the
  result is spoken with `announceForAccessibility` (name and place, or "No
  more terminals").
- TalkBack cannot be driven over adb, and a UI dump pauses it. So merged
  semantics are tested in Robolectric (as `ReadOnceTest` does), and the
  owner listens once on a phone.
- Tap targets are 48 dp; the map in the title bar is a button with the
  spoken name "Tab overview, terminal 2 of 3".

## Risks

- **Pane order is our reading of the tree.** herdr documents moving between
  panes by direction only. Depth first matches what the official phone app
  showed for one tab. If the desk ever defines another order, D1 is the one
  place to change. Confidence: moderate.
- **The document's shape is not a public contract.** It is read leniently,
  per tab, and never written, so a change on herdr's side degrades to
  placeholders, unreadable tabs or "Layout could not be read". The protocol
  suite in CI runs against the newest herdr-core; it does not cover the
  desk app's document. A fixture copied from a real document, with invented
  names, guards the reader (task 2.1).
- **The title bar is tight under the chat** (gauge, map, Changes, view
  switch). Settled at the render (proposal, "New on screen" 4).
- **The reach of the title bar** on a tall phone. If it bothers in use, the
  two-finger swipe of decision 5 is the follow-up.
- **400 ms** may feel slow or too short. One constant; phone check.
