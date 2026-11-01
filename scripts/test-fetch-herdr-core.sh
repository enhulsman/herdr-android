#!/usr/bin/env bash
# Tests for scripts/fetch-herdr-core.sh against a fake release folder reached
# through file:// URLs (HERDR_RELEASES_URL), with its own pins file and cache.
#
# Usage: scripts/test-fetch-herdr-core.sh
set -euo pipefail

fetch=$(cd "$(dirname "$0")" && pwd)/fetch-herdr-core.sh
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
failures=0

releases="$scratch/releases"
export HERDR_RELEASES_URL="file://$releases"
export HERDR_CORE_PINS="$scratch/pins.txt"
export HERDR_CORE_CACHE="$scratch/cache"

sha() { sha256sum "$1" | cut -d' ' -f1; }

# release <version> [script body]: an archive holding a fake herdr-core that
# prints its version, and that version's manifest.
release() {
  local version=$1 body=${2:-} dir="$releases/core/$1" work="$scratch/work-$1"
  mkdir -p "$dir" "$work"
  printf '#!/bin/sh\n%s\necho "herdr-core %s"\n' "$body" "$version" > "$work/herdr-core"
  chmod 755 "$work/herdr-core"
  tar -czf "$dir/herdr-core-linux-x86_64.tar.gz" -C "$work" herdr-core
  manifest "$version" "$(sha "$dir/herdr-core-linux-x86_64.tar.gz")" > "$dir/manifest.json"
}

manifest() {
  cat <<EOF
{
  "version": "$1",
  "generation": 1,
  "notes": "herdr-core $1",
  "targets": {
    "linux-aarch64": {
      "url": "$HERDR_RELEASES_URL/core/$1/herdr-core-linux-aarch64.tar.gz",
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
    },
    "linux-x86_64": {
      "url": "${3:-$HERDR_RELEASES_URL/core/$1/herdr-core-linux-x86_64.tar.gz}",
      "sha256": "$2"
    }
  }
}
EOF
}

latest() { cp "$releases/core/$1/manifest.json" "$releases/core/latest.json"; }

pin() { printf '# version target sha256\n%s linux-x86_64 %s\n' "$1" "$2" > "$HERDR_CORE_PINS"; }

reset() {
  rm -rf "$releases" "$HERDR_CORE_CACHE"
  : > "$HERDR_CORE_PINS"
}

fail() {
  echo "FAIL ${FUNCNAME[1]}: $*" >&2
  failures=$((failures + 1))
}

# run <args...>: sets out (stdout) and status.
run() {
  status=0
  out=$("$fetch" "$@" 2>"$scratch/stderr") || status=$?
}

pinned_version_downloads_and_prints_the_binary() {
  reset; release 0.1.15
  pin 0.1.15 "$(sha "$releases/core/0.1.15/herdr-core-linux-x86_64.tar.gz")"
  run 0.1.15
  [[ $status -eq 0 ]] || { fail "exit $status: $(cat "$scratch/stderr")"; return; }
  [[ $out == "$HERDR_CORE_CACHE/0.1.15/herdr-core" ]] || fail "printed '$out'"
  [[ $("$out" --version) == "herdr-core 0.1.15" ]] || fail "binary does not run"
}

latest_resolves_through_latest_json() {
  reset; release 0.1.15; release 0.1.16; latest 0.1.16
  run latest
  [[ $status -eq 0 ]] || { fail "exit $status: $(cat "$scratch/stderr")"; return; }
  [[ $out == "$HERDR_CORE_CACHE/0.1.16/herdr-core" ]] || fail "printed '$out'"
}

latest_needs_no_pin_but_checks_its_manifest() {
  reset; release 0.1.16; latest 0.1.16
  printf 'tampered' >> "$releases/core/0.1.16/herdr-core-linux-x86_64.tar.gz"
  run latest
  [[ $status -ne 0 ]] || fail "accepted an archive that does not match its manifest"
  [[ ! -e "$HERDR_CORE_CACHE/0.1.16/herdr-core" ]] || fail "left a binary behind"
}

a_version_without_a_pin_is_refused() {
  reset; release 0.1.15
  run 0.1.15
  [[ $status -ne 0 ]] || fail "fetched a version with no committed checksum"
  grep -q 'no pinned checksum' "$scratch/stderr" || fail "stderr: $(cat "$scratch/stderr")"
}

