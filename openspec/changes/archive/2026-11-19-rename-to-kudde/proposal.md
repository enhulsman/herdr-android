# Proposal: rename to Kudde

## Plain summary

**Decision** (Ezra, 2026-11-19): the app is called Kudde from 1.0 on. Its
package becomes `dev.kudde.android` (kudde.dev is the project's own domain),
the repository `enhulsman/kudde`, and `herdr-push` becomes `kudde-push`.
Wherever the text means herdr-core, the herdr desk app or herdr's protocol,
it still says herdr.

**What changes for someone using the app:** Kudde installs as a new app next
to the old one, so machines, the phone key and alerts are set up once more.
A machine list exported from the old app imports into Kudde. On each machine,
`herdr-push` is swapped for `kudde-push` with the same settings
(docs/kudde-push.md, "Coming from herdr-push").

## Why

The app is growing past a phone port of the herdr desk app (chat, changes,
and more to come), and the herdr name and the `dev.herdr` package belong to
the herdr project.

## What changes

- The specs name the alert program `kudde-push` where they mean the current
  one. "An older herdr-push" in agent-names stays: older versions carry that
  name.
