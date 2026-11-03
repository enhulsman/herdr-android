//! libghostty-vt behind a small safe wrapper, and the frame format Kotlin draws
//! from (M0.5; graphemes, scrollback and cursor shape since M2).
//!
//! # Frame format (little-endian)
//!
//! Header, [`HEADER_LEN`] bytes:
//!
//! | bytes | field |
//! |---|---|
//! | 0..2 | cols (u16) |
//! | 2..4 | rows (u16) |
//! | 4..6 | cursor x (u16) |
//! | 6..8 | cursor y (u16) |
//! | 8 | flags: bit 0 full frame (every row follows), bit 1 cursor shown, bit 2 live (the view is at the bottom) |
//! | 9 | cursor shape: see the `SHAPE_*` constants |
//! | 10..12 | number of rows that follow (u16) |
//! | 12..16 | default foreground, `0x00RRGGBB` |
//! | 16..20 | default background, `0x00RRGGBB` |
//! | 20..24 | rows in screen and scrollback together (u32) |
//! | 24..28 | the view's first row in that space (u32): `total - rows` when live |
//! | 28..32 | reserved, 0 |
//!
//! Then each changed row: its y (u16), `cols` cells of [`CELL_LEN`] bytes, then
//! the row's extra codepoints (u32 each), cell by cell, in cell order:
//!
//! | bytes | field |
//! |---|---|
//! | 0..4 | codepoint (u32), 0 for no text: the grapheme's first codepoint |
//! | 4..8 | foreground: `0x01RRGGBB` when the cell has its own colour, else 0 |
//! | 8..12 | background: the same |
//! | 12 | attributes: see the `ATTR_*` constants |
//! | 13 | width: 0 narrow, 1 wide, 2 spacer after a wide cell, 3 spacer at a line end |
//! | 14 | how many more codepoints the grapheme has (combining marks, ZWJ parts) |
//! | 15 | reserved, 0 |

#[cfg_attr(not(test), allow(dead_code))]
mod ffi;
// The cell grid in herdr-app's form, for the golden tests.
#[cfg_attr(not(test), allow(dead_code))]
mod grid;
pub(crate) mod keys;
mod mouse;

use std::ffi::c_void;
use std::fmt;
use std::ptr;

pub const HEADER_LEN: usize = 32;
pub const CELL_LEN: usize = 16;
pub const FLAG_FULL: u8 = 1;
pub const FLAG_CURSOR: u8 = 2;
pub const FLAG_LIVE: u8 = 4;
// The cursor shapes Kotlin draws; named here for the format's sake.
#[allow(dead_code)]
pub const SHAPE_BAR: u8 = 0;
pub const SHAPE_BLOCK: u8 = 1;
#[allow(dead_code)]
pub const SHAPE_UNDERLINE: u8 = 2;
#[allow(dead_code)]
pub const SHAPE_BLOCK_HOLLOW: u8 = 3;
pub const COLOR_SET: u32 = 0x0100_0000;
pub const ATTR_BOLD: u8 = 1;
pub const ATTR_ITALIC: u8 = 1 << 1;
pub const ATTR_FAINT: u8 = 1 << 2;
pub const ATTR_UNDERLINE: u8 = 1 << 3;
pub const ATTR_INVERSE: u8 = 1 << 4;
pub const ATTR_STRIKETHROUGH: u8 = 1 << 5;
pub const ATTR_INVISIBLE: u8 = 1 << 6;

/// A libghostty-vt call that did not return success.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Failure {
    pub call: &'static str,
    pub code: i32,
}

impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} returned {}", self.call, self.code)
    }
}

impl std::error::Error for Failure {}

fn check(call: &'static str, code: ffi::GhosttyResult) -> Result<(), Failure> {
    if code == ffi::SUCCESS {
        Ok(())
    } else {
        Err(Failure { call, code })
    }
}

/// One terminal and the render state that reads it.
pub struct Terminal {
    terminal: ffi::Terminal,
    state: ffi::RenderState,
    rows_iter: ffi::RowIterator,
    cells: ffi::RowCells,
    keys: ffi::KeyEncoder,
    key_event: ffi::KeyEvent,
    cols: u16,
    rows: u16,
    sent_first_frame: bool,
    /// The view moved or the grid changed size: the next frame sends every row.
    force_full: bool,
}

// The handles are only used through &mut self, so one thread at a time.
unsafe impl Send for Terminal {}

impl Terminal {
    pub fn new(cols: u16, rows: u16) -> Result<Self, Failure> {
        if cols == 0 || rows == 0 {
            return Err(Failure {
                call: "Terminal::new (cols and rows must be above 0)",
                code: -2,
            });
        }
        // Built field by field so Drop frees whatever was created if a later call fails.
        let mut t = Terminal {
            terminal: ptr::null_mut(),
            state: ptr::null_mut(),
            rows_iter: ptr::null_mut(),
            cells: ptr::null_mut(),
            keys: ptr::null_mut(),
            key_event: ptr::null_mut(),
            cols,
            rows,
            sent_first_frame: false,
            force_full: false,
        };
        unsafe {
            check(
                "ghostty_terminal_new",
                ffi::ghostty_terminal_new(ptr::null(), &mut t.terminal, cols, rows),
            )?;
            check(
                "ghostty_render_state_new",
                ffi::ghostty_render_state_new(ptr::null(), &mut t.state),
            )?;
            check(
                "ghostty_render_state_row_iterator_new",
                ffi::ghostty_render_state_row_iterator_new(ptr::null(), &mut t.rows_iter),
            )?;
            check(
                "ghostty_render_state_row_cells_new",
                ffi::ghostty_render_state_row_cells_new(ptr::null(), &mut t.cells),
            )?;
            check(
                "ghostty_key_encoder_new",
                ffi::ghostty_key_encoder_new(ptr::null(), &mut t.keys),
            )?;
            check(
                "ghostty_key_event_new",
                ffi::ghostty_key_event_new(ptr::null(), &mut t.key_event),
            )?;
        }
        Ok(t)
    }

