# key-bar Specification (delta)

## ADDED Requirements

### Requirement: The key bar shows the person's arrangement
While the phone types, the terminal SHALL show the key bar as the person
arranged it: one, two or three rows, every row always visible. A row SHALL
hold at least one key and at most nine places; a text key takes two places
and every other key one. Keys in a row SHALL share its width by their
places. A key SHALL be in the bar at most once. A fresh install SHALL show
the standard bar: `esc ctrl alt tab [keyboard] - / ↑ ~` over
`^C ⇧⇥ \ ⏎ [compose] | ← ↓ →`. The phone's terminal size SHALL leave room
for exactly the bar's rows. One arrangement SHALL serve every terminal.

#### Scenario: Nothing changed
- **GIVEN** a fresh install
- **WHEN** the person types in a terminal
- **THEN** the bar looks and behaves as before this change, pixel for pixel

#### Scenario: One row gives more terminal
- **GIVEN** the bar has one row
- **WHEN** the person types in a terminal with the keyboard hidden
- **THEN** the terminal has the rows of one more 48 dp strip than with the standard bar, and the size reported to the machine matches

#### Scenario: Three rows
- **GIVEN** the bar has three rows
- **WHEN** the person types in a terminal
- **THEN** all three rows show above the keyboard, and the terminal is one 48 dp strip shorter than with the standard bar

#### Scenario: Fewer keys are wider
- **GIVEN** a row holds five one-place keys
- **WHEN** the bar is drawn
- **THEN** each of the five keys is one fifth of the row's width, less the gaps

### Requirement: The keys on offer
The keys that can be placed in the bar SHALL be:
- escape (a long press interrupts, as today), ctrl and alt (they hold for the
  next key), tab, shift+tab, enter;
