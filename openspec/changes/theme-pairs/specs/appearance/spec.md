# appearance Specification (delta)

## ADDED Requirements

### Requirement: A theme for each mode
The app SHALL keep one chosen theme for dark mode and one for light mode,
and SHALL show the dark one while the app is dark and the light one while
it is light. The themes on offer SHALL be graphite, ink, moss and
atom-one-dark for dark mode, and paper for light mode. A theme belongs to
light mode when the luminance of its palette background
(0.2126 R + 0.7152 G + 0.0722 B, each from 0 to 1) is above 0.5, and to dark
mode otherwise, as the herdr desk app decides it. A fresh install SHALL use
graphite and paper. The choices SHALL be kept on the phone only, SHALL
survive a restart, and SHALL NOT be part of the machine list export. Custom
theme files are not supported.

#### Scenario: Choosing moss for dark mode
- **GIVEN** the app is dark and shows graphite
- **WHEN** the person picks moss in the dark list
- **THEN** every screen shows moss at once, and the app shows moss whenever it is dark, also after a restart

#### Scenario: A fresh install
- **WHEN** the app starts for the first time
- **THEN** it shows graphite in dark mode and paper in light mode

#### Scenario: Picking for the mode that is not showing
- **GIVEN** the app is light and shows paper
- **WHEN** the person picks ink in the dark list
- **THEN** nothing on screen changes colour, the dark list marks ink, and the app shows ink the next time it is dark

#### Scenario: A stored name the app does not know
- **GIVEN** the stored dark theme is a name this version does not have
- **WHEN** the app starts in dark mode
- **THEN** it shows graphite and does not crash

#### Scenario: A stored theme of the other mode
- **GIVEN** the stored light theme is "moss"
- **WHEN** the app starts in light mode
- **THEN** it shows paper

### Requirement: Appearance follows the phone or stays dark or light
The app SHALL persist a three-state appearance choice on the phone:
follow the phone (the default), always dark, and always light. Missing, unknown
or wrongly typed stored values SHALL read as follow the phone. Selected rows
and the Machines detail SHALL read this stored choice, never infer it from
effective configuration or the system night-mode setting. The choice SHALL
survive process death and updates and SHALL NOT be exported. It SHALL set
the effective choice through Android's
per-app night mode, so that the status bar icons, the keyboard, system
dialogs and the splash screen follow it. While an appearance other than
follow the phone is set, a change of the phone's own mode SHALL NOT change
the app.

#### Scenario: Always dark at noon
- **GIVEN** the phone is in light mode and the dark theme is moss
- **WHEN** the person picks always dark
- **THEN** the app shows moss, and the status bar shows light icons

#### Scenario: The phone switches while the app stays dark
- **GIVEN** the appearance is always dark
- **WHEN** the phone switches from dark to light mode
- **THEN** the app still shows its dark theme

#### Scenario: Back to following the phone
- **GIVEN** the appearance is always light and the phone is in dark mode
- **WHEN** the person picks follow the phone
- **THEN** the app shows its dark theme

#### Scenario: Kept across a restart
- **GIVEN** the appearance is always light
- **WHEN** the app is closed and opened again while the phone is dark
- **THEN** it opens in its light theme

### Requirement: The terminal draws in the theme
The terminal SHALL draw with the showing theme's terminal background and
terminal foreground as its default colours and the theme's 16 ANSI colours
as palette entries 0 to 15, set as the engine's default colours. Palette
entries 16 to 255 SHALL stay the engine's own. The cursor SHALL stay the
default foreground colour. A colour a program sets itself (a palette entry,
the default foreground or the default background) and any 24-bit colour it
writes SHALL show as the program set it, until the program resets it.
Applying a theme, or changing it while a terminal is open, SHALL send
nothing to the machine: no input, no size, and the `display` fields stay
null. A change SHALL show on the open terminal within one second, at the
same scroll position, without a viewer leaving or joining.

#### Scenario: A terminal in paper
- **GIVEN** the app shows paper
- **WHEN** a terminal opens and a program prints plain text, then text in ANSI red
- **THEN** the terminal's background is `#ffffff`, the plain text is `#25272c` and the red text is `#c62a2a`

#### Scenario: The same colours as the desk
- **GIVEN** the phone and the desk both show graphite
- **WHEN** a program prints text in ANSI blue
- **THEN** the phone's terminal background is `#17191d`, its plain text `#d2d4d9` and the blue text `#96b2d7`, as on the desk

