# Kudde mark: ear tag A1 (chosen 2026-11-19)

A livestock ear tag in tag yellow with a shell prompt. The tag is the herd; the
prompt is the terminal. Drawn by hand: each side of the tag is three tangent
circles; the prompt strokes are as wide as the hole is round, with round ends.

- `kudde-tag-A1.svg`: full colour. Tag `#f2c230`, prompt and ink `#1c1e1b`.
- `kudde-tag-A1-mono.svg`: one-colour outline (themed/monochrome icons,
  favicons on any ground, print).
- `kudde-tag-A1-icon.svg`: app icon on graphite `#1c1e1b`, on the 108 dp
  adaptive-icon canvas with the mark inside the 66 dp safe zone.

In the app:

- Release launcher icon (`android/app/src/main/res/drawable/ic_launcher_foreground.xml`):
  the tag on graphite, with the prompt and the hole cut out (even-odd) instead
  of drawn in graphite, so the same layer serves as the themed-icon monochrome.
- Alert icon (`ic_notification.xml`): that cut-out tag alone, in white.
- Debug launcher icon (`android/app/src/debug/res/`): inverted, the outline
  mark in graphite on a tag-yellow tile.

The wordmark is not final.
