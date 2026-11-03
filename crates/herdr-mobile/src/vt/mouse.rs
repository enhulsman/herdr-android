//! Scrolling a full-screen program (terminal-view spec, "Scrollback with a way
//! back to live"). A program on the alternate screen, or one tracking the
//! mouse, keeps its own history and herdr sends none, so the phone scrolls the
//! program itself, as the desk app does (herdr-app `TerminalView::scroll`):
//! wheel events while it tracks the mouse, otherwise arrow keys on the
//! alternate screen, one per row. On the main screen the phone scrolls its own
//! scrollback. A tap on a program tracking the mouse is a click, as on the desk.

use std::ffi::c_void;
use std::ptr;

use super::{Terminal, ffi};
use crate::keys_row::{Key, Mods};

/// Whole-pixel cells for the encoder (it needs a size; the program sees cells,
/// or these pixels in SGR-pixels mode).
const CELL_W: u32 = 10;
const CELL_H: u32 = 20;

impl Terminal {
    fn mouse_tracking(&self) -> bool {
        let mut on = false;
        unsafe {
            ffi::ghostty_terminal_get(
                self.terminal,
                ffi::TERMINAL_DATA_MOUSE_TRACKING,
                (&raw mut on).cast::<c_void>(),
            )
        };
        on
    }

    fn alt_screen(&self) -> bool {
        let mut screen: i32 = ffi::SCREEN_PRIMARY;
        unsafe {
            ffi::ghostty_terminal_get(
                self.terminal,
                ffi::TERMINAL_DATA_ACTIVE_SCREEN,
                (&raw mut screen).cast::<c_void>(),
            )
        };
        screen == ffi::SCREEN_ALTERNATE
    }

    /// The input that scrolls the program by `rows` (up is negative), or
    /// `None` when the program leaves scrolling to the phone (main screen, no
    /// mouse tracking): then [`Terminal::scroll`] moves the local view.
    pub fn program_scroll(&mut self, rows: i32) -> Option<Vec<u8>> {
        let up = rows < 0;
        let n = rows.unsigned_abs() as usize;
        if self.mouse_tracking() {
            let button = if up {
                ffi::MOUSE_BUTTON_FOUR
            } else {
                ffi::MOUSE_BUTTON_FIVE
            };
            let one = self.mouse(ffi::MOUSE_ACTION_PRESS, button, 0, 0);
            return Some(one.repeat(n));
        }
        if self.alt_screen() {
            let key = if up { Key::Up } else { Key::Down };
            let one = self.encode_key(key, Mods::default());
            return Some(one.repeat(n));
        }
        None
    }

    /// A tap on cell (`col`, `row`) as a left click (press and release), for a
    /// program tracking the mouse; `None` for any other, which takes no clicks.
    /// A cell past the grid is its last one.
    pub fn program_click(&mut self, col: u16, row: u16) -> Option<Vec<u8>> {
        if !self.mouse_tracking() {
            return None;
        }
        let (col, row) = (
            col.min(self.cols.saturating_sub(1)),
            row.min(self.rows.saturating_sub(1)),
        );
        let mut out = self.mouse(ffi::MOUSE_ACTION_PRESS, ffi::MOUSE_BUTTON_LEFT, col, row);
        out.extend(self.mouse(ffi::MOUSE_ACTION_RELEASE, ffi::MOUSE_BUTTON_LEFT, col, row));
        Some(out)
    }