    /// Feeds program output to the terminal. Any split of the stream is fine.
    pub fn write(&mut self, bytes: &[u8]) {
        unsafe { ffi::ghostty_terminal_vt_write(self.terminal, bytes.as_ptr(), bytes.len()) }
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn size(&self) -> (u16, u16) {
        (self.cols, self.rows)
    }

    /// A new grid size (the phone's own, while it types). Content reflows.
    pub fn resize(&mut self, cols: u16, rows: u16) -> Result<(), Failure> {
        if cols == 0 || rows == 0 {
            return Err(Failure {
                call: "Terminal::resize (cols and rows must be above 0)",
                code: -2,
            });
        }
        check("ghostty_terminal_resize", unsafe {
            ffi::ghostty_terminal_resize(self.terminal, cols, rows, 10, 20)
        })?;
        (self.cols, self.rows) = (cols, rows);
        self.force_full = true;
        Ok(())
    }

    /// Moves the view through the scrollback; up is negative. A view that is
    /// not at the bottom stays where it is while output arrives.
    pub fn scroll(&mut self, rows: i32) {
        let delta = rows as isize as u64;
        unsafe {
            ffi::ghostty_terminal_scroll_viewport(
                self.terminal,
                ffi::ScrollViewport {
                    tag: ffi::SCROLL_DELTA,
                    value: [delta, 0],
                },
            );
        }
        self.force_full = true;
    }

    /// Back to the bottom, following output again ("Live").
    pub fn scroll_to_live(&mut self) {
        unsafe {
            ffi::ghostty_terminal_scroll_viewport(
                self.terminal,
                ffi::ScrollViewport {
                    tag: ffi::SCROLL_BOTTOM,
                    value: [0, 0],
                },
            );
        }
        self.force_full = true;
    }

    unsafe fn terminal_get<T: Default>(&self, call: &'static str, data: i32) -> Result<T, Failure> {
        let mut value = T::default();
        let code = unsafe {
            ffi::ghostty_terminal_get(self.terminal, data, (&raw mut value).cast::<c_void>())
        };
        check(call, code).map(|()| value)
    }

    /// Encodes the rows that changed since the last frame; the first frame has
    /// every row. See the module docs for the format.
    pub fn frame(&mut self) -> Result<Vec<u8>, Failure> {
        unsafe {
            check(
                "ghostty_render_state_update",
                ffi::ghostty_render_state_update(self.state, self.terminal),
            )?;
            let dirty: i32 = self.state_get("dirty", ffi::DATA_DIRTY)?;
            let full = !self.sent_first_frame || self.force_full || dirty == ffi::DIRTY_FULL;
            let shape: i32 = self.state_get("cursor style", ffi::DATA_CURSOR_VISUAL_STYLE)?;
            let bar: ffi::Scrollbar =
                self.terminal_get("scrollbar", ffi::TERMINAL_DATA_SCROLLBAR)?;
            let live: bool =
                self.terminal_get("viewport active", ffi::TERMINAL_DATA_VIEWPORT_ACTIVE)?;
            let fg: ffi::ColorRgb = self.state_get("foreground", ffi::DATA_COLOR_FOREGROUND)?;
            let bg: ffi::ColorRgb = self.state_get("background", ffi::DATA_COLOR_BACKGROUND)?;
            let shown: bool = self.state_get("cursor visible", ffi::DATA_CURSOR_VISIBLE)?;
            let in_view: bool =
                self.state_get("cursor in view", ffi::DATA_CURSOR_VIEWPORT_HAS_VALUE)?;
            let (cx, cy) = if in_view {
                (
                    self.state_get::<u16>("cursor x", ffi::DATA_CURSOR_VIEWPORT_X)?,
                    self.state_get::<u16>("cursor y", ffi::DATA_CURSOR_VIEWPORT_Y)?,
                )
            } else {
                (0, 0)
            };

            let mut out = Vec::with_capacity(HEADER_LEN);
            out.extend_from_slice(&self.cols.to_le_bytes());
            out.extend_from_slice(&self.rows.to_le_bytes());
            out.extend_from_slice(&cx.to_le_bytes());
            out.extend_from_slice(&cy.to_le_bytes());
            let mut flags = 0;
            if full {
                flags |= FLAG_FULL;
            }
            if shown && in_view {
                flags |= FLAG_CURSOR;
            }
            if live {
                flags |= FLAG_LIVE;
            }
            out.push(flags);
            out.push(u8::try_from(shape).unwrap_or(SHAPE_BLOCK));
            out.extend_from_slice(&0u16.to_le_bytes()); // row count, set below
            out.extend_from_slice(&fg.word().to_le_bytes());
            out.extend_from_slice(&bg.word().to_le_bytes());
            let clamp = |n: u64| u32::try_from(n).unwrap_or(u32::MAX);
            out.extend_from_slice(&clamp(bar.total).to_le_bytes());
            out.extend_from_slice(&clamp(bar.offset).to_le_bytes());
            out.extend_from_slice(&[0; 4]);

            let mut count: u16 = 0;
            if full || dirty != ffi::DIRTY_FALSE {
                self.load_rows()?;
                while ffi::ghostty_render_state_row_iterator_next(self.rows_iter) {
                    let row_dirty: bool = self.row_get("row dirty", ffi::ROW_DATA_DIRTY)?;
                    if !full && !row_dirty {
                        continue;
                    }
                    let y: i32 = self.row_get("row y", ffi::ROW_DATA_VIEWPORT_Y)?;
                    let Ok(y) = u16::try_from(y) else { continue };
                    if y >= self.rows {
                        continue;
                    }
                    out.reserve(2 + usize::from(self.cols) * CELL_LEN);
                    out.extend_from_slice(&y.to_le_bytes());
                    self.encode_row(&mut out)?;
                    count += 1;
                }
            }
            out[10..12].copy_from_slice(&count.to_le_bytes());

            check(
                "ghostty_render_state_clean",
                ffi::ghostty_render_state_clean(self.state),
            )?;
            self.sent_first_frame = true;
            self.force_full = false;
            Ok(out)
        }
    }

    /// The screen as text: one line per row, trailing spaces and empty rows
    /// removed, wide characters once. For tests and the on-device check.
    pub fn text(&mut self) -> Result<String, Failure> {
        self.view_text()
    }

    /// What the view shows (scrolled back or live) as text, whole graphemes:
    /// one line per row, trailing spaces and empty rows removed.
    pub fn view_text(&mut self) -> Result<String, Failure> {
        let mut lines = Vec::with_capacity(usize::from(self.rows));
        unsafe {
            check(
                "ghostty_render_state_update",
                ffi::ghostty_render_state_update(self.state, self.terminal),
            )?;
            self.load_rows()?;
            while ffi::ghostty_render_state_row_iterator_next(self.rows_iter) {
                self.load_cells()?;
                let mut line = String::new();
                while ffi::ghostty_render_state_row_cells_next(self.cells) {
                    let raw: ffi::Cell = self.cells_get("cell", ffi::CELLS_DATA_RAW)?;
                    let wide: i32 = cell_get(raw, "cell width", ffi::CELL_DATA_WIDE)?;
                    if wide == ffi::CELL_WIDE_SPACER_TAIL || wide == ffi::CELL_WIDE_SPACER_HEAD {
                        continue;
                    }
                    let grapheme = self.grapheme()?;
                    if grapheme.is_empty() {
                        line.push(' ');
                    }
                    line.extend(grapheme.iter().filter_map(|&c| char::from_u32(c)));
                }
                lines.push(line.trim_end().to_owned());
            }
        }
        while lines.last().is_some_and(String::is_empty) {
            lines.pop();
        }
        Ok(lines.join("\n"))
    }

    /// The current cell's codepoints, empty for no text.
    unsafe fn grapheme(&self) -> Result<Vec<u32>, Failure> {
        unsafe {
            let len: u32 = self.cells_get("grapheme length", ffi::CELLS_DATA_GRAPHEMES_LEN)?;
            let mut cps = vec![0u32; len as usize];
            if len > 0 {
                check(
                    "grapheme",
                    ffi::ghostty_render_state_row_cells_get(
                        self.cells,
                        ffi::CELLS_DATA_GRAPHEMES_BUF,
                        cps.as_mut_ptr().cast::<c_void>(),
                    ),
                )?;
            }
            Ok(cps)
        }
    }

    unsafe fn encode_row(&mut self, out: &mut Vec<u8>) -> Result<(), Failure> {
        unsafe {
            self.load_cells()?;
            let mut extras: Vec<u32> = Vec::new();
            let mut written: u16 = 0;
            while written < self.cols && ffi::ghostty_render_state_row_cells_next(self.cells) {
                let raw: ffi::Cell = self.cells_get("cell", ffi::CELLS_DATA_RAW)?;
                let wide: i32 = cell_get(raw, "cell width", ffi::CELL_DATA_WIDE)?;
                let spacer =
                    wide == ffi::CELL_WIDE_SPACER_TAIL || wide == ffi::CELL_WIDE_SPACER_HEAD;
                let grapheme = if spacer { Vec::new() } else { self.grapheme()? };
                let cp = grapheme.first().copied().unwrap_or(0);
                // At most 255 extra codepoints per cell; longer clusters are cut.
                let more = &grapheme[grapheme.len().min(1)..];
                let more = &more[..more.len().min(255)];
                let fg = self.cell_color(ffi::CELLS_DATA_FG_COLOR);
                let bg = self.cell_color(ffi::CELLS_DATA_BG_COLOR);
                let styled: bool = self.cells_get("has styling", ffi::CELLS_DATA_HAS_STYLING)?;
                let attrs = if styled {
                    let mut style = ffi::Style::sized();
                    check(
                        "row cells style",
                        ffi::ghostty_render_state_row_cells_get(
                            self.cells,
                            ffi::CELLS_DATA_STYLE,
                            (&raw mut style).cast::<c_void>(),
                        ),
                    )?;
                    attributes(&style)
                } else {
                    0
                };
                out.extend_from_slice(&cp.to_le_bytes());
                out.extend_from_slice(&fg.to_le_bytes());
                out.extend_from_slice(&bg.to_le_bytes());
                out.push(attrs);
                out.push(u8::try_from(wide).unwrap_or(0));
                out.push(more.len() as u8);
                out.push(0);
                extras.extend_from_slice(more);
                written += 1;
            }
            // A row is never shorter than the grid; pad defensively so offsets hold.
            for _ in written..self.cols {
                out.extend_from_slice(&[0; CELL_LEN]);
            }
            for c in extras {
                out.extend_from_slice(&c.to_le_bytes());
            }
        }
        Ok(())
    }

    /// `COLOR_SET | rgb` for a cell with its own colour, 0 for the default.
    unsafe fn cell_color(&self, data: i32) -> u32 {
        let mut rgb = ffi::ColorRgb::default();
        let code = unsafe {
            ffi::ghostty_render_state_row_cells_get(
                self.cells,
                data,
                (&raw mut rgb).cast::<c_void>(),
            )
        };
        if code == ffi::SUCCESS {
            COLOR_SET | rgb.word()
        } else {
            0
        }
    }

    unsafe fn load_rows(&mut self) -> Result<(), Failure> {
        unsafe {
            check(
                "render state row iterator",
                ffi::ghostty_render_state_get(
                    self.state,
                    ffi::DATA_ROW_ITERATOR,
                    (&raw mut self.rows_iter).cast::<c_void>(),
                ),
            )
        }
    }

    unsafe fn load_cells(&mut self) -> Result<(), Failure> {
        unsafe {
            check(
                "row cells",
                ffi::ghostty_render_state_row_get(
                    self.rows_iter,
                    ffi::ROW_DATA_CELLS,
                    (&raw mut self.cells).cast::<c_void>(),
                ),
            )
        }
    }

    unsafe fn state_get<T: Default>(&self, call: &'static str, data: i32) -> Result<T, Failure> {
        let mut value = T::default();
        let code = unsafe {
            ffi::ghostty_render_state_get(self.state, data, (&raw mut value).cast::<c_void>())
        };
        check(call, code).map(|()| value)
    }

    unsafe fn row_get<T: Default>(&self, call: &'static str, data: i32) -> Result<T, Failure> {
        let mut value = T::default();
        let code = unsafe {
            ffi::ghostty_render_state_row_get(
                self.rows_iter,
                data,
                (&raw mut value).cast::<c_void>(),
            )
        };
        check(call, code).map(|()| value)
    }

    unsafe fn cells_get<T: Default>(&self, call: &'static str, data: i32) -> Result<T, Failure> {
        let mut value = T::default();
        let code = unsafe {
            ffi::ghostty_render_state_row_cells_get(
                self.cells,
                data,
                (&raw mut value).cast::<c_void>(),
            )
        };
        check(call, code).map(|()| value)
    }
}

unsafe fn cell_get<T: Default>(
    cell: ffi::Cell,
    call: &'static str,
    data: i32,
) -> Result<T, Failure> {
    let mut value = T::default();
    let code = unsafe { ffi::ghostty_cell_get(cell, data, (&raw mut value).cast::<c_void>()) };
    check(call, code).map(|()| value)
}

fn attributes(style: &ffi::Style) -> u8 {
    let mut a = 0;
    for (on, bit) in [
        (style.bold, ATTR_BOLD),
        (style.italic, ATTR_ITALIC),
        (style.faint, ATTR_FAINT),
        (style.underline != 0, ATTR_UNDERLINE),
        (style.inverse, ATTR_INVERSE),
        (style.strikethrough, ATTR_STRIKETHROUGH),
        (style.invisible, ATTR_INVISIBLE),
    ] {
        if on {
            a |= bit;
        }
    }
    a
}

impl Drop for Terminal {
    fn drop(&mut self) {
        unsafe {
            if !self.key_event.is_null() {
                ffi::ghostty_key_event_free(self.key_event);
            }
            if !self.keys.is_null() {
                ffi::ghostty_key_encoder_free(self.keys);
            }
            if !self.cells.is_null() {
                ffi::ghostty_render_state_row_cells_free(self.cells);
            }
            if !self.rows_iter.is_null() {
                ffi::ghostty_render_state_row_iterator_free(self.rows_iter);
            }
            if !self.state.is_null() {
                ffi::ghostty_render_state_free(self.state);
            }
            if !self.terminal.is_null() {
                ffi::ghostty_terminal_free(self.terminal);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::synthetic;

    pub(super) struct Parsed {
        pub cols: u16,
        pub rows: u16,
        pub cursor: (u16, u16),
        pub flags: u8,
        pub fg: u32,
        pub bg: u32,
        pub shape: u8,
        pub total: u32,
        pub offset: u32,
        pub rows_sent: Vec<(u16, Vec<[u8; CELL_LEN]>)>,
        /// Each sent row's cells as text: the whole grapheme, "" for none.
        pub texts: Vec<Vec<String>>,
    }

    fn u16_at(b: &[u8], i: usize) -> u16 {
        u16::from_le_bytes([b[i], b[i + 1]])
    }
    fn u32_at(b: &[u8], i: usize) -> u32 {
        u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]])
    }

