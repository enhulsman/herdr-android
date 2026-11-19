# Kudde chat on the desktop: brief for the next session

Written 2026-11-19 for the session that writes the openspec proposal. It
merges a first look and two independent read-only reviews (Codex and a
Claude agent), kept locally (`local/spec-research/`, not tracked), which
formed their own views from the same brief. Superseded where they differ by
`openspec/changes/desktop-chat/` after its council review.

## Summary for Ezra

**Your decisions:**

1. **Route for sharing code with the phone.** Recommended: run Kudde's Rust
   chat code inside the desktop mod by converting it to plain JavaScript
   (`wasm2js`), and test that first. If the test fails, the choice is between:
   - a small Kudde program installed on each agent machine: one parser, but it
     breaks the chat spec's "nothing installed on the desk" rule; or
   - a JavaScript copy of the parser: nothing installed, but two parsers to
     keep in step.

   My pick if the test fails: the installed program, because log formats
   change often (10 parser commits this month). Confidence: moderate.
2. **What v1 includes.** Recommended: read the chat (text, markdown, folded
   tool calls, pictures) and send replies. When an agent is waiting on a
   permission prompt or question, v1 opens the real terminal instead of
   answering it from the chat. Both reviewers recommend this, because answering
   prompts safely needs a design of its own.
3. **Is publishing to the gallery a v1 goal?** v1 works for agents on Linux
   (WSL, a Pi, a server), seen from Windows, Linux or a Mac. It does not work for
   agents running on a Mac itself, which is most of the gallery's audience. My
   pick: build v1 for your own setup and publish once Mac agents are supported.
4. **Which visual ideas to pursue next** (list below). No rush; none of them
   blocks v1.

**What you will see:** in the Herdr app on Windows, an agent running in WSL or
on a Pi shows as a conversation like it does on the phone: your messages as
bubbles, its answers formatted, tool calls folded into one line, and pictures
shown. You type a reply under it. The terminal stays one click away.

## What the three agreed on

- Read the logs through herdr-core on the agent's own machine
  (`exec(..., {on: machine})` in the mod API), with the shell scripts Kudde
  already has. Nothing runs on Windows itself. Confidence: high that the API
  allows it; low until it is tested.
- Share more than the parser. `ChatCore` (`crates/kudde-mobile/src/hub/chat_core.rs`,
  "the chat's state without any I/O") moves into `kudde-chat`, keeping its
  times as milliseconds instead of `Instant`. Phone and desk then share lookup,
  incremental reads, row identity, health and the send checks. The UI and the
  transport stay separate.
- Agents running on a Mac or natively on Windows are out of scope. The scripts
  need `/proc` and GNU `stat`, and the chat spec already limits itself to Linux
  machines. Supporting a Mac means a second way of finding the log (`ps`,
  `lsof`).
- Do not clone Flockbook. Do borrow these from it:
  - keys go out only on a press, never on a timer;
  - the screen or session is checked again right before anything is typed;
  - a versioned protocol with clear mismatch errors;
  - the Needs you and Overview screens;
  - a timing harness that stays off unless a file switches it on;
  - an honest "Watch out" list in the RECIPE.

  Flockbook shows only "N images"; showing the actual pictures is Kudde's edge.

## Where the reviewers disagreed

| | Codex Astra | Fable | Brief |
|---|---|---|---|
| How the desk runs Kudde code | A Rust program on each agent machine, returning versioned JSON | Kudde code compiled to wasm, converted to JS, shipped in the mod | Test Fable's route first (it keeps the no-install rule and shares `ChatCore` too); Codex's route is the fallback |

