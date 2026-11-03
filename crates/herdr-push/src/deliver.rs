//! What a push says and where it may go (alerts spec, "Alerts are encrypted,
//! minimal, and sent only to the allowlisted host").
//!
//! The payload carries only `{v, type, machine, terminal?, name?, state?, kind?,
//! at}`: never screen text or the program's `msg`. A push goes only to an
//! endpoint whose scheme, host and port match an allowlist entry, over https;
//! the ntfy token goes with it, so it reaches no other host.

use serde_json::{Value, json};

use crate::phones::Phone;
use crate::rules::Word;
use crate::webpush;

/// One push, before it is addressed to a phone.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Push {
    Alert {
        terminal: String,
        name: String,
        state: Word,
        kind: Option<String>,
    },
    Cleared {
        terminal: String,
    },
    Canary,
    Test,
}

/// The plaintext for one phone: `machine` is that phone's name for this machine.
pub fn payload(push: &Push, machine: &str, at_ms: u64) -> Value {
    let mut p = json!({"v": 1, "machine": machine, "at": at_ms});
    match push {
        Push::Alert {
            terminal,
            name,
            state,
            kind,
        } => {
            p["type"] = "alert".into();
            p["terminal"] = terminal.as_str().into();
            p["name"] = name.as_str().into();
            p["state"] = state.as_str().into();
            if let Some(kind) = kind {
                p["kind"] = kind.as_str().into();
            }
        }
        Push::Cleared { terminal } => {
            p["type"] = "cleared".into();
            p["terminal"] = terminal.as_str().into();
        }
        Push::Canary => p["type"] = "canary".into(),
        Push::Test => p["type"] = "test".into(),
    }
    p
}

/// Host and port of an https URL without user info, lower-cased.
fn origin(url: &str) -> Option<(String, u16, ureq::http::Uri)> {
    let uri: ureq::http::Uri = url.parse().ok()?;
    if uri.scheme_str() != Some("https") {
        return None;
    }
    let authority = uri.authority()?;
    if authority.as_str().contains('@') {
        return None;
    }
    let host = authority.host().to_ascii_lowercase();
    if host.is_empty() {
        return None;
    }
    let port = authority.port_u16().unwrap_or(443);
    Some((host, port, uri))
}

/// `https://host[:port]` origins the watcher may POST to.
#[derive(Clone, Debug)]
pub struct Allowlist {
    origins: Vec<(String, u16)>,
}

impl Allowlist {
    /// Each entry is an https origin (`https://ntfy.example.org`), no path.
    pub fn parse(entries: &[String]) -> Result<Allowlist, String> {
        let mut origins = Vec::new();
        for entry in entries {
            let bad = || format!("{entry} is not an https origin");
            let (host, port, uri) = origin(entry).ok_or_else(bad)?;
            let bare = matches!(uri.path(), "" | "/") && uri.query().is_none();
            if !bare {
                return Err(bad());
            }
            origins.push((host, port));
        }
        if origins.is_empty() {
            return Err("at least one https origin is needed".into());
        }
        Ok(Allowlist { origins })
    }

    pub fn allows(&self, endpoint: &str) -> bool {
        origin(endpoint).is_some_and(|(host, port, _)| {
            self.origins.iter().any(|(h, p)| *h == host && *p == port)
        })
    }
}

/// Where the body goes: the real thing is [`webpush::post`].
pub trait Post {
    /// The HTTP status ntfy answered, or why there was no answer.
    fn post(&mut self, endpoint: &str, token: &str, body: Vec<u8>) -> Result<u16, String>;
}

pub struct Https;

impl Post for Https {
    fn post(&mut self, endpoint: &str, token: &str, body: Vec<u8>) -> Result<u16, String> {
        webpush::post(endpoint, token, body)
            .map(|a| a.status)
            .map_err(|e| e.to_string())
    }
}

/// What happened for one phone.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Outcome {
    /// ntfy answered (2xx accepted it; anything else refused it).
    Answered(u16),
    Failed(String),
    /// Not on the allowlist: nothing was sent there.
    NotAllowed,
}

