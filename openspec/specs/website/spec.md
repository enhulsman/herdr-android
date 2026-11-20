# website Specification

## Purpose
TBD - created by archiving change kudde-dev-site. Update Purpose after archive.
## Requirements
### Requirement: Five pages share one header and footer
The site SHALL have the pages `/`, `/install`, `/setup`, `/security` and
`/herdr`. Each page SHALL have the colour tag mark with the name "kudde"
linking to `/`, links to Install, Setup, Security, "kudde and herdr" and the
GitHub repository, and a "Get kudde" button linking to `/install`. Each page
SHALL end with a footer that says "Independent app, built on herdr-core. Not
made by the herdr project." Each page SHALL have exactly one `h1`.

#### Scenario: The header on every page
- **WHEN** a visitor opens any of the five pages at 1440 px
- **THEN** the header shows the mark, the five links and the "Get kudde" button, and every internal link resolves to one of the five pages

#### Scenario: The header at phone width
- **WHEN** a visitor opens any page at 375 px
- **THEN** the "Get kudde" button is visible and the page does not scroll sideways

### Requirement: The name is written in lowercase
Text a reader sees SHALL write the app's name as "kudde", also at the start
of a sentence and in titles.

#### Scenario: No capitalised name
- **WHEN** the visible text and the `title` of every page are read
- **THEN** none contains "Kudde" with a capital K

### Requirement: The site loads nothing from anyone else
Pages SHALL load fonts, scripts, styles and images only from the site
itself. There SHALL be no analytics.

#### Scenario: Only own requests
- **WHEN** any page loads
- **THEN** every network request goes to the site's own origin

### Requirement: The hanging tag
The home page SHALL show the A1 tag at hero scale, lit (gradient and rim
light), hanging on a knob through its hole, with the phone on its body. The
whole tag, its hole included, SHALL be inside the first screen at 1440 x 900,
and SHALL be fully in view horizontally at 375 px. The tag SHALL swing once
on load and lean toward a mouse pointer; with reduced motion it SHALL show
settled, without swinging or leaning.

#### Scenario: The hole in view on a phone
- **WHEN** the home page is open at 375 px
- **THEN** the tag's left and right edges and its hole lie within the viewport width

#### Scenario: Reduced motion
- **WHEN** the home page opens with reduced motion requested
- **THEN** the tag has no running animation and does not lean toward the pointer

### Requirement: Yellow is never a background
No section, band or panel SHALL have a tag-yellow background. Tag yellow
SHALL appear only on the tag, the mark, the app icon and the step markers.

#### Scenario: No yellow grounds
- **WHEN** the computed background colour of every element wider than 200 px is read
- **THEN** none is tag yellow (`#f2c230`) or one of its lit shades

### Requirement: The release fingerprint matches the repository
The Install page SHALL show the release signing certificate's SHA-256 digest,
read at build time from `docs/release-signing-fingerprint.txt`.

#### Scenario: Same fingerprint
- **WHEN** the Install page is built
- **THEN** it shows the 64-character digest exactly as the one non-comment line of the file

### Requirement: Example content is invented
The phone and any example SHALL use invented names only (website, api, docs;
workstation, server), never real hosts, addresses or projects.

#### Scenario: The phone's names
- **WHEN** the phone on the home page is read
- **THEN** its agent names and places come from the invented set

### Requirement: The pages are accessible
Pages SHALL meet WCAG 2.2 AA for text contrast, show a visible focus ring on
every link and button, use `lang="en"`, give decorative art `aria-hidden`
and the phone a text label, and keep touch targets at least 44 px high.

#### Scenario: Keyboard focus
- **WHEN** a visitor tabs to the "Get kudde" button
- **THEN** a visible focus ring surrounds it

#### Scenario: Touch targets
- **WHEN** the buttons on any page are measured at 375 px
- **THEN** each is at least 44 px high

### Requirement: Unknown paths get the site's own 404 page
A request for a path that is not a page or a file SHALL be answered with
status 404 and a page in the site's design, with the shared header and
footer, one `h1`, and links to `/` and `/install`.

#### Scenario: A mistyped address
- **WHEN** a visitor opens a path the site does not have
- **THEN** the response has status 404 and the page shows the header, an `h1`, a link home and a link to Install, and the footer

#### Scenario: One sheep got away again
- **WHEN** a visitor opens an unknown path
- **THEN** the page SHALL show a decorative 10 by 10 flock with 99 identical graphite sheep and one visibly empty slot, plus one lost sheep wearing the lit A1 ear tag numbered "404" with each visible foot resting on the ink of the letter directly beneath it in "Oops" in the heading "Oops. One got away again." at 375 px and 1440 px, and tag digits at least 10 CSS px
- **AND** the lost sheep SHALL look toward the gap, hop toward it, overshoot, stop and hold position while looking around, then turn and move the wrong way in one finite 3–5 second CSS animation, ending still on the same letters facing away from the gap
- **AND** with reduced motion the sheep SHALL remain on the heading looking toward the gap with no animation
- **AND** the flock SHALL retain ten rows and ten columns at 375 px and 1440 px without horizontal scroll, with sheep about 16–24 px wide at 375 px
- **AND** the illustration SHALL be hidden from assistive technology and contain no focusable elements, with yellow confined to the small ear tag

### Requirement: Responses carry security headers
Every page, the 404 page and every file SHALL be served with a
Content-Security-Policy that allows only the site itself (no
`'unsafe-inline'` or `'unsafe-eval'`, no other origins, `frame-ancestors
'none'`), Strict-Transport-Security for at least a year,
`X-Content-Type-Options: nosniff`, a Referrer-Policy and a
Permissions-Policy. Files under `/_astro/` SHALL be cached for a year as
immutable.

#### Scenario: Headers on a page and a file
- **WHEN** a page, the 404 page, a stylesheet and a font are requested
- **THEN** each response carries every header above, and the stylesheet and font are cached for a year as immutable

#### Scenario: No policy violations
- **WHEN** any page, the 404 page included, loads at 1440 and at 375 px
- **THEN** the browser reports no Content-Security-Policy violation

