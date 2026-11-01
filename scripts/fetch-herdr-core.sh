#!/usr/bin/env bash
# Fetches a released herdr-core for Linux x86_64 and prints the path of the
# binary, for the scratch-core tests (crates/herdr-proto/tests/scratch_core.rs).
#
#   scripts/fetch-herdr-core.sh 0.1.15   # a pinned version
#   scripts/fetch-herdr-core.sh latest   # whatever the release server calls latest
#
# A numbered version must have its archive checksum in herdr-core-pins.txt
# (committed); the release manifest only supplies the URL. `latest` is checked
# against its own manifest (latest.json), and against a pin too when that
# version is pinned. Download URLs must be on the release server.
#
# Cached under target/herdr-core/<version>/ (archive and binary); a cached
# binary is reused without the network when the archive still has the expected
# checksum and the binary is identical to the one in it.
#
# Environment (tests): HERDR_RELEASES_URL (default https://releases.herdr.dev),
# HERDR_CORE_PINS, HERDR_CORE_CACHE.
# Exit: 0 printed the path, 1 failed, 2 usage.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
releases=${HERDR_RELEASES_URL:-https://releases.herdr.dev}
releases=${releases%/}
pins=${HERDR_CORE_PINS:-$here/herdr-core-pins.txt}
cache=${HERDR_CORE_CACHE:-$(dirname "$here")/target/herdr-core}
target=linux-x86_64

fail() { echo "fetch-herdr-core: $*" >&2; exit 1; }

want=${1:-}
if [[ $# -ne 1 || ! ( $want == latest || $want =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ) ]]; then
  echo "usage: fetch-herdr-core.sh <x.y.z|latest>" >&2
  exit 2
fi
[[ $(uname -s)-$(uname -m) == Linux-x86_64 ]] || fail "only Linux x86_64 is supported"

sha() { sha256sum "$1" | cut -d' ' -f1; }

# The pinned archive checksum of a version, or nothing.
pinned() {
  [[ -f $pins ]] || return 0
  awk -v v="$1" -v t="$target" '$1 == v && $2 == t { print $3 }' "$pins"
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Reads the manifest at $1 into $tmp/manifest.json; sets version, url, listed.
read_manifest() {
  curl -fsSL "$1" -o "$tmp/manifest.json" || fail "cannot fetch $1"
  local fields
  fields=$(python3 -I - "$tmp/manifest.json" "$target" <<'EOF'
import json, sys
m = json.load(open(sys.argv[1]))
t = m.get("targets", {}).get(sys.argv[2]) or {}
print(m.get("version", ""), t.get("url", ""), t.get("sha256", ""))
EOF
  ) || fail "unreadable manifest $1"
  read -r version url listed <<<"$fields"
  [[ -n $url && -n $listed ]] || fail "no $target build in $1"
}

if [[ $want == latest ]]; then
  read_manifest "$releases/core/latest.json"
  [[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "latest.json names version '$version'"
  expect=$(pinned "$version")
  if [[ -n $expect && $expect != "$listed" ]]; then
    fail "latest is $version, whose manifest checksum differs from its pin"
  fi
  expect=${expect:-$listed}
else
  version=$want
  expect=$(pinned "$version")
  [[ -n $expect ]] || fail "no pinned checksum for $version $target in $pins"
fi

dir="$cache/$version"
bin="$dir/herdr-core"
# Reuse: the cached archive still has the expected checksum and the cached
# binary is byte for byte the one inside it. Nothing else in the cache is trusted.
if [[ -x $bin && -f $dir/archive.tar.gz && $(sha "$dir/archive.tar.gz") == "$expect" ]]; then
  mkdir "$tmp/cached"
  if tar -xzf "$dir/archive.tar.gz" -C "$tmp/cached" herdr-core 2>/dev/null &&
    cmp -s "$tmp/cached/herdr-core" "$bin"; then
    echo "$bin"
    exit 0
  fi
fi

if [[ $want != latest ]]; then
  read_manifest "$releases/core/$version/manifest.json"
  [[ $version == "$want" ]] || fail "manifest for $want names version $version"
fi
case $url in
  "$releases"/core/"$version"/*) ;;
  *) fail "download URL is not on $releases: $url" ;;
esac
[[ $url != *[?#]* ]] || fail "download URL carries a query: $url"

curl -fsSL "$url" -o "$tmp/archive.tar.gz" || fail "download failed: $url"
got=$(sha "$tmp/archive.tar.gz")
[[ $got == "$expect" ]] || fail "checksum mismatch for $url: got $got, expected $expect"
mkdir "$tmp/x"
tar -xzf "$tmp/archive.tar.gz" -C "$tmp/x" herdr-core || fail "no herdr-core in $url"
"$tmp/x/herdr-core" --version > /dev/null 2>&1 || fail "the downloaded herdr-core does not run here"

rm -rf "$dir"
mkdir -p "$dir"
mv "$tmp/archive.tar.gz" "$dir/archive.tar.gz"
mv "$tmp/x/herdr-core" "$bin"
echo "$bin"
