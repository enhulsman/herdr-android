#!/usr/bin/env bash
# Checks, on the machine, that the phone's key can only start herdr-core.
#
# The app gives you one line to add to ~/.ssh/authorized_keys:
#   restrict,command="sh -c '...exec herdr-core connect'" ecdsa-sha2-nistp256 AAAA... herdr-android
# This script reads that file (it never changes it) and checks every line that
# carries the phone's key:
#   - `restrict` is set: no terminal (pty), no port, agent or X11 forwarding,
#     no ~/.ssh/rc;
#   - nothing turns one of those back on (`pty`, `port-forwarding`,
#     `permitopen=`, ...), and no other option loosens the line;
#   - `command=` is set once and is exactly the app's herdr-core command, so any
#     command a connection asks for runs herdr-core instead;
#   - no other line carries the same key without these limits, in this file
#     or in the other files sshd reads keys from (its AuthorizedKeysFile list
#     with --sshd-settings, else ~/.ssh/authorized_keys and authorized_keys2);
#     systemd's `userdbctl ssh-authorized-keys %u` is asked for its keys,
#     any other AuthorizedKeysCommand cannot be checked and counts as a FAIL;
#   - only you can change the file.
# It does not need the phone's private key.
#
# sshd's effective settings (keys are read from this file, user environment
# off) need root to read. Run as root it reads them itself; otherwise pass
# them in a file:
#   sudo sshd -T -C user=$USER,host=localhost,addr=127.0.0.1 > /tmp/sshd-settings
#   scripts/check-restricted-key.sh --sshd-settings /tmp/sshd-settings
# Without either it says SKIP for that part.
#
# --live also proves the refusals against a real sshd, without touching your
# own: it makes a temporary key pair, puts it in a temporary authorized_keys
# with the SAME options as your line, starts a throwaway sshd on 127.0.0.1 on a
# free high port (its own host key and config, a scratch HOME with a stand-in
# herdr-core), and asks it for a terminal, another command, a local and a
# remote port forward. Everything is removed afterwards. Needs /usr/sbin/sshd,
# ssh and ssh-keygen (the OpenSSH server you already connect to).
#
# Usage: scripts/check-restricted-key.sh [--live] [--comment NAME]
#                                         [--sshd-settings FILE] [AUTHORIZED_KEYS]
#   AUTHORIZED_KEYS  default ~/.ssh/authorized_keys
#   --comment NAME   the key's comment, default herdr-android
# Prints PASS, FAIL or SKIP per check. Exit 0 when nothing failed, 1 when a
# check failed, 2 on a usage error.
set -euo pipefail

