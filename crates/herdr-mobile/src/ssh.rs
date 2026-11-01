//! SSH with a phone key that never enters Rust (task M0.6, Spike B).
//!
//! The private key lives in the Android Keystore. Rust gets its public half as
//! the X.509 SubjectPublicKeyInfo Keystore hands out, and signs through
//! [`SshSigner`]: Kotlin signs with `SHA256withECDSA` and returns the DER
//! signature, which this module turns into the SSH wire format (RFC 5656 §3.1.2:
//! `string "ecdsa-sha2-nistp256"`, `string (mpint r, mpint s)`).
//!
//! A Keystore key that requires user authentication fails to sign once its
//! validity window has passed; the signer reports that as
//! [`SignError::NeedsUserAuth`], the run ends with [`SshError::NeedsUserAuth`],
//! and the app prompts and runs again. Rust never waits on a prompt.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use russh::client::{self, Handle};
use russh::keys::PublicKeyOrCertificate;
use russh::keys::agent::AgentIdentity;
use russh::keys::ssh_encoding::Encode;
use russh::keys::ssh_key::public::{EcdsaPublicKey, KeyData};
use russh::keys::ssh_key::{self, HashAlg, PublicKey};
use russh::{ChannelMsg, SendError};

/// DER prefix of a P-256 SubjectPublicKeyInfo with an uncompressed point:
/// SEQUENCE { SEQUENCE { id-ecPublicKey, prime256v1 }, BIT STRING (66 bytes) }.
const P256_SPKI_PREFIX: [u8; 26] = [
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
    0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
];

#[derive(Debug, uniffi::Error)]
pub enum SignError {
    /// The key needs the user to authenticate (fingerprint or device credential) first.
    NeedsUserAuth,
    Failed {
        detail: String,
    },
}

impl std::fmt::Display for SignError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SignError::NeedsUserAuth => f.write_str("the key needs user authentication"),
            SignError::Failed { detail } => write!(f, "signing failed: {detail}"),
        }
    }
}

impl std::error::Error for SignError {}

impl From<uniffi::UnexpectedUniFFICallbackError> for SignError {
    fn from(e: uniffi::UnexpectedUniFFICallbackError) -> Self {
        SignError::Failed { detail: e.reason }
    }
}

/// Signs with the phone key; implemented in Kotlin over the Android Keystore.
#[uniffi::export(foreign)]
pub trait SshSigner: Send + Sync {
    /// Signs `data` with ECDSA P-256 over SHA-256 and returns the DER signature.
    fn sign(&self, data: Vec<u8>) -> Result<Vec<u8>, SignError>;
}

#[derive(Debug, uniffi::Error)]
pub enum SshError {
    /// The signer needs the user to authenticate; prompt, then run again.
    NeedsUserAuth,
    /// The machine presented a different host key than the pinned one.
    HostKeyMismatch {
        presented: String,
    },
    /// The machine did not accept the key.
    Rejected,
    /// The public key is not a P-256 SubjectPublicKeyInfo, or a signature is malformed.
    Key {
        detail: String,
    },
    Sign {
        detail: String,
    },
    /// The OS refused the socket: on GrapheneOS, the app's Network permission is off.
    NetworkPermissionOff,
    Connect {
        detail: String,
    },
    Timeout,
}

impl std::fmt::Display for SshError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SshError::NeedsUserAuth => f.write_str("the key needs user authentication"),
            SshError::HostKeyMismatch { presented } => {
                write!(f, "host key mismatch: the machine presented {presented}")
            }
            SshError::Rejected => f.write_str("the machine rejected the key"),
            SshError::Key { detail } => write!(f, "key: {detail}"),
            SshError::Sign { detail } => write!(f, "signing failed: {detail}"),
            SshError::NetworkPermissionOff => f.write_str("Network permission is off"),
            SshError::Connect { detail } => write!(f, "connection failed: {detail}"),
            SshError::Timeout => f.write_str("timed out"),
        }
    }
}

impl std::error::Error for SshError {}

/// What one spike run saw.
#[derive(Debug, uniffi::Record)]
pub struct SshRun {
    /// `SHA256:...`, as `ssh-keygen -l` prints it.
    pub host_key_fingerprint: String,
    /// stdout and stderr of the remote command, lossily decoded.
    pub output: String,
    pub exit_status: Option<u32>,
    /// How many times the signer was asked to sign.
    pub signatures: u32,
}

