# Design: a key bar you can edit

Background, with paths and lines for every statement about today's code:
`research/key-bar-editor-2026-11.md`.

## D1. Rows the person owns, not pages

The bar stays what was signed off on 2026-11-03: rows of keys, all visible,
one tap each. What changes is who decides its content. A row has nine
places; the bar has one to three rows.

Pages were weighed and left out. They save 48 dp, but a key on page two is
two gestures away and nothing on screen says it exists except three dots.
The first keys row hid keys on a swipe-up shelf and the shelf was not found
at the phone check. A swipe-only way also needs a tap alternative (WCAG
2.5.1). One row gives the same 48 dp back with every key still in sight.

Nine places is today's row, so no key gets smaller than it is now. A row
with fewer keys has wider ones.

## D2. One model, three users

A pure Kotlin model, `KeyBar`, holds the rows as lists of key ids and the
text keys. The terminal draws from it, the Keys screen edits it, the storage
writes it. Every rule of the spec (places, moves, add, remove, repair) is a
function on the model with unit tests, so the screens stay thin.

Key ids are stable strings, independent of Kotlin names:
`esc`, `ctrl`, `alt`, `tab`, `shift_tab`, `enter`, `keyboard`, `compose`,
`up`, `down`, `left`, `right`, `arrows`, `dash`, `slash`, `tilde`,
`backslash`, `pipe`, `home`, `end`, `pgup`, `pgdn`, `del`, `f1` to `f12`,
`ctrl:<letter>`, `text:<number>`.

The standard bar in ids:
`esc ctrl alt tab keyboard dash slash up tilde` over
`ctrl:c shift_tab backslash enter compose pipe left down right`.
Today's `^C` key becomes `ctrl:c`; it looks and acts the same.

`KEY_LINES` goes; the tests that walk it (`KeyNamesTest`, `ReadOnceTest`)
walk every key on offer instead.

## D3. What the Rust side learns

- `keys_row::Key` gains `Home`, `End`, `PageUp`, `PageDown`, `Delete` and
  `F(u8)`; the encoder maps them to the engine's key codes, which the pinned
  headers already have. They take and release held modifiers like the
  arrows.
- `RowKeyTap` (UniFFI) gains the same, plus `CtrlChar { text }` for
  ctrl + letter. `CtrlC` stays as it is so nothing that exists changes
  meaning; the Kotlin side may send `CtrlChar("c")` for the `ctrl:c` key.
  Only a to z is accepted; anything else is a no-op, as `Symbol` treats an
  empty text today.
- A new call, `TerminalView::type_plain(text, enter)`: validates 1–200
  Unicode code points with no C0/C1 controls or U+2028/U+2029, releases held
  modifiers unused, and queues one bounded command, not one `Cmd::Key` per
  character. Capture machine, terminal, connection epoch and attach
  generation at the tap boundary; the receiver rechecks them before each
  write and expires unsent work one second after activation. Do not wait for
  attach readiness; bytes already in transport cannot be recalled. Encode
  without modifiers using that attachment's keyboard mode,
  then optional Enter. Serialize activations with no interleaving. Never
  retry after error or uncertain delivery. Cancel undelivered text and
  Enter on disconnect, reattach, terminal switch or view closure; a tap
  while offline is discarded. Invalidate the generation before reuse of
  the channel, including same-connection reattaches. `type_text` is not
  reused: it sends per-character commands and consumes held modifiers.
  The existing watching-to-typing takeover must complete before accepting
  a text-key activation; do not silently transfer a captured activation
  across that reattach. This is at most one delivery attempt, not atomic
  remote execution; a delivered prefix cannot be undone.

Text keys are typed as keys, not pasted. A pasted `/` command once opened a
different dialog than the typed one (`research/chat-view-spike-2026-11.md`),
and "press Enter after" on a paste would need the bracketed-paste question
the compose sheet already has to answer. One line of text avoids it.

## D4. A text key is two places wide

A label like `clear` or `commit` does not fit a 41 dp key at twice the font
size, and the accessibility spec promises whole labels. Two places is about
86 dp on the reference phone and 74 dp on a 360 dp one; six characters fit
there at the smallest label size. The wider key is also the easier target,
which suits the one kind of key that can send a whole command.

## D5. The arrows key steps by distance

Each 24 dp of travel is one arrow; nothing repeats by time. Reasons: the
common use is an agent's menu, where "two down" must be two and not "about
two"; Enter may sit in the same row; and a stepped drag can be reversed
without lifting. 24 dp is a first value; the phone check may move it between
16 and 40 dp (40 dp is what the title bar's swipe uses). Feedback is the
system's key click or tick through the view, which follows the person's
touch-feedback setting.

It is drag-only, so two other ways exist: the four separate arrow keys, and
four screen reader actions on the key itself (WCAG 2.5.7).

How Herdr's app behaves when the drag is held still is not known (see the
research note); this design does not depend on it.

## D6. The Keys screen edits a picture of the bar

A list of keys, the usual editor, does not show rows, and its reorder is
either drag (needs an alternative) or one step per tap. This screen draws
the same row arrangement in an enlarged, horizontally scrollable surface
and lets the person tap a key in it. Each one-place target is at least
48 x 48 dp, each text key takes two places; targets never overlap.
Keep all rows visible and scroll overflowing rows horizontally. Reuse key
visuals without reusing the terminal component's fixed 40 dp target height.
The selected key gets a ring; a strip of buttons under the picture acts on
it. Selecting and tapping buttons is complete on its own and needs no
dragging, so it is built and tested first (task group 5).

