## ADDED Requirements

### Requirement: The terminal screen fits the largest font size
At every system font size up to 2x, the terminal screen's title bar SHALL
show both its lines (the agent's name and its place) without clipping, and
each key of the keys row SHALL show its whole label; a label MAY shrink
to fit its key.

#### Scenario: Title bar at 2x
- **WHEN** the system font size is 2x and Ezra opens a terminal
- **THEN** the title bar shows the name and the place line in full height, and the terminal starts below it

#### Scenario: Keys row at 2x
- **WHEN** the system font size is 2x and the keys row shows
- **THEN** every key shows its whole label ("ctrl", not "ctr")

### Requirement: Every key has a spoken name
Each key of the keys row SHALL have a name a screen reader reads. The
symbol keys SHALL be read as "dash", "slash", "tilde", "backslash" and
"pipe". A key with a spoken name SHALL NOT also have its label read.

#### Scenario: A symbol key with TalkBack
- **WHEN** TalkBack lands on the "~" key
- **THEN** it reads "Tilde", not "Tilde, tilde"

### Requirement: A Needs you card is read once
A screen reader SHALL hear a Needs you card's kind and request once: as
the card's state, not again from the ask box under the name.

#### Scenario: A permission card with TalkBack
- **WHEN** TalkBack lands on the card of an agent waiting for a permission
- **THEN** it reads "Permission" (and the request, if any) once, with the agent's name, place and age

### Requirement: A screen reader can read the terminal
While a screen reader is on, the terminal's accessibility node SHALL carry
the text of the visible grid as lines, with box-drawing characters,
block elements and braille spinner marks removed, runs of spaces folded to
one, and empty lines left out. The text SHALL be refreshed at most once a
second, and not at all while no screen reader is on.

#### Scenario: Reading a permission prompt
- **WHEN** TalkBack is on and lands on a terminal showing an agent's permission question inside a box
- **THEN** it reads the question and its choices as plain lines, without the box's lines

#### Scenario: No screen reader
- **WHEN** no screen reader is on
- **THEN** the terminal's node reads only "Terminal" and no text is gathered

### Requirement: The chat announces new answers
The chat SHALL announce once, in the order they arrive, each agent answer
row (kind "said") that arrives live while a screen reader is on and the
chat is open. Rows from the first read or a read-back SHALL NOT be announced,
nor the person's own messages, tool rows or notes.

#### Scenario: An answer arrives
- **WHEN** TalkBack is on, the chat is open, and the agent writes an answer
- **THEN** TalkBack reads the answer once

#### Scenario: Opening a chat
- **WHEN** TalkBack is on and Ezra opens a chat with earlier answers
- **THEN** nothing is announced until a new answer arrives