/// The `authorized_keys` key part for a P-256 SubjectPublicKeyInfo:
/// `ecdsa-sha2-nistp256 AAAA... <comment>`.
#[uniffi::export]
pub fn ssh_public_key_line(spki_der: Vec<u8>, comment: String) -> Result<String, SshError> {
    let mut key = public_key_from_spki(&spki_der)?;
    key.set_comment(comment);
    key.to_openssh().map_err(key_error)
}

/// What the app runs on a machine: the desktop core's remote command (herdr-core
/// `machines.rs` `pipe()`, Unix, default session), verbatim. It finds
/// `herdr-core` on PATH, else in `~/.local/bin`, and exits 127 with "herdr-core
/// is not installed here" when neither exists.
pub const REMOTE_COMMAND: &str = r#"sh -c 'p=$(command -v herdr-core) || p=$HOME/.local/bin/herdr-core; [ -x "$p" ] || { echo "herdr-core is not installed here" >&2; exit 127; }; exec "$p" connect'"#;

/// The line to add to the machine's `~/.ssh/authorized_keys`: the key, limited
/// by `restrict` (no pty, forwarding or agent) to [`REMOTE_COMMAND`], whatever
/// command a connection asks for. OpenSSH unescapes only `\"` inside
/// `command="..."`, which is all the command needs.
#[uniffi::export]
pub fn authorized_keys_line(spki_der: Vec<u8>, comment: String) -> Result<String, SshError> {
    if comment.is_empty() || comment.contains(['\n', '\r']) {
        return Err(SshError::Key {
            detail: "the key comment must be one non-empty line".into(),
        });
    }
    let key = ssh_public_key_line(spki_der, comment)?;
    Ok(format!(
        "restrict,command=\"{}\" {key}",
        REMOTE_COMMAND.replace('"', "\\\"")
    ))
}

/// Connects, authenticates with `signer`, runs one command and returns what it
/// printed (the spike's whole SSH path). `host_key_fingerprint` pins the
/// machine; `None` accepts any key and reports it, as a first connect would.
#[uniffi::export]
#[allow(clippy::too_many_arguments)] // the spike's one entry point; M1 replaces it with a session object
pub fn ssh_run_spike(
    host: String,
    port: u16,
    user: String,
    spki_der: Vec<u8>,
    host_key_fingerprint: Option<String>,
    command: String,
    signer: Arc<dyn SshSigner>,
    timeout_ms: u64,
) -> Result<SshRun, SshError> {
    let key = public_key_from_spki(&spki_der)?;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| SshError::Connect {
            detail: e.to_string(),
        })?;
    runtime.block_on(async {
        tokio::time::timeout(
            Duration::from_millis(timeout_ms),
            run(host, port, user, key, host_key_fingerprint, command, signer),
        )
        .await
        .unwrap_or(Err(SshError::Timeout))
    })
}

pub(crate) fn public_key_from_spki(spki_der: &[u8]) -> Result<PublicKey, SshError> {
    let point = spki_der
        .strip_prefix(&P256_SPKI_PREFIX[..])
        .filter(|point| point.len() == 65)
        .ok_or_else(|| SshError::Key {
            detail: "not a P-256 public key with an uncompressed point".into(),
        })?;
    // Checks that the point is on the curve; ssh-key only checks its length.
    p256::PublicKey::from_sec1_bytes(point).map_err(|_| SshError::Key {
        detail: "the point is not on the P-256 curve".into(),
    })?;
    let ecdsa = EcdsaPublicKey::from_sec1_bytes(point).map_err(key_error)?;
    Ok(PublicKey::new(KeyData::Ecdsa(ecdsa), ""))
}

/// The SSH signature blob (`string alg`, `string (mpint r, mpint s)`) for a DER
/// ECDSA P-256 signature.
pub(crate) fn ssh_signature_from_der(der: &[u8]) -> Result<Vec<u8>, SshError> {
    let signature = p256::ecdsa::Signature::from_der(der).map_err(|_| SshError::Key {
        detail: "the signature is not DER ECDSA P-256".into(),
    })?;
    let signature = ssh_key::Signature::try_from(signature).map_err(key_error)?;
    signature.encode_vec().map_err(key_error)
}

