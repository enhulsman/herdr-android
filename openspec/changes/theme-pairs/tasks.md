# Tasks: themes as a light and dark pair

Tests come first in every group. Each task is one builder run. Check
commands run from the repository root unless they start with `./gradlew`,
which runs from `android/`. Marks: **[owner]** needs the owner's eyes or
answer; **[phone]** needs a phone. Tasks without a mark can run unattended.

These are complete task instructions, not completed implementations: leave
checkboxes unchecked until built and checked. Task 2.1 and the graphite/paper
parts of group 3 can run unattended. Tasks 2.2–2.5 are **[owner]** gated on
decisions 4–6 (theme set and contrast behaviour); task 1.1 waits on decisions
1–5. Group 4 waits for 1.2 and approved texts. Group 3 uses existing approved
graphite/paper palettes and does not release new palettes before sign-off.

## 1. Records

- [ ] 1.1 **[owner: decisions 1–5]** Reconcile both retained appearance requirements in `openspec/changes/android-client-v1/specs/appearance/spec.md`: replace "Mac colours, following light and dark mode" with a reference to the selected pair and stored Appearance in this change, keeping desk-derived colours and terminal parity; replace its Evening switch scenario so a phone-mode change affects the app only under Follow the phone. Replace "All six themes (v1.1)" with a reference to the owner-approved theme set here (do not silently drop or adapt win98). M4.3 in its `tasks.md` becomes "Rail at 600 dp and wider (themes: see `theme-pairs`)". Preserve wide-screen and golden requirements. These future edits are outside this spec-fix card's scope. Check: `openspec validate android-client-v1 --strict && openspec validate theme-pairs --strict`
- [ ] 1.2 **[owner]** Decisions 1 to 7 of the proposal answered and the texts in `design.md` approved; record the answers in the proposal's summary

## 2. Theme values (2.1 ready; new themes gated as marked)

- [ ] 2.1 Test first: `ThemeResolveTest` in the Android test sources with a port of the desk's derivation (design D2, 64-bit floats). It asserts that the graphite and paper palettes give exactly the colours `design-tokens.json` records today, and that a theme is light when its background's luminance is above 0.5. Passes on its own. Check: `./gradlew :app:testDebugUnitTest --tests '*ThemeResolveTest*'`
- [ ] 2.2 **[owner: decisions 4–6; after 2.1]** Copy the ink, moss and atom-one-dark palettes from the herdr desk app's public sources into `design-tokens.json` (`colors.palettes`, with source and commit), add graphite's and paper's palettes the same way, and record the port's output as `colors.themes.ink`, `.moss`, `.atom-one-dark` with their `colors.semantic` entries. Extend 2.1 to all five; the spec's ink and atom-one-dark values must come out. Check: as 2.1
- [ ] 2.3 **[owner: decisions 4–6; after 2.1]** Test first: `ThemeTokensTest` covers all five themes. Then `ThemeName` and `Colors.kt` gain the three themes. Check: `./gradlew :app:testDebugUnitTest --tests '*ThemeTokensTest*'`
- [ ] 2.4 **[owner: decisions 4–6; after 2.1]** Test first: for ink, moss and atom-one-dark, muted text reaches 4.5:1 on chrome and on popover; under Higher text contrast `dimText` is the muted colour and nothing else differs; graphite and paper unchanged. Then `kuddeColors`. Record the ratios in `accessibility.checked`. A theme that fails is reported, not adjusted. Check: `./gradlew :app:testDebugUnitTest --tests '*Theme*'`
- [ ] 2.5 **[owner: decisions 4–6; after 2.1]** Swatch goldens for the three themes, as `ThemeSwatchScreenshotTest` does for graphite and paper. Check: `./gradlew :app:recordRoborazziDebug --tests '*ThemeSwatchScreenshotTest*' && ./gradlew :app:verifyRoborazziDebug`
- [ ] 2.6 **[owner]** Sign off the three swatches (the golden images), next to graphite's

## 3. The terminal takes the theme (approved graphite/paper only until sign-off)

