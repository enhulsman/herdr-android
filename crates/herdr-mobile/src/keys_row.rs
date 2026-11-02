//! The keys row above the keyboard (terminal-input spec, "A keys row for keys
//! the phone keyboard lacks"): esc, tab, sticky ctrl and alt, arrows, enter,
//! ctrl+c and the shelf's symbols. Each tap becomes one key press for the
//! terminal's key encoder (`vt::Terminal::encode_key`), the same one typing uses.

/// A key the terminal's encoder understands.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Key {
    Escape,
    Tab,
    Enter,
    Backspace,
    Up,
    Down,
    Left,
    Right,
    /// A character, typed or from the shelf.
    Char(char),
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Mods {
    pub ctrl: bool,
    pub alt: bool,
}

/// What the user tapped: a keys-row key, or a character from the keyboard
/// (which takes any armed sticky modifier).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RowKey {
    Esc,
    Tab,
    Ctrl,
    Alt,
    Up,
    Down,
    Left,
    Right,
    Enter,
    CtrlC,
    /// A shelf symbol such as `|`, `~`, `/`, `-`.
    Symbol(char),
    /// A character from the phone keyboard.
    Typed(char),
}

/// The row's sticky modifiers: ctrl and alt arm for the next key, which uses
/// and releases them; tapping an armed modifier again releases it.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct KeysRow {
    armed: Mods,
}

impl KeysRow {
    /// The modifiers waiting for the next key (the row shows them as held).
    pub fn armed(&self) -> Mods {
        self.armed
    }

    /// A tap: None for a modifier (now armed or released), else the key press
    /// to encode, with the armed modifiers, which are then released.
    pub fn tap(&mut self, key: RowKey) -> Option<(Key, Mods)> {
        let key = match key {
            RowKey::Ctrl => {
                self.armed.ctrl = !self.armed.ctrl;
                return None;
            }
            RowKey::Alt => {
                self.armed.alt = !self.armed.alt;
                return None;
            }
            RowKey::CtrlC => {
                self.armed.ctrl = true;
                Key::Char('c')
            }
            RowKey::Esc => Key::Escape,
            RowKey::Tab => Key::Tab,
            RowKey::Up => Key::Up,
            RowKey::Down => Key::Down,
            RowKey::Left => Key::Left,
            RowKey::Right => Key::Right,
            RowKey::Enter => Key::Enter,
            RowKey::Symbol(c) | RowKey::Typed(c) => Key::Char(c),
        };
        Some((key, std::mem::take(&mut self.armed)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sticky_ctrl_applies_to_the_next_key_only() {
        let mut row = KeysRow::default();
        assert_eq!(row.tap(RowKey::Ctrl), None);
        assert!(row.armed().ctrl);
        let ctrl = Mods {
            ctrl: true,
            alt: false,
        };
        assert_eq!(row.tap(RowKey::Typed('c')), Some((Key::Char('c'), ctrl)));
        assert_eq!(row.armed(), Mods::default());
        assert_eq!(
            row.tap(RowKey::Typed('c')),
            Some((Key::Char('c'), Mods::default()))
        );
    }

    #[test]
    fn tapping_an_armed_modifier_releases_it() {
        let mut row = KeysRow::default();
        row.tap(RowKey::Alt);
        row.tap(RowKey::Alt);
        assert_eq!(row.tap(RowKey::Up), Some((Key::Up, Mods::default())));
    }

    #[test]
    fn ctrl_and_alt_combine_and_ctrl_c_is_one_tap() {
        let mut row = KeysRow::default();
        row.tap(RowKey::Ctrl);
        row.tap(RowKey::Alt);
        let both = Mods {
            ctrl: true,
            alt: true,
        };
        assert_eq!(row.tap(RowKey::Left), Some((Key::Left, both)));
        let ctrl = Mods {
            ctrl: true,
            alt: false,
        };
        assert_eq!(row.tap(RowKey::CtrlC), Some((Key::Char('c'), ctrl)));
        assert_eq!(row.armed(), Mods::default());
    }
}
