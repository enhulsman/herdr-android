## ADDED Requirements

### Requirement: The chat opens beside an agent's terminal
The herdr app SHALL open an agent's chat as a pane to the right of its
terminal, from a key and from the command `kudde.chat`, while that terminal
is focused. The pane SHALL follow that one terminal, and SHALL close when
the terminal closes. Opening the chat for a terminal that already has one
SHALL focus the existing pane, never open a second. For a terminal whose
lookup result is Missed or None, the pane SHALL read "No conversation found
for this terminal" with an "Open terminal" button. The reply box and every
button SHALL be reachable with Tab, and Enter SHALL open or close a focused
fold.

#### Scenario: Opening a chat
- **WHEN** Ezra focuses a terminal running Claude Code and presses the chat key
- **THEN** a pane opens to its right showing the conversation, and the terminal stays visible

#### Scenario: Opening it twice
- **WHEN** Ezra presses the chat key again on a terminal whose chat is open
- **THEN** the existing chat pane is focused and no second pane opens

#### Scenario: A plain shell
- **WHEN** Ezra opens the chat for a terminal running a shell
- **THEN** the pane reads "No conversation found for this terminal" with an "Open terminal" button

#### Scenario: The keyboard only
- **WHEN** Ezra moves through the pane with Tab and presses Enter on a folded tool row
- **THEN** focus reaches the reply box and the buttons, and the row opens

### Requirement: The desk shares the phone's chat core
The desk SHALL find, read and show a conversation, and decide how and
whether to send, with the same core as the phone: the lookup results and
their rules, bounded reading from the end, the health guard, row identity,
Codex commands kept in their turn, code blocks, the send checks, the guards
and the pending states, as `chat-view` states them. Given the same inputs,
the desk's rows and send decisions SHALL equal the phone's. Nothing SHALL be
installed on the agent's machine or the desk beyond the mod folder.

#### Scenario: The same log on both
- **WHEN** a chat-view fixture is replayed through the desk's converted core and through the phone's
- **THEN** both give the same rows with the same keys

#### Scenario: The same pending states on both
- **WHEN** a sent message never reaches the log, through the desk's converted core and through the phone's
- **THEN** both read "Not in the conversation yet" 30 seconds after sending

#### Scenario: Two agents in one folder
- **WHEN** two Claude Code agents run in the same folder in two terminals and both chats are open
- **THEN** each pane shows its own conversation

#### Scenario: A failure inside the core
- **WHEN** the converted core fails while reading one chat, with a second chat open
- **THEN** both panes recover from a fresh lookup and show their conversations again

### Requirement: New answers appear without a click
Each pane SHALL do one read at a time and drop a reply older than the
latest it started. It SHALL read on opening and when its agent's status
changes; every second while the agent works, for 7 seconds after it stops
working, and while a sent message is pending; and otherwise every 5 seconds
by checking the log's size, reading only when it changed. It SHALL look the
log up again every 10 seconds with the same process in front. A pane that is
not shown still costs one check on the agent's machine every 5 seconds.
After three rounds slower than 1.5 seconds, the pane SHALL read at most
every 3 seconds until a round is fast again. The chat SHALL follow the
bottom unless Ezra has scrolled up, and SHALL load older messages when he
scrolls to the top.

#### Scenario: A reply while watching
- **WHEN** the agent answers while its chat is open and scrolled to the bottom, on a machine answering within a second
- **THEN** the answer appears within two seconds of being written, without a click, and the chat stays at the bottom

#### Scenario: An answer written just after the agent goes idle
- **WHEN** the agent's status turns idle and its final answer is written to the log a second later
- **THEN** the answer appears without a click

#### Scenario: An idle agent
- **WHEN** an agent stays idle with its chat open and its log does not grow
- **THEN** only a size check runs every 5 seconds, and the lookup every 10 seconds

#### Scenario: A slow machine
- **WHEN** three rounds in a row take longer than 1.5 seconds
- **THEN** the pane reads at most every 3 seconds, and the chat keeps working

#### Scenario: Scrolled up
- **WHEN** Ezra has scrolled up and a new answer arrives
- **THEN** his place stays where it is

### Requirement: The chat never holds up the window
The desk SHALL bound synchronous work before calling the core.

