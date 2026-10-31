# release Specification (delta)

## ADDED Requirements

### Requirement: Releases are signed only after Ezra approves
A tag `v<semver>` SHALL start a GitHub Actions workflow that builds and tests the
app and `herdr-push`. Signing SHALL happen in a separate job bound to a protected
environment that requires Ezra's approval; the signing key SHALL exist only as
that environment's secret (plus an offline backup in Bitwarden). Third-party
actions SHALL be pinned by commit SHA.

#### Scenario: Release tag
- **WHEN** Ezra pushes tag v0.2.0
- **THEN** the build and tests run, and the signed APK is attached to the GitHub release only after he approves the signing job

#### Scenario: Unapproved run
- **WHEN** nobody approves the signing job
- **THEN** no signed APK exists for that tag

### Requirement: Obtainium installs and updates the app
Each release SHALL attach exactly one arm64-v8a APK with a `versionCode` that
increases with every release, and a `herdr-push` binary per supported machine
(Linux x86_64, macOS arm64). Obtainium SHALL be able to track the repository and
install updates without the Play Store.

#### Scenario: Update on the phone
- **WHEN** a new release is published
- **THEN** Obtainium offers the update and installs it over the old version, keeping app data

### Requirement: Tests gate every release
The workflow SHALL fail the release when any of these fail: Rust unit tests;
protocol tests against a scratch herdr-core of the pinned version (own
`HC_SESSION` and XDG directories, never the user's session); Compose screenshot
tests; an emulator test that loads the real native library and renders a
terminal.

#### Scenario: Protocol drift
- **WHEN** the pinned herdr-core is bumped and an RPC the app uses changed shape
- **THEN** the protocol tests fail before any APK is signed

### Requirement: Client data stays out of the repository
Screenshots, fixtures and test recordings SHALL NOT contain real project or
client names; tests SHALL use synthetic terminals and names.

#### Scenario: Reference shots
- **WHEN** reference screenshots are added to the repository
- **THEN** they come from synthetic sessions, and the Mac screenshots taken during research stay ignored by git
