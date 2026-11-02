//! Keys to bytes through libghostty's key encoder, for the program's current
//! keyboard modes (terminal-input spec: typing, the keys row and the compose
//! paste all go through it).
//!
//! Adapted from herdr-app `src/vt.rs` (`Vt::encode_key`, `Vt::paste_bytes`),
//! Apache-2.0 (see crates/herdr-proto/LICENSE-herdr-Apache-2.0). Changed: keys
//! come from a phone keyboard as characters, so a character is mapped to the
//! US-layout key that types it (its unshifted codepoint and Shift), and the
//! macOS option-as-alt setting does not apply; the compose paste is always
//! bracketed (the spec), and markers are removed until none is left.

use super::{Terminal, ffi};
use crate::keys_row::{Key, Mods};
use std::ffi::c_char;

/// The key that types `c` on a US layout, and whether it takes Shift.
fn us_key(c: char) -> Option<(i32, char, bool)> {
    const SHIFTED: [(char, char); 21] = [
        ('~', '`'),
        ('!', '1'),
        ('@', '2'),
        ('#', '3'),
        ('$', '4'),
        ('%', '5'),
        ('^', '6'),
        ('&', '7'),
        ('*', '8'),
        ('(', '9'),
        (')', '0'),
        ('_', '-'),
        ('+', '='),
        ('{', '['),
        ('}', ']'),
        ('|', '\\'),
        (':', ';'),
        ('"', '\''),
        ('<', ','),
        ('>', '.'),
        ('?', '/'),
    ];
    let (base, shift) = match SHIFTED.iter().find(|(s, _)| *s == c) {
        Some((_, base)) => (*base, true),
        None if c.is_ascii_uppercase() => (c.to_ascii_lowercase(), true),
        None => (c, false),
    };
    let key = match base {
        'a'..='z' => ffi::KEY_A + (base as i32 - 'a' as i32),
        '0'..='9' => ffi::KEY_DIGIT_0 + (base as i32 - '0' as i32),
        '`' => ffi::KEY_BACKQUOTE,
        '\\' => ffi::KEY_BACKSLASH,
        '[' => ffi::KEY_BRACKET_LEFT,
        ']' => ffi::KEY_BRACKET_RIGHT,
        ',' => ffi::KEY_COMMA,
        '=' => ffi::KEY_EQUAL,
        '-' => ffi::KEY_MINUS,
        '.' => ffi::KEY_PERIOD,
        '\'' => ffi::KEY_QUOTE,
        ';' => ffi::KEY_SEMICOLON,
        '/' => ffi::KEY_SLASH,
        ' ' => ffi::KEY_SPACE,
        _ => return None,
    };
    Some((key, base, shift))
}

impl Terminal {
    /// One key press, encoded for the program's current modes (cursor key
    /// mode, kitty keyboard flags, ...). Empty when the encoder has nothing.
    pub fn encode_key(&mut self, key: Key, mods: Mods) -> Vec<u8> {
        let mut gmods = 0u16;
        if mods.ctrl {
            gmods |= ffi::MODS_CTRL;
        }
        if mods.alt {
            gmods |= ffi::MODS_ALT;
        }
        let mut text = [0u8; 4];
        let (gkey, utf8, unshifted, consumed): (i32, &[u8], u32, u16) = match key {
            Key::Escape => (ffi::KEY_ESCAPE, &[], 0, 0),
            Key::Tab => (ffi::KEY_TAB, &[], 0, 0),
            Key::Enter => (ffi::KEY_ENTER, &[], 0, 0),
            Key::Backspace => (ffi::KEY_BACKSPACE, &[], 0, 0),
            Key::Up => (ffi::KEY_ARROW_UP, &[], 0, 0),
            Key::Down => (ffi::KEY_ARROW_DOWN, &[], 0, 0),
            Key::Left => (ffi::KEY_ARROW_LEFT, &[], 0, 0),
            Key::Right => (ffi::KEY_ARROW_RIGHT, &[], 0, 0),
            Key::Char(c) => {
                let utf8 = c.encode_utf8(&mut text).as_bytes();
                match us_key(c) {
                    Some((k, base, shift)) => {
                        if shift {
                            gmods |= ffi::MODS_SHIFT;
                        }
                        let consumed = if shift { ffi::MODS_SHIFT } else { 0 };
                        (k, utf8, u32::from(base), consumed)
                    }
                    None => (ffi::KEY_UNIDENTIFIED, utf8, u32::from(c), 0),
                }
            }
        };
        unsafe {
            ffi::ghostty_key_encoder_setopt_from_terminal(self.keys, self.terminal);
            let e = self.key_event;
            ffi::ghostty_key_event_set_action(e, ffi::KEY_ACTION_PRESS);
            ffi::ghostty_key_event_set_key(e, gkey);
            ffi::ghostty_key_event_set_mods(e, gmods);
            ffi::ghostty_key_event_set_consumed_mods(e, consumed);
            ffi::ghostty_key_event_set_utf8(e, utf8.as_ptr().cast::<c_char>(), utf8.len());
            ffi::ghostty_key_event_set_unshifted_codepoint(e, unshifted);
            let mut buf = [0u8; 128];
            let mut len = 0usize;
            let r = ffi::ghostty_key_encoder_encode(
                self.keys,
                e,
                buf.as_mut_ptr().cast::<c_char>(),
                buf.len(),
                &mut len,
            );
            if r != ffi::SUCCESS {
                return Vec::new();
            }
            buf[..len].to_vec()
        }
    }
}