usage() {
  sed -n '/^# Usage:/,/^# check failed/p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

live=0
comment=herdr-android
settings=
file=
while [[ $# -gt 0 ]]; do
  case $1 in
    --live) live=1 ;;
    --comment) [[ $# -ge 2 ]] || usage; comment=$2; shift ;;
    --sshd-settings) [[ $# -ge 2 ]] || usage; settings=$2; shift ;;
    -h|--help) usage ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) [[ -z $file ]] || usage; file=$1 ;;
  esac
  shift
done
file=${file:-$HOME/.ssh/authorized_keys}

# The forced command, as crates/herdr-mobile/src/ssh.rs REMOTE_COMMAND has it
# (the line escapes its double quotes as \"; parsing undoes that).
expected_command=$(cat <<'EOF'
sh -c 'p=$(command -v herdr-core) || p=$HOME/.local/bin/herdr-core; [ -x "$p" ] || { echo "herdr-core is not installed here" >&2; exit 127; }; exec "$p" connect'
EOF
)

failed=0
pass() { printf 'PASS  %s\n' "$*"; }
fail() { printf 'FAIL  %s\n' "$*"; failed=1; }
skip() { printf 'SKIP  %s\n' "$*"; }

# split_line <line>: sets `options` (raw, may be empty), `keytype`, `blob` and
# `key_comment`. Options end at the first space or tab outside double quotes.
split_line() {
  local line=$1 i c quoted=0 rest
  # sshd skips leading blanks; so must we, or options read as the key type.
  line=${line#"${line%%[![:space:]]*}"}
  options=
  if [[ $line =~ ^(ssh-|ecdsa-|sk-) ]]; then
    rest=$line
  else
    for ((i = 0; i < ${#line}; i++)); do
      c=${line:i:1}
      if [[ $c == '\' && $quoted -eq 1 ]]; then
        i=$((i + 1))
        continue
      fi
      [[ $c == '"' ]] && quoted=$((1 - quoted))
      [[ $quoted -eq 0 && ( $c == ' ' || $c == $'\t' ) ]] && break
    done
    options=${line:0:i}
    rest=${line:i}
  fi
  read -r keytype blob key_comment <<< "$rest" || true
}

# split_options <options>: fills the arrays `names` (lower case) and `values`
# (quotes removed, \" unescaped). Commas inside double quotes do not split.
split_options() {
  local opts=$1 i c quoted=0 part=
  names=()
  raw_names=()
  raw_parts=()
  values=()
  [[ -z $opts ]] && return 0
  for ((i = 0; i <= ${#opts}; i++)); do
    c=${opts:i:1}
    if [[ $i -lt ${#opts} && $c == '\' && $quoted -eq 1 ]]; then
      part+=${opts:i:2}
      i=$((i + 1))
      continue
    fi
    if [[ $i -eq ${#opts} || ( $c == ',' && $quoted -eq 0 ) ]]; then
      local name=${part%%=*} value=
      [[ $part == *=* ]] && value=${part#*=}
      value=${value#\"}
      value=${value%\"}
      names+=("${name,,}")
      raw_names+=("$name")
      raw_parts+=("$part")
      values+=("${value//\\\"/\"}")
      part=
      continue
    fi
    [[ $c == '"' ]] && quoted=$((1 - quoted))
    part+=$c
  done
}

# check_options <label> <options>: the static checks for one line.
check_options() {
  local label=$1 i name has_restrict=0 commands=0 command= loosened=()
  split_options "$2"
  for i in "${!names[@]}"; do
    name=${names[i]}
    case $name in
      restrict) has_restrict=1 ;;
      command) commands=$((commands + 1)); command=${values[i]} ;;
      # Further limits only.
      from|expiry-time|no-pty|no-port-forwarding|no-agent-forwarding|\
      no-x11-forwarding|no-user-rc|verify-required) ;;
      *) loosened+=("${raw_names[i]}") ;;
    esac
  done
  if [[ $has_restrict -eq 1 ]]; then
    pass "$label: restrict is set (no terminal, no port, agent or X11 forwarding)"
  else
    fail "$label: restrict is missing, so a terminal and port forwarding are allowed"
  fi
  if [[ ${#loosened[@]} -eq 0 ]]; then
    pass "$label: no option turns a pty, forwarding or anything else back on"
  else
    fail "$label: option(s) not allowed on this line: ${loosened[*]} (re-enable pty, forwarding, environment or similar)"
  fi
  if [[ $commands -eq 0 ]]; then
    fail "$label: no command= is set, so any command the connection asks for runs"
  elif [[ $commands -gt 1 ]]; then
    fail "$label: command= is set $commands times; set it once"
  elif [[ $command == "$expected_command" ]]; then
    pass "$label: command= runs only herdr-core connect, whatever is asked"
  else
    fail "$label: command= is not the app's herdr-core command; it is: $command"
  fi
}

# --- The authorized_keys files ----------------------------------------------

# read_lines <path>: fills `lines` with the file's lines (CR removed).
read_lines() {
  local line
  lines=()
  while IFS= read -r line || [[ -n $line ]]; do
    lines+=("${line%$'\r'}")
  done < "$1"
}

# writable_check <path>: fails when others can write to the file.
writable_check() {
  local mode
  mode=$(stat -c %a "$1")
  if (( 8#$mode & 8#022 )); then
    fail "$1: others can write to it (mode $mode); run chmod 600 on it"
  else
    pass "$1: only you can write to it"
  fi
}

# check_copies <label prefix> <path>: the static checks for every line in the
# file that carries one of the phone's keys (`blobs`), whatever its comment.
# Prints how many it found in `copies`.
check_copies() {
  local prefix=$1 path=$2 n=0 line
  copies=0
  read_lines "$path"
  for line in "${lines[@]}"; do
    n=$((n + 1))
    [[ $line =~ ^[[:space:]]*(#|$) ]] && continue
    split_line "$line"
    [[ $blobs == *" $blob "* ]] || continue
    copies=$((copies + 1))
    if [[ $key_comment != "$comment" ]]; then
      echo "      ${prefix}line $n carries the phone key under another comment (${key_comment:-none})"
    fi
    check_options "${prefix}line $n" "$options"
    phone_options+=("$options")
  done
}

check_file() {
  if [[ ! -f $file ]]; then
    fail "$file does not exist"
    return
  fi
  if [[ ! -r $file ]]; then
    fail "$file cannot be read"
    return
  fi
  writable_check "$file"

  # The phone's key: every blob on a line with the comment.
  local line
  read_lines "$file"
  for line in "${lines[@]}"; do
    [[ $line =~ ^[[:space:]]*(#|$) ]] && continue
    split_line "$line"
    [[ $key_comment == "$comment" ]] && blobs+="$blob "
  done
  if [[ $blobs == " " ]]; then
    fail "no line with the comment $comment in $file: add the line from the app's Phone key screen"
    return
  fi
  check_copies "" "$file"
}

# key_path <AuthorizedKeysFile token>: the path sshd reads for this user.
key_path() {
  local path=${1//%h/$HOME}
  path=${path//%u/$(id -un)}
  path=${path//%%/%}
  [[ $path == /* ]] || path=$HOME/$path
  printf '%s\n' "$path"
}

# Any other file sshd may read keys from must not hold an unrestricted copy.
# With sshd's settings these are its AuthorizedKeysFile entries; without,
# OpenSSH's defaults.
check_other_files() {
  [[ $blobs == " " ]] && return 0
  local abs token path
  abs=$(absolute "$file")
  local tokens=(.ssh/authorized_keys .ssh/authorized_keys2)
  if [[ $have_settings -eq 1 ]]; then
    read -r -a tokens <<< "$keysfile"
  fi
  for token in "${tokens[@]}"; do
    path=$(key_path "$token")
    [[ $path == "$abs" ]] && continue
    [[ -e $path ]] || continue
    if [[ ! -r $path ]]; then
      fail "$path cannot be read, so a copy of the phone key in it is not verified"
      continue
    fi
    check_copies "$path " "$path"
    if [[ $copies -eq 0 ]]; then
      pass "$path: no copy of the phone key"
    else
      writable_check "$path"
    fi
  done
  if [[ $have_settings -eq 0 ]]; then
    skip "only ~/.ssh/authorized_keys and ~/.ssh/authorized_keys2 were searched; other key sources sshd may be set to use (AuthorizedKeysFile, AuthorizedKeysCommand) are not verified without sshd's settings"
  fi
}

absolute() {
  printf '%s/%s\n' "$(cd "$(dirname "$1")" 2> /dev/null && pwd)" "$(basename "$1")"
}

# --- sshd's effective settings ----------------------------------------------

have_settings=0
keysfile=
settings_text=

load_settings() {
  if [[ -n $settings ]]; then
    if [[ ! -r $settings ]]; then
      fail "sshd settings file $settings cannot be read"
      return
    fi
    settings_text=$(cat "$settings")
  elif [[ $(id -u) -eq 0 ]] && command -v sshd > /dev/null; then
    settings_text=$(sshd -T -C "user=$(id -un),host=localhost,addr=127.0.0.1")
  else
    return 0
  fi
  have_settings=1
  keysfile=$(awk 'tolower($1) == "authorizedkeysfile" {$1 = ""; print}' <<< "$settings_text")
}

# systemd's sshd drop-in (20-systemd-userdb.conf) sets this command: it
# returns the keys in the user record, for most users none.
is_userdb() {
  local words
  read -r -a words <<< "$1"
  [[ ${#words[@]} -eq 3 && ${words[0]##*/} == userdbctl &&
    ${words[1]} == ssh-authorized-keys && ${words[2]} == %u ]]
}

# Asks userdbctl for this user's keys (the user may read their own record's
# keys) and checks them as a file: no copy, or only restricted ones.
check_userdb() {
  local words out
  read -r -a words <<< "$1"
  out=$(mktemp)
  if ! "${words[0]}" ssh-authorized-keys "$(id -un)" > "$out" 2> /dev/null; then
    rm -f "$out"
    fail "sshd also takes keys from AuthorizedKeysCommand ($1), and it did not answer: keys it returns are not verified"
    return
  fi
  if [[ $blobs != " " ]]; then
    check_copies "userdbctl " "$out"
  else
    copies=0
  fi
  rm -f "$out"
  if [[ $copies -eq 0 ]]; then
    pass "sshd also takes keys from userdbctl (systemd user records): no copy of the phone key"
  else
    pass "sshd also takes keys from userdbctl (systemd user records): its copies of the phone key are checked above"
  fi
}

check_settings() {
  if [[ $have_settings -eq 0 ]]; then
    [[ -n $settings ]] && return
    skip "sshd's effective settings need root (sshd -T); to include them run:"
    echo "      sudo sshd -T -C user=\$USER,host=localhost,addr=127.0.0.1 > /tmp/sshd-settings"
    echo "      $0 --sshd-settings /tmp/sshd-settings"
    return
  fi
  local pubkey userenv keyscommand
  pubkey=$(awk 'tolower($1) == "pubkeyauthentication" {print tolower($2)}' <<< "$settings_text")
  userenv=$(awk 'tolower($1) == "permituserenvironment" {print tolower($2)}' <<< "$settings_text")
  keyscommand=$(awk 'tolower($1) == "authorizedkeyscommand" {$1 = ""; sub(/^ /, ""); print}' <<< "$settings_text")

  if [[ $pubkey == yes ]]; then
    pass "sshd accepts key sign-in"
  else
    fail "sshd does not accept key sign-in (pubkeyauthentication ${pubkey:-unset})"
  fi
  if [[ $userenv == no ]]; then
    pass "sshd ignores ~/.ssh/environment (permituserenvironment no)"
  else
    fail "sshd lets ~/.ssh/environment change the forced command's environment (permituserenvironment ${userenv:-unset})"
  fi
  if [[ -z $keyscommand || ${keyscommand,,} == none ]]; then
    pass "sshd takes keys from files only (no AuthorizedKeysCommand)"
  elif is_userdb "$keyscommand"; then
    check_userdb "$keyscommand"
  else
    fail "sshd also takes keys from AuthorizedKeysCommand ($keyscommand): keys it returns are not verified"
  fi

  local abs token found=0
  abs=$(absolute "$file")
  for token in $keysfile; do
    [[ $(key_path "$token") == "$abs" ]] && found=1
  done
  if [[ $found -eq 1 ]]; then
    pass "sshd reads keys from $abs"
  else
    fail "sshd reads keys from:${keysfile:- (unset)}, not from $abs: the line you checked is not the one in use"
  fi
}

# --- Live: a throwaway sshd -------------------------------------------------

live_check() {
  local options=$1 sshd_bin scratch port try ok=0 out status
  sshd_bin=/usr/sbin/sshd
  if [[ ! -x $sshd_bin ]] || ! command -v ssh > /dev/null || ! command -v ssh-keygen > /dev/null; then
    fail "live: needs $sshd_bin, ssh and ssh-keygen"
    return
  fi
  scratch=$(mktemp -d "${TMPDIR:-/tmp}/herdr-check.XXXXXX")
  # shellcheck disable=SC2064 # expand now: the paths are local
  trap "[[ -n \$live_pid ]] && kill \$live_pid 2> /dev/null; rm -rf '$scratch'" EXIT
  live_pid=

  mkdir -p "$scratch/home/bin"
  ssh-keygen -q -t ed25519 -N '' -f "$scratch/host_key"
  ssh-keygen -q -t ecdsa -b 256 -N '' -C herdr-check -f "$scratch/client_key"
  # Stand-in herdr-core: says what it was asked and whether it got a terminal.
  cat > "$scratch/home/bin/herdr-core" <<'EOF'
#!/bin/sh
t=no
[ -t 0 ] && t=yes
echo "stand-in herdr-core: $* (terminal: $t)"
EOF
  chmod 755 "$scratch/home/bin/herdr-core"
  # from= limits where a connection may come from; this one comes from
  # 127.0.0.1, so leave it out. Every other option stays as it is.
  local i kept=()
  split_options "$options"
  for i in "${!names[@]}"; do
    [[ ${names[i]} == from ]] || kept+=("${raw_parts[i]}")
  done
  options=$(IFS=,; printf '%s' "${kept[*]}")
  if [[ -n $options ]]; then
    printf '%s %s\n' "$options" "$(cat "$scratch/client_key.pub")" > "$scratch/authorized_keys"
  else
    cat "$scratch/client_key.pub" > "$scratch/authorized_keys"
  fi

  for ((try = 0; try < 5; try++)); do
    port=$((20000 + RANDOM % 40000))
    cat > "$scratch/sshd_config" <<EOF
Port $port
ListenAddress 127.0.0.1
HostKey $scratch/host_key
AuthorizedKeysFile $scratch/authorized_keys
PidFile $scratch/sshd.pid
UsePAM no
StrictModes no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
LogLevel VERBOSE
SetEnv HOME=$scratch/home PATH=$scratch/home/bin:/usr/bin:/bin
EOF
    : > "$scratch/sshd.log"
    "$sshd_bin" -D -e -f "$scratch/sshd_config" < /dev/null > /dev/null 2> "$scratch/sshd.log" &
    live_pid=$!
    # Bounded wait for "Server listening", or sshd giving up on the port.
    local i
    for ((i = 0; i < 50; i++)); do
      if grep -q "Server listening on 127.0.0.1 port $port" "$scratch/sshd.log"; then
        ok=1
        break
      fi
      kill -0 "$live_pid" 2> /dev/null || break
      sleep 0.1
    done
    [[ $ok -eq 1 ]] && break
    kill "$live_pid" 2> /dev/null || true
    wait "$live_pid" 2> /dev/null || true
    live_pid=
  done
  if [[ $ok -ne 1 ]]; then
    fail "live: the throwaway sshd did not start: $(tail -n 3 "$scratch/sshd.log" | tr '\n' ' ')"
    return
  fi
  printf '[127.0.0.1]:%s %s\n' "$port" "$(cut -d' ' -f1,2 "$scratch/host_key.pub")" > "$scratch/known_hosts"

  local ssh_args=(-F /dev/null -p "$port" -i "$scratch/client_key"
    -o IdentitiesOnly=yes -o IdentityAgent=none -o BatchMode=yes
    -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$scratch/known_hosts"
    -o ConnectTimeout=5 -o LogLevel=ERROR)
  local target
  target="$(id -un)@127.0.0.1"
  local core_ran="stand-in herdr-core: connect (terminal: no)"

  # A terminal.
  status=0
  out=$(timeout 15 ssh -tt "${ssh_args[@]}" "$target" < /dev/null 2> "$scratch/err" | tr -d '\r') || status=$?
  # Newer ssh clients give up when a forced terminal is refused; older ones
  # carry on without it, and herdr-core runs without a terminal.
  if [[ $out == *"terminal: yes"* ]]; then
    fail "live: a terminal (pty) was granted"
  elif [[ $out == "$core_ran" ]] || grep -q 'PTY allocation request failed' "$scratch/err"; then
    pass "live: a terminal (pty, ssh -tt) is refused"
  else
    fail "live: pty check, unexpected result (exit $status): ${out:-$(head -n 3 "$scratch/err")}"
  fi

  # Another command.
  status=0
  out=$(timeout 15 ssh "${ssh_args[@]}" "$target" 'echo OTHER-COMMAND-RAN' < /dev/null 2> "$scratch/err") || status=$?
  if [[ $out == "$core_ran" ]]; then
    pass "live: any other command is replaced by herdr-core connect"
  elif [[ $out == *OTHER-COMMAND-RAN* ]]; then
    fail "live: other command ran as asked"
  else
    fail "live: other command check, unexpected result (exit $status): ${out:-$(head -n 3 "$scratch/err")}"
  fi

  # Local forwarding: ssh -W opens the same direct-tcpip channel as ssh -L.
  # The target is this sshd itself, whose banner would come back if allowed.
  status=0
  out=$(timeout 15 ssh -W "127.0.0.1:$port" "${ssh_args[@]}" "$target" < /dev/null 2> "$scratch/err") || status=$?
  if [[ $out == SSH-* ]]; then
    fail "live: local port forwarding (ssh -L / -W) reached 127.0.0.1:$port"
  elif [[ $status -ne 0 ]] && grep -qi 'prohibited\|forwarding failed' "$scratch/err"; then
    pass "live: local port forwarding (ssh -L / -W) is refused"
  else
    fail "live: local port forwarding, unexpected result (exit $status): $(head -n 3 "$scratch/err" | tr '\n' ' ')"
  fi

  # Remote forwarding: with ExitOnForwardFailure, a refusal ends ssh at once;
  # an accepted forward keeps it running until the timeout.
  status=0
  timeout 10 ssh -N -o ExitOnForwardFailure=yes -R "127.0.0.1:0:127.0.0.1:$port" \
    "${ssh_args[@]}" "$target" < /dev/null > /dev/null 2> "$scratch/err" || status=$?
  if [[ $status -eq 124 ]]; then
    fail "live: remote port forwarding (ssh -R) was accepted"
  elif [[ $status -ne 0 ]] && grep -qi 'forwarding failed' "$scratch/err"; then
    pass "live: remote port forwarding (ssh -R) is refused"
  else
    fail "live: remote port forwarding, unexpected result (exit $status): $(head -n 3 "$scratch/err" | tr '\n' ' ')"
  fi

  kill "$live_pid" 2> /dev/null || true
  wait "$live_pid" 2> /dev/null || true
  live_pid=
  rm -rf "$scratch"
  trap - EXIT
}

phone_options=()
blobs=" "
echo "Checking $file for the key with comment $comment"
load_settings
check_file
check_other_files
check_settings
if [[ $live -eq 1 ]]; then
  if [[ ${#phone_options[@]} -eq 0 ]]; then
    fail "live: no phone key line to take the options from"
  else
    for opts in "${phone_options[@]}"; do
      live_check "$opts"
    done
  fi
fi

if [[ $failed -eq 0 ]]; then
  echo "All checks passed."
else
  echo "Some checks failed: see FAIL above."
  exit 1
fi
