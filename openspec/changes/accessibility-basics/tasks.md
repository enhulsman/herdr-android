## 1. Tests first

- [x] 1.1 Spoken screen text: box drawing, block elements and braille marks removed; spaces folded; empty lines dropped; a boxed permission prompt reads as its question and choices
- [x] 1.2 Every key in the keys row has a spoken name; the five symbol keys read dash, slash, tilde, backslash, pipe
- [x] 1.3 Chat announcements: only live "said" rows, each once, in order; nothing from the first read or a read-back; not the person's rows, tools or notes
- [x] 1.4 Screenshot goldens of the terminal screen at font size 2x (title bar and keys row)

## 2. Build

- [x] 2.1 Title bar grows with its text (minimum height kept)
- [x] 2.2 Keys row labels shrink to fit their key at large font sizes
- [x] 2.3 Spoken names for the symbol keys
- [x] 2.4 Terminal node carries the spoken text while a screen reader is on, refreshed at most once a second
- [x] 2.5 Chat announces live answers while a screen reader is on

## 3. Check on the phone

- [ ] 3.1 At 2x font: open an agent, type, answer an alert
- [ ] 3.2 With TalkBack on: the same three paths, in the terminal and the chat
- [ ] 3.3 Restore the phone's font size and TalkBack setting
