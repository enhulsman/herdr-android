# release Specification (delta)

## ADDED Requirements

### Requirement: Ezra's approval is the release control
The code repository (`herdr-android`) SHALL stay private and SHALL hold no signing
key. Signing SHALL happen only in a separate public repository
(`herdr-android-releases`), in a job bound to its protected environment `release`
with Ezra as required reviewer and self-review allowed. That repository SHALL
contain only the signing workflow, a README and the signed releases. The release
process SHALL state that before approving, Ezra checks in the GitHub web UI, not
in the run's own output, that the tag's commit is on `main` in the code repository
and that `.github/` in both repositories is unchanged since the previous release.

#### Scenario: Release tag
- **WHEN** Ezra pushes tag v0.2.0 to the code repository, starts the signing
  workflow for v0.2.0 in the releases repository and approves it after the check
- **THEN** the signed APK and `herdr-push` are attached to release v0.2.0 in the
  releases repository

#### Scenario: Unapproved run
- **WHEN** nobody approves the signing job
- **THEN** no signed APK exists for that tag

### Requirement: No token on the workstation can approve a release
The release process SHALL state that no GitHub token stored on a development
machine (the `gh` login included) has access to the releases repository; pushes
from those machines go over SSH. Approval happens only from Ezra's phone.

#### Scenario: Compromised workstation
- **WHEN** something on WSL uses the stored GitHub credentials to start the
  signing workflow
- **THEN** the run waits for an approval those credentials cannot give

### Requirement: The signing job runs no code from the code repository
The build job in the code repository SHALL build from the tagged commit with full
history, SHALL fail when that commit is not on `main`, and SHALL attach the
unsigned artifacts and their SHA-256 sums to a release in the code repository.
The signing job SHALL NOT check out either repository: it SHALL download those
artifacts with a read-only token limited to the code repository's contents, stored
as a secret of the `release` environment, verify their sums, sign the APK with a
pinned `apksigner` and `herdr-push` with a detached signature, and publish.

#### Scenario: Tag off main
- **WHEN** a tag points at a commit not on `main`
- **THEN** the build job fails and no artifact reaches the signing job

#### Scenario: Swapped artifact
- **WHEN** an artifact's sum does not match the one recorded by the build job
- **THEN** the signing job fails without signing

### Requirement: Releases move to the code repository when it goes public
When the code repository becomes public, signing SHALL move into its own `release`
environment with the same signing key, and Obtainium SHALL be pointed at the code
repository. Old releases stay in the releases repository, which is then archived.

#### Scenario: Switch-over release
- **WHEN** the first release is published from the code repository
- **THEN** Obtainium on the phone installs it over the old version, keeping app
  data and the SSH key

### Requirement: Dependencies are locked and checked
The repository SHALL commit `Cargo.lock` and Gradle dependency verification
metadata, pin third-party actions by commit SHA, and run `cargo-deny` (advisories,
licenses, sources) before any artifact is uploaded.

#### Scenario: Unverified dependency
- **WHEN** a Gradle dependency's checksum differs from the verification metadata
- **THEN** the build fails

### Requirement: Obtainium installs and updates the app
Each signed release SHALL attach one arm64-v8a APK whose `versionCode` is
`major*10000 + minor*100 + patch`, tested to exceed the previous release's, plus a
`herdr-push` binary for Linux x86_64 with its sum and signature. The signing
certificate fingerprint SHALL be recorded in the repository.

#### Scenario: Update on the phone
- **WHEN** a new release is published
- **THEN** Obtainium installs it over the old version, keeping app data and the SSH key

#### Scenario: Version code goes backwards
- **WHEN** a tag would produce a `versionCode` not above the previous release's
- **THEN** the build fails

### Requirement: Tests gate every release
The build SHALL fail when any of these fail: Rust unit tests; protocol tests
against scratch cores of the pinned and the latest herdr-core release (own
`HC_SESSION` and XDG directories, never the user's session); cell-grid goldens;
Roborazzi screenshot goldens; the emulator test that loads the real native library
and renders a terminal; the throwaway-sshd test of the `authorized_keys` line.

#### Scenario: Protocol drift
- **WHEN** a new herdr-core release changes an RPC the app uses
- **THEN** the latest-release protocol tests fail before any APK is signed

### Requirement: Client data stays out of the repository
Screenshots, fixtures and recordings SHALL use synthetic terminals and names only.
The repository SHALL ignore `reference-shots/` except for the upstream images it
lists explicitly.

#### Scenario: A new screenshot in reference-shots
- **WHEN** a screenshot is saved into `reference-shots/` under a new name
- **THEN** git ignores it unless it is added to the allowlist