    pub(super) fn parse(b: &[u8]) -> Parsed {
        let cols = usize::from(u16_at(b, 0));
        let count = usize::from(u16_at(b, 10));
        assert_eq!(&b[28..32], &[0; 4], "reserved header bytes");
        let mut at = HEADER_LEN;
        let mut rows_sent = Vec::new();
        let mut texts = Vec::new();
        for _ in 0..count {
            let y = u16_at(b, at);
            at += 2;
            let cells: Vec<[u8; CELL_LEN]> = (0..cols)
                .map(|c| {
                    b[at + c * CELL_LEN..at + (c + 1) * CELL_LEN]
                        .try_into()
                        .unwrap()
                })
                .collect();
            at += cols * CELL_LEN;
            let mut text = Vec::new();
            for cell in &cells {
                assert_eq!(cell[15], 0, "reserved cell byte");
                let mut t = String::new();
                if let Some(c) = char::from_u32(u32_at(cell, 0)).filter(|c| *c != '\0') {
                    t.push(c);
                }
                for _ in 0..cell[14] {
                    t.push(char::from_u32(u32_at(b, at)).unwrap());
                    at += 4;
                }
                text.push(t);
            }
            rows_sent.push((y, cells));
            texts.push(text);
        }
        assert_eq!(b.len(), at, "frame length");
        Parsed {
            cols: cols as u16,
            rows: u16_at(b, 2),
            cursor: (u16_at(b, 4), u16_at(b, 6)),
            flags: b[8],
            shape: b[9],
            fg: u32_at(b, 12),
            bg: u32_at(b, 16),
            total: u32_at(b, 20),
            offset: u32_at(b, 24),
            rows_sent,
            texts,
        }
    }

