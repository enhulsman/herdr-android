# M2 phone check

The checklist for M2 (live terminal and keyboard), task M2.5 plus a first look
at everything M2.1-M2.4 built. Install the debug build from `m2-terminal`
("herdr debug"); it updates the debug app already on the phone and keeps its
machine list and key.

Write what you see on each `Result:` line, in plain words, without real host
names, addresses or project names (this file is tracked). Opinions are as
welcome as failures: mark them "Opinion:". The choices made while you were away
are in section 12.

Best done with a Claude Code session on the workstation that you can make ask
for a permission (any small task that runs a command), and a shell terminal in
herdr next to it.

## 1. Install

- [ ] The debug APK installs as an update to "herdr debug" (no uninstall).
- [ ] The agent list looks as before; the machine connects.

Result:

## 2. Opening and watching (renders 3)

- [ ] Tap an agent: the terminal opens, not the old read-only sheet.
- [ ] Text has a margin left and right (no text against the screen edge), and a
  small one at the top.
- [ ] The desk's grid fills the width; below it, a dashed line and an empty area.
- [ ] The chip at the bottom: "Watching at desk size CxR · tap to type", with
  the desk's real size.
- [ ] While you only watch, the desk's window does not change size or redraw.
- [ ] Pinch out: text grows, a drag pans; pinch back returns to fit-width.
- [ ] Colours, bold, dim text, the Claude Code boxes and the diff colours look
  like the desk. Accents and emoji (try `echo café 👩‍💻 🇳🇱` in a shell)
  draw whole.
- [ ] The cursor looks right (bar, block or underline, as the program sets it).

Result:

## 3. Typing at phone size (render 4)

- [ ] Tap the terminal: the keyboard opens; the terminal still watches.
- [ ] The first key: the terminal reflows to about 53 columns; the tag reads
  "phone size 53xR"; the desk's window now shows the narrower terminal.
- [ ] In a shell: type `lsx`, backspace, enter: it runs `ls`.
- [ ] The keyboard shows no suggestions and does not autocorrect.

Result: Typing works, also into a Claude Code session from the phone (first round, 2026-11-02).

## 4. Keyboard hidden while typing (render 4b)

- [ ] Hide the keyboard: after it settles, the terminal grows to the full
  height once (one redraw, not several); the keys row stays.
- [ ] Show it again: it shrinks back once.
- [ ] Scroll up in Claude Code afterwards: is there duplicated history from
  the redraws? How much does it bother you?

Result: Resizes cleanly every time. FAIL: the terminal reacts to no touch at all (cannot scroll).

## 5. Keys row and shelf

- [ ] On a Claude Code permission prompt: down, down, enter (enter is in the
  shelf: swipe up on the keys row) picks option 3; esc backs out.
- [ ] ctrl stays lit after a tap and applies to the next key only; tap ctrl,
  then c in a shell: the running command stops.
- [ ] alt behaves the same way.
- [ ] The shelf has `^C ⏎ | ~ / - _ \ ` $` and each types what it shows.
- [ ] Hold esc on a working agent: it stops and the phone shows a short
  report ("Stopped", "Still working", ...).

Result: Permission prompt answered with down, down, enter. Opinion: the shelf was not found without being told; enter belongs in the main row.

## 6. Compose (render 5)

- [ ] The compose key opens a sheet with the full keyboard (suggestions,
  swipe typing, dictation).
- [ ] Dictate a long prompt, tap Paste: it appears in Claude Code's input box
  and is NOT sent; enter is yours.
- [ ] Cancel sends nothing.

Result: Dictated text lands in the input box, not sent. Pass.

## 7. Back at the desk

- [ ] After typing on the phone, type at the desk: the desk gets its size back,
  and the phone goes back to watching at desk size without garbled lines.
- [ ] Resize the desk window while output streams: the phone follows cleanly.

Result: The desk takes its size back at once. Opinion: watching a wide desk terminal leaves a large empty area below it; handle differently (proposal: fill it with older output above the desk screen).

## 8. Scrollback, Live and copy

- [ ] Flick up while an agent works: the view stays put, "Live" shows; tap it
  to return to the bottom.
- [ ] Long-press: a selection layer with the text on screen; select an error
  line and copy it; paste somewhere: plain text, no colour codes.
- [ ] The desk's screen did not move while you scrolled or selected.

Result:

## 9. Between agents

- [ ] With another agent needing you: the red strip under the title says
  "1 more needs you · next: <name>, <kind>"; tapping it opens that agent.
- [ ] Swipe up on the title bar: the next agent that needs you (or finished
  unseen) opens, with a light buzz; at the end, a stronger buzz and nothing
  opens. Swipe down goes back.
- [ ] Back from the terminal returns to the list, and the desk no longer
  follows the phone's size (the phone let go).

Result:

## 10. Instant open and reconnect

- [ ] Reopen an agent you looked at a minute ago: its last screen shows at once
  (no blank frame), then the live one replaces it.
- [ ] Leave the app for more than 10 s with a terminal open, come back: it
  reconnects and the terminal comes back by itself.
- [ ] After more than 30 minutes since the last unlock or fingerprint: opening
  asks once (as before) and nothing breaks.

Result:

## 11. Speed

- [ ] Run something that prints fast (`seq 1 100000` in a shell, or a long
  Claude Code answer) while watching: does it stay smooth, and does the phone
  get warm? (The measured p95 frame time comes later.)

Result: Smooth and fast; responsiveness as expected over SSH. Text size and margins fine.

## 12. Choices made while you were away (keep or change)

- [ ] enter and ctrl+c are in the swipe-up shelf, not in the main keys row
  (the renders have neither; the spec lists both in the row).
- [ ] Switching agents puts the keyboard down; the next one opens watching.
- [ ] Copy takes what is on screen; scroll back first for older text.
- [ ] The typing grid sits at the top of its area.
- [ ] The interrupt report shows as a short toast.
- [ ] Wording: "N more need you" for more than one; "Waiting for <machine>",
  "Opening the terminal", "The terminal ended", "Could not open the terminal:
  ... Trying again.", "Pasted into the terminal. Enter is yours."

Result:
