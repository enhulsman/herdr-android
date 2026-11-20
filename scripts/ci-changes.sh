#!/usr/bin/env bash
# Which CI areas a pull request runs, from its changed paths (one per line on
# stdin, as `git diff --name-only` prints them). Prints one line per area, in
# this order, ready for $GITHUB_OUTPUT:
#   rust=true|false     fmt, clippy, the workspace tests, the script tests, cargo-deny
#   core=true|false     the scratch-core and sshd tests (core-tests.yml)
#   android=true|false  the app's unit and screenshot tests and the debug APK
#   push=true|false     kudde-push for Linux aarch64
#   site=true|false     the website's build and browser checks (site-tests.yml)
#
# Fail-safe: a path that matches no area below and is not on the non-code list
# runs every area. Each mapping that crosses a directory names the file that
# reads it. Tests: scripts/test-ci-changes.sh.
#
# Usage: git diff --name-only <base>...HEAD | scripts/ci-changes.sh
set -euo pipefail

declare -A runs=([rust]=false [core]=false [android]=false [push]=false [site]=false)

run() {
  local area
  for area in "$@"; do runs[$area]=true; done
}

while IFS= read -r path || [[ -n $path ]]; do
  case $path in
    '') ;;

    # The routing itself, and the workflow that runs it.
    .github/workflows/ci.yml | scripts/ci-changes.sh | scripts/test-ci-changes.sh)
      run rust core android push site ;;

    # Crates, by who depends on them: kudde-mobile (android, core's sshd test)
    # uses herdr-proto, kudde-chat and kudde-changes; kudde-push (core, push)
    # uses herdr-proto; uniffi-bindgen generates the Kotlin bindings.
    Cargo.toml | Cargo.lock | rust-toolchain.toml | crates/herdr-proto/*)
      run rust core android push ;;
    crates/kudde-push/*) run rust core push ;;
    crates/kudde-mobile/* | crates/kudde-chat/* | crates/kudde-changes/*)
      run rust core android ;;
    crates/uniffi-bindgen/*) run rust android ;;
    crates/*) run rust core android push ;;

    # crates/herdr-proto/tests/wire_handshake_compat.rs reads the README.
    README.md) run rust ;;
    # crates/kudde-mobile/src/lib.rs and android/app/build.gradle.kts read it.
    VERSION) run rust android ;;
    # crates/kudde-mobile/src/vt/grid.rs checks the frames against its goldens.
    android/app/src/test/resources/frames/*) run rust android ;;
    android/*) run android ;;
    # ThemeTokensTest, through android/app/build.gradle.kts.
    design-tokens.json) run android ;;

    deny.toml | scripts/check-release-tag.sh | scripts/test-check-release-tag.sh | \
      scripts/test-release-sign-gate.sh | scripts/test-fetch-herdr-core.sh | \
      scripts/check-restricted-key.sh | scripts/test-check-restricted-key.sh | \
      scripts/grid-golden.c | scripts/record-grid-goldens.sh)
      run rust ;;
    scripts/fetch-herdr-core.sh | scripts/herdr-core-pins.txt) run rust core ;;
    scripts/build-ghostty.sh) run rust android ;;
    scripts/build-rust-android.sh | scripts/uniffi-kotlin.toml) run android ;;

    .github/workflows/core-tests.yml) run core ;;
    .github/workflows/site-tests.yml) run site ;;
    # scripts/test-release-sign-gate.sh runs its gate step.
    .github/workflows/release-sign.yml) run rust ;;
    # Only a release tag runs it.
    .github/workflows/release.yml) ;;

    site/*) run site ;;
    # The install page shows it; site/tests/site.test.mjs checks it does.
    docs/release-signing-fingerprint.txt) run site ;;

    # Not code: no build or test reads these.
    docs/* | openspec/* | design/* | research/* | canvas/* | contrib/* | .claude/* | \
      .github/ISSUE_TEMPLATE/* | LICENSE | *.md) ;;

    *) run rust core android push site ;;
  esac
done

for area in rust core android push site; do
  echo "$area=${runs[$area]}"
done