    fn cp(cell: &[u8; CELL_LEN]) -> u32 {
        u32_at(cell, 0)
    }

    #[test]
    fn layout_matches_type_json() {
        let json = unsafe { std::ffi::CStr::from_ptr(ffi::ghostty_type_json()) };
        let v: serde_json::Value = serde_json::from_slice(json.to_bytes()).unwrap();
        let types = &v["types"];
        let size = |t: &str| types[t]["size"].as_u64().unwrap() as usize;
        let offset = |t: &str, f: &str| types[t]["fields"][f]["offset"].as_u64().unwrap() as usize;

        assert_eq!(size("GhosttyColorRgb"), size_of::<ffi::ColorRgb>());
        assert_eq!(size("GhosttyStyleColor"), size_of::<ffi::StyleColor>());
        assert_eq!(
            offset("GhosttyStyleColor", "value"),
            std::mem::offset_of!(ffi::StyleColor, value)
        );
        assert_eq!(size("GhosttyStyle"), size_of::<ffi::Style>());
        for (field, ours) in [
            ("fg_color", std::mem::offset_of!(ffi::Style, fg_color)),
            ("bg_color", std::mem::offset_of!(ffi::Style, bg_color)),
            ("bold", std::mem::offset_of!(ffi::Style, bold)),
            ("italic", std::mem::offset_of!(ffi::Style, italic)),
            ("faint", std::mem::offset_of!(ffi::Style, faint)),
            ("inverse", std::mem::offset_of!(ffi::Style, inverse)),
            ("invisible", std::mem::offset_of!(ffi::Style, invisible)),
            (
                "strikethrough",
                std::mem::offset_of!(ffi::Style, strikethrough),
            ),
            ("underline", std::mem::offset_of!(ffi::Style, underline)),
        ] {
            assert_eq!(offset("GhosttyStyle", field), ours, "GhosttyStyle.{field}");
        }
        let enum_value = |t: &str, k: &str| types[t]["values"][k].as_i64().unwrap() as i32;
        assert_eq!(
            enum_value("GhosttyRenderStateData", "DIRTY"),
            ffi::DATA_DIRTY
        );
        assert_eq!(
            enum_value("GhosttyRenderStateData", "ROW_ITERATOR"),
            ffi::DATA_ROW_ITERATOR
        );
        assert_eq!(
            enum_value("GhosttyRenderStateRowData", "VIEWPORT_Y"),
            ffi::ROW_DATA_VIEWPORT_Y
        );
        assert_eq!(
            enum_value("GhosttyRenderStateRowCellsData", "HAS_STYLING"),
            ffi::CELLS_DATA_HAS_STYLING
        );
        assert_eq!(enum_value("GhosttyCellData", "WIDE"), ffi::CELL_DATA_WIDE);
        // Scrolling a full-screen program (mouse.rs).
        assert_eq!(
            size("GhosttyMouseEncoderSize"),
            size_of::<ffi::MouseEncoderSize>()
        );
        assert_eq!(
            size("GhosttyMousePosition"),
            size_of::<ffi::MousePosition>()
        );
        for (t, k, ours) in [
            (
                "GhosttyTerminalData",
                "ACTIVE_SCREEN",
                ffi::TERMINAL_DATA_ACTIVE_SCREEN,
            ),
            (
                "GhosttyTerminalData",
                "MOUSE_TRACKING",
                ffi::TERMINAL_DATA_MOUSE_TRACKING,
            ),
            ("GhosttyTerminalScreen", "ALTERNATE", ffi::SCREEN_ALTERNATE),
            (
                "GhosttyMouseEncoderOption",
                "SIZE",
                ffi::MOUSE_ENCODER_OPT_SIZE,
            ),
            ("GhosttyMouseAction", "PRESS", ffi::MOUSE_ACTION_PRESS),
            ("GhosttyMouseAction", "RELEASE", ffi::MOUSE_ACTION_RELEASE),
            ("GhosttyMouseButton", "LEFT", ffi::MOUSE_BUTTON_LEFT),
            ("GhosttyMouseButton", "FOUR", ffi::MOUSE_BUTTON_FOUR),
            ("GhosttyMouseButton", "FIVE", ffi::MOUSE_BUTTON_FIVE),
        ] {
            assert_eq!(enum_value(t, k), ours, "{t}.{k}");
        }
        assert_eq!(enum_value("GhosttyCellWide", "WIDE"), ffi::CELL_WIDE_WIDE);
        assert_eq!(
            enum_value("GhosttyCellWide", "SPACER_HEAD"),
            ffi::CELL_WIDE_SPACER_HEAD
        );
        for (k, ours) in [
            ("CURSOR_VISUAL_STYLE", ffi::DATA_CURSOR_VISUAL_STYLE),
            (
                "CURSOR_VIEWPORT_WIDE_TAIL",
                ffi::DATA_CURSOR_VIEWPORT_WIDE_TAIL,
            ),
        ] {
            assert_eq!(enum_value("GhosttyRenderStateData", k), ours, "{k}");
        }
        for (k, ours) in [
            ("BAR", ffi::CURSOR_BAR),
            ("UNDERLINE", ffi::CURSOR_UNDERLINE),
            ("BLOCK_HOLLOW", ffi::CURSOR_BLOCK_HOLLOW),
        ] {
            assert_eq!(
                enum_value("GhosttyRenderStateCursorVisualStyle", k),
                ours,
                "{k}"
            );
        }
        for (k, ours) in [
            ("GRAPHEMES_LEN", ffi::CELLS_DATA_GRAPHEMES_LEN),
            ("GRAPHEMES_BUF", ffi::CELLS_DATA_GRAPHEMES_BUF),
        ] {
            assert_eq!(enum_value("GhosttyRenderStateRowCellsData", k), ours, "{k}");
        }
        assert_eq!(
            enum_value("GhosttyKeyAction", "PRESS"),
            ffi::KEY_ACTION_PRESS
        );
        for (k, ours) in [
            ("UNIDENTIFIED", ffi::KEY_UNIDENTIFIED),
            ("BACKQUOTE", ffi::KEY_BACKQUOTE),
            ("BACKSLASH", ffi::KEY_BACKSLASH),
            ("BRACKET_LEFT", ffi::KEY_BRACKET_LEFT),
            ("BRACKET_RIGHT", ffi::KEY_BRACKET_RIGHT),
            ("COMMA", ffi::KEY_COMMA),
            ("DIGIT_0", ffi::KEY_DIGIT_0),
            ("DIGIT_9", ffi::KEY_DIGIT_0 + 9),
            ("EQUAL", ffi::KEY_EQUAL),
            ("A", ffi::KEY_A),
            ("Z", ffi::KEY_A + 25),
            ("MINUS", ffi::KEY_MINUS),
            ("PERIOD", ffi::KEY_PERIOD),
            ("QUOTE", ffi::KEY_QUOTE),
            ("SEMICOLON", ffi::KEY_SEMICOLON),
            ("SLASH", ffi::KEY_SLASH),
            ("BACKSPACE", ffi::KEY_BACKSPACE),
            ("ENTER", ffi::KEY_ENTER),
            ("SPACE", ffi::KEY_SPACE),
            ("TAB", ffi::KEY_TAB),
            ("ARROW_DOWN", ffi::KEY_ARROW_DOWN),
            ("ARROW_LEFT", ffi::KEY_ARROW_LEFT),
            ("ARROW_RIGHT", ffi::KEY_ARROW_RIGHT),
            ("ARROW_UP", ffi::KEY_ARROW_UP),
            ("ESCAPE", ffi::KEY_ESCAPE),
        ] {
            assert_eq!(enum_value("GhosttyKey", k), ours, "GhosttyKey {k}");
        }
        assert_eq!(
            enum_value("GhosttyCellWide", "SPACER_TAIL"),
            ffi::CELL_WIDE_SPACER_TAIL
        );
        assert_eq!(
            size("GhosttyTerminalScrollbar"),
            size_of::<ffi::Scrollbar>()
        );
        assert_eq!(
            size("GhosttyTerminalScrollViewport"),
            size_of::<ffi::ScrollViewport>()
        );
        assert_eq!(
            offset("GhosttyTerminalScrollViewport", "value"),
            std::mem::offset_of!(ffi::ScrollViewport, value)
        );
        for (k, ours) in [("BOTTOM", ffi::SCROLL_BOTTOM), ("DELTA", ffi::SCROLL_DELTA)] {
            assert_eq!(
                enum_value("GhosttyTerminalScrollViewportTag", k),
                ours,
                "{k}"
            );
        }
        for (k, ours) in [
            ("SCROLLBAR", ffi::TERMINAL_DATA_SCROLLBAR),
            ("VIEWPORT_ACTIVE", ffi::TERMINAL_DATA_VIEWPORT_ACTIVE),
        ] {
            assert_eq!(enum_value("GhosttyTerminalData", k), ours, "{k}");
        }
    }