Facts behind that call, checked in this session:
- The mod runtime is QuickJS (herdr-app's THIRD-PARTY-NOTICES), so real
  WebAssembly is unavailable and the converted JS is the only way to run the
  crate in the mod.
- `kudde-chat` built for `wasm32-unknown-unknown` unmodified on the stable
  toolchain. The repo pins 1.95.0, which lacks that target, so adding it is a
  toolchain change.
- `openspec/specs/chat-view/spec.md:60-63` requires "nothing installed on the
  desk".

## Corrections to the first pass

- "macOS works" was wrong for agents running on a Mac (see above).
- It suggested `bin/token-place` for copying the helper; that tool is for
  secrets. Wrong tool.
- "Polling is probably fine" was unsupported. The mod has no event stream, and
  each check starts `hc.exe` plus a command on the remote machine. Kudde's
  phone measured 110-420 ms per check. Check only when `agents()` status or the
  terminal's turn changes.
- "The first chat view on Windows" is not established; the gallery names no
  platforms.
- The size estimate of 3-5 sessions was too low. Fable estimates 1 session for
  the test, then 5-8 for a read-only chat. Confidence: low.

## Risks the spike must settle (in this order)

1. **wasm2js under QuickJS.** Does a 0.5-1.5 MB module load, and how long does
   a 2 MB first open take (`FIRST_BUDGET`)? Mod code probably runs on the UI
   thread, so a slow parse freezes the window.
2. **Row heights.** `v_virtual_list` needs every row's height up front.
   Flockbook guesses from Mac Helvetica widths, which will be wrong on Windows.
   Try `list` (measured rows) or a scroll column of the last N rows with
   `scroll_to_bottom` first.
3. **Exec returns text, not bytes.** Check that non-ASCII text survives from
   WSL to Windows, and add a base64 variant of `image::FILE_SCRIPT`.
4. **Images.** Likely fine. The app already shows files it downloaded at run
   time (`frame/settings/release-notes.js:23`). Use content-hash file names
   under the app home; cache eviction is still open.
5. **Sending.** Keep the `send.rs` target check. On the desk that is two round
   trips (`hc get` plus the locate script) before `hc send --if`. Test long
   text, a leading `-` and newlines through Windows process arguments. "done"
   has no status guard (`send.rs:68-70`).
6. **Markdown limits.** `TextView.markdown` takes no style, so code blocks and
   tables are drawn by hand (Flockbook's notes cover how).

**The spike is done when:** a mod pane in the Herdr app on Windows shows a real
Claude Code session from WSL, including one picture, and a new reply from the
agent appears without a click. The timings for module load, first open and one
check are written down. If wasm2js fails, record why; that triggers decision 1.

## Ideas, merged from all three

Utility, for the chat mod itself:
- **Chat beside the terminal:** the conversation on the left, the live terminal
  on the right; prompts are answered in the real terminal.
- **Context strip:** each agent's context filling toward 300k/400k/500k and
  turning WRAP UP, then HANDOFF, with a button that types `/handoff`
  (`kudde_chat::Gauge` already exists).
- **Image contact sheet:** every picture the agent saw or made this session, as
  a grid.

Visual and themed, for the gallery:
- **Pit Wall:** a timing tower with agents as cars, turn time as lap time, and
  "BOX BOX" when one is blocked. Check whether the gallery's "Paddock" is
  already motorsport.
- **Chess clock:** two clocks per agent, its thinking time against your waiting
  time; the flag drops when it is waiting on you.
- **Analysis Board:** Claude's plan and Codex's critique drawn as chess
  variations, with the open objections marked.
- **Three-machine map / Dutch Night Watch:** WSL, a Pi and a server as lit
  windows or islands in the art-page style; a light comes on when an agent
  there needs you.
- **Context Observatory:** the context strip as orbital gauges, the art
  version of the utility above.

Workflow:
- **Handoff shelf:** handoffs per project, newest first, with a Continue button
  that starts an agent in a new tab and marks the handoff `continued`.
- **Spec rail:** the openspec change's tasks ticking off beside the agent doing
  them.
- **Review with Codex:** a button on a finished agent that runs the Codex
  review and shows the weekly quota first.

## Next session

1. Fresh session in the repository; read this brief.
2. Ezra answers decisions 2 and 3 (decision 1 waits for the spike).
3. `/opsx:propose desktop-chat`. Scope: move `ChatCore` into `kudde-chat`, the
   wasm2js spike, and a read-only chat mod with reply.
4. Review the proposal with `/council`, not `/scrutinize`: it sets new
   patterns across two products (one crate feeding both apps, a desk mod,
   possibly a program on remote machines). The case for `/scrutinize`: one
   repo, and the first step is a cheap test. Use `/scrutinize` for the smaller
   follow-up changes.
5. Then tests from the scenarios, then `/opsx:apply`.
