# Installing the app

The app is not on Google Play. Each release is on this repository's
[releases page](https://github.com/enhulsman/herdr-android/releases) as a
signed APK, and [Obtainium](https://github.com/ImranR98/Obtainium) installs it
and tells you about updates.

## Get Obtainium

Obtainium is not on Google Play either. On the phone, open its
[releases](https://github.com/ImranR98/Obtainium/releases), download
`app-arm64-v8a-release.apk` from the newest release and open it. Android asks
to let your browser install apps; allow it.

## Add the app in Obtainium

1. In Obtainium, tap Add App and enter
   `https://github.com/enhulsman/herdr-android`. The defaults are fine.
2. Tap Install. Android asks to let Obtainium install apps; allow it.
3. Leave background installing off. Obtainium may check for updates and notify
   you; you install each one by hand, after the check below.
4. Optional: GitHub limits how often Obtainium may check without a token. If
   checks fail, create a fine-grained personal access token with repository
   access "Public repositories" and nothing else, and add it under Obtainium's
   GitHub source settings.

## When Android stands in the way

- **Play Protect** may show "App scan recommended". Scanning is fine (the APK
  is public), or choose Install without scanning under More details. With
  Identity Check on, that asks for your fingerprint or face.
- **Advanced Protection** (Android 16) allows only apps from Google Play and
  the stores that came with the phone, so it blocks Obtainium and anything it
  installs ("App blocked by Advanced Protection"). Turn it off to install, or
  install from a computer with adb as below.

## Installing from a computer (adb)

1. From the release, download `herdr-android-<version>.apk` and `SHA256SUMS`,
   and check them as under [Checking a release](#checking-a-release).
2. Turn on USB or wireless debugging on the phone, then:
   `adb install --user 0 herdr-android-<version>.apk`
   (`--user 0` keeps it out of a work profile or Private Space).

Updates go the same way, with `adb install -r --user 0 ...`.

## Checking a release

On the first install, check that the APK is the one this repository signs.
On a computer, in the folder with the downloads:

```
sha256sum --check --ignore-missing SHA256SUMS
apksigner verify --print-certs herdr-android-<version>.apk
```

`apksigner` comes with Android's build tools. Its "Signer #1 certificate
SHA-256 digest" must equal the line in
[docs/release-signing-fingerprint.txt](release-signing-fingerprint.txt).

After that, Android itself refuses an update signed with any other key. Before
installing an update, open the release on GitHub: its notes name a commit
("Built from commit ..."); open that commit, and GitHub lists `main` among the
branches that contain it. If not, do not install it, and open an issue.

## Checking herdr-push on a machine

From the release, download `herdr-push-$(uname -m)`, its `.sig` and
`SHA256SUMS`, and the signing key from this repository as `allowed_signers`:

```
curl -fsSL -o allowed_signers \
  https://raw.githubusercontent.com/enhulsman/herdr-android/main/docs/herdr-push-allowed-signers
sha256sum --check --ignore-missing SHA256SUMS
ssh-keygen -Y verify -f allowed_signers -I herdr-push -n herdr-push \
  -s herdr-push-$(uname -m).sig < herdr-push-$(uname -m)
```

The last command must say `Good "herdr-push" signature`.