    #[test]
    fn zero_size_is_refused() {
        assert!(Terminal::new(0, 10).is_err());
        assert!(Terminal::new(10, 0).is_err());
    }

    #[test]
    fn text_shows_the_written_lines() {
        let mut t = Terminal::new(20, 4).unwrap();
        t.write(b"hello\r\nworld");
        assert_eq!(t.text().unwrap(), "hello\nworld");
    }

    #[test]
    fn first_frame_has_every_row_and_the_cursor() {
        let mut t = Terminal::new(10, 3).unwrap();
        t.write(b"hi");
        let f = parse(&t.frame().unwrap());
        assert_eq!((f.cols, f.rows), (10, 3));
        assert_eq!(f.flags & FLAG_FULL, FLAG_FULL);
        assert_eq!(f.flags & FLAG_CURSOR, FLAG_CURSOR);
        assert_eq!(f.cursor, (2, 0));
        assert_eq!(
            f.rows_sent.iter().map(|r| r.0).collect::<Vec<_>>(),
            [0, 1, 2]
        );
        assert_eq!(cp(&f.rows_sent[0].1[0]), u32::from('h'));
        assert_eq!(cp(&f.rows_sent[0].1[2]), 0);
        assert_eq!(f.fg & COLOR_SET, 0, "defaults carry no flag");
        assert_ne!(f.fg, f.bg);
    }

