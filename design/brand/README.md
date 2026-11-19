# kudde mark: ear tag A1 (chosen 2026-11-19)

A livestock ear tag in tag yellow with a shell prompt. The tag is the herd; the
prompt is the terminal. Drawn by hand: each side of the tag is three tangent
circles; the prompt strokes are as wide as the hole is round, with round ends.

- `kudde-tag-A1.svg`: full colour. Tag `#f2c230`, prompt and ink `#1c1e1b`.
- `kudde-tag-A1-mono.svg`: one-colour outline (themed/monochrome icons,
  favicons on any ground, print).
- `kudde-tag-A1-icon.svg`: app icon on graphite `#1c1e1b`, on the 108 dp
  adaptive-icon canvas with the mark inside the 66 dp safe zone, raised 2.5 dp
  (the tag's wide body made it sit low). Soft light: the tag yellow runs from
  `#fcdc72` to `#d99d12`, a rim light along the top edge, a soft shadow under
  the tag, a faint lift in the graphite. Gradients and clips only, so the
  Android vectors match it exactly.

In the app:

- Release launcher icon (`android/app/src/main/res/drawable/ic_launcher_*.xml`):
  `kudde-tag-A1-icon.svg`, with the prompt and the hole cut out (even-odd)
  instead of drawn in graphite. The themed-icon layer is the same tag, flat.
- Alert icon (`ic_notification.xml`): the flat cut-out tag alone, in white.
- Debug launcher icon (`android/app/src/debug/res/`): inverted, the outline
  mark in graphite on a tag-yellow tile, lit the same way.

The wordmark is not final.
