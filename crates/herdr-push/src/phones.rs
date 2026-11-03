//! The phones registered in a core's `hdroid-push` document (design.md §5,
//! "Registration"): `{phones: [{endpoint, p256dh, auth, machine, test?}]}`.
//!
//! `machine` is the phone's own name for this machine, echoed in every push so
//! the phone knows which of its machines spoke. `test` is set by "Send test
//! alert"; each new value asks for one test push to that phone.

use serde_json::Value;

use crate::webpush::Subscription;

pub const DOC: &str = "hdroid-push";

#[derive(Clone, Debug)]
pub struct Phone {
    pub subscription: Subscription,
    pub machine: String,
    pub test: Option<Value>,
}

/// The usable entries, and a note for each one that was ignored.
pub fn phones(doc: &Value) -> (Vec<Phone>, Vec<String>) {
    if doc.is_null() {
        return (Vec::new(), Vec::new());
    }
    let Some(entries) = doc["phones"].as_array() else {
        return (Vec::new(), vec![format!("{DOC} unreadable")]);
    };
    let mut phones = Vec::new();
    let mut notes = Vec::new();
    for (i, e) in entries.iter().enumerate() {
        let n = i + 1;
        if !e.is_object() {
            notes.push(format!("ignored phone {n}: not an object"));
            continue;
        }
        let field = |k: &str| e[k].as_str().filter(|v| !v.is_empty()).map(str::to_owned);
        let (Some(endpoint), Some(p256dh), Some(auth), Some(machine)) = (
            field("endpoint"),
            field("p256dh"),
            field("auth"),
            field("machine"),
        ) else {
            let missing = ["endpoint", "p256dh", "auth", "machine"]
                .into_iter()
                .find(|k| field(k).is_none())
                .unwrap_or_default();
            notes.push(format!("ignored phone {n}: missing {missing}"));
            continue;
        };
        phones.push(Phone {
            subscription: Subscription {
                endpoint,
                p256dh,
                auth,
            },
            machine,
            test: Some(&e["test"]).filter(|t| !t.is_null()).cloned(),
        });
    }
    (phones, notes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn entry(endpoint: &str) -> Value {
        json!({"endpoint": endpoint, "p256dh": "BAAA", "auth": "AAAA", "machine": "workstation"})
    }

    #[test]
    fn complete_entries_are_read() {
        let mut second = entry("https://ntfy.example.org/upB?up=1");
        second["test"] = json!("1730650000000");
        let (got, notes) =
            phones(&json!({"phones": [entry("https://ntfy.example.org/upA?up=1"), second]}));
        assert!(notes.is_empty(), "{notes:?}");
        assert_eq!(got.len(), 2);
        assert_eq!(
            got[0].subscription.endpoint,
            "https://ntfy.example.org/upA?up=1"
        );
        assert_eq!(got[0].subscription.p256dh, "BAAA");
        assert_eq!(got[0].subscription.auth, "AAAA");
        assert_eq!(got[0].machine, "workstation");
        assert_eq!(got[0].test, None);
        assert_eq!(got[1].test, Some(json!("1730650000000")));
    }

    #[test]
    fn incomplete_entries_are_skipped_with_a_note() {
        let mut no_keys = entry("https://ntfy.example.org/upB");
        no_keys.as_object_mut().unwrap().remove("p256dh");
        let mut blank = entry("https://ntfy.example.org/upC");
        blank["machine"] = json!("");
        let doc = json!({"phones": [entry("https://ntfy.example.org/upA"), no_keys, blank, 7]});
        let (got, notes) = phones(&doc);
        assert_eq!(got.len(), 1);
        assert_eq!(
            notes,
            vec![
                "ignored phone 2: missing p256dh",
                "ignored phone 3: missing machine",
                "ignored phone 4: not an object",
            ]
        );
    }

    #[test]
    fn no_document_means_no_phones() {
        assert_eq!(phones(&Value::Null).0.len(), 0);
        assert!(phones(&Value::Null).1.is_empty());
        let (got, notes) = phones(&json!("junk"));
        assert!(got.is_empty());
        assert_eq!(notes, vec!["hdroid-push unreadable"]);
    }
}
