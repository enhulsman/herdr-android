# agent-list Specification (delta)

## ADDED Requirements

### Requirement: One attention model, shared with the watcher
The app and `herdr-push` SHALL use one implementation of the desktop's attention
model (`herdr-proto::attention`, ported from herdr-app `model.rs`), tested
against this table:

| From | To | Result |
|---|---|---|
| any | `blocked` or `error` | needs you (error shown red) |
| `working`, `blocked` or `error` | `idle` or `done` | finished, unseen |
| finished, unseen | opened on the phone | idle on the phone |
| any | `working` | working |
| record has `parent` | any | not a separate row, no alert |

#### Scenario: Error counts as needing you
- **WHEN** an agent's status becomes `error`
- **THEN** it is treated as needing you, shown red, at the top

#### Scenario: Working to idle is finished
- **WHEN** an agent goes from `working` straight to `idle`
- **THEN** it is shown as finished and unseen

#### Scenario: Sub-agents
- **WHEN** a terminal whose record has a `parent` finishes
- **THEN** it does not appear as its own row and causes no alert

### Requirement: One list of agents across machines
The home screen SHALL list every terminal that reports agent status across
connected machines, showing name, machine, state and the state's age. The name
SHALL be the terminal's `name`, else the program's display name, else its id.
Terminals with no status now SHALL appear in a collapsed "Other terminals"
group, from which they can be opened: those that never reported status, and
those whose agent quit back to the shell (the core clears `status` then but
keeps `reported`).

#### Scenario: Two agents on WSL
- **WHEN** WSL runs two Claude Code agents
- **THEN** the home screen shows two rows labelled with WSL

#### Scenario: A build tab
- **WHEN** a terminal running a build has never reported status
- **THEN** it is listed under "Other terminals" and opens like any terminal

#### Scenario: An agent quits back to its shell
- **WHEN** the agent in a terminal quits and the terminal is a plain shell again
- **THEN** it moves from the agent list to "Other terminals", the title-bar
  swipe no longer stops at it, and a finish it had not shown counts as seen

### Requirement: Agents needing you come first
The list SHALL be ordered: needs you, finished-unseen, working, idle; within a
group, most recent change first. A change SHALL reorder the list within one second
of the phone receiving the event.

#### Scenario: A new permission prompt
- **WHEN** a working agent becomes blocked
- **THEN** its row moves to the top within one second of the status event arriving

### Requirement: Blocked rows say what is asked when the program said it
A blocked row SHALL show the request kind (permission, question, auth) and the
program's `msg` when the record carries them, and "Needs you" otherwise.

#### Scenario: Permission with message
- **WHEN** the record has `kind: permission` and `msg: "Run npm test?"`
- **THEN** the row shows "Permission: Run npm test?"

#### Scenario: No kind
- **WHEN** a blocked record has no `kind`
- **THEN** the row shows "Needs you"

### Requirement: Working agents show progress when given
A working row SHALL show a spinner, and a progress bar when the record carries
`progress` (0-100).

#### Scenario: Progress reported
- **WHEN** the record has `progress: 40`
- **THEN** the row shows a 40% bar

### Requirement: Jump to the next agent that needs you
From an open terminal, a swipe up on the title bar SHALL open the next agent
that needs you or is finished-unseen, in list order; a swipe down SHALL go back
through the agents that walk opened (also once they are seen or answered), then
to earlier agents that need you; a swipe up after going back SHALL retrace them.
A sideways swipe on the title bar SHALL open the next (finger to the left) or
previous agent in list order, whatever its state, like tabs.
A light haptic per step, a stronger one when none is left.

#### Scenario: Clearing a backlog
- **WHEN** three agents need Ezra and he swipes up on the title bar twice
- **THEN** the second and third open in turn, and a third swipe gives the end-of-queue haptic

#### Scenario: Tabs
- **WHEN** Ezra swipes left on the title bar of an idle agent's terminal
- **THEN** the next agent in the list opens, whatever its state

#### Scenario: Going back
- **WHEN** Ezra has swiped up through three finished agents and swipes down twice
- **THEN** the second and then the first open again, though opening them marked them seen
