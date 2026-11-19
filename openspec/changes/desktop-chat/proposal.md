# Proposal: kudde's chat in the herdr desktop app

## Plain summary

**Decisions** (Ezra, 2026-11-19: decisions 1–8 stand, after the council review):
1. **Opening a chat on the desk.** A pane beside the agent's terminal,
   opened with a key and from the command list (`kudde.chat`) while the
   agent's terminal is focused. The pane stays with that terminal and closes
   with it. *Over* a Terminal / Chat switch inside one pane, as on the phone:
   the desk has room for both, and the terminal stays visible for prompts.
2. **Prompts.** While an agent waits on a permission prompt or a question,
   the chat says so and offers a button that focuses the real terminal.
   Nothing is answered from the chat in this change.
3. **Agents on a Mac are in v1**, as their own change, `chat-on-mac`, which
   the gallery release waits for. Until it lands, this change reads agents
   on Linux machines (WSL included) and names a Mac agent as not supported
   yet. Agents running on Windows itself stay out (no `sh`).
4. **Two new build tools.** The `wasm32-unknown-unknown` target in
   `rust-toolchain.toml`, and Binaryen's `wasm2js`, on the dev machine and
   in CI, plus a pinned QuickJS (`qjs`) for tests. They are how the phone's
   Rust chat code runs inside the desktop mod (QuickJS, the mod runtime, has
   no WebAssembly).
5. **Published to the herdr gallery as "kudde"**, from a separate small
   public repository that holds only the built mod; this repository's
   release builds it and copies it there. A survey of the gallery found
   listed mods with generated files up to 6 MB, and deleted repositories
   still listed, so no test entry is published first. The listing text
   is shown to Ezra verbatim, and submitting waits for his OK.
6. **The phone's send logic moves into the shared Rust code**, with the chat
   core and the code-block splitter, and ships as its own phone release
   before the desk depends on it. Otherwise the desk would hold a third copy
   of the logic most phone fixes landed in.
7. **If the spike fails**, Ezra chooses then between a
   JavaScript copy of the parser (nothing installed, two parsers to keep in
   step) and a small kudde program on each agent machine (one parser, but
   something to install; `kudde-push` already has a signed release
   pipeline it could join). *Pick then: the program*, because the log
   formats change often. Confidence: moderate.
8. **Later, each as its own issue:** attaching pictures from the desk, the
   context gauge, answers coming in as if typed, `/` completion, screen
   reader support in the pane (gpui-kit exposes no semantics yet), and the
   ideas in the research brief.

**Open for you (from the R1 review):**
- **Pick: keep blank input in the box (R1-B).** If removing stray control
  characters leaves no text or pictures, send nothing, without a new error?
- **Pick: cap history held in desk memory (R1-D), waiting for spike speed
  measurements.** Reload older messages on scroll, allowing one giant record
  up to 300 ms, or fund an incremental parser, or revisit a separate program?
  The memory cap is not chosen yet and the limits are not proven.
- **Pick: keep the phone's send and formatting moves after the spike (R1-F).**
  Keep that order, or allow those pure refactors alongside the measurement?
- **Pick: preserve the phone's refusals (R1-G), if the spike finds missing
  error details.** Require structured errors before desk replies ship, or
  accept unsure sends whose drafts do not return? Decide after the probe.

Unclear identity already keeps the draft, and an unsure send already stays
pending: those are settled phone rules, not new choices. Decision 7 still
applies to failed spikes. UI or image failures also need a decision on their
specific repair; changing parser routes alone does not fix them.

**What changes for you:** in the herdr app, on Windows, Linux or a Mac, a
key opens an agent's conversation beside its terminal. It reads as on the
phone: your messages as bubbles, the agent's answers formatted, tool calls
folded into one line you can open, and pictures shown, larger on a click.
New answers appear on their own, and you reply from a box under the chat.
When the agent waits on a prompt, the chat says so and a button takes you to
the terminal. This works for Claude Code and Codex on Linux machines (WSL
included) first, and on Macs once `chat-on-mac` lands. On the phone, nothing
looks different; one release moves its send logic into the shared code, and
stray control characters in a pasted message (a raw Ctrl+C or Escape) are no
longer sent as keys.