/// Text from the compose field as a bracketed paste with no Enter
/// (terminal-input spec, "A compose key for long text"): always wrapped, even
/// when the program did not ask for bracketed paste, newlines kept as typed.
/// Paste markers inside the text are removed until none is left, so removing
/// one can never join the text around it into another.
pub fn paste_bytes(text: &str) -> Vec<u8> {
    const START: &str = "\x1b[200~";
    const END: &str = "\x1b[201~";
    let mut clean = text.to_owned();
    while clean.contains(START) || clean.contains(END) {
        clean = clean.replace(START, "").replace(END, "");
    }
    [START.as_bytes(), clean.as_bytes(), END.as_bytes()].concat()
}

#[cfg(test)]
mod tests {
    use super::super::Terminal;
    use super::paste_bytes;
    use crate::keys_row::{Key, KeysRow, Mods, RowKey};

    fn hex(b: &[u8]) -> String {
        b.iter()
            .map(|x| format!("{x:02x}"))
            .collect::<Vec<_>>()
            .join(" ")
    }

    /// Presses keys-row keys in order (sticky modifiers included) and returns
    /// the bytes of the last press that produced any.
    fn press(t: &mut Terminal, keys: &[RowKey]) -> Vec<u8> {
        let mut row = KeysRow::default();
        let mut out = Vec::new();
        for k in keys {
            if let Some((key, mods)) = row.tap(*k) {
                out = t.encode_key(key, mods);
            }
        }
        out
    }

    /// Every key on the keys row and its shelf, alone and with the sticky
    /// modifiers, in a program that set no keyboard modes (a shell).
    #[test]
    fn every_keys_row_key_encodes() {
        use RowKey::*;
        let table: &[(&[RowKey], &[u8])] = &[
            (&[Esc], b"\x1b"),
            (&[Tab], b"\t"),
            (&[Enter], b"\r"),
            (&[Up], b"\x1b[A"),
            (&[Down], b"\x1b[B"),
            (&[Right], b"\x1b[C"),
            (&[Left], b"\x1b[D"),
            (&[CtrlC], b"\x03"),
            (&[Symbol('|')], b"|"),
            (&[Symbol('~')], b"~"),
            (&[Symbol('/')], b"/"),
            (&[Symbol('-')], b"-"),
            (&[Symbol('_')], b"_"),
            (&[Symbol('\\')], b"\\"),
            (&[Symbol('`')], b"`"),
            (&[Symbol('*')], b"*"),
            (&[Symbol('&')], b"&"),
            (&[Symbol('<')], b"<"),
            (&[Symbol('>')], b">"),
            (&[Symbol('[')], b"["),
            (&[Symbol(']')], b"]"),
            (&[Symbol('{')], b"{"),
            (&[Symbol('}')], b"}"),
            // Sticky ctrl, then a key from the keyboard or the row.
            (&[Ctrl, Typed('c')], b"\x03"),
            (&[Ctrl, Typed('d')], b"\x04"),
            (&[Ctrl, Typed('r')], b"\x12"),
            (&[Ctrl, Typed('z')], b"\x1a"),
            (&[Ctrl, Up], b"\x1b[1;5A"),
            (&[Ctrl, Left], b"\x1b[1;5D"),
            // Sticky alt: ESC prefix for text, modifier parameter for arrows.
            (&[Alt, Typed('b')], b"\x1bb"),
            (&[Alt, Typed('f')], b"\x1bf"),
            (&[Alt, Left], b"\x1b[1;3D"),
            (&[Alt, Enter], b"\x1b\r"),
            (&[Ctrl, Alt, Typed('c')], b"\x1b\x03"),
        ];
        let mut t = Terminal::new(80, 24).unwrap();
        let mut failed = Vec::new();
        for (keys, want) in table {
            let got = press(&mut t, keys);
            if got != *want {
                failed.push(format!("{keys:?}: got {}, want {}", hex(&got), hex(want)));
            }
        }
        assert!(failed.is_empty(), "{}", failed.join("\n"));
    }

