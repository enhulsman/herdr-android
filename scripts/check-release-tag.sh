#!/usr/bin/env bash
# Checks that a release tag may be built, from inside a full-history clone:
# - the tag is vMAJOR.MINOR.PATCH (no leading zeros, minor and patch below 100),
# - the VERSION file at the tag says the same version,
# - the tag's commit is on origin/main,
# - its versionCode (major*10000 + minor*100 + patch, as in
#   android/app/build.gradle.kts) is above that of every other release tag.
#   Tags not of that form never produced a release and are ignored.
# Prints version_code=<code> on success; exits 1 with a reason otherwise.
#
# Usage: scripts/check-release-tag.sh <tag>
set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 <tag>" >&2; exit 2; }
tag=$1
fail() { echo "$tag: $*" >&2; exit 1; }

code() {
  [[ $1 =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] || return 1
  echo $((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))
}

current=$(code "$tag") || fail "not a release tag (vMAJOR.MINOR.PATCH, minor and patch below 100)"
commit=$(git rev-parse --verify --quiet "refs/tags/$tag^{commit}") || fail "no such tag"

version=$(git show "$commit:VERSION") || fail "no VERSION file at the tag"
[[ "v$version" == "$tag" ]] || fail "VERSION at the tag is '$version'"

git merge-base --is-ancestor "$commit" refs/remotes/origin/main || fail "commit $commit is not on origin/main"

while read -r other; do
  [[ $other == "$tag" ]] && continue
  previous=$(code "$other") || continue
  ((current > previous)) || fail "versionCode $current is not above $previous of $other"
done < <(git tag --list 'v*')

echo "version_code=$current"
