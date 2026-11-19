//! An invented agent session as raw terminal output, shaped like Claude Code's:
//! truecolor and 256-colour text, box drawing, a spinner redrawn in place, an
//! input box erased with cursor-up, diff lines on coloured backgrounds, wide
//! characters, synchronized-output brackets. Every name and word is invented.
//! The same arguments always give the same bytes.

const ORANGE: &str = "\x1b[38;2;215;119;87m";
const GREY: &str = "\x1b[38;2;153;153;153m";
const GREEN: &str = "\x1b[38;2;78;186;101m";
const RESET: &str = "\x1b[0m";
const SPINNER: [&str; 6] = ["·", "✢", "✳", "✶", "✻", "✽"];
const FILES: [&str; 5] = [
    "src/frame.rs",
    "src/machines.rs",
    "src/list.kt",
    "src/settings.kt",
    "src/retry.rs",
];
const TOOLS: [&str; 4] = ["Read", "Update", "Search", "Bash"];
const WORDS: [&str; 34] = [
    "the",
    "parser",
    "reads",
    "each",
    "frame",
    "and",
    "checks",
    "its",
    "length",
    "before",
    "allocating",
    "a",
    "buffer",
    "so",
    "bad",
    "peer",
    "cannot",
    "exhaust",
    "memory",
    "we",
    "now",
    "retry",
    "once",
    "after",
    "timeout",
    "settings",
    "page",
    "renders",
    "list",
    "of",
    "machines",
    "日本語",
    "テスト",
    "done",
];

/// About `len` bytes of session output for a terminal `cols` wide, cut at `len`.
pub fn session(cols: u16, len: usize) -> Vec<u8> {
    let cols = usize::from(cols.max(20));
    let mut s = Session {
        out: String::with_capacity(len + 8192),
        rng: 0x2545_f491_4f6c_dd1d,
        cols,
    };
    s.banner();
    let mut turn = 0;
    while s.out.len() < len {
        s.turn(turn);
        turn += 1;
    }
    let mut bytes = s.out.into_bytes();
    bytes.truncate(len);
    bytes
}

struct Session {
    out: String,
    rng: u64,
    cols: usize,
}

impl Session {
    fn next(&mut self, n: usize) -> usize {
        self.rng = self
            .rng
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        ((self.rng >> 33) as usize) % n
    }

    fn line(&mut self, text: &str) {
        self.out.push_str(text);
        self.out.push_str("\r\n");
    }

    fn rule(&self, left: char, right: char) -> String {
        let mut s = String::new();
        s.push(left);
        s.extend(std::iter::repeat_n('─', self.cols - 2));
        s.push(right);
        s
    }

    fn banner(&mut self) {
        let top = self.rule('╭', '╮');
        let bottom = self.rule('╰', '╯');
        let body = format!("│ ✻ Welcome to the agent{}│", " ".repeat(self.cols - 25));
        self.line(&format!("{ORANGE}{top}"));
        self.line(&body);
        self.line(&format!("{bottom}{RESET}"));
    }

    fn sentence(&mut self, words: usize) -> String {
        let mut s = String::new();
        for i in 0..words {
            if i > 0 {
                s.push(' ');
            }
            let word = WORDS[self.next(WORDS.len())];
            match self.next(12) {
                0 => s.push_str(&format!("\x1b[1m{word}\x1b[22m")),
                1 => s.push_str(&format!("\x1b[3m{word}\x1b[23m")),
                2 => s.push_str(&format!("\x1b[38;5;{}m{word}\x1b[39m", 16 + self.next(216))),
                _ => s.push_str(word),
            }
        }
        s
    }

    fn turn(&mut self, turn: usize) {
        let prompt = self.sentence(6);
        self.line("");
        self.line(&format!("{GREY}>{RESET} {prompt}"));
        self.line("");

        for tick in 0..8 {
            let frame = SPINNER[tick % SPINNER.len()];
            self.out.push_str(&format!(
                "\x1b[?2026h\r\x1b[2K{ORANGE}{frame}{RESET} Thinking… \x1b[2m({}s · ↓ {:.1}k tokens · esc to interrupt)\x1b[22m\x1b[?2026l",
                turn + tick,
                (turn * 8 + tick) as f32 / 10.0
            ));
        }
        self.out.push_str("\r\x1b[2K");

        let tool = TOOLS[self.next(TOOLS.len())];
        let file = FILES[self.next(FILES.len())];
        self.line(&format!("{GREEN}⏺{RESET} \x1b[1m{tool}\x1b[22m({file})"));
        if tool == "Update" {
            self.line(&format!(
                "  ⎿  Updated {file} with 2 additions and 1 removal"
            ));
            let n = 10 + self.next(200);
            let removed = self.sentence(5);
            let added = self.sentence(6);
            self.line(&format!(
                "\x1b[48;2;92;34;43m  {n:>4} -  {removed}\x1b[K{RESET}"
            ));
            self.line(&format!(
                "\x1b[48;2;34;92;43m  {n:>4} +  {added}\x1b[K{RESET}"
            ));
            let extra = self.sentence(4);
            self.line(&format!(
                "\x1b[48;2;34;92;43m  {:>4} +  {extra}\x1b[K{RESET}",
                n + 1
            ));
        } else {
            let lines = 20 + self.next(400);
            self.line(&format!("  ⎿  Read {lines} lines"));
        }
        self.line("");

        let paragraphs = 1 + self.next(3);
        for _ in 0..paragraphs {
            let words = 15 + self.next(40);
            let text = self.sentence(words);
            self.line(&format!("⏺ {text}"));
        }

        // The input box under the output, erased again before the next turn.
        let top = self.rule('╭', '╮');
        let bottom = self.rule('╰', '╯');
        self.out.push_str(&format!(
            "\x1b[?2026h{GREY}{top}\r\n│ >{}│\r\n{bottom}{RESET}\r\n  ? for shortcuts\x1b[4A\r\x1b[J\x1b[?2026l",
            " ".repeat(self.cols - 4)
        ));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_arguments_same_bytes() {
        assert_eq!(session(80, 10_000), session(80, 10_000));
    }

    #[test]
    fn length_is_exact() {
        assert_eq!(session(200, 4096 * 3).len(), 4096 * 3);
    }

    #[test]
    fn has_the_sequences_the_spike_must_handle() {
        let s = String::from_utf8_lossy(&session(120, 64 * 1024)).into_owned();
        for needle in [
            "\x1b[38;2;",
            "\x1b[38;5;",
            "\x1b[48;2;",
            "\x1b[4A",
            "\x1b[J",
            "\x1b[2K",
            "\x1b[?2026h",
            "日本語",
            "╭",
            "⎿",
        ] {
            assert!(s.contains(needle), "missing {needle:?}");
        }
    }
}
