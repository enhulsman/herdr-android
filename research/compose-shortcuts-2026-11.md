# Research: shortcuts on the chat's message box (2026-11-20)

For the change `compose-shortcuts`. Read-only: code and specs in this
repository and public documentation. Nothing was built or run.

## What kudde has today

- The chat's message box is one rounded row: a `+` that attaches photos, a
  text field with the hint "Message" (up to six lines), and a send button
  (`android/app/src/main/kotlin/dev/kudde/android/ui/chat/ChatScreen.kt:557-589`).
  The draft is a plain string kept per terminal while the app runs
  (`ui/chat/ChatState.kt:39`, chat-view spec "A draft is kept for each
  terminal while the app runs").
- Sending is guarded: only to the agent whose log shows, only while it is
  idle, done or working, with refusals that keep the draft
  (`openspec/specs/chat-view/spec.md:356-369`).
- A message that starts with `/` or `!` is sent as typed, and a `/` command
  that may have opened a dialog holds further sends
  (`openspec/specs/chat-view/spec.md:456-477`).
- Nothing suggests commands or texts. `/` completion is issue #41, deferred
  twice: there is no source for an agent's command list on the phone, and a
  pasted `/` command once opened a different dialog than the typed one
  (`research/chat-view-spike-2026-11.md`, "Two hazards").
- The terminal has a compose sheet of its own, which pastes without Enter
  (`ui/terminal/ComposeSheet.kt:49-53`). It is not part of this change.

## Elsewhere

- **Herdr's own Android app** has no chat. Its compose box sits over the
  terminal and its custom keys go to the terminal, not into the draft.
- **Moshi (iOS):** shortcuts that type a text such as `/clear`, with an
  optional Enter; a tab of one-tap agent commands.
  <https://getmoshi.app/docs/keyboard>.
- **Termius:** tapping a snippet runs it; a long press inserts it instead.
  <https://docs.termius.com/terminal/mobile-terminal>.
- **A herdr web client:** quick replies read from a file, sent as written.
  <https://colliepwa.dev/docs/configure>.
- **Agent apps with a command list:** one phone client forwards the agent's
  commands and completes them with fuzzy search
  (<https://happy.engineering/docs/features>); a desktop agent app opens a
  picker on `/` (<https://docs.github.com/en/copilot/reference/github-copilot-app-reference/slash-commands>).
  Claude Code's own remote sessions accept typed commands but offer no
  completion in the phone app
  (<https://code.claude.com/docs/en/remote-control>).
- **Where a command list could come from** (for #41, not for this change):
  Claude Code lists its commands to programs that start it through its SDK
  (<https://code.claude.com/docs/en/agent-sdk/slash-commands>), which kudde
  does not; otherwise the list would have to be read from the agent's
  folders on the machine.
- **Chat apps:** picking a suggested command in Telegram sends it at once
  (<https://core.telegram.org/bots/features>); Android's own guidance puts
  suggestion chips directly above or below the field they fill
  (<https://developer.android.com/develop/ui/compose/components/chip>).

## What this change takes, and why

- **The person's own text keys, shown as a row above the message box.** They
  are defined once, in the Keys screen that `key-bar-editor` adds, and serve
  both the terminal and the chat. No command list is needed, so it works for
  every agent today.
- **A tap fills the box; it does not send.** The row sits where the thumb
  goes to start typing, and one saved text is `/clear`. Filling the box costs
  one more tap and cannot go wrong. It also leaves the send path untouched,
  which another open change is moving into shared code.
- **Nothing shows until a text key exists**, so the chat looks as it does
  now for everyone else.
- **Not taken:** `/` completion (#41 stays open; its suggestions could use
  this row later), keys such as esc in the chat, sending from the row.

## Spec-fix verification (2026-11-20)

No independent review finding targets this change. Its shared-data boundary
must still agree with key-bar-editor. `ui/chat/ChatState.kt:39` holds a draft
per terminal, and the proposal adds no terminal-delivery call. The added
requirement and scenarios state update preservation of themes, appearance,
keys and texts, default-on only for absent/invalid chat-row preferences,
preservation of valid off, and exclusion of invalid text records. The final
Enter flag is ignored; terminal connection delivery rules are never invoked
by a shortcut tap. Confidence: high for this specification boundary; runtime
behaviour is unimplemented and untested. Task 2.1 includes these acceptance
cases and explicitly waits for owner decisions 1–3. No on-screen wording
was added or changed; the three exact texts in the proposal still require
approval.
