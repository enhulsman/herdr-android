## ADDED Requirements

### Requirement: Control characters are not typed as keys
Before a message is sent, the app SHALL remove control characters other than
newline and tab, and SHALL turn a Windows line ending into a plain line
break. Everything else SHALL be sent as typed, `/` and `!` commands
included.

#### Scenario: A pasted Ctrl+C
- **WHEN** Ezra pastes text containing a Ctrl+C character into the message box and sends it
- **THEN** the agent receives the text without it, and nothing is interrupted

#### Scenario: Windows line endings
- **WHEN** Ezra sends text copied with Windows line endings
- **THEN** the agent receives plain line breaks, one per line
