//! The `hdroid-push-status` document (alerts spec, "Delivery status is shown as
//! what it is"): what ntfy last answered, per phone, and notes. "Accepted" is
//! all the watcher can know; it never claims a push was delivered.

use std::collections::{BTreeMap, BTreeSet};

use serde_json::{Value, json};

use crate::deliver::Outcome;

pub const DOC: &str = "hdroid-push-status";

/// Rewritten at least this often, so a stale status shows its age.
pub const REFRESH_MS: u64 = 30 * 60 * 1000;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Status {
    /// The time and HTTP status of the last push ntfy accepted or refused.
    last: Option<(u64, u16)>,
    /// Per endpoint: what happened last, and when.
    endpoints: BTreeMap<String, (String, u64)>,
    notes: BTreeSet<String>,
}

impl Status {
    /// Records what one push did; true if the document changed.
    pub fn record(&mut self, outcomes: &[(String, Outcome)], at_ms: u64) -> bool {
        let before = self.clone();
        for (endpoint, outcome) in outcomes {
            let said = match outcome {
                Outcome::Answered(status) => {
                    self.last = Some((at_ms, *status));
                    let word = if (200..300).contains(status) {
                        "accepted"
                    } else {
                        "refused"
                    };
                    format!("{word} {status}")
                }
                Outcome::Failed(e) => format!("failed: {e}"),
                Outcome::NotAllowed => "ignored: host not allowed".to_owned(),
            };
            self.endpoints.insert(endpoint.clone(), (said, at_ms));
        }
        *self != before
    }

    /// Forgets endpoints no phone registers any more.
    pub fn keep_endpoints<'a>(&mut self, endpoints: impl IntoIterator<Item = &'a str>) -> bool {
        let keep: BTreeSet<&str> = endpoints.into_iter().collect();
        let before = self.endpoints.len();
        self.endpoints.retain(|e, _| keep.contains(e.as_str()));
        self.endpoints.len() != before
    }

    /// Replaces the notes; true if they changed.
    pub fn set_notes(&mut self, notes: impl IntoIterator<Item = String>) -> bool {
        let notes: BTreeSet<String> = notes.into_iter().collect();
        let changed = notes != self.notes;
        self.notes = notes;
        changed
    }

    pub fn doc(&self) -> Value {
        let endpoints: serde_json::Map<String, Value> = self
            .endpoints
            .iter()
            .map(|(e, (status, at))| (e.clone(), json!({"status": status, "at": at})))
            .collect();
        json!({
            "version": env!("CARGO_PKG_VERSION"),
            "last_push_at": self.last.map(|(at, _)| at),
            "last_push_status": self.last.map(|(_, s)| s),
            "endpoints": endpoints,
            "notes": self.notes,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const A: &str = "https://ntfy.example.org/upA";
    const B: &str = "https://evil.test/upB";

    #[test]
    fn an_empty_status_names_the_version() {
        assert_eq!(
            Status::default().doc(),
            json!({"version": env!("CARGO_PKG_VERSION"), "last_push_at": null,
                   "last_push_status": null, "endpoints": {}, "notes": []})
        );
    }

    #[test]
    fn answers_failures_and_ignored_endpoints_show_as_what_they_are() {
        let mut s = Status::default();
        assert!(s.record(
            &[
                (A.into(), Outcome::Answered(200)),
                (B.into(), Outcome::NotAllowed)
            ],
            10
        ));
        assert_eq!(
            s.doc(),
            json!({"version": env!("CARGO_PKG_VERSION"), "last_push_at": 10, "last_push_status": 200,
                   "endpoints": {
                       A: {"status": "accepted 200", "at": 10},
                       B: {"status": "ignored: host not allowed", "at": 10},
                   },
                   "notes": []})
        );
        assert!(s.record(&[(A.into(), Outcome::Answered(403))], 20));
        assert_eq!(s.doc()["last_push_status"], 403);
        assert_eq!(s.doc()["endpoints"][A]["status"], "refused 403");
        // No answer at all leaves the last answer as it was.
        assert!(s.record(&[(A.into(), Outcome::Failed("timed out".into()))], 30));
        assert_eq!(s.doc()["last_push_at"], 20);
        assert_eq!(s.doc()["endpoints"][A]["status"], "failed: timed out");
    }

    #[test]
    fn notes_change_only_when_they_differ() {
        let mut s = Status::default();
        assert!(s.set_notes(["layout unreadable".to_owned()]));
        assert!(!s.set_notes(["layout unreadable".to_owned()]));
        assert_eq!(s.doc()["notes"], json!(["layout unreadable"]));
        assert!(s.set_notes([]));
    }
}
