#!/usr/bin/env bash
# Drives the test phone over adb for phone checks (docs/phone-testing.md).
#
#   scripts/phone.sh unlock            wake, swipe and type the PIN in one go
#   scripts/phone.sh lock              screen off
#   scripts/phone.sh ui                the screen's texts and bounds (fails while
#                                      the app animates; use `shot` then)
#   scripts/phone.sh tap <text>        tap the first element with this text or
#                                      content description
#   scripts/phone.sh shot <file.png>   screenshot
#   scripts/phone.sh note              the status notification's title and text
#   scripts/phone.sh alerts            the app's notifications: id, tag, channel
#   scripts/phone.sh doze on|off       force deep Doze (as if unplugged) / undo it
#   scripts/phone.sh install           install the debug APK for the owner profile
#                                      only, and report copies in other users
#   scripts/phone.sh launch            open the debug app (monkey, never am start)
#   scripts/phone.sh <shell args...>   anything else runs as `adb shell`
#
# Which phone: PHONE_SERIAL, else the "Test phone adb serial:" line in
# local/setup.md. It never falls back to "the only device": that could be a
# daily phone. The PIN: PHONE_PIN, else the "Test phone PIN:" line there.
# HERDR_LOCAL_SETUP points at another setup file (the tests use it).
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
setup=${HERDR_LOCAL_SETUP:-$repo/local/setup.md}
app=dev.herdr.android.debug

from_setup() { # the value after "<label>:" in the setup file
  [ -f "$setup" ] || return 0
  sed -nE "s/^[-* ]*$1: *([^ ]+).*/\1/p" "$setup" | head -1
}
serial() {
  local s=${PHONE_SERIAL:-$(from_setup 'Test phone adb serial')}
  [ -n "$s" ] || { echo "no phone: set PHONE_SERIAL or add 'Test phone adb serial: <serial>' to local/setup.md" >&2; exit 1; }
  echo "$s"
}
pin() {
  local p=${PHONE_PIN:-$(from_setup 'Test phone PIN')}
  [ -n "$p" ] || { echo "no PIN: set PHONE_PIN or add 'Test phone PIN: <digits>' to local/setup.md" >&2; exit 1; }
  echo "$p"
}

case "${1:-}" in
  --print-pin) pin; exit ;;
  --print-serial) serial; exit ;;
  ""|-h|--help) sed -n '2,24p' "$0"; exit ;;
esac

S=$(serial)
a() { adb -s "$S" "$@"; }

dump() {
  a shell "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1 || true
  a shell cat /sdcard/ui.xml 2>/dev/null | grep -q '<hierarchy' || {
    echo "no screen dump (the app may be animating): use 'shot'" >&2; exit 1; }
  a shell cat /sdcard/ui.xml
}

status_note() {
  a shell dumpsys notification --noredact |
    grep -A40 "NotificationRecord(.*pkg=$app user=UserHandle{0} id=2 " |
    grep -E 'android\.(title|text)=' | head -2 | sed 's/.*String (\(.*\))$/\1/' | paste -sd'|' -
}

case "$1" in
  unlock)
    if a shell dumpsys window | grep -q 'isKeyguardShowing=true'; then
      # The lock screen falls back to always-on display within seconds: wake
      # and swipe in one shell. The PIN goes in only once the PIN pad shows:
      # typed blind, enter presses whatever lock screen button has focus (at
      # night the Do Not Disturb shortcut). The last try asks for the pad
      # without a swipe, for when swipes are ignored.
      p=${PHONE_UNLOCK_PAUSE:-1}
      for open in "input swipe 720 1900 720 500 200" "input swipe 720 1900 720 500 200" "wm dismiss-keyguard"; do
        a shell "input keyevent KEYCODE_WAKEUP; sleep 0.6; $open; sleep 0.9"
        a shell "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1 || true
        if a shell cat /sdcard/ui.xml 2>/dev/null | grep -qE 'keyguard_pin_view|id/pinEntry'; then
          a shell "input text $(pin); input keyevent KEYCODE_ENTER"
          sleep "$(awk "BEGIN{print 1.5*$p}")"
          break
        fi
        sleep "$p"
      done
      if a shell dumpsys window | grep -q 'isKeyguardShowing=true'; then
        f=$(mktemp "${TMPDIR:-/tmp}/phone-unlock-XXXXXX.png")
        a exec-out screencap -p > "$f" 2>/dev/null || true
        echo "still locked: no PIN pad after three tries; screen in $f" >&2; exit 1
      fi
    else
      a shell input keyevent KEYCODE_WAKEUP
    fi
    a shell dumpsys window | grep -q 'isKeyguardShowing=false' && echo unlocked || { echo "still locked" >&2; exit 1; }
    ;;
  lock) a shell input keyevent KEYCODE_SLEEP ;;
  ui)
    dump | python3 -c '
import sys, xml.etree.ElementTree as E
for n in E.fromstring(sys.stdin.read()).iter("node"):
    t, d = n.get("text") or "", n.get("content-desc") or ""
    if t or d:
        print(n.get("bounds"), repr(t), repr(d), "click" if n.get("clickable") == "true" else "", sep="\t")'
    ;;
  tap)
    xy=$(dump | python3 -c '
import sys, re, xml.etree.ElementTree as E
want = sys.argv[1]
for n in E.fromstring(sys.stdin.read()).iter("node"):
    if want in ((n.get("text") or ""), (n.get("content-desc") or "")):
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
        print((a + c) // 2, (b + d) // 2)
        break' "$2")
    [ -n "$xy" ] || { echo "not on screen: $2" >&2; exit 1; }
    # shellcheck disable=SC2086
    a shell input tap $xy
    echo "tapped $2 at $xy"
    ;;
  shot) a exec-out screencap -p > "$2"; echo "$2" ;;
  note) status_note ;;
  alerts)
    a shell dumpsys notification --noredact | grep -E "NotificationRecord\(.*pkg=$app user" |
      sed -E 's/.*(id=[0-9]+) (tag=[^ ]*).*channel=([a-z-]+).*/\1 \2 \3/'
    ;;
  doze)
    case "${2:-}" in
      on) a shell dumpsys battery unplug; a shell dumpsys deviceidle force-idle ;;
      off) a shell dumpsys deviceidle unforce; a shell dumpsys battery reset; echo "Doze released, battery real again" ;;
      *) echo "doze on|off" >&2; exit 2 ;;
    esac
    ;;
  install)
    apk=$repo/android/app/build/outputs/apk/debug/app-debug.apk
    [ -f "$apk" ] || { echo "no $apk: run ./gradlew :app:assembleDebug in android/" >&2; exit 1; }
    # --user 0: without it adb installs into every user, Private Space included.
    a install -r --user 0 "$apk"
    for u in $(a shell pm list users | grep -oE 'UserInfo\{[0-9]+' | grep -oE '[0-9]+'); do
      [ "$u" = 0 ] && continue
      if a shell pm list packages --user "$u" 2>/dev/null | grep -qx "package:$app"; then
        echo "also in user $u: remove with  scripts/phone.sh pm uninstall --user $u $app"
      fi
    done
    ;;
  launch) a shell monkey -p "$app" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo launched ;;
  *) a shell "$@" ;;
esac