/// Maps a failed connect. EPERM and EACCES mean the OS refused the socket,
/// which on GrapheneOS is the per-app Network permission being off.
pub(crate) fn connect_error(e: russh::Error) -> SshError {
    const EPERM: i32 = 1;
    const EACCES: i32 = 13;
    match &e {
        russh::Error::IO(io) if matches!(io.raw_os_error(), Some(EPERM | EACCES)) => {
            SshError::NetworkPermissionOff
        }
        _ => SshError::Connect {
            detail: e.to_string(),
        },
    }
}

fn key_error(e: impl std::fmt::Display) -> SshError {
    SshError::Key {
        detail: e.to_string(),
    }
}

/// A signed-in SSH session.
pub(crate) struct Session {
    pub handle: Handle<HostKeyCheck>,
    /// `SHA256:...` of the key the machine presented.
    pub host_key_fingerprint: String,
    /// How many times the signer was asked to sign.
    pub signatures: Arc<AtomicU32>,
}

pub(crate) enum SessionError {
    Ssh(SshError),
    /// No host key is pinned and an unpinned one is not accepted: the machine
    /// presented this one, and nothing was signed.
    HostKeyUnconfirmed {
        presented: String,
    },
}

impl From<SshError> for SessionError {
    fn from(e: SshError) -> Self {
        SessionError::Ssh(e)
    }
}

/// Connects and signs in with `signer`. `pinned` is the confirmed host key;
/// without one, `accept_unpinned` decides whether any key will do (the
/// spike) or the session stops before signing (the app's first connection).
pub(crate) async fn open_session(
    host: &str,
    port: u16,
    user: &str,
    key: PublicKey,
    pinned: Option<String>,
    accept_unpinned: bool,
    signer: Arc<dyn SshSigner>,
) -> Result<Session, SessionError> {
    let presented = Arc::new(Mutex::new(None));
    let handler = HostKeyCheck {
        pinned: pinned.clone(),
        accept_unpinned,
        presented: presented.clone(),
    };
    let config = Arc::new(client::Config {
        keepalive_interval: Some(Duration::from_secs(15)),
        ..Default::default()
    });
    let connected = client::connect(config, (host, port), handler).await;
    let presented_fingerprint = presented.lock().unwrap().clone();
    let mut handle = match connected {
        Ok(handle) => handle,
        Err(e) => {
            return Err(match (pinned, presented_fingerprint) {
                (Some(pinned), Some(presented)) if pinned != presented => {
                    SshError::HostKeyMismatch { presented }.into()
                }
                (None, Some(presented)) if !accept_unpinned => {
                    SessionError::HostKeyUnconfirmed { presented }
                }
                _ => connect_error(e).into(),
            });
        }
    };
    let host_key_fingerprint = presented_fingerprint.unwrap_or_default();

    let mut callback = CallbackSigner {
        signer,
        signatures: Arc::new(AtomicU32::new(0)),
    };
    let signatures = callback.signatures.clone();
    let auth = handle
        .authenticate_publickey_with(user, key, None, &mut callback)
        .await
        .map_err(|e| match e {
            SignFailure::Sign(SignError::NeedsUserAuth) => SshError::NeedsUserAuth,
            SignFailure::Sign(SignError::Failed { detail }) => SshError::Sign { detail },
            SignFailure::Key(e) => e,
            SignFailure::Send => SshError::Connect {
                detail: "the session closed during authentication".into(),
            },
        })?;
    if !auth.success() {
        return Err(SshError::Rejected.into());
    }
    Ok(Session {
        handle,
        host_key_fingerprint,
        signatures,
    })
}

async fn run(
    host: String,
    port: u16,
    user: String,
    key: PublicKey,
    pinned: Option<String>,
    command: String,
    signer: Arc<dyn SshSigner>,
) -> Result<SshRun, SshError> {
    let mut session = open_session(&host, port, &user, key, pinned, true, signer)
        .await
        .map_err(|e| match e {
            SessionError::Ssh(e) => e,
            // Unreachable: any key is accepted here.
            SessionError::HostKeyUnconfirmed { presented } => {
                SshError::HostKeyMismatch { presented }
            }
        })?;
    let (output, exit_status) =
        exec(&mut session.handle, &command)
            .await
            .map_err(|e| SshError::Connect {
                detail: e.to_string(),
            })?;
    let _ = session
        .handle
        .disconnect(russh::Disconnect::ByApplication, "", "en")
        .await;
    Ok(SshRun {
        host_key_fingerprint: session.host_key_fingerprint,
        output,
        exit_status,
        signatures: session.signatures.load(Ordering::Relaxed),
    })
}