**Owner pick R1-D (proposed):** catch-up and older messages appear across
bounded slices; alternative: pause immediately on a backlog larger than one
slice. Before each synchronous call the desk SHALL bound input bytes,
records processed, rows cloned and bytes serialized, using limits measured
by the spike. First, incremental and older read budgets SHALL be configurable
for the desk, including the 512 KB first-read fallback; phone defaults SHALL
remain unchanged. Parsing a record larger than a slice, reparsing older
history and constructing or serializing views SHALL yield across bounded
slices or pages without losing or duplicating rows. An unchanged revision
query SHALL NOT clone or serialize the held rows. Panes SHALL share a work
queue that yields to window input and other panes between slices.

The spike SHALL use the actual ChatCore through callable wasm32 exports and
JSON/view calls, after isolated core/clock preparation 2.1–2.2 if needed. It
SHALL measure first open (at most 300 ms), maximum incremental reads
(7,864,320 bytes), long retained histories, older-page reparsing, maximum-size
records, changed and unchanged views, JSON encoding/decoding and multiple
panes. Subsequent synchronous slices SHALL take at most 50 ms in these
measurements. A parser-only harness or a ten-tick pause SHALL NOT establish
this gate; dependent work SHALL wait for a passing spike.

#### Scenario: Backlog and older history
- **GIVEN** a pane with a long retained history and a maximum incremental read
- **WHEN** it parses the backlog or loads an older page
- **THEN** each synchronous call obeys the recorded work limits, yields between slices, and no row is lost or duplicated

#### Scenario: A record larger than a slice
- **GIVEN** one log record larger than the configured slice budget
- **WHEN** the desk reads and parses it
- **THEN** bounded slices preserve the partial record and byte offsets until it is complete, without one unbounded parse call

#### Scenario: Unchanged view with another pane active
- **WHEN** the desk checks an unchanged revision while another pane catches up
- **THEN** it does not construct or serialize the held rows, and the catching-up pane yields to other panes and window input

The pane SHALL stop reading when ten reads in a row each spend more than
50 ms on the window's thread, and SHALL show "The chat is paused: it was slowing the
window" with an "Open terminal" button. Slow answers from the agent's machine
SHALL NOT pause it.

#### Scenario: A pane slowing the window
- **WHEN** ten reads in a row spend more than 50 ms each on the window's thread
- **THEN** the pane shows the paused message and reads no more

### Requirement: The desk chat shows the conversation as the phone does
The pane SHALL show Ezra's messages as bubbles, the agent's answers as
formatted markdown with fenced code in monospace blocks, and tool calls
folded into one line per run that opens on a click to show each call and
its result. While the agent works, a working line SHALL show at the bottom.
Colours SHALL come from the app's theme.

#### Scenario: A turn with three tool calls
- **WHEN** the agent ran three commands before answering
- **THEN** one folded line shows above the answer, and a click on it shows the three calls with their results

#### Scenario: A code block
- **WHEN** an answer contains a fenced code block
- **THEN** the block shows in a monospace font on its own background

#### Scenario: Switching theme
- **WHEN** Ezra switches the app between a light and a dark theme
- **THEN** the chat follows it

### Requirement: Pictures show in the desk chat
The pane SHALL show pictures from Ezra's messages and from tool results as
thumbnails, fetched one at a time from the agent's machine and kept in a
cache under the app's folder. A click SHALL open the picture larger in a
dialog. A picture that cannot be fetched, is larger than 6,000,000 bytes, or
is not a PNG, JPEG, GIF or WebP SHALL show as "Image not available". A Codex
image SHALL be cached by path and file time, so a reused path never shows an
older picture. The cache SHALL hold at most 200 MB, removing the least
recently used pictures first, never one used in the last minute.

#### Scenario: A screenshot the agent read
- **WHEN** the agent read a PNG screenshot
- **THEN** its thumbnail shows in the tool row, and a click opens it larger

#### Scenario: Two pictures in one row
- **WHEN** a tool row holds two thumbnails and Ezra clicks the second
- **THEN** the second picture opens larger

#### Scenario: A deleted Codex image
- **WHEN** a Codex image view points to a file that is gone
- **THEN** "Image not available" shows in its place

#### Scenario: A reused Codex image path
- **WHEN** Codex writes a new picture to a path whose old picture is cached
- **THEN** the new picture shows

#### Scenario: A picture too large
- **WHEN** a picture's file is larger than 6,000,000 bytes
- **THEN** "Image not available" shows in its place

