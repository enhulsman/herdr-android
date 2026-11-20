# Proposal: a key bar you can edit

## Plain summary

**Decisions for the owner** (my pick first in each; nothing is settled yet):

1. **Rows or pages.** *Rows, all visible: one, two or three, up to nine
   places each.* The other option is one row that swipes through pages, as
   Herdr's own Android app does. Pages give back a row of terminal, but
   they hide keys behind a swipe with three dots as the only hint, and this
   project already saw a hidden shelf of keys go unfound at a phone check
   (2026-11-03). Someone who wants the extra terminal picks one row.
2. **The standard bar.** *Stays exactly as signed off on 2026-11-03*: two
   rows of nine. The other option is a new standard bar that uses the
   arrows key.
3. **Which keys are on offer.** *Today's 18, plus:* one arrows key you drag,
   home, end, page up, page down, delete, f1 to f12, any ctrl + letter, and
   your own text keys. The other option is a shorter list without the
   function keys.
4. **Text keys.** *A label, one line of text, and a "Press Enter after"
   switch.* A tap types the text into the terminal as keys, and presses
   Enter if you set it, without asking: you made the key. It is two places
   wide so its label stays readable. The other option is that a text key
   never presses Enter.
5. **How you rearrange.** *The Keys screen shows an enlarged picture of the bar. You tap a
   key and move it with buttons.* That works without dragging, so it is the
   accessible way and the first one built. Dragging keys in that picture is
   a second step and needs a phone check. No new library. The other option
   is a list with drag handles from a reorder library, a new dependency.
6. **One bar or several.** *One bar for every terminal, kept on this phone.*
   A bar per kind of agent can come later.
7. **The arrows key.** *It steps by distance*: every 24 dp you drag sends
   one arrow, and lifting stops. The other option is to repeat while held,
   as Termux does; next to Enter that can run past the option you wanted.

One thing is not a choice: **the keyboard key cannot be removed.** A tap on
the terminal never opens the keyboard, so without that key there is no way
to open it.

**Every text on screen** is listed in `design.md` ("Texts") word for word.
Each one needs the owner's approval before it is built in.

**What changes for you:**

- A new "Keys" row under "This phone" opens the Keys screen. It shows your
  bar with the same rows and order, enlarged so every key has at least a
  48 by 48 dp target; wide rows scroll sideways. Tap a key to move it left, right or to
  another row, or to remove it. Below are the keys you can add.
- You can make text keys, for example a key labelled `clear` that types
  `/clear` and presses Enter, or `yes` without Enter.
- You can swap the four arrow keys for one arrows key and drag on it.
- The bar can be one row, for more terminal, or three, for more keys.
- "Reset to the standard keys" brings back today's bar. Your text keys are
  kept.
- Until you change something, the terminal looks exactly as it does now,
  including after an update. Later updates keep your arrangement and text keys.
- A text key belongs to the terminal and connection you tapped it on. If
  delivery is interrupted, any unsent text and Enter are discarded; they
  are never retried after reconnection. Text already typed cannot be undone.

## Why

Issue #30: "Let the user choose and order the terminal keys rows." The bar
is fixed in code. Someone who never uses `~` or `\` cannot swap them for the
keys they miss, and there is no way to send a text one uses twenty times a
day in one tap. Herdr's own Android app has an editable bar with text keys;
Termux, Termius, Prompt, Secure ShellFish and Moshi all have one too
(`research/key-bar-editor-2026-11.md`).

## What Changes

- The bar is drawn from an arrangement the person owns: one to three rows of
  up to nine places. The standard arrangement is today's bar.
- New keys: home, end, page up, page down, delete, f1 to f12, ctrl + any
  letter, an arrows key, and text keys. The first three kinds need the
  key encoder to learn them (Rust).
- A Keys screen: the bar as a picture, buttons for the selected key, lists
  of keys to add, text key and ctrl + letter dialogs, reset.
- The arrangement and the text keys are stored on the phone only.
- The phone's terminal size follows the number of rows.
- The requirement "A keys row for keys the phone keyboard lacks" in the open
  `android-client-v1` delta gets one reworded sentence (task 1.1).

Not in this change: pages; a lock for ctrl and alt; keys that repeat while
held; a paste key; a different bar per agent or per terminal; exporting the
bar with the machine list; shortcuts in the chat (see `compose-shortcuts`).

## Capabilities

### New Capabilities
- `key-bar`: the arrangement and its limits, the keys on offer, text keys,
  the arrows key, the Keys screen, and what is stored.

### Modified Capabilities
- `accessibility`: "Every key has a spoken name" covers the new keys, text
  keys and the arrows key's actions; "The terminal screen fits the largest
  font size" covers a text key's label.

## Impact

- Rust, `kudde-mobile`: new variants on `keys_row::Key` and the `RowKeyTap`
  UniFFI enum, key constants in the engine bindings, one new call that
  types a text without modifiers.
- Android: `ui/terminal/TerminalScreen.kt` (the bar is drawn from a model,
  the grid's reserved height), a new `ui/keys` package (model, storage,
  screen), one row in `ui/machines`, `MachineFlows`, strings.
- `design-tokens.json` (`components.keysRow` describes the standard bar; a
  new entry for the Keys screen), new renders and goldens, which need
  sign-off. Existing terminal goldens must not change.
- Shares the "This phone" card with `theme-pairs` and the terminal screen
  with the wide layout. `desktop-chat` does not touch the bar.
- Estimate: 6 to 8 builder runs, plus one sign-off stop and one phone check.
  Confidence: moderate.
