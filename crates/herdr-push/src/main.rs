//! herdr-push: pushes herdr agent alerts to phones (design.md §5).
//!
//!   herdr-push [--config <file>]   watch the configured core
//!   herdr-push --version
//!
//! Config: `$XDG_CONFIG_HOME/herdr-push/config` (see `config.rs`). State (what
//! was alerted): `$XDG_STATE_HOME/herdr-push/state.json`.

use std::path::PathBuf;
use std::process::ExitCode;

use herdr_push::config;
use herdr_push::deliver::{Allowlist, Https};
use herdr_push::watch::{self, Settings, Watcher};

fn xdg(var: &str, home: &std::path::Path, fallback: &str) -> PathBuf {
    std::env::var_os(var)
        .filter(|v| !v.is_empty())
        .map_or_else(|| home.join(fallback), PathBuf::from)
}

fn main() -> ExitCode {
    match start() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("herdr-push: {e}");
            ExitCode::from(2)
        }
    }
}

fn start() -> Result<(), String> {
    let home = PathBuf::from(std::env::var_os("HOME").ok_or("HOME is not set")?);
    let mut config_file = xdg("XDG_CONFIG_HOME", &home, ".config").join("herdr-push/config");
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--version" => {
                println!("herdr-push {}", env!("CARGO_PKG_VERSION"));
                return Ok(());
            }
            "--config" => config_file = args.next().ok_or("--config needs a file")?.into(),
            other => return Err(format!("unknown argument {other} (try --config <file>)")),
        }
    }
    let text = std::fs::read_to_string(&config_file)
        .map_err(|e| format!("cannot read {}: {e}", config_file.display()))?;
    let dir = config_file.parent().unwrap_or(&home).to_path_buf();
    let c =
        config::parse(&text, &dir, &home).map_err(|e| format!("{}: {e}", config_file.display()))?;
    let Some(socket) = c.socket else {
        // Never guess a core: a wrong environment would watch, or start, another one.
        return Err(format!(
            "{}: socket not set in {}",
            watch::NO_CORE,
            config_file.display()
        ));
    };
    let token = std::fs::read_to_string(&c.token_file)
        .map_err(|e| format!("cannot read the ntfy token {}: {e}", c.token_file.display()))?
        .trim()
        .to_owned();
    if token.is_empty() {
        return Err(format!(
            "the ntfy token {} is empty",
            c.token_file.display()
        ));
    }
    let settings = Settings {
        socket,
        allow: Allowlist::parse(&c.allow)?,
        token,
        grace_ms: c.grace_s * 1000,
        state_file: xdg("XDG_STATE_HOME", &home, ".local/state").join("herdr-push/state.json"),
    };
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .map_err(|e| e.to_string())?;
    runtime.block_on(Watcher::new(settings, Box::new(Https)).run());
    Ok(())
}
