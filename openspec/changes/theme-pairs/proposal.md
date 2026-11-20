# Proposal: themes as a light and dark pair

## Plain summary

**Decisions for the owner** (my pick first in each; nothing is settled yet):

1. **What a theme colours.** *The whole app*: bars, lists, chat, Changes and
   the terminal, as on the desk and in the signed-off renders. The other
   option is the terminal alone with a plain app around it, as Herdr's own
   Android app does; there a loud terminal theme clashes with the lists.
2. **Which theme goes in which half.** *Dark themes in the dark half, light
   themes in the light half*, decided by the theme's own background, which
   is the desk's rule. The status bar, the keyboard and system dialogs
   follow the mode, so a dark theme in light mode would sit under a light
   keyboard. The other option is any theme in either half.
3. **Staying dark or light.** *Add an Appearance choice*: Follow the phone,
   Always dark, Always light. The desk has it and Android recommends it.
   The other option is to follow the phone only, as today.
4. **win98.** *Leave it out.* The 2026-10-31 decision lists it among "the
   Mac's six", but the desk offers it only together with its own win98
   layout: its file sets a black terminal and black text, which reads only
   inside that layout's grey panels. In kudde the chat would be black on
   black. The other option is to adapt it, which means inventing colours.
5. **More themes than the desk's.** *Not in this change.* With win98 out,
   the desk gives four dark themes (graphite, ink, moss, atom-one-dark) and
   one light (paper), so the light half has one choice for now. What this
   change builds takes any palette in the desk's theme-file shape, so a
   second batch is data plus your sign-off on each swatch. The other option
   is to name extra themes now; each one still needs its licence checked
   and your eyes.
6. **Higher text contrast on the new themes.** *Dim text takes the theme's
   own muted colour*, which every theme already has, so no colour is
   invented. Graphite and paper keep the replacements you approved. The
   other option is to let the switch do nothing on the new themes.
7. **Where it lives.** *A "Theme" row under "This phone" that opens its own
   screen.* The other option is three more rows on the Machines screen.

**Every text on screen** is listed in `design.md` ("Texts") word for word.
Each one needs the owner's approval before it is built in.

**What changes for you:**

- A new Theme screen. You pick one theme for dark mode and one for light
  mode, each from a list with a small swatch, and the app switches between
  the two with the phone. You can also keep the app always dark or always
  light.
- Your appearance choice is remembered after reopening. An update starts
  with today's graphite/paper pair following the phone and keeps your other settings.
- Three more themes: ink, moss and atom-one-dark, with the desk's colours.
- The terminal finally takes the theme. Today it is black with the engine's
  own colours in every mode, also under paper's light bars. After this, a
  terminal in paper is white with dark text, and graphite's terminal has
  the same colours as on the desk.
- One limit stays: a program picks its colours for the background the desk
  reports. With a dark desk and a light phone, some coloured text can read
  poorly on the phone.

## Why

Issue #28 asks for the desk's other themes, chosen per light and dark mode.
The open v1 spec promises the pair in one sentence but names no screen, no
texts, no rule for which theme fits which mode, and nothing for the contrast
switch. Reading the code for this proposal also showed that the terminal
never received a theme at all: the engine draws with its own defaults
(`research/theme-pairs-2026-11.md`, with paths and lines). A theme choice
that leaves the terminal black would not be what the issue means.

Herdr's own Android app shows the pair works well on a phone: one choice per
mode and no surprise when the phone switches in the evening.

## What Changes

- The theme reaches the terminal engine as its default colours and its
  first 16 palette entries. Nothing is sent to the machine for it.
- Theme values become data: each theme's desk palette and its derived
  colours are recorded in `design-tokens.json`, and a test holds a port of
  the desk's derivation that must reproduce graphite and paper exactly.
- Three themes are added: ink, moss, atom-one-dark.
- Phone-local stored choices: a theme per mode and a three-state Appearance.
  A platform adapter applies the effective mode; status and navigation icons
  are refreshed explicitly when the mode changes in place.
- A Theme screen, opened from a new row under "This phone".
- "Higher text contrast" works on every theme.
- The retained graphite/paper-only requirement is reconciled with the pair
  and Appearance; "All six themes (v1.1)" moves out of `android-client-v1`
  into this change, reworded; task M4.3 there keeps only the wide layout.

Not in this change: the wide layout (the other half of #28, still specified
in `android-client-v1`), win98, well-known terminal themes, custom theme
files on the phone, a font choice, and a contrast adjustment for program
colours (the desk's `minimum_contrast`).

## Capabilities

### New Capabilities
- `appearance`: a theme for each mode, the Appearance choice, the terminal
  drawing in the theme, where theme values come from, the Theme screen, and
  the contrast switch on every theme. (The capability's first requirements
  are still in the open `android-client-v1` change; this change adds to
  it.)

### Modified Capabilities
- None in `openspec/specs/`. One requirement of the open `android-client-v1`
  delta is replaced and its retained graphite/paper-only requirement reconciled (task 1.1).

## Impact

- Rust, `kudde-mobile`: bindings for the engine's colour options, a theme on
  `open_terminal`, a `set_theme` call on an open terminal. One new UniFFI
  record.
- Android: `ui/theme` (five themes, two settings, the theme following
  them), `ui/terminal` (session passes the theme, cache check), a new
  screen and one row in `ui/machines`, `MainActivity`.
- `design-tokens.json`: palettes, three resolved themes, their state
  colours and checked contrast figures. New renders and goldens, which need
  sign-off.
- Shares files with #30 (the "This phone" card) and with the wide layout
  (terminal route). No change to herdr-core calls, alerts or the chat's
  logic.
- Estimate: 5 to 7 builder runs, plus two sign-off stops and one phone
  check. Confidence: moderate.