#### Scenario: A full cache
- **WHEN** a new picture would take the cache over 200 MB
- **THEN** the least recently used pictures are removed until it fits

### Requirement: Replies go only to the agent whose log is shown
The desk SHALL check fresh session facts before each send.

**Owner pick R1-A (proposed):** refuse and keep the draft when identity
cannot be established; alternative: wait and retry lookup automatically.
Both first and final send checks SHALL compare fresh facts with the immutable
displayed terminal, agent kind, pid, process start and session. Claude's
facts SHALL come from its session file and process facts; Codex's SHALL
include freshly read open rollout and lock files and their times. Unreadable
or ambiguous facts SHALL refuse before dispatch, without cached lookup
fallback. The same target SHALL survive uploads and a guarded second Enter.
These checks SHALL NOT be described as an atomic session guard.

The pane SHALL have a reply box under the chat: Enter sends, Shift+Enter
starts a new line. A message SHALL be sent only after the send checks
confirm, right before sending, that the same process and session are still
in front; while the agent is idle or working it SHALL be sent with herdr-core's
guard for that status, and while it is done without a guard, as on the
phone. `/` and `!` messages SHALL be sent as typed. A sent message SHALL show
at once and follow the pending states `chat-view` states; a send whose
outcome is unsure SHALL never be sent again by itself. Control characters
other than newline and tab SHALL be removed; all other text SHALL arrive
unchanged, including newlines, non-ASCII characters and a leading `-`.

#### Scenario: Codex changes thread before the first check
- **GIVEN** Codex keeps its pid and process start
- **WHEN** `/new` or `/resume` changes the displayed thread before the first send check
- **THEN** fresh open-file facts refuse the send and no text is typed

#### Scenario: Codex changes thread between checks
- **GIVEN** the first check accepted the displayed thread
- **WHEN** `/new` or `/resume` changes it before the final check with the same pid and process start
- **THEN** the final fresh open-file check refuses and preserves the original draft

#### Scenario: Open files cannot identify the thread
- **WHEN** either send check finds unreadable open files or an ambiguous lookup
- **THEN** nothing is typed, the draft stays, and the previous lookup is not reused

#### Scenario: A normal reply
- **WHEN** Ezra types a reply and presses Enter while the agent is idle
- **THEN** the agent receives the text exactly as typed and the bubble shows at once

#### Scenario: A reply to an agent that is done
- **WHEN** Ezra replies to an agent whose status is done
- **THEN** the message is sent after the process and session checks

#### Scenario: The agent restarted meanwhile
- **WHEN** the agent was restarted in the terminal between opening the chat and sending
- **THEN** nothing is typed into the terminal and the pane says "This is a different session now"

#### Scenario: A multi-line message with special characters
- **WHEN** Ezra sends three lines starting with `- `, containing "café", quotes, a `%` and a trailing backslash
- **THEN** the agent receives the same three lines

#### Scenario: A pasted control character
- **WHEN** Ezra pastes text containing a Ctrl+C character and a Windows line ending
- **THEN** the agent receives the text without the Ctrl+C, with a plain line break

#### Scenario: A send that timed out
- **WHEN** a send's outcome is unsure because herdr-core did not answer in time
- **THEN** the message is not sent again, and its bubble follows the pending states

### Requirement: A waiting agent sends Ezra to the terminal
The reply box SHALL be replaced, while the agent's status is blocked or it
reports no status, by "Waiting for you in the terminal" and an "Open
terminal" button that focuses the agent's terminal. Nothing SHALL be typed
into the agent then. A draft typed before SHALL be kept and come back when
the agent is idle, working or done again.

#### Scenario: A permission prompt
- **WHEN** Claude Code asks for permission while its chat is open
- **THEN** the pane shows "Waiting for you in the terminal", and "Open terminal" focuses the terminal

#### Scenario: A draft across a prompt
- **WHEN** Ezra had typed half a message when the prompt appeared, and answers the prompt in the terminal
- **THEN** the reply box comes back with his half message in it

### Requirement: After the agent exits, the desk chat stays read-only
When the agent's process ends, the pane SHALL keep showing what was read,
without the reply box, with an "Open terminal" button.

#### Scenario: Exit while the chat is open
- **WHEN** the agent exits while its chat is open
- **THEN** the conversation stays and the reply box is gone

