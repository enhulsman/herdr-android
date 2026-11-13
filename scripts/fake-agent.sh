#!/usr/bin/env bash
# A fake agent on the desk for phone checks (docs/phone-testing.md): a
# throwaway herdr-core terminal that reports whatever status you give it,
# through the same terminal signal real agents use (OSC 7501). So "working",
# "needs you" and "done" can be shown on the phone without touching a real
# session, and typing into it from the phone harms nothing.
#
#   scripts/fake-agent.sh start           start terminal "phonecheck"
#   scripts/fake-agent.sh set <state>     working | blocked | idle | error
#                                         (blocked = needs you; working then
#                                         idle = done until opened on the phone)
#   scripts/fake-agent.sh stop            end and remove it
#   scripts/fake-agent.sh --print <state> the bytes it would print (tests)
#
# herdr-push sends no alert for it: it alerts only for terminals in a Herdr app
# pane. Use a real agent's "Finished" alert to check alerts.
set -euo pipefail
name=phonecheck
fifo=${FAKE_AGENT_FIFO:-${XDG_RUNTIME_DIR:-/tmp}/herdr-fake-agent.fifo}

signal() {
  case "$1" in
    working|idle|error) printf '\033]7501;state=%s:app=claude\033\\' "$1" ;;
    blocked) printf '\033]7501;state=blocked:kind=permission:app=claude\033\\' ;;
    *) echo "unknown state: $1 (working, blocked, idle, error)" >&2; return 1 ;;
  esac
}

case "${1:-}" in
  --print) signal "$2" ;;
  --loop) # runs inside the terminal: each state written to the fifo becomes its status
    echo "$name: fake agent, type here freely"
    while :; do
      while read -r s; do signal "$s" || continue; echo "status -> $s"; done < "$fifo"
    done
    ;;
  start)
    [ -p "$fifo" ] || { rm -f "$fifo"; mkfifo "$fifo"; }
    herdr-core run --bg --name "$name" --no-parent --cwd /tmp "$(cd "$(dirname "$0")" && pwd)/fake-agent.sh" --loop
    ;;
  set)
    signal "$2" >/dev/null
    [ -p "$fifo" ] || { echo "not started: scripts/fake-agent.sh start" >&2; exit 1; }
    echo "$2" > "$fifo"
    ;;
  stop)
    herdr-core kill "$name" >/dev/null 2>&1 || true
    sleep 1
    herdr-core rm "$name" >/dev/null 2>&1 || true
    rm -f "$fifo"
    echo stopped
    ;;
  *) sed -n '2,17p' "$0"; exit 2 ;;
esac
