# chat-view Specification (delta)

## ADDED Requirements

### Requirement: Text keys show above the message box
The chat SHALL show the person's text keys (`key-bar` spec) as one row
directly above the message box, in the order they were made, whenever the
message box shows, at least one text key exists, and "Show text keys in the
chat" is on. The switch SHALL be on the Keys screen and on for a fresh
install. The row SHALL be a single line that scrolls sideways when the keys
do not fit; it SHALL NOT wrap. A tap on a key SHALL add its text at the end
of the draft, with one space before it when the draft is not empty and does
not end in a space or a line break. A tap SHALL NOT send anything, SHALL NOT
press Enter whatever the key's own Enter choice is, and SHALL NOT open or
close the keyboard. The draft that results SHALL be sent, guarded and
followed exactly as a typed one. Each key SHALL be at least 48 dp high as a
touch target and SHALL have a spoken name that says its label and that it
puts its text in the message.

#### Scenario: A command into an empty box
- **GIVEN** a text key labelled `clear` with the text `/clear`, and an empty message box
- **WHEN** the person taps `clear` in the row
- **THEN** the message box holds `/clear`, the send button is ready, and nothing was sent

#### Scenario: Sending it
- **GIVEN** the message box holds `/clear` from a text key
- **WHEN** the person taps send
- **THEN** the message goes as a typed `/clear` would, with the same bubble and the same waiting line

#### Scenario: Added after typed words
- **GIVEN** the draft is `Looks good,` and a text key holds `commit this`
- **WHEN** the person taps that key
- **THEN** the draft is `Looks good, commit this`

#### Scenario: After a space
- **GIVEN** the draft is `Please ` with a space at its end
- **WHEN** the person taps a key holding `run the tests`
- **THEN** the draft is `Please run the tests`, with one space between the words

#### Scenario: A key that presses Enter in the terminal
- **GIVEN** a text key with "Press Enter after" on
- **WHEN** the person taps it in the chat's row
- **THEN** its text stands in the message box and nothing is sent

#### Scenario: No text keys
- **GIVEN** the person has made no text key
- **WHEN** a chat opens
- **THEN** no row shows and the chat looks as it did before this change

#### Scenario: Switched off
- **GIVEN** three text keys exist and "Show text keys in the chat" is off
- **WHEN** a chat opens
- **THEN** no row shows

#### Scenario: An agent that has exited
- **GIVEN** the agent has exited and the chat shows no message box
- **WHEN** the person reads that chat
- **THEN** no row shows

#### Scenario: While a send is refused
- **GIVEN** the chat says "Answer the dialog in the terminal first"
- **WHEN** the person taps a text key in the row
- **THEN** its text is added to the draft, and a send is still refused as before

#### Scenario: More keys than fit
- **GIVEN** twelve text keys
- **WHEN** a chat opens on a phone 412 dp wide
- **THEN** the row shows the first keys on one line and the rest are reached by scrolling it sideways

#### Scenario: A key changed on the Keys screen
- **GIVEN** a chat was open with a key labelled `clear`
- **WHEN** the person renames it to `clr` on the Keys screen and returns to the chat
- **THEN** the row shows `clr`

#### Scenario: Another terminal's draft
- **GIVEN** two terminals each with an open chat
- **WHEN** the person taps a text key in the first chat
- **THEN** only the first terminal's draft changes

#### Scenario: With a screen reader
- **GIVEN** a text key labelled `yes` with the text `yes, go ahead`
- **WHEN** a screen reader lands on it in the chat's row
- **THEN** it reads "yes. Puts yes, go ahead in the message" once

### Requirement: Updates reuse validated text keys without changing settings
This change SHALL reuse the validated key-bar text records; it SHALL NOT
migrate, reset or rewrite the saved theme, appearance, arrangement or texts.
The chat-row switch SHALL default to on when absent, unknown or wrongly typed,
and SHALL preserve an existing valid boolean across updates. Only texts that
pass key-bar validation (no C0/C1 controls, U+2028 or U+2029) SHALL reach the
draft; the separate Enter flag SHALL be ignored. Taps SHALL never invoke the
terminal text-key delivery call.

#### Scenario: Update with no saved text keys
- **GIVEN** an existing installation with its theme and key bar and no text keys
- **WHEN** it updates
- **THEN** those settings remain unchanged, the new switch defaults to on, and no shortcut row shows

#### Scenario: Update with existing text keys and switch off
- **GIVEN** a saved theme pair, edited key bar, valid text keys and the chat-row switch off
- **WHEN** it updates
- **THEN** all those settings remain unchanged and no row shows

#### Scenario: Invalid stored text is not inserted
- **GIVEN** storage contains a text key with a C0/C1 control, U+2028 or U+2029
- **WHEN** the chat opens
- **THEN** that key is excluded by key-bar validation and its text cannot be inserted or sent

#### Scenario: Invalid switch value
- **GIVEN** the chat-row switch is absent, unknown or not a boolean and valid text keys exist
- **WHEN** a chat with a message box opens
- **THEN** the switch reads as on and the row shows
