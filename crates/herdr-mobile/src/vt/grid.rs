//! The terminal's cell grid read the way herdr-app's VT pipeline reads it
//! (terminal-view spec, "Cell-grid fidelity"), for the goldens and as the
//! contract the frame path draws.
//!
//! Adapted from herdr-app `src/vt.rs` (`Cell`, `Vt::frame`, `read_cell`),
//! Apache-2.0 (see crates/herdr-proto/LICENSE-herdr-Apache-2.0). Changed: no
//! theme, minimum contrast, selection or kitty images (the phone applies its
//! theme to the engine in M4); read through the app's own bindings (ffi.rs).
//! The goldens come from scripts/grid-golden.c, a port of the same code over
//! the library's headers (scripts/record-grid-goldens.sh).

use super::{Failure, Terminal, cell_get, check, ffi};
use std::ffi::c_void;
use std::fmt::Write as _;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Rgb(pub u8, pub u8, pub u8);

impl From<ffi::ColorRgb> for Rgb {
    fn from(c: ffi::ColorRgb) -> Self {
        Rgb(c.r, c.g, c.b)
    }
}

impl std::fmt::Display for Rgb {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:02x}{:02x}{:02x}", self.0, self.1, self.2)
    }
}

/// One cell as herdr-app draws it: colours resolved, inverse applied.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct GridCell {
    /// The whole grapheme; empty for blank cells, hidden text and the tail
    /// half of a wide character.
    pub text: String,
    pub fg: Rgb,
    /// None: the default background.
    pub bg: Option<Rgb>,
    pub bold: bool,
    pub italic: bool,
    pub faint: bool,
    pub underline: bool,
    pub strike: bool,
    pub wide: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CursorShape {
    Block,
    Bar,
    Underline,
    Hollow,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GridCursor {
    pub x: u16,
    pub y: u16,
    pub shape: CursorShape,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Grid {
    pub cols: u16,
    pub rows: u16,
    pub fg: Rgb,
    pub bg: Rgb,
    /// None when hidden or out of view.
    pub cursor: Option<GridCursor>,
    pub cells: Vec<Vec<GridCell>>,
}

impl Grid {
    /// The golden text format (documented in scripts/grid-golden.c).
    pub fn golden(&self) -> String {
        let mut out = format!(
            "grid {}x{}\ndefault fg={} bg={}\n",
            self.cols, self.rows, self.fg, self.bg
        );
        match self.cursor {
            Some(c) => {
                let shape = match c.shape {
                    CursorShape::Block => "block",
                    CursorShape::Bar => "bar",
                    CursorShape::Underline => "underline",
                    CursorShape::Hollow => "hollow",
                };
                let _ = writeln!(out, "cursor {},{} {shape}", c.x, c.y);
            }
            None => out.push_str("cursor none\n"),
        }
        for (y, row) in self.cells.iter().enumerate() {
            let mut line = String::new();
            for (x, cell) in row.iter().enumerate() {
                if !cell.text.is_empty() {
                    line.push_str(&cell.text);
                } else if !(x > 0 && row[x - 1].wide) {
                    line.push(' ');
                }
            }
            let _ = writeln!(out, "row {y}: {}", line.trim_end_matches(' '));
            let mut x = 0;
            while x < row.len() {
                let style = self.style(&row[x]);
                let mut end = x;
                while end + 1 < row.len() && self.style(&row[end + 1]) == style {
                    end += 1;
                }
                if !style.is_empty() {
                    let _ = writeln!(out, "  {x}-{end}{style}");
                }
                x = end + 1;
            }
        }
        out
    }

    fn style(&self, c: &GridCell) -> String {
        let mut s = String::new();
        if c.fg != self.fg {
            let _ = write!(s, " fg={}", c.fg);
        }
        if let Some(bg) = c.bg {
            let _ = write!(s, " bg={bg}");
        }
        for (on, word) in [
            (c.bold, " bold"),
            (c.italic, " italic"),
            (c.faint, " faint"),
            (c.underline, " underline"),
            (c.strike, " strike"),
            (c.wide, " wide"),
        ] {
            if on {
                s.push_str(word);
            }
        }
        s
    }
}

impl Terminal {
    /// The visible grid now (the viewport), with the cursor.
    pub fn grid(&mut self) -> Result<Grid, Failure> {
        unsafe {
            check(
                "ghostty_render_state_update",
                ffi::ghostty_render_state_update(self.state, self.terminal),
            )?;
            let fg: Rgb = self
                .state_get::<ffi::ColorRgb>("foreground", ffi::DATA_COLOR_FOREGROUND)?
                .into();
            let bg: Rgb = self
                .state_get::<ffi::ColorRgb>("background", ffi::DATA_COLOR_BACKGROUND)?
                .into();
            let visible: bool = self.state_get("cursor visible", ffi::DATA_CURSOR_VISIBLE)?;
            let in_view: bool =
                self.state_get("cursor in view", ffi::DATA_CURSOR_VIEWPORT_HAS_VALUE)?;
            let cursor = if visible && in_view {
                let x: u16 = self.state_get("cursor x", ffi::DATA_CURSOR_VIEWPORT_X)?;
                let y: u16 = self.state_get("cursor y", ffi::DATA_CURSOR_VIEWPORT_Y)?;
                let tail: bool =
                    self.state_get("cursor wide tail", ffi::DATA_CURSOR_VIEWPORT_WIDE_TAIL)?;
                let style: i32 = self.state_get("cursor style", ffi::DATA_CURSOR_VISUAL_STYLE)?;
                Some(GridCursor {
                    x: if tail { x.saturating_sub(1) } else { x },
                    y,
                    shape: match style {
                        ffi::CURSOR_BAR => CursorShape::Bar,
                        ffi::CURSOR_UNDERLINE => CursorShape::Underline,
                        ffi::CURSOR_BLOCK_HOLLOW => CursorShape::Hollow,
                        _ => CursorShape::Block,
                    },
                })
            } else {
                None
            };

            let mut cells = Vec::with_capacity(usize::from(self.rows));
            self.load_rows()?;
            while ffi::ghostty_render_state_row_iterator_next(self.rows_iter) {
                self.load_cells()?;
                let mut row = Vec::with_capacity(usize::from(self.cols));
                while row.len() < usize::from(self.cols)
                    && ffi::ghostty_render_state_row_cells_next(self.cells)
                {
                    row.push(self.read_cell(fg, bg)?);
                }
                cells.push(row);
            }
            Ok(Grid {
                cols: self.cols,
                rows: self.rows,
                fg,
                bg,
                cursor,
                cells,
            })
        }
    }

    /// herdr-app's `read_cell`, with its theme background being the
    /// terminal's default background.
    unsafe fn read_cell(&self, fg_default: Rgb, bg_default: Rgb) -> Result<GridCell, Failure> {
        unsafe {
            let raw: ffi::Cell = self.cells_get("cell", ffi::CELLS_DATA_RAW)?;
            let wide: i32 = cell_get(raw, "cell width", ffi::CELL_DATA_WIDE)?;
            let mut style = ffi::Style::sized();
            check(
                "row cells style",
                ffi::ghostty_render_state_row_cells_get(
                    self.cells,
                    ffi::CELLS_DATA_STYLE,
                    (&raw mut style).cast::<c_void>(),
                ),
            )?;
            let mut fg = self
                .own_color(ffi::CELLS_DATA_FG_COLOR)
                .unwrap_or(fg_default);
            let mut bg = self.own_color(ffi::CELLS_DATA_BG_COLOR);
            if style.inverse {
                let new_fg = bg.unwrap_or(bg_default);
                bg = Some(fg);
                fg = new_fg;
            }

            let mut cps: Vec<u32> = Vec::new();
            if wide != ffi::CELL_WIDE_SPACER_TAIL
                && wide != ffi::CELL_WIDE_SPACER_HEAD
                && !style.invisible
            {
                let len: u32 = self.cells_get("grapheme length", ffi::CELLS_DATA_GRAPHEMES_LEN)?;
                if len > 0 {
                    cps = vec![0; len as usize];
                    check(
                        "grapheme",
                        ffi::ghostty_render_state_row_cells_get(
                            self.cells,
                            ffi::CELLS_DATA_GRAPHEMES_BUF,
                            cps.as_mut_ptr().cast::<c_void>(),
                        ),
                    )?;
                }
            }
            let mut text: String = cps.iter().copied().filter_map(char::from_u32).collect();
            let mut underline = style.underline != 0;
            let mut strike = style.strikethrough;
            // A kitty placeholder draws no text and no decorations.
            if text.starts_with('\u{10eeee}') {
                text.clear();
                underline = false;
                strike = false;
            }
            Ok(GridCell {
                text,
                fg,
                bg,
                bold: style.bold,
                italic: style.italic,
                faint: style.faint,
                underline,
                strike,
                wide: wide == ffi::CELL_WIDE_WIDE,
            })
        }
    }

    /// The cell's own colour, None for the default.
    unsafe fn own_color(&self, data: i32) -> Option<Rgb> {
        let mut rgb = ffi::ColorRgb::default();
        let code = unsafe {
            ffi::ghostty_render_state_row_cells_get(
                self.cells,
                data,
                (&raw mut rgb).cast::<c_void>(),
            )
        };
        (code == ffi::SUCCESS).then(|| rgb.into())
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::parse;
    use super::super::*;
    use super::*;
    use crate::synthetic;

    /// `(name, stream, golden)`: streams are invented; goldens come from
    /// scripts/record-grid-goldens.sh (herdr-app's pipeline ported to C).
    const GOLDENS: [(&str, &[u8], &str); 3] = [
        (
            "styles-40x12",
            include_bytes!("goldens/styles-40x12.vt"),
            include_str!("goldens/styles-40x12.grid"),
        ),
        (
            "alt-screen-60x16",
            include_bytes!("goldens/alt-screen-60x16.vt"),
            include_str!("goldens/alt-screen-60x16.grid"),
        ),
        (
            "session-80x24",
            include_bytes!("goldens/session-80x24.vt"),
            include_str!("goldens/session-80x24.grid"),
        ),
    ];

    fn size(name: &str) -> (u16, u16) {
        let (c, r) = name.rsplit('-').next().unwrap().split_once('x').unwrap();
        (c.parse().unwrap(), r.parse().unwrap())
    }

    fn grid_of(name: &str, stream: &[u8], chunk: usize) -> Grid {
        let (cols, rows) = size(name);
        let mut t = Terminal::new(cols, rows).unwrap();
        for part in stream.chunks(chunk) {
            t.write(part);
        }
        t.grid().unwrap()
    }

    #[test]
    fn goldens_match_herdr_apps_pipeline() {
        for (name, stream, golden) in GOLDENS {
            // Any split of the stream: the core's DATA frames fall anywhere.
            for chunk in [stream.len(), 1, 7, 4096] {
                let got = grid_of(name, stream, chunk).golden();
                assert!(
                    got == golden,
                    "{name} in chunks of {chunk} differs from its golden:\n{}",
                    first_difference(&got, golden)
                );
            }
        }
    }

    fn first_difference(got: &str, want: &str) -> String {
        for (i, (g, w)) in got.lines().zip(want.lines()).enumerate() {
            if g != w {
                return format!("line {}:\n  got  {g:?}\n  want {w:?}", i + 1);
            }
        }
        format!(
            "lengths differ: {} lines, golden {}",
            got.lines().count(),
            want.lines().count()
        )
    }

    /// The session golden is fed the stream the frame benchmarks use.
    #[test]
    fn the_session_stream_is_the_synthetic_session() {
        assert_eq!(
            GOLDENS[2].1,
            synthetic::session(80, 24 * 1024).as_slice(),
            "regenerate goldens/session-80x24.vt from synthetic::session(80, 24 KiB)"
        );
    }

    /// The goldens cover what the spec lists: 16, 256 and true colour, bold,
    /// italic, underline styles, inverse, the cursor and wide characters.
    #[test]
    fn the_goldens_cover_every_listed_attribute() {
        let all: String = GOLDENS.iter().map(|g| g.2).collect();
        for word in [
            " bold",
            " italic",
            " faint",
            " underline",
            " strike",
            " wide",
        ] {
            assert!(all.contains(word), "no golden has{word}");
        }
        let g = grid_of(GOLDENS[0].0, GOLDENS[0].1, usize::MAX);
        let cell = |x: usize, y: usize| &g.cells[y][x];
        // inverse swaps (default bg/fg), 16-colour red, 256-colour 208, true colour
        assert_eq!((cell(0, 1).fg, cell(0, 1).bg), (g.bg, Some(g.fg)));
        assert_eq!(cell(0, 2).fg, Rgb(0xcc, 0x66, 0x66));
        assert_eq!(cell(0, 3).fg, Rgb(0xff, 0x87, 0x00));
        assert_eq!(cell(10, 3).fg, Rgb(215, 119, 87));
        // hidden text has no text; a combining mark stays with its base
        assert_eq!(cell(8, 1).text, "");
        assert!(g.golden().contains("e\u{301}"));
        // a wide character's tail is empty, the cursor a bar
        assert_eq!((cell(0, 4).text.as_str(), cell(0, 4).wide), ("日", true));
        assert_eq!(cell(1, 4).text, "");
        assert_eq!(
            g.cursor,
            Some(GridCursor {
                x: 15,
                y: 10,
                shape: CursorShape::Bar
            })
        );
        let alt = grid_of(GOLDENS[1].0, GOLDENS[1].1, usize::MAX);
        assert_eq!(alt.cursor.map(|c| c.shape), Some(CursorShape::Underline));
    }

    /// The frame Kotlin draws carries the same grid, read with herdr-app's
    /// rules (inverse swaps, hidden text is blank), each cell's text the
    /// whole grapheme.
    #[test]
    fn frames_carry_the_golden_grid() {
        for (name, stream, _) in GOLDENS {
            let (cols, rows) = size(name);
            let mut t = Terminal::new(cols, rows).unwrap();
            t.write(stream);
            let grid = t.grid().unwrap();
            let f = parse(&t.frame().unwrap());
            assert_eq!(f.rows_sent.len(), usize::from(rows), "{name}");
            let (dfg, dbg) = (grid.fg, grid.bg);
            assert_eq!((f.fg, f.bg), (word(dfg), word(dbg)), "{name} defaults");
            for ((y, cells), texts) in f.rows_sent.into_iter().zip(f.texts) {
                for (x, cell) in cells.iter().enumerate() {
                    let want = &grid.cells[usize::from(y)][x];
                    let at = format!("{name} cell {x},{y}");
                    let attrs = cell[12];
                    let own = |w: u32| {
                        (w & COLOR_SET != 0).then_some(Rgb(
                            (w >> 16) as u8,
                            (w >> 8) as u8,
                            w as u8,
                        ))
                    };
                    let mut fg = own(u32_at(cell, 4)).unwrap_or(dfg);
                    let mut bg = own(u32_at(cell, 8));
                    if attrs & ATTR_INVERSE != 0 {
                        (fg, bg) = (bg.unwrap_or(dbg), Some(fg));
                    }
                    assert_eq!((fg, bg), (want.fg, want.bg), "{at} colours");
                    let shown = cell[13] < 2 && attrs & ATTR_INVISIBLE == 0;
                    let text = if shown { texts[x].as_str() } else { "" };
                    assert_eq!(text, want.text, "{at} text");
                    assert_eq!(cell[13] == 1, want.wide, "{at} wide");
                    for (bit, on) in [
                        (ATTR_BOLD, want.bold),
                        (ATTR_ITALIC, want.italic),
                        (ATTR_FAINT, want.faint),
                        (ATTR_UNDERLINE, want.underline),
                        (ATTR_STRIKETHROUGH, want.strike),
                    ] {
                        assert_eq!(attrs & bit != 0, on, "{at} attribute {bit}");
                    }
                }
            }
            assert_eq!(
                (f.flags & FLAG_CURSOR != 0).then_some(f.cursor),
                grid.cursor.map(|c| (c.x, c.y)),
                "{name} cursor"
            );
        }
    }

    fn word(c: Rgb) -> u32 {
        (u32::from(c.0) << 16) | (u32::from(c.1) << 8) | u32::from(c.2)
    }

    fn u32_at(b: &[u8], i: usize) -> u32 {
        u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]])
    }
}