    #[test]
    fn styles_and_colours_reach_the_cells() {
        let mut t = Terminal::new(10, 1).unwrap();
        t.write(b"\x1b[1;31mR\x1b[0m\x1b[38;2;1;2;3mT\x1b[0m\x1b[7mI\x1b[0m\x1b[3;4mU\x1b[0m\x1b[48;2;9;8;7mB");
        let f = parse(&t.frame().unwrap());
        let row = &f.rows_sent[0].1;
        assert_eq!(row[0][12] & ATTR_BOLD, ATTR_BOLD);
        assert_eq!(
            u32_at(&row[0], 4) & COLOR_SET,
            COLOR_SET,
            "palette red resolves to RGB"
        );
        assert_eq!(u32_at(&row[1], 4), COLOR_SET | 0x01_02_03);
        assert_eq!(row[1][12], 0);
        assert_eq!(row[2][12] & ATTR_INVERSE, ATTR_INVERSE);
        assert_eq!(
            row[3][12] & (ATTR_ITALIC | ATTR_UNDERLINE),
            ATTR_ITALIC | ATTR_UNDERLINE
        );
        assert_eq!(u32_at(&row[4], 8), COLOR_SET | 0x09_08_07);
        assert_eq!(u32_at(&row[5], 8), 0, "background ends with the text");
    }

