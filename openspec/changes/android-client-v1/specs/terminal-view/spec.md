# terminal-view Specification (delta)

## ADDED Requirements

### Requirement: The terminal is drawn by libghostty-vt
The app SHALL feed attach-stream bytes, including the first paint, to
libghostty-vt and SHALL draw its state: characters, 16/256/true colour, bold,
italic, underline styles, inverse, cursor, wide characters. Its cell grid SHALL
match the cell grid herdr-app's VT pipeline produces for the same bytes at the
same Ghostty pin.

#### Scenario: Cell-grid fidelity
- **WHEN** a recorded synthetic Claude Code byte stream is fed to the app's VT
- **THEN** the text and attributes of every cell equal the golden grid produced by herdr-app's `vt.rs` pipeline for the same stream

### Requirement: Opening takes the phone's size; watching never changes it
Opening an agent SHALL attach once, actively, at the phone's size (phone check
round 2: the open is the person's interaction); leaving SHALL detach, so the desk
takes its own size back. Once the desk takes the size back while the phone is
open, the phone watches: it SHALL attach with `observe: true`, SHALL draw the
terminal at its real columns and rows, and SHALL NOT send input, RESIZE, FOCUS or
FACTS on that attach.

#### Scenario: Opening an agent
- **WHEN** a terminal is 180x50 at the desk and Ezra opens it on the phone
- **THEN** it reflows to the phone's size at once, and returns to 180x50 when he goes back to the list

#### Scenario: The desk takes it back
- **WHEN** Ezra types at the desk while the phone has the terminal open
- **THEN** the terminal returns to the desk's size and the phone watches it at that size

### Requirement: Size changes reattach cleanly
On a `size` event for the open terminal, and on any reattach, the app SHALL discard
its VT state and attach afresh, so no stale content is drawn.

#### Scenario: Desk resizes mid-stream
- **WHEN** the desk window is resized while output is streaming to the phone
- **THEN** the phone shows the terminal at the new size with no garbled lines

### Requirement: Fit to width, zoom and pan
The terminal SHALL open scaled to fit the screen width. Pinch SHALL zoom between
fit-to-width and a readable minimum cell size, with panning when zoomed. The zoom
level SHALL be remembered per terminal.

#### Scenario: Reading small text
- **WHEN** Ezra pinches out on a 180-column terminal
- **THEN** the text grows, a drag pans, and pinching back returns to fit-to-width

### Requirement: Scrollback with a way back to live
The attach SHALL request 2,000 lines of history. On the main screen, flicking
SHALL scroll locally; while scrolled up, new output SHALL NOT move the view and a
"Live" control SHALL return to the bottom.

A full-screen program (on the alternate screen, or tracking the mouse) keeps its
own history and herdr sends none, so flicking SHALL scroll the program itself, as
the desk app does: mouse wheel events while it tracks the mouse, otherwise arrow
keys, one per row. Because a watching attach never sends input, the first such
flick while watching SHALL take over at the phone's size, as the first key does.

#### Scenario: Reading while the agent works
- **WHEN** Ezra scrolls up while output arrives
- **THEN** the view stays put and "Live" is shown until tapped

#### Scenario: Scrolling a full-screen agent
- **WHEN** Ezra flicks down on a full-screen Claude Code that tracks the mouse
- **THEN** the program receives one wheel-up event per row, and the phone leads at its own size

### Requirement: Select and copy from the local screen
A long press SHALL switch to a text selection layer built from the local VT
screen and scrollback, with the platform copy action. The app SHALL NOT call
`terminal.read` with `lines` above 0 (it scrolls full-screen programs for every
viewer).

#### Scenario: Copy an error
- **WHEN** Ezra long-presses an error line and copies it
- **THEN** the clipboard holds the text without colour codes, and the desk's screen did not move

### Requirement: Opening is instant
The app SHALL keep the last VT state per terminal in app-private storage,
encrypted by a Keystore key, wiped when its machine is removed and after 7 days
unused, and SHALL show it on open until the live first paint arrives.

#### Scenario: Tap from the list (device check)
- **WHEN** Ezra taps a recently viewed agent on the Pixel
- **THEN** its last screen is visible within 200 ms and the live view replaces it without a blank frame

### Requirement: Drawing keeps up
The app SHALL keep p95 frame time at or below 16.7 ms on the Pixel for a 200x60
terminal under continuous output, measured with Macrobenchmark at the phone check.

#### Scenario: Fast output (device check)
- **WHEN** a terminal prints 10,000 lines as fast as it can
- **THEN** p95 frame time stays within 16.7 ms and the final grid matches the golden

### Requirement: An unexpected size change is noticed
After an observe attach the app SHALL compare the terminal's size from
`terminal.get` with the size before attaching and SHALL log any difference as a
diagnostic.

#### Scenario: Core behaviour drift
- **WHEN** an untested core changes size on an observe attach
- **THEN** the diagnostic log records "desk size changed on observe attach" with both sizes
