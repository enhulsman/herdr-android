# Phone testing

How an agent runs phone checks (the `4.x` tasks of a change) on its own. The
real names, the PIN and the network details are in `local/setup.md`.

## The phones

- **The test phone**: an old phone kept on wireless adb with a throwaway PIN,
  for agents to drive alone: unlock, tap through the app, lock-screen checks,
  forced Doze. It has no SIM.
- **The daily phone**: the person's own. Install there only when asked (owner
  profile only, see AGENTS.md), and never drive it with `scripts/phone.sh`.

What the test phone cannot check: Wi-Fi to mobile data (no SIM, and Wi-Fi off
ends wireless adb), a GrapheneOS-only behaviour (it runs stock Android), and
anything that needs root on the desk (a firewall rule: ask the person). A
battery cost is measured on it as with and without over several nights, after
the person unplugs it; Wi-Fi only, so a lower bound.

## Rules

- Never type into a real agent's terminal. Use the fake agent below.
- Never act on the test phone's other notifications. It still receives the
  person's own alerts, such as SSH approval requests: leave them alone.
- Undo what you force: `scripts/phone.sh doze off` after a Doze check.
- Install with `scripts/phone.sh install`: owner profile only.

## Driving it

`scripts/phone.sh` (its header lists the commands). Things learned the hard way:

- `ui` reads the screen through uiautomator, which fails while the app shows a
  spinner or other animation. Take a `shot` and read the image instead; tap by
  coordinates (`scripts/phone.sh input tap X Y`, screen pixels).
- The lock screen drops back to the always-on display within seconds, so
  `unlock` sends wake, swipe and PIN in one adb shell. A swipe that starts too
  low hits the lock-screen shortcuts.
- `adb shell input text " word"` loses the leading space.
- `note` prints the status notification; `alerts` lists the app's
  notifications. A notification swiped away leaves the live list but stays in
  dumpsys's "attention state" section.

## A fake agent

`scripts/fake-agent.sh start` opens terminal `phonecheck` on the desk; `set
working|blocked|idle|error` changes what it reports. Working then idle makes it
"done" on the phone until opened there. Typing into it from the phone is
harmless: it echoes. `stop` removes it. herdr-push sends no alert for it (it
alerts only for terminals in a Herdr app pane), so check alerts with a real
agent's quiet "Finished" alert, for example the checking agent's own turn
ending.

## Measuring on the desk

- **SSH logins**: `ss -tn sport = :<ssh port>` lists the phone's connections;
  a new remote port means a new login. Log it every few seconds in the
  background for a long check.
- **Terminal attach and detach**: `herdr-core get phonecheck` shows `cols` and
  `rows`; the phone's size while attached, the desk's again after the detach.
  Rows alone change when the keyboard opens or closes.
- **Cutting the phone's connection** (key-window checks): find the
  `sshd: <user>@notty` process with a `herdr-core connect` child that started
  when the phone connected, confirm the phone's remote port is unchanged, then
  kill that process. Never one that could be another phone's.
- **Doze**: `scripts/phone.sh doze on` with the screen off; `dumpsys deviceidle`
  shows `mState=IDLE`. A forced Doze on a charging phone stands in for a real
  night; the real night is the battery check.
- The key window is 30 minutes from the last unlock: a check that needs it
  closed waits 31 minutes with the phone locked.
- Broadcasts: `dumpsys activity broadcasts history` shows whether a broadcast
  reached the app's receiver (how the not-exported unlock receiver was found).
