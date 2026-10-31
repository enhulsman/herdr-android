# terminal-input Specification (delta)

## ADDED Requirements

### Requirement: Typing goes straight into the terminal at phone size
The app SHALL send keyboard input to the terminal through an active (non-observe)
attach, encoded by libghostty's key encoder for the program's keyboard mode. On
the first input the app SHALL reattach as an active viewer at the phone's size,
without asking. The desk SHALL take its size back through herdr-core's normal
rule on its own next input. The phone SHALL send `display` with null fields.

#### Scenario: Typing on the phone
- **WHEN** Ezra taps the terminal and types into a 180-column terminal
- **THEN** the terminal reflows to the phone's columns and the typed text appears in the program

#### Scenario: Back at the desk
- **WHEN** Ezra then types at the Mac app
- **THEN** the terminal returns to the desk's size

#### Scenario: Desk colours kept
- **WHEN** the phone becomes the size leader
- **THEN** the terminal's reported colours stay the desk's (null display fields leave them in place)

### Requirement: The keyboard behaves like a terminal keyboard
In the terminal the app SHALL request a keyboard without suggestions or
autocorrect, and SHALL map Android key events, including backspace and enter, to
terminal keys.

#### Scenario: Backspace
- **WHEN** Ezra types "lsx" then backspace then enter at a shell prompt
- **THEN** the shell runs `ls`

### Requirement: A keys row for keys the phone keyboard lacks
Above the keyboard the app SHALL show a keys row: esc, tab, ctrl and alt (sticky),
arrows, enter, ctrl+c, and a swipe-up shelf with symbols such as `|`, `~`, `/`,
`-`. Every key SHALL go through the same encoder as typing.

#### Scenario: Choosing a menu option
- **WHEN** Claude Code shows a numbered menu and Ezra taps down, down, enter
- **THEN** the third option is chosen

#### Scenario: Sticky ctrl
- **WHEN** Ezra taps ctrl then c
- **THEN** the program receives ctrl+c

### Requirement: A compose key for long text
The keys row SHALL have a compose key that opens a text field with the phone's
full keyboard (autocorrect, swipe typing, dictation). Its text SHALL be sent to
the terminal as a bracketed paste without Enter.

#### Scenario: Dictating a prompt
- **WHEN** Ezra dictates a sentence into the compose field and confirms
- **THEN** the sentence appears in Claude Code's input box, not yet submitted

### Requirement: Interrupt from the keys row
A long press on esc SHALL call `terminal.interrupt` and show what it reports.

#### Scenario: Stopping an agent
- **WHEN** Ezra long-presses esc on a working agent
- **THEN** the app shows the interrupt's report, or "outcome unknown" on timeout

### Requirement: Leaving the terminal stops leading
When Ezra leaves the terminal screen the app SHALL detach its active viewer.

#### Scenario: Back to the list
- **WHEN** Ezra types on the phone and then returns to the agent list
- **THEN** the phone no longer holds an active viewer on that terminal

### Requirement: Nothing destructive
The app SHALL NOT offer kill, close, remove or new-agent actions.

#### Scenario: Long press on an agent
- **WHEN** Ezra long-presses an agent row
- **THEN** no kill or close action is offered
