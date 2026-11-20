#!/usr/bin/env bash
# Tests for scripts/ci-changes.sh: which CI areas a pull request's changed
# paths run. CI's changes job runs these before it trusts the routing.
#
# Usage: scripts/test-ci-changes.sh
set -euo pipefail

route=$(cd "$(dirname "$0")" && pwd)/ci-changes.sh
failures=0
count=0

# expect <areas that run: space-separated, "all" or "none"> [path...]
# Feeds the paths one per line, as git diff --name-only prints them.
expect() {
  local want=$1 expected="" out status=0 area
  shift
  count=$((count + 1))
  [[ $want == all ]] && want="rust core android push site"
  for area in rust core android push site; do
    if [[ " $want " == *" $area "* ]]; then expected+="$area=true"; else expected+="$area=false"; fi
    expected+=$'\n'
  done
  expected=${expected%$'\n'}
  out=$(if (($#)); then printf '%s\n' "$@"; fi | "$route" 2>&1) || status=$?
  if [[ $status -ne 0 ]]; then
    echo "FAIL ${FUNCNAME[1]}: [$*] exit $status: $out" >&2
    failures=$((failures + 1))
  elif [[ $out != "$expected" ]]; then
    echo "FAIL ${FUNCNAME[1]}: [$*] printed" >&2
    printf '%s\n' "$out" | sed 's/^/  /' >&2
    echo "expected $want" >&2
    failures=$((failures + 1))
  fi
}

# Each path alone, then all of them at once: none of them may widen the union.
expect_each() {
  local want=$1 path
  shift
  for path in "$@"; do expect "$want" "$path"; done
  expect "$want" "$@"
}

site_change_runs_only_site() {
  expect_each site site/src/pages/index.astro site/package.json site/package-lock.json \
    site/tests/site.test.mjs site/README.md
}

readme_runs_rust() {
  # crates/herdr-proto/tests/wire_handshake_compat.rs reads the README.
  expect rust README.md
}

signing_fingerprint_runs_site() {
  # The install page and its test read it; the other docs are prose.
  expect site docs/release-signing-fingerprint.txt
}

non_code_runs_nothing() {
  expect_each none docs/release.md docs/phone-testing.md docs/kudde-push-allowed-signers \
    openspec/changes/x/proposal.md openspec/specs/x/spec.md design/renders/a.png \
    research/notes.md canvas/project/Main.dc.html contrib/kudde-push.service \
    .claude/settings.json .github/ISSUE_TEMPLATE/bug.yml LICENSE AGENTS.md CLAUDE.md \
    decisions.md SECURITY.md THIRD_PARTY_NOTICES.md design-brief.md scripts/notes.md
}

android_change_runs_only_android() {
  expect_each android android/app/build.gradle.kts android/gradle/libs.versions.toml \
    android/app/src/main/kotlin/dev/kudde/android/MainActivity.kt \
    android/app/src/test/snapshots/agents.png android/README.md
}

frame_fixtures_run_rust_and_android() {
  # crates/kudde-mobile/src/vt/grid.rs checks them against its goldens.
  expect "rust android" android/app/src/test/resources/frames/session-80x24.txt
}

push_crate_runs_rust_core_push() {
  expect "rust core push" crates/kudde-push/src/main.rs crates/kudde-push/tests/scratch_core.rs \
    crates/kudde-push/Cargo.toml crates/kudde-push/README.md
}

mobile_crate_runs_rust_core_android() {
  expect "rust core android" crates/kudde-mobile/src/lib.rs crates/kudde-mobile/tests/sshd.rs
}

mobile_dependencies_run_rust_core_android() {
  # kudde-mobile depends on them; kudde-push does not.
  expect_each "rust core android" crates/kudde-chat/src/lib.rs crates/kudde-changes/src/lib.rs
}

proto_crate_runs_every_rust_area() {
  # Both kudde-mobile and kudde-push depend on it.
  expect "rust core android push" crates/herdr-proto/src/wire.rs
}

bindgen_crate_runs_rust_android() {
  expect "rust android" crates/uniffi-bindgen/src/main.rs
}

new_crate_runs_every_rust_area() {
  expect "rust core android push" crates/kudde-new/src/lib.rs
}

workspace_files_run_every_rust_area() {
  expect_each "rust core android push" Cargo.toml Cargo.lock rust-toolchain.toml
}

version_runs_rust_and_android() {
  # kudde-mobile's unit tests and the APK's versionCode read it.
  expect "rust android" VERSION
}

design_tokens_run_android() {
  expect android design-tokens.json
}

rust_scripts_run_rust() {
  expect_each rust deny.toml scripts/check-release-tag.sh scripts/test-check-release-tag.sh \
    scripts/test-release-sign-gate.sh scripts/test-fetch-herdr-core.sh \
    scripts/check-restricted-key.sh scripts/test-check-restricted-key.sh \
    scripts/grid-golden.c scripts/record-grid-goldens.sh
}

core_fetch_runs_rust_and_core() {
  expect_each "rust core" scripts/fetch-herdr-core.sh scripts/herdr-core-pins.txt
}

ghostty_build_runs_rust_and_android() {
  expect "rust android" scripts/build-ghostty.sh
}

android_scripts_run_android() {
  expect_each android scripts/build-rust-android.sh scripts/uniffi-kotlin.toml
}

ci_workflow_runs_everything() {
  expect_each all .github/workflows/ci.yml scripts/ci-changes.sh scripts/test-ci-changes.sh
}

core_workflow_runs_core() {
  expect core .github/workflows/core-tests.yml
}

site_workflow_runs_site() {
  expect site .github/workflows/site-tests.yml
}

release_sign_workflow_runs_rust() {
  # scripts/test-release-sign-gate.sh runs its gate step.
  expect rust .github/workflows/release-sign.yml
}

release_workflow_runs_nothing() {
  expect none .github/workflows/release.yml
}

unknown_path_runs_everything() {
  expect_each all newdir/x .gitignore Makefile .github/workflows/new.yml .github/dependabot.yml \
    scripts/phone.sh sitemap.xml crates-old/x docs README.md.bak
}

unknown_path_among_others_runs_everything() {
  expect all docs/release.md site/src/pages/index.astro newdir/x
}

areas_add_up() {
  expect "android site" site/src/pages/index.astro android/app/build.gradle.kts docs/release.md
  expect "rust core push site" crates/kudde-push/src/main.rs docs/release-signing-fingerprint.txt
}

empty_change_runs_nothing() {
  expect none
  expect none ""
  expect none "" docs/release.md ""
}

paths_with_spaces() {
  expect site "site/src/pages/a b.astro"
  expect android "android/app/src/main/a b.kt"
  expect none "docs/a b.md" "openspec/changes/x y/z.md"
  expect all "new dir/x y"
  expect "rust core push" "crates/kudde-push/tests/data/a b.txt"
}

# git quotes unusual names unless core.quotePath is off; such a line matches
# nothing and must not be taken for docs.
quoted_path_runs_everything() {
  expect all '"docs/caf\303\251.md"'
}

last_line_without_newline_counts() {
  local out
  count=$((count + 1))
  out=$(printf 'docs/release.md\nandroid/app/x.kt' | "$route" 2>&1) || true
  if [[ $out != *android=true* ]]; then
    echo "FAIL ${FUNCNAME[0]}: printed $out" >&2
    failures=$((failures + 1))
  fi
}

for test in site_change_runs_only_site readme_runs_rust signing_fingerprint_runs_site \
  non_code_runs_nothing android_change_runs_only_android frame_fixtures_run_rust_and_android \
  push_crate_runs_rust_core_push mobile_crate_runs_rust_core_android \
  mobile_dependencies_run_rust_core_android proto_crate_runs_every_rust_area \
  bindgen_crate_runs_rust_android new_crate_runs_every_rust_area \
  workspace_files_run_every_rust_area version_runs_rust_and_android design_tokens_run_android \
  rust_scripts_run_rust core_fetch_runs_rust_and_core ghostty_build_runs_rust_and_android \
  android_scripts_run_android ci_workflow_runs_everything core_workflow_runs_core \
  site_workflow_runs_site release_sign_workflow_runs_rust release_workflow_runs_nothing \
  unknown_path_runs_everything unknown_path_among_others_runs_everything areas_add_up \
  empty_change_runs_nothing paths_with_spaces quoted_path_runs_everything \
  last_line_without_newline_counts; do
  "$test"
done

echo "$((count - failures)) passed, $failures failed"
[[ $failures -eq 0 ]]
