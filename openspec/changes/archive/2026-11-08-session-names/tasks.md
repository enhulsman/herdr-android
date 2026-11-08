Tests come first in every section: write the section's tests (listed in its
first task), see them fail, then build. Example titles and places use invented
names (website, api, docs; "Pricing page copy").

## 1. Shared rule (herdr-proto, herdr-push)

- [x] 1.1 Tests: every scenario of "An agent is named after its session" and "The list shows where an agent sits" against `Tracker` rows and others (title used, name wins, no title, quit keeps title but row is an other named by its shell, whitespace-only title, control characters, 120-char title cut to 79 + "…" on a char boundary with a multi-byte character at the cut, `where` present, absent, whitespace-only); `where` with an empty `herdr.space` (parked) gives no place; herdr-push: an alert payload carries the titled name and no new field, the body stays under 4 KiB with an 80-char name
- [x] 1.2 `attention.rs`: `clean()`, the D1 rule in `name_of` for status rows only, `place` on `Row` and `Other` (D2)
- [x] 1.3 Amend V1 in place in `openspec/changes/android-client-v1/specs/`: agent-list's name sentence points to `agent-names`; alerts' "never screen text or `msg`" sentence says `name` may be a session title; "Three notification channels" drops "lock screen" from "Needs you" and points to `agent-names`; herdr-push `deliver.rs` and `Alerts.kt` doc comments to match

## 2. Hub (herdr-mobile)

- [x] 2.1 Tests: a snapshot row carries the titled name and `place`; a title change on a status event renames the row
- [x] 2.2 `place` through the hub's views and uniffi; regenerate bindings

## 3. Android

- [x] 3.0 Device spike (D3): with "hide sensitive content" on, post a PRIVATE notification with a public version on the existing `needs-you` channel; record in design.md whether the lock screen redacts it, and keep or move the channel accordingly
- [x] 3.1 Tests: `AgentsModel` second line with and without place (agents and others); terminal screen header with the titled name and the place line; `Alerts` texts with the titled name, every agent note and the test note carry the fixed public version; Roborazzi goldens updated for the list (place lines) and reviewed
- [x] 3.2 `AgentsModel` line two (list and terminal header); public versions in `Alerts`; `AndroidAlertSink` sets `VISIBILITY_PRIVATE` and `setPublicVersion`

## 4. Check and release

- [x] 4.1 Codex review of the branch
- [x] 4.2 Phone check (2026-11-08, debug build, herdr-push from this branch on WSL): titled rows and "space / tab · WSL" lines for the real sessions; a test terminal titled "Pricing page copy" alerted as "Pricing page copy needs you"; locked with sensitive content hidden, the lock screen showed "An agent needs you · WSL" (v0.2.0 beside it showed only "herdr"); titles sampled mid-turn carry no glyphs; "Needs you" alerted on its existing channel. Not checked: a tab rename or reorder at the desk (the desk app was not running), so `where` following a rename stays the open risk in design D2
- [ ] 4.3 Archive the change; release notes tell you to update `herdr-push`
