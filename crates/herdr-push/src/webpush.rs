//! Web push encryption (RFC 8291, `aes128gcm`) and delivery to a UnifiedPush
//! endpoint on ntfy (design.md §5, "Payload" and "Delivery").
//!
//! The phone registers `{endpoint, p256dh, auth}`; the watcher encrypts each
//! payload to that key, so ntfy only ever sees ciphertext.

use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

/// ntfy turns bigger messages into attachments, which UnifiedPush does not
/// deliver; the design caps the encrypted body here.
pub const MAX_BODY: usize = 4096;

/// What a phone registered: its push endpoint and web push keys, base64url as
/// the UnifiedPush connector hands them out.
#[derive(Debug, Clone)]
pub struct Subscription {
    pub endpoint: String,
    pub p256dh: String,
    pub auth: String,
}

#[derive(Debug)]
pub enum PushError {
    /// `p256dh` or `auth` is not base64url, or not a P-256 key / 16-byte secret.
    Keys(String),
    /// The encrypted body would exceed [`MAX_BODY`].
    TooLarge {
        body: usize,
    },
    Encrypt(String),
    Http(String),
}

impl std::fmt::Display for PushError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PushError::Keys(detail) => write!(f, "subscription keys: {detail}"),
            PushError::TooLarge { body } => {
                write!(f, "encrypted body of {body} bytes exceeds {MAX_BODY}")
            }
            PushError::Encrypt(detail) => write!(f, "encryption failed: {detail}"),
            PushError::Http(detail) => write!(f, "delivery failed: {detail}"),
        }
    }
}

impl std::error::Error for PushError {}

/// Decodes base64url with or without padding, as subscriptions arrive both ways.
pub fn base64url(value: &str) -> Result<Vec<u8>, PushError> {
    URL_SAFE_NO_PAD
        .decode(value.trim_end_matches('='))
        .map_err(|e| PushError::Keys(e.to_string()))
}

/// The `aes128gcm` body for `payload` (header, one record, padding delimiter).
pub fn encrypt(subscription: &Subscription, payload: &[u8]) -> Result<Vec<u8>, PushError> {
    let p256dh = base64url(&subscription.p256dh)?;
    let auth = base64url(&subscription.auth)?;
    if p256dh.len() != 65 || p256dh[0] != 0x04 {
        return Err(PushError::Keys(
            "p256dh is not an uncompressed P-256 point".into(),
        ));
    }
    if auth.len() != 16 {
        return Err(PushError::Keys("auth is not 16 bytes".into()));
    }
    // ece pads the plaintext to a multiple of 128 bytes (hiding its length) and
    // splits it into 4096-byte records, so the size is known only afterwards.
    let body =
        ece::encrypt(&p256dh, &auth, payload).map_err(|e| PushError::Encrypt(e.to_string()))?;
    if body.len() > MAX_BODY {
        return Err(PushError::TooLarge { body: body.len() });
    }
    Ok(body)
}

/// What ntfy answered.
#[derive(Debug)]
pub struct Accepted {
    pub status: u16,
    pub body: String,
}

/// POSTs an encrypted body to the endpoint with the ntfy access token. No
/// redirects, 10 s timeout; any answer, including an error status, is returned.
pub fn post(endpoint: &str, token: &str, body: Vec<u8>) -> Result<Accepted, PushError> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .max_redirects(0)
        .timeout_global(Some(Duration::from_secs(10)))
        .http_status_as_error(false)
        // The system's TLS and trust store; ece already links OpenSSL.
        .tls_config(
            ureq::tls::TlsConfig::builder()
                .provider(ureq::tls::TlsProvider::NativeTls)
                .root_certs(ureq::tls::RootCerts::PlatformVerifier)
                .build(),
        )
        .build()
        .into();
    let mut response = agent
        .post(endpoint)
        .header("Authorization", &format!("Bearer {token}"))
        .header("Content-Encoding", "aes128gcm")
        .header("TTL", "86400")
        .header("Urgency", "high")
        .send(&body[..])
        .map_err(|e| PushError::Http(e.to_string()))?;
    let status = response.status().as_u16();
    let body = response
        .body_mut()
        .with_config()
        .limit(16 * 1024)
        .read_to_string()
        .unwrap_or_default();
    Ok(Accepted { status, body })
}