- the keyboard key and the compose key;
- the four arrows as four keys, and the arrows key;
- the symbols `-`, `/`, `~`, `\` and `|`;
- home, end, page up, page down and delete;
- f1 to f12;
- ctrl with any one letter from a to z, shown as `^` and the capital letter;
- the person's text keys.

Home, end, page up, page down, delete and f1 to f12 SHALL go through the
same key encoder as typing, take a held ctrl or alt, and release it. A
ctrl + letter key SHALL act as ctrl held and the letter typed.

#### Scenario: Page up in a pager
- **GIVEN** the bar has the page up key and a pager is open in the terminal
- **WHEN** the person taps page up
- **THEN** the program receives the page up key as the encoder writes it for that program's keyboard mode

#### Scenario: f5
- **GIVEN** the bar has f5 and the program uses the default keyboard mode
- **WHEN** the person taps f5
- **THEN** the program receives `ESC [ 1 5 ~`

#### Scenario: Ctrl + r from one key
- **GIVEN** the bar has a `^R` key
- **WHEN** the person taps it at a shell prompt
- **THEN** the shell receives ctrl+r, and ctrl is not left held

#### Scenario: Alt held, then home
- **GIVEN** alt is held
- **WHEN** the person taps home
- **THEN** the program receives alt+home, and alt is released

#### Scenario: Escape keeps its long press wherever it sits
- **GIVEN** escape was moved to the bottom row
- **WHEN** the person long-presses it on a working agent
- **THEN** the agent is interrupted and the report shows, as before

### Requirement: Text keys type the person's own text
A text key SHALL have a label of 1 to 6 characters, a text of 1 to 200
characters on one line, and a choice to press Enter after. The label SHALL
be stored without spaces at its ends; the text SHALL be stored as typed. A
text containing U+0000–U+001F, U+007F–U+009F, U+2028 or U+2029 SHALL be
refused, including tab, CR, LF, ESC and DEL. This validation SHALL apply on
save, stored-data load and the Rust delivery boundary. Enter SHALL be a
separate boolean, never a character in the saved text; the only generated
control action SHALL be one final Enter when that boolean is true. There
SHALL be at most 24 text keys. A tap SHALL release a held ctrl or alt
without using it, type the text character by character through the same key
encoder as the phone keyboard, and then press Enter when the key says so.
The app SHALL NOT ask before typing and SHALL NOT judge the text.

#### Scenario: A key that clears the conversation
- **GIVEN** a text key with the label `clear`, the text `/clear` and Enter on
- **WHEN** the person taps it while an agent's input box is empty
- **THEN** the agent receives the characters `/clear` as typed keys, then Enter

#### Scenario: A key without Enter
- **GIVEN** a text key with the text `git status` and Enter off
- **WHEN** the person taps it at a shell prompt
- **THEN** `git status` stands on the prompt line and nothing has run

#### Scenario: Ctrl was held
- **GIVEN** ctrl is held and a text key types `yes`
- **WHEN** the person taps the text key
- **THEN** the program receives `y`, `e`, `s` without ctrl, and ctrl is no longer held

#### Scenario: Two taps
- **GIVEN** a healthy unchanged terminal attachment
- **WHEN** the person taps a text key twice
- **THEN** two distinct activations each type its text once, in order

#### Scenario: A text with a line break
- **WHEN** the person saves a text key whose text holds a line break, pasted in
- **THEN** the key is not saved and the dialog says "One line only"

#### Scenario: An empty label
- **WHEN** the person saves a text key whose label is only spaces
- **THEN** the key is not saved and the dialog says "Give the key a label"

#### Scenario: The twenty-fifth text key
- **GIVEN** 24 text keys exist
- **WHEN** the person opens the Keys screen
- **THEN** "New text key" is dimmed and says "24 text keys is the most"

### Requirement: One arrows key that is dragged
The arrows key SHALL send arrow keys while a finger moves on it: one arrow
for every 24 dp of travel since the press or the last arrow, in the
direction of the larger of the horizontal and vertical travel (horizontal
when equal). The direction MAY change during one drag. A press that travels
less than 24 dp SHALL send nothing. Lifting the finger SHALL end it; nothing
repeats by time. A held ctrl or alt SHALL apply to the first arrow and be
released. Each arrow SHALL give the system's key feedback. The four separate
arrow keys SHALL stay on offer, and with a screen reader the arrows key
SHALL offer Up, Down, Left and Right as actions that need no drag.

#### Scenario: Two options down
- **GIVEN** an agent shows a numbered menu and the bar has the arrows key
- **WHEN** the person presses the arrows key and drags 50 dp straight down
- **THEN** the program receives down twice

#### Scenario: Down, then back up
- **WHEN** the person drags 30 dp down and then, without lifting, 30 dp up
- **THEN** the program receives down once, then up once

#### Scenario: A tap
- **WHEN** the person taps the arrows key without moving
- **THEN** nothing is sent

#### Scenario: A diagonal
- **WHEN** the person drags 30 dp right and 10 dp down in one motion
- **THEN** the program receives right once and no down

#### Scenario: With a screen reader
- **WHEN** a screen reader user opens the actions of the arrows key and picks Down
- **THEN** the program receives down once

### Requirement: A Keys screen arranges the bar
The Machines screen's "This phone" card SHALL have a Keys row that says how
many rows and keys the bar has and opens the Keys screen. The Keys screen
SHALL show the same keys, order, rows and relative widths as the terminal,
in an enlarged selection surface. Every selectable key SHALL have a
non-overlapping touch target at least 48 dp wide and 48 dp high. When a row
does not fit the viewport it SHALL scroll horizontally, never compress the
targets or rely on overlapping automatic touch expansion. A tap on a key there
SHALL select it and SHALL NOT send anything. For the selected key the
screen SHALL offer:
- Left and Right, which swap it with its neighbour in the row, and are
  unavailable at the row's ends;
- Row up and Row down, which move it to the next row at the same position
  counted from the left, or to that row's end when the row is shorter; they
  are unavailable when that row has no room; Row down from the last row
  starts a new row when there are fewer than three rows and the key is not
  alone in its row;
- Remove, which takes it out of the bar and puts it back among the keys to
  add;
- Edit, for a text key.

Below, the screen SHALL list the keys that are not in the bar, in the groups
of "The keys on offer", and the ways to make a ctrl + letter key and a text
key. A tap on a listed key SHALL add it at the end of the lowest row that
has room for it, or as a new last row when no row has room and there are
fewer than three, and SHALL select it. When no room exists the key SHALL NOT
be added and the screen SHALL say so. A row left without keys SHALL
disappear. The keyboard key SHALL NOT be removable. Every change SHALL be
kept at once. Everything the screen does by dragging SHALL also be possible
with these single taps. The buttons and list rows SHALL have non-overlapping touch targets at least
48 dp wide and 48 dp high.

#### Scenario: The row on Machines
- **GIVEN** the standard bar
- **WHEN** the person opens Machines
- **THEN** the Keys row reads "2 rows, 18 keys"

#### Scenario: Swapping two keys
- **GIVEN** the top row starts `esc ctrl alt`
- **WHEN** the person selects ctrl and taps Left
- **THEN** the top row starts `ctrl esc alt`, ctrl stays selected, and the next terminal shows it so

#### Scenario: At the row's end
- **WHEN** the person selects the first key of a row
- **THEN** Left is unavailable

#### Scenario: Moving to a full row
- **GIVEN** both rows hold nine places
- **WHEN** the person selects a key in the top row
- **THEN** Row down is unavailable

#### Scenario: Starting a third row
- **GIVEN** two rows, and `|` selected in the bottom row
- **WHEN** the person taps Row down
- **THEN** a third row holds `|` alone and the bottom row has one key fewer

#### Scenario: Removing a key
- **WHEN** the person selects `~` and taps Remove
- **THEN** `~` leaves the bar, the row's other keys grow wider, and `~` is listed among the keys to add

#### Scenario: Adding a key
- **GIVEN** the bottom row holds nine places and the top row eight
- **WHEN** the person taps page up in the list
- **THEN** page up is the last key of the top row and is selected

#### Scenario: A text key needs two places
- **GIVEN** one row has one place free and no other row has room, with three rows in the bar
- **WHEN** the person taps a text key in the list
- **THEN** it is not added and the screen says "The bar is full. Remove a key first."

#### Scenario: The keyboard key stays
- **WHEN** the person selects the keyboard key
- **THEN** Remove is unavailable and the screen says "The keyboard key stays. It is the only way to open the keyboard."

#### Scenario: The last key of a row
- **GIVEN** a third row that holds one key
- **WHEN** the person removes that key
- **THEN** the bar has two rows

#### Scenario: Removing a text key keeps its text
- **GIVEN** the text key `clear` is in the bar
- **WHEN** the person removes it from the bar
- **THEN** it is listed under the person's text keys with its text and Enter choice unchanged

#### Scenario: Editing a text key in place
- **GIVEN** the text key `clear` is the third key of the top row
- **WHEN** the person selects it, taps Edit, changes its label to `clr` and saves
- **THEN** the third key of the top row reads `clr`

#### Scenario: Deleting a text key
- **WHEN** the person taps Delete in a text key's dialog and confirms
- **THEN** the key is gone from the bar and from the list, and its text is not kept

#### Scenario: A ctrl + letter key
- **WHEN** the person chooses the ctrl + letter entry, types `r` and adds it
- **THEN** a `^R` key is added to the bar and selected

#### Scenario: A letter that already has a key
- **GIVEN** `^C` is in the bar
- **WHEN** the person enters `c` in the ctrl + letter dialog
- **THEN** no second key is added and `^C` is selected

#### Scenario: With a screen reader
- **WHEN** a screen reader lands on a key in the picture
- **THEN** it reads the key's spoken name and whether it is selected, and offers Move left, Move right, Move up a row, Move down a row and Remove as actions

### Requirement: Reset brings back the standard bar
The Keys screen SHALL offer a reset that, after the person confirms, puts
the standard bar back. Text keys SHALL be kept as keys to add.

#### Scenario: Reset
- **GIVEN** a bar of one row with two text keys in it
- **WHEN** the person taps reset and confirms
- **THEN** the bar is the standard two rows, and both text keys are listed under the person's text keys

#### Scenario: Reset cancelled
- **WHEN** the person taps reset and then Cancel
- **THEN** nothing changes

### Requirement: The arrangement is kept on the phone
The arrangement and the text keys SHALL be stored on the phone, SHALL
survive a restart and an update, and SHALL NOT be part of the machine list
export. What is read back SHALL be repaired, never crash the app: stored
data that cannot be read gives the standard bar and no text keys; an unknown
key is dropped; a key listed twice keeps its first place; keys past a row's
ninth place and rows past the third are dropped; an empty row is dropped; a
bar without the keyboard key gets it at the end of the lowest row with room,
or in place of the last key of the last row when no row has room.

#### Scenario: After a restart
- **GIVEN** the person arranged a one-row bar
- **WHEN** the app is closed and opened again
- **THEN** the terminal shows the same one-row bar

#### Scenario: Unreadable stored data
- **GIVEN** the stored arrangement is not valid data
- **WHEN** the app starts
- **THEN** the terminal shows the standard bar and the app does not crash

#### Scenario: A key from a newer version
- **GIVEN** the stored arrangement names a key this version does not know
- **WHEN** the app starts
- **THEN** the bar shows the other keys in their order, without it

#### Scenario: A stored bar without the keyboard key
- **GIVEN** the stored arrangement has one row of `esc tab` only
- **WHEN** the app starts
- **THEN** the row is `esc tab [keyboard]`

### Requirement: One text-key activation belongs to one live attachment
A terminal text-key tap SHALL capture its machine, terminal, connection epoch
and attach generation at activation, and enqueue one bounded command holding
the entire text and Enter boolean. Without a live attachment it SHALL be
discarded, never queued for reconnection. The receiver SHALL check those
identities before delivery and before any later write, including Enter.
The command SHALL expire one second after activation: before every write,
an expired activation SHALL discard all unsent text and Enter. It SHALL NOT
wait for attachment readiness or reconnect. Bytes already handed to the
transport may arrive later; this contract forbids application buffering and
retries, and cannot retract bytes already in flight.
An intervening disconnect, reattach, terminal switch or view closure SHALL
cancel any undelivered part. The text and optional Enter SHALL be encoded in
order without interleaving another activation. No character or Enter SHALL be
retried after failure or uncertain delivery, nor replayed on a new connection
or attachment. Already delivered characters cannot be rolled back. A fresh
tap is a new attempt; the app SHALL NOT promise remote atomicity or exactly-
once execution by the program. A text key SHALL NOT be treated as paste.

#### Scenario: Disconnect before the command is consumed
- **GIVEN** a tap queued for terminal api on connection epoch 1 and attach generation 1
- **WHEN** the connection is lost before the receiver handles it and epoch 2 attaches
- **THEN** neither the text nor Enter is written on epoch 2

#### Scenario: Disconnect between text and Enter
- **GIVEN** a text key with Enter enabled and a fake core that accepts the text write
- **WHEN** the connection fails before Enter can be written and then reconnects
- **THEN** no Enter or replayed text is written after reconnect

#### Scenario: Interrupted text delivery
- **GIVEN** a fake core accepts only a prefix of a text-key activation
- **WHEN** the write fails or its outcome is unknown
- **THEN** no suffix, Enter or retry is sent on that or any replacement attachment

#### Scenario: Switching terminals before delivery
- **GIVEN** a tap captured for terminal api
- **WHEN** the person switches to terminal docs before delivery
- **THEN** the activation is cancelled and neither terminal receives its undelivered text or Enter

#### Scenario: Same connection, new attachment
- **GIVEN** a text-key command queued against attach generation 1
- **WHEN** the viewer reattaches as generation 2 on the same connection before delivery
- **THEN** the old command sends nothing on generation 2

#### Scenario: Tapping while offline
- **GIVEN** no live terminal attachment
- **WHEN** the person taps a text key and the connection later recovers
- **THEN** nothing from that tap is delivered

#### Scenario: Control characters refused at all boundaries
- **GIVEN** a text containing any C0 or C1 control, U+2028 or U+2029
- **WHEN** it is saved, read from storage, or passed directly to the Rust delivery call
- **THEN** save refuses it with "One line only", load drops the invalid text key and its bar references, and Rust sends nothing, including no final Enter

#### Scenario: Empty delivery cannot send Enter alone
- **GIVEN** an empty text or more than 200 Unicode code points and Enter enabled
- **WHEN** the Rust delivery call is invoked
- **THEN** nothing is sent

#### Scenario: Editor targets on a narrow phone
- **GIVEN** nine one-place keys in a row on a 360 dp or 412 dp viewport at normal or twice font size
- **WHEN** the Keys screen is measured and scrolled to each key
- **THEN** every key target measures at least 48 by 48 dp, targets do not overlap, and tapping just inside either side of each adjacent boundary selects only the corresponding key and sends nothing

### Requirement: Updates preserve or initialize the key bar
An update from the current fixed-bar version SHALL initialize the standard
two rows and no text keys when the keys preference is absent. A later update
SHALL preserve valid stored arrangements and text keys, applying only the
specified repairs to invalid entries. Invalid text records SHALL be dropped
with their bar references before row repair. Text length SHALL count Unicode
code points consistently in Kotlin and Rust. Theme and appearance preferences
and unrelated settings SHALL remain unchanged.

#### Scenario: Upgrade from the fixed bar
- **GIVEN** the current version with Higher text contrast on and no keys preference
- **WHEN** an update starts
- **THEN** the terminal keeps the same standard two rows, no text keys exist, and Higher text contrast is still on

#### Scenario: Upgrade with an edited bar
- **GIVEN** valid stored custom rows and text keys, and a saved theme pair and appearance
- **WHEN** an update starts
- **THEN** those rows, texts, Enter flags, theme pair and appearance are unchanged

#### Scenario: A queued activation expires
- **GIVEN** a live attachment and a queued activation with Enter enabled
- **WHEN** the receiver is delayed until at least one second after activation
- **THEN** no text or Enter is written, even if the same attachment remains live
