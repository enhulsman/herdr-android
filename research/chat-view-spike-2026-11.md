# Chat view spike (issue 17), November 2026

Can the phone show an agent's session as a conversation beside its terminal,
and answer it, with what herdr-core already offers? Tested on the desk (WSL,
herdr-core 0.1.20) with Claude Code 2.1.322-2.1.323 and Codex 0.166.0 and
0.169.0, in throwaway terminals in a scratch folder with one-line prompts, and
read-only against the desk's existing logs (counts and record shapes only).
Spike code: branch `spike/chat-view`, never merged: the `herdr-chat` crate
(parsers, matching, desk scripts, 23 tests on synthetic fixtures), a
`ChatSpike` object in the hub, and a debug-only chat screen.

**Result: go for both agents**, with the conditions in "Recommended approach".

## 1. Which log belongs to which terminal

`terminal.get` gives the terminal's foreground process (`foreground.pid`,
`foreground.name`). Everything else is read with one `machine.exec` of a
small POSIX sh script (`herdr_chat::locate::SCRIPT`, 40-120 ms on the desk).

| Method | Claude Code | Codex |
|---|---|---|
| Per-process session file `~/.claude/sessions/<pid>.json` (`sessionId`, `cwd`, `procStart`) | **holds** | n/a |
| Log file held open (`/proc/<pid>/fd`) | fails: written and closed per record | **holds** (rollout and a per-thread lock file stay open) |
| `--resume <id>` on the command line | partial: wrong after /clear or a fork, absent for `--continue` | same |
| Terminal title (the session's AI title) | weak: only a name, not unique, cleared on exit | weak, same |
| Newest log in the folder | fails with two agents in one folder | fails, same |
| Start hook writing the session id into a terminal label | **holds**, also after exit (tested per run, not installed) | expected to hold (Codex has a stable `SessionStart` hook with `session_id`; not run) |

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
  the open rollout written last. Thread ids are time-ordered.
- **Exited into the kept shell.** The foreground becomes the shell; title and
  status are cleared; Claude Code removes its session file on a clean exit
  (stale files stay after a crash: the desk had 164); Codex closes its files.
  Nothing on the desk then links the terminal to the log, except a label a
  start hook wrote. The spike keeps showing what it already read, without the
  message box.
- **Stale and reused pids.** The session file's `procStart` equals field 22 of
  `/proc/<pid>/stat`; a mismatch is refused.

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
- **Format guard** (`Parser::health`): a line that is not JSON, or a message
  record without role and content, is Broken; fewer than half the lines in a
  known shape is Broken (catches the other agent's format); unknown content
  blocks or item types are counted and left out (Degraded). Broken hides the
  chat and shows the terminal.
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

| Log | Raw | Sent to the phone | Time on the desk |
|---|---|---|---|
| Claude Code, eight largest under 64 MB | 30-49 MB | 4.7-26 MB (whole) | 53-174 ms |
| Claude Code, 84 MB, first open | 84 MB | 0.25 MB (last 2 MB) | 9 ms |
| Codex rollouts | 3.6-40 MB | 0.04-0.46 MB (whole) | 72-701 ms |

Claude Code stays at 30-58% of raw because each tool result is stored twice
(`toolUseResult`, a structured copy); awk cannot drop a nested field safely.
The spike polls every 2 s while the chat is open; the feature should read on
`turn`, `title` and `status` events and while the agent is working.

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
  done or working (it queues), never with no status or blocked; slash
  commands are not offered from the chat.

## 5. Images

Claude Code stores images inline as base64: in the person's messages and in
tool results (a screenshot read by the agent). One session with seven phone
screenshots grew to 10.7 MB, with a single 1.6 MB line. Codex records an image
the agent viewed as a path (`ImageView`) and keeps images elsewhere. The spike
drops image bytes on the desk and shows "1 image"; showing them would need a
second read of that one record.

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
   terminal starts another agent. An optional start hook that labels the
   terminal (`agent.session=<id>`) makes this exact across app restarts; it is
   a desk setting, so the spec offers it rather than requiring it.
3. Read through `machine.exec` and the tail script, never `file.read`; first
   open from the end with a budget, older messages on scroll back, new bytes
   on `turn`/`status` events.
4. Parse in Rust (`herdr-chat`), one module per agent, with the health guard;
   fixtures stay synthetic.
5. Send with `terminal.send` from the API, gated on foreground and status as
   in section 4; no slash commands from the chat in the first version.
6. Images: a count in v1; viewing them is a follow-up.
