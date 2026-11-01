#!/usr/bin/env bash
# Tests for scripts/setup-release-keys.sh. Runs it in a scratch repository with a
# fake `gh` on PATH, so nothing reaches GitHub. Signs the unsigned release APK with
# the uploaded keystore exactly as release-sign.yml does.
#
# Needs keytool, ssh-keygen, apksigner from build-tools 36.0.0 and the unsigned
# release APK (cd android && ./gradlew assembleRelease).
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
apksigner="${ANDROID_HOME:?set ANDROID_HOME}/build-tools/36.0.0/apksigner"
apk="$root/android/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -x $apksigner && -f $apk ]] || { echo "needs $apksigner and $apk"; exit 2; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
pass=0 fail=0
check() {
  local name=$1; shift
  if "$@"; then pass=$((pass + 1)); else echo "FAIL: $name"; fail=$((fail + 1)); fi
}
absent() { ! grep -qF -- "$1" "$2"; }

# A fresh scratch repository with the placeholder fingerprint and a fake gh. Sets S.
sandbox() {
  S=$(mktemp -d "$work/s.XXXX")
  mkdir -p "$S/repo/scripts" "$S/repo/docs" "$S/bin" "$S/fake" "$S/keys"
  cp "$here/setup-release-keys.sh" "$S/repo/scripts/"
  cat > "$S/repo/docs/release-signing-fingerprint.txt" <<'EOF'
# SHA-256 of the release signing certificate.
# M0.4: replace the next line with the real fingerprint.
PLACEHOLDER-replace-in-M0.4
EOF
  git -C "$S/repo" init -q
  git -C "$S/repo" remote add origin git@github.com-test:owner/repo.git
  touch "$S/fake/calls"
  cat > "$S/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE/calls"
case $1 in
  auth) echo fake-token ;;
  secret) cat > "$FAKE/secret-$3" ;;
  api)
    if [[ $* == *"-X PUT"* ]]; then cat > "$FAKE/env-put"
    elif [[ $* == *"-X POST"* ]]; then :
    elif [[ $* == *"/secrets"* ]]; then
      [[ -n ${FAKE_API_ERROR:-} ]] && { echo "gh: Bad credentials (HTTP 401)" >&2; exit 1; }
      [[ -z ${FAKE_SECRETS:-} ]] && { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
      printf '%s\n' $FAKE_SECRETS
    elif [[ $* == *deployment-branch-policies* ]]; then
      [[ -n ${FAKE_POLICIES:-} ]] && printf '%s\n' $FAKE_POLICIES
    fi ;;
esac
exit 0
EOF
  chmod +x "$S/bin/gh"
}
run() {
  (cd "$S/repo" && PATH="$S/bin:$PATH" FAKE="$S/fake" KEYS_PARENT="$S/keys" GH_TOKEN='' \
    scripts/setup-release-keys.sh "$@") > "$S/out" 2>&1
}
keydir() { compgen -G "$S/keys/herdr-release-keys.*" | head -1; }

# --- generate, then upload: the normal path -------------------------------------
sandbox
run generate; rc=$?
check "generate exits 0" test "$rc" -eq 0
dir=$(keydir)
check "generate makes one key folder" test "$(compgen -G "$S/keys/herdr-release-keys.*" | wc -l)" -eq 1
check "the key folder is private" test "$(stat -c %a "$dir")" = 700
check "generate uploads nothing" absent "secret set" "$S/fake/calls"
check "generate creates no environment" absent "-X PUT" "$S/fake/calls"
check "generate leaves the docs alone" grep -qx PLACEHOLDER-replace-in-M0.4 "$S/repo/docs/release-signing-fingerprint.txt"
check "generate prints the upload command" grep -qF "upload $dir" "$S/out"
password=$(<"$dir/password")
check "the password is long" test "${#password}" -ge 32
check "generate does not print the password" absent "$password" "$S/out"
check "bitwarden.txt holds the password" grep -qF -- "$password" "$dir/bitwarden.txt"
check "bitwarden.txt holds the keystore" grep -qF -- "$(<"$dir/release.p12.b64")" "$dir/bitwarden.txt"
check "bitwarden.txt holds the herdr-push key" grep -qF -- "$(sed -n 2p "$dir/herdr-push")" "$dir/bitwarden.txt"

run upload "$dir"; rc=$?
check "upload exits 0" test "$rc" -eq 0
for name in RELEASE_KEYSTORE_B64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD HERDR_PUSH_SIGNING_KEY; do
  check "$name goes to the release environment" grep -qx "secret set $name --env release --repo owner/repo" "$S/fake/calls"
  check "$name is not empty" test -s "$S/fake/secret-$name"
