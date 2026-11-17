# Releasing

How the maintainer makes a release. To install the app or `herdr-push`, see
[install.md](install.md).
The rules behind this are in `openspec/changes/android-client-v1/specs/release/spec.md`.

## Making a release

1. On a branch, set the new version in `VERSION` and in `Cargo.toml`
   (`[workspace.package] version`); both must say the same. Merge to `main`.
2. Tag that commit on `main` and push the tag:
   `git tag -s v0.1.0 -m v0.1.0 && git push origin v0.1.0`.
3. The `Release` workflow (`release.yml`) fails unless the tag's commit is on
   `main`, `VERSION` matches the tag, and the versionCode
   (`major*10000 + minor*100 + patch`) is above every earlier release tag. It runs
   all tests and `cargo-deny`, builds the unsigned arm64-v8a APK and `herdr-push`,
   and uploads them with their SHA-256 sums.
4. When it succeeds, `Sign and publish` (`release-sign.yml`) runs in the `release`
   environment. It always runs as the file is on `main`, so a tag cannot change
   it, and one run at a time. It checks out nothing. Through the GitHub API it
   checks again that the tag points at the built commit, that commit is on
   `main`, `VERSION` matches, and the versionCode is above every published
   release; then it verifies the sums, signs, checks the certificate against
   `docs/release-signing-fingerprint.txt`, and publishes the release with the
   APK, `herdr-push-x86_64` and `herdr-push-aarch64` with a `.sig` each, and
   `SHA256SUMS`.

A tag cannot be moved or deleted, so a failed release is fixed with the next
patch version.

## One-time setup (M0.4)

`scripts/setup-release-keys.sh generate`, then `... upload <folder>`, does all of
this; the script's header says how.

- Environment `release`, deployment policy: selected branches, `main` only. The
  signing workflow is triggered by `workflow_run` and so runs on `main`; a tag
  policy would block it, and a workflow from any other branch or tag cannot
  read the keys.
- Its secrets (never repository secrets): `RELEASE_KEYSTORE_B64` (the keystore,
  base64), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`,
  and `HERDR_PUSH_SIGNING_KEY` (an unencrypted OpenSSH private key).
- Keystore and both keys backed up in the password manager.
- The certificate's SHA-256 replaces the placeholder in
  `docs/release-signing-fingerprint.txt`. Until then every release stops before
  publishing.

## Checking a release you made

Before installing a release on your own phone, open it on GitHub:

1. The tag is one you pushed (its name and time match what you did).
2. The checks for every user in [install.md](install.md#checking-a-release)
   pass: its commit is on `main`, and the APK's certificate matches.

If either check fails, do not install, and look at the repository's recent
pushes and workflow runs.
