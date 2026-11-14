## ADDED Requirements

### Requirement: The switch shows only while the chat can follow
The terminal screen's title bar SHALL show a switch labelled Terminal and
Chat while the terminal's agent log is found and its health is Ok or
Degraded. It SHALL NOT show for a terminal with no agent in front, an
agent whose log is not found, an ambiguous match, a log whose health is
Broken, or a machine that is not Linux. The terminal SHALL stay the first
view: a terminal opened for the first time shows the terminal.

#### Scenario: An agent with a readable log
- **WHEN** Ezra opens a terminal running Claude Code whose session file and log are found
- **THEN** the title bar shows the switch on Terminal, and a tap on Chat shows the conversation

#### Scenario: A plain shell
- **WHEN** Ezra opens a terminal with only a shell in front
- **THEN** no switch is shown

#### Scenario: The log turns Broken while the chat is open
- **WHEN** the chat is open and the health guard finds the log Broken
- **THEN** the terminal shows, the switch is gone, and a one-line note says "This conversation can't be read; showing the terminal"

### Requirement: The chosen view stays when switching agents
The app SHALL remember the last chosen view (Terminal or Chat) across
agents and app restarts. Opening an agent while Chat is chosen SHALL show
its chat when it has one, else its terminal. A chat opened by hand on a
terminal whose log is not ready yet SHALL say why ("Looking for the
conversation…", "No conversation found for this agent") instead of
switching back unasked.

#### Scenario: Chat chosen, next agent has a log
- **WHEN** Ezra chose Chat on one agent and swipes to another agent with a readable log
- **THEN** the other agent opens on its chat

#### Scenario: Chat chosen, next terminal has none
- **WHEN** Ezra chose Chat and opens a terminal with a plain shell
- **THEN** the terminal shows, and returning to an agent with a log shows its chat again

### Requirement: A terminal is matched to its agent's log without desk setup
The app SHALL find the log of the agent in front of a terminal from the
terminal's foreground process, with one `machine.exec` script and nothing
installed on the desk. For Claude Code it SHALL use the per-process session
file and SHALL refuse it when its recorded process start differs from the
process's own. For Codex it SHALL use the rollout the process holds open; a
held thread lock newer than every open rollout SHALL mean a new, empty
thread; several open rollouts written in the same second SHALL give no
match. An agent started through `node` SHALL be found by its script in the
process's arguments.

#### Scenario: Two agents in one folder
- **WHEN** two Claude Code agents run in the same folder in two terminals
- **THEN** each terminal's chat shows its own conversation

#### Scenario: A reused process id
- **WHEN** a session file names a process id whose start time differs from the file's
- **THEN** that file is not used and the terminal has no chat

#### Scenario: Codex after /new
- **WHEN** Codex starts a new thread in the terminal and no message is sent yet
- **THEN** the chat shows an empty conversation, not the previous thread

#### Scenario: Ambiguous Codex rollouts
- **WHEN** two open rollouts of the process were last written in the same second
- **THEN** the terminal has no chat and nothing can be sent from it

### Requirement: /clear and new sessions are followed
The app SHALL look the log up again when the process in front changes, every
10 s, and on every poll for 3 s after a `/` command was sent, so that a new
session in the same process (Claude Code `/clear`, a fork, Codex `/new`)
replaces the chat with its own conversation.

#### Scenario: /clear from the chat
- **WHEN** Ezra sends `/clear` from the chat
- **THEN** within 5 s the chat shows the new, empty conversation with the `/clear` bubble

### Requirement: After the agent exits, the chat stays read-only
When the agent in front exits, the chat SHALL keep what it has read, SHALL
hide the message box, and SHALL say "The agent has exited" at the bottom,
until the terminal starts another agent. A terminal opened after its agent
exited SHALL show the terminal with no switch.

#### Scenario: Exit while the chat is open
- **WHEN** the agent exits into its shell while Ezra reads its chat
- **THEN** the conversation stays, the message box is gone, and "The agent has exited" shows

#### Scenario: Opened after the exit
- **WHEN** Ezra opens a terminal whose agent exited before the app read its log
- **THEN** the terminal shows and there is no switch

### Requirement: Reading is bounded and starts from the end
The app SHALL read logs with `machine.exec` and the tail script, never
`file.read`. The first open SHALL read the last 2 MB from a line start; later
reads SHALL read only new whole lines; each read SHALL span at most 7.5 MB.
The script SHALL drop records the chat never shows and replace base64 image
data over 300 characters with a reference. Scrolling to the top SHALL read
the 2 MB before the oldest line held (doubled while one line is longer),
back to the start of the log, and every line SHALL be read exactly once. A
record over 7.5 MB SHALL be skipped with a note, and reading SHALL go on
after it. A read that fails SHALL keep the chat and say so over it
("Can't read new messages; retrying") until a read succeeds.

#### Scenario: An 84 MB log
- **WHEN** Ezra opens the chat of an agent whose log is 84 MB
- **THEN** the recent messages show within 2 s on Wi-Fi, and scrolling up loads older ones in steps

#### Scenario: A record too large to read
- **WHEN** the agent writes one record of 9 MB
- **THEN** the chat shows a note "A message too large to show was skipped" and later messages still arrive

#### Scenario: A failed read
- **WHEN** a read times out
- **THEN** the chat stays on screen with "Can't read new messages; retrying", and the note goes once a read succeeds

### Requirement: Live updates follow the agent
The app SHALL read new lines right after each `status`, `turn` or `title`
event of the terminal, every 0.5 s while the agent works or a sent message
waits, and every 5 s otherwise, while the chat is on screen. While the
terminal view shows, the app SHALL only look the log up and read its recent
window to decide the switch, when the terminal opens and when its foreground
process changes. In the background it SHALL make no reads.

#### Scenario: A short answer
- **WHEN** the agent finishes a short answer while the chat is open on Wi-Fi
- **THEN** the answer shows within 1 s of the agent's done event

#### Scenario: Terminal view on screen
- **WHEN** the terminal view shows and the agent works for a minute
- **THEN** the log is looked up and read once on opening, and not again until the foreground process changes

### Requirement: A health guard falls back to the terminal
The parser SHALL rate each read Ok, Degraded or Broken, on every read, not
only before the first message: a message record without role and content,
fewer than half the lines in a known shape, or 30 lines or more with no
message in a log that has none yet SHALL be Broken; a line that is not
JSON or an unknown content block or item inside a known record SHALL be
Degraded. Bookkeeping lines in the recent window SHALL NOT count as Broken
before older messages have been read back. Degraded SHALL show "Some parts
of this conversation aren't shown" at the top of the chat. Broken SHALL
show the terminal (see the switch requirement). Unknown record types SHALL
be ignored.

#### Scenario: A newer agent version adds a block type
- **WHEN** a log holds an assistant record with an unknown content block
- **THEN** the chat shows the rest of the message and the note "Some parts of this conversation aren't shown"

#### Scenario: The other agent's format
- **WHEN** the matched log turns out to be in a format the parser does not know
- **THEN** the switch is gone and the terminal shows

#### Scenario: A recent window of bookkeeping only
- **WHEN** the last 2 MB of a log hold 40 bookkeeping lines and no message
- **THEN** the app reads further back before judging, and shows the older messages

### Requirement: The chat shows the conversation as the agents' apps do
The chat SHALL show the person's messages as bubbles, the agent's text as
Markdown (headings, lists, tables, links, inline code, and code blocks that
wrap and have a copy button), a run of tool calls as one line naming what
ran ("Ran 7 commands, read 2 files, started 1 agent"), notes for slash
command results, compaction and interruptions, and another agent's or a
background task's message as its own kind of row named after its task.
Thinking, meta records, sidechains and the compaction summary SHALL be left
out. Text the person sent SHALL be shown without herdr-core's `<hc>` wrapper
or Claude Code's `<pasted_content>` wrapper. A message typed while Claude
Code worked (a queued command) SHALL show once as the person's message.
A long press on a message SHALL copy it; text SHALL be selectable, inline
code included.

#### Scenario: A queued message
- **WHEN** Ezra types a message in the terminal while Claude Code works
- **THEN** the chat shows it once as Ezra's bubble

#### Scenario: Wrapped inline code
- **WHEN** an answer's inline code wraps onto the next line
- **THEN** only the code's own characters are boxed on each line

#### Scenario: Selecting inside inline code
- **WHEN** Ezra selects text that includes inline code
- **THEN** the selection shows over the code as over the rest of the text

### Requirement: Rows keep their identity
Each row SHALL carry an identity made of the byte offset of the log line
that starts it and its index within that line, so that reading back, merging
a folded tool row with older calls, or dropping a duplicate queued message
never gives a row's place, thumbnail or open detail to another row.

#### Scenario: Reading back past a folded tool row
- **WHEN** the first row held is a folded tool row and reading back adds older calls to it
- **THEN** each thumbnail below still opens its own image

### Requirement: Codex commands stay in their turn
A Codex command written after its turn's end SHALL be shown in that turn,
above the turn's answer. A command the person ran with `!` SHALL be shown as
the person's own message with its output under it, not only inside a folded
tool row.

#### Scenario: A command logged after the answer
- **WHEN** a steered Codex turn's command is written after the turn's completion
- **THEN** the chat shows it above that turn's answer

### Requirement: The tool sheet shows each call
A tap on a folded tool line SHALL open a sheet over the dimmed chat with
three heights (about 46 and 62 percent of the screen, and full), dragged by
its handle or its list; a line with one call SHALL open that call's detail
at once. Each call SHALL show its kind's icon and verb (Read, Ran, Updated,
Searched, Ran agent) and the file name or command; its detail SHALL show the
tool's name, Completed, Failed or Running, and Description, Command (or
Input) and Output, the last two with a copy button. A detail open on a
running call SHALL follow it until it ends.

