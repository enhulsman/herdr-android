#!/usr/bin/env bash
# Usage: scripts/build-ghostty.sh <aarch64-linux-android|x86_64-linux-android>
set -euo pipefail

usage() {
  echo "usage: $0 <aarch64-linux-android|x86_64-linux-android>" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
target=$1
case "$target" in
  aarch64-linux-android) abi=arm64-v8a ;;
  x86_64-linux-android) abi=x86_64 ;;
  *) usage ;;
esac

repo=$(cd "$(dirname "$0")/.." && pwd)
cache="${XDG_CACHE_HOME:-$HOME/.cache}/herdr-android"
export ANDROID_NDK_HOME="${ANDROID_HOME:?set ANDROID_HOME}/ndk/27.1.12297006"
[[ -d "$ANDROID_NDK_HOME" ]] || {
  echo "NDK not found: $ANDROID_NDK_HOME" >&2
  exit 1
}

zig_version=0.16.0
# SHA-256: https://ziglang.org/download/index.json, 0.16.0, x86_64-linux.
zig_sha256=70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00
zig_name="zig-x86_64-linux-$zig_version"
archive="$cache/$zig_name.tar.xz"
ghostty_hash=1e5cdb865807abc990181efb8ebfa9122955cce2
ghostty="$cache/ghostty-$ghostty_hash"

mkdir -p "$cache"
if ! [[ -f "$archive" ]] || ! printf '%s  %s\n' "$zig_sha256" "$archive" | sha256sum --check --status; then
  curl --fail --location --show-error \
    "https://ziglang.org/download/$zig_version/$zig_name.tar.xz" \
    --output "$archive.part"
  printf '%s  %s\n' "$zig_sha256" "$archive.part" | sha256sum --check
  mv "$archive.part" "$archive"
fi
tar -xJf "$archive" -C "$cache"

if [[ ! -d "$ghostty/.git" ]]; then
  git init "$ghostty"
  git -C "$ghostty" remote add origin https://github.com/ghostty-org/ghostty
fi
if ! git -C "$ghostty" cat-file -e "$ghostty_hash^{commit}" 2>/dev/null; then
  git -C "$ghostty" fetch --depth 1 origin "$ghostty_hash"
fi
git -C "$ghostty" checkout --detach "$ghostty_hash"
[[ $(git -C "$ghostty" rev-parse HEAD) == "$ghostty_hash" ]] || {
  echo "Ghostty HEAD does not match $ghostty_hash" >&2
  exit 1
}

cd "$ghostty"
"$cache/$zig_name/zig" build \
  -Demit-lib-vt -Doptimize=ReleaseFast -Dcpu=baseline -Dtarget="$target"
out="$repo/build/ghostty/$abi"
mkdir -p "$out/include"
cp -L zig-out/lib/libghostty-vt.so "$out/libghostty-vt.so"
cp -R include/ghostty "$out/include/"