## Why

On the desk, reading an agent's answers means scrolling a terminal, and
pictures an agent read or was sent cannot be seen at all. kudde's phone chat
already solves both, and its parsing, matching, reading and send decisions
can be plain Rust with no I/O. Bringing that code to the herdr app as a mod
gives one chat for phone and desk instead of two, and a gallery entry that
shows real pictures, which no chat mod there does today.

## What Changes

- A spike comes first (the isolated core move and commit, tasks 1.1–1.3, come
  before it) and decides the route (decision 7): the mod on
  Windows shows a real Claude Code session from WSL, with a picture,
  updating without a click, measuring whether it can meet the window-thread limits; the spike does not
  have to build a new parser, paged views or a work queue.
- `ChatCore` moves from `kudde-mobile` into `kudde-chat`, with times as
  milliseconds; then the send and pending decisions (`chat.rs`,
  `ChatPending.kt`) and the code-block splitter (`ChatScreen.kt`) move there
  too. The phone keeps its behaviour, apart from stripping control
  characters, and ships this as its own release.
- A new crate, `kudde-desk`, wraps the shared core behind a small JSON
  interface built for `wasm32-unknown-unknown`, converted to JavaScript
  with `wasm2js`.
- A herdr mod, `desk/kudde/`: a chat pane beside an agent's terminal, with
  live updates, folded tool calls, pictures, a reply box, a waiting notice
  that focuses the terminal, and a read-only state after the agent exits.
- A version check against the gallery repository, and a notice when part of
  a log cannot be shown, since gallery copies never update themselves.
- Packaging for the gallery repository, published after `chat-on-mac`.

## Capabilities

### New Capabilities
- `desktop-chat`: the chat as a herdr mod: opening it beside a terminal,
  live updates, what it shows, pictures, replies and their guards, the
  waiting notice, unsupported and unreachable machines, update and drift
  notices, and parity with the phone's core.

### Modified Capabilities
- `chat-view`: sending strips control characters other than newline and
  tab. The rest of the phone's behaviour is unchanged; the shared-core move
  is an implementation change. (Mac support for the phone is in
  `chat-on-mac`.)

## Impact

- Crates: `kudde-chat` gains the core, the send and pending decisions and
  the code-block splitter, with their tests and a `serde` feature;
  `kudde-mobile` keeps only the calls around them; Android's `ChatPending`
  and the splitter call Rust; new `kudde-desk` (cdylib, wasm32 exports).
- Toolchain and CI: the wasm32 target, Binaryen with pinned lowering flags,
  and a pinned `qjs` to run the converted core's tests (decision 4); a
  weekly job that opens an issue when Claude Code or Codex ships a version
  with no recorded fixture.
- New `desk/kudde/` mod folder and `scripts/build-desk.sh`; the generated
  `core.js` is built, never committed here. A new public repository for the
  gallery copy.
- herdr-core and herdr app APIs used: `exec(..., {on})`, `hc get`,
  `hc send --if`, `agents()`, `machines()`, `image()`, `fs/promises` and
  `Buffer` under the app's folder, `fetch` for the version check. The send-result probe must establish whether existing error details suffice
  before building the adapter; a missing distinction returns to the owner.
  A 1.x issue asks for an `--if done` guard.
- Desk cost: one `hc` call plus one remote command per read; about every
  second while an agent works or just finished, every 5 seconds otherwise
  while the pane exists.
- Estimate: 1–2 measurement sessions and owner review, then provisionally
  10–14 sessions; `chat-on-mac` 2–3 more. Added work for retention limits,
  incremental parsing or structured errors remains unestimated until the
  spike and owner choices. Confidence: low.
