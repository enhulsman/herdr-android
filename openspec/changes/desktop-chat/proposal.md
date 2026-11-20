# Proposal: kudde's chat in the herdr desktop app

## Plain summary

**Decisions** (Ezra, 2026-11-19: decisions 1–8 stand, after the council
review; decision 7 settled 2026-11-20 after the spike):
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
4. **One new build tool: a pinned QuickJS (`qjs`)** in CI, to run the desk's
   JavaScript and its comparison with the phone's Rust. The wasm32 target
   and Binaryen, approved for the converted route, are no longer needed
   (decision 7).
5. **Published to the herdr gallery as "kudde"**, from a separate small
   public repository that holds only the mod; this repository's release
   copies it there. A survey of the gallery found deleted repositories still
   listed, so no test entry is published first. The listing text is shown
   to Ezra verbatim, and submitting waits for his OK.
6. **The phone's send logic moves into the shared Rust code**, with the chat
   core, and ships as its own phone release. With decision 7 the desk cannot
   call it, but it becomes the one reference the desk's copy is checked
   against, instead of rules spread over four files in Kotlin and Rust.
7. **Locked (2026-11-20): the desk carries a JavaScript copy of the chat
   rules.** The spike measured the phone's Rust converted to JavaScript at
   10 times the limits or more (3 s to open a chat, 0.6 s per update). A
   JavaScript reader written for the test gave exactly the Rust's result on
   194 inputs, opened 2 MB in about 0.35 s and took 5 ms per update. Nothing
   is installed; the cost is a second, JavaScript copy of the chat rules
   (about 2,400 lines of Rust, much of it shell text shared as is; the
   spike's copy of the reader alone is 435 lines), kept equal by an exact
   comparison in CI. The program on each agent machine is
   dropped.
8. **Later, each as its own issue:** attaching pictures from the desk, the
   context gauge, answers coming in as if typed, `/` completion, screen
   reader support in the pane (gpui-kit exposes no semantics yet), and the
   ideas in the research brief.

**Answered (Ezra, 2026-11-20):**
- **N1: the phone keeps its Markdown reader; the desk gets a small function
  that only separates code blocks.** Decision 6 had named a "code-block
  splitter" to move into Rust, but the phone has none: it has a full
  Markdown reader (headings, lists, tables, quotes, code), which stays.
- **R1-B: blank input stays in the box.** If removing stray control
  characters leaves no text or pictures, nothing is sent, without a new
  error.
- **R1-G, in case the send probe needs it: desk replies ship.** If herdr
  does not tell the desk why it refused a message (for example, the agent
  just started working), the desk does not wait for herdr-core to say so;
  it recognises herdr's refusal text wherever it can, and otherwise the
  bubble stays, reads "Not in the conversation yet", and you retype the
  message.

Nothing is open for you on this change until the in-app session.

No longer asked: R1-D (how to keep the desk fast: updates are well inside
the limits, 5 ms; opening, older messages and long catch-ups are split into
short steps by the build; if the app's timings miss anyway, it comes back to
you) and R1-F (the spike is done, so the phone's send
move can start now). Unclear identity already keeps the draft, and an unsure
send already stays pending: those are settled phone rules, not new choices.

**What changes for you:** in the herdr app, on Windows, Linux or a Mac, a
key opens an agent's conversation beside its terminal. It reads as on the
phone: your messages as bubbles, the agent's answers formatted, tool calls
folded into one line you can open, and pictures shown, larger on a click.
New answers appear on their own, and you reply from a box under the chat.
When the agent waits on a prompt, the chat says so and a button takes you to
the terminal. This works for Claude Code and Codex on Linux machines (WSL
included) first, and on Macs once `chat-on-mac` lands. Nothing is installed
beyond the mod folder. On the phone, nothing looks different; one release
moves its send logic into the shared code, and stray control characters in
a pasted message (a raw Ctrl+C or Escape) are no longer sent as keys.

## Why

On the desk, reading an agent's answers means scrolling a terminal, and
pictures an agent read or was sent cannot be seen at all. kudde's phone chat
already solves both, and its parsing, matching, reading and send decisions
are plain Rust with no I/O. The herdr app's mods run JavaScript and cannot
run that Rust fast enough, so the desk carries a JavaScript copy of the same
rules, held equal to the Rust by an exact comparison. That gives one set of
chat rules for phone and desk, and a gallery entry that shows real pictures,
which no chat mod there does today.

## What Changes

- The spike is done (decision 7). What it could not do without the app,
  with Ezra at the screen, comes next: the UI and picture probes, a live
  update, the reader's timings inside the app, and what a send returns.
- `ChatCore` moved from `kudde-mobile` into `kudde-chat`, with times as
  milliseconds; the send and pending decisions (`chat.rs`, `ChatPending.kt`)
  move there too. The phone keeps its behaviour, apart from stripping
  control characters, and ships this as its own release.
- The desk's JavaScript copy of the chat rules in `desk/kudde/chat/`: the
  reader, the lookup decision, the send decisions, the script texts and a
  fence split for code blocks.
- `kudde-chat-oracle`, a small native program used only by tests, and a
  parity suite in CI that compares the JavaScript with the Rust exactly on
  every fixture and recorded case.
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
  notices, and parity with the phone's rules.

### Modified Capabilities
- `chat-view`: sending strips control characters other than newline and
  tab. The rest of the phone's behaviour is unchanged; the shared-core move
  is an implementation change. (Mac support for the phone is in
  `chat-on-mac`.)

## Impact

- Crates: `kudde-chat` gains the core (moved), the send and pending
  decisions, with their tests, a `serde` feature for the oracle, and
  configurable budgets, a trim and a view revision, with the phone's values
  as defaults;
  `kudde-mobile` keeps only the calls around them; Android's `ChatPending`
  calls Rust; new `kudde-chat-oracle` (native, test-only, never shipped).
- Toolchain and CI: a pinned `qjs` for the parity suite and the mod's tests
  (decision 4); a weekly job that opens an issue when Claude Code or Codex
  ships a version with no recorded fixture.
- New `desk/kudde/` mod folder, handwritten, nothing generated. A new
  public repository for the gallery copy.
- herdr-core and herdr app APIs used: `exec(..., {on})`, `hc get`,
  `hc send --if`, `agents()`, `machines()`, `image()`, `fs/promises` and
  `Buffer` under the app's folder, `fetch` for the version check. The
  send-result probe must establish whether existing error details suffice
  before building the adapter; a missing distinction returns to the owner.
  A 1.x issue asks for an `--if done` guard.
- Desk cost: one `hc` call plus one remote command per read; about every
  second while an agent works or just finished, every 5 seconds otherwise
  while the pane exists.
- Maintenance: every change to the log rules lands in Rust and in the
  JavaScript, in the same pull request, or CI fails.
- Estimate: one in-app session with Ezra for the probes, then provisionally
  10–14 sessions (the wasm bridge drops out; the JavaScript send copy and
  the parity suite come in); `chat-on-mac` 2–3 more. Confidence: low.
