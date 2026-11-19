# Design: the kudde.dev website

## Stack

Astro, static output, in `site/` of this repository, with its own
`package.json`. Astro because it builds to plain files with no client
framework, keeps shared pieces (header, footer, the phone, the tag) as
components, and is what the maintainer's other site uses. Fonts come from
Fontsource packages and are served from the site itself: a site for an app
whose promise is "no Google, nothing in between" does not load Google Fonts.

## Visual contract

`design/site/design-tokens.json` (schema v2) is binding; `design/site/design-brief.md`
explains it, and `design/site/checkpoints/` holds the approved slice. The
signature is the hanging tag: the A1 tag at hero scale, lit like the app
icon, hanging on a graphite knob through its hole, with the phone standing
on its body. Yellow is only ever an object, never a ground.

The tokens become CSS custom properties in `site/src/styles/tokens.css`;
components use only those.

## Pages

| Path | Holds |
|---|---|
| `/` | Hero with the hanging tag; what the app does (agents, terminal and chat, changes, alerts); three steps; the app card |
| `/install` | What you need; Obtainium; when Android stands in the way; adb; checking a release (fingerprint read from `docs/`) |
| `/setup` | Add a machine; place the phone key; turn on alerts with `kudde-push`; the step markers, current step filled |
| `/security` | The key in secure hardware, host key checks, what an alert carries, what ntfy sees, reporting a problem |
| `/herdr` | kudde and herdr: built on herdr-core, not made by the herdr project, which herdr pieces it uses |

## Drift

The site restates what the README and `docs/` say, briefly, and links to the
guides for the full steps. The one value that must be exact, the signing
fingerprint, is read from `docs/release-signing-fingerprint.txt` at build
time, and a test checks the built page against the file.

## Testing

`site/tests/site.test.mjs` runs Playwright (pinned to the version whose
browser is installed) against a preview of the built site, at 1440 and 375
px, and checks each spec scenario. `npm test` builds and runs it.
