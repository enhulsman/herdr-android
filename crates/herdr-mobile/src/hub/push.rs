//! This phone's alert registration on each machine (alerts spec, "The phone
//! registers itself in a core document", "Delivery status is shown as what it
//! is", "A test alert proves the path").
//!
//! The phone writes its push endpoint and keys, with its own name for the
//! machine, into the machine's `hdroid-push` document, replacing the entry it
//! wrote before; herdr-push reads them there. herdr-push answers in
//! `hdroid-push-status`, which the phone reads back for the machine card. Only
//! a phone with a registration (or one to take back) talks to these documents.

use serde_json::{Value, json};

pub const DOC: &str = "hdroid-push";
pub const STATUS_DOC: &str = "hdroid-push-status";

/// What the UnifiedPush distributor gave this phone.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PushRegistration {
    pub endpoint: String,
    pub p256dh: String,
    pub auth: String,
}

/// Whether this machine has the phone's current endpoint.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum AlertsRegistration {
    /// Written at its last connection: herdr-push there has it.
    Registered,
    /// Never written yet: it is written at the next connection.
    NotYet,
    /// The machine still has an older endpoint: "alerts not registered since
    /// endpoint change" until the next connection rewrites it.
    EndpointChanged,
}

/// What herdr-push last wrote in `hdroid-push-status`, as of the last connection.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PushStatusView {
    /// herdr-push's version, if it ever wrote a status.
    pub version: Option<String>,
    /// The last push ntfy answered (accepted or refused), ms since the epoch.
    pub last_push_at_ms: Option<u64>,
    pub last_push_status: Option<u16>,
    /// What happened to this phone's endpoint last: "accepted 200", "refused
    /// 403", "failed: ...", "ignored: host not allowed".
    pub endpoint_status: Option<String>,
    pub notes: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AlertsView {
    pub registration: AlertsRegistration,
    /// None until read at a connection; also when herdr-push never wrote one.
    pub status: Option<PushStatusView>,
}

/// The `hdroid-push` value to write, or None when it already says the same.
///
/// The phone's entries are the one for its current endpoint and the one it
/// wrote before (`previous`); both are replaced by one entry for `me`, or
/// removed when `me` is None. Other phones' entries are kept as they are.
/// `test` asks herdr-push for one test push; an unchanged entry keeps its own.
pub fn registration_doc(
    doc: &Value,
    me: Option<&PushRegistration>,
    machine: &str,
    previous: Option<&str>,
    test: Option<&str>,
) -> Option<Value> {
    let phones = doc["phones"].as_array();
    let valid = phones.is_some();
    let phones: Vec<Value> = phones.cloned().unwrap_or_default();
    let endpoint_of = |e: &Value| e["endpoint"].as_str().map(str::to_owned);
    let mine = |e: &Value| {
        endpoint_of(e).is_some_and(|ep| {
            me.is_some_and(|m| m.endpoint == ep) || previous.is_some_and(|p| p == ep)
        })
    };
    let (ours, mut rest): (Vec<Value>, Vec<Value>) = phones.into_iter().partition(|e| mine(e));
    let Some(me) = me else {
        // Taking the entries back: only a write when there were any.
        if ours.is_empty() {
            return None;
        }
        return Some(with_phones(doc, rest));
    };
    let current = ours
        .iter()
        .find(|e| endpoint_of(e).as_deref() == Some(me.endpoint.as_str()));
    let mut entry = json!({"endpoint": me.endpoint, "p256dh": me.p256dh, "auth": me.auth,
                           "machine": machine});
    match test
        .map(Value::from)
        .or_else(|| current.map(|c| c["test"].clone()))
    {
        Some(t) if !t.is_null() => entry["test"] = t,
        _ => {}
    }
    if valid && ours.len() == 1 && ours[0] == entry {
        return None;
    }
    rest.push(entry);
    Some(with_phones(doc, rest))
}

/// The document with its phone list replaced; other keys are kept.
fn with_phones(doc: &Value, phones: Vec<Value>) -> Value {
    let mut out = if doc.is_object() {
        doc.clone()
    } else {
        json!({})
    };
    out["phones"] = Value::Array(phones);
    out
}

