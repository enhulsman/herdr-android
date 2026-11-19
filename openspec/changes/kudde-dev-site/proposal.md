# Proposal: the kudde.dev website

## Plain summary

**Decisions still yours:**
1. The page text. All of it is drafted from the README and the guides in
   `docs/`, and you read it word for word before anything goes live.
2. Going live: the deploy to kudde.dev (Cloudflare) and the DNS change are a
   separate step, after this change, and only on your OK.

**What changes for people:** kudde.dev stops redirecting to the repository and
becomes a site of five pages: Home, Install, Setup, Security, and kudde and
herdr. It says what the app does, how to install it and check a release, how
to add a machine and turn on alerts, how the security works, and how kudde
relates to herdr. It looks like the approved sketch "One tag, lit": paper and
ink, with one big lit ear tag holding the phone.

## Why

The domain redirects to the repository until there is a site
(`decisions.md`, 2026-11-19). The README is written for people who already
know what herdr-core is; the site is for someone who runs coding agents and
has never heard of kudde, and has to get them from "what is this" to an
installed app with one machine and alerts working.

## What changes

- A static site in `site/` (Astro), built to plain HTML, CSS and a few lines
  of JavaScript. Fonts are bundled; the site loads nothing from anyone else.
- Five pages, in the design of `design/site/` (tokens, brief, checkpoint
  screenshots), with the app shown as an HTML rebuild of its agent list using
  invented names.
- Facts that must not drift are read from the repository at build time: the
  release signing fingerprint (`docs/release-signing-fingerprint.txt`). The
  other facts are restated briefly and link to the guides in `docs/` for the
  details.
- Browser tests in `site/tests/` that check every scenario in the spec.

## Out of scope

- Deploying, DNS, analytics (there are none), a blog, release notes on the
  site, translations, a dark theme.
- Changes to the app or its guides.