done
check "the key password is the keystore password" cmp -s "$S/fake/secret-RELEASE_KEY_PASSWORD" "$S/fake/secret-RELEASE_KEYSTORE_PASSWORD"
check "the environment allows chosen branches only" grep -qF '"custom_branch_policies":true' "$S/fake/env-put"
check "the environment does not use protected branches" grep -qF '"protected_branches":false' "$S/fake/env-put"
check "main is added as the one deployment branch" grep -qx -- "api -X POST repos/owner/repo/environments/release/deployment-branch-policies -f name=main -f type=branch" "$S/fake/calls"

# Sign as release-sign.yml does, from the uploaded secrets.
expected=$(grep -v -e '^#' -e '^$' "$S/repo/docs/release-signing-fingerprint.txt")
check "the docs hold one fingerprint line, as the workflow reads it" grep -qxE '[0-9a-f]{64}' <<<"$expected"
base64 --decode < "$S/fake/secret-RELEASE_KEYSTORE_B64" > "$S/release.jks"
RELEASE_KEYSTORE_PASSWORD=$(<"$S/fake/secret-RELEASE_KEYSTORE_PASSWORD") \
RELEASE_KEY_PASSWORD=$(<"$S/fake/secret-RELEASE_KEY_PASSWORD") \
  "$apksigner" sign --ks "$S/release.jks" --ks-key-alias "$(<"$S/fake/secret-RELEASE_KEY_ALIAS")" \
  --ks-pass env:RELEASE_KEYSTORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD \
  --out "$S/signed.apk" "$apk" > /dev/null 2>&1
digest=$("$apksigner" verify --print-certs --min-sdk-version 31 "$S/signed.apk" 2>/dev/null \
  | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')
check "an APK signed with the secrets matches the recorded fingerprint" test "$digest" = "$expected"

# Sign herdr-push as the workflow does, verify with the committed allowed-signers file.
echo payload > "$S/herdr-push"
key="$S/push-key"; (umask 077; printf '%s\n' "$(<"$S/fake/secret-HERDR_PUSH_SIGNING_KEY")" > "$key")
ssh-keygen -q -Y sign -f "$key" -n herdr-push "$S/herdr-push" 2>/dev/null
check "herdr-push signatures verify against docs/herdr-push-allowed-signers" \
  ssh-keygen -q -Y verify -f "$S/repo/docs/herdr-push-allowed-signers" -I herdr-push -n herdr-push \
  -s "$S/herdr-push.sig" < "$S/herdr-push"
check "the M0.4 comment is gone" absent "M0.4: replace" "$S/repo/docs/release-signing-fingerprint.txt"
check "upload deletes the key folder" test ! -e "$dir"
check "upload does not print the password" absent "$password" "$S/out"

# --- upload refuses when a secret already exists --------------------------------
sandbox
run generate
dir=$(keydir)
FAKE_SECRETS="RELEASE_KEY_ALIAS" run upload "$dir"; rc=$?
check "upload with an existing secret fails" test "$rc" -ne 0
check "upload with an existing secret sets nothing" absent "secret set" "$S/fake/calls"
check "upload with an existing secret creates no environment" absent "-X PUT" "$S/fake/calls"
check "upload with an existing secret keeps the folder" test -d "$dir"
check "upload with an existing secret leaves the docs alone" grep -qx PLACEHOLDER-replace-in-M0.4 "$S/repo/docs/release-signing-fingerprint.txt"

# --- upload does not add main twice ---------------------------------------------
sandbox
run generate
FAKE_POLICIES="main" run upload "$(keydir)"; rc=$?
check "upload with main already allowed exits 0" test "$rc" -eq 0
check "upload with main already allowed adds no policy" absent "-X POST" "$S/fake/calls"

# --- generate refuses once a fingerprint is recorded ----------------------------
sandbox
sed -i "s/^PLACEHOLDER-replace-in-M0.4$/$(printf 'a%.0s' {1..64})/" "$S/repo/docs/release-signing-fingerprint.txt"
run generate; rc=$?
check "generate with a recorded fingerprint fails" test "$rc" -ne 0
check "generate with a recorded fingerprint makes no keys" test -z "$(keydir)"

# --- generate stops on a GitHub error other than 404 ----------------------------
sandbox
FAKE_API_ERROR=1 run generate; rc=$?
check "generate stops when GitHub refuses" test "$rc" -ne 0
check "generate makes no keys when GitHub refuses" test -z "$(keydir)"

# --- upload needs a complete key folder -----------------------------------------
sandbox
run upload "$S/keys/nothing-here"; rc=$?
check "upload of a missing folder fails" test "$rc" -ne 0
check "upload of a missing folder sets nothing" absent "secret set" "$S/fake/calls"

echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