async fn exec(
    session: &mut Handle<HostKeyCheck>,
    command: &str,
) -> Result<(String, Option<u32>), russh::Error> {
    let mut channel = session.channel_open_session().await?;
    channel.exec(true, command).await?;
    let mut output = Vec::new();
    let mut exit_status = None;
    while let Some(msg) = channel.wait().await {
        match msg {
            ChannelMsg::Data { data } | ChannelMsg::ExtendedData { data, .. } => {
                output.extend_from_slice(&data)
            }
            ChannelMsg::ExitStatus { exit_status: code } => exit_status = Some(code),
            _ => {}
        }
    }
    Ok((String::from_utf8_lossy(&output).into_owned(), exit_status))
}

pub(crate) struct HostKeyCheck {
    pinned: Option<String>,
    accept_unpinned: bool,
    presented: Arc<Mutex<Option<String>>>,
}

impl client::Handler for HostKeyCheck {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        server_public_key: &PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        let PublicKeyOrCertificate::PublicKey { key, .. } = server_public_key else {
            return Ok(false);
        };
        let fingerprint = key.fingerprint(HashAlg::Sha256).to_string();
        let accepted = self
            .pinned
            .as_ref()
            .map_or(self.accept_unpinned, |pinned| *pinned == fingerprint);
        *self.presented.lock().unwrap() = Some(fingerprint);
        Ok(accepted)
    }
}

enum SignFailure {
    Sign(SignError),
    Key(SshError),
    Send,
}

impl From<SendError> for SignFailure {
    fn from(_: SendError) -> Self {
        SignFailure::Send
    }
}

struct CallbackSigner {
    signer: Arc<dyn SshSigner>,
    signatures: Arc<AtomicU32>,
}

impl russh::Signer for CallbackSigner {
    type Error = SignFailure;

