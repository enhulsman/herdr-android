# agent-names Specification

## Purpose
TBD - created by archiving change session-names. Update Purpose after archive.
## Requirements
### Requirement: An agent is named after its session
An agent SHALL be named by the terminal's `name`, else its `title`, else the
program's display name, else its address, in the Agents list, on the terminal
screen and in alerts. The `title` SHALL be used only while the terminal
reports agent status. A terminal without agent status ("Other terminals") SHALL
be named by its `name`, else its program, else its address, never its `title`.
A `name` or `title` SHALL be cleaned before use: control characters removed,
whitespace runs collapsed, trimmed, and cut to 80 characters with "…"; one that
is empty after cleaning SHALL count as absent.

#### Scenario: A titled session
- **WHEN** a Claude Code terminal on WSL has no `name` and the title "Pricing page copy"
- **THEN** its row is named "Pricing page copy"

#### Scenario: A name given at the desk wins
- **WHEN** a terminal has the name "release" and the title "Pricing page copy"
- **THEN** its row is named "release"

#### Scenario: No title
- **WHEN** a Codex terminal has neither a name nor a title
- **THEN** its row is named "Codex"

#### Scenario: The agent quit
- **WHEN** Claude Code quits back to its shell and the terminal keeps the title "Pricing page copy"
- **THEN** the terminal moves to "Other terminals" named by its shell, not by the title

#### Scenario: A title that is only whitespace
- **WHEN** an agent's title is "  \t "
- **THEN** its row is named by its program

#### Scenario: A long title
- **WHEN** an agent's title is 120 characters long
- **THEN** its name is the first 79 characters followed by "…"

### Requirement: The list shows where an agent sits
Each row's second line, and the terminal screen's line under its name, SHALL be
the terminal's `where` label and the machine ("website / 2 · WSL") when that
label is non-empty after cleaning and the `herdr.space` label is non-empty,
else the folder and the machine ("website · WSL"), for agents and other
terminals alike.

#### Scenario: A placed terminal
- **WHEN** a terminal on WSL carries `where` = "website / 2" and works in `/home/me/src/website`
- **THEN** its second line reads "website / 2 · WSL"

#### Scenario: Not placed yet
- **WHEN** a terminal started from the phone has no `where` label and works in `/home/me/src/api`
- **THEN** its second line reads "api · WSL"

#### Scenario: Parked
- **WHEN** a terminal still carries `where` = "website / 2" but its `herdr.space` label is empty
- **THEN** its second line reads "<folder> · WSL"

#### Scenario: The terminal screen
- **WHEN** the person opens the agent "Pricing page copy" with `where` = "website / 2" on WSL
- **THEN** the terminal screen's header shows "Pricing page copy" over "website / 2 · WSL"

### Requirement: Alerts name the session and hide it on a private lock screen
Agent alerts SHALL use the agent's name: "<name> needs you" with the body
"<kind> · <machine>", and "<name> finished · <machine>". The watcher SHALL send
the name by the rule above; the payload's fields SHALL stay as the alerts
requirement lists them, and `name` MAY be a session title. Every agent
notification and the test alert SHALL be private with a fixed public version
that names only the machine ("An agent needs you · WSL", "An agent finished ·
WSL", "Test alert from WSL"), so the phone's own setting for sensitive
notifications decides what the lock screen shows.

#### Scenario: A session finishes
- **WHEN** the agent "Pricing page copy" on WSL finishes while the app is closed
- **THEN** the notification reads "Pricing page copy finished · WSL"

#### Scenario: Locked, sensitive content hidden
- **WHEN** the agent "Pricing page copy" on WSL needs you and the phone is locked with sensitive notifications hidden
- **THEN** the lock screen shows "An agent needs you · WSL", and the full text after unlocking

#### Scenario: Locked, sensitive content shown
- **WHEN** the same alert arrives and the phone shows sensitive notifications on the lock screen
- **THEN** the lock screen shows "Pricing page copy needs you"

#### Scenario: An older watcher
- **WHEN** an older herdr-push sends the name "Claude Code"
- **THEN** the notification reads "Claude Code needs you"