#### Scenario: Evening switch with a terminal open
- **GIVEN** a terminal is open in paper, scrolled up by 40 rows, and the appearance follows the phone
- **WHEN** the phone switches to dark mode
- **THEN** the terminal shows the dark theme's colours on every row within one second, still scrolled up by 40 rows, and no message was sent to the machine for it

#### Scenario: The desk changes the terminal's size
- **GIVEN** a terminal is open in moss
- **WHEN** the desk resizes it and the phone attaches again
- **THEN** the terminal still draws in moss, from its first screen

#### Scenario: A program with its own background
- **GIVEN** a program has set the default background to `#002b36`
- **WHEN** the theme changes
- **THEN** the terminal keeps `#002b36` as its background, and shows the theme's background once the program resets the colour

#### Scenario: 24-bit and 256-colour text
- **WHEN** a program prints text in the 24-bit colour `#ff8800` and text in palette entry 208
- **THEN** both draw the same in every theme

#### Scenario: The last screen kept from another theme
- **GIVEN** a terminal's kept last screen was drawn in graphite
- **WHEN** the terminal is opened while the app shows paper
- **THEN** the kept screen is not shown; the terminal area is paper's terminal background until the first live screen arrives

#### Scenario: Colours chosen for the desk
- **GIVEN** the desk is dark, the phone shows paper, and a program wrote text in a light 24-bit colour
- **WHEN** the person reads that terminal on the phone
- **THEN** the text shows in the colour the program wrote; the phone does not change it and does not report its own colours to the program

### Requirement: Theme colours are the desk's, derived and recorded
Each theme's colours SHALL be the herdr desk app's palette for that name
(background, foreground, selection, optional cursor, 16 ANSI colours and
optional overrides) run through the desk's derivation: panel colours mixed
from the background, text greys mixed toward the text colour, and the
working, needs-you and done colours from the preset or else from ANSI
yellow, red and blue. The palette, the derived colours, the source and its
commit SHALL be recorded in `design-tokens.json`. A test SHALL hold a port
of the derivation and SHALL fail unless it reproduces the recorded graphite
and paper colours exactly and the recorded colours of every other theme.
No theme colour SHALL be typed in by hand.

#### Scenario: The port reproduces the signed-off themes
- **WHEN** the derivation test runs on the graphite and paper palettes
- **THEN** every derived colour equals the value `design-tokens.json` already records for that theme

#### Scenario: ink is derived
- **WHEN** the derivation runs on the ink palette (background `#111720`)
- **THEN** it gives chrome `#0c1016`, canvas `#0e131b` and popover `#1f252d`, and `design-tokens.json` records the same

#### Scenario: A theme file without state colours
- **WHEN** the derivation runs on atom-one-dark, whose file names no state colours
- **THEN** working is its ANSI yellow `#e5c07b`, needs-you its ANSI red `#e06c75` and done its ANSI blue `#61afef`

#### Scenario: A value edited by hand
- **WHEN** a recorded colour of moss is changed in `design-tokens.json` without changing its palette
- **THEN** the derivation test fails and names the colour

### Requirement: A Theme screen
The Machines screen's "This phone" card SHALL have a Theme row that says
which themes are chosen and opens the Theme screen. The Theme screen SHALL
show the three appearances, then the dark themes, then the light themes.
Each theme row SHALL show a swatch of that theme, its name, and a mark on
the chosen one. A tap SHALL apply and keep the choice at once; there is no
save step. Every row SHALL be at least 48 dp high with the whole row as its
target. The chosen row SHALL be exposed to a screen reader as selected, and
the swatch SHALL NOT be read. The screen SHALL hold at twice the font size
without clipping a name.

#### Scenario: The row on Machines
- **GIVEN** the dark theme is moss, the light theme is paper and the appearance follows the phone
- **WHEN** the person opens Machines
- **THEN** the Theme row reads "moss in dark mode, paper in light mode"

#### Scenario: The row with a fixed appearance
- **GIVEN** the dark theme is ink and the appearance is always dark
- **WHEN** the person opens Machines
- **THEN** the Theme row reads "Always dark: ink"

#### Scenario: Picking re-themes the screen itself
- **GIVEN** the app is dark and the Theme screen is open
- **WHEN** the person taps atom-one-dark
- **THEN** the Theme screen itself shows atom-one-dark's colours and marks that row