    #[allow(clippy::manual_async_fn)]
    fn auth_sign(
        &mut self,
        _key: &AgentIdentity,
        _hash_alg: Option<HashAlg>,
        mut to_sign: Vec<u8>,
    ) -> impl Future<Output = Result<Vec<u8>, Self::Error>> + Send {
        let signer = self.signer.clone();
        let signatures = self.signatures.clone();
        async move {
            signatures.fetch_add(1, Ordering::Relaxed);
            // A Keystore signature can take a StrongBox round trip; keep it off the runtime.
            let data = to_sign.clone();
            let der = tokio::task::spawn_blocking(move || signer.sign(data))
                .await
                .map_err(|e| {
                    SignFailure::Sign(SignError::Failed {
                        detail: e.to_string(),
                    })
                })?
                .map_err(SignFailure::Sign)?;
            let blob = ssh_signature_from_der(&der).map_err(SignFailure::Key)?;
            // russh expects the data with the signature appended as an SSH string.
            blob.encode(&mut to_sign)
                .map_err(|e| SignFailure::Key(key_error(e)))?;
            Ok(to_sign)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::SigningKey;
    use p256::ecdsa::signature::Signer as _;
    use p256::pkcs8::EncodePublicKey;
    use russh::keys::signature::Verifier;
    use russh::keys::ssh_encoding::Decode;

    fn test_key(seed: u8) -> SigningKey {
        let mut bytes = [0x11u8; 32];
        bytes[31] = seed;
        SigningKey::from_slice(&bytes).unwrap()
    }

    fn spki(key: &SigningKey) -> Vec<u8> {
        key.verifying_key().to_public_key_der().unwrap().into_vec()
    }

    // GrapheneOS refuses the socket with EPERM (or EACCES) when the app's
    // Network permission is off; Linux and Android share these numbers.
    #[test]
    fn refused_socket_means_network_permission_off() {
        for errno in [1, 13] {
            let e = russh::Error::IO(std::io::Error::from_raw_os_error(errno));
            assert!(
                matches!(connect_error(e), SshError::NetworkPermissionOff),
                "errno {errno}"
            );
        }
        assert_eq!(
            SshError::NetworkPermissionOff.to_string(),
            "Network permission is off"
        );
    }

    #[test]
    fn other_connect_failures_stay_connection_failures() {
        // ECONNREFUSED, ENETUNREACH, and a non-IO russh error.
        for e in [
            russh::Error::IO(std::io::Error::from_raw_os_error(111)),
            russh::Error::IO(std::io::Error::from_raw_os_error(101)),
            russh::Error::Disconnect,
        ] {
            assert!(matches!(connect_error(e), SshError::Connect { .. }));
        }
    }

    #[test]
    fn public_key_line_matches_ssh_keys_rendering() {
        let key = test_key(1);
        let spki = spki(&key);
        let line = ssh_public_key_line(spki.clone(), "herdr-android".into()).unwrap();
        // Parsed back, the line carries exactly the SPKI's point.
        let parsed = PublicKey::from_openssh(&line).unwrap();
        let KeyData::Ecdsa(EcdsaPublicKey::NistP256(point)) = parsed.key_data() else {
            panic!("not a P-256 key: {line}");
        };
        assert_eq!(point.as_bytes(), &spki[26..]);
        assert_eq!(parsed.comment().as_str_lossy(), "herdr-android");
        assert!(line.starts_with("ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYA"));
        assert!(line.ends_with(" herdr-android"));
    }

    // herdr-core 0.1.15 `machines.rs` `pipe()` for a Unix machine and no
    // session, with NOT_HERE filled in, copied by hand: a change upstream must
    // be a deliberate change here.
    const UPSTREAM_PIPE: &str = r#"sh -c 'p=$(command -v herdr-core) || p=$HOME/.local/bin/herdr-core; [ -x "$p" ] || { echo "herdr-core is not installed here" >&2; exit 127; }; exec "$p" connect'"#;

    #[test]
    fn remote_command_is_the_desktop_cores() {
        assert_eq!(REMOTE_COMMAND, UPSTREAM_PIPE);
        // authorized_keys only unescapes \" inside command="...": a backslash
        // or newline in the command would not survive the quoting.
        assert!(!REMOTE_COMMAND.contains(['\\', '\n', '\r']));
    }

    #[test]
    fn authorized_keys_line_restricts_the_key_to_the_remote_command() {
        let key = test_key(1);
        let public = ssh_public_key_line(spki(&key), "herdr-android".into()).unwrap();
        let line = authorized_keys_line(spki(&key), "herdr-android".into()).unwrap();
        assert_eq!(
            line,
            format!(
                "restrict,command=\"{}\" {public}",
                UPSTREAM_PIPE.replace('"', "\\\"")
            )
        );
        assert!(!line.contains('\n'));
    }

    #[test]
    fn authorized_keys_line_refuses_comments_that_break_the_line() {
        for comment in ["two\nlines", "cr\r", ""] {
            assert!(
                authorized_keys_line(spki(&test_key(1)), comment.into()).is_err(),
                "{comment:?}"
            );
        }
        assert!(authorized_keys_line(vec![0x30, 0x00], "herdr-android".into()).is_err());
    }

    #[test]
    fn rejects_keys_that_are_not_p256_spki() {
        let good = spki(&test_key(1));
        for bad in [
            Vec::new(),
            good[..good.len() - 1].to_vec(),
            [good.clone(), vec![0]].concat(),
            // P-384's curve OID in place of P-256's.
            {
                let mut b = good.clone();
                b[24] = 0x22;
                b
            },
        ] {
            assert!(matches!(
                public_key_from_spki(&bad),
                Err(SshError::Key { .. })
            ));
        }
        // Right shape, but the point is off the curve.
        let mut off_curve = good;
        off_curve[90] ^= 1;
        assert!(matches!(
            public_key_from_spki(&off_curve),
            Err(SshError::Key { .. })
        ));
    }

    #[test]
    fn der_signatures_become_verifiable_ssh_signatures() {
        let key = test_key(2);
        let public = public_key_from_spki(&spki(&key)).unwrap();
        // Enough messages that some r and s have their top bit set (an mpint
        // needs a leading zero) and some are shorter than 32 bytes.
        for i in 0u32..300 {
            let message = format!("session data {i}").into_bytes();
            let signature: p256::ecdsa::Signature = key.sign(&message);
            let der = signature.to_der();
            let blob = ssh_signature_from_der(der.as_bytes()).unwrap();
            let signature = ssh_key::Signature::decode(&mut blob.as_slice()).unwrap();
            assert_eq!(signature.algorithm().as_str(), "ecdsa-sha2-nistp256");
            public.key_data().verify(&message, &signature).unwrap();
            assert!(public.key_data().verify(b"other data", &signature).is_err());
        }
    }

    #[test]
    fn rejects_malformed_der() {
        for bad in [&[][..], &[0x30, 0x02, 0x02, 0x00][..], b"not der"] {
            assert!(matches!(
                ssh_signature_from_der(bad),
                Err(SshError::Key { .. })
            ));
        }
    }
}