Dragging a key inside the picture (long press, move, drop) is task 5.4. It
calls the same model functions, so it cannot do anything the buttons cannot.
It is written by hand on a fixed grid of at most 27 places; a reorder
library would be a new dependency for one small screen.

Text keys are kept apart from the bar. Removing one from the bar does not
delete it, so there is no undo to build and nothing is lost by a slip; only
Delete in its dialog, with a confirm, drops the text.

## D7. Storage

One key, `keys`, in the existing `settings` preferences file, holding JSON:

```json
{"v": 1,
 "rows": [["esc", "ctrl", "..."], ["ctrl:c", "..."]],
 "text": [{"id": 1, "label": "clear", "text": "/clear", "enter": true}]}
```

A missing preference on update from the fixed-bar app gives the standard
bar and no text keys, preserving other preferences. Later updates keep valid
rows and text records. Reading repairs as the spec lists. Another `v` reads
as unreadable. Validate text records before row repair; invalid labels,
lengths, control characters or Enter types drop the record and its row
references. Never strip controls into a different command. The Enter boolean
alone permits a final Enter; CR/LF in text is always invalid. Text
keys sit in the app's private storage as plain text, like the other
settings; the dialog says so, since someone will think of a password. They
are not exported with the machine list and the app has backups off.

## D8. New visual elements, for sign-off

Nothing here is final until the owner has seen a render.

- **The Keys row** on Machines: a plain row with a chevron, after "Theme"
  (or after "Phone key" if `theme-pairs` has not landed).
- **The picture of the bar**: the keys row component itself, on chrome, in
  an enlarged selection surface at the top of the screen (D6).
- **The selected key**: a 1.5 dp outline in the ring colour around the key.
- **The buttons for the selected key**: the existing compact outline
  buttons in one wrapping line; an unavailable one is dimmed.
- **The lists of keys to add**: the card and rows Machines uses. A row shows
  the key's label in the terminal type and what it is in words ("Page up");
  a text key's row shows its label and its text.
- **The dialogs**: the existing modal dialog with the add-machine screen's
  text fields and the existing toggle.
- **The arrows key**: a key with a four-way arrow icon in the line style of
  the keyboard and compose icons. The icon is new and needs drawing.
- **A text key in the bar**: a key like the others, two places wide.

## Texts

Every text below needs the owner's approval before it is built in.

| Where | Text |
|---|---|
| Machines, "This phone", row title | Keys |
| Row detail | `%d rows, %d keys` ("2 rows, 18 keys"; one row: "1 row, 9 keys") |
| Screen title | Keys |
| Under the picture, nothing selected | Tap a key to move or remove it. |
| Buttons for the selected key | Left / Right / Row up / Row down / Remove / Edit |
| When the keyboard key is selected | The keyboard key stays. It is the only way to open the keyboard. |
| No room for a key | The bar is full. Remove a key first. |
| Header of the lists | Add a key |
| List captions | Keys / Function keys / Your text keys |
| Entry that makes a ctrl key | Ctrl + letter |
| Entry that makes a text key | New text key |
| That entry at the limit | 24 text keys is the most |
| Words beside listed keys | Escape / Control / Alt / Tab / Shift Tab / Enter / Keyboard / Compose / Up / Down / Left / Right / Arrows, one key you drag / Dash / Slash / Tilde / Backslash / Pipe / Home / End / Page up / Page down / Delete |
| Ctrl + letter dialog | title "Ctrl + letter", field "Letter", buttons "Cancel" and "Add" |
| Text key dialog | title "Text key", fields "Label" and "Text to type", switch "Press Enter after", buttons "Cancel" and "Save"; when editing also "Delete" |
| Note in the text key dialog | Kept on this phone as plain text. Not for passwords. |
| Dialog errors | Give the key a label / Up to 6 characters / Give the key some text / One line only |
| Delete confirm | title `Delete the key "%s"?`, body "Its text is not kept.", buttons "Cancel" and "Delete" |
| Reset entry | Reset to the standard keys |
| Reset confirm | title "Reset the keys?", body "The bar goes back to the standard two rows. Your text keys are kept.", buttons "Cancel" and "Reset" |
| Spoken: new keys | Home / End / Page up / Page down / Delete / `F %d` / `Control %s` / Arrows |
| Spoken: a text key | `%1$s. Types %2$s` and `%1$s. Types %2$s, then Enter` |
| Spoken: actions on the arrows key | Up / Down / Left / Right |
| Spoken: actions on a key in the picture | Move left / Move right / Move up a row / Move down a row / Remove |

## Risks

- **A text key with Enter is one slip from sending.** Accepted: the person
  made the key, and the wider key helps. Moving it away from the arrows is
  theirs to do.
- **The enlarged editor needs horizontal scrolling on narrow phones.**
  Measure non-overlapping 48 x 48 dp targets and boundary taps; leave the
  terminal bar's signed-off geometry unchanged.
- **Dragging keys** hides the key under the finger. If the phone
  check finds it poor, task 5.4 is dropped and nothing is lost.
- **Existing goldens** of the terminal must pass unchanged; the standard bar
  from the model has to draw byte for byte like `KEY_LINES` did.
- **Sharing the encoder change with `compose-shortcuts`**: none; that change
  uses the text keys' data only.