#[cfg(test)]
mod tests {
    use super::*;

    // RFC 8291 §5 and Appendix A.
    const UA_PUBLIC: &str =
        "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    const UA_PRIVATE: &str = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    const AUTH: &str = "BTBZMqHH6r4Tts7J_aSIgg";
    // The header is 86 bytes, so header and ciphertext are base64url apart.
    const HEADER: &str = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml\
                          mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    const CIPHERTEXT: &str = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEs\
                              bI_0LpXMuGvnzQ";
    const PLAINTEXT: &[u8] = b"When I grow up, I want to be a watermelon";

    fn receiver() -> ece::EcKeyComponents {
        ece::EcKeyComponents::new(
            base64url(UA_PRIVATE).unwrap(),
            base64url(UA_PUBLIC).unwrap(),
        )
    }

    fn subscription() -> Subscription {
        Subscription {
            endpoint: "https://ntfy.example.org/upTEST?up=1".into(),
            p256dh: UA_PUBLIC.into(),
            auth: AUTH.into(),
        }
    }

    #[test]
    fn decrypts_the_rfc_8291_example() {
        let message = [base64url(HEADER).unwrap(), base64url(CIPHERTEXT).unwrap()].concat();
        assert_eq!(message.len(), 86 + PLAINTEXT.len() + 1 + 16);
        let plain = ece::decrypt(&receiver(), &base64url(AUTH).unwrap(), &message).unwrap();
        assert_eq!(plain, PLAINTEXT);
    }

    #[test]
    fn encrypts_to_the_subscription_and_round_trips() {
        let payload = br#"{"v":1,"type":"test","machine":"workstation","at":1}"#;
        let body = encrypt(&subscription(), payload).unwrap();
        // Padded to 128 bytes: a short payload's length does not show.
        assert_eq!(body.len(), 86 + 128 + 16);
        assert_eq!(encrypt(&subscription(), b"{}").unwrap().len(), body.len());
        // Header: 16-byte salt, record size 4096, key id = the sender's 65-byte key.
        assert_eq!(&body[16..20], &4096u32.to_be_bytes());
        assert_eq!(body[20], 65);
        let plain = ece::decrypt(&receiver(), &base64url(AUTH).unwrap(), &body).unwrap();
        assert_eq!(plain, payload);
        // A fresh sender key and salt each time.
        assert_ne!(encrypt(&subscription(), payload).unwrap()[..86], body[..86]);
    }

    #[test]
    fn every_byte_value_survives() {
        let payload: Vec<u8> = (0..=255u8).cycle().take(3000).collect();
        let body = encrypt(&subscription(), &payload).unwrap();
        let plain = ece::decrypt(&receiver(), &base64url(AUTH).unwrap(), &body).unwrap();
        assert_eq!(plain, payload);
    }

    #[test]
    fn the_body_cap_is_exact() {
        // One record holds 4080 bytes of padded plaintext; 3968 is the largest
        // multiple of 128 that fits, and padding needs at least one byte.
        let largest = 3967;
        let body = encrypt(&subscription(), &vec![b'x'; largest]).unwrap();
        assert_eq!(body.len(), 86 + 3968 + 16);
        assert!(matches!(
            encrypt(&subscription(), &vec![b'x'; largest + 1]),
            Err(PushError::TooLarge { body }) if body > MAX_BODY
        ));
    }

    #[test]
    fn rejects_bad_subscription_keys() {
        let padded = Subscription {
            auth: format!("{AUTH}=="),
            ..subscription()
        };
        assert!(encrypt(&padded, b"x").is_ok());
        for bad in [
            Subscription {
                p256dh: "not base64!".into(),
                ..subscription()
            },
            Subscription {
                p256dh: AUTH.into(),
                ..subscription()
            },
            Subscription {
                auth: UA_PRIVATE.into(),
                ..subscription()
            },
        ] {
            assert!(matches!(encrypt(&bad, b"x"), Err(PushError::Keys(_))));
        }
    }
}
