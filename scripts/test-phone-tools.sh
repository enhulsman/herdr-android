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

# unlock against a fake adb: the PIN goes in only once the PIN pad shows, so
# no PIN or enter lands on a lock screen button (a night-time Do Not Disturb
# shortcut opened its dialog that way).
mkdir -p "$tmp/bin"
cat > "$tmp/bin/adb" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_DIR/adb.log"
case "$*" in
  *"dumpsys window"*) if [ -f "$FAKE_DIR/unlocked" ]; then echo "isKeyguardShowing=false"; else echo "isKeyguardShowing=true"; fi ;;
  *"cat /sdcard/ui.xml"*) cat "$FAKE_DIR/ui.xml" ;;
  *"input text"*) [ "$FAKE_PAD" = yes ] && touch "$FAKE_DIR/unlocked" ;;
esac
exit 0
EOF
chmod +x "$tmp/bin/adb"
unlock() { # pad shows: yes|no
  rm -f "$tmp/adb.log" "$tmp/unlocked"
  if [ "$1" = yes ]; then
    echo '<hierarchy><node resource-id="com.android.systemui:id/keyguard_pin_view"/></hierarchy>' > "$tmp/ui.xml"
  else
    echo '<hierarchy><node resource-id="com.android.systemui:id/keyguard_long_press"/></hierarchy>' > "$tmp/ui.xml"
  fi
  PATH="$tmp/bin:$PATH" FAKE_DIR="$tmp" FAKE_PAD=$1 PHONE_UNLOCK_PAUSE=0 \
    HERDR_LOCAL_SETUP="$tmp/setup.md" "$here/phone.sh" unlock >/dev/null 2>&1
}
if unlock yes; then r=ok; else r=failed; fi
check "unlock with the PIN pad showing" "ok" "$r"
check "unlock types the PIN on the pad" "1" "$(grep -c 'input text 908172' "$tmp/adb.log")"
if unlock no; then r=ok; else r=failed; fi
check "unlock without a PIN pad fails" "failed" "$r"
check "no PIN typed without a PIN pad" "0" "$(grep -c 'input text' "$tmp/adb.log" || true)"
check "no enter pressed without a PIN pad" "0" "$(grep -c 'KEYCODE_ENTER' "$tmp/adb.log" || true)"

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