### Requirement: Machines the chat cannot read or reach are named
The desk chat SHALL read agents on Linux machines. While a machine is still
connecting, the pane SHALL wait without a message. For a machine herdr
cannot reach, it SHALL read "Can't reach <machine>; retrying" and try again
once the machine is back. For an agent on a Mac it SHALL read "kudde's chat
can't read agents on a Mac yet." For an agent on any other machine it SHALL
read "kudde's chat can read agents on Linux. This agent runs on Windows
itself; agents in WSL work." Each SHALL offer "Open terminal". An
unreachable machine SHALL never be named as unsupported.

#### Scenario: An agent on Windows itself
- **WHEN** Ezra opens the chat for Claude Code running natively on Windows
- **THEN** the pane shows the Windows message and the "Open terminal" button

#### Scenario: An agent on a Mac
- **WHEN** Ezra opens the chat for Claude Code running on a Mac
- **THEN** the pane reads "kudde's chat can't read agents on a Mac yet."

#### Scenario: A machine that is offline
- **WHEN** Ezra opens the chat for an agent on a Linux machine that herdr cannot reach
- **THEN** the pane reads "Can't reach <machine>; retrying", and shows the conversation once the machine is back

### Requirement: Drift and updates are visible
The pane SHALL show, when three or more and more than 5% of the records the
chat holds are of a kind the core does not know, "Some messages can't be
shown" with an "Open terminal" button, above the conversation. Once a day
the mod SHALL check the version published in the gallery repository and,
when its own is older, show "A newer kudde chat is available". When herdr
answers with a terminal description the mod does not know, the pane SHALL
read "This herdr version is not supported yet" with "Open terminal".

#### Scenario: A newer agent version writes new records
- **WHEN** an agent update writes a record kind the core does not know, ten times in a chat of a hundred records
- **THEN** the pane shows "Some messages can't be shown" and keeps showing the rest

#### Scenario: A newer mod exists
- **WHEN** the gallery repository publishes a version newer than the installed mod
- **THEN** the pane shows "A newer kudde chat is available"

### Requirement: The desk preserves delivery certainty
The adapter SHALL return Typed only for a recognized successful send
acknowledgement, and Refused only with evidence that nothing was typed
(a pre-dispatch failure or a recognized no-input herdr-core rejection).
After dispatch, timeout, disconnect, malformed output or an unrecognized
failure SHALL be Unsure; exit status, stderr or a rejected promise alone
SHALL NOT prove refusal. This mapping SHALL apply to direct `hc()` and
fallback `exec` alike.

**Owner pick R1-C (proposed):** Unsure SHALL remain pending without draft
restoration, automatic retry, fallback-route retry or second Enter;
alternative: offer manual retry with a duplicate warning. A definitive
refusal SHALL remove its pending bubble and restore the original draft,
merged with text typed meanwhile, as on the phone.

#### Scenario: Delivery followed by lost acknowledgement
- **GIVEN** the message was delivered through direct hc or fallback exec
- **WHEN** its acknowledgement is lost through timeout or disconnect
- **THEN** it remains pending as Unsure, the draft is not restored, and no route sends it or a second Enter again

#### Scenario: An unrecognized command failure
- **WHEN** either route returns malformed output, nonzero exit/stderr or a rejected promise after dispatch without evidence that nothing was typed
- **THEN** the result is Unsure, not Refused, and the same pending and no-retry rules apply

#### Scenario: A definitive refusal
- **WHEN** a pre-dispatch failure or a recognized no-input rejection refuses a send
- **THEN** the bubble is removed and the original draft is restored alongside anything typed meanwhile

#### Scenario: A successful acknowledgement
- **WHEN** either route returns a recognized successful send acknowledgement
- **THEN** the result is Typed and the bubble follows the shared pending states

### Requirement: Mac integration precedes the gallery release
The gallery release SHALL wait for chat-on-mac's integrated desktop delta,
checks and review. Mac integration SHALL depend on desktop-chat task 2.8
and sections 3–4 passing, including bounded-work and adapter tests, rather
than archival. The changes SHALL apply in order: desktop-chat base, then
chat-on-mac modifications; both SHALL validate before joint release.

#### Scenario: Integrating Mac support before archival
- **GIVEN** desktop-chat task 2.8 and sections 3–4 pass and neither change is archived
- **WHEN** chat-on-mac integrates and passes checks and review
- **THEN** the joint gallery release can proceed, followed by desktop-chat archival and then chat-on-mac archival
