# Chat view spike (issue 17), November 2026

Can the phone show an agent's session as a conversation beside its terminal,
and answer it, with what herdr-core already offers? Tested on the desk (WSL,
herdr-core 0.1.20) with Claude Code 2.1.322-2.1.323 and Codex 0.166.0 and
0.169.0, in throwaway terminals in a scratch folder with one-line prompts, and
read-only against the desk's existing logs (counts and record shapes only).
Spike code: branch `spike/chat-view`, never merged: the `herdr-chat` crate
(parsers, matching, desk scripts, 37 tests on synthetic fixtures), a
`ChatSpike` object in the hub, and a debug-only chat screen.

**Result: go for both agents**, with the conditions in "Recommended approach".

## 1. Which log belongs to which terminal

`terminal.get` gives the terminal's foreground process (`foreground.pid`,
`foreground.name`, `foreground.argv`). The agent in front is a process named
`claude` or `codex`, or `node` with the agent's script in its argv (an npm
install, then located as that agent rather than by its process name;
unit-tested, not run on the desk, where both run as their own binaries); `status.app` also names the agent once it reports. Everything else is read with one `machine.exec` of a
small POSIX sh script (`herdr_chat::locate::SCRIPT`, 40-120 ms on the desk).

| Method | Claude Code | Codex |
|---|---|---|
| Per-process session file `~/.claude/sessions/<pid>.json` (`sessionId`, `cwd`, `procStart`) | **holds** | n/a |
| Log file held open (`/proc/<pid>/fd`) | fails: written and closed per record | **holds** (rollout and a per-thread lock file stay open) |
| `--resume <id>` on the command line | partial: wrong after /clear or a fork, absent for `--continue` | same |
| Terminal title (the session's AI title) | weak: only a name, not unique, cleared on exit | weak, same |
| Newest log in the folder | fails with two agents in one folder | fails, same |
| Start hook writing the session id into a terminal label | **holds**, also after exit (tested per run, not installed, no code on the branch) | not tested: Codex documents a `SessionStart` hook with `session_id`, never run here |

Evidence per case (all observed, high confidence for the versions above):

- **Two agents in one folder.** Two Claude Code agents in the scratch folder
  had two session files naming two different sessions; the same held for two
  real agents sharing one project folder on the desk. Two Codex agents in one
  folder each held their own rollout open.
- **/clear.** Claude Code switched to a new session id; its session file, the
  test hook (`source=clear`) and the label followed. The new log exists after
  the first record.
- **Resume.** `--resume <id>` and `--continue` keep the session id and append
  to the same log; `--resume <id> --fork-session` gets a new id and a new log.
  Session file and hook agreed every time. Codex `resume <id>` reopens and
  appends to the same rollout.
- **Codex /new.** The old rollout and its lock stay open and a lock for the new
  thread appears; its rollout appears with the first message. Rule (tested):
  a held lock newer than every open rollout is a new, empty thread; otherwise
  the open rollout written last. Thread ids are time-ordered. Exact with one
  rollout open; with several it is a heuristic on whole-second times, so two
  written in the same second give no chat rather than a guess. A resume or
  fork inside the Codex TUI (an older rollout reopened beside the current
  one) was not tested.
- **Exited into the kept shell.** The foreground becomes the shell; title and
  status are cleared; Claude Code removes its session file on a clean exit
  (stale files stay after a crash: the desk had 164); Codex closes its files.
  Nothing on the desk then links the terminal to the log, except a label a
  start hook wrote. The spike keeps showing what it already read, without the
  message box; opened fresh after the exit, the terminal has no chat (the
  terminal shows). Without the hook that is what the feature can promise.
- **Stale and reused pids.** The session file's `procStart` equals field 22 of
  `/proc/<pid>/stat`; a mismatch is refused. Claude Code writes it as a JSON
  string, not a number (the first phone try failed on that).

Limits: Linux only (`/proc`); a moved `CLAUDE_CONFIG_DIR` or `CODEX_HOME` is
found for Codex (the script matches `*/sessions/*/rollout-*`) but not for
Claude Code (`$HOME/.claude` is assumed). Classic herdr solves the same problem
with a `SessionStart` hook that reports the session id and transcript path to
its server; herdr-core has no such field.

## 2. Parsing

`herdr_chat::Parser` turns either format into four kinds of item: the
person's message (text, image count), the agent's text (Markdown), tool calls
in a row folded into one item (name, title, input, output, running, failed),
and notes (a slash command, "Conversation compacted").

- **Claude Code**: one JSON record per line. Messages are `user` and
  `assistant` records; one API message is split over several records (one
  content block each: `thinking`, `text`, `tool_use`). Tool results are
  `tool_result` blocks in `user` records, matched by `tool_use_id`. Left out:
  `isMeta`, `isSidechain` (subagents), thinking, and some 20 bookkeeping record
  types. `ai-title` gives the title.
- **Claude Code, other agents and the shell.** A subagent's report comes
  back as a `user` record marked `isMeta` (origin "peer") holding
  `<agent-message from="<agent id>">`, its lines indented under a fixed
  harness preamble; the parser shows it as its own kind of item, named after
  the task the agent was started with (the `Agent` tool call's description,
  matched through `toolUseResult.agentId`). Another session's message has the
  same tag without the preamble. A finished background task arrives as a
  `<task-notification>` record (origin "task-notification"); the spike
  showed it as the person's own message until this was found, and now shows
  its `<summary>` as a note. A `!` command the person ran is a
  `<bash-input>` record followed by one with `<bash-stdout>` and
  `<bash-stderr>`; the parser folds them into a "Shell" tool call. In 150
  recent logs no other tag-wrapped record reaches the chat as the person's
  message. **A message typed while the agent works** is written only as an
  `attachment` record of type `queued_command` (origin "human"; in 150 logs
  none of the 18 text ones had a user record too), and so are many
  notifications and peer messages (origin "task-notification" or "peer",
  some repeated later as user records, shown once). The compaction summary
  (`isCompactSummary`) is left out, and "[Request interrupted by user]" is a
  note.
- **Codex**: the `event_msg` records with `item_completed` are the UI-level
  items (`UserMessage`, `AgentMessage`, `CommandExecution`, `FileChange`,
  `ImageView`, `Extension`, `Reasoning`). The `response_item` records repeat
  the same turn for the model and include injected context (AGENTS.md, the
  environment) as role "user", so they are not used. Codex writes no
  "started" item, so a running command shows only once it ends.
- **Sent text comes back wrapped.** herdr-core puts `<hc from="t…">` around a
  message sent from inside a terminal (not one sent through the API without a
  terminal, as the phone does); Claude Code stores a paste of three or more
  lines, or a long one, inside `<pasted_content id="…">`. The parser unwraps
  both.
- **Format guard** (`Parser::health`): a message record without role and
  content is Broken; a single line that is not JSON (a write cut short) is
  left out and counted (Degraded); fewer than half the lines in a
  known shape is Broken (catches the other agent's format); 30 lines or more
  with no message at all is Broken (catches a renamed message record or
  Codex `item_completed`). Inside known records, unknown content blocks or
  item types are counted and left out (Degraded; the chat says some parts
  are not shown). Unknown record types are ignored by design: the desk's
  Claude Code logs carry some 20 bookkeeping types and new ones appear with
  releases. Broken hides the chat and shows the terminal. All 37 real logs
  still parse Ok under this guard (40 recent ones again after the round-2
  changes). Not caught: a renamed field inside a known record that leaves
  messages empty but present, and a message record renamed after messages
  were already shown (the no-message check only covers a log with none);
  the spec needs a check that keeps running.
- **Against real logs**: twelve Claude Code logs from 2.1.292 to 2.1.323 (up
  to 49 MB) and all 25 Codex rollouts of the month (0.166.0 and 0.169.0) parse with health Ok;
  a synthetic unknown Codex item gives Degraded, as tested.

## 3. Live updates

`file.read` does not fit: it counts lines, not bytes; a read stops at 8 MB;
it refuses files over 64 MB outright (seven Claude Code logs on the desk are,
the largest 84 MB); an offset past the end is an error. Most of a large log is
not conversation: request records and base64 images take 50-90%, the
conversation 5-11%.

The spike reads with `machine.exec` and `herdr_chat::tail::SCRIPT` (sh, tail,
head, awk): from a byte offset, only whole lines (a line the agent is still
writing waits), dropping request records, Codex `response_item`,
`world_state` and token records, and emptying base64 `data` fields over 300
characters. It prints the size and the next offset. First open reads the last
2 MB from a line start; later reads only the new bytes.

| Log | Raw | Script output on the desk | Time on the desk |
|---|---|---|---|
| Claude Code, eight largest under 64 MB | 30-49 MB | 4.7-26 MB (whole) | 53-174 ms |
| Claude Code, 84 MB, first open | 84 MB | 0.25 MB (last 2 MB) | 9 ms |
| Codex rollouts | 3.6-40 MB | 0.04-0.46 MB (whole) | 72-701 ms |

Claude Code stayed at 30-58% of raw because each tool result is stored twice
(`toolUseResult`, a structured copy). Most of that copy was image base64,
which the tail now empties too: see section 5.

**Bounded reads.** `machine.exec` keeps only the first 8 MB of output. A
read now spans at most 7.5 MB of the log (the script prints no more than it
reads), so a big backlog comes over several polls. A read that fails (cut
output, timeout, the log gone, unexpected output) or a window holding no
whole line (one record over 7.5 MB) keeps the chat and says so over it
instead of stalling silently; reading back stops at such a record with a
note. Not tested: a log truncated or replaced while open.

**Reading back.** The script takes an end bound too (`sh SCRIPT path start
end`) and leaves out a first line cut by `start` (the byte before it is not a
newline); it reports where its first whole line starts. Scrolling up reads
the 2 MB before the oldest line held, doubled while one line is longer, puts
that text in front of what is held and parses it all again in one parser, so
tool results still meet their calls. Desk tests read a log back in pieces and
get every line exactly once. Each step parses everything held again: 1-62 ms
on the test phone for the small test log; not measured on a large one. If
the recent part holds no message, the screen reads further back by itself.

**Latency.** First measured with the 2 s poll: a message reached the log
0.33 s after `terminal.send`, but new content showed after about 1.3 s on
average and up to 2.5 s, all of it the poll's wait plus three calls in a row.
The spike now polls right after each `status`, `turn` or `title` event of the
terminal (its own `events.subscribe` for that terminal), every 0.5 s while
the agent works or a sent message waits, and every 5 s otherwise; it looks the
log up again only when the process in front changes or 10 s have passed (a
`/clear` keeps the process but starts a new log). On the test phone over
Wi-Fi: a poll takes 110-420 ms (`terminal.get` 50-600 ms with Wi-Fi jitter,
the tail 60 ms, the lookup 100-200 ms when due, and every poll while a new
session's log does not exist yet); the sent message shows at
once, dimmed, until its row is in the log (0.9 s after the tap); a short
answer showed 0.3 s after the agent's `done` event. A pending message is
taken as arrived when any new message of the person appears (no text
match), and the battery and desk cost of polling every 0.5 s were not
measured.

**No streaming.** The logs hold finished blocks only: an answer appears
whole when the agent ends that block, not word by word as in the agents' own
apps. Streaming would need the agent's live output, which neither log offers.

## 4. Sending

`terminal.send` (paste, then Enter, after 3 s without typing; refused while the
agent is blocked) is enough for both agents, multi-line included:

- Two- and four-line messages arrived as one message with their line breaks,
  in Claude Code and in Codex 0.166 and 0.169. Claude Code shows a long paste
  to the model as pasted text; the agent still acted on it.
- Sent through the API without a terminal (as the phone does) there is no
  `<hc>` tag; slash commands such as `/clear` then work. Sent from inside a
  terminal, the tag turns them into plain text.
- **Two hazards, both seen.** A send while Codex showed a start-up update
  prompt (no status reported yet) answered the prompt: Codex updated itself
  from 0.166.0 to 0.169.0. A send to a terminal whose agent had exited ran the
  text as a shell command. And one pasted `/exit` opened a different
  command's dialog in Claude Code, which then took the next sends. So the
  phone sends only when the foreground is the agent and its status is idle,
  done or working, never with no status or blocked. It checks before the
  image uploads and again right before typing, and passes `terminal.send`'s
  own guard (`if`: only idle or working; a mismatch types nothing, tested),
  so a status change in between is refused by the core. `done` has no such
  guard, a small window left. It also refuses when the agent in front is no
  longer the process whose log is shown (restarted, or another agent); a
  `/clear` inside the same process is only noticed at the next look-up. Text
  starting with `/` is refused, and so is text starting with `!`: both
  agents run it as a shell command on the desk (tried in the throwaway
  terminals: Claude Code wrote a `bash-input` record, Codex a
  `user_shell_command`).
- **A message sent while the agent works steers the running turn**, in both
  agents (tested in throwaway terminals: "sleep 8, then say FIRST", then
  "Say SECOND" while working): each took the second message into the turn
  and answered only it; neither said FIRST. So "working" is not a queue; the
  spec has to decide whether the chat offers that (as a steer) or waits.
  Codex records the steering message as an ordinary `UserMessage` item;
  Claude Code only as a queued command (section 2), which the spike first
  dropped, so the message never showed and stayed "Sending…".

## 5. Images

Viewing images is, by the owner's account, the main reason to use the app
over the terminal, so the spike shows them: thumbnails in the person's
bubble and under the tool line that read them, full screen with pinch zoom on
tap, and photos attached from the phone.

**Where they are.**
- Claude Code keeps images as base64 in the log: in the person's message
  (`image` blocks, with `[Image #1]` placeholders in the text, taken out by
  the parser) and in tool results (a screenshot the agent read). A Read
  result stores the image twice: once in the message, once in its
  structured copy (`toolUseResult.file.base64`, with the image's size).
- Codex 0.169 records an image it viewed as an `ImageView` item holding a
  `file://` URL. Of the desk's 276, 127 still existed; 149 pointed into
  `/tmp` and were gone. The log also keeps a copy as a data URL in the
  tool output (`custom_tool_call_output`, a `response_item` the tail
  drops); the feature could fall back to it (not built or tested). No image
  a person attached to Codex appears in the month's logs, so that shape is
  unknown and shown as a missing image.

**Fetching one image.** The tail script now empties every base64 `data` or
`base64` field over 300 characters and leaves `"@<line>:<k>"`: the byte
offset of its line and its index on that line. `herdr_chat::image::LOG_SCRIPT`
(`tail -c`, `head -n 1`, awk) prints that one field; the phone decodes it.
The phone's key names the session too, so after `/clear` an image of the
earlier log is never read from the new one. The emptying goes by field name
and length, so a long non-image `data` value would become a reference
(none seen).
A Codex image is read as a file (`FILE_SCRIPT`, at most 8 MB). Both go
through `machine.exec`; the phone caches what it fetched.

- Against ten real Claude Code logs (0.6-49 MB): all 307 images came back
  as valid PNG, JPEG or WebP (average 12-325 KB), each in 5-94 ms on the
  desk.
- Emptying the structured copies too shrank the tail of the eight largest
  logs from 4.7-26.4 MB to 2.4-9.8 MB (section 3's table is before this).

**Sending from the phone.** The photo picker, then `terminal.put` (the bytes
land in a folder beside the terminal, removed with it, so a sent photo's
bubble loses its image once the terminal is removed), then the message
with each path on a line of its own before the text. Tested in a throwaway
Claude Code 2.1.323 terminal through the API: a pasted line that is an image
path becomes an attached image (`[Image #1]`), two paths give two images, and
the log stores them as normal image blocks, so the chat shows them like any
other. A path inside a sentence also works, but the agent then opens it with
its Read tool. The phone scales a photo over 2000 px or 3.5 MB down and
re-encodes types agents do not take (HEIC) as JPEG.

**Codex** (0.169, throwaway terminal): a pasted image path stays text in
the person's message, and Codex opens the file with its own viewer (an
`ImageView` item), then answers from it (one trial: the model chose to
open it). So the same send works for both agents; the parser turns leading lines that are image paths back into the
images, so the bubble shows the photo, not the path. (A first Codex try
failed to start because the test launch lacked the desk's per-OS setting
for Codex's state database; not a Codex fault.)

**On the phone** (test phone, debug build, the throwaway Claude Code
session): thumbnails and the full-screen view loaded for images the agent
read and images sent; a synthetic photo picked in the system photo picker
was sent with a question, the agent answered from it, and the chat showed it
in the person's bubble.

## Tried on the phone, for the spec

After a first try on the test phone the owner asked for: subagent messages
(done, above), Markdown tables, `!` commands shown as tool calls, code
blocks that wrap and copy, and copying a message (long press). All are in
the spike now. Ideas for later, not built: a context gauge (from the last
assistant record's `usage`: input plus cache tokens against the model's
window), switching between an agent's sessions from the chat, and slash
command completion (careful: section 4's pasted slash command opened the
wrong dialog).

A second round asked for, and the spike now has: the sent message shown at
once (above); the folded line naming what ran ("Ran 7 commands, read 2
files, started 1 agent"); a lone tool call opening its detail straight away;
older messages loading on scrolling up, back to the start; the chat staying
the chosen view when switching agents (a terminal with no chat shows the
terminal, unless the chat was opened there by hand, which says why); and the
terminal's own title bar in the chat (icon, status, swipes), with a switch
labelled Terminal or Chat.

A third round asked for two things. **A tool sheet like the Claude app's:**
three fixed heights (about 46 and 62 percent of the screen, and full),
dragged by its handle or by its list, the same whatever the number of calls;
a colour that stands out from the dimmed chat; a row per call with its kind's
icon and verb (Read, Ran, Updated, Searched, Ran agent), the path's file name
or the command as code; a detail with a back arrow, the tool's name and
Completed, Failed or Running, and Description, Command (or Input) and Output
sections, the last two with a copy button. **A thumbnail bug:** after reading
back, tapping each of three thumbnails opened another image until they were
scrolled out and back. A thumbnail's loaded image outlived a change of the
image it stood for, and its loader then skipped the new one; it is now kept
per image (test: `ChatThumbKeyTest`). What handed the slots other images is
not pinned down: rows are keyed by position minus the rows read back; a
likely cause is the first row held being a folded tool row that gains calls
and images at its front when older text is merged in. The spec should give
each row a stable identity instead: the byte offset of the line that starts
it plus its index within that line (one line can give several rows, and
rows merge across lines), which needs the tail to report line offsets.

## Go or no-go

- **Claude Code: go.** Matching is exact for a running agent through its
  session file; the format is rich and parsed cleanly from 2.1.292 to 2.1.323.
  Risk: a private format with weekly releases; the guard falls back.
- **Codex: go.** Matching is exact through the open rollout; `item_completed`
  is a clean UI-level stream. Risks: no running state for a command until it
  ends; the interactive TUI was tested on two versions only, and 22 of the
  month's 25 rollouts came from non-interactive runs.

## Recommended approach for the OpenSpec change

1. Match a running agent without desk setup: Claude Code's session file
   (with the `procStart` check) and Codex's open rollout, through one
   `machine.exec` script. Hide the chat switch when it finds nothing.
2. After the agent exits, keep showing what was read, read-only, until the
   terminal starts another agent; a fresh open after the exit shows the
   terminal. An optional start hook that labels the terminal
   (`agent.session=<id>`) would make this exact across app restarts; it is a
   desk setting, tested for Claude Code only, so the spec offers it rather
   than requiring it, and Codex's needs a test first.
3. Read through `machine.exec` and the tail script, never `file.read`; first
   open from the end with a budget, older messages on scroll back, new bytes
   after the terminal's events and every 0.5 s while it works; show a sent
   message at once.
4. Parse in Rust (`herdr-chat`), one module per agent, with the health guard
   (kept running, not only for a log with no message yet); include Claude
   Code's queued commands; fixtures stay synthetic. Rows carry a stable
   identity (line offset plus index), so the screen never keys them by
   position.
5. Send with `terminal.send` from the API, gated on foreground and status as
   in section 4, checked again right before typing and passed as its `if`
   guard, and only to the agent process whose log is shown; no slash
   commands and no leading `!` from the chat in the first version. A sent
   message waits as "Sending…" until a row with its text arrives. Decide
   whether a message while the agent works is offered as a steer of the
   running turn (what both agents do with it) or held until it is idle.
6. Images in v1: fetched one at a time by the reference the tail leaves,
   shown as thumbnails and full screen; attached from the phone through
   `terminal.put` and a path line, for both agents (Codex rests on one trial
   where the model chose to open the file). Photos are turned upright from
   their EXIF orientation before they are re-encoded.