/// Reads `hdroid-push-status` for this phone's endpoint. Null (never written)
/// is None; fields that are missing or of the wrong type are left empty.
pub fn status_view(doc: &Value, endpoint: Option<&str>) -> Option<PushStatusView> {
    if doc.is_null() {
        return None;
    }
    let text = |v: &Value| v.as_str().map(str::to_owned);
    Some(PushStatusView {
        version: text(&doc["version"]),
        last_push_at_ms: doc["last_push_at"].as_u64(),
        last_push_status: doc["last_push_status"]
            .as_u64()
            .and_then(|s| u16::try_from(s).ok()),
        endpoint_status: endpoint.and_then(|e| text(&doc["endpoints"][e]["status"])),
        notes: doc["notes"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(text)
            .collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn me(endpoint: &str) -> PushRegistration {
        PushRegistration {
            endpoint: endpoint.into(),
            p256dh: "BKEY".into(),
            auth: "AUTH".into(),
        }
    }

    const NEW: &str = "https://ntfy.example.org/upNEW?up=1";
    const OLD: &str = "https://ntfy.example.org/upOLD?up=1";
    const OTHER: &str = "https://ntfy.example.org/upOTHER?up=1";

    fn entry(endpoint: &str) -> Value {
        json!({"endpoint": endpoint, "p256dh": "BKEY", "auth": "AUTH", "machine": "workstation"})
    }

    #[test]
    fn a_first_registration_adds_the_entry() {
        let got = registration_doc(&Value::Null, Some(&me(NEW)), "workstation", None, None);
        assert_eq!(got, Some(json!({"phones": [entry(NEW)]})));
    }

    #[test]
    fn a_new_endpoint_replaces_the_old_entry_and_keeps_other_phones() {
        let other =
            json!({"endpoint": OTHER, "p256dh": "X", "auth": "Y", "machine": "desk", "test": "5"});
        let doc = json!({"phones": [entry(OLD), other.clone()]});
        let got = registration_doc(&doc, Some(&me(NEW)), "workstation", Some(OLD), None);
        assert_eq!(got, Some(json!({"phones": [other, entry(NEW)]})));
    }

    #[test]
    fn an_unchanged_entry_is_not_written_again() {
        let mut tested = entry(NEW);
        tested["test"] = json!("17");
        let doc = json!({"phones": [tested]});
        assert_eq!(
            registration_doc(&doc, Some(&me(NEW)), "workstation", Some(NEW), None),
            None
        );
        // New keys or a renamed machine are a change.
        let mut renamed = entry(NEW);
        renamed["machine"] = json!("wsl");
        let got = registration_doc(&doc, Some(&me(NEW)), "wsl", Some(NEW), None).unwrap();
        assert_eq!(got["phones"][0]["machine"], "wsl");
        assert_eq!(got["phones"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn a_test_request_sets_a_new_test_value() {
        let doc = json!({"phones": [entry(NEW)]});
        let got = registration_doc(
            &doc,
            Some(&me(NEW)),
            "workstation",
            Some(NEW),
            Some("1730650000000"),
        );
        let mut want = entry(NEW);
        want["test"] = json!("1730650000000");
        assert_eq!(got, Some(json!({"phones": [want]})));
    }

    #[test]
    fn turning_alerts_off_takes_the_entries_back() {
        let doc = json!({"phones": [entry(OLD), entry(NEW), entry(OTHER)]});
        let got = registration_doc(&doc, None, "workstation", Some(OLD), None);
        assert_eq!(got, Some(json!({"phones": [entry(NEW), entry(OTHER)]})));
        assert_eq!(
            registration_doc(&Value::Null, None, "workstation", Some(OLD), None),
            None
        );
    }

    #[test]
    fn a_broken_document_is_replaced_by_this_phone_alone() {
        for broken in [json!("junk"), json!({"phones": 4}), json!([1, 2])] {
            assert_eq!(
                registration_doc(&broken, Some(&me(NEW)), "workstation", None, None),
                Some(json!({"phones": [entry(NEW)]})),
                "{broken}"
            );
        }
        // Junk entries in a good list stay: they are not this phone's to judge.
        let doc = json!({"phones": [7, entry(OLD)]});
        assert_eq!(
            registration_doc(&doc, Some(&me(NEW)), "workstation", Some(OLD), None),
            Some(json!({"phones": [7, entry(NEW)]}))
        );
    }

    #[test]
    fn the_status_is_read_for_this_phone() {
        let doc = json!({"version": "0.0.4", "last_push_at": 1_730_650_000_000u64,
                         "last_push_status": 403,
                         "endpoints": {NEW: {"status": "refused 403", "at": 1}, OTHER: {"status": "accepted 200"}},
                         "notes": ["no layout document", 3]});
        assert_eq!(
            status_view(&doc, Some(NEW)),
            Some(PushStatusView {
                version: Some("0.0.4".into()),
                last_push_at_ms: Some(1_730_650_000_000),
                last_push_status: Some(403),
                endpoint_status: Some("refused 403".into()),
                notes: vec!["no layout document".into()],
            })
        );
        assert_eq!(status_view(&doc, Some(OLD)).unwrap().endpoint_status, None);
        assert_eq!(status_view(&Value::Null, Some(NEW)), None);
        let empty = status_view(&json!("junk"), Some(NEW)).unwrap();
        assert_eq!(empty.version, None);
        assert!(empty.notes.is_empty());
    }
}