    #[test]
    fn wide_characters_take_two_cells() {
        let mut t = Terminal::new(10, 1).unwrap();
        t.write("日x".as_bytes());
        let f = parse(&t.frame().unwrap());
        let row = &f.rows_sent[0].1;
        assert_eq!((cp(&row[0]), row[0][13]), (u32::from('日'), 1));
        assert_eq!(row[1][13], 2);
        assert_eq!(cp(&row[2]), u32::from('x'));
        assert_eq!(t.text().unwrap(), "日x");
    }

    #[test]
    fn later_frames_carry_only_changed_rows() {
        let mut t = Terminal::new(10, 3).unwrap();
        t.frame().unwrap();
        t.write(b"\x1b[3;1Hz");
        let f = parse(&t.frame().unwrap());
        assert_eq!(f.flags & FLAG_FULL, 0);
        let ys: Vec<u16> = f.rows_sent.iter().map(|r| r.0).collect();
        assert!(ys.contains(&2), "row 2 changed: {ys:?}");
        assert!(ys.len() < 3, "not every row: {ys:?}");
        assert_eq!(f.cursor, (1, 2));

        let f = parse(&t.frame().unwrap());
        assert!(f.rows_sent.is_empty(), "nothing changed");
    }

    #[test]
    fn any_split_of_the_stream_gives_the_same_screen() {
        let stream = synthetic::session(80, 64 * 1024);
        let mut whole = Terminal::new(80, 24).unwrap();
        whole.write(&stream);
        let expected = whole.text().unwrap();
        assert!(
            expected.lines().count() > 10,
            "the session fills the screen"
        );
        for size in [1, 7, 4096] {
            let mut t = Terminal::new(80, 24).unwrap();
            for chunk in stream.chunks(size) {
                t.write(chunk);
            }
            assert_eq!(t.text().unwrap(), expected, "chunks of {size}");
        }
    }

    #[test]
    fn a_long_session_keeps_producing_valid_frames() {
        let stream = synthetic::session(200, 512 * 1024);
        let mut t = Terminal::new(200, 60).unwrap();
        let mut rows_sent = 0;
        for chunk in stream.chunks(4096) {
            t.write(chunk);
            let f = parse(&t.frame().unwrap());
            assert_eq!((f.cols, f.rows), (200, 60));
            assert!(f.rows_sent.iter().all(|r| r.0 < 60));
            rows_sent += f.rows_sent.len();
        }
        assert!(rows_sent > 60, "output keeps changing rows");
    }

