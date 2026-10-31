# Proposal: herdr for Android, version 1

## Plain summary

**Decisions still yours (none block writing code for M0):**
1. GitHub repo visibility. Public: Obtainium works with no token, anyone can read
   the code. Private: Obtainium needs a read-only GitHub token on the phone. Pick:
   private until the maintainers have answered, then decide.
2. When to post the question to the herdr maintainers (agreed: before code). Pick:
   right after the council review, with the spec's protocol questions attached.

**What you get, milestone by milestone, each ending with a check on your phone:**
- **M0** An empty app that installs and updates itself through Obtainium from a
  GitHub release you approved.
- **M1** The app connects to WSL and the Mac over Tailscale and shows every agent,
  with "needs you" agents at the top.
- **M2** Tapping an agent shows its live terminal, exactly as the Mac draws it,
  without changing the size of the terminal on your desk.
- **M3** You can answer agents: a text box (with your phone keyboard, dictation,
  paste) and a row of terminal keys; a "type directly" mode for menus and vim.
- **M4** Alerts with the app closed ("Needs you", "Finished"), through your own
  ntfy, plus the spaces drawer and the wide layout for tablets and landscape.
- **M5** The view-only Changes panel: what an agent changed, before you approve it.

Out of v1: starting new agents, closing or killing anything, editing spaces/tabs,
the Files panel, mods, the command palette, custom theme files, image paste.

## Why

The herdr Mac app is the only way to see and steer agents in herdr-core. Away
from the desk, an agent waiting on a permission prompt waits until Ezra is back.
A phone client that can see every agent, read its terminal and answer it closes
that gap. herdr-core already exposes everything needed over its JSON-RPC protocol
(Apache-2.0, "preview, generation 1"); no server change is required.

## What changes

New repository `herdr-android` with two deliverables:
- **The Android app** (Kotlin + Jetpack Compose UI over a Rust core that embeds
  libghostty-vt, the terminal engine the Mac app uses).
- **`herdr-push`**, a small watcher that runs next to each herdr-core and sends
  encrypted UnifiedPush alerts through the self-hosted ntfy.

Capabilities (each has a spec under `specs/`):

| Capability | What it covers |
|---|---|
| `machine-connection` | Machines list, SSH over Tailscale, phone key, host keys, protocol check, reconnect |
| `agent-list` | Merged agents across machines, attention order, states |
| `terminal-view` | Live terminal drawing, observe-by-default size rule, scrollback, zoom, selection |
| `terminal-input` | Composer, keys row, direct typing, taking the terminal size |
| `spaces` | Read-only spaces/tabs drawer and tab overview from the shared layout |
| `alerts` | `herdr-push` watcher, encrypted push, Android notification channels, mute |
| `changes-view` | View-only git changes for an agent's folder |
| `appearance` | Themes, light/dark, phone and wide layouts |
| `release` | CI build, approval-gated signing, GitHub releases, Obtainium, test loop |

## Impact

- Machines (WSL, Mac): one `authorized_keys` line per machine for the phone key,
  restricted to herdr-core; `herdr-push` installed as a user service.
- VPS: one more anonymous write-only ntfy topic grant (the MollySocket pattern).
- Phone: Tailscale (already present), ntfy app as UnifiedPush distributor
  (already present), Obtainium.
- herdr-core version pinned to the tested release (0.1.15 at time of writing;
  0.1.16 changes one size-ownership detail, see `terminal-view`).

## Sources

`decisions.md`, `research/source-findings-2026-10-31.md`,
`research/ux-brainstorm-2026-10-31.md`, `research/hc.openrpc-0.1.15.json`,
sketch canvas `canvas/project/` (published: claude.ai/artifact/RE7aZCduo2naP6RfA1i5ou).