#### Scenario: A running command's detail
- **WHEN** Ezra opens the detail of a command that is still running
- **THEN** it reads Running, and its output and Completed appear when the command ends, without reopening

### Requirement: Sending goes only to the agent whose log is shown
The message box SHALL send with `terminal.send` through the API, only when
the terminal's foreground is the agent process whose log is shown and its
status is idle, done or working. The app SHALL check before uploading photos
and again right before typing, SHALL pass `terminal.send`'s `if` guard, and
SHALL refuse when the process or session differs from the one at the first
check. A refusal SHALL keep the draft and say why ("The agent is waiting for
you in the terminal", "The agent has exited", "This is a different session
now"), with a button to the terminal where it applies.

#### Scenario: Blocked on a permission prompt
- **WHEN** Ezra taps send while the agent is blocked
- **THEN** nothing is typed, the draft stays, and the chat says "The agent is waiting for you in the terminal" with a button to it

#### Scenario: The agent restarted during a photo upload
- **WHEN** the agent process changes while a photo uploads
- **THEN** nothing is typed and the chat says "This is a different session now"

#### Scenario: An exited agent
- **WHEN** the agent exited and the shell is in front
- **THEN** no text reaches the shell

### Requirement: A sent message shows at once
A sent message SHALL show at once as a dimmed bubble reading "Sending…"
until a person's row with the same text (or, for a `!` command, a shell
call with that command) arrives. After 30 s without it, the bubble SHALL
read "Not in the conversation yet" with a button to the terminal and a way
to dismiss it, and SHALL turn into the real row if it still arrives.

