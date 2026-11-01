# Design brief: herdr for Android

Signed off by Ezra on 2026-11-01 (task 0.2). The binding values are in
`design-tokens.json`; this file explains them. The approved renders are the
reference for every screen: `design/renders/screens.html` (open it in a browser;
`?s=<id>` shows one screen) and the screenshots in `design/checkpoints/`.

## Concept: the same herdr, held in one hand

The phone app uses the Mac app's design system as it is: the same colours, the
same type and the same status language. Only the arrangement changes, for one
thumb. The list is ordered by who needs you, and the terminal is the real
terminal, drawn the way the desk draws it.

The mood is graphite chrome a shade darker than the terminal and quiet grey
text. Colour appears only where an agent has a state. There is no decoration:
the agents and their terminals are the content.

The design deliberately avoids:
- **Material You dynamic colour.** The app should look like herdr, not like the wallpaper.
- **A chat composer or message bubbles.** The terminal is shown as it is.
- **The early sketch's marketing-site colours.** It used a blue working state, amber for needs-you and green for done.
- **Approve/Deny buttons, or anything destructive, in v1.**

## Signature element: the needs-you queue

Agents that need you show as cards at the top of the home list, not as rows.
Each card has a 3 dp red left edge, the kind in red ("Permission", "Question")
and the agent's request in terminal type on the canvas colour. You can read the
request without opening the terminal.

Inside a terminal the same red edge continues as the queue strip under the
title, for example "1 more needs you · next: api tests, question". Tapping it
jumps to that agent.

Rules:
- Only blocked agents (blocked or error) get the red edge.
- The strip appears only when another agent needs you.
- The request text (`msg`) never goes into a notification.

## Colours

The values come from the herdr-app presets, run through the app's own
`resolve()`; none were invented.

| Role | graphite (dark) | paper (light) |
|---|---|---|
| Terminal background | `#17191d` | `#ffffff` |
| Chrome (bars, list) | `#101114` | `#f1f1ee` |
| Canvas (insets, beyond the grid) | `#131518` | `#fafaf9` |
| Popover (cards, sheets) | `#25272b` | `#ffffff` |
| Text | `#eeeeef` | `#1b1b1e` |
| Muted text | `#98999b` | `#69696a` |
| Dim text | `#696a6d` | `#969698` |
| Working | `#e5c07b` (spinner) | `#9a6700` |
| Needs you | `#f0705f` (pill, card edge) | `#c93c2c` |
| Done | `#76a8ff` (dot) | `#2f6fe4` |
| Idle | `#696a6d` (ring) | `#a8a8aa` |

Each theme also defines its 16 ANSI colours, selection and accent; the full set
is in the tokens. v1 ships graphite and paper. The other four Mac themes come
in v1.1.

## Type

- **UI: Geist**, at 400, 500 and 600. Screen title 22/600, row name 16, secondary text 13.
- **Terminal and the card's request text: JetBrains Mono**, with ligatures off, so that `===` stays three cells.
- **Terminal size while typing:** 12.2 sp, which gives about 56 columns on a 412 dp phone.
- **Terminal size while watching:** the desk's grid is scaled to the phone's width. The 96-column example comes out at 7.15 sp.
- Both fonts are OFL and bundled in the app.

## Screens (the approved renders)

| Render | Shows |
|---|---|
| `1-home-dark`, `2-home-light` | Agent list in the order Needs you, Done, Working, Idle, then "Other terminals" (collapsed); an unreachable-machine line |
| `3-terminal-watch` | Watching at desk size: the grid fitted to the width, a dashed line where it ends, and the "Watching … tap to type" chip |
| `4-terminal-type` | Typing at phone size: size tag, keys row (`esc tab ctrl alt ← ↓ ↑ →` and compose), ctrl latched |
| `5-compose` | Compose sheet; Paste sends no Enter |
| `6-notification` | Lock screen: "Needs you" (loud) and "Finished" (quiet); agent, kind and machine only |
| `7-machines` | Machine cards: alert status, mute, test alert, untested-version warning, unreachable machine |
| `8-host-key` | First connection: trust the host key by fingerprint |

## Components

Each component below has its full spec in `design-tokens.json` under `components`:
- agent row
- needs-you card
- section header
- machine notice
- terminal title bar
- queue strip
- watching chip
- size tag
- keys row
- compose sheet
- machine card
- host-key dialog
- notification
- button
- toggle

## Motion

Nothing moves in an orchestrated way. The only motion is the working spinner,
which turns every 900 ms as on the Mac. When an agent moves between sections,
its row slides into place over 200 ms.

With reduced motion on, the spinner stays still as a quarter arc and rows move
without sliding.

## Accessibility

- The target is WCAG 2.2 AA, with touch targets of at least 44 dp and all text in sp. Layouts must hold at 130% font scale.
- No state relies on colour alone: each one also has a mark (spinner, pill, dot or ring) and a section header in words.
- **Known exception, kept on purpose: dim text does not reach 4.5:1.** It is used for subtitles, times and counts. In graphite it reaches 3.49 on chrome and 2.77 on a card. In paper it reaches 2.61.
- **Also kept:** in paper, the status colours used as section headers are slightly under 4.5:1 (4.1 to 4.45).

## Follow-up: contrast

Ezra's call (2026-11-01): ship the Mac values first and judge them on the
phone, outdoors, at the M1 phone check (task M1.5).

If they turn out too faint:
- **Dim text:** lighten it for text only, to graphite `#8d8e90` and paper `#6d6d70`; dim stays for counts and rings. The cost is that subtitles come close to the muted grey.
- **Paper status headers:** shift them 1-3% toward the text colour: `#956401`, `#c73c2c`, `#2e69d6`.

All of these replacements pass 4.5:1. They are recorded in `design-tokens.json`
under `accessibility.followUp`.

## Implementation notes

- **Tokens are law.** The Compose theme is generated from `design-tokens.json`; colours are never hard-coded on a screen.
- **Screens are checked against the renders.** Roborazzi goldens are recorded once the first screens exist, compared against these renders, and signed off at the M1 phone check.
- **The icons** are herdr-app's (Apache-2.0), copied to `design/renders/icons/`; keep the license notice.
- **The renders use invented agent and machine names only.** Never put real project or client names in screenshots or goldens.