    /// Typing goes through the same encoder: letters, digits, space, a
    /// character outside the US layout, and the keyboard's backspace.
    #[test]
    fn typed_characters_and_backspace_encode() {
        let mut t = Terminal::new(80, 24).unwrap();
        let none = Mods::default();
        for (key, want) in [
            (Key::Char('l'), b"l".as_slice()),
            (Key::Char('S'), b"S"),
            (Key::Char('7'), b"7"),
            (Key::Char(' '), b" "),
            (Key::Char('é'), "é".as_bytes()),
            (Key::Char('日'), "日".as_bytes()),
            (Key::Backspace, b"\x7f"),
            (Key::Enter, b"\r"),
        ] {
            assert_eq!(t.encode_key(key, none), want, "{key:?}");
        }
    }

    /// "lsx", backspace, enter: what a shell receives (terminal-input spec,
    /// "Backspace").
    #[test]
    fn backspace_scenario_bytes() {
        let mut t = Terminal::new(80, 24).unwrap();
        let none = Mods::default();
        let bytes: Vec<u8> = [
            Key::Char('l'),
            Key::Char('s'),
            Key::Char('x'),
            Key::Backspace,
            Key::Enter,
        ]
        .into_iter()
        .flat_map(|k| t.encode_key(k, none))
        .collect();
        assert_eq!(bytes, b"lsx\x7f\r");
    }

    /// The program's modes change the encoding: application cursor keys
    /// (menus in full-screen programs) and the kitty keyboard protocol.
    #[test]
    fn keys_follow_the_programs_modes() {
        use RowKey::*;
        let mut t = Terminal::new(80, 24).unwrap();
        t.write(b"\x1b[?1h");
        assert_eq!(press(&mut t, &[Up]), b"\x1bOA");
        assert_eq!(press(&mut t, &[Down]), b"\x1bOB");
        assert_eq!(press(&mut t, &[Enter]), b"\r");
        t.write(b"\x1b[?1l");
        assert_eq!(press(&mut t, &[Down]), b"\x1b[B");

        // Kitty "disambiguate escape codes" (flag 1), as agents push it.
        t.write(b"\x1b[>1u");
        assert_eq!(press(&mut t, &[Esc]), b"\x1b[27u");
        assert_eq!(press(&mut t, &[CtrlC]), b"\x1b[99;5u");
        assert_eq!(press(&mut t, &[Enter]), b"\r");
        assert_eq!(press(&mut t, &[Tab]), b"\t");
        assert_eq!(press(&mut t, &[Symbol('|')]), b"|");
        t.write(b"\x1b[<u");
        assert_eq!(press(&mut t, &[Esc]), b"\x1b");
    }

    /// "Choosing a menu option": down, down, enter in a program using
    /// application cursor keys.
    #[test]
    fn menu_scenario_bytes() {
        use RowKey::*;
        let mut t = Terminal::new(80, 24).unwrap();
        t.write(b"\x1b[?1h");
        let bytes: Vec<u8> = [Down, Down, Enter]
            .iter()
            .flat_map(|k| press(&mut t, &[*k]))
            .collect();
        assert_eq!(bytes, b"\x1bOB\x1bOB\r");
    }

    /// The compose key (terminal-input spec, "A compose key for long text"):
    /// always a bracketed paste, with no Enter and no newline conversion,
    /// whether or not the program asked for bracketed paste.
    #[test]
    fn compose_text_is_a_bracketed_paste_without_enter() {
        let text = "fix the retry\nthen run the tests";
        let want = b"\x1b[200~fix the retry\nthen run the tests\x1b[201~".as_slice();
        assert_eq!(paste_bytes(text), want);
        let mut t = Terminal::new(80, 24).unwrap();
        t.write(b"\x1b[?2004h");
        assert_eq!(paste_bytes(text), want, "same with the mode on");
        assert_eq!(
            paste_bytes("one\r\ntwo\n"),
            b"\x1b[200~one\r\ntwo\n\x1b[201~",
            "newlines are kept as typed, nothing is added"
        );
    }

    /// Pasted text can never end the paste early or start a nested one, also
    /// when removing one marker would join the text around it into another.
    #[test]
    fn paste_markers_inside_the_text_are_removed_and_never_recreated() {
        for (text, body) in [
            ("a\x1b[201~b", "ab"),
            ("a\x1b[200~b", "ab"),
            ("\x1b[20\x1b[201~1~", ""),
            ("\x1b[20\x1b[200~0~x", "x"),
            ("\x1b[2\x1b[20\x1b[201~1~1~y", "\x1b[21~y"),
            ("\x1b[20\x1b[20\x1b[201~1~1~z", "z"),
            ("\x1b[201\x1b[200~~", ""),
            ("plain \x1b[A arrow", "plain \x1b[A arrow"),
        ] {
            let got = paste_bytes(text);
            let inner = &got[6..got.len() - 6];
            assert_eq!(inner, body.as_bytes(), "{text:?}");
            for marker in [b"\x1b[200~".as_slice(), b"\x1b[201~"] {
                assert!(
                    !inner.windows(6).any(|w| w == marker),
                    "{text:?} left a marker"
                );
            }
        }
    }
}
