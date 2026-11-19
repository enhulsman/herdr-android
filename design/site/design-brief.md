# kudde.dev design brief: One tag, lit

Chosen 2026-11-19 after five rounds. The binding spec is `design-tokens.json`
next to this file; the approved slice is `slice/onetag2.html` (with its phone,
`slice/phone.html` and `slice/phone.css`), with screenshots in `checkpoints/`.

## The concept

The brand colour is never a background. It is one big ear tag, the kudde
logo at hero scale, hanging on a paper page from a graphite knob through
its punched hole. The phone with the real app stands on its body, where the
logo's `>_` prompt sits. The tag is lit the way the app icon is: light from
above, a gradient from light to deep yellow, a rim along the top edge, a hole
with depth, a soft shadow on the paper.

Bright daylight on good paper. Ink type, set large and tight. Everything
except the tag and the app's screen is quiet.

**The page's job:** install kudde, then finish setup.
**The feeling:** a well-made, independent tool that keeps you in touch with
your agents, and looks as considered as the app.

## The signature: the hanging tag

- One A1 tag per page at hero scale, with the whole tag in view and the hole
  never cropped (at 375 px too). At this size, a cropped hole reads as a
  bottle or a bell.
- It hangs on a round graphite knob fixed to the page, seen head-on in the
  punched hole, with the hole's top edge resting on it. The knob is lit like
  everything else: a faint highlight on top, a darker lower edge, and a small
  shadow down and to the right onto the tag and through the hole. No cord.
- On load it is knocked once: a swing of about 9 degrees on the knob that
  dies away over a few oscillations in about 5 seconds. Afterwards it leans
  up to 1.5 degrees toward a mouse pointer (not on touch screens). The tag and
  the phone turn together around the knob's centre. With reduced motion it
  shows settled, with no swing and no lean.
- The phone stands on the tag's body and casts a warm shadow onto it.

## The rules for yellow

1. **One big tag per page.** The tag where the page opens, the app icon
   where it ends. Everything between is paper and ink.
2. **Yellow is an object, never a background.** No yellow bands, panels or
   washes.
3. **Lit like the app icon.** One soft light from above. The tag, the phone
   and the icon all cast their shadows down and to the right.
4. **Ink on yellow, never yellow on paper.** Text on the tag is ink, yellow
   never carries text or lines on paper, and buttons are ink.

## Palette

| Role | Hex |
|---|---|
| Paper (ground) | `#f7f5ef` |
| Ink (text, rules, buttons) | `#1c1e1b` |
| Muted text | `#5b5d57` |
| Tag yellow, flat | `#f2c230` |
| Tag lit: top, middle, bottom | `#fcdc72`, `#f2c230`, `#d99d12` |
| Tag deep (hover underline) | `#e2ac14` |
| Code ground | `#ece8dc` |

Contrast:

| Pair | Ratio |
|---|---|
| Ink on paper | 15.4 |
| Muted on paper | 6.1 |
| Ink on tag yellow | 10.0 |
| Yellow on paper | 1.5 |

Yellow on paper is decorative only. It never carries text, and it is never
the only sign of a state.

## Type

- **Bricolage Grotesque 800** for display: 104 px, 84 px under 1100 px,
  60 px under 860 px; tracking -0.04em, line height 0.92, opsz 96. Section
  headings use 700.
- **Instrument Sans** for body text: 18 px with a 1.55 line height; the
  lede is 21 px. Buttons and links use 600.
- **JetBrains Mono** for commands and the meta line.

## Pictures

The only pictures are the real app on a phone (the HTML rebuild in
`slice/phone.html`, or real screenshots), the A1 tag and the lit app icon.
Example content uses invented names only: website, api, docs; workstation,
server. No photography and no generated scenes.

## Components

| Component | Description |
|---|---|
| Header | Paper, colour mark plus "kudde", links, ink "Get kudde" button, 2 px ink rule |
| Hero | Copy left; the hanging tag right; the app-icon meta line |
| Section | Opened by a 2 px ink rule; heading and content in a 5/7 split |
| Step markers | A1 outline tags with the number inside; the current step filled |
| Rules list | Two columns, each item opened by a rule |
| Code | Mono on `#ece8dc` |
| Closing app card | The lit app icon (132 px), "kudde", one line, the install button and the APK link |
| Footer | The independence line and the licence |

## Copy

- The name is written in lowercase, "kudde", also at the start of a
  sentence.
- The tone is calm and plain. Hero: "Keep in touch with your herd."
- No alarm or triage framing.
- Footer: "Independent app, built on herdr-core. Not made by the herdr
  project."

## Avoid

- Yellow backgrounds.
- The app's status marks as decoration.
- Tags as a pattern.
- Glows or frost over the screen.
- National flavour.
- herdr's ram.
- Generated pictures with invented copy.

## Accessibility

- WCAG AA.
- 44 px touch targets.
- A visible 3 px ink focus ring.
- Reduced motion is respected: the tag shows settled.
- The hero art is `aria-hidden`; the phone has a text label.

## Checkpoints

| File | View |
|---|---|
| `checkpoints/onetag2-1440.png` | First screen at 1440×900 |
| `checkpoints/onetag2-1440-full.png` | Full page at 1440 |
| `checkpoints/onetag2-375-full.png` | Full page at 375 |
