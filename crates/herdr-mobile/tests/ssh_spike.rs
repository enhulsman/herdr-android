//! The spike's SSH path against a throwaway OpenSSH server on 127.0.0.1 with
//! its own host key and `authorized_keys` (task M0.6). The phone key is a
//! software P-256 key behind the same `SshSigner` callback the Keystore uses.
//!
//! Needs `/usr/sbin/sshd` and `ssh-keygen`; without them the tests say so and
//! pass, unless `HERDR_REQUIRE_SSHD` is set (CI sets it).

use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{Duration, Instant};

use herdr_mobile::{SignError, SshError, SshSigner, ssh_public_key_line, ssh_run_spike};
use p256::ecdsa::SigningKey;
use p256::ecdsa::signature::Signer as _;
use p256::pkcs8::EncodePublicKey;

const SSHD: &str = "/usr/sbin/sshd";

struct SoftwareSigner {
    key: SigningKey,
    calls: AtomicU32,
    needs_auth: bool,
}

impl SshSigner for SoftwareSigner {
    fn sign(&self, data: Vec<u8>) -> Result<Vec<u8>, SignError> {
        self.calls.fetch_add(1, Ordering::Relaxed);
        if self.needs_auth {
            return Err(SignError::NeedsUserAuth);
        }
        let signature: p256::ecdsa::Signature = self.key.sign(&data);
        Ok(signature.to_der().as_bytes().to_vec())
    }
}

fn key(seed: u8) -> SigningKey {
    let mut bytes = [0x22u8; 32];
    bytes[31] = seed;
    SigningKey::from_slice(&bytes).unwrap()
}

fn spki(key: &SigningKey) -> Vec<u8> {
    key.verifying_key().to_public_key_der().unwrap().into_vec()
}

fn signer(key: SigningKey, needs_auth: bool) -> Arc<SoftwareSigner> {
    Arc::new(SoftwareSigner {
        key,
        calls: AtomicU32::new(0),
        needs_auth,
    })
}

struct Sshd {
    child: Child,
    dir: PathBuf,
    port: u16,
    host_key_fingerprint: String,
}

