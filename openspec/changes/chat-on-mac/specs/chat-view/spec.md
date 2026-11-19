## MODIFIED Requirements

### Requirement: The switch shows only while the chat can follow
The terminal screen's title bar SHALL show a switch labelled Terminal and
Chat while the terminal's lookup result is Found or Empty (see the lookup
requirement) and the log's health is Ok or Degraded. It SHALL NOT show
for a result of Missed or None, for a log whose health is Broken when the
terminal is opened, or on a machine that is neither Linux nor macOS. A log
that turns Broken while the chat is open SHALL keep the switch (see the
health guard). The terminal SHALL stay the first view: a terminal opened for
the first time shows the terminal.

#### Scenario: An agent with a readable log
- **WHEN** Ezra opens a terminal running Claude Code whose session file and log are found
- **THEN** the title bar shows the switch on Terminal, and a tap on Chat shows the conversation

#### Scenario: A plain shell
- **WHEN** Ezra opens a terminal with only a shell in front
- **THEN** no switch is shown

#### Scenario: A Broken log on opening
- **WHEN** Ezra opens a terminal whose agent's log the health guard finds Broken
- **THEN** the terminal shows and there is no switch

#### Scenario: An agent on a Mac
- **WHEN** Ezra opens a terminal on a Mac desk running Claude Code whose session file and log are found
- **THEN** the title bar shows the switch, as for a Linux desk

### Requirement: A terminal is matched to its agent's log without desk setup
The app SHALL find the log of the agent in front of a terminal from the
terminal's foreground process, with one `machine.exec` script and nothing
installed on the desk. The script SHALL work on Linux and macOS machines,
finding the same facts on both. For Claude Code it SHALL use the per-process
session file and SHALL refuse it when its recorded process start differs
from the process's own. On macOS it SHALL compare the recorded start with
the process's start as the system reports it, to within 2 seconds, and
SHALL refuse the file when they differ. For Codex it
SHALL use the rollout the process holds open; a held thread lock newer than
every open rollout SHALL mean a new thread with no record yet; several open
rollouts written in the same second SHALL be ambiguous. An agent started
through `node` SHALL be found by its script in the process's arguments.

The lookup SHALL give one of four results:
- **Found**: the agent's log exists and is read.
- **Empty**: the agent's session is known but its log has no record yet
  (a Claude Code session file naming an id whose log is absent; a Codex
  lock newer than every open rollout). The chat reads "No messages yet" and
  its message box follows the send rules.
- **Missed**: an agent is in front but no session is known, the match is
  ambiguous, or the session file's id cannot be read.
- **None**: no agent in front.

Empty SHALL turn Missed when the agent's first turn ends (working, then
done or idle) and its log still does not exist, so a misread id shows the
terminal rather than an empty chat for good. Only a turn that began on that
same session counts, and not one a command sent from the chat began: the
turn of a `/clear` belongs to the old session, and the new log is written
about a second after it ends.

#### Scenario: Between /clear and the new log
- **WHEN** Ezra sends `/clear` from the chat and the new session's log is not written yet
- **THEN** the chat shows the new, empty conversation, never "No conversation found"

#### Scenario: Two agents in one folder
- **WHEN** two Claude Code agents run in the same folder in two terminals
- **THEN** each terminal's chat shows its own conversation

#### Scenario: A reused process id
- **WHEN** a session file names a process id whose start time differs from the file's
- **THEN** that file is not used and the terminal has no switch

#### Scenario: A new Claude Code session watched in the terminal
- **WHEN** Ezra starts Claude Code in a terminal he is watching, and then sends its first message there
- **THEN** the switch shows at once (Empty), and the chat shows the conversation once the first record is written

#### Scenario: Codex after /new
- **WHEN** Codex starts a new thread in the terminal and no message is sent yet
- **THEN** the chat reads "No messages yet", not the previous thread

#### Scenario: A session id the script cannot read
- **WHEN** the session file's id cannot be read
- **THEN** the result is Missed: no switch, the terminal shows

#### Scenario: Ambiguous Codex rollouts
- **WHEN** two open rollouts of the process were last written in the same second
- **THEN** the result is Missed and nothing can be sent from a chat for it

#### Scenario: Codex on a Mac
- **WHEN** Codex runs on a Mac and holds its rollout open
- **THEN** the lookup finds that rollout through the process's open files, as on Linux

#### Scenario: A stale session file on a Mac
- **WHEN** on a Mac a Claude Code session file records a start more than 2 seconds from the start of the process it names
- **THEN** that file is not used and the result is Missed

#### Scenario: Two agents in one folder on a Mac
- **WHEN** two Claude Code agents run in the same folder on a Mac
- **THEN** each terminal's chat shows its own conversation