#### Scenario: A normal send
- **WHEN** Ezra sends "Run the tests"
- **THEN** a dimmed bubble shows at once, and turns into the normal bubble when the log has it

#### Scenario: Never arrives
- **WHEN** a sent message has no matching row after 30 s
- **THEN** the bubble reads "Not in the conversation yet" with a button to the terminal

### Requirement: A message while the agent works steers its turn
While the agent works, the message box SHALL say "Steers the current turn"
under its text field, and a message sent then SHALL be sent at once.

#### Scenario: Sending while the agent works
- **WHEN** Ezra sends a message while the agent's status is working
- **THEN** the message is typed into the agent at once and shows as Ezra's bubble in the running turn

### Requirement: Slash and shell commands are sent as typed
A message starting with `/` or `!` SHALL be sent as typed and shown as the
person's bubble. A `!` command's output SHALL show under it as a tool line;
a slash command's output as a note, Claude Code's local command output
included. When a slash command has no row after 3 s, the chat SHALL say
"Waiting for the terminal: /<command> may have opened a dialog there." with
a button to the terminal, kept in view while the chat follows the bottom.

#### Scenario: /usage
- **WHEN** Ezra sends `/usage` to Claude Code
- **THEN** the bubble shows, and after 3 s the waiting line with the terminal button shows under it

