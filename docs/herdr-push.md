# herdr-push: alerts on the phone

`herdr-push` runs on the machine where your agents run, as a systemd user
service. It watches that machine's herdr-core and sends an encrypted push to
your phone when an agent needs you or finishes. The push goes through your ntfy
server to the ntfy app on the phone (UnifiedPush); no Google service is involved.

What it sends: agent name, machine, state and request kind ("permission"), and
the time. Never screen text or the agent's message. ntfy only sees ciphertext.

## What it does

- An agent that stays blocked (or in error) for the grace delay (30 s) gives one
  "Needs you" alert. Answer it at the desk within the delay and nothing is sent.
- When an alerted agent stops needing you, a "cleared" push removes the
  notification from the phone, also when the app is closed.
- An agent that finishes a turn gives a quiet "Finished" alert after the delay.
- Sub-agents and terminals that are not in a herdr-app pane stay quiet. Without
  a herdr-app layout every agent may alert.
- Every 6 hours it sends a canary, so the phone can tell a quiet machine from a
  broken alert path (after 13 hours of silence it shows "Alert health").
- It writes what ntfy last answered into the `hdroid-push-status` document on
  the core; the app shows "Last accepted by ntfy hh:mm" or "Alerts failing".
- It only connects to an existing core and never starts one. With no core it
  logs "no core" and tries again every 30 s.
- After a restart it alerts only what it has not alerted before
  (`~/.local/state/herdr-push/state.json`).

## Install

1. Download `herdr-push`, `herdr-push.sig` and `SHA256SUMS` from the release and
   check them (see [release.md](release.md#checking-herdr-push-on-a-machine)).
2. `install -m 755 herdr-push ~/.local/bin/herdr-push`
3. Put the ntfy access token in `~/.config/herdr-push/ntfy-token` (mode 600).
   Use a dedicated ntfy user that may only write to `up*` topics, never read:

   ```
   ntfy user add herdr-push
   ntfy access herdr-push 'up*' write-only
   ntfy token add herdr-push
   ```

4. Write `~/.config/herdr-push/config`:

   ```
   # The core to watch. Find it with: ls "$XDG_RUNTIME_DIR/herdr-core/"
   socket = /run/user/1000/herdr-core/default.sock
   # Where pushes may go: your ntfy server (https only, repeatable).
   allow = https://ntfy.example.org
   # Optional: seconds a state must hold before it alerts (default 30).
   # grace = 30
   # Optional: the token file (default: ntfy-token next to this file).
   # token_file = ~/.config/herdr-push/ntfy-token
   ```

   The socket is `$XDG_RUNTIME_DIR/herdr-core/<session>.sock`; the session is
   `default` unless you set `HC_SESSION`. A `socket` in herdr-core's own config
   or `HC_SOCKET` moves it.
5. Install and start the service:

   ```
   cp contrib/herdr-push.service ~/.config/systemd/user/
   systemctl --user daemon-reload
   systemctl --user enable --now herdr-push
   loginctl enable-linger "$USER"   # keep it running without a login session
   ```

   On WSL, systemd must be on (`[boot] systemd=true` in `/etc/wsl.conf`).
6. In the app, turn on alerts for the machine and tap "Send test alert".

## Checking it

```
systemctl --user status herdr-push
journalctl --user -u herdr-push -f
```

The log names each push with the ntfy host and what ntfy answered, for
example `alert t3 blocked to ntfy.example.org: Answered(200)`. A 403 means the
token was refused (revoked, or no write access to `up*`).

The phone writes `hdroid-push` on the core (its endpoint and keys); the
watcher only sends to endpoints on a host in `allow`, and sends the token
nowhere else. An entry on another host is ignored and noted in
`hdroid-push-status`.
