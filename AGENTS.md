# Working in this repository

This repository is private now and meant to go public later without rewriting
history. So tracked files, commit messages, screenshots and test fixtures never
contain homelab hostnames, IP addresses, ports, private domains, home-directory
paths, real project or client names, or Mac screenshots.

- Real values live in `local/setup.md` (ignored by git). Read it when you need
  them; write neutral wording in tracked files ("WSL over OpenSSH", "your ntfy
  server", `https://ntfy.example.org`).
- Example content uses invented names (website, api, docs; workstation, server).
- A local pre-commit hook checks staged changes against a local denylist. If it
  blocks a commit, fix the wording; never bypass it with `--no-verify`.

Start with `decisions.md`, then `openspec/changes/android-client-v1/`.
The visual contract is the signed-off renders in `design/`; `design-tokens.json`
and `design-brief.md` record them.

## Putting a build on a phone

Test rounds use the debug build ("herdr debug", `dev.herdr.android.debug`,
the inverted icon), installed over wireless adb next to the release; a
release is cut only for a planned batch of fixes.

1. Build: `./gradlew :app:assembleDebug` from `android/`.
2. Find the phone: `adb devices -l` (wireless debugging gets a new port
   after a reboot or a network change).
3. Install for the owner profile only:
   `adb -s <serial> install -r --user 0 app/build/outputs/apk/debug/app-debug.apk`.
   Without `--user 0` adb installs into every user on the phone, Private
   Space and other users included.
4. Check, and remove any copy outside the owner profile:
   `adb -s <serial> shell pm list users`, then per user
   `pm list packages --user <n> | grep herdr`, and
   `pm uninstall --user <n> dev.herdr.android.debug` where it should not be.
5. Launch with `adb shell monkey -p dev.herdr.android.debug -c android.intent.category.LAUNCHER 1`,
   never `am start`.
