//! libghostty-vt behind a small safe wrapper, and the frame format Kotlin draws
//! from (task M0.5, the device smoke test).
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
//! | 8 | flags: bit 0 full frame (every row follows), bit 1 cursor shown |
//! | 9 | reserved, 0 |
//! | 10..12 | number of rows that follow (u16) |
//! | 12..16 | default foreground, `0x00RRGGBB` |
//! | 16..20 | default background, `0x00RRGGBB` |
//!
//! Then each changed row: its y (u16) and `cols` cells of [`CELL_LEN`] bytes:
//!
//! | bytes | field |
//! |---|---|
//! | 0..4 | codepoint (u32), 0 for no text; only the base codepoint of a grapheme |
//! | 4..8 | foreground: `0x01RRGGBB` when the cell has its own colour, else 0 |
//! | 8..12 | background: the same |
//! | 12 | attributes: see the `ATTR_*` constants |
//! | 13 | width: 0 narrow, 1 wide, 2 spacer after a wide cell, 3 spacer at a line end |
//! | 14..16 | reserved, 0 |

mod ffi;

use std::ffi::c_void;
use std::fmt;
use std::ptr;

pub const HEADER_LEN: usize = 20;
pub const CELL_LEN: usize = 16;
pub const FLAG_FULL: u8 = 1;
pub const FLAG_CURSOR: u8 = 2;
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
    cols: u16,
    rows: u16,
    sent_first_frame: bool,
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
            cols,
            rows,
            sent_first_frame: false,
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
        }
        Ok(t)
    }

    /// Feeds program output to the terminal. Any split of the stream is fine.
    pub fn write(&mut self, bytes: &[u8]) {
        unsafe { ffi::ghostty_terminal_vt_write(self.terminal, bytes.as_ptr(), bytes.len()) }
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
            let full = !self.sent_first_frame || dirty == ffi::DIRTY_FULL;
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
            out.push(flags);
            out.push(0);
            out.extend_from_slice(&0u16.to_le_bytes()); // row count, set below
            out.extend_from_slice(&fg.word().to_le_bytes());
            out.extend_from_slice(&bg.word().to_le_bytes());

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
            Ok(out)
        }
    }

    /// The screen as text: one line per row, trailing spaces and empty rows
    /// removed, wide characters once. For tests and the on-device check.
    pub fn text(&mut self) -> Result<String, Failure> {
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
                    if wide == ffi::CELL_WIDE_SPACER_TAIL {
                        continue;
                    }
                    let cp: u32 = cell_get(raw, "codepoint", ffi::CELL_DATA_CODEPOINT)?;
                    line.push(char::from_u32(cp).filter(|_| cp != 0).unwrap_or(' '));
                }
                lines.push(line.trim_end().to_owned());
            }
        }
        while lines.last().is_some_and(String::is_empty) {
            lines.pop();
        }
        Ok(lines.join("\n"))
    }

    unsafe fn encode_row(&mut self, out: &mut Vec<u8>) -> Result<(), Failure> {
        unsafe {
            self.load_cells()?;
            let mut written: u16 = 0;
            while written < self.cols && ffi::ghostty_render_state_row_cells_next(self.cells) {
                let raw: ffi::Cell = self.cells_get("cell", ffi::CELLS_DATA_RAW)?;
                let cp: u32 = cell_get(raw, "codepoint", ffi::CELL_DATA_CODEPOINT)?;
                let wide: i32 = cell_get(raw, "cell width", ffi::CELL_DATA_WIDE)?;
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
                out.extend_from_slice(&[0, 0]);
                written += 1;
            }
            // A row is never shorter than the grid; pad defensively so offsets hold.
            for _ in written..self.cols {
                out.extend_from_slice(&[0; CELL_LEN]);
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

    struct Parsed {
        cols: u16,
        rows: u16,
        cursor: (u16, u16),
        flags: u8,
        fg: u32,
        bg: u32,
        rows_sent: Vec<(u16, Vec<[u8; CELL_LEN]>)>,
    }

    fn u16_at(b: &[u8], i: usize) -> u16 {
        u16::from_le_bytes([b[i], b[i + 1]])
    }
    fn u32_at(b: &[u8], i: usize) -> u32 {
        u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]])
    }

    fn parse(b: &[u8]) -> Parsed {
        let cols = u16_at(b, 0);
        let count = usize::from(u16_at(b, 10));
        let row_len = 2 + usize::from(cols) * CELL_LEN;
        assert_eq!(b.len(), HEADER_LEN + count * row_len, "frame length");
        let rows_sent = (0..count)
            .map(|r| {
                let at = HEADER_LEN + r * row_len;
                let cells = (0..usize::from(cols))
                    .map(|c| {
                        b[at + 2 + c * CELL_LEN..at + 2 + (c + 1) * CELL_LEN]
                            .try_into()
                            .unwrap()
                    })
                    .collect();
                (u16_at(b, at), cells)
            })
            .collect();
        Parsed {
            cols,
            rows: u16_at(b, 2),
            cursor: (u16_at(b, 4), u16_at(b, 6)),
            flags: b[8],
            fg: u32_at(b, 12),
            bg: u32_at(b, 16),
            rows_sent,
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
        assert_eq!(
            enum_value("GhosttyCellWide", "SPACER_TAIL"),
            ffi::CELL_WIDE_SPACER_TAIL
        );
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
}
