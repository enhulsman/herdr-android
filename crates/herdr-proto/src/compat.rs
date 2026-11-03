//! Whether a core's `hello` says it can serve this app (machine-connection spec,
//! "The protocol version is checked before use").
//!
//! Adapted from herdr-app `src/hc.rs` (`check`, `semver`), Apache-2.0. Changed:
//! the phone's own method list and minimum. A newer core than CI tested
//! connects with no notice (phone check round 2): CI runs the protocol suite
//! against the latest release, and bugs in a newer one become issues.

use serde_json::Value;

/// The core API generation this app speaks.
pub const GENERATION: u64 = 1;

/// The oldest core this app works with: the pinned version its tests run against.
pub const MIN_CORE: &str = "0.1.15";

/// What the app calls on a core; a core lacking one of them is too old.
/// Grows with the milestones; a test lists every method called in `src`.
pub const USES: &[&str] = &[
    "events.subscribe",
    "terminal.attach",
    "terminal.get",
    "terminal.interrupt",
];

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Compat {
    Ok,
    /// Older than the oldest supported core: stay disconnected.
    TooOld {
        have: String,
        need: String,
    },
    /// It lacks methods the app calls (an unusual build): treated as too old.
    Missing {
        have: String,
        methods: Vec<String>,
    },
    /// A protocol generation this app does not speak: the app needs an update.
    NewerGeneration {
        generation: u64,
    },
}

/// Major, minor, patch; a pre-release suffix is ignored.
fn semver(v: &str) -> (u64, u64, u64) {
    let mut p = v
        .trim()
        .split(['.', '-', '+'])
        .map(|n| n.parse::<u64>().unwrap_or(0));
    (
        p.next().unwrap_or(0),
        p.next().unwrap_or(0),
        p.next().unwrap_or(0),
    )
}

/// Checks a `hello` result against this app.
pub fn check(hello: &Value) -> Compat {
    let generation = hello.get("generation").and_then(Value::as_u64).unwrap_or(0);
    if generation > GENERATION {
        return Compat::NewerGeneration { generation };
    }
    let have = hello
        .get("version")
        .and_then(Value::as_str)
        .unwrap_or("0.0.0")
        .to_owned();
    if generation < GENERATION || semver(&have) < semver(MIN_CORE) {
        return Compat::TooOld {
            have,
            need: MIN_CORE.to_owned(),
        };
    }
    let methods: Vec<&str> = hello
        .get("methods")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .collect();
    let missing: Vec<String> = USES
        .iter()
        .filter(|m| !methods.contains(m))
        .map(|m| m.to_string())
        .collect();
    if !missing.is_empty() {
        return Compat::Missing {
            have,
            methods: missing,
        };
    }
    Compat::Ok
}
