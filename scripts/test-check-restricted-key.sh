#!/usr/bin/env bash
# Tests for scripts/check-restricted-key.sh against fixture authorized_keys
# files in a scratch directory: the line the app generates, lines with a
# restriction missing or re-enabled, a wrong command, a second copy of the key,
# and sshd -T output. With /usr/sbin/sshd and ssh-keygen installed it also runs
# the live mode against a throwaway sshd; without them those tests say so and
# pass, unless HERDR_REQUIRE_SSHD is set (CI sets it).
#
# Usage: scripts/test-check-restricted-key.sh
set -euo pipefail

check=$(cd "$(dirname "$0")" && pwd)/check-restricted-key.sh
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
failures=0
count=0

# The forced command the app puts in the line (crates/herdr-mobile/src/ssh.rs
# REMOTE_COMMAND), with its double quotes escaped as in the line.
command_escaped='sh -c '\''p=$(command -v herdr-core) || p=$HOME/.local/bin/herdr-core; [ -x \"$p\" ] || { echo \"herdr-core is not installed here\" >&2; exit 127; }; exec \"$p\" connect'\'''
blob=AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBExampleExampleExampleExampleExampleExampleExampleExampleExampleExample
other_blob=AAAAC3NzaC1lZDI1NTE5AAAAIExampleOtherKeyExampleOtherKeyExampleOther
good="restrict,command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"

# keys <line>...: writes the lines as an authorized_keys file, mode 600.
keys() {
  file="$scratch/keys$count"
  printf '%s\n' "$@" > "$file"
  chmod 600 "$file"
}

# expect pass|fail [grep pattern the output must contain] -- <script args>
expect() {
  local want=$1 pattern=$2 out status=0
  shift 3
  count=$((count + 1))
  out=$(HOME="${HOME_DIR:-$scratch/home}" "$check" "$@" 2>&1) || status=$?
  if [[ $want == pass && $status -ne 0 ]] || [[ $want == fail && $status -ne 1 ]]; then
    echo "FAIL ${FUNCNAME[1]}: expected $want, exit $status" >&2
    printf '%s\n' "$out" | sed 's/^/    /' >&2
    failures=$((failures + 1))
  elif [[ -n $pattern ]] && ! grep -qE -- "$pattern" <<< "$out"; then
    echo "FAIL ${FUNCNAME[1]}: output lacks /$pattern/" >&2
    printf '%s\n' "$out" | sed 's/^/    /' >&2
    failures=$((failures + 1))
  elif [[ $want == pass ]] && grep -q '^FAIL' <<< "$out"; then
    echo "FAIL ${FUNCNAME[1]}: exit 0 but a FAIL line was printed" >&2
    failures=$((failures + 1))
  fi
}

the_generated_line_passes() {
  keys "$good"
  expect pass '^PASS.*restrict' -- "$file"
}

other_keys_comments_and_blank_lines_are_ignored() {
  keys "# my keys" "" "ssh-ed25519 $other_blob laptop" "$good" \
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAnotherOne desktop"
  expect pass '' -- "$file"
}

option_names_are_case_insensitive() {
  keys "RESTRICT,Command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect pass '' -- "$file"
}

extra_restrictions_are_fine() {
  keys "restrict,from=\"192.0.2.0/24\",no-pty,command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect pass '' -- "$file"
}

missing_restrict_fails() {
  keys "command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*restrict' -- "$file"
}

re_enabled_pty_fails() {
  keys "restrict,pty,command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*pty' -- "$file"
}

re_enabled_pty_in_capitals_fails() {
  keys "restrict,PTY,command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*pty' -- "$file"
}

re_enabled_forwarding_fails() {
  local option
  for option in port-forwarding agent-forwarding X11-forwarding user-rc \
    'permitopen="127.0.0.1:80"' 'permitlisten="8080"' 'environment="A=b"' \
    'tunnel="1"' cert-authority; do
    keys "restrict,$option,command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"
    expect fail "^FAIL.*${option%%=*}" -- "$file"
  done
}

wrong_command_fails() {
  keys "restrict,command=\"bash\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*command' -- "$file"
}

command_with_a_comma_inside_quotes_is_one_option() {
  keys "restrict,command=\"echo a,pty\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*command' -- "$file"
  # ...and the pty inside the quotes is not read as an option.
  if grep -q '^FAIL.*not allowed' <<< "$(HOME="$scratch/home" "$check" "$file" 2>&1)"; then
    echo "FAIL ${FUNCNAME[0]}: read pty from inside the quoted command" >&2
    failures=$((failures + 1))
  fi
}

missing_command_fails() {
  keys "restrict ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*command' -- "$file"
}

two_commands_fail() {
  keys "restrict,command=\"$command_escaped\",command=\"bash\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*command' -- "$file"
}

no_options_at_all_fails() {
  keys "ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*restrict' -- "$file"
}

no_phone_key_line_fails() {
  keys "ssh-ed25519 $other_blob laptop"
  expect fail '^FAIL.*herdr-android' -- "$file"
}

the_same_key_without_restrictions_elsewhere_fails() {
  keys "$good" "ecdsa-sha2-nistp256 $blob old-copy"
  expect fail '^FAIL.*line 2' -- "$file"
}

another_comment_can_be_checked() {
  keys "restrict,command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob my-phone"
  expect pass '' -- --comment my-phone "$file"
}

a_missing_file_fails() {
  expect fail '^FAIL' -- "$scratch/does-not-exist"
}

a_file_others_can_change_fails() {
  keys "$good"
  chmod 666 "$file"
  expect fail '^FAIL.*write' -- "$file"
}

sshd_settings_are_skipped_without_root_or_a_file() {
  [[ $(id -u) -eq 0 ]] && return
  keys "$good"
  expect pass '^SKIP.*sshd -T' -- "$file"
}

good_sshd_settings_pass() {
  mkdir -p "$scratch/home/.ssh"
  keys "$good"
  cp "$file" "$scratch/home/.ssh/authorized_keys"
  printf '%s\n' 'pubkeyauthentication yes' 'permituserenvironment no' \
    'authorizedkeysfile .ssh/authorized_keys .ssh/authorized_keys2' > "$scratch/sshd-t"
  expect pass '^PASS.*environment' -- --sshd-settings "$scratch/sshd-t" \
    "$scratch/home/.ssh/authorized_keys"
  # The default path is ~/.ssh/authorized_keys.
  expect pass '^PASS.*reads' -- --sshd-settings "$scratch/sshd-t"
}

absolute_authorized_keys_file_setting_passes() {
  mkdir -p "$scratch/home/.ssh"
  keys "$good"
  cp "$file" "$scratch/home/.ssh/authorized_keys"
  printf '%s\n' 'pubkeyauthentication yes' 'permituserenvironment no' \
    'authorizedkeysfile %h/.ssh/authorized_keys' > "$scratch/sshd-t"
  expect pass '' -- --sshd-settings "$scratch/sshd-t"
}

user_environment_fails() {
  keys "$good"
  printf '%s\n' 'pubkeyauthentication yes' 'permituserenvironment yes' \
    'authorizedkeysfile .ssh/authorized_keys' > "$scratch/sshd-t"
  expect fail '^FAIL.*environment' -- --sshd-settings "$scratch/sshd-t" "$file"
}

keys_read_from_elsewhere_fail() {
  mkdir -p "$scratch/home/.ssh"
  keys "$good"
  cp "$file" "$scratch/home/.ssh/authorized_keys"
  printf '%s\n' 'pubkeyauthentication yes' 'permituserenvironment no' \
    'authorizedkeysfile /etc/ssh/keys/%u' > "$scratch/sshd-t"
  expect fail '^FAIL.*reads' -- --sshd-settings "$scratch/sshd-t"
}

pubkey_auth_off_fails() {
  keys "$good"
  printf '%s\n' 'pubkeyauthentication no' 'permituserenvironment no' \
    'authorizedkeysfile .ssh/authorized_keys' > "$scratch/sshd-t"
  expect fail '^FAIL.*key' -- --sshd-settings "$scratch/sshd-t" "$file"
}

an_indented_line_is_parsed_like_any_other() {
  keys "	  $good"
  expect pass '^PASS.*restrict' -- "$file"
}

an_indented_unrestricted_copy_fails() {
  keys "$good" "	  command=\"bash\" ecdsa-sha2-nistp256 $blob old-copy"
  expect fail '^FAIL.*line 2' -- "$file"
}

# Other key files: a fresh HOME per test (the caller declares `local
# HOME_DIR`), so a copy left in one cannot leak into the next.
other_home() {
  HOME_DIR="$scratch/home-${FUNCNAME[1]}"
  mkdir -p "$HOME_DIR/.ssh"
  keys "$good"
  cp "$file" "$HOME_DIR/.ssh/authorized_keys"
}

an_unrestricted_copy_in_authorized_keys2_fails() {
  local HOME_DIR
  other_home
  printf '%s\n' "command=\"bash\" ecdsa-sha2-nistp256 $blob old-copy" > "$HOME_DIR/.ssh/authorized_keys2"
  expect fail '^FAIL.*authorized_keys2' -- "$HOME_DIR/.ssh/authorized_keys"
}

a_restricted_copy_in_authorized_keys2_passes() {
  local HOME_DIR
  other_home
  cp "$HOME_DIR/.ssh/authorized_keys" "$HOME_DIR/.ssh/authorized_keys2"
  expect pass '' -- "$HOME_DIR/.ssh/authorized_keys"
}

without_settings_other_key_sources_are_unverified() {
  [[ $(id -u) -eq 0 ]] && return
  keys "$good"
  expect pass '^SKIP.*not verified' -- "$file"
}

an_unrestricted_copy_in_another_configured_file_fails() {
  local HOME_DIR
  other_home
  mkdir -p "$scratch/extra"
  printf '%s\n' "ecdsa-sha2-nistp256 $blob old-copy" > "$scratch/extra/$(id -un)"
  printf '%s\n' 'pubkeyauthentication yes' 'permituserenvironment no' \
    "authorizedkeysfile .ssh/authorized_keys $scratch/extra/%u" \
    'authorizedkeyscommand none' > "$scratch/sshd-t"
  expect fail "^FAIL.*extra/$(id -un)" -- --sshd-settings "$scratch/sshd-t"
  rm -rf "$scratch/extra"
}

a_restricted_copy_in_another_configured_file_passes() {
  local HOME_DIR
  other_home
  mkdir -p "$scratch/extra"
  cp "$HOME_DIR/.ssh/authorized_keys" "$scratch/extra/$(id -un)"
  printf '%s\n' 'pubkeyauthentication yes' 'permituserenvironment no' \
    "authorizedkeysfile .ssh/authorized_keys $scratch/extra/%u" \
    'authorizedkeyscommand none' > "$scratch/sshd-t"
  expect pass '' -- --sshd-settings "$scratch/sshd-t"
  rm -rf "$scratch/extra"
}

an_authorized_keys_command_fails_as_unverified() {
  local HOME_DIR
  other_home
  printf '%s\n' 'pubkeyauthentication yes' 'permituserenvironment no' \
    'authorizedkeysfile .ssh/authorized_keys' \
    'authorizedkeyscommand /usr/local/bin/fetch-keys %u' > "$scratch/sshd-t"
  expect fail '^FAIL.*AuthorizedKeysCommand' -- --sshd-settings "$scratch/sshd-t"
}

an_unknown_flag_is_a_usage_error() {
  local status=0
  count=$((count + 1))
  "$check" --nonsense > /dev/null 2>&1 || status=$?
  if [[ $status -ne 2 ]]; then
    echo "FAIL ${FUNCNAME[0]}: expected exit 2, got $status" >&2
    failures=$((failures + 1))
  fi
}

# Live mode: a throwaway sshd with the line's options and a temporary key.

have_sshd() {
  if [[ -x /usr/sbin/sshd ]] && command -v ssh-keygen > /dev/null; then
    return 0
  fi
  if [[ -n ${HERDR_REQUIRE_SSHD:-} ]]; then
    echo "FAIL: HERDR_REQUIRE_SSHD is set but /usr/sbin/sshd is not installed" >&2
    failures=$((failures + 1))
  else
    echo "skipped ${FUNCNAME[1]}: /usr/sbin/sshd is not installed" >&2
  fi
  return 1
}

live_good_line_passes_every_refusal() {
  have_sshd || return 0
  keys "$good"
  expect pass '^PASS.*live.*pty' -- --live "$file"
  expect pass '^PASS.*live.*other command' -- --live "$file"
  expect pass '^PASS.*live.*local port forwarding' -- --live "$file"
  expect pass '^PASS.*live.*remote port forwarding' -- --live "$file"
}

live_ignores_a_from_limit() {
  have_sshd || return 0
  keys "restrict,from=\"192.0.2.0/24\",command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect pass '^PASS.*live.*other command' -- --live "$file"
}

live_re_enabled_pty_is_caught() {
  have_sshd || return 0
  keys "restrict,pty,command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*live.*pty' -- --live "$file"
}

live_re_enabled_forwarding_is_caught() {
  have_sshd || return 0
  keys "restrict,port-forwarding,command=\"$command_escaped\" ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*live.*local port forwarding' -- --live "$file"
  expect fail '^FAIL.*live.*remote port forwarding' -- --live "$file"
}

live_wrong_command_is_caught() {
  have_sshd || return 0
  keys "restrict ecdsa-sha2-nistp256 $blob herdr-android"
  expect fail '^FAIL.*live.*other command' -- --live "$file"
}

mkdir -p "$scratch/home"
for test in $(declare -F | awk '{print $3}' | grep -vE '^(keys|expect|have_sshd|other_home)$'); do
  "$test"
done

if [[ $failures -gt 0 ]]; then
  echo "$failures of $count checks failed" >&2
  exit 1
fi
echo "all $count checks passed"
