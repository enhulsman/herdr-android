# agent-list Specification (delta)

## ADDED Requirements

### Requirement: One list of agents across all machines
The home screen SHALL list every terminal that reports agent status (OSC 7501
`reported` true) across all connected machines, each row showing the agent's
name, its machine, its state, and the age of that state. Terminals that never
reported a status SHALL appear in a collapsed "Other terminals" group.

#### Scenario: Agents on two machines
- **WHEN** WSL runs two Claude Code agents and the Mac runs one
- **THEN** the home screen shows three rows, each labelled with its machine

### Requirement: Agents needing attention come first
The list SHALL be ordered blocked, then error, then done-and-unseen, then
working, then idle; within a group, the most recent state change first. The
states SHALL follow the desktop's model (`model.rs`), including "seen": a done
agent the phone has opened becomes idle on the phone.

#### Scenario: A new permission prompt
- **WHEN** an agent that was working becomes blocked
- **THEN** its row moves to the top within one second of the status event

#### Scenario: Seen on the phone
- **WHEN** Ezra opens a done agent and returns to the list
- **THEN** that agent is shown as idle on the phone

### Requirement: Blocked rows say what is asked when the program said it
For a blocked agent, the row SHALL show the request kind (permission, question,
auth) and the program's `msg` when the 7501 record carries them. Without them,
the row SHALL show "Needs you" and SHALL NOT guess a kind.

#### Scenario: Permission with message
- **WHEN** the record has `kind: permission` and `msg: "Run npm test?"`
- **THEN** the row shows "Permission: Run npm test?"

#### Scenario: No kind
- **WHEN** a blocked record has no `kind`
- **THEN** the row shows "Needs you"

### Requirement: Working agents show progress when given
A working agent SHALL show a spinner, and a progress bar when the record carries
`progress` (0-100).

#### Scenario: Progress reported
- **WHEN** the record has `progress: 40`
- **THEN** the row shows a 40% bar

### Requirement: Jump to the next agent that needs you
From an open terminal, a vertical swipe on the title bar SHALL open the next
(or previous) agent that is blocked or done-and-unseen, in list order, with a
light haptic per step and a stronger one when none is left.

#### Scenario: Clearing a backlog
- **WHEN** three agents are blocked and Ezra swipes up on the title bar twice
- **THEN** the second and third blocked agents open in turn, and a third swipe gives the end-of-queue haptic
