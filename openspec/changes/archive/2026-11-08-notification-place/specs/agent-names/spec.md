## MODIFIED Requirements

### Requirement: Alerts name the session and hide it on a private lock screen
Agent alerts SHALL use the agent's name: "<name> needs you" with the body
"<kind> · <place> · <machine>", and "<name> finished" with the body
"<place> · <machine>", where `place` is the terminal's `where` label by the
placement rule of the Agents list (no folder fallback) and an absent kind or
place is left out with its separator. The watcher SHALL send the name by the
rule above and the place as the optional `place` field; `name` MAY be a session
title. Every agent notification and the test alert SHALL be private with a
fixed public version that names only the machine ("An agent needs you · WSL",
"An agent finished · WSL", "Test alert from WSL"), so the phone's own setting
for sensitive notifications decides what the lock screen shows.

#### Scenario: A session finishes
- **WHEN** the agent "Pricing page copy" with `where` = "website / 2" on WSL finishes while the app is closed
- **THEN** the notification reads "Pricing page copy finished" over "website / 2 · WSL"

#### Scenario: A session needs you
- **WHEN** the agent "Pricing page copy" with `where` = "website / 2" on WSL asks permission
- **THEN** the notification reads "Pricing page copy needs you" over "Permission · website / 2 · WSL"

#### Scenario: Not placed yet
- **WHEN** an agent without a `where` label, or with an empty `herdr.space`, asks permission on WSL
- **THEN** the second line reads "Permission · WSL"

#### Scenario: Locked, sensitive content hidden
- **WHEN** the agent "Pricing page copy" on WSL needs you and the phone is locked with sensitive notifications hidden
- **THEN** the lock screen shows "An agent needs you · WSL", and the full text after unlocking

#### Scenario: Locked, sensitive content shown
- **WHEN** the same alert arrives and the phone shows sensitive notifications on the lock screen
- **THEN** the lock screen shows "Pricing page copy needs you"

#### Scenario: An older watcher
- **WHEN** an older herdr-push sends the name "Claude Code" and no place
- **THEN** the notification reads "Claude Code needs you" over "Permission · WSL"
