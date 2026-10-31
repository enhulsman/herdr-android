# terminal-input Specification (delta)

## ADDED Requirements

### Requirement: The composer sends through terminal.send
The terminal screen SHALL have a composer: a standard Android text field (IME,
autocorrect, dictation, paste work as usual). Send SHALL call `terminal.send`
with the text, so it is typed only once the terminal is quiet and never mixes
with typing at the desk.

#### Scenario: Answering a question
- **WHEN** an agent asks a question and Ezra types an answer and taps Send
- **THEN** the answer arrives in the agent followed by Enter, and the terminal's size is unchanged

### Requirement: The composer follows the agent's state
While the agent is working, Send SHALL become "Send when idle" (`if: idle`).
While it is blocked, Send SHALL be disabled and a "Type anyway" action SHALL
send with `force: true`.

#### Scenario: Blocked agent
- **WHEN** the agent is blocked on a permission prompt
- **THEN** Send is disabled and "Type anyway" is offered

### Requirement: A keys row sends terminal keys
Above the keyboard the app SHALL show a keys row: esc, tab, ctrl (sticky),
arrows, enter, ctrl+c, plus a swipe-up shelf for y, n, 1, 2, 3, /, |, ~. Keys
SHALL be sent with `terminal.keys`. A long press on esc SHALL call
`terminal.interrupt` and show what it reports.

#### Scenario: Choosing a menu option
- **WHEN** Claude Code shows a numbered menu and Ezra taps down, down, enter
- **THEN** the third option is chosen

### Requirement: Direct typing is a deliberate switch
A keyboard toggle SHALL switch to direct typing, where keystrokes go straight to
the terminal stream (for vim and other full-screen programs). The first time per
terminal, the app SHALL ask: "Typing here resizes this terminal on your other
screens." with "Keep their size" and "Use phone size". Leaving direct typing
SHALL return to an observe attach.

#### Scenario: Keep their size
- **WHEN** Ezra picks "Keep their size" and types
- **THEN** the terminal keeps its columns and rows and the Mac does not reflow

#### Scenario: Use phone size
- **WHEN** Ezra picks "Use phone size"
- **THEN** the terminal reflows to the phone's columns, and the phone shows "Sized for this phone" until direct typing ends

### Requirement: Nothing destructive in version 1
The app SHALL NOT offer kill, close, remove, or new-agent actions in version 1.

#### Scenario: Long press on an agent
- **WHEN** Ezra long-presses an agent row
- **THEN** no kill or close action is offered
