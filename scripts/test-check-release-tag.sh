#!/usr/bin/env bash
# Tests for scripts/check-release-tag.sh, each against a scratch git repository
# with an origin/main ref, as the release workflow's full-history checkout has.
#
# Usage: scripts/test-check-release-tag.sh
set -euo pipefail

# Independent of the caller's git config (signing, default branch, hooks).
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.org
export GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.org

check=$(cd "$(dirname "$0")" && pwd)/check-release-tag.sh
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
failures=0
count=0

# A fresh repository whose main has one commit with VERSION=$1.
new_repo() {
  repo="$scratch/repo$count"
  git init -q -b main "$repo"
  commit "$1"
}

# Commits VERSION=$1 on the current branch and moves origin/main to main.
commit() {
  printf '%s\n' "$1" > "$repo/VERSION"
  git -C "$repo" add VERSION
  git -C "$repo" commit -q -m "version $1"
  git -C "$repo" update-ref refs/remotes/origin/main refs/heads/main
}

tag() {
  git -C "$repo" tag -a -m "$1" "$@"
}

# expect pass|fail <tag> [expected stdout]
expect() {
  local want=$1 name=$2 out status=0
  count=$((count + 1))
  out=$(cd "$repo" && "$check" "$name" 2>/dev/null) || status=$?
  if [[ $want == pass && $status -ne 0 ]] || [[ $want == fail && $status -ne 1 ]]; then
    echo "FAIL ${FUNCNAME[1]}: $name expected $want, exit $status" >&2
    failures=$((failures + 1))
  elif [[ $# -ge 3 && $out != "$3" ]]; then
    echo "FAIL ${FUNCNAME[1]}: $name printed '$out', expected '$3'" >&2
    failures=$((failures + 1))
  fi
}

first_release_passes() {
  new_repo 0.0.1; tag v0.0.1
  expect pass v0.0.1 version_code=1
}

lightweight_tag_passes() {
  new_repo 0.0.1; git -C "$repo" tag v0.0.1
  expect pass v0.0.1 version_code=1
}

next_release_passes() {
  new_repo 0.0.1; tag v0.0.1
  commit 1.2.3; tag v1.2.3
  expect pass v1.2.3 version_code=10203
}

tag_behind_newer_main_passes() {
  new_repo 0.0.1; tag v0.0.1
  commit 0.0.2
  expect pass v0.0.1
}

version_file_mismatch_fails() {
  new_repo 0.0.2; tag v0.0.1
  expect fail v0.0.1
}

tag_off_main_fails() {
  new_repo 0.0.1; tag v0.0.1
  git -C "$repo" checkout -q -b side
  printf 'x\n' > "$repo/side.txt"
  git -C "$repo" add side.txt
  git -C "$repo" commit -q -m side
  printf '0.0.2\n' > "$repo/VERSION"
  git -C "$repo" commit -q -am "version 0.0.2"
  tag v0.0.2
  expect fail v0.0.2
}

version_code_going_backwards_fails() {
  new_repo 0.1.0; tag v0.1.0
  commit 0.0.9; tag v0.0.9
  expect fail v0.0.9
}

older_tag_released_after_newer_fails() {
  new_repo 0.0.2; git -C "$repo" tag v0.0.3
  tag v0.0.2
  expect fail v0.0.2
}

malformed_tags_fail() {
  for name in v1.2 v1.2.3.4 v1.2.3-rc1 1.2.3 v1.100.0 v1.0.100 v01.2.3 v1.02.3; do
    new_repo "${name#v}"; tag "$name"
    expect fail "$name"
  done
}

other_tags_are_ignored() {
  new_repo 0.0.1; git -C "$repo" tag v-test; git -C "$repo" tag v9.9.9-rc1; git -C "$repo" tag other
  tag v0.0.1
  expect pass v0.0.1 version_code=1
}

missing_tag_fails() {
  new_repo 0.0.1
  expect fail v0.0.1
}

for test in first_release_passes lightweight_tag_passes next_release_passes \
  tag_behind_newer_main_passes version_file_mismatch_fails tag_off_main_fails \
  version_code_going_backwards_fails older_tag_released_after_newer_fails \
  malformed_tags_fail other_tags_are_ignored missing_tag_fails; do
  "$test"
done

echo "$((count - failures)) passed, $failures failed"
[[ $failures -eq 0 ]]
