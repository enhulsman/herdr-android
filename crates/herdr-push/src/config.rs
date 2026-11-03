//! The watcher's config file, `$XDG_CONFIG_HOME/herdr-push/config`:
//!
//! ```text
//! # The core to watch: its socket. herdr-push never starts a core.
//! socket = /run/user/1000/herdr-core/default.sock
//! # Where pushes may go (repeatable): https origins.
//! allow = https://ntfy.example.org
//! # The ntfy access token (write-only on up*), one line.
//! token_file = ~/.config/herdr-push/ntfy-token
//! # Seconds a state must hold before it alerts.
//! grace = 30
//! ```

use std::path::{Path, PathBuf};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Config {
    /// None: not configured, so the watcher reports "no core".
    pub socket: Option<PathBuf>,
    pub allow: Vec<String>,
    pub token_file: PathBuf,
    pub grace_s: u64,
}

pub const DEFAULT_GRACE_S: u64 = 30;

/// `dir` is the config file's directory (the token's default home), `home`
/// expands a leading `~/`.
pub fn parse(text: &str, dir: &Path, home: &Path) -> Result<Config, String> {
    let expand = |v: &str| match v.strip_prefix("~/") {
        Some(rest) => home.join(rest),
        None => PathBuf::from(v),
    };
    let mut config = Config {
        socket: None,
        allow: Vec::new(),
        token_file: dir.join("ntfy-token"),
        grace_s: DEFAULT_GRACE_S,
    };
    for (i, line) in text.lines().enumerate() {
        let n = i + 1;
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            return Err(format!("line {n}: expected key = value"));
        };
        let (key, value) = (key.trim(), value.trim());
        match key {
            "socket" => {
                let path = expand(value);
                if !path.is_absolute() {
                    return Err(format!("line {n}: socket must be an absolute path"));
                }
                config.socket = Some(path);
            }
            "allow" => config.allow.push(value.to_owned()),
            "token_file" => config.token_file = expand(value),
            "grace" => {
                config.grace_s = value
                    .parse()
                    .map_err(|_| format!("line {n}: grace is not a number of seconds"))?;
            }
            other => return Err(format!("line {n}: unknown key {other}")),
        }
    }
    crate::deliver::Allowlist::parse(&config.allow).map_err(|e| format!("allow: {e}"))?;
    Ok(config)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(text: &str) -> Result<Config, String> {
        parse(text, Path::new("/cfg/herdr-push"), Path::new("/home/me"))
    }

    #[test]
    fn a_full_config_is_read() {
        let c = p("# watcher\n\
                   socket = /run/user/1000/herdr-core/default.sock\n\
                   \n\
                   allow = https://ntfy.example.org\n\
                   allow=https://push.example.org:8443\n\
                   token_file = ~/secrets/ntfy\n\
                   grace = 45\n")
        .unwrap();
        assert_eq!(
            c,
            Config {
                socket: Some("/run/user/1000/herdr-core/default.sock".into()),
                allow: vec![
                    "https://ntfy.example.org".into(),
                    "https://push.example.org:8443".into()
                ],
                token_file: "/home/me/secrets/ntfy".into(),
                grace_s: 45,
            }
        );
    }

    #[test]
    fn defaults_leave_the_socket_unset() {
        let c = p("allow = https://ntfy.example.org\n").unwrap();
        assert_eq!(c.socket, None);
        assert_eq!(c.token_file, PathBuf::from("/cfg/herdr-push/ntfy-token"));
        assert_eq!(c.grace_s, DEFAULT_GRACE_S);
    }

    #[test]
    fn mistakes_name_their_line() {
        assert_eq!(
            p("sokcet = /x\n").unwrap_err(),
            "line 1: unknown key sokcet"
        );
        assert_eq!(p("\nallow\n").unwrap_err(), "line 2: expected key = value");
        assert_eq!(
            p("grace = soon\n").unwrap_err(),
            "line 1: grace is not a number of seconds"
        );
        assert_eq!(
            p("socket = relative.sock\n").unwrap_err(),
            "line 1: socket must be an absolute path"
        );
        assert_eq!(
            p("allow = http://ntfy.example.org\n").unwrap_err(),
            "allow: http://ntfy.example.org is not an https origin"
        );
        assert_eq!(
            p("").unwrap_err(),
            "allow: at least one https origin is needed"
        );
    }
}