a_pin_that_disagrees_with_the_archive_is_refused() {
  reset; release 0.1.15
  # Upstream republished 0.1.15: archive and manifest agree, the pin does not.
  pin 0.1.15 1111111111111111111111111111111111111111111111111111111111111111
  run 0.1.15
  [[ $status -ne 0 ]] || fail "accepted an archive that does not match the pin"
  grep -q 'checksum' "$scratch/stderr" || fail "stderr: $(cat "$scratch/stderr")"
  [[ ! -e "$HERDR_CORE_CACHE/0.1.15/herdr-core" ]] || fail "left a binary behind"
}

latest_that_is_pinned_is_checked_against_the_pin() {
  reset; release 0.1.15; latest 0.1.15
  pin 0.1.15 1111111111111111111111111111111111111111111111111111111111111111
  run latest
  [[ $status -ne 0 ]] || fail "latest skipped the pin of the same version"
}

a_url_off_the_release_server_is_refused() {
  reset; release 0.1.16
  manifest 0.1.16 "$(sha "$releases/core/0.1.16/herdr-core-linux-x86_64.tar.gz")" \
    "file://$scratch/elsewhere/herdr-core-linux-x86_64.tar.gz" > "$releases/core/latest.json"
  mkdir -p "$scratch/elsewhere"
  cp "$releases/core/0.1.16/herdr-core-linux-x86_64.tar.gz" "$scratch/elsewhere/"
  run latest
  [[ $status -ne 0 ]] || fail "followed a URL outside the release server"
}

the_cache_is_used_without_the_network() {
  reset; release 0.1.15
  pin 0.1.15 "$(sha "$releases/core/0.1.15/herdr-core-linux-x86_64.tar.gz")"
  run 0.1.15
  rm -rf "$releases"
  run 0.1.15
  [[ $status -eq 0 ]] || fail "second run needed the network: $(cat "$scratch/stderr")"
  [[ $out == "$HERDR_CORE_CACHE/0.1.15/herdr-core" ]] || fail "printed '$out'"
}

a_damaged_cache_is_fetched_again() {
  reset; release 0.1.15
  pin 0.1.15 "$(sha "$releases/core/0.1.15/herdr-core-linux-x86_64.tar.gz")"
  run 0.1.15
  printf 'echo damaged\n' >> "$HERDR_CORE_CACHE/0.1.15/herdr-core"
  run 0.1.15
  [[ $status -eq 0 ]] || { fail "exit $status: $(cat "$scratch/stderr")"; return; }
  [[ $("$out" --version) == "herdr-core 0.1.15" ]] || fail "kept the damaged binary"
}

a_binary_that_does_not_run_is_refused() {
  reset; release 0.1.16 'exit 1'; latest 0.1.16
  run latest
  [[ $status -ne 0 ]] || fail "accepted a binary that fails --version"
  [[ ! -e "$HERDR_CORE_CACHE/0.1.16/herdr-core" ]] || fail "left a binary behind"
}

a_bad_version_argument_is_refused() {
  reset
  for arg in "" "../0.1.15" "0.1.15/x" "v0.1.15"; do
    run "$arg"
    [[ $status -eq 2 ]] || fail "'$arg' exit $status, expected 2"
  done
}

the_committed_pins_name_the_supported_minimum() {
  local pins min
  pins=$(cd "$(dirname "$0")" && pwd)/herdr-core-pins.txt
  min=$(sed -n 's/^pub const MIN_CORE: &str = "\(.*\)";/\1/p' \
    "$(dirname "$pins")/../crates/herdr-proto/src/compat.rs")
  grep -Eq "^$min linux-x86_64 [0-9a-f]{64}$" "$pins" || fail "no pin for MIN_CORE $min in $pins"
}

pinned_version_downloads_and_prints_the_binary
latest_resolves_through_latest_json
latest_needs_no_pin_but_checks_its_manifest
a_version_without_a_pin_is_refused
a_pin_that_disagrees_with_the_archive_is_refused
latest_that_is_pinned_is_checked_against_the_pin
a_url_off_the_release_server_is_refused
the_cache_is_used_without_the_network
a_damaged_cache_is_fetched_again
a_binary_that_does_not_run_is_refused
a_bad_version_argument_is_refused
the_committed_pins_name_the_supported_minimum

if [[ $failures -ne 0 ]]; then
  echo "$failures failure(s)" >&2
  exit 1
fi
echo "fetch-herdr-core: all tests passed"
