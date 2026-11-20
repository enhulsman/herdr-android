# Design: themes as a light and dark pair

Background, with paths and lines for every statement about today's code:
`research/theme-pairs-2026-11.md`.

## D1. The theme goes into the engine, not onto the picture

Today the engine is created without colours and every frame carries the
engine's own defaults, which the renderer paints. Two ways to theme it:

- **Chosen: set the engine's default colours.** The pinned engine has
  `ghostty_terminal_set` with options for the default foreground (11),
  background (12) and the 256-entry palette (14). The app reads the engine's
  default palette, replaces entries 0 to 15 with the theme's and sets it
  back, then sets foreground and background. The engine keeps a program's
  own colour changes on top of the defaults, so "a program with its own
  background" needs no code of ours. After a set, the next frame is a full
  one (the existing `force_full` flag).
- Rejected: recolour in Kotlin while drawing. A frame carries resolved
  colours, so the renderer cannot tell "palette red" from a 24-bit red that
  happens to match.

The cursor option (13) is not used: every theme on offer names no cursor or
names its foreground, and the renderer already draws the cursor in the
default foreground.

The theme travels as one UniFFI record, `TerminalTheme { fg, bg, ansi }`
(`ansi` has 16 entries; any other length is refused), given to
`open_terminal` and to a new `TerminalView::set_theme`. Inside, it is one
more command on the terminal's channel and touches only the local engine.
Nothing about it goes over the link; a test holds that.

## D2. Where the colours live

`design-tokens.json` stays the law. It gains:

- `colors.palettes.<name>`: the desk's palette for each theme (background,
  foreground, selection, optional cursor, 16 ANSI colours, optional state
  colours, optional overrides), with `source` (repository, file, commit).
  The palettes come from the herdr desk app's public sources,
  `src/settings.rs` (presets) and `defaults/themes/*.json`. This proposal
  read commit `07ba77e`; the builder records the commit it copies from.
- `colors.themes.<name>` for ink, moss and atom-one-dark, in the same shape
  graphite and paper have, and their entries under `colors.semantic`.
- `accessibility.checked.<name>`: the measured ratios.

`Colors.kt` stays what it is, constants that mirror the tokens, compared by
`ThemeTokensTest`. New is a port of the desk's `resolve()` in the test
sources. It turns a palette into a theme and must equal the recorded
values. The port is test code, so the app carries no second copy of the
rule at run time, and a later theme is: one palette, run the port, record
its output, a swatch, a sign-off.

**Arithmetic.** The desk mixes two colours per channel as
`x * (1 - t) + y * t` and rounds. Some channels land exactly on a half, so
the width of the float decides the result. A check made for this proposal:
with 64-bit floats and halves rounded up, the port reproduces every panel
colour recorded for graphite and paper; a 32-bit emulation was one step off
in graphite's ring and paper's muted. The recorded, signed-off values win:
the port uses 64-bit floats, and the first test proves it on the two
signed-off themes before anything new is derived.

**The rule being ported** (dark themes; light ones use the desk's light
branch, which paper already exercises): chrome = background mixed 32% to
black, canvas 16% to black, popover 6% to white, raised 9% to white; text
`#eeeeef`; muted, dim, ring and idle = background mixed 60%, 38%, 70% and
38% toward the text colour; solid = text; solid text = background mixed 28%
to black; accent = ANSI blue; hover, selected, border and input are the
fixed white overlays graphite has; working, needs-you and done = the
palette's state colours, else ANSI 3, 1 and 4; connected = ANSI green, as
today. Overrides in a theme file replace derived values by name.

## D3. Light or dark is the theme's own property

`isDark` comes from the palette background's luminance, the desk's rule, and
is recorded as `mode` in the tokens. The lists on the Theme screen are built
from it, so a later theme lands in the right list without a code change.

Why not any theme in either half: `MainActivity` draws the system bars with
`SystemBarStyle.auto`, which follows the mode, and the keyboard and system
dialogs do the same. Keeping themes in their half keeps all of them in
step. A person who wants a dark theme at noon uses Appearance.

## D4. Appearance through the system

Android 12 and later (minimum API 31) exposes a setter for per-app night
mode, but no public getter for the saved override. `getNightMode` reports
the system setting; effective configuration reports only the resulting mode.
Neither restores the person's three-state choice. The app stores that choice
(D5), and the night-mode adapter consumes it at startup and on change.
Always dark maps to `MODE_NIGHT_YES`, always light to `MODE_NIGHT_NO`.
Follow the phone must track the device's effective mode, including manual
and scheduled changes; it must not be mapped blindly to `MODE_NIGHT_AUTO`
(which is automatic mode, not a documented follow-system override).
The adapter synchronizes the per-app override to the effective device mode
on startup and device-mode changes, reading device configuration separately
from the overridden activity configuration. Use public APIs only, no hidden
constants. Test the mapping and observers behind an interface, and verify
real configuration delivery on API 31+ in the phone check. The platform keeps
the last applied override for splash; do not claim a getter or guarantee a
correct cold splash after an unseen device-mode change while the app is dead.

