# terminal-view Specification (delta)

## ADDED Requirements

### Requirement: The terminal is drawn by libghostty-vt
The app SHALL feed attach-stream bytes to libghostty-vt and SHALL draw its
screen state: characters, 16/256/true colours, bold, italic, underline styles,
inverse, cursor, and wide characters. The first paint (raw VT bytes) SHALL go
through the same path.

#### Scenario: Claude Code's screen
- **WHEN** a terminal running Claude Code is opened on the phone
- **THEN** the phone shows the same characters and colours as the Mac app for the same terminal (screenshot comparison at equal size)

### Requirement: Watching never changes the terminal's size
By default the app SHALL attach with `observe: true`, SHALL draw the terminal at
its real columns and rows, and SHALL NOT send RESIZE, FOCUS, FACTS or input on
that attach.

#### Scenario: Opening an agent from the phone
- **WHEN** a terminal is 180x50 on the Mac and Ezra opens it on the phone
- **THEN** the terminal stays 180x50 and the Mac's screen does not reflow

### Requirement: Fit to width, zoom and pan
The terminal SHALL open scaled to fit the screen width. Pinch SHALL zoom between
fit-to-width and a readable minimum cell size; when zoomed in, the view SHALL
pan. Landscape SHALL show more of the terminal at the same zoom rule. The zoom
level SHALL be remembered per terminal on the phone.

#### Scenario: Reading small text
- **WHEN** Ezra pinches out on a 180-column terminal
- **THEN** the text grows, the view pans with a drag, and pinching back returns to fit-to-width

### Requirement: Scrollback with a way back to live
The attach SHALL request scrollback history. Flicking SHALL scroll it locally;
while scrolled up, a "Live" control SHALL return to the bottom, and new output
SHALL NOT yank the view down.

#### Scenario: Reading earlier output while the agent works
- **WHEN** Ezra scrolls up while new output arrives
- **THEN** the view stays where it is and "Live" is shown until tapped

### Requirement: Select and copy text
A long press SHALL switch to a text selection layer built from `terminal.read`
(screen plus history), with the platform's copy action. "Copy last output" SHALL
copy the lines since the last prompt when the program marks prompts, else the
visible screen.

#### Scenario: Copy an error
- **WHEN** Ezra long-presses on an error line and copies it
- **THEN** the clipboard holds that text without colour codes

### Requirement: Opening is instant
On opening a terminal the app SHALL show the last known screen text immediately
and SHALL swap to the live view when the first paint arrives.

#### Scenario: Tap from the list
- **WHEN** Ezra taps an agent while the phone is connected
- **THEN** text is visible within 200 ms and the live view replaces it without a blank frame

### Requirement: Drawing keeps up
The app SHALL keep drawing at the display refresh rate up to 60 fps for a 200x60
terminal receiving continuous output, on the target phone.

#### Scenario: Fast output
- **WHEN** a terminal prints 10,000 lines as fast as it can
- **THEN** the view stays responsive to scroll and the final screen matches the Mac's
