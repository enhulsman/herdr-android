//! Starting a terminal or an agent from the phone (start-agents design D1, D2,
//! D5): the `terminal.run` parameters, the agent table, the installed-agents
//! check and its parse, and where a new terminal goes.
//!
//! The agent table is herdr-app's (`defaults/programs.json`, entries with
//! `run`, ordered as `programs.rs` `startable()`); keep it in step with the
//! desk when it adds agents.

use std::collections::BTreeSet;

use base64::Engine;
use serde_json::{Value, json};

use crate::attention::folder_name;
use crate::layout::Space;

/// The label the desk places terminals by.
pub const SPACE_LABEL: &str = "herdr.space";

/// One agent the sheet can start.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Agent {
    /// The desk's table key, also the icon's program id.
    pub program: &'static str,
    pub name: &'static str,
    /// The command, one word of `[a-z0-9._-]`: written into a shell line as is.
    pub word: &'static str,
}

const fn agent(program: &'static str, name: &'static str, word: &'static str) -> Agent {
    Agent {
        program,
        name,
        word,
    }
}

/// The desk's startable agents, most picked first, then by key.
pub const AGENTS: &[Agent] = &[
    agent("claude", "Claude Code", "claude"),
    agent("codex", "Codex", "codex"),
    agent("pi", "Pi", "pi"),
    agent("opencode", "OpenCode", "opencode"),
    agent("cursor", "Cursor", "cursor-agent"),
    agent("copilot", "Copilot", "copilot"),
    agent("amp", "Amp", "amp"),
    agent("gemini", "Gemini", "gemini"),
    agent("grok", "Grok", "grok"),
    agent("antigravity", "Antigravity", "agy"),
    agent("crush", "Crush", "crush"),
    agent("hermes", "Hermes", "hermes"),
    agent("kimi", "Kimi", "kimi"),
    agent("kiro", "Kiro", "kiro-cli"),
    agent("qoder", "Qoder", "qodercli"),
];

// ---- the start (design D1)

const WRAP_HEAD: &str =
    r#"case $(uname -s) in Darwin) f=-lic i=-li;; *) f=-ic i=-i;; esac; exec "${SHELL:-sh}" $f ""#;
const WRAP_TAIL: &str = r#"; exec \"\$0\" $i" "${SHELL:-sh}""#;

/// The outer `sh` line that runs `word` in the person's interactive shell (a
/// login one on macOS), written literally so their alias for it applies, and
/// leaves that shell in its place when it exits.
pub fn wrapper(word: &str) -> String {
    format!("{WRAP_HEAD}{word}{WRAP_TAIL}")
}

pub fn agent_argv(word: &str) -> Vec<String> {
    vec!["sh".into(), "-c".into(), wrapper(word), "sh".into()]
}

/// The word a wrapped start was made for, from a terminal's `argv`.
pub fn wrapped_word(argv: &Value) -> Option<&str> {
    let a = argv.as_array()?;
    if a.len() != 4 || a[0] != "sh" || a[1] != "-c" || a[3] != "sh" {
        return None;
    }
    a[2].as_str()?
        .strip_prefix(WRAP_HEAD)?
        .strip_suffix(WRAP_TAIL)
        .filter(|w| !w.is_empty())
}

/// `terminal.run` parameters: the machine's own shell (`agent` None) or a
/// wrapped agent, in `cwd` (None: the core's own folder), placed in `space`
/// and kept as the desk keeps its terminals.
pub fn run_params(cwd: Option<&str>, space: &str, agent: Option<&str>) -> Value {
    let mut p = json!({"labels": {SPACE_LABEL: space}, "keep": true});
    if let Some(cwd) = cwd {
        p["cwd"] = json!(cwd);
    }
    if let Some(word) = agent {
        p["argv"] = json!(agent_argv(word));
    }
    p
}

// ---- where it goes (design D2)

/// Where a space's folder puts a new terminal.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Folder {
    Path(String),
    /// Not a usable path (relative): home, with a note in the sheet.
    HomeInstead(String),
    /// Home was needed but is not known: no `cwd`, the core's own folder.
    HomeUnknown,
}

impl Folder {
    /// The `cwd` to send.
    pub fn cwd(&self) -> Option<&str> {
        match self {
            Folder::Path(p) | Folder::HomeInstead(p) => Some(p),
            Folder::HomeUnknown => None,
        }
    }
}

pub fn resolve_folder(folder: &str, home: Option<&str>) -> Folder {
    if folder.starts_with('/') {
        return Folder::Path(folder.to_owned());
    }
    let Some(home) = home else {
        return Folder::HomeUnknown;
    };
    let home = if home.len() > 1 {
        home.trim_end_matches('/')
    } else {
        home
    };
    match folder {
        "" | "~" => Folder::Path(home.to_owned()),
        f => match f.strip_prefix("~/") {
            Some(rest) => Folder::Path(format!("{home}/{rest}")),
            None => Folder::HomeInstead(home.to_owned()),
        },
    }
}

