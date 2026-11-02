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
pub struct KeyEncoderImpl {
    _private: [u8; 0],
}
#[repr(C)]
pub struct KeyEventImpl {
    _private: [u8; 0],
}
pub type KeyEncoder = *mut KeyEncoderImpl;
pub type KeyEvent = *mut KeyEventImpl;

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
pub const DATA_CURSOR_VISUAL_STYLE: c_int = 10;
pub const DATA_CURSOR_VISIBLE: c_int = 11;
pub const DATA_CURSOR_VIEWPORT_HAS_VALUE: c_int = 14;
pub const DATA_CURSOR_VIEWPORT_X: c_int = 15;
pub const DATA_CURSOR_VIEWPORT_Y: c_int = 16;
pub const DATA_CURSOR_VIEWPORT_WIDE_TAIL: c_int = 17;

// GhosttyRenderStateCursorVisualStyle
pub const CURSOR_BAR: c_int = 0;
pub const CURSOR_UNDERLINE: c_int = 2;
pub const CURSOR_BLOCK_HOLLOW: c_int = 3;

// GhosttyRenderStateRowData
pub const ROW_DATA_DIRTY: c_int = 1;
pub const ROW_DATA_CELLS: c_int = 3;
pub const ROW_DATA_VIEWPORT_Y: c_int = 6;

// GhosttyRenderStateRowCellsData
pub const CELLS_DATA_RAW: c_int = 1;
pub const CELLS_DATA_STYLE: c_int = 2;
pub const CELLS_DATA_GRAPHEMES_LEN: c_int = 3;
pub const CELLS_DATA_GRAPHEMES_BUF: c_int = 4;
pub const CELLS_DATA_BG_COLOR: c_int = 5;
pub const CELLS_DATA_FG_COLOR: c_int = 6;
pub const CELLS_DATA_HAS_STYLING: c_int = 8;

// GhosttyCellData
pub const CELL_DATA_CODEPOINT: c_int = 1;
pub const CELL_DATA_WIDE: c_int = 3;

// GhosttyCellWide
pub const CELL_WIDE_WIDE: c_int = 1;
pub const CELL_WIDE_SPACER_TAIL: c_int = 2;
pub const CELL_WIDE_SPACER_HEAD: c_int = 3;

// GhosttyKeyAction
pub const KEY_ACTION_PRESS: c_int = 1;

// GhosttyMods
pub const MODS_SHIFT: u16 = 1;
pub const MODS_CTRL: u16 = 1 << 1;
pub const MODS_ALT: u16 = 1 << 2;

// GhosttyKey: the keys the phone sends (checked against the type manifest).
pub const KEY_UNIDENTIFIED: c_int = 0;
pub const KEY_BACKQUOTE: c_int = 1;
pub const KEY_BACKSLASH: c_int = 2;
pub const KEY_BRACKET_LEFT: c_int = 3;
pub const KEY_BRACKET_RIGHT: c_int = 4;
pub const KEY_COMMA: c_int = 5;
pub const KEY_DIGIT_0: c_int = 6;
pub const KEY_EQUAL: c_int = 16;
pub const KEY_A: c_int = 20;
pub const KEY_MINUS: c_int = 46;
pub const KEY_PERIOD: c_int = 47;
pub const KEY_QUOTE: c_int = 48;
pub const KEY_SEMICOLON: c_int = 49;
pub const KEY_SLASH: c_int = 50;
pub const KEY_BACKSPACE: c_int = 53;
pub const KEY_ENTER: c_int = 58;
pub const KEY_SPACE: c_int = 63;
pub const KEY_TAB: c_int = 64;
pub const KEY_ARROW_DOWN: c_int = 75;
pub const KEY_ARROW_LEFT: c_int = 76;
pub const KEY_ARROW_RIGHT: c_int = 77;
pub const KEY_ARROW_UP: c_int = 78;
pub const KEY_ESCAPE: c_int = 120;

/// `GhosttyTerminalScrollViewport`: a tag and a 16-byte union.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct ScrollViewport {
    pub tag: c_int,
    pub value: [u64; 2],
}

// GhosttyTerminalScrollViewportTag
pub const SCROLL_BOTTOM: c_int = 1;
pub const SCROLL_DELTA: c_int = 2;

/// `GhosttyTerminalScrollbar`, in rows.
#[repr(C)]
#[derive(Clone, Copy, Default, Debug, PartialEq, Eq)]
pub struct Scrollbar {
    pub total: u64,
    pub offset: u64,
    pub len: u64,
}

// GhosttyTerminalData
pub const TERMINAL_DATA_SCROLLBAR: c_int = 9;
pub const TERMINAL_DATA_VIEWPORT_ACTIVE: c_int = 32;

unsafe extern "C" {
    pub fn ghostty_terminal_new(
        allocator: *const c_void,
        terminal: *mut Terminal,
        cols: u16,
        rows: u16,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_free(terminal: Terminal);
    pub fn ghostty_terminal_vt_write(terminal: Terminal, data: *const u8, len: usize);
    pub fn ghostty_terminal_resize(
        terminal: Terminal,
        cols: u16,
        rows: u16,
        cell_width_px: u32,
        cell_height_px: u32,
    ) -> GhosttyResult;
    pub fn ghostty_terminal_scroll_viewport(terminal: Terminal, behavior: ScrollViewport);
    pub fn ghostty_terminal_get(terminal: Terminal, data: c_int, out: *mut c_void)
    -> GhosttyResult;

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

    pub fn ghostty_key_encoder_new(
        allocator: *const c_void,
        encoder: *mut KeyEncoder,
    ) -> GhosttyResult;
    pub fn ghostty_key_encoder_free(encoder: KeyEncoder);
    pub fn ghostty_key_encoder_setopt_from_terminal(encoder: KeyEncoder, terminal: Terminal);
    pub fn ghostty_key_encoder_encode(
        encoder: KeyEncoder,
        event: KeyEvent,
        out: *mut c_char,
        out_size: usize,
        out_len: *mut usize,
    ) -> GhosttyResult;
    pub fn ghostty_key_event_new(allocator: *const c_void, event: *mut KeyEvent) -> GhosttyResult;
    pub fn ghostty_key_event_free(event: KeyEvent);
    pub fn ghostty_key_event_set_action(event: KeyEvent, action: c_int);
    pub fn ghostty_key_event_set_key(event: KeyEvent, key: c_int);
    pub fn ghostty_key_event_set_mods(event: KeyEvent, mods: u16);
    pub fn ghostty_key_event_set_consumed_mods(event: KeyEvent, mods: u16);
    pub fn ghostty_key_event_set_utf8(event: KeyEvent, utf8: *const c_char, len: usize);
    pub fn ghostty_key_event_set_unshifted_codepoint(event: KeyEvent, codepoint: u32);

    #[allow(dead_code, reason = "used by the layout test")]
    pub fn ghostty_type_json() -> *const c_char;
}