/// Encrypts `push` to each phone and POSTs it where allowed, in order.
pub fn send(
    push: &Push,
    phones: &[Phone],
    allow: &Allowlist,
    token: &str,
    post: &mut dyn Post,
    at_ms: u64,
) -> Vec<(String, Outcome)> {
    phones
        .iter()
        .map(|phone| {
            let endpoint = phone.subscription.endpoint.clone();
            if !allow.allows(&endpoint) {
                return (endpoint, Outcome::NotAllowed);
            }
            let plain = payload(push, &phone.machine, at_ms).to_string();
            let outcome = match webpush::encrypt(&phone.subscription, plain.as_bytes()) {
                Err(e) => Outcome::Failed(e.to_string()),
                Ok(body) => match post.post(&endpoint, token, body) {
                    Ok(status) => Outcome::Answered(status),
                    Err(e) => Outcome::Failed(e),
                },
            };
            (endpoint, outcome)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::webpush::{Subscription, base64url};

    // RFC 8291 Appendix A receiver keys, so the test can decrypt what was sent.
    const UA_PUBLIC: &str =
        "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    const UA_PRIVATE: &str = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    const AUTH: &str = "BTBZMqHH6r4Tts7J_aSIgg";

    fn phone(endpoint: &str) -> Phone {
        Phone {
            subscription: Subscription {
                endpoint: endpoint.into(),
                p256dh: UA_PUBLIC.into(),
                auth: AUTH.into(),
            },
            machine: "workstation".into(),
            test: None,
        }
    }

    fn allow() -> Allowlist {
        Allowlist::parse(&["https://ntfy.example.org".into()]).unwrap()
    }

    #[derive(Default)]
    struct Recorder {
        sent: Vec<(String, String, Vec<u8>)>,
        answer: Option<Result<u16, String>>,
    }

    impl Post for Recorder {
        fn post(&mut self, endpoint: &str, token: &str, body: Vec<u8>) -> Result<u16, String> {
            self.sent.push((endpoint.into(), token.into(), body));
            self.answer.clone().unwrap_or(Ok(200))
        }
    }

    fn decrypt(body: &[u8]) -> Value {
        let keys = ece::EcKeyComponents::new(
            base64url(UA_PRIVATE).unwrap(),
            base64url(UA_PUBLIC).unwrap(),
        );
        let plain = ece::decrypt(&keys, &base64url(AUTH).unwrap(), body).unwrap();
        serde_json::from_slice(&plain).unwrap()
    }

    fn blocked() -> Push {
        Push::Alert {
            terminal: "t3".into(),
            name: "Claude Code".into(),
            state: Word::Blocked,
            kind: Some("permission".into()),
        }
    }

    #[test]
    fn payloads_carry_only_the_listed_fields() {
        assert_eq!(
            payload(&blocked(), "workstation", 1_730_650_000_000),
            json!({"v": 1, "type": "alert", "machine": "workstation", "terminal": "t3",
                   "name": "Claude Code", "state": "blocked", "kind": "permission",
                   "at": 1_730_650_000_000u64})
        );
        let done = Push::Alert {
            terminal: "t3".into(),
            name: "Codex".into(),
            state: Word::Done,
            kind: None,
        };
        assert_eq!(
            payload(&done, "w", 5),
            json!({"v": 1, "type": "alert", "machine": "w", "terminal": "t3",
                   "name": "Codex", "state": "done", "at": 5})
        );
        assert_eq!(
            payload(
                &Push::Cleared {
                    terminal: "t3".into()
                },
                "w",
                5
            ),
            json!({"v": 1, "type": "cleared", "machine": "w", "terminal": "t3", "at": 5})
        );
        assert_eq!(
            payload(&Push::Canary, "w", 5),
            json!({"v": 1, "type": "canary", "machine": "w", "at": 5})
        );
        assert_eq!(
            payload(&Push::Test, "w", 5),
            json!({"v": 1, "type": "test", "machine": "w", "at": 5})
        );
    }

    #[test]
    fn only_https_on_an_allowed_host_and_port_passes() {
        let a = allow();
        assert!(a.allows("https://ntfy.example.org/upAbC?up=1"));
        assert!(a.allows("https://NTFY.example.org/upAbC"));
        assert!(a.allows("https://ntfy.example.org:443/upAbC"));
        for bad in [
            "http://ntfy.example.org/upAbC",
            "https://ntfy.example.org:8443/upAbC",
            "https://ntfy.example.org.evil.test/upAbC",
            "https://evil.test/ntfy.example.org/upAbC",
            "https://ntfy.example.org@evil.test/upAbC",
            "https://user:pw@ntfy.example.org/upAbC",
            "https://sub.ntfy.example.org/upAbC",
            "ntfy.example.org/upAbC",
            "",
        ] {
            assert!(!a.allows(bad), "{bad}");
        }
    }

    #[test]
    fn the_allowlist_takes_https_origins_only() {
        assert!(Allowlist::parse(&["https://ntfy.example.org:8443".into()]).is_ok());
        for bad in [
            "http://ntfy.example.org",
            "https://ntfy.example.org/path",
            "ntfy.example.org",
            "",
        ] {
            assert!(Allowlist::parse(&[bad.into()]).is_err(), "{bad}");
        }
        assert!(Allowlist::parse(&[]).is_err());
    }

    #[test]
    fn a_foreign_endpoint_gets_neither_the_post_nor_the_token() {
        let phones = [
            phone("https://evil.test/upX"),
            phone("https://ntfy.example.org/upA?up=1"),
        ];
        let mut rec = Recorder::default();
        let got = send(&blocked(), &phones, &allow(), "tk_secret", &mut rec, 9);
        assert_eq!(
            got,
            vec![
                ("https://evil.test/upX".to_owned(), Outcome::NotAllowed),
                (
                    "https://ntfy.example.org/upA?up=1".to_owned(),
                    Outcome::Answered(200)
                ),
            ]
        );
        assert_eq!(rec.sent.len(), 1);
        let (endpoint, token, body) = &rec.sent[0];
        assert_eq!(endpoint, "https://ntfy.example.org/upA?up=1");
        assert_eq!(token, "tk_secret");
        // What ntfy sees is ciphertext; the phone's keys open it to the payload.
        assert!(!String::from_utf8_lossy(body).contains("Claude"));
        assert_eq!(decrypt(body), payload(&blocked(), "workstation", 9));
    }

    #[test]
    fn refusals_and_failures_are_reported_per_phone() {
        let phones = [phone("https://ntfy.example.org/upA")];
        let mut rec = Recorder {
            answer: Some(Ok(403)),
            ..Default::default()
        };
        let got = send(&Push::Canary, &phones, &allow(), "t", &mut rec, 1);
        assert_eq!(got[0].1, Outcome::Answered(403));
        rec.answer = Some(Err("timed out".into()));
        let got = send(&Push::Canary, &phones, &allow(), "t", &mut rec, 1);
        assert_eq!(got[0].1, Outcome::Failed("timed out".into()));
        // Keys that do not encrypt are a failure for that phone only.
        let mut broken = phone("https://ntfy.example.org/upB");
        broken.subscription.p256dh = "BAAA".into();
        let got = send(
            &Push::Canary,
            &[broken],
            &allow(),
            "t",
            &mut Recorder::default(),
            1,
        );
        assert!(matches!(&got[0].1, Outcome::Failed(e) if e.contains("keys")));
    }
}
