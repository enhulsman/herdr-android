# release Specification (delta)

## ADDED Requirements

### Requirement: Ezra's install is the release control
The repository SHALL stay private. Signing SHALL happen only in a job bound to the
environment `release`, whose deployment policy admits only tags matching `v*`; the
signing key SHALL be a secret of that environment, never a repository secret.
Obtainium on the phone SHALL check for updates without installing them in the
background. The release process SHALL state that before installing an update,
Ezra checks in the GitHub web UI that the release's tag is one he pushed and that
its commit is on `main`.

#### Scenario: Release tag
- **WHEN** Ezra pushes tag v0.2.0
- **THEN** the signed APK and `herdr-push` are attached to release v0.2.0, and
  Obtainium shows the update without installing it

#### Scenario: Workflow on another branch
- **WHEN** a workflow runs from a branch or from a tag not matching `v*`
- **THEN** it cannot read the signing key

### Requirement: The signing job runs no repository code
The build job SHALL build from the tagged commit with full history, SHALL fail when
that commit is not on `main`, and SHALL upload unsigned artifacts with SHA-256 sums.
The signing job SHALL NOT check out the repository: it SHALL download the artifacts,
verify their sums, sign the APK with a pinned `apksigner` and `herdr-push` with a
detached signature, and publish.

#### Scenario: Tag off main
- **WHEN** a tag points at a commit not on `main`
- **THEN** the build job fails and no artifact reaches the signing job

#### Scenario: Swapped artifact
- **WHEN** an artifact's sum does not match the one recorded by the build job
- **THEN** the signing job fails without signing

### Requirement: Dependencies are locked and checked
The repository SHALL commit `Cargo.lock` and Gradle dependency verification
metadata, pin third-party actions by commit SHA, and run `cargo-deny` (advisories,
licenses, sources) before any artifact is uploaded.

#### Scenario: Unverified dependency
- **WHEN** a Gradle dependency's checksum differs from the verification metadata
- **THEN** the build fails

### Requirement: Obtainium installs and updates the app
Each release SHALL attach one arm64-v8a APK whose `versionCode` is
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
