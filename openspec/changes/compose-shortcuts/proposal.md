# Proposal: your text keys above the chat's message box

## Plain summary

This is the small one. It needs `key-bar-editor` first, because the texts
are the text keys made there.

**Decisions for the owner** (my pick first in each; nothing is settled yet):

1. **What a tap does.** *It puts the text in the message box. It does not
   send.* The row sits where your thumb goes to start typing, and one saved
   text may be `/clear`; filling the box costs one more tap and cannot go
   wrong. It also leaves the chat's send logic alone, which `desktop-chat`
   is moving into shared code. The other option: a text key with "Press
   Enter after" sends at once when the box is empty.
2. **Which texts.** *The same text keys as the terminal's bar, all of them.*
   You make a text once and it serves both views. The other option is a
   separate list for the chat.
3. **When the row shows.** *Whenever the message box shows and you have at
   least one text key*, with a switch on the Keys screen to hide it. It
   costs one line of chat, about 48 dp. The other option is to show it only
   while the keyboard is open, which makes "tap a text, tap send" need the
   keyboard first.
4. **`/` completion (#41) is not part of this.** It needs the agent's own
   command list, which the phone cannot read yet. This row is the manual
   stand-in: save the commands you use. #41 stays open.

**Texts on screen**, each needing the owner's approval:

| Where | Text |
|---|---|
| Keys screen, switch | Show text keys in the chat |
| Its detail line | A tap puts the text in the message box. It does not send. |
| Spoken name of a key in the chat's row | `%1$s. Puts %2$s in the message` |

The keys in the row show the labels you gave them.

**What changes for you:** once you have made a text key, the chat shows your
text keys as one row above the message box. Tap `clear` and `/clear` stands
in the box; tap send. Tap `yes` after a half-typed sentence and it is added
at the end. With no text keys, the chat looks exactly as it does now.

**New visual element, for sign-off:** the row uses the look of a key from
the terminal's bar (raised, the same corner, the same type), since it is
the same key. One line, scrolling sideways when the keys do not fit,
aligned with the message box. A render comes before it is built (task 3.1).

## Why

The owner's remark on Herdr's key bar: "I think we can learn from that for
the chat message compose screen too." Answering an agent from the phone is
mostly a handful of texts: yes, continue, run the tests, `/compact`. The
chat has no way to send those without typing them
(`research/compose-shortcuts-2026-11.md`). Text keys solve the same need in
the terminal; showing them at the message box makes one feature of two.

An update keeps your theme, key bar and saved texts. The new switch starts
on if it has no saved value; an existing off choice stays off. Invalid text
records are excluded by the same control-character validation as terminal
text keys. Their Enter switch never inserts an Enter or sends from this row.

## What Changes

- The chat draws a row of the person's text keys above the message box.
- A tap appends the key's text to the draft.
- A switch on the Keys screen hides the row.

Not in this change: `/` completion (#41), sending from the row, keys such as
escape in the chat, a stop button, and any change to how a message is sent,
guarded or followed.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `chat-view`: one added requirement, "Text keys show above the message
  box".

## Impact

- Android only: `ui/chat/ChatScreen.kt` (the row), `ui/chat/ChatState.kt`
  (appending to the draft), the Keys screen (one switch), one preference.
  No Rust, no UniFFI, no herdr-core call.
- Needs `key-bar-editor` groups 3 and 5 (the text keys and the Keys screen).
- `desktop-chat` also edits `ChatScreen.kt` (its code-block splitter moves
  out); the two should not be built in the same run.
- Estimate: 2 builder runs, one sign-off stop, one short phone check.
  Confidence: high.
