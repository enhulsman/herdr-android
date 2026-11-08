# Proposal: alerts say where the session sits

## Plain summary

Alerts name the session ("Pricing page copy needs you") but not where it is at
the desk, so with two sessions in different tabs you still look around for it.

**What changes for you:**
- Needs you: "Pricing page copy needs you" over "Permission · website / 2 · WSL".
- Finished: "Pricing page copy finished" over "website / 2 · WSL"; the machine
  moves from the title to the second line.
- A terminal the desk has not placed yet, or an older `herdr-push`, leaves the
  place out: "Permission · WSL", "WSL".
- The lock screen still shows only "An agent needs you · WSL".

**Decisions:** taken in conversation (Ezra, 2026-11-08): add it, with the
wording above.

## What changes

- `herdr-push` adds an optional `place` field to alert pushes: the desk's
  "space / tab", by the same rule as the Agents list's second line, and only
  when the terminal is placed (no folder fallback; the phone does not need it
  to find the session).
- The app writes the notification's second line from the kind, the place and
  the machine, leaving out what is absent; the finished title loses the machine.
- An older app ignores the unknown field; an older `herdr-push` sends none.