    #[test]
    fn graphemes_reach_the_frame_whole() {
        let mut t = Terminal::new(10, 1).unwrap();
        // Grapheme clustering on (mode 2027), as programs that want it ask:
        // e + combining acute, a ZWJ sequence (wide), a flag, then plain text
        t.write(b"\x1b[?2027h");
        t.write("e\u{301}\u{1f469}\u{200d}\u{1f4bb}\u{1f1f3}\u{1f1f1}x".as_bytes());
        let f = parse(&t.frame().unwrap());
        let text = &f.texts[0];
        assert_eq!(text[0], "e\u{301}");
        assert_eq!(text[1], "\u{1f469}\u{200d}\u{1f4bb}");
        assert_eq!(f.rows_sent[0].1[1][13], 1, "the sequence is wide");
        assert_eq!(text[2], "", "its spacer has no text");
        let flag = text
            .iter()
            .position(|c| c.starts_with('\u{1f1f3}'))
            .unwrap();
        assert_eq!(text[flag], "\u{1f1f3}\u{1f1f1}");
        assert!(text.contains(&"x".to_owned()));
        assert_eq!(
            cp(&f.rows_sent[0].1[0]),
            u32::from('e'),
            "base codepoint stays first"
        );
    }

    #[test]
    fn the_header_carries_the_cursor_shape() {
        let mut t = Terminal::new(10, 2).unwrap();
        t.write(b"\x1b[6 q");
        assert_eq!(parse(&t.frame().unwrap()).shape, SHAPE_BAR);
        t.write(b"\x1b[4 q");
        assert_eq!(parse(&t.frame().unwrap()).shape, SHAPE_UNDERLINE);
        t.write(b"\x1b[2 q");
        assert_eq!(parse(&t.frame().unwrap()).shape, SHAPE_BLOCK);
    }

    fn numbered(n: usize) -> Vec<u8> {
        (0..n)
            .map(|i| format!("line {i}\r\n"))
            .collect::<String>()
            .into_bytes()
    }

    #[test]
    fn scrolling_back_holds_the_view_until_live() {
        let mut t = Terminal::new(20, 5).unwrap();
        t.write(&numbered(50));
        let f = parse(&t.frame().unwrap());
        assert_eq!(f.flags & FLAG_LIVE, FLAG_LIVE);
        assert!(f.total >= 50, "scrollback is counted: {}", f.total);
        assert_eq!(f.offset + 5, f.total, "at the bottom");

        t.scroll(-10);
        let f = parse(&t.frame().unwrap());
        assert_eq!(f.flags & FLAG_LIVE, 0, "scrolled up");
        assert_eq!(
            f.flags & FLAG_FULL,
            FLAG_FULL,
            "a scrolled view sends every row"
        );
        assert_eq!(f.offset + 15, f.total);
        let top = f.texts[0].concat();
        assert!(top.starts_with("line "), "{top}");
        assert_eq!(f.flags & FLAG_CURSOR, 0, "the cursor is below the view");

        // new output does not move a scrolled view
        t.write(&numbered(3));
        let g = parse(&t.frame().unwrap());
        assert_eq!(g.flags & FLAG_LIVE, 0);
        let still: Vec<String> = t.view_text().unwrap().lines().map(str::to_owned).collect();
        assert_eq!(still[0], top.trim_end());

        t.scroll_to_live();
        let h = parse(&t.frame().unwrap());
        assert_eq!(h.flags & FLAG_LIVE, FLAG_LIVE);
        assert_eq!(h.offset + 5, h.total);
        assert!(t.view_text().unwrap().ends_with("line 2"));
    }

    #[test]
    fn scrolling_past_either_end_is_clamped() {
        let mut t = Terminal::new(20, 5).unwrap();
        t.write(&numbered(8));
        t.scroll(-1000);
        let f = parse(&t.frame().unwrap());
        assert_eq!(f.offset, 0);
        assert_eq!(t.view_text().unwrap().lines().next(), Some("line 0"));
        t.scroll(1000);
        assert_eq!(parse(&t.frame().unwrap()).flags & FLAG_LIVE, FLAG_LIVE);
    }

    #[test]
    fn resizing_changes_the_frame_size_and_resends_every_row() {
        let mut t = Terminal::new(40, 10).unwrap();
        t.write(b"hello");
        t.frame().unwrap();
        t.resize(30, 6).unwrap();
        assert_eq!(t.size(), (30, 6));
        let f = parse(&t.frame().unwrap());
        assert_eq!((f.cols, f.rows), (30, 6));
        assert_eq!(f.flags & FLAG_FULL, FLAG_FULL);
        assert_eq!(f.rows_sent.len(), 6);
        assert!(t.resize(0, 6).is_err());
    }

    #[test]
    fn view_text_reads_graphemes_and_skips_spacers() {
        let mut t = Terminal::new(10, 2).unwrap();
        t.write("e\u{301}\u{65e5}x\r\n  ".as_bytes());
        assert_eq!(t.view_text().unwrap(), "e\u{301}\u{65e5}x");
    }
}