#### Scenario: A shell command
- **WHEN** Ezra sends `!git status`
- **THEN** the bubble shows `!git status` and the command's output shows under it as a tool line

### Requirement: Images show in the chat
The chat SHALL show images as thumbnails: those in the person's messages
and in tool results (a screenshot the agent read, a Codex image view),
fetched one at a time by reference and cached per session and image. A tap SHALL open a full-screen
viewer with pinch zoom, in which a swipe SHALL move to the other images of
the same message or tool row. An image that cannot be fetched (a Codex
file since deleted, an unknown shape) SHALL show as a missing-image
placeholder. A Codex image SHALL be cached by path and file time, so a
reused path never shows an older image.

#### Scenario: Three screenshots in one row
- **WHEN** Ezra opens the second of three thumbnails in one tool row and swipes left
- **THEN** the viewer shows the third image

#### Scenario: A deleted Codex image
- **WHEN** a Codex image view points to a file that is gone
- **THEN** a missing-image placeholder shows in its place

### Requirement: Photos can be attached from the phone
The message box SHALL offer the system photo picker. Picked photos SHALL be
turned upright from their EXIF orientation, scaled down when over 2000 px or
3.5 MB, re-encoded as JPEG when the agent cannot take their type, uploaded
with `terminal.put`, and sent as one path per line before the text. The
person's bubble SHALL show the photos, not the paths.

#### Scenario: A photo with a question
- **WHEN** Ezra picks a photo and sends "What is on this screen?"
- **THEN** the bubble shows the photo and the text, and the agent answers from the photo

### Requirement: A working line shows for the whole turn
The chat SHALL show a working line at the bottom while the agent's status
is working or a plain message is on its way: three sheep hopping a fence
and a herd verb that changes every 4 s, such as "Herding" or "Rounding up"
followed by an ellipsis. It SHALL NOT
show for a `/` or `!` command on its way or while the agent is blocked. A
running command's tool line SHALL also have a light sweeping over it while
the agent works; both SHALL show together.

#### Scenario: Thinking, then running a command
- **WHEN** Claude Code thinks and then runs a command
- **THEN** the working line shows throughout, and the command's line shimmers above it while it runs

#### Scenario: Blocked
- **WHEN** the agent is blocked on a prompt
- **THEN** no working line shows

### Requirement: New answers come in as if typed
Text that arrives while the chat is open SHALL fade in quickly from the top,
as if typed, taking at most 600 ms per message. Text read on first open or
read back SHALL show at once.

#### Scenario: A long answer
- **WHEN** a 40-line answer arrives while Ezra reads the chat
- **THEN** it fades in within 600 ms

#### Scenario: Opening an old conversation
- **WHEN** Ezra opens a chat
- **THEN** the history shows at once, without fading

### Requirement: The chat follows the bottom and the keyboard
While the person is at the bottom, the chat SHALL keep its true end in view
as rows arrive or grow and when the keyboard opens or closes; only the
person's own drag SHALL stop or restart following. A tap on a tool line or
an image SHALL close the keyboard and open the detail in front; closing it
SHALL leave the keyboard closed and the draft kept. Touches on the chat SHALL
never reach the terminal under it, and the hidden terminal SHALL be left out
of accessibility.

#### Scenario: Keyboard opens in a working session
- **WHEN** Ezra taps the message box at the bottom of a chat whose agent works
- **THEN** the end of the last message stays in view above the keyboard as new rows arrive

#### Scenario: Scrolled up
- **WHEN** Ezra has scrolled up and a new answer arrives
- **THEN** the chat stays where it is

### Requirement: A context gauge shows in the top bar
The chat's title bar SHALL show how full the session's context is: for
Codex, percent of the model's window from the log's last token count; for
Claude Code, the used tokens (input plus cache tokens of the last assistant
record), with percent once the window is known. A tap SHALL show the numbers
("283k of 475k tokens"). Plan limits are not part of this change.

#### Scenario: A Codex session
- **WHEN** a Codex log's last token count reports 223k used of a 475k window
- **THEN** the gauge reads 47%, and a tap shows "223k of 475k tokens"

#### Scenario: A Claude Code session
- **WHEN** a Claude Code log's last assistant record used 283k input and cache tokens
- **THEN** the gauge reads 283k
