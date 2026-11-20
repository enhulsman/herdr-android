# Tasks: a key bar you can edit

Tests come first in every group. Each task is one builder run. Check
commands run from the repository root unless they start with `./gradlew`,
which runs from `android/`. Marks: **[owner]** needs the owner's eyes or
answer; **[phone]** needs a phone. Tasks without a mark can run unattended.

These are complete instructions, not checked-off implementations. Tasks
1.1 and 3.1–3.2 are **[owner]** gated on decisions 1–4 and 6 (row model,
standard layout, key set, text behaviour and storage scope). Tasks 2.1–2.2
wait for decision 3; 2.3 for decisions 3–4. Once those answers are recorded,
each task below is self-contained with a check command. Group 4 waits for
1.2, and group 5 for approved texts and the signed-off render. Do not resolve
those decisions merely to let an unattended builder start.

## 1. Records

- [ ] 1.1 **[owner: decisions 1–4 and 6]** In `openspec/changes/android-client-v1/specs/terminal-input/spec.md`, requirement "A keys row for keys the phone keyboard lacks": the first sentence becomes "While the phone types, the app SHALL show the key bar the person arranged (`key-bar` spec), every row always; a fresh install shows two rows: esc, tab, shift+tab, ctrl and alt (sticky), arrows, enter, ctrl+c, symbols such as `|`, `~`, `/`, `-`, a keyboard key and the compose key." The rest stays. Check: `openspec validate android-client-v1 --strict && openspec validate key-bar-editor --strict`
- [ ] 1.2 **[owner]** Decisions 1 to 7 of the proposal answered and the texts in `design.md` approved; record the answers in the proposal's summary

## 2. Keys the encoder must learn (Rust)

- [ ] 2.1 **[owner: decision 3]** Tests first: in `crates/kudde-mobile/src/vt/keys.rs`, home, end, page up, page down, delete and f1 to f12 encode for a fresh terminal (f5 is `ESC [ 1 5 ~`; the others as the engine writes them, recorded in the test), and home follows the program's cursor-key mode; in `crates/kudde-mobile/src/keys_row.rs`, each new key takes a held ctrl or alt and releases it, ctrl + letter works for a to z and leaves nothing held, and a ctrl + letter with any other character sends nothing. Check: `cargo test --locked --features kudde-mobile/vt,kudde-mobile/spike -p kudde-mobile keys`
- [ ] 2.2 **[owner: decision 3; after 2.1]** Key codes in `vt/ffi.rs` from the pinned headers; `keys_row::Key` and `RowKey` variants; the encoder's mapping; `RowKeyTap` variants and their mapping in `lib.rs`. Check: as 2.1, plus `cargo clippy --locked --all-targets --features kudde-mobile/vt,kudde-mobile/spike -- -D warnings`
- [ ] 2.3 **[owner: decisions 3–4]** Tests first in `crates/kudde-mobile/src/hub/tests/term.rs` with the fake core: valid text with held ctrl/alt sends unmodified characters and clears both; optional Enter is last; empty and >200-code-point text with Enter send nothing; exhaust C0/C1 plus U+2028/U+2029 rejection. Barrier-controlled tests: lose connection before dequeue; fail after accepted prefix; fail between text and Enter; uncertain write outcome; same-connection reattach; view close or terminal switch before dequeue; offline tap; receiver delayed by one second with the same live attachment (nothing sent); expiry after a prefix (no suffix or Enter); two healthy taps delivered twice in order without interleaving. Assert no suffix, replay, duplicate or Enter reaches any later epoch, attach or other terminal. Then `TerminalView::type_plain` and one bounded command carrying text, boolean and captured machine/terminal/epoch/attach identity; generation invalidation on every reattach and closure, pre-write checks and no retries. Complete any normal watching-to-typing takeover before accepting activation. Check: `cargo test --locked --features kudde-mobile/vt,kudde-mobile/spike -p kudde-mobile`

## 3. The bar's model and storage (Kotlin, no screen)

