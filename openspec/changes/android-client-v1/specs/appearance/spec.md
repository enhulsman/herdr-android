# appearance Specification (delta)

## ADDED Requirements

### Requirement: Mac colours, following light and dark mode
The app SHALL use graphite in dark mode and paper in light mode, switching with the
system setting, with colours and derived chrome and status colours taken from
herdr-app (`settings.rs` presets, derivation `resolve()`).

#### Scenario: Same colours as the Mac
- **WHEN** the phone and the Mac both use graphite
- **THEN** the terminal background, foreground and 16 ANSI colours are identical

#### Scenario: Evening switch
- **WHEN** the phone switches to dark mode
- **THEN** the app switches to graphite without restarting the open terminal

### Requirement: All six themes (v1.1)
The app SHALL offer graphite, ink, moss, paper, atom-one-dark and win98 (from
`settings.rs` and `defaults/themes/*.json`), with a choice per light and dark mode.
Custom theme files are not supported.

#### Scenario: Choosing moss for dark mode
- **WHEN** Ezra picks moss for dark mode
- **THEN** the app uses moss whenever the phone is in dark mode

### Requirement: Wide screens show list and terminal together (v1.1)
At a window width of 600 dp or more the app SHALL show the agent list as a rail
beside the open terminal, keeping the terminal and its scroll position when the
width changes.

#### Scenario: Rotating the phone
- **WHEN** Ezra rotates to landscape with a terminal open
- **THEN** the rail appears beside the same terminal at the same position

### Requirement: Screens stay as signed off
After Ezra signs off each screen's rendered look once, the app SHALL record
Roborazzi goldens for it, and CI SHALL fail on differences beyond the set tolerance.

#### Scenario: Unintended change
- **WHEN** a code change alters the agent list's rendering
- **THEN** the screenshot test fails until the golden is re-approved
