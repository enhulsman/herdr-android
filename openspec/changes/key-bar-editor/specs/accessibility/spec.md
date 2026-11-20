# accessibility Specification (delta)

## MODIFIED Requirements

### Requirement: The terminal screen fits the largest font size
At every system font size up to 2x, the terminal screen's title bar SHALL
show both its lines (the agent's name and its place) without clipping, and
each key of the keys row SHALL show its whole label; a label MAY shrink
to fit its key. This SHALL hold for every key the person can place in the
bar, a text key's label of six characters included, in a row filled to its
nine places.

#### Scenario: Title bar at 2x
- **WHEN** the system font size is 2x and the person opens a terminal
- **THEN** the title bar shows the name and the place line in full height, and the terminal starts below it

#### Scenario: Keys row at 2x
- **WHEN** the system font size is 2x and the keys row shows
- **THEN** every key shows its whole label ("ctrl", not "ctr")

#### Scenario: A text key at 2x
- **GIVEN** a row of seven one-place keys and a text key labelled `commit`
- **WHEN** the system font size is 2x and the keys row shows
- **THEN** the text key shows `commit` whole

### Requirement: Every key has a spoken name
Each key of the keys row SHALL have a name a screen reader reads. The
symbol keys SHALL be read as "dash", "slash", "tilde", "backslash" and
"pipe". Home, end, page up, page down and delete SHALL be read by those
names, a function key as "F" and its number, and a ctrl + letter key as
"Control" and the letter. A text key SHALL be read as its label, then what
it types, then "then Enter" when it presses Enter. The arrows key SHALL be
read as "Arrows" and SHALL offer Up, Down, Left and Right as actions a
screen reader can run without dragging. A key with a spoken name SHALL NOT
also have its label read.

#### Scenario: A symbol key with TalkBack
- **WHEN** TalkBack lands on the "~" key
- **THEN** it reads "Tilde", not "Tilde, tilde"

#### Scenario: A text key with TalkBack
- **GIVEN** a text key labelled `clear` that types `/clear` and presses Enter
- **WHEN** TalkBack lands on it
- **THEN** it reads "clear. Types /clear, then Enter" once

#### Scenario: A ctrl + letter key with TalkBack
- **WHEN** TalkBack lands on the `^R` key
- **THEN** it reads "Control R"
