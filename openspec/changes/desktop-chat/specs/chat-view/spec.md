## ADDED Requirements

### Requirement: Control characters are not typed as keys
Before creating pending state or classifying a message, the app SHALL remove control characters other than
newline and tab, and SHALL turn a Windows line ending into a plain line
break. Everything else SHALL be sent as typed, `/` and `!` commands
included. The resulting canonical text SHALL be used for pending bubbles,
slash/bang classification, log and queue confirmation, input-box matching
and transport formatting. Image-path prefixes and transport quoting SHALL
be added afterwards without changing pending's text. The original draft and
attachments SHALL be retained separately for definitive refusal restoration.

**Owner pick R1-B (proposed):** if canonical text is blank and there are no
attachments, the app SHALL leave the original draft in the box and create
no pending bubble or terminal send; alternative: show a new refusal.
Attachment-only messages SHALL retain their current behaviour.

#### Scenario: A pasted Ctrl+C
- **WHEN** Ezra pastes text containing a Ctrl+C character into the message box and sends it
- **THEN** the agent receives the text without it, and nothing is interrupted

#### Scenario: Windows line endings
- **WHEN** Ezra sends text copied with Windows line endings
- **THEN** the agent receives plain line breaks, one per line

#### Scenario: A normalized message confirms
- **GIVEN** a draft containing CRLF and embedded control characters
- **WHEN** its canonical text arrives in the log or the agent's queue
- **THEN** the pending message confirms or shows Queued using that canonical text

#### Scenario: A command after stripping
- **WHEN** a control character precedes `/usage` or a `!` command in the draft
- **THEN** stripping happens before classification, so `/usage` keeps its dialog wait and the `!` command keeps its shell confirmation rules

#### Scenario: Input-box matching after normalization
- **WHEN** the input box still holds the normalized message after the Enter-again delay
- **THEN** matching uses canonical text and the existing per-message guarded Enter rule

#### Scenario: Normalized-empty input
- **WHEN** the draft becomes blank after stripping and has no attachments
- **THEN** the original draft stays, no pending bubble is created and nothing is dispatched

#### Scenario: Attachments without canonical text
- **WHEN** text becomes blank but images are attached
- **THEN** the existing attachment-only send and confirmation rules apply

#### Scenario: Original draft on refusal
- **WHEN** a normalized message is definitively refused while newer text is in the box
- **THEN** the original draft and attachments return with the newer draft, rather than replacing it with the canonical text