Handle uiMode in place so Compose and the classic input view update without
closing the terminal. On each effective mode change refresh both status-
and navigation-bar icon appearance explicitly, either by reapplying
`enableEdgeToEdge` with updated configuration or by the window insets
controller. `SystemBarStyle.auto` does not observe configuration by itself.
Keep the same terminal session and scroll position in both transition
directions; no attach, detach, resize or input for a mode change.

## D5. Settings and what is stored

Three more keys in the existing preferences file (`settings`, where
`text_contrast` lives): `theme_dark` and `theme_light`, holding the theme's
token name. A missing or unknown name, or a theme of the other mode, reads
as the default for that half. `appearance` stores `system`, `dark` or `light`;
missing, unknown or wrongly typed values read as `system`. Persist before
applying the platform mapping; UI selection and Machines detail read this
preference. Settings remain local and outside machine exports.

Update from today: there are no saved theme names or appearance to migrate.
Missing keys give graphite/paper/system, preserving `text_contrast` and the
key bar. Later updates retain valid values and default invalid fields only.

`KuddeTheme` takes the theme setting next to the contrast setting.
`ThemeName` gains the three themes; `kuddeColors` returns the muted colour
as `dimText` for them under Higher text contrast.

## D6. The kept last screen

A terminal's last screen is kept as a frame and shown while the live one
loads. A frame holds resolved colours, so one kept in graphite would flash
dark in paper. A kept frame's header carries its default foreground and
background; when they differ from the showing theme's, the frame is not
shown. No change to what is stored.

## D7. A limit that stays

The phone sends its display fields empty, so programs learn the desk's
colours, not the phone's (terminal-input spec, "Desk colours kept"). A
program that picks 24-bit colours for a dark desk can read poorly on a
light phone. Reporting the phone's colours would change what the desk
shows, so this change leaves it. The desk's `minimum_contrast` (adjust
program colours until they reach a ratio) is the known answer and can be
its own change. The phone check records how it looks in practice.

## D8. New visual elements, for sign-off

Nothing here is final until the owner has seen a render.

- **The Theme row** on Machines: a plain row with a chevron, like "Phone
  key", placed after it.
- **The Theme screen**: the sub-screen top bar and the card with rows that
  Machines already uses. Three cards under caption headers.
- **The chosen mark**: a check at the row's end, in the text colour.
- **The swatch**, the one new shape: a rounded rectangle about 56 x 32 dp,
  radius 8, thin border in the border colour, filled with that theme's
  terminal background. Inside, "Aa" in the terminal type and that theme's
  terminal foreground, then three 9 dp dots in that theme's working,
  needs-you and done colours. These are kudde's own state marks, which is
  what a theme changes across the app. It is drawn in the swatch's theme,
  not the showing one.

## Texts

Every text below needs the owner's approval before it is built in.

| Where | Text |
|---|---|
| Machines, "This phone", row title | Theme |
| Row detail, following the phone | `<dark> in dark mode, <light> in light mode` (for example "graphite in dark mode, paper in light mode") |
| Row detail, always dark | `Always dark: <dark>` |
| Row detail, always light | `Always light: <light>` |
| Screen title | Theme |
| First header | Appearance |
| Its rows | Follow the phone / Always dark / Always light |
| Second header | In dark mode |
| Third header | In light mode |
| Theme names | graphite / ink / moss / atom-one-dark / paper (as the desk writes them) |
| Line under the lists | These are the herdr desk app's themes. The terminal, the lists and the chat all take the theme. |

A screen reader reads a row's text and the platform's own word for
"selected"; no extra texts.

## Risks

- **A mode change recreates the activity today.** If handling it in place
  turns out harder than expected, the terminal may reattach on a mode
  change. That would fail the "Evening switch" scenario and must come back
  to the owner, not be shipped quietly.
- **Robolectric and the night-mode call.** If its shadow does not support
  the per-app call, the interface in D4 is tested with a fake and the real
  call is covered by the phone check only.
- **atom-one-dark's muted text on a card** measures about 4.8:1, the closest
  to the 4.5 line. The contrast test decides.
- **Goldens.** Every existing golden is graphite or paper and must not
  change. A terminal golden in paper is new, since the fixture frames carry
  their own colours.
