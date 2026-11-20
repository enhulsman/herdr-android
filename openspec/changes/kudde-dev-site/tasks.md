# Tasks: the kudde.dev website

Tests come first: every scenario in `specs/website/spec.md` becomes a check in
`site/tests/site.test.mjs` before the pages exist.

## 1. Groundwork

- [x] 1.1 Move the approved design into `design/site/`: tokens, brief, checkpoint screenshots and the approved slice
- [x] 1.2 Scaffold `site/` (Astro, static output), Fontsource fonts, `tokens.css` from the tokens
- [x] 1.3 Write `site/tests/site.test.mjs` from the spec scenarios; `npm test` builds, previews and runs it (fails at first)

## 2. Pages

- [x] 2.1 Shared layout: header, footer, the mark, the phone component, the hanging tag, the app card, step markers
- [x] 2.2 Home
- [x] 2.3 Install, with the fingerprint read from `docs/`
- [x] 2.4 Setup
- [x] 2.5 Security
- [x] 2.6 kudde and herdr

## 3. Check

- [x] 3.1 All tests pass; screenshots at 1440 and 375 for every page
- [ ] 3.2 Ezra reads the page text word for word and approves it
- [ ] 3.3 Review (Codex), then the PR

## 4. Live

- [x] 4.1 Hosting config in the PR: `site/wrangler.jsonc` (assets only, 404 page, Custom Domain `kudde.dev`), `public/_headers`, the 404 page, and tests through `wrangler dev`
- [x] 4.2 On Ezra's OK: the first `wrangler deploy` from a terminal, answering its question about the existing `kudde.dev` record, and removing the apex redirect rule at the same time
- [ ] 4.3 Git-connected builds in the Cloudflare dashboard, so each merge to `main` deploys