/// The desk's name for a space: its own, else its folder's (`~` for home).
pub fn space_title(space: &Space, home: Option<&str>) -> String {
    if !space.name.is_empty() {
        return space.name.clone();
    }
    let folder = resolve_folder(&space.folder, home);
    folder_name(folder.cwd().unwrap_or(""), home)
}

/// The folders a terminal can start in on a machine without spaces: home
/// first, then each live terminal's (its foreground's, else its own), once.
pub fn folder_choices(terminals: &[Value], home: Option<&str>) -> Vec<String> {
    let mut seen = BTreeSet::new();
    let mut out = Vec::new();
    let live = terminals
        .iter()
        .filter(|t| t["state"] == "live")
        .filter_map(|t| {
            t.pointer("/foreground/cwd")
                .and_then(Value::as_str)
                .or_else(|| t["cwd"].as_str())
        });
    for f in home.into_iter().chain(live) {
        let f = if f.len() > 1 {
            f.trim_end_matches('/')
        } else {
            f
        };
        if f.starts_with('/') && seen.insert(f.to_owned()) {
            out.push(f.to_owned());
        }
    }
    out
}

/// A space id in the desk's form (`sp` + 32 hex), for the first space on a
/// machine that has none: the desk adopts it as a new unnamed space.
pub fn fresh_space_id() -> String {
    use std::hash::{BuildHasher, Hasher};
    let random = || {
        std::collections::hash_map::RandomState::new()
            .build_hasher()
            .finish()
    };
    format!("sp{:016x}{:016x}", random(), random())
}

// ---- the installed-agents check (design D5)

const HOME_MARK: &str = "herdr-home:";
const HAS_MARK: &str = "herdr-has:";
const DONE_MARK: &str = "herdr-done";

/// `machine.exec` argv: home from the outer `sh`, then each `words` entry the
/// person's interactive shell finds (aliases count), then a done marker.
pub fn check_argv(words: &[&str]) -> Vec<String> {
    let line = format!(
        r#"printf '{HOME_MARK}%s\n' "$HOME"; case $(uname -s) in Darwin) f=-lic;; *) f=-ic;; esac; exec "${{SHELL:-sh}}" $f 'for c in {}; do command -v "$c" >/dev/null 2>&1 && echo "{HAS_MARK}$c"; done; echo {DONE_MARK}'"#,
        words.join(" ")
    );
    vec!["sh".into(), "-c".into(), line, "sh".into()]
}

/// Seconds the core lets the check run before ending it.
pub const CHECK_TIMEOUT_S: u64 = 5;

/// `machine.exec` parameters for the check.
pub fn check_params(words: &[&str]) -> Value {
    json!({"argv": check_argv(words), "timeout": CHECK_TIMEOUT_S})
}

/// What a check told: home (also when the rest failed), and the agents found
/// in table order, or None when the check failed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Check {
    pub home: Option<String>,
    pub found: Option<Vec<String>>,
}

/// `code` is the exit code, None for a signal or a timeout. Markers are found
/// anywhere in the output, as the desk does: a shell's prompt and title
/// escapes may sit around them.
pub fn parse_check(out: &str, code: Option<i32>, words: &[&str]) -> Check {
    let home = out
        .find(HOME_MARK)
        .map(|i| &out[i + HOME_MARK.len()..])
        .map(|rest| rest.split(['\n', '\r']).next().unwrap_or(""))
        .filter(|h| !h.is_empty())
        .map(str::to_owned);
    let done = code == Some(0) && out.contains(DONE_MARK);
    let found = done.then(|| {
        let has: BTreeSet<&str> = out
            .split(HAS_MARK)
            .skip(1)
            .filter_map(|s| {
                s.split(|c: char| c.is_whitespace() || c.is_control())
                    .next()
            })
            .collect();
        words
            .iter()
            .filter(|w| has.contains(*w))
            .map(|w| w.to_string())
            .collect()
    });
    Check { home, found }
}

/// [`parse_check`] on a `machine.exec` result (`stdout` in base64).
pub fn parse_exec_result(result: &Value, words: &[&str]) -> Check {
    let out = result["stdout"]
        .as_str()
        .and_then(|b| base64::engine::general_purpose::STANDARD.decode(b).ok())
        .map(|b| String::from_utf8_lossy(&b).into_owned())
        .unwrap_or_default();
    let code = if result["timed_out"] == true {
        None
    } else {
        result["code"].as_i64().map(|c| c as i32)
    };
    parse_check(&out, code, words)
}
