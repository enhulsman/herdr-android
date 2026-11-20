# Research: themes as a light and dark pair (2026-11-20)

For the change `theme-pairs` (the themes half of issue #28). Read-only: code
and specs in this repository, the public herdr-app sources, a walkthrough of
Herdr's own Android app on a test phone, and public documentation. Nothing
was built or run.

## What kudde has today

- Two themes, graphite and paper: `ThemeName` in
  `android/app/src/main/kotlin/dev/kudde/android/ui/theme/Colors.kt:13-16`,
  with every colour mirrored from `design-tokens.json` (`colors.themes`,
  line 78) and compared by `ThemeTokensTest`.
- The app follows the phone's mode with those two and nothing else:
  `KuddeTheme` picks graphite when dark, paper when light
  (`ui/theme/Theme.kt:17-24`). There is no choice of theme and no way to stay
  in one mode.
- System bars follow the phone's mode, not the theme
  (`MainActivity.kt:37`, `SystemBarStyle.auto`).
- "Higher text contrast" swaps in replacement values that exist for graphite
  and paper only (`Colors.kt:146-159`, `design-tokens.json`
  `accessibility.followUp`).
- **The live terminal does not take the theme.** The engine is created
  without colours (`crates/kudde-mobile/src/vt/mod.rs:133-136`), each frame
  carries the engine's own default foreground and background
  (`vt/mod.rs:240-241`), and the renderer paints with those
  (`ui/terminal/TerminalRenderer.kt:98`, `:194`). The binding file has no
  call that sets colours (`crates/kudde-mobile/src/vt/ffi.rs:275-380`), and
  the grid code says so: "the phone applies its theme to the engine in M4"
  (`crates/kudde-mobile/src/vt/grid.rs:7-8`). The published screenshot shows
  the result: a black terminal under graphite bars
  (`docs/screenshots/terminal.png`). By the code the same holds in light
  mode: the bars turn to paper and the terminal stays black. That was read,
  not seen on a phone.
  The theme's `ansi`, `termFg` and `selection` values are used by the Changes
  view and the chat only (`ui/changes/ChangesScreen.kt:213-214`,
  `ui/chat/ChatScreen.kt:596`).
- The pinned engine can take a theme: `ghostty_terminal_set` with
  `GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND` (11), `_BACKGROUND` (12),
  `_CURSOR` (13) and `_PALETTE` (14), documented in its `terminal.h` under
  "Color Theme". A program's own colour changes stay on top of these
  defaults.

## What the open spec already promises

`openspec/changes/android-client-v1/specs/appearance/spec.md`:

- "Mac colours, following light and dark mode", with the scenario that the
  terminal background, foreground and 16 ANSI colours equal the desk's.
  Built for the bars and lists; **not built for the terminal** (above).
- "All six themes (v1.1)": graphite, ink, moss, paper, atom-one-dark and
  win98, "with a choice per light and dark mode", no custom theme files. Not
  built (task M4.3 is open). It names no screen, no texts, no rule for which
  theme goes in which mode, and no behaviour for the contrast switch.
- "Wide screens" (the other half of #28) and "Screens stay as signed off".

So the pair itself is already promised. What is missing is everything that
makes it buildable, and the terminal.

## How the herdr desk app models it

Public sources, `herdr-app` at commit `07ba77e` (2026-10-31).

- A theme is one name or a pair: `ThemeChoice::One | Pair { light, dark }`
  (`src/layout.rs:180-183`). The defaults are graphite and paper
  (`src/settings.rs:82-84`).
- `appearance` is `system`, `light` or `dark` (`src/settings.rs:404-411`).
- A theme is light or dark by its own background: luminance above one half
  is light (`src/settings.rs:260-268`). Presets: graphite, ink and moss are
  dark, paper is light; atom-one-dark ships as a file.
- A theme is a small palette: background, foreground, selection, an
  optional cursor, 16 ANSI colours and optional overrides
  (`src/settings.rs:103-114`). Every panel colour is derived from it by
  `resolve()` (`src/settings.rs:306-362`). kudde's graphite and paper tokens
  are that function's output.
- People add their own themes as files on the desk
  (`defaults/AGENTS.md`, "themes/<name>.json").
- **win98 is not a general theme.** `defaults/AGENTS.md` says a theme named
  like a layout "is that layout's own: Settings offers it only there". Its
  file overrides the terminal background to black and the text colour to
  black (`defaults/themes/win98.json`), which only works inside the win98
  layout's grey panels.

## How Herdr's own Android app does it

Version 1.0.7, walked through on a test phone.

- An Appearance choice: System, Dark, Light.
- A terminal theme screen with two lists, ten dark themes and six light
  ones; one is picked in each. Each row shows a small chip in the theme's
  background with six colour dots. The terminal then follows the phone's
  mode.
- Only the terminal takes the theme; lists and menus stay plain dark or
  light. The themes are well-known terminal themes, not the desk's.
- Weak points seen: no preview on a real terminal before leaving the list;
  the plain app can clash with a loud terminal theme.

Not captured, worth a second look if it matters:
1. With the desk dark and the phone on a light theme, is a full-screen
   agent's output still readable? (Programs pick colours for the background
   the desk reports.)
2. Does a theme change reach an open terminal at once?
3. With Appearance on Dark and the phone light, do the keyboard and status
   bar turn dark too?

## Elsewhere

- **Ghostty:** `theme = light:X,dark:Y`; built-in themes come from the
  iTerm2-Color-Schemes collection (MIT).
  <https://ghostty.org/docs/config/reference>,
  <https://ghostty.org/docs/features/theme>,
  <https://github.com/mbadolato/iTerm2-Color-Schemes>.
- **iTerm2:** separate colours for light and dark per profile.
  <https://iterm2.com/documentation-preferences-profiles-colors.html>.
- **Windows Terminal:** `"colorScheme": {"light": ..., "dark": ...}`.
  <https://learn.microsoft.com/en-us/windows/terminal/customize-settings/profile-appearance>.
- **kitty:** `dark-theme.auto.conf` and `light-theme.auto.conf`.
  <https://sw.kovidgoyal.net/kitty/kittens/themes/>.
- **Moshi (iOS):** nine themes grouped dark and light; one theme drives the
  whole app. <https://getmoshi.app/docs/personalization>.
- **What a program sees.** A program learns the terminal's colours by asking
  (OSC 10/11) or by subscribing to changes (mode 2031).
  <https://contour-terminal.org/vt-extensions/color-palette-update-notifications/>.
  In herdr the answer comes from the desk: the phone sends its display
  fields empty so the desk's colours stay
  (`crates/herdr-proto/src/viewer.rs:59-60`, terminal-input spec "Desk
  colours kept").
- **Android:** offer Light, Dark and System default; on Android 12 and later
  set it with `UiModeManager.setApplicationNightMode`, which the system
  remembers and applies to the splash screen and system-drawn parts.
  <https://developer.android.com/develop/ui/views/theming/darktheme>.
- **Contrast:** text 4.5:1, parts of controls 3:1.
  <https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html>,
  <https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html>.

## What this change takes, and why

- **The desk's model as it is:** a theme per mode plus an appearance choice.
  It is what the open spec promised, what herdr people know, and what every
  terminal above converged on.
- **Each theme in its own half, by its background.** The desk's rule. It
  keeps the status bar, the keyboard and system dialogs in step with the
  app, since those follow the mode.
- **The theme reaches the terminal.** Without that a theme choice changes
  the bars around a black terminal. The engine already has the call.
- **The whole app takes the theme**, as on the desk and in the signed-off
  renders, not the terminal alone. The clash seen in the other app comes
  from theming only half.
- **Values are derived, never chosen.** A port of `resolve()` in the tests
  must reproduce the two signed-off themes colour for colour before its
  output for the new ones is trusted.
- **win98 left out**, for the reason above. It is the owner's call.
- **Not taken:** well-known terminal themes beyond the desk's, custom theme
  files on the phone, a font choice. The first is a likely follow-up, since
  the desk's themes give the light half one choice; the mechanism here takes
  any palette in the desk's file shape, and each new palette needs the
  owner's sign-off on its swatch.

## Spec-fix verification (2026-11-20)

All checks here are static, not device tests.

- **Appearance persistence: holds, high confidence.** `MainActivity.kt:33–46`
  only initializes bars/theme; `ui/theme/Theme.kt:21–26` chooses graphite or
  paper from effective configuration. `ui/theme/ContrastSetting.kt:35–46`
  stores only contrast in `settings`; there is no existing saved theme or
  appearance to migrate. The installed API 36 `android.app.UiModeManager`
  public signatures (inspected with `javap`) include `getNightMode` and
  `setApplicationNightMode`, but no application-override getter.
  [Android API](https://developer.android.com/reference/android/app/UiModeManager)
  confirms the distinction. Fixed design D4/D5, appearance requirements and
  process-death/migration scenarios, proposal summary and task 4.1.
- **System bars: holds, high confidence.** `MainActivity.kt:31–37` calls
  `enableEdgeToEdge` only in `onCreate`. The
  [AndroidX implementation](https://raw.githubusercontent.com/androidx/androidx/androidx-main/activity/activity/src/main/java/androidx/activity/EdgeToEdge.kt)
  resolves icon mode during that call, not via a configuration observer.
  Fixed D4 and added both-direction bar/session/scroll scenarios and task 4.5.
- **Retained appearance conflict: holds, high confidence.**
  `openspec/changes/android-client-v1/specs/appearance/spec.md:5–15` still
  requires graphite/paper and an unconditional evening switch. Task 1.1 now
  names both replacements and preserved requirements. That older change is
  outside this card's edit scope; no reconciliation is claimed to have landed.
- Tasks 2.2–2.5 depend on owner theme/contrast decisions; task 1.1 depends
  on the approved scope. They are explicitly gated rather than declared
  decision-free. Tasks 2.1 and 3.1–3.5 use approved graphite/paper inputs.
- This change does not store or deliver text keys: their no-controls rule
  belongs to key-bar-editor and is unchanged by theme migration. No palettes
  or on-screen texts were added or changed in this fix; all proposed texts
  remain verbatim in design's Texts table, awaiting owner approval.
