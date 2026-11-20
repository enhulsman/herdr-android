# Spaces navigation: research, November 2026

For the change `openspec/changes/spaces-navigation/`. Read on 2026-11-20.
"Read" means the page or file said it; "inferred" is marked. Line numbers
are this repository's at that date.

## 1. What kudde does today

**Moving between agents.** Only from an open terminal's title bar, which the
chat shares:

- `android/app/src/main/kotlin/dev/kudde/android/ui/terminal/TerminalScreen.kt:265-292`:
  one drag handler on the title bar. The longer direction decides (`:277-278`):
  sideways calls `onTab`, up and down call `onSwipe`. The distance is 40 dp
  (`:806`). A step gives a light haptic, the end a refusing one (`:282-284`).
- Sideways, `AgentsHome.tab` (`ui/agents/AgentsHome.kt:153`), walks every
  agent in the order of the Agents list as it was when the terminal opened
  (`walk`, `:57`, taken in `open`, `:115`). That list is sorted by state and
  then by newest change (`crates/herdr-proto/src/attention.rs:329`). So the
  sideways walk is the list by state, not the desk's layout. Terminals
  without agent status are never stops.
- Up and down, `AgentsHome.step` (`:129`) with `attentionStep`
  (`ui/terminal/AttentionQueue.kt:30`), walks the agents that need the
  person or finished unseen.
- The queue strip opens the next agent that needs the person
  (`AttentionQueue.kt:55`).

**Touches on the terminal itself** (`TerminalScreen.kt:424-457`): a tap
clicks or takes over, a long press starts selecting, a pinch zooms and a
drag pans while watching at desk size, and a vertical drag scrolls. A
sideways drag at the phone's size does nothing. In the chat, wide tables
scroll sideways (`ui/chat/ChatScreen.kt:601`).

**Spaces.** The phone knows little of them:

- `crates/herdr-proto/src/layout.rs:35` reads each space's id, name and
  folder in the document's order, for the "New terminal" sheet. `placed`
  (`:56`) returns the placed terminals as a set (`:17`), so tab and pane
  order are lost, and nothing reads tabs, splits or names of tabs.
- The hub reads the document once per connection and after each `doc`
  event (`crates/kudde-mobile/src/hub/mod.rs:1244`, `:1259`), keeps only the
  spaces (`hub/start.rs:178-191`), does not compare document versions, and
  drops them when the connection ends (`hub/mod.rs:324`). The snapshot the
  app draws from has machines, agents and other terminals, no layout
  (`hub/mod.rs:232-238`).
- A row's second line is the desk's own `where` label, "website / 2"
  (`attention.rs:482`, `openspec/specs/agent-names/spec.md:40`).
- Sub-agents (terminals with a `parent`) that reported status are left out
  of every list (`attention.rs:126`, `:138-142`). One that never reported
  and has no status is listed under "Other terminals" (`:141`), and the row
  the app gets carries no parent (`crates/kudde-mobile/src/hub/mod.rs:207-215`),
  so the app cannot tell it from any other shell.
- No screen, drawer, tab map or tile exists in `android/`. The home screen
  has one list and a top bar with a title and two buttons
  (`ui/agents/AgentsScreen.kt:201-255`).

