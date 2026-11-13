#!/usr/bin/env bash
# Offline tests for scripts/phone.sh and scripts/fake-agent.sh: the parts that
# need no phone and no herdr-core (where the PIN and the device come from, and
# the exact status signal the fake agent prints). Run: scripts/test-phone-tools.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail=0
check() { # name, expected, actual
  if [ "$2" == "$3" ]; then echo "PASS $1"; else echo "FAIL $1: expected [$2], got [$3]"; fail=1; fi
}

cat > "$tmp/setup.md" <<'EOF'
# Local setup
- Some other line: 1234
- Test phone PIN: 908172
- Test phone adb serial: adb-TESTSERIAL._adb-tls-connect._tcp
EOF

check "PIN from local setup" "908172" \
  "$(HERDR_LOCAL_SETUP="$tmp/setup.md" "$here/phone.sh" --print-pin)"
check "PIN from the environment wins" "1111" \
  "$(PHONE_PIN=1111 HERDR_LOCAL_SETUP="$tmp/setup.md" "$here/phone.sh" --print-pin)"
check "serial from local setup" "adb-TESTSERIAL._adb-tls-connect._tcp" \
  "$(HERDR_LOCAL_SETUP="$tmp/setup.md" "$here/phone.sh" --print-serial)"
check "serial from the environment wins" "emulator-5554" \
  "$(PHONE_SERIAL=emulator-5554 HERDR_LOCAL_SETUP="$tmp/setup.md" "$here/phone.sh" --print-serial)"

printf '# nothing here\n' > "$tmp/empty.md"
if out=$(HERDR_LOCAL_SETUP="$tmp/empty.md" "$here/phone.sh" --print-pin 2>&1); then
  check "no PIN is an error" "error" "ok: $out"
else
  check "no PIN is an error" "1" "$(grep -c 'Test phone PIN' <<<"$out")"
fi

check "working signal" "$(printf '\033]7501;state=working:app=claude\033\\')" \
  "$("$here/fake-agent.sh" --print working)"
check "needs-you signal is a permission block" "$(printf '\033]7501;state=blocked:kind=permission:app=claude\033\\')" \
  "$("$here/fake-agent.sh" --print blocked)"
check "idle signal" "$(printf '\033]7501;state=idle:app=claude\033\\')" \
  "$("$here/fake-agent.sh" --print idle)"
if "$here/fake-agent.sh" --print sleeping >/dev/null 2>&1; then
  check "unknown state is refused" "refused" "accepted"
else
  check "unknown state is refused" "refused" "refused"
fi

exit $fail
