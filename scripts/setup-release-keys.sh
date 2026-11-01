#!/usr/bin/env bash
# One-time setup of the release signing keys (task M0.4), in two steps you run
# yourself from the repository root:
#
#   scripts/setup-release-keys.sh generate
#       Makes the APK signing keystore (EC P-256) and the herdr-push signing key
#       (ed25519) in a new private folder in your home directory and prints its
#       path. Uploads nothing. Copy bitwarden.txt from that folder into your
#       password manager before the next step.
#
#   scripts/setup-release-keys.sh upload <folder>
#       Creates the `release` environment (deployment branch: main only), stores
#       the five secrets in it, writes docs/release-signing-fingerprint.txt and
#       docs/herdr-push-allowed-signers, and shreds the folder.
#
# Both steps refuse to run once a fingerprint is recorded or any of the five
# secrets exists, so a signing key is never replaced by accident. Secrets never
# appear on a command line or in the output.
#
# GitHub access: GH_TOKEN, else `gh auth token --user <repository owner>`.
# KEYS_PARENT (default: $HOME) is where the folder is made.
# Tests: scripts/test-setup-release-keys.sh.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
fingerprint_file="$repo/docs/release-signing-fingerprint.txt"
signers_file="$repo/docs/herdr-push-allowed-signers"
placeholder=PLACEHOLDER-replace-in-M0.4
key_alias=herdr-release
secrets=(RELEASE_KEYSTORE_B64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD HERDR_PUSH_SIGNING_KEY)
files=(release.p12 release.p12.b64 password alias fingerprint herdr-push herdr-push.pub bitwarden.txt)

die() { echo "setup-release-keys: $*" >&2; exit 1; }

url=$(git -C "$repo" remote get-url origin)
slug=${url%.git}
slug=${slug#https://github.com/}
slug=${slug#*:}
[[ $slug =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "cannot read owner/repo from origin ($url)"
GH_TOKEN=${GH_TOKEN:-$(gh auth token --user "${slug%%/*}")}
export GH_TOKEN

# Stop if the key already exists: a recorded fingerprint or any of the secrets.
preflight() {
  grep -qx "$placeholder" "$fingerprint_file" \
    || die "$fingerprint_file already holds a fingerprint, so the signing key exists"
  local existing name
  if ! existing=$(gh api "repos/$slug/environments/release/secrets" --jq '.secrets[].name' 2>&1); then
    [[ $existing == *"HTTP 404"* ]] || die "GitHub: $existing"
    existing=   # no release environment yet
  fi
  for name in "${secrets[@]}"; do
    if grep -qx "$name" <<<"$existing"; then
      die "secret $name already exists in the release environment; delete it on GitHub first if you mean to replace the key"
    fi
  done
}

# Prints the SHA-256 of the keystore's certificate, as apksigner reports it.
cert_sha256() {
  KS_PASS=$(<"$1/password") keytool -exportcert -keystore "$1/release.p12" \
    -alias "$(<"$1/alias")" -storepass:env KS_PASS | sha256sum | cut -c1-64
}

generate() {
  preflight
  umask 077
  local parent=${KEYS_PARENT:-$HOME} dir
  mkdir -p "$parent"
  dir=$(mktemp -d "$parent/herdr-release-keys.XXXXXX")

  openssl rand -base64 33 | tr -d '\n' > "$dir/password"
  printf '%s' "$key_alias" > "$dir/alias"
  KS_PASS=$(<"$dir/password") keytool -genkeypair -keystore "$dir/release.p12" -storetype PKCS12 \
    -alias "$key_alias" -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
    -validity 10000 -dname "CN=herdr-android" -storepass:env KS_PASS -keypass:env KS_PASS 2>/dev/null
  base64 -w0 "$dir/release.p12" > "$dir/release.p12.b64"
  cert_sha256 "$dir" > "$dir/fingerprint"
  ssh-keygen -q -t ed25519 -N '' -C herdr-push -f "$dir/herdr-push"

  cat > "$dir/bitwarden.txt" <<EOF
Two items for your password manager. Copy each value exactly.

1. herdr-android release keystore
   Key alias:           $key_alias
   Keystore password:   $(<"$dir/password")
   (The key password is the same.)
   Certificate SHA-256: $(<"$dir/fingerprint")
   Keystore, base64 (to restore: base64 -d > release.p12):
$(<"$dir/release.p12.b64")

2. herdr-push signing key (OpenSSH private key, no passphrase)
$(<"$dir/herdr-push")
   Public key: $(<"$dir/herdr-push.pub")
EOF

  local windows
  windows=$(wslpath -w "$dir" 2>/dev/null || true)
  cat <<EOF
Keys made in: $dir${windows:+
  (from Windows: $windows)}
Nothing is uploaded yet.

1. Open bitwarden.txt in that folder and save both items in Bitwarden.
2. Then run: scripts/setup-release-keys.sh upload $dir
   It uploads the keys to GitHub and shreds the folder.
EOF
}

upload() {
  local dir=${1:-} file name
  [[ -n $dir ]] || die "usage: setup-release-keys.sh upload <folder>"
  for file in "${files[@]}"; do
    [[ -s $dir/$file ]] || die "$dir/$file is missing; run generate first"
  done
  preflight
  [[ $(cert_sha256 "$dir") == "$(<"$dir/fingerprint")" ]] \
    || die "the keystore in $dir does not match its fingerprint file"

  gh api -X PUT "repos/$slug/environments/release" --input - > /dev/null \
    <<<'{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}'
  if ! gh api "repos/$slug/environments/release/deployment-branch-policies" \
      --jq '.branch_policies[] | select(.type == "branch") | .name' | grep -qx main; then
    gh api -X POST "repos/$slug/environments/release/deployment-branch-policies" \
      -f name=main -f type=branch > /dev/null
  fi
  echo "Environment release: deployment branch main only."

  set_secret() { gh secret set "$1" --env release --repo "$slug" < "$2" > /dev/null; echo "Secret $1 set."; }
  set_secret RELEASE_KEYSTORE_B64 "$dir/release.p12.b64"
  set_secret RELEASE_KEYSTORE_PASSWORD "$dir/password"
  set_secret RELEASE_KEY_ALIAS "$dir/alias"
  set_secret RELEASE_KEY_PASSWORD "$dir/password"
  set_secret HERDR_PUSH_SIGNING_KEY "$dir/herdr-push"

  sed -i -e "/^# M0.4: replace/d" -e "s/^$placeholder\$/$(<"$dir/fingerprint")/" "$fingerprint_file"
  printf 'herdr-push %s\n' "$(cut -d' ' -f1,2 "$dir/herdr-push.pub")" > "$signers_file"
  echo "Wrote ${fingerprint_file#"$repo"/} and ${signers_file#"$repo"/}."

  for name in "${files[@]}"; do shred -u "$dir/$name"; done
  rmdir "$dir"
  echo "Shredded $dir."
}

case ${1:-} in
  generate) generate ;;
  upload) upload "${2:-}" ;;
  *) die "usage: setup-release-keys.sh generate | upload <folder>" ;;
esac