**What the unbuilt spec promises** (`openspec/changes/android-client-v1/specs/spaces/spec.md`,
milestone M4, unchecked in that change's `tasks.md:51-55`): a drawer opened
by a swipe from the left edge with machine, space and tab and a rolled-up
status dot (`:7`); lenient reading, never writing, following `doc` events
(`:21`); a tab overview with tiles, opened by pinching in on a terminal
(`:40`); a peek sheet for sub-agents (`:49`). It promises nothing about
swiping between panes or tabs in the desk's order, and nothing that shows
where the open terminal sits.

Three things in it do not hold up:

1. **The left-edge swipe is the system's Back** on phones with gesture
   navigation (section 5). A drawer there needs a button anyway.
2. **The pinch is taken.** `terminal-view` gives the pinch to zooming
   (`openspec/changes/android-client-v1/specs/terminal-view/spec.md:40`), and
   the code does that (`TerminalScreen.kt:443-447`).
3. **No signed-off render** shows a drawer, a tile or a peek sheet
   (`design-brief.md`, the table of renders 1 to 12), and the appearance spec
   requires sign-off before a screen ships.

## 2. The official Herdr app for Android

Sources: its product page (public, quoted below), its Play listing, and a
walk through version 1.0.7 in its demo mode on a test phone on 2026-11-20
(notes kept outside this repository).

- **Two home tabs** in a bottom bar: an inbox by state, and Spaces.
- **Spaces** lists machines as headings and each machine's spaces under
  them. A space shows its name, a rolled-up state at the right, and one
  small map per tab: the tab's splits drawn as cells, with a dot on a cell
  whose agent wants attention. The product page calls it "each tab drawn as
  a small map of its panes".
- **A space's screen** has its tabs as a row of chips and the chosen tab's
  panes as tiles in the arrangement of the splits, each with a live picture
  of its terminal.
- **In a terminal**, the header shows the title, "space · machine", a row of
  dots (one per pane of the tab) and, at the right, the tab's map with the
  open pane filled in. The product page says: "In a terminal, swipe the
  header to go to the next pane of its tab", "or tap the tab's map to see
  all of its panes, live." In a tab of one pane the header shows neither
  dots nor map.
- The order of the dots matched the order of the panes in the splits, read
  left to right and top to bottom (one tab of three panes seen: moderate
  confidence).
- In landscape the inbox and spaces sit in a sidebar beside the content.

Not captured, and worth a second look on a test phone. Each is one exact
question:

1. On the header of the last pane of a tab, does a swipe do nothing, wrap to
   the first pane, or go to the next tab?
2. Does a sideways swipe on the terminal itself (one finger, then two) do
   anything?
3. On a space's screen, does a sideways swipe on the tiles change the tab?
4. The product page says a tap on the tab's map opens the tiles. Do the
   dots and the title do the same?
5. Does the picture slide when the header is swiped, or change at once?
6. With the app open on Spaces, reorder two tabs and then two spaces on the
   desk: does the phone follow without a reload, and how fast?
7. With a terminal open, move its pane to another tab on the desk: what
   does the header show, and where does the next swipe go?
8. With a screen reader on: is there any way to reach the next pane without
   the swipe?

Links: <https://play.google.com/store/apps/details?id=dev.herdr.app>,
<https://herdr.dev/mobile>.

## 3. How herdr orders spaces, tabs and panes

- **Each machine keeps its own layout** in its core, as the document
  `herdr-app` (`research/source-findings-2026-10-31.md`, section 3; the
  herdr app's README: "Each machine keeps its spaces, tabs and splits in its
  core"). Its shape is `{spaces: [{id, name, folder, tabs: [{id, name,
  root}]}]}`. A tab's root is a tree: a pane is a block holding a terminal
  or a mod; a split is a row or a column with children; a stack holds
  children with one active. Children may carry a size as a weight or as
  pixels.
- **Order is the order of the lists.** Spaces: the sidebar's order
  (`space.move` moves a space "to a position (1 = first)"; the number keys
  "follow the order of spaces in the sidebar"). Tabs: the tab strip's order
  (`tab.move`, "to a position in its space (1 = first)"). Panes: the desk
  moves between panes by direction (`pane.go:left` and so on); a linear
  order of panes is not documented. Reading the tree depth first, children
  in list order, gives left to right and top to bottom, and matches what the
  official phone app showed. That is our reading, not a documented contract
  (moderate confidence).
- **What the server tells a client** (`research/hc.openrpc-0.1.15.json`):
  `doc.get` returns the document's value and a version; `doc.set` writes if
  the version still matches; `events.subscribe` sends a `doc` event without
  the value when a document changes, and `missed` plus a fresh snapshot to a
  subscriber that fell behind. The core does not interpret the document. So
  the phone learns the order only by reading the whole document again after
  each event.
- **Not in the document** (kept on each desk, in its own view file): the
  active space, each space's active tab, the focused pane, zoom, and what
  was seen. The phone cannot know which pane the desk is looking at.
- **Terminals outside the layout** exist: started without the desk app,
  parked, or sub-agents. A terminal also carries labels the desk sets
  (`herdr.space`, `herdr.tab`, `where`).
- **Desk commands and keys**: next and previous tab, space and agent each
  have a key (`tab.next`, `space.next`, `agent.next`); `agent.next` goes "in
  the order the agents list shows them".

Links: <https://github.com/herdrdev/herdr-app/blob/HEAD/docs/commands.md>,
<https://github.com/herdrdev/herdr-app/blob/HEAD/defaults/keys.json>,
<https://github.com/herdrdev/herdr-app/blob/HEAD/CHANGELOG.md>.

## 4. Other clients and terminal apps

| App | What it does | Link |
|---|---|---|
| bordr (web) | Shows "a tab's real split, dividers and all". A sideways swipe across a conversation opens the next or previous agent in the list; "Swiping from a screen edge still goes back" | <https://github.com/AnnasCookies/bordr> |
| Whip (Android, iOS) | A rail of hosts and a rail of spaces; switching and swiping between tabs keeps terminals warm | <https://github.com/kosumic/whip> |
| Shepherdr (web) | "Spaces are the rows and their tabs nest underneath" | <https://github.com/luiscleto/shepherdr> |
| Pairfob (web) | "A tab's real pane layout" on a board | <https://github.com/arronKler/pairfob> |
| Remux | "Swipe to the next window or herdr tab"; one pane at a time with a pane map | <https://github.com/h3nock/remux> |
| Moshi (iOS) | On the terminal itself: one finger sideways is the next tab, two fingers sideways the next pane, two fingers up or down the next workspace, pinch zooms; all can be remapped | <https://getmoshi.app/docs/gestures> |
| ConnectBot (Android) | A one-finger sideways swipe on the terminal changes session, "Off by default for accessibility". It counts only when the sideways movement is over 1.5 times the vertical and over 18% of the width, never while text is selected, and does not wrap | <https://github.com/connectbot/connectbot/blob/HEAD/app/src/main/java/org/connectbot/ui/screens/console/ConsoleScreen.kt> |
| Termux (Android) | Sessions in a drawer swiped in from the left part of the screen (read in a copy of its manual; the wiki refused the request) | <https://wiki.termux.com/wiki/User_Interface> |
| Blink Shell (iOS) | "To switch between active shells, swipe side to side" | <https://docs.blink.sh> |
| ShellFish (iOS) | No swipe: a button beside the title shows session thumbnails to tap | <https://secureshellfish.app/help/tmux> |
| Hi Term | A sideways swipe changes the tmux window and the window's name shows briefly at the top | <https://hiterm.yinbin.ink/docs/phone-operations> |

No swipe between sessions was found for Termius, Prompt 3 or La Terminal.

What we take from them:

- **Swipe in one fixed place.** Apps that swipe on the terminal itself need
  rules to tell a step from a scroll, a pan or a selection (ConnectBot's
  thresholds), split the meanings over one and two fingers (Moshi), or turn
  it off by default (ConnectBot). kudde's terminal already takes taps as
  clicks, drags as pan and scroll, and a pinch as zoom. The official app and
  kudde today both swipe on the header only. We keep that.
- **A map and a list, not live pictures.** Several clients draw the real
  split. A small map plus rows with names costs no extra connections and
  never resizes a desk terminal; live tiles would attach to every pane of a
  tab.
- **No wrap.** ConnectBot stops at the ends; a walk that wraps hides where
  the desk's order starts.
- **A tap for everything.** ShellFish reaches every session by taps, and
  ConnectBot's own text names accessibility as the reason its swipe is off
  by default.

## 5. Android's guidance

- **Back owns the edges.** Back is "an inward swipe from either the left or
  the right edge". An app can exclude regions
  (`setSystemGestureExclusionRects`, API 29; in Compose
  `Modifier.systemGestureExclusion`), but "the system will put a limit of
  200dp on the vertical extent of the exclusions it takes into account", and
  some gesture areas cannot be opted out of at all.
  <https://developer.android.com/develop/ui/views/touch-and-input/gestures/gesturenav>,
  <https://developer.android.com/reference/android/view/WindowInsets.Type>
- **Drawers.** The old view `DrawerLayout` opts out by itself; Material 3's
  Compose drawer has no such call in its source, so an edge swipe there goes
  to Back (inferred from the source). Google's own figures put drawer-swipe
  use at "~3-7% of users".
  <https://android-developers.googleblog.com/2019/08/gesture-navigation-backstory.html>
- **Predictive back.** Back must go through the back callback APIs for the
  system's animation; a custom handler replaces it.
  <https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture>
- **Pagers.** A pager takes all sideways movement of its page by default,
  so a page that also scrolls sideways needs its own nested-scroll rules.
  <https://developer.android.com/develop/ui/compose/layouts/pager>
- **Tabs and swiping content.** Material's guidance since its first version:
  "Because swipe gestures are used for navigating between tabs, don't pair
  tabs with content that also supports swiping."
  <https://m1.material.io/components/tabs.html>
- **Every gesture needs another way.** Android: "users of accessibility
  services have a way to complete all user flows", and for gestures that are
  not taps, custom accessibility actions.
  <https://developer.android.com/develop/ui/compose/accessibility/semantics>.
  WCAG 2.2, 2.5.1: "All functionality that uses multipoint or path-based
  gestures for operation can be operated with a single pointer without a
  path-based gesture, unless a multipoint or path-based gesture is
  essential." <https://www.w3.org/TR/WCAG22/>

What we take from it: no drawer and no edge swipe; no gesture exclusion; the
swipe stays on the title bar, away from the terminal's own sideways
movement; no pager; and a tap path plus screen-reader actions for every
step.
