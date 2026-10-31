# appearance Specification (delta)

## ADDED Requirements

### Requirement: The Mac's six themes
The app SHALL offer graphite, ink, moss, paper, atom-one-dark and win98, with
colours and derived chrome and status colours taken from herdr-app
(`settings.rs` presets and `defaults/themes/*.json`, derivation `resolve()`).
Custom theme files are not supported in version 1.

#### Scenario: Same colours as the Mac
- **WHEN** the phone and the Mac both use moss
- **THEN** the terminal background, foreground and 16 ANSI colours are identical

### Requirement: Follows the phone's light and dark mode
By default the app SHALL use graphite in dark mode and paper in light mode, and
SHALL switch with the system setting. Ezra SHALL be able to pick a theme for
each mode.

#### Scenario: Evening switch
- **WHEN** the phone switches to dark mode
- **THEN** the app switches to the dark theme without restarting the open terminal

### Requirement: Wide screens show list and terminal together
At a window width of 600 dp or more (landscape, tablet, unfolded), the app SHALL
show the agents list as a left rail beside the open terminal. Changing width
SHALL keep the open terminal and its scroll position.

#### Scenario: Rotating the phone
- **WHEN** Ezra rotates the phone to landscape with a terminal open
- **THEN** the rail appears beside the same terminal at the same position

### Requirement: Looks match the signed-off designs
Each screen SHALL match its signed-off design (from `/design-brief` and
`design-tokens.json`) within the screenshot tests' tolerance.

#### Scenario: Screenshot test
- **WHEN** the screenshot tests run in CI
- **THEN** each screen's render differs from its approved reference by less than the set tolerance, or the build fails
