# Design: the chat for agents on a Mac

## Context

`kudde-chat`'s scripts (`locate::SCRIPT`, `image::FILE_SCRIPT`, the tail
script) are POSIX `sh` but read Linux facts: `/proc/<pid>/comm`, `/stat`,
`/fd`, and `stat -c %Y`. Claude Code writes `~/.claude/sessions/<pid>.json`
with a `procStart` taken from `/proc` on Linux; what it writes on macOS is
not known yet.

## Decisions

### D1. One script, one branch on `uname -s`
The Linux branch keeps its exact text; a golden test fails on any byte
change to it, so the released phone path cannot shift. The Darwin branch:
- name: `ps -o comm= -p <pid>`, reduced to its basename (`${n##*/}`), since
  macOS prints the full path where Linux prints the name;
- process start: `date +%s` minus `ps -o etime=` converted to seconds
  (locale-free, unlike `lstart`);
- open files: `lsof -p <pid> -Fn` for the rollouts and locks (Codex) and
  the open `.jsonl` (Claude Code);
- file times: `stat -f %m`; children: `pgrep -P`; base64: `base64 -b 0`.

### D2. Claude Code on a Mac
Task 1 records what Claude Code writes in its session file on macOS. The
spec's rule is a comparison of the recorded start with the system's, to
within 2 seconds (the `etime` resolution plus a margin). If Claude Code
records nothing comparable, the spec is revised before building: the open
`.jsonl` from `lsof` becomes the match, and Missed when more than one is
open. Either way, two agents in one folder must each find their own log.

### D3. `lsof` only in the lookup
`lsof` can take hundreds of milliseconds on a Mac. It runs in the 10 s
lookup only; the check right before a send compares pid, process start and
session from `ps` and the session file.

### D4. The phone asks the system
The phone runs `uname -s` once per machine (cached on success only); a
machine that is neither Linux nor Darwin shows no switch. Today no such
check exists, so the existing spec sentence was not tested.

## Testing

- Shell tests with fake `ps`, `lsof`, `stat`, `pgrep` and `date` on the PATH:
  a full-path `comm`, `etime` forms (`MM:SS`, `HH:MM:SS`, `D-HH:MM:SS`), an
  unreadable start, ambiguous rollouts, two agents in one folder.
- The Linux golden test.
- On the MacBook: the desk chat for local agents, from Windows for Mac
  agents, and the phone's switch if task 1 found a route.

## Risks

- Claude Code on macOS may record no usable start (D2 fallback).
- The phone may have no route to a Mac (then desk only).
