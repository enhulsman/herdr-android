// Generated from Ghostty 1e5cdb8 include/ghostty/vt/terminal.h.
// Values and declaration extracted from cc -E -P output; do not edit by hand.
use super::{GhosttyResult, Terminal};
use std::ffi::{c_int, c_void};
pub const GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND: c_int = 11;
pub const GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND: c_int = 12;
pub const GHOSTTY_TERMINAL_OPT_COLOR_PALETTE: c_int = 14;
pub const GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT: c_int = 25;
unsafe extern "C" {
    pub fn ghostty_terminal_set(
        terminal: Terminal,
        option: c_int,
        value: *const c_void,
    ) -> GhosttyResult;
}