- [ ] 3.1 Tests first, Rust (`crates/kudde-mobile/src/vt/`): after a theme is set, a frame's default foreground and background are the theme's; a cell written with ANSI red resolves to the theme's entry 1; palette entry 208 and a 24-bit colour are unchanged; a default background the program set stays after a theme change and gives way to the theme after the program resets it; setting a theme makes the next frame a full one; a theme whose `ansi` is not 16 long is refused. Check: `cargo test --locked --features kudde-mobile/vt,kudde-mobile/spike -p kudde-mobile vt::`
- [ ] 3.2 After 3.1 has failing assertions, implement bindings for `ghostty_terminal_set` and the options 11, 12 and 14 and the default-palette read, regenerated from the pinned headers; `Terminal::set_theme`. Check: as 3.1, plus `cargo clippy --locked --all-targets --features kudde-mobile/vt,kudde-mobile/spike -- -D warnings`
- [ ] 3.3 Test first (`crates/kudde-mobile/src/hub/tests/term.rs`, with its fake core): a theme given at open and a theme set later show in the next frame's default colours, a reattach into a fresh engine keeps the theme, and no message is written to the link for a theme. Then the `TerminalTheme` record, the `open_terminal` argument, `TerminalView::set_theme` and the channel command. Check: `cargo test --locked --features kudde-mobile/vt,kudde-mobile/spike -p kudde-mobile`
- [ ] 3.4 Tests first (Android, `TerminalSessionTest`, `TerminalCacheTest`): the session opens with the showing theme and sets it again when the theme changes; a kept frame whose default colours differ from the showing theme's is not shown. Then `TerminalSession` and `TerminalRoute`. Check: `./gradlew :app:testDebugUnitTest --tests '*Terminal*'`
- [ ] 3.5 After 3.1–3.4, add a terminal golden in paper (`terminal-watch-paper`), from the existing fixture drawn with paper's defaults; existing goldens unchanged. Check: `./gradlew :app:verifyRoborazziDebug`
- [ ] 3.6 **[owner]** Sign off the paper terminal golden

## 4. Choosing a theme (after 1.2)

- [ ] 4.1 Tests first: the theme setting (defaults, a stored choice survives a new instance, an unknown name and a theme of the other mode read as the default, another type in the file never crashes); the lists per mode built from the themes' own mode; the Machines row's three detail texts; the stored appearance survives a new instance/process for all three choices,
  including system/dark while the phone is dark and system/light while it is
  light; missing, unknown and non-string appearance defaults to system; update
  from current app gives graphite/paper/system without changing contrast or
  keys, and later valid preferences survive; fake night-mode adapter maps
  fixed choices to YES/NO and follows effective device configuration for
  system (manual and scheduled), never inferring selection from configuration.
  Verify separate device-mode observation before implementation. Then `ThemeSetting`, the interface and its Android implementation, and `KuddeTheme` following both settings. Check: `./gradlew :app:testDebugUnitTest --tests '*Theme*'`
- [ ] 4.2 Render the Theme screen and the Machines row in `design/renders/screens.html`, in graphite and paper, with the swatch from design D8 and the approved texts; checkpoints in `design/checkpoints/`
- [ ] 4.3 **[owner]** Sign off the render, the swatch included
- [ ] 4.4 Tests first: every row is at least 48 dp high; the chosen row is selected for a screen reader and the swatch is silent; a tap applies at once; picking for the other mode changes no colour on screen; goldens in graphite and paper, and at twice the font size. Then the Theme screen, `Screen.Theme` in `MachineFlows`, the row on Machines, strings. Check: `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug`
- [ ] 4.5 Tests first: light-to-dark via phone while following and dark-to-light via appearance choice in place; both status- and navigation-bar icon flags match new chrome; terminal session identity and scroll offset (40 rows) remain, colours change within one second, and fake-core traffic has no attach, detach, resize or input for the change. Test adapter and activity configuration callback, not just Compose colours. Then handle uiMode and classic input view updates in place and explicitly refresh both bars (D4). Check: `./gradlew :app:testDebugUnitTest --tests '*Terminal*' --tests '*Theme*' --tests '*MainActivity*'`

## 5. Review and phone check

- [ ] 5.1 `openspec validate theme-pairs --strict`; the full suites: `cargo test --locked --features kudde-mobile/vt,kudde-mobile/spike` and `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:assembleDebug`
- [ ] 5.2 **[phone]** Debug build on the test phone, a staged agent: each of the five themes in the terminal, the chat and Changes; a terminal open while the phone switches mode (colours change, place kept, the desk's size untouched); Always dark by day and both in-place transition directions (status and navigation icons, keyboard, photo picker, terminal session and scroll offset); Follow the phone after each fixed choice, including manual and scheduled mode changes and a cold start after a device-mode change; Higher text contrast in each new theme; a full-screen agent started on a dark desk read in paper (design D7), with a note on how it reads
- [ ] 5.3 **[owner]** Records, shown verbatim before they land: a `decisions.md` entry (the pair, Appearance, win98, the terminal now themed), the README's themes line, `design-brief.md` (colours table, components, the terminal note), and the wording for issue #28 (themes done, wide layout open)
