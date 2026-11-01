//! The part of the libghostty-vt C API (Ghostty `1e5cdb8`, `include/ghostty/vt/`)
//! that the frame path uses, written by hand from the headers. Enums are C `int`.
//! `tests::layout_matches_type_json` checks the structs against the library's own
//! layout manifest.

use std::ffi::{c_char, c_int, c_void};

pub type GhosttyResult = c_int;
pub const SUCCESS: GhosttyResult = 0;

#[repr(C)]
pub struct TerminalImpl {
    _private: [u8; 0],
}
#[repr(C)]
pub struct RenderStateImpl {
    _private: [u8; 0],
}
#[repr(C)]
pub struct RowIteratorImpl {
    _private: [u8; 0],
}
#[repr(C)]
pub struct RowCellsImpl {
    _private: [u8; 0],
}
pub type Terminal = *mut TerminalImpl;
pub type RenderState = *mut RenderStateImpl;
pub type RowIterator = *mut RowIteratorImpl;
pub type RowCells = *mut RowCellsImpl;
pub type Cell = u64;

#[repr(C)]
#[derive(Clone, Copy, Default, Debug, PartialEq, Eq)]
pub struct ColorRgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl ColorRgb {
    pub fn word(self) -> u32 {
        (u32::from(self.r) << 16) | (u32::from(self.g) << 8) | u32::from(self.b)
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub union StyleColorValue {
    pub palette: u8,
    pub rgb: ColorRgb,
    pub padding: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct StyleColor {
    pub tag: c_int,
    pub value: StyleColorValue,
}

/// `GhosttyStyle`, a sized struct: `size` must hold `size_of::<Style>()`.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct Style {
    pub size: usize,
    pub fg_color: StyleColor,
    pub bg_color: StyleColor,
    pub underline_color: StyleColor,
    pub bold: bool,
    pub italic: bool,
    pub faint: bool,
    pub blink: bool,
    pub inverse: bool,
    pub invisible: bool,
    pub strikethrough: bool,
    pub overline: bool,
    pub underline: c_int,
}

impl Style {
    pub fn sized() -> Self {
        let none = StyleColor {
            tag: 0,
            value: StyleColorValue { padding: 0 },
        };
        Style {
            size: size_of::<Style>(),
            fg_color: none,
            bg_color: none,
            underline_color: none,
            bold: false,
            italic: false,
            faint: false,
            blink: false,
            inverse: false,
            invisible: false,
            strikethrough: false,
            overline: false,
            underline: 0,
        }
    }
}

// GhosttyRenderStateDirty
pub const DIRTY_FALSE: c_int = 0;
pub const DIRTY_FULL: c_int = 2;

// GhosttyRenderStateData
pub const DATA_DIRTY: c_int = 3;
pub const DATA_ROW_ITERATOR: c_int = 4;
pub const DATA_COLOR_BACKGROUND: c_int = 5;
pub const DATA_COLOR_FOREGROUND: c_int = 6;
pub const DATA_CURSOR_VISIBLE: c_int = 11;
pub const DATA_CURSOR_VIEWPORT_HAS_VALUE: c_int = 14;
pub const DATA_CURSOR_VIEWPORT_X: c_int = 15;
pub const DATA_CURSOR_VIEWPORT_Y: c_int = 16;

// GhosttyRenderStateRowData
pub const ROW_DATA_DIRTY: c_int = 1;
pub const ROW_DATA_CELLS: c_int = 3;
pub const ROW_DATA_VIEWPORT_Y: c_int = 6;

// GhosttyRenderStateRowCellsData
pub const CELLS_DATA_RAW: c_int = 1;
pub const CELLS_DATA_STYLE: c_int = 2;
pub const CELLS_DATA_BG_COLOR: c_int = 5;
pub const CELLS_DATA_FG_COLOR: c_int = 6;
pub const CELLS_DATA_HAS_STYLING: c_int = 8;

// GhosttyCellData
pub const CELL_DATA_CODEPOINT: c_int = 1;
pub const CELL_DATA_WIDE: c_int = 3;

// GhosttyCellWide
pub const CELL_WIDE_SPACER_TAIL: c_int = 2;

unsafe extern "C" {
    pub fn ghostty_terminal_new(
        allocator: *const c_void,
        terminal: *mut Terminal,
        cols: u16,
        rows: u16,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_free(terminal: Terminal);
    pub fn ghostty_terminal_vt_write(terminal: Terminal, data: *const u8, len: usize);

    pub fn ghostty_render_state_new(
        allocator: *const c_void,
        state: *mut RenderState,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_free(state: RenderState);
    pub fn ghostty_render_state_update(state: RenderState, terminal: Terminal) -> GhosttyResult;
    pub fn ghostty_render_state_clean(state: RenderState) -> GhosttyResult;
    pub fn ghostty_render_state_get(
        state: RenderState,
        data: c_int,
        out: *mut c_void,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_iterator_new(
        allocator: *const c_void,
        iterator: *mut RowIterator,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_iterator_free(iterator: RowIterator);
    pub fn ghostty_render_state_row_iterator_next(iterator: RowIterator) -> bool;
    pub fn ghostty_render_state_row_get(
        iterator: RowIterator,
        data: c_int,
        out: *mut c_void,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_cells_new(
        allocator: *const c_void,
        cells: *mut RowCells,
    ) -> GhosttyResult;
    pub fn ghostty_render_state_row_cells_free(cells: RowCells);
    pub fn ghostty_render_state_row_cells_next(cells: RowCells) -> bool;
    pub fn ghostty_render_state_row_cells_get(
        cells: RowCells,
        data: c_int,
        out: *mut c_void,
    ) -> GhosttyResult;

    pub fn ghostty_cell_get(cell: Cell, data: c_int, out: *mut c_void) -> GhosttyResult;

    #[allow(dead_code, reason = "used by the layout test")]
    pub fn ghostty_type_json() -> *const c_char;
}
