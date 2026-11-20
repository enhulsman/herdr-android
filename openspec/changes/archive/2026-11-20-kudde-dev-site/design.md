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

## Hosting

Cloudflare Workers with static assets only: no Worker script runs, so every
request is answered from `site/dist/`. `site/wrangler.jsonc` names the Worker
`kudde-site`, points `assets.directory` at `./dist`, sets
`not_found_handling` to `404-page` (an unknown path gets `dist/404.html` with
status 404) and routes the apex `kudde.dev` as a Custom Domain, for which
Cloudflare creates the DNS record and the certificate on deploy. With a route
set, wrangler leaves the `workers.dev` address off.

- **Paths.** Astro builds `install.html`, not `install/index.html`: the
  default `html_handling` (`auto-trailing-slash`) serves a file at `/install`
  but a folder index only at `/install/`, behind a redirect.
- **Headers.** `site/public/_headers` (copied to `dist/`) sets a
  Content-Security-Policy that allows only the site itself (`data:` images for
  the paper grain), HSTS, `nosniff`, `X-Frame-Options`, `Referrer-Policy:
  no-referrer` and a Permissions-Policy, and caches the hashed files under
  `/_astro/` for a year as immutable. Astro is set to write every script and
  stylesheet to a file and the components use no `style` attributes, so the
  policy needs neither `'unsafe-inline'` nor hashes.
  `Cross-Origin-Opener-Policy` is left out: with it, headless Chromium reports
  no fine pointer and the tag's lean is lost, and a static site without
  popups gains little from it.
- **Compatibility date.** `2026-11-11`, the newest date the pinned wrangler's
  local runtime accepts; nothing in an assets-only Worker depends on it.
- **Tests.** `npm test` serves `dist/` with `wrangler dev` (local only, no
  account), so routing, the 404 page and the headers are tested as Cloudflare
  serves them.
- **Going live.** A first `wrangler deploy` from a terminal creates the
  Worker and the Custom Domain. If `kudde.dev` already has a DNS record,
  wrangler asks before replacing it; without a terminal (CI, Git builds) it
  replaces it without asking. A redirect rule on the apex runs before the
  Worker, so the current redirect to the repository must be removed at the
  same time. After that, Git-connected builds in the dashboard deploy each
  merge to `main` (build command `npm ci && npm run build`, root `site/`).