- [ ] 3.1 **[owner: decisions 1–4 and 6]** Tests first, `KeyBarTest`: the standard bar equals today's two rows key for key; places per row with a text key counting two; every rule of "A Keys screen arranges the bar" as model functions (left, right, row up, row down, the new third row, add to the lowest row with room, no room, remove, the vanishing empty row, the keyboard key that stays); reset keeps text keys; text key rules (label 1 to 6 after trimming, text 1 to 200, one line, reject every C0/C1 code point plus U+2028/U+2029, Unicode code-point length shared with Rust, Enter as a boolean only, at most 24, edit keeps the place, delete removes from bar and list); ctrl + letter (a to z, the same letter twice). Then `ui/keys/KeyBar.kt`. Check: `./gradlew :app:testDebugUnitTest --tests '*KeyBarTest*'`
- [ ] 3.2 **[owner: decisions 1–4 and 6; after 3.1]** Tests first, `KeyBarSettingTest`: a round trip; every repair of "The arrangement is kept on the phone" (unreadable data, another version number, an unknown key, a key twice, a tenth place, a fourth row, an empty row, no keyboard key with and without room); a value of another type in the file; absent preference upgrades to the exact standard bar and no texts while contrast/theme/appearance stay unchanged; later updates preserve valid rows, texts and Enter flags; invalid label/length/control text/Enter type drops the record and its references before row repair, never strips text into a changed command. Then `KeyBarSetting` on the `settings` preferences file (design D7) and its instance on the app. Check: `./gradlew :app:testDebugUnitTest --tests '*KeyBar*'`

## 4. The bar in the terminal (after 1.2)

- [ ] 4.1 Tests first: the bar drawn from the standard model matches every existing terminal golden unchanged; a spoken name for every key on offer, each read once (`KeyNamesTest`, `ReadOnceTest`); a text key is two places wide and shows a six-character label whole at twice the font size (golden); the phone grid leaves room for one, two and three rows (`TerminalLayoutTest`); a tap on a text key captures its originating session and invokes one `type_plain` activation with its text and Enter choice; a terminal switch or view close invalidates pending activations; a tap before typing takeover completes sends nothing; escape's long press interrupts from any place. Then the keys row reads the model, `KEY_LINES` goes, the reserved height follows the rows. Check: `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug`
- [ ] 4.2 The four-way arrow icon in the line style of the existing icons, as a vector; a golden of a bar that holds the arrows key
- [ ] 4.3 Tests first, with injected touches: 50 dp down gives two downs; 30 dp down then 30 dp up gives down, up; no travel gives nothing; 30 dp right with 10 dp down gives one right; a held ctrl goes with the first arrow only; the four screen reader actions each send one arrow. Then the arrows key. Check: `./gradlew :app:testDebugUnitTest --tests '*Arrows*'`

## 5. The Keys screen (after 1.2)

- [ ] 5.1 Render the Keys screen in `design/renders/screens.html`, graphite and paper: the enlarged, horizontally scrollable selection surface with a key selected (all targets at least 48 x 48 dp), the buttons, the lists, the text key dialog, the ctrl + letter dialog, the reset confirm, a one-row and a three-row bar, a bar with text keys and the arrows key (design D8); checkpoints in `design/checkpoints/`
- [ ] 5.2 **[owner]** Sign off the render and the arrows icon
- [ ] 5.3 Tests first: a tap in the picture selects and sends nothing; each button calls its model function and is unavailable when the model says so; the lists show exactly the keys not in the bar; adding selects the new key; the dialogs' errors; reset asks first; every selectable key (including the keyboard key, text keys and add-list entries), list row and action button has a non-overlapping target at least 48 dp wide and high; measure nine-place rows at 360 and 412 dp and 1x/2x fonts, horizontally scroll every target into view and inject taps just inside both sides of every adjacent boundary to prove exactly the intended key is selected and no terminal call occurs; a key in the picture reads its spoken name and "selected" and offers the five move and remove actions; goldens in graphite, paper and at twice the font size. Then the screen, `Screen.Keys` in `MachineFlows`, the row on Machines with its count text, strings. Check: `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug`
- [ ] 5.4 **[phone]** Dragging a key in the picture: a long press lifts it, a drop inserts it at that place under the model's rules, a drop outside puts it back. Unit tests on the drop-to-place mapping first. Built only after 5.3 is on a phone; judged there, and dropped without loss if it is poor

## 6. Review and phone check

- [ ] 6.1 `openspec validate key-bar-editor --strict`; the full suites: `cargo test --locked --features kudde-mobile/vt,kudde-mobile/spike` and `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug`
- [ ] 6.2 **[phone]** Debug build on the test phone, a staged agent and a shell: a one-row and a three-row bar (the terminal's size follows, the desk gets its size back on leaving); page up and home in a pager; `^R` at a shell; a text key with and without Enter into a staged agent; the arrows key on an agent's menu (is 24 dp right?), with touch feedback on and off; the Keys screen at twice the font size; restart keeps the bar
- [ ] 6.3 **[owner]** TalkBack by ear: the keys' names in the terminal, a text key, the arrows key's actions, moving a key on the Keys screen
- [ ] 6.4 **[owner]** Records, shown verbatim before they land: a `decisions.md` entry, the README's keys line, `design-tokens.json` (`components.keysRow` as the standard bar, an entry for the Keys screen), `design-brief.md`, and the closing note for issue #30
