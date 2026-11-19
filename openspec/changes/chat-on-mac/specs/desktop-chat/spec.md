## MODIFIED Requirements

### Requirement: Machines the chat cannot read or reach are named
The desk chat SHALL read agents on Linux and macOS machines. While a machine is still
connecting, the pane SHALL wait without a message. For a machine herdr
cannot reach, it SHALL read "Can't reach <machine>; retrying" and try again
once the machine is back. For an agent on Windows itself it SHALL
read "kudde's chat can read agents on Linux and macOS. This agent runs on Windows
itself; agents in WSL work." For another unsupported system it SHALL read
"kudde's chat can read agents on Linux and macOS. This machine is not
supported." Each SHALL offer "Open terminal". An
unreachable machine SHALL never be named as unsupported.

#### Scenario: An agent on Windows itself
- **WHEN** Ezra opens the chat for Claude Code running natively on Windows
- **THEN** the pane shows the Windows message and the "Open terminal" button

#### Scenario: An agent on a Mac
- **WHEN** Ezra opens the chat for Claude Code running on a Mac
- **THEN** the pane reads the conversation and permits replies under the same checks as for Linux

#### Scenario: A machine that is offline
- **WHEN** Ezra opens the chat for an agent on a Linux machine that herdr cannot reach
- **THEN** the pane reads "Can't reach <machine>; retrying", and shows the conversation once the machine is back


#### Scenario: Another unsupported system
- **WHEN** the ready agent machine is neither Linux, macOS nor Windows, or its shell cannot start and Windows is not established
- **THEN** the pane shows the generic unsupported-machine notice without claiming the agent runs on Windows
