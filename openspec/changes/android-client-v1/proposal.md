# Proposal: herdr for Android, version 1

## Plain summary

**Decisions still yours:**
1. GitHub repo visibility (needed at M0). Public: Obtainium works with no token,
   anyone can read the code. Private: Obtainium needs a read-only GitHub token on
   the phone. Pick: private until the maintainers have answered, then decide.
2. The maintainer post: drafted in your voice after this spec, shown to you word
   for word, posted by you.

**What you get, each milestone ending with a check on your phone:**
- **M0** An empty app that installs and updates itself through Obtainium from a
  GitHub release you approved; plus three early tests: the terminal engine runs on
  your phone, the SSH key works from the phone's secure hardware, and an
  encrypted alert makes it from WSL through ntfy to the phone.
- **M1** The app connects to WSL over Tailscale and lists every agent, "needs you"
  first.
- **M2** Tapping an agent shows its live terminal as the Mac draws it. Typing
  works like a terminal: the phone keyboard, a row of terminal keys, and a compose
  key for long prompts with autocorrect and dictation. While you type, the
  terminal takes the phone's size; your desk gets its size back on its next input.
- **M3** Alerts with the app closed: "Needs you" (loud) and "Finished" (quiet),
  through your own ntfy, with a warning when a machine has gone quiet.

That is version 1. Version 1.1: **M4** spaces drawer, tab overview, sub-agent
peek, wide layout for landscape and tablets, the other four themes; **M5** the
view-only Changes panel.

Not planned: starting new agents, closing or killing anything, editing
spaces/tabs, the Files panel, mods, the command palette, custom theme files,
image paste. The Mac is out: the phone cannot SSH into it. Agents on WSL show up
whether you started them from the Mac app or not.

## Why

The herdr Mac app is the only way to see and steer agents in herdr-core. Away
from the desk, an agent waiting on a permission prompt waits until Ezra is back.
A phone client that alerts, shows the terminal and lets him answer closes that
gap. herdr-core already exposes everything needed over its JSON-RPC protocol
(Apache-2.0, "preview, generation 1"); no server change is required.

## What changes

New repository `herdr-android` with two deliverables:
- **The Android app**: Kotlin + Jetpack Compose UI over a Rust core that embeds
  libghostty-vt, the terminal engine the Mac app uses.
- **`herdr-push`**: a small watcher that runs next to herdr-core on WSL and sends
  encrypted UnifiedPush alerts through the self-hosted ntfy.

| Capability | What it covers | Version |
|---|---|---|
| `machine-connection` | Machines, SSH over Tailscale, phone key, host keys, protocol check, transport limits, reconnect | v1 |
| `agent-list` | Agents across machines, the shared attention model, order | v1 |
| `terminal-view` | Live terminal drawing, watching without resizing, scrollback, zoom, selection | v1 |
| `terminal-input` | Typing at phone size, keys row, compose key, interrupt | v1 |
| `alerts` | `herdr-push`, encrypted push, three notification channels, canary, mute | v1 |
| `release` | CI build, approval-gated signing, GitHub releases, Obtainium, tests | v1 |
| `appearance` | Themes and light/dark (v1: graphite, paper); wide layout (v1.1) | v1 / v1.1 |
| `spaces` | Read-only spaces drawer, tab overview, peek | v1.1 |
| `changes-view` | View-only git changes | v1.1 |

## Impact

- WSL: one restricted `authorized_keys` line for the phone key (OpenSSH on
  WSL); `herdr-push` as a systemd user service with explicit core config.
- VPS: a write-only ntfy access token for `up*` topics, used only by `herdr-push`;
  anonymous access stays denied. MollySocket's grant is untouched.
- Phone: Tailscale and the ntfy app (both present), Obtainium.
- herdr-core pinned to the tested release (0.1.15); CI also tests the latest.
- Effort (council estimate, low-moderate confidence): 150-250 h for v1 and v1.1
  together; v1 roughly two thirds of that.

## Sources

`decisions.md`; `docs/council/council-2026-10-31.md` and
`docs/council/fix-plan-2026-10-31.md` (review that shaped this version);
`research/source-findings-2026-10-31.md`; `research/ux-brainstorm-2026-10-31.md`;
`research/hc.openrpc-0.1.15.json`; sketch canvas `canvas/project/`
(claude.ai/artifact/RE7aZCduo2naP6RfA1i5ou).
