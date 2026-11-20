# Tasks: your text keys above the chat's message box

Starts after `key-bar-editor` groups 3 and 5 (the text keys and the Keys
screen) have landed. Tests come first. Each task is one builder run. Check
commands starting with `./gradlew` run from `android/`. Marks: **[owner]**
needs the owner's eyes or answer; **[phone]** needs a phone.

Task 2.1 is **[owner]** gated on decisions 1–3 (append behaviour, shared text source and visibility setting), in addition to the key-bar dependencies. No answer is assumed.

## 1. Records

- [ ] 1.1 **[owner]** Decisions 1 to 4 of the proposal answered and its three texts approved; record the answers in the proposal's summary

## 2. The draft and the setting (no screen)

- [ ] 2.1 **[owner: decisions 1–3]** Tests first: adding a text to a draft (empty; ending in a letter; ending in a space; ending in a line break; a text that starts with `/` after other words is added as it is); which keys the row gets (none made; the switch off; the order they were made in); the switch's stored value, on by default, missing/unknown/another type reads as on, a valid off survives an update; upgrade with or without text keys leaves theme/appearance/bar/texts unchanged; invalid stored C0/C1/U+2028/U+2029 text is excluded, Enter flags are ignored, and no shortcut calls terminal delivery. Then the function on `ChatState` and the setting on the `settings` preferences file. Check: `./gradlew :app:testDebugUnitTest --tests '*Chat*'`

## 3. The row (after 1.1)

- [ ] 3.1 Render the chat with the row in `design/renders/screens.html`, graphite and paper: three keys, twelve keys (scrolled), with a draft, with the keyboard open; checkpoint in `design/checkpoints/`
- [ ] 3.2 **[owner]** Sign off the render
- [ ] 3.3 Tests first: the row shows only with the message box, with text keys and with the switch on; a tap changes the draft and does not call send; the keyboard's state is untouched; each key is at least 48 dp high as a target; the spoken name, read once; goldens in graphite and paper, at twice the font size, and with twelve keys; every existing chat golden unchanged. Then the row in `ChatScreen.kt` and the switch with its detail line on the Keys screen. Check: `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug`

## 4. Review and phone check

- [ ] 4.1 `openspec validate compose-shortcuts --strict`; `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug`
- [ ] 4.2 **[phone]** Debug build on the test phone, a staged agent: tap a key and send; tap a key after typed words; a `/` command from a key and its waiting line; the row with the keyboard open and closed (the chat still follows the bottom); the switch off
- [ ] 4.3 **[owner]** Records, shown verbatim before they land: the README's chat line and a note on issue #41 that it stays open and can reuse this row
