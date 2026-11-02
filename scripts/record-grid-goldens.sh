#!/usr/bin/env bash
# Records the cell-grid goldens for the app's VT (terminal-view spec,
# "Cell-grid fidelity"): every crates/herdr-mobile/src/vt/goldens/<name>-<cols>x<rows>.vt
# is fed to scripts/grid-golden.c, a C port of herdr-app's VT pipeline linked
# against the same libghostty-vt build the app uses, and its grid is written
# next to it as <name>-<cols>x<rows>.grid. The Rust test
# (vt::grid::tests::goldens_match_herdr_apps_pipeline) compares the app's own
# reader with those files.
#
# Needs build/ghostty/host (scripts/build-ghostty.sh x86_64-linux-gnu) and a C
# compiler (cc). Run it after changing a stream, the Ghostty pin or the reader;
# review the diff of the .grid files before committing.
#
# Usage: scripts/record-grid-goldens.sh
# Exit: 0 recorded, 1 failed.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
ghostty="$repo/build/ghostty/host"
goldens="$repo/crates/herdr-mobile/src/vt/goldens"
[[ -f "$ghostty/libghostty-vt.a" ]] || {
  echo "missing $ghostty/libghostty-vt.a: run scripts/build-ghostty.sh x86_64-linux-gnu" >&2
  exit 1
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cc -std=c11 -O1 -Wall -Wextra -Werror -I"$ghostty/include" \
  "$repo/scripts/grid-golden.c" "$ghostty/libghostty-vt.a" -o "$work/grid-golden"

shopt -s nullglob
count=0
for stream in "$goldens"/*.vt; do
  base=${stream%.vt}
  size=${base##*-}
  [[ $size =~ ^([0-9]+)x([0-9]+)$ ]] || {
    echo "$stream: name must end in -<cols>x<rows>.vt" >&2
    exit 1
  }
  "$work/grid-golden" "$stream" "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" >"$base.grid"
  count=$((count + 1))
done
echo "recorded $count goldens in $goldens"
