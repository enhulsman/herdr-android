# Research: a key bar the person can edit (2026-11-20)

For the change `key-bar-editor` (issue #30). Read-only: code and specs in this
repository, a walkthrough of Herdr's own Android app on a test phone, and
public documentation of other apps. Nothing was built or run.

## What kudde has today

- Two fixed rows of nine keys, `KEY_LINES` in
  `android/app/src/main/kotlin/dev/kudde/android/ui/terminal/TerminalScreen.kt:669-692`:
  `esc ctrl alt tab [keyboard] - / ↑ ~` over `^C ⇧⇥ \ ⏎ [compose] | ← ↓ →`.
  The rows show only while the phone types (`TerminalScreen.kt:184`, `:196`).
- Each key takes an equal share of the row and is 40 dp high in a 48 dp line
  (`TerminalScreen.kt:762`, `ui/theme/Dimens.kt:54`). On a 412 dp phone a key
  is about 41 x 40 dp. A label shrinks to fit at large font sizes
  (`TerminalScreen.kt:778`).
- Ctrl and alt arm for the next key and release on a second tap; there is no
  lock (`crates/kudde-mobile/src/keys_row.rs:53-90`). `^C` is one tap
  (`keys_row.rs:75-78`). No key repeats while held: a key is a plain click
  with an optional long press (`TerminalScreen.kt:803-804`), used only by esc,
  whose long press interrupts the agent (`TerminalScreen.kt:718-724`).
- What a key can be is fixed in Rust: `RowKeyTap` has esc, tab, shift+tab,
  ctrl, alt, the four arrows, enter, ctrl+c and one-character symbols
  (`crates/kudde-mobile/src/lib.rs:439-455`). The encoder knows no function,
  page, home, end or delete keys (`crates/kudde-mobile/src/vt/keys.rs:80-92`);
  the pinned engine does (`GHOSTTY_KEY_HOME`, `_END`, `_PAGE_UP`, `_PAGE_DOWN`,
  `_DELETE`, `_F1` to `_F12` in its `key/event.h`).
- Text can already be typed as keys: `type_text` sends each character through
  the encoder and turns a line break into Enter (`lib.rs:564-576`). The
  compose sheet pastes instead, without Enter (`lib.rs:596-608`,
  `ui/terminal/ComposeSheet.kt:49-53`).
- The phone's grid always leaves room for exactly two rows
  (`TerminalScreen.kt:401`).
- There is no settings screen. Phone settings are rows in the "This phone"
  card of the Machines screen (`ui/machines/MachinesScreen.kt:164-202`), each
  kept in one preferences file (`ui/theme/ContrastSetting.kt:36-37`).
- Specs: "two keys rows, both always" with the keys named
  (`openspec/changes/android-client-v1/specs/terminal-input/spec.md`, "A keys
  row for keys the phone keyboard lacks"); whole labels at 2x font and a
  spoken name per key (`openspec/specs/accessibility/spec.md`). No spec
  promises an editor: `decisions.md` (2026-11-03) parks it as "Later, not v1:
  keys the user can arrange, as Termux's extra-keys".
- A lesson already paid for: the first keys row hid keys on a swipe-up shelf.
  At the phone check the shelf "was not found without being told", so enter
  moved to the main row and the shelf was replaced by a second row
  (`decisions.md`, both 2026-11-03 entries).

## How Herdr's own Android app does it

Version 1.0.7, walked through on a test phone in its demo mode.

- One row. A pill holds seven keys and swipes sideways through three pages
  (18 keys in all); three small dots under the pill are the only hint. A
  second, fixed cluster holds Enter, a keyboard key and a compose key.
- Default pages: esc, ctrl, alt, tab, shift+tab, arrows, ^C / ^D, ^Z, ^L,
  `|`, `~`, `/`, `-` / home, end, pgup, pgdn.
- The four arrows are one key, drawn as a four-way arrow. Dragging on it in a
  direction sends that arrow.
- Ctrl and alt: a tap applies to the next key, a long press locks.
- The editor is a settings screen: one list, "in the bar", in bar order,
  each row with up, down and remove; below it keys to add in groups (common
  keys, f1-f12, a custom key). A custom key has a label, the text to type
  and a "press Enter after" switch. One row resets everything.
- Tried: a custom key was added, moved and used; it typed its text and
  pressed Enter. A removed key went without a confirm or an undo.
- Weak points seen: reordering is one step per tap; the list does not show
  where a page ends; nothing can be edited from the bar itself; with the
  compose box open, a key from the bar still goes to the terminal, not into
  the draft.

Not captured, worth a second look if it matters:
1. The arrows key: does a held drag repeat, and how fast? What does a plain
   tap do? Does anything show under the finger? The shots show no feedback.
2. Whether the editor allows the keyboard or compose key to be removed, and
   what the bar does when it is empty.
3. How many keys the bar takes before a fourth page appears, and whether a
   custom key's label has a length limit.

herdr's own page says only that the bar "has Esc, Ctrl, Tab and the arrows,
and you can change what's on it" (<https://herdr.dev/mobile>).

## Other apps

- **Termux** (Android). `extra-keys` in a properties file: an array of rows,
  each key a name or `{key, popup, macro, display}`; `popup` fires on a
  swipe up. Default two rows of seven. Key names include F1-F12, HOME, END,
  PGUP, PGDN, DEL, BKSP, KEYBOARD and PASTE. Arrows, backspace, delete and
  page keys repeat while held (after the long-press time, then every 80 ms).
  A tap on ctrl/alt arms the next key, a long press locks. No editor in the
  app. Sources:
  <https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysInfo.java>,
  <https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysConstants.java>,
  <https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java>.
- **Termius**. Keys in groups of four; groups are dragged to reorder and
  custom groups built. Snippets (name and script) live in a side panel:
  paste inserts, run executes. Arrows by holding space and dragging, with
  repeat. <https://docs.termius.com/terminal/mobile-terminal>,
  <https://docs.termius.com/terminal/snippets>.
- **Prompt 3** (iOS). A row of 16 keys, edited by tapping a key and choosing
  its type; text clips in groups. <https://help.panic.com/prompt/prompt-keyboard/>.
- **Secure ShellFish** (iOS). Long-press a bar button to reassign it; snippets
  replay key sequences and can sit on the bar.
  <https://secureshellfish.app/help/snippets>.
- **Moshi** (iOS, for coding agents). Toolbar items chosen and reordered in
  settings; a shortcut can type text such as `/clear` with an optional Enter;
  a D-pad for arrows; ctrl/alt lock on a double tap.
  <https://getmoshi.app/docs/keyboard>.
- **ConnectBot** (Android). A fixed row; no reordering found.
- **Other herdr clients.** One web client has a key pad edited in the browser
  (drag or arrow buttons, presets) and quick replies read from a file
  (<https://colliepwa.dev/docs/configure>); others mention a key bar without
  detail. Nothing else was findable.

## Android and accessibility guidance

- Touch targets: Android asks for at least 48 x 48 dp
  (<https://support.google.com/accessibility/android/answer/7101858>,
  <https://developer.android.com/develop/ui/compose/accessibility/api-defaults>).
  WCAG 2.2 AA asks for 24 x 24 px (2.5.8), AAA for 44 x 44 (2.5.5)
  (<https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html>).
  This project's own floor is 44 dp (`design-brief.md`, Accessibility); the
  signed-off keys are about 41 x 40 dp.
- Dragging (WCAG 2.5.7, AA): anything done by dragging must also be possible
  with single taps
  (<https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html>).
  Swipes (2.5.1, A) need the same
  (<https://www.w3.org/WAI/WCAG22/Understanding/pointer-gestures.html>).
- Compose has no reorder API of its own; `dragAndDropSource` is for moving
  data between apps
  (<https://developer.android.com/develop/ui/compose/touch-input/user-interactions/drag-and-drop>).
  For screen readers the documented way is custom actions on the item
  (<https://developer.android.com/develop/ui/compose/accessibility/semantics>).
  A common library exists (<https://github.com/Calvin-LL/Reorderable>); it
  would be a new dependency.

## What this change takes, and why

- **An editor inside the app.** Every strong app has one except Termux, and
  Termux's file is the complaint people have about it.
- **Rows that are all visible, not pages.** Pages hide keys behind a swipe
  with dots as the only hint; this project already saw a hidden shelf go
  unfound. A person who wants more terminal picks one row.
- **The editor shows the bar itself.** The weak point of a plain list is that
  it does not show rows or pages. Tapping a key in a picture of the bar and
  moving it with buttons needs no dragging, so it is the accessible way and
  the first one built; dragging in the picture comes second.
- **Text keys with "press Enter after".** The feature people use most in the
  apps aimed at agents (`/clear`, `yes`). Typed as keys, not pasted, so a
  `/` command behaves as if typed by hand (the chat spike saw a pasted
  command open a different dialog, `research/chat-view-spike-2026-11.md`).
- **One arrows key, as an option.** It frees three places. Stepped by
  distance, not by time: a timed repeat next to Enter can overshoot a menu.
  The four separate arrows stay on offer, which is also the no-drag way.
- **Not taken:** pages; a lock for ctrl/alt; held-key repeat; a paste key;
  different bars per agent. Each can be its own small change later.

## Spec-fix verification (2026-11-20)

- **Connection-bound text delivery: holds as a specification gap, moderate
  confidence.** `crates/kudde-mobile/src/lib.rs:564–576` emits a command per
  character via `press` (`:511–512`). `src/term.rs:175–197` waits for new
  connections with the same receiver; `:315–321` changes epochs, and
  `:346–355` handles untagged keys. No `type_plain` exists yet, so this is
  not a demonstrated exploit of that new call. Fixed design D3, key-bar's
  activation requirement and failure scenarios, task 2.3 and UI
  integration task 4.1. Identity includes machine, terminal, connection and
  attach generation, with a one-second expiry for unsent work; no application
  retry or promise of remote atomicity.
- **Editor touch targets: holds, high confidence.**
  `android/app/src/main/kotlin/dev/kudde/android/ui/terminal/TerminalScreen.kt:739–764`
  fixes key height to 40 dp and divides the row width by weight. Reusing
  this unchanged cannot give nine non-overlapping 48 dp targets across
  412 dp (even before padding). Fixed design D6/D8/Risks, proposal summary,
  editor requirement and narrow-screen/boundary-tap scenario, tasks 5.1/5.3.
  The enlarged scrollable editor still needs render sign-off.
- **Migration and controls:** today's bar is `KEY_LINES` in
  `TerminalScreen.kt:669–692`; no stored customization exists to migrate.
  Missing storage initializes that exact bar; later updates preserve valid
  data and repair invalid records. The previous empty-text-plus-Enter task
  contradicted the 1–200 text limit and is corrected. Save, load and Rust
  reject C0/C1 and Unicode line separators; Enter is a separate flag only.
  Requirements/scenarios, D7 and tasks 2.3/3.1/3.2 now cover all boundaries.
- Tasks 1.1, 2.1–2.3 and 3.1–3.2 now carry explicit owner gates: their key
  set, row model, text behaviour and storage scope were still proposals.
- No new or changed on-screen strings: existing "One line only" also
  covers forbidden controls. The complete Texts table remains unapproved.
