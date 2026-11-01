#!/usr/bin/env bash
# Builds libherdr_mobile.so for the app (arm64-v8a for phones, x86_64 for the
# emulator) into android/app/src/main/jniLibs/<abi>/, and generates its Kotlin
# bindings with the workspace uniffi-bindgen (library mode) into
# android/app/build/generated/uniffi/kotlin.
#
# Usage: scripts/build-rust-android.sh [--release] [--features <list>]
#   --features vt links libghostty-vt (scripts/build-ghostty.sh for both targets first).
# Needs: cargo-ndk, the Rust targets aarch64-linux-android and
# x86_64-linux-android, and ANDROID_HOME with the NDK below installed.
set -euo pipefail

NDK_VERSION=27.1.12297006
API_LEVEL=31

profile=debug
cargo_args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) profile=release; cargo_args+=(--release) ;;
    --features) [[ $# -ge 2 ]] || { echo "usage: $0 [--release] [--features <list>]" >&2; exit 2; }
      cargo_args+=(--features "$2"); shift ;;
    *) echo "usage: $0 [--release] [--features <list>]" >&2; exit 2 ;;
  esac
  shift
done

repo=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo"

# The NDK that android/app/build.gradle.kts names, whatever the environment says.
export ANDROID_NDK_HOME="${ANDROID_HOME:?set ANDROID_HOME}/ndk/$NDK_VERSION"
# uniffi-bindgen below reads the library from target/, so cargo writes there
# even when CARGO_TARGET_DIR or build.target-dir points elsewhere.
export CARGO_TARGET_DIR="$repo/target"

# cargo-ndk skips copying over a newer file, so a debug library would survive a
# --release build: start from an empty directory.
rm -rf android/app/src/main/jniLibs
cargo ndk -t arm64-v8a -t x86_64 -P "$API_LEVEL" \
  -o android/app/src/main/jniLibs \
  build -p herdr-mobile --locked "${cargo_args[@]}"

out=android/app/build/generated/uniffi/kotlin
rm -rf "$out"
cargo run -p uniffi-bindgen --locked -- generate \
  --library "target/aarch64-linux-android/$profile/libherdr_mobile.so" \
  --language kotlin --no-format --out-dir "$out"
