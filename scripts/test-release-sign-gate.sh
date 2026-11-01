#!/usr/bin/env bash
# Tests for the gate step of .github/workflows/release-sign.yml (the checks that
# run before any signing key is used), against a stub gh that answers the API
# calls from FAKE_* variables.
#
# Usage: scripts/test-release-sign-gate.sh
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
failures=0
count=0

# The step's `run: |` block, unindented.
awk '/^ *id: gate$/ { found = 1; next }
     found && /^ *run: \|$/ { inside = 1; next }
     inside && /[^ ]/ && !/^          / { exit }
     inside { sub(/^          /, ""); print }' \
  "$repo/.github/workflows/release-sign.yml" > "$scratch/gate.sh"
grep -q 'gh release list' "$scratch/gate.sh" || { echo "gate step not found" >&2; exit 1; }

mkdir "$scratch/bin"
cat > "$scratch/bin/gh" <<'EOF'
#!/usr/bin/env bash
filter=. args=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --jq) filter=$2; shift 2 ;;
    -H | --repo | --limit | --json) shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
answer() { jq -r "$filter" <<<"$1"; }
if [[ ${args[0]} == release ]]; then
  [[ -z ${FAKE_RELEASES_FAIL:-} ]] || { echo "HTTP 502" >&2; exit 1; }
  answer "${FAKE_RELEASES:-[]}"
  exit
fi
case ${args[1]#repos/owner/repo/} in
  git/ref/tags/*) answer "{\"object\": {\"type\": \"${FAKE_REF_TYPE:-tag}\", \"sha\": \"${FAKE_REF_SHA:-tagobject}\"}}" ;;
  git/tags/tagobject) answer "{\"object\": {\"sha\": \"${FAKE_TAG_COMMIT:-built}\"}}" ;;
  branches/main) answer '{"commit": {"sha": "mainhead"}}' ;;
  compare/built...mainhead) answer "{\"status\": \"${FAKE_STATUS:-ahead}\"}" ;;
  contents/VERSION\?ref=built) echo "${FAKE_VERSION:-0.0.2}" ;;
  *) echo "unexpected call: ${args[*]}" >&2; exit 1 ;;
esac
EOF
chmod +x "$scratch/bin/gh"

# expect pass|fail [VAR=value ...]: runs the gate for tag v0.0.2 of commit "built".
expect() {
  local want=$1 status=0
  shift
  count=$((count + 1))
  env -i PATH="$scratch/bin:$PATH" GITHUB_REPOSITORY=owner/repo TAG=v0.0.2 SHA=built "$@" \
    bash --noprofile --norc -eo pipefail "$scratch/gate.sh" > /dev/null 2>&1 || status=$?
  if [[ $want == pass && $status -ne 0 ]] || [[ $want == fail && $status -eq 0 ]]; then
    echo "FAIL: expected $want with ${*:-defaults}, exit $status" >&2
    failures=$((failures + 1))
  fi
}

expect pass                                              # first release
expect pass FAKE_RELEASES='[{"tagName": "v0.0.1"}]'
expect pass FAKE_RELEASES='[{"tagName": "nightly"}, {"tagName": "v0.0.1"}]'
expect pass FAKE_REF_TYPE=commit FAKE_REF_SHA=built      # lightweight tag
expect pass FAKE_STATUS=identical                        # tag on main's head
expect fail TAG=main                                     # not a release tag
expect fail TAG=v0.0.2/../x
expect fail FAKE_TAG_COMMIT=other                        # tag is not the built commit
expect fail FAKE_REF_TYPE=commit FAKE_REF_SHA=other
expect fail FAKE_STATUS=diverged                         # commit off main
expect fail FAKE_STATUS=behind
expect fail FAKE_VERSION=0.0.3                           # VERSION disagrees with the tag
expect fail FAKE_RELEASES='[{"tagName": "v0.0.3"}]'      # a newer release is out
expect fail FAKE_RELEASES='[{"tagName": "v0.0.2"}]'      # this version is out
expect fail FAKE_RELEASES_FAIL=1                         # API error

echo "$((count - failures)) passed, $failures failed"
[[ $failures -eq 0 ]]
