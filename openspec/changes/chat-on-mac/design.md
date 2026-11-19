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

### D3. Fresh session facts before sending
Periodic lookup still runs every 10 s. A send cannot reuse it: both first
and final checks compare the immutable displayed target's pid, process start
and session against fresh facts. Claude Code uses its session file and `ps`
(or D2's open-file fallback if required); Codex always rereads rollout and
lock files and their times through `lsof`. Unreadable or ambiguous results
refuse before typing. A send may therefore pay the cost of `lsof` twice.

**Owner pick R1-A (proposed):** refuse and keep the draft when session identity
cannot be established, rather than waiting and retrying automatically, as
in desktop-chat D4. Keep the same target through uploads and any guarded
second Enter; do not substitute the latest poll's target.

### D4. The phone asks the system
The phone runs `uname -s` once per machine (cached on success only); a
machine that is neither Linux nor Darwin shows no switch. Today no such
check exists, so the existing spec sentence was not tested.

### D5. Integration before the joint gallery release
Task 3.1 depends on desktop-chat task 2.8 and sections 3–4 (including 3.8 and 4.10) passing,
not on archival. Its desktop delta is drafted now against the pending base:
apply desktop-chat's requirements first, then this change's modifications.
Both changes validate and complete review before desktop-chat section 6;
archive desktop-chat then chat-on-mac after the joint release. This keeps
Mac agents in v1 without a completion cycle.

**Owner pick R1-E (proposed):** after Mac integration, the unsupported Windows
notice says "kudde's chat can read agents on Linux and macOS. This agent runs
on Windows itself; agents in WSL work." Alternative: retain the Linux-only
notice. The supported-machine wording then agrees with the implemented set.

## Testing

- Shell tests with fake `ps`, `lsof`, `stat`, `pgrep` and `date` on the PATH:
  a full-path `comm`, `etime` forms (`MM:SS`, `HH:MM:SS`, `D-HH:MM:SS`), an
  unreadable start, ambiguous rollouts, two agents in one folder.
- The Linux golden test for existing scripts; fresh-send orchestration is
  tested separately without changing the Linux lookup script.
- Fresh-send tests: Codex `/new` and `/resume` between display and first
  check, and between first and final checks; unreadable or tied open files;
  Claude's changed session; immutable upload and second-Enter targets.
- On the MacBook: the desk chat for local agents, from Windows for Mac
  agents, and the phone's switch if task 1 found a route.

## Risks

- Claude Code on macOS may record no usable start (D2 fallback).
- The phone may have no route to a Mac (then desk only).
