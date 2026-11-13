# herdr for Android

See and answer your [herdr](https://github.com/herdrdev/herdr) agents from your
phone. The app connects to herdr-core on your own machines over SSH, lists every
agent with the ones that need you first, shows each terminal live as the desk
draws it, and lets you type into it. With the app closed, an encrypted alert
tells you when an agent needs you or has finished. Alerts go through your own
ntfy server; no Google service is involved.

**Status:** early, pre-1.0, used daily by its author. Expect rough edges and
changes between releases.

## What works today

- **Agents:** every agent on every machine in one list, "needs you" first, then
  finished (not yet seen), working and idle. Each goes by its session's name
  ("Pricing page copy") and shows where it sits at the desk ("website / 2").
- **New terminals and agents:** start a terminal, or an agent installed on that
  machine (Claude Code, Codex and the others herdr knows), in one of the desk's
  spaces. It opens on the phone and shows up on the desk as a new tab.
- **Live terminal:** the terminal as the desk draws it, with colours, zoom and
  scrollback. Opening it switches the terminal to the phone's size. Type at the
  desk and the desk gets its size back while the phone keeps watching; exit the
  terminal's screen on the phone and the desk gets it back too.
- **Typing:** the phone keyboard and two rows of terminal keys (esc, ctrl, alt,
  tab, arrows, ^C and more). A compose sheet takes long prompts with
  autocorrect and dictation, and pastes them without pressing enter.
- **Alerts:** "Needs you" with sound, "Finished" silently, and a quiet warning
  when a machine has gone silent for 13 hours. Alerts name the session and
  where it sits. Tap an alert to open that agent's terminal. Mute per machine, and a test alert that checks the whole
  path.
- **Security:** the SSH key is made in the phone's secure hardware and never
  leaves it. It works for 30 minutes after you unlock the phone or use your
  fingerprint. You confirm each machine's host key once,
  and a changed key stops the connection.

## What you need

- An Android phone on Android 12 or newer (arm64).
- herdr-core 0.1.15 or newer on a Linux or WSL machine, with an OpenSSH server
  the phone can reach (for example over Tailscale). Tailscale SSH does not work:
  it ignores the phone key.
- For alerts: an ntfy server, the ntfy app on the phone, and `herdr-push` on
  each machine.
- To install and update: [Obtainium](https://github.com/ImranR98/Obtainium).

## Setting up

1. **Install the app** through Obtainium from this repository's releases; see
   [docs/release.md](docs/release.md#obtainium-setup). Check each update before
   installing it ([how](docs/release.md#before-installing-an-update)).
2. **Add a machine:** in the app, tap "Add machine" and enter its address, port
   and user. Compare the host key fingerprint with the machine's own, then
   copy the phone key line into that user's `~/.ssh/authorized_keys`. The line
   lets the key run only herdr-core: no shell session, no forwarding. herdr-core
   can still run commands as that user, so treat the key like a shell login.
3. **Turn on alerts:** install `herdr-push` on the machine and turn alerts on in
   the app; see [docs/herdr-push.md](docs/herdr-push.md).

"Finished" alerts are silent by default. To hear them, open Android settings,
go to the app's notifications, open "Finished", and set it to alerting.

## Roadmap

Version 1.0 completes the loop: a chat view beside the terminal that you
can also answer from, Stay connected, muting a session, and a view of an
agent's git changes, plus the work that lets someone else set the app up
without help.

What is planned and what is being worked on now are tracked in the open:
the [v1.0.0 milestone](https://github.com/enhulsman/herdr-android/milestone/1)
and the [roadmap board](https://github.com/users/enhulsman/projects/4). Ideas
and bug reports are welcome as issues.

**Not planned:** closing or killing agents, editing spaces and tabs, a file
browser, custom theme files, image paste.

## Repository

- `android/`: the app (Kotlin and Jetpack Compose).
- `crates/`: the Rust core the app uses (SSH, the herdr-core protocol, the
  terminal engine) and `herdr-push`.
- `docs/`: guides for releases and `herdr-push`, phone checklists, release notes.
- `openspec/`: the specification, and `decisions.md` the decisions behind it.
- `design/`: the signed-off screen renders.
- `THIRD_PARTY_NOTICES.md`: licences of the bundled material.

## Licence

Apache License 2.0; see [LICENSE](LICENSE). Bundled fonts and other third-party
material keep their own licences, listed in `THIRD_PARTY_NOTICES.md`.