#### Scenario: With a screen reader
- **WHEN** a screen reader lands on the chosen dark theme
- **THEN** it reads the theme's name and that it is selected, once, and reads nothing for the swatch

#### Scenario: A list with one theme
- **WHEN** the person opens the Theme screen
- **THEN** the light list shows paper alone, marked

### Requirement: Higher text contrast works on every theme
With "Higher text contrast" on, graphite and paper SHALL use their recorded
replacement colours as before. Every other theme SHALL draw dim text in the
theme's muted colour and leave its other colours as they are. For each such
theme a test SHALL check that muted text reaches 4.5:1 on that theme's
chrome and popover colours; a theme that fails SHALL be listed for the
owner and SHALL NOT be shipped with an adjusted colour.

#### Scenario: Higher contrast in moss
- **GIVEN** the app shows moss
- **WHEN** the person turns Higher text contrast on
- **THEN** subtitles and times draw in moss's muted colour, and rings and counts keep its dim colour

#### Scenario: Higher contrast in graphite is unchanged
- **GIVEN** the app shows graphite with Higher text contrast on
- **WHEN** a subtitle is drawn
- **THEN** it uses `#8d8e90`, as before this change

### Requirement: Each theme's look is signed off
For every theme on offer there SHALL be a swatch golden showing its colours,
state colours, ANSI row and type, and the Theme screen SHALL have goldens in
graphite and paper. A new theme SHALL NOT be offered in a release before
the owner has signed off its swatch.

#### Scenario: A new theme's swatch
- **WHEN** ink is added
- **THEN** a swatch golden for ink exists, and a change to any of ink's colours fails the screenshot test until the golden is approved again

### Requirement: Updating preserves existing phone settings
The app SHALL preserve the current experience on update: when no theme pair
or appearance choice is stored, use graphite, paper and follow the phone,
without changing Higher text contrast, the key bar or any unrelated setting.
Existing valid pair and appearance values from this feature SHALL survive
later updates; invalid values SHALL use the per-field defaults.

#### Scenario: Updating an existing installation
- **GIVEN** the current app follows the phone and Higher text contrast is on
- **WHEN** the person updates and starts the app in light mode
- **THEN** paper shows, graphite is chosen for dark mode, follow the phone is selected, and Higher text contrast remains on

#### Scenario: Updating with saved choices
- **GIVEN** a supported stored pair of moss and paper with always dark, and a saved key bar
- **WHEN** an update starts while the phone is light
- **THEN** moss shows, always dark remains selected, and the saved key bar is unchanged

### Requirement: In-place mode changes refresh system bars
Whenever the effective app mode changes, the app SHALL refresh both status-
and navigation-bar icon appearance with the new mode, including when uiMode
is handled without activity recreation. Dark app chrome SHALL have light
system-bar icons, and light chrome dark icons. The open terminal SHALL stay
attached, at its existing scroll position, with no detach, attach, input or
resize caused by the appearance change.

#### Scenario: Light to dark in place
- **GIVEN** a live terminal in paper, scrolled up 40 rows, following the phone
- **WHEN** the phone becomes dark and uiMode is handled in place
- **THEN** both system bars use light icons, the terminal uses the dark theme within one second, the same session remains attached and scrolled up 40 rows, and no attach, detach, input or resize is sent

#### Scenario: Dark to light in place
- **GIVEN** a live terminal in graphite, scrolled up 40 rows, with always dark chosen and the phone light
- **WHEN** the person chooses always light without recreating the activity
- **THEN** both system bars use dark icons, the terminal uses paper within one second, and its attachment and scroll position remain unchanged with no attach, detach, input or resize

#### Scenario: Same effective mode, different saved choice
- **GIVEN** the phone is dark
- **WHEN** each of follow the phone and always dark is saved in turn and the process is killed and reopened
- **THEN** the matching saved choice is selected and the Machines detail reflects that choice even though both show the dark theme

#### Scenario: Both light choices survive
- **GIVEN** the phone is light
- **WHEN** each of follow the phone and always light is saved in turn and the process is killed and reopened
- **THEN** the matching saved choice and its Machines detail are restored

#### Scenario: Invalid appearance preference
- **GIVEN** the stored appearance is absent, unknown or a non-string value
- **WHEN** the app starts
- **THEN** follow the phone is selected and the app does not crash