impl Drop for Sshd {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn user() -> String {
    std::env::var("USER").expect("USER is set")
}

/// Starts sshd with `authorized` as its only authorized key line, or returns
/// None (and says why) when OpenSSH is not installed.
fn sshd(name: &str, authorized: &str) -> Option<Sshd> {
    if !Path::new(SSHD).exists() {
        assert!(
            std::env::var_os("HERDR_REQUIRE_SSHD").is_none(),
            "HERDR_REQUIRE_SSHD is set but {SSHD} is not installed"
        );
        eprintln!("skipped: {SSHD} is not installed");
        return None;
    }
    let dir = std::env::temp_dir().join(format!("herdr-ssh-spike-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let host_key = dir.join("host_key");
    let status = Command::new("ssh-keygen")
        .args(["-q", "-t", "ed25519", "-N", "", "-f"])
        .arg(&host_key)
        .status()
        .expect("ssh-keygen runs");
    assert!(status.success());
    let fingerprint = Command::new("ssh-keygen")
        .arg("-lf")
        .arg(dir.join("host_key.pub"))
        .output()
        .unwrap();
    let host_key_fingerprint = String::from_utf8(fingerprint.stdout)
        .unwrap()
        .split_whitespace()
        .nth(1)
        .unwrap()
        .to_owned();
    std::fs::write(dir.join("authorized_keys"), format!("{authorized}\n")).unwrap();

    let port = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let config = format!(
        "Port {port}\nListenAddress 127.0.0.1\nHostKey {host}\nAuthorizedKeysFile {keys}\n\
         PidFile {pid}\nUsePAM no\nStrictModes no\nPasswordAuthentication no\n\
         KbdInteractiveAuthentication no\nPubkeyAuthentication yes\nLogLevel VERBOSE\n",
        host = host_key.display(),
        keys = dir.join("authorized_keys").display(),
        pid = dir.join("sshd.pid").display(),
    );
    std::fs::write(dir.join("sshd_config"), config).unwrap();
    let log = std::fs::File::create(dir.join("sshd.log")).unwrap();
    let child = Command::new(SSHD)
        .args(["-D", "-e", "-f"])
        .arg(dir.join("sshd_config"))
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(log)
        .spawn()
        .expect("sshd starts");
    let sshd = Sshd {
        child,
        dir,
        port,
        host_key_fingerprint,
    };
    let deadline = Instant::now() + Duration::from_secs(5);
    while TcpStream::connect(("127.0.0.1", port)).is_err() {
        assert!(
            Instant::now() < deadline,
            "sshd did not listen: {}",
            std::fs::read_to_string(sshd.dir.join("sshd.log")).unwrap_or_default()
        );
        std::thread::sleep(Duration::from_millis(50));
    }
    Some(sshd)
}

fn authorized_line(key: &SigningKey) -> String {
    let line = ssh_public_key_line(spki(key), "herdr-android".into()).unwrap();
    format!("restrict,command=\"printf spike-ok\" {line}")
}

fn run(
    sshd: &Sshd,
    key: &SigningKey,
    pinned: Option<String>,
    signer: Arc<SoftwareSigner>,
) -> Result<herdr_mobile::SshRun, SshError> {
    ssh_run_spike(
        "127.0.0.1".into(),
        sshd.port,
        user(),
        spki(key),
        pinned,
        "ignored by the forced command".into(),
        signer,
        10_000,
    )
}

#[test]
fn signs_in_through_the_callback_and_runs_the_forced_command() {
    let phone = key(1);
    let Some(sshd) = sshd("ok", &authorized_line(&phone)) else {
        return;
    };
    let signer = signer(phone.clone(), false);

    let first = run(&sshd, &phone, None, signer.clone()).unwrap();
    assert_eq!(first.output, "spike-ok");
    assert_eq!(first.exit_status, Some(0));
    assert_eq!(first.signatures, 1);
    assert_eq!(first.host_key_fingerprint, sshd.host_key_fingerprint);

    // Pinned to the key it presented the first time: connects again.
    let second = run(
        &sshd,
        &phone,
        Some(first.host_key_fingerprint),
        signer.clone(),
    )
    .unwrap();
    assert_eq!(second.output, "spike-ok");
    assert_eq!(signer.calls.load(Ordering::Relaxed), 2);
}

#[test]
fn a_changed_host_key_refuses_before_anything_is_signed() {
    let phone = key(2);
    let Some(sshd) = sshd("mismatch", &authorized_line(&phone)) else {
        return;
    };
    let signer = signer(phone.clone(), false);
    let pinned = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".to_owned();

    match run(&sshd, &phone, Some(pinned), signer.clone()) {
        Err(SshError::HostKeyMismatch { presented }) => {
            assert_eq!(presented, sshd.host_key_fingerprint)
        }
        other => panic!("expected a host key mismatch, got {other:?}"),
    }
    assert_eq!(signer.calls.load(Ordering::Relaxed), 0);
}

#[test]
fn a_key_needing_authentication_ends_the_run_for_a_prompt() {
    let phone = key(3);
    let Some(sshd) = sshd("needs-auth", &authorized_line(&phone)) else {
        return;
    };
    let signer = signer(phone.clone(), true);

    assert!(matches!(
        run(&sshd, &phone, None, signer.clone()),
        Err(SshError::NeedsUserAuth)
    ));
    assert_eq!(signer.calls.load(Ordering::Relaxed), 1);
}

#[test]
fn an_unknown_key_is_rejected_without_signing() {
    let phone = key(4);
    let other = key(5);
    let Some(sshd) = sshd("rejected", &authorized_line(&other)) else {
        return;
    };
    let signer = signer(phone.clone(), false);

    assert!(matches!(
        run(&sshd, &phone, None, signer.clone()),
        Err(SshError::Rejected)
    ));
    // OpenSSH answers the unsigned key query with "no", so nothing is signed.
    assert_eq!(signer.calls.load(Ordering::Relaxed), 0);
}