    /// One mouse event over cell (`col`, `row`). Wheel events go over the
    /// top-left cell: one that exists at any size, also after the takeover
    /// reattaches at the phone's smaller grid.
    fn mouse(&mut self, action: i32, button: i32, col: u16, row: u16) -> Vec<u8> {
        let (mut enc, mut ev) = (ptr::null_mut(), ptr::null_mut());
        unsafe {
            if ffi::ghostty_mouse_encoder_new(ptr::null(), &mut enc) != ffi::SUCCESS {
                return Vec::new();
            }
            if ffi::ghostty_mouse_event_new(ptr::null(), &mut ev) != ffi::SUCCESS {
                ffi::ghostty_mouse_encoder_free(enc);
                return Vec::new();
            }
            ffi::ghostty_mouse_encoder_setopt_from_terminal(enc, self.terminal);
            let size = ffi::MouseEncoderSize {
                size: size_of::<ffi::MouseEncoderSize>(),
                screen_width: u32::from(self.cols) * CELL_W,
                screen_height: u32::from(self.rows) * CELL_H,
                cell_width: CELL_W,
                cell_height: CELL_H,
                ..Default::default()
            };
            ffi::ghostty_mouse_encoder_setopt(
                enc,
                ffi::MOUSE_ENCODER_OPT_SIZE,
                (&raw const size).cast::<c_void>(),
            );
            ffi::ghostty_mouse_event_set_action(ev, action);
            ffi::ghostty_mouse_event_set_button(ev, button);
            ffi::ghostty_mouse_event_set_mods(ev, 0);
            ffi::ghostty_mouse_event_set_position(
                ev,
                ffi::MousePosition {
                    x: (f32::from(col) + 0.5) * CELL_W as f32,
                    y: (f32::from(row) + 0.5) * CELL_H as f32,
                },
            );
            let mut buf = [0u8; 64];
            let mut len = 0usize;
            let code = ffi::ghostty_mouse_encoder_encode(
                enc,
                ev,
                buf.as_mut_ptr().cast(),
                buf.len(),
                &mut len,
            );
            ffi::ghostty_mouse_event_free(ev);
            ffi::ghostty_mouse_encoder_free(enc);
            if code != ffi::SUCCESS {
                return Vec::new();
            }
            buf[..len].to_vec()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::super::Terminal;

    fn t() -> Terminal {
        Terminal::new(40, 10).unwrap()
    }

    #[test]
    fn the_main_screen_is_scrolled_on_the_phone() {
        let mut t = t();
        t.write(b"plain shell output\r\n");
        assert_eq!(t.program_scroll(-3), None);
        assert_eq!(t.program_scroll(2), None);
    }

    #[test]
    fn a_full_screen_program_tracking_the_mouse_gets_one_wheel_event_per_row() {
        let mut t = t();
        t.write(b"\x1b[?1049h\x1b[?1000h\x1b[?1006h");
        assert_eq!(
            t.program_scroll(-2).unwrap(),
            b"\x1b[<64;1;1M\x1b[<64;1;1M".to_vec(),
            "up is wheel up (button 4)"
        );
        assert_eq!(
            t.program_scroll(1).unwrap(),
            b"\x1b[<65;1;1M".to_vec(),
            "down is wheel down (button 5)"
        );
    }

    #[test]
    fn mouse_tracking_on_the_main_screen_also_gets_the_wheel() {
        let mut t = t();
        t.write(b"\x1b[?1000h\x1b[?1006h");
        assert_eq!(t.program_scroll(-1).unwrap(), b"\x1b[<64;1;1M".to_vec());
    }

    #[test]
    fn the_alternate_screen_without_the_mouse_gets_arrow_keys() {
        let mut t = t();
        t.write(b"\x1b[?1049h");
        assert_eq!(t.program_scroll(-2).unwrap(), b"\x1b[A\x1b[A".to_vec());
        assert_eq!(t.program_scroll(1).unwrap(), b"\x1b[B".to_vec());
        // in application cursor mode the program hears its own arrows
        t.write(b"\x1b[?1h");
        assert_eq!(t.program_scroll(-1).unwrap(), b"\x1bOA".to_vec());
    }

    #[test]
    fn leaving_the_full_screen_hands_scrolling_back_to_the_phone() {
        let mut t = t();
        t.write(b"\x1b[?1049h\x1b[?1000h");
        assert!(t.program_scroll(-1).is_some());
        t.write(b"\x1b[?1000l\x1b[?1049l");
        assert_eq!(t.program_scroll(-1), None);
    }

    #[test]
    fn a_tap_on_a_program_tracking_the_mouse_is_a_click_on_that_cell() {
        let mut t = t();
        t.write(b"\x1b[?1049h\x1b[?1000h\x1b[?1006h");
        assert_eq!(
            t.program_click(4, 2).unwrap(),
            b"\x1b[<0;5;3M\x1b[<0;5;3m".to_vec(),
            "left press and release, 1-based"
        );
        // past the grid: the last cell
        assert_eq!(
            t.program_click(99, 99).unwrap(),
            b"\x1b[<0;40;10M\x1b[<0;40;10m".to_vec()
        );
    }

    #[test]
    fn a_tap_on_a_program_not_tracking_the_mouse_sends_nothing() {
        let mut t = t();
        assert_eq!(t.program_click(1, 1), None, "main screen");
        t.write(b"\x1b[?1049h");
        assert_eq!(t.program_click(1, 1), None, "full screen, no mouse");
    }

    #[test]
    fn no_rows_is_no_input() {
        let mut t = t();
        t.write(b"\x1b[?1049h\x1b[?1000h");
        assert_eq!(t.program_scroll(0), Some(Vec::new()));
    }
}
