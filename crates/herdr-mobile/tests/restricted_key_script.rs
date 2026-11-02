//! `scripts/check-restricted-key.sh` accepts exactly the line the app
//! generates (task M1.5, machine-connection spec, "The key is restricted to
//! herdr-core on each machine"): the script's expected command and the Rust
//! `REMOTE_COMMAND` cannot drift apart unnoticed. The script's own cases are in
//! `scripts/test-check-restricted-key.sh`.

use std::path::PathBuf;
use std::process::Command;

use herdr_mobile::authorized_keys_line;
use p256::ecdsa::SigningKey;
use p256::pkcs8::EncodePublicKey;

fn script() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../scripts/check-restricted-key.sh")
}

fn generated_line() -> String {
    let mut bytes = [0x33u8; 32];
    bytes[31] = 7;
    let key = SigningKey::from_slice(&bytes).unwrap();
    let spki = key.verifying_key().to_public_key_der().unwrap().into_vec();
    authorized_keys_line(spki, "herdr-android".into()).unwrap()
}

/// Runs the script on an authorized_keys file holding `lines`.
fn check(name: &str, lines: &[String]) -> (i32, String) {
    use std::os::unix::fs::PermissionsExt;
    let dir = std::env::temp_dir().join(format!("hrk-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir(&dir).unwrap();
    let keys = dir.join("authorized_keys");
    std::fs::write(&keys, lines.join("\n") + "\n").unwrap();
    std::fs::set_permissions(&keys, std::fs::Permissions::from_mode(0o600)).unwrap();
    let out = Command::new("bash")
        .arg(script())
        .arg(&keys)
        .env("HOME", &dir)
        .output()
        .expect("bash runs");
    let _ = std::fs::remove_dir_all(&dir);
    let text =
        String::from_utf8_lossy(&out.stdout).into_owned() + &String::from_utf8_lossy(&out.stderr);
    (out.status.code().unwrap_or(-1), text)
}

#[test]
fn the_script_passes_the_generated_line() {
    let (status, out) = check("good", &[generated_line()]);
    assert_eq!(status, 0, "{out}");
    assert!(!out.lines().any(|l| l.starts_with("FAIL")), "{out}");
    assert!(out.contains("PASS"), "{out}");
}

#[test]
fn the_script_fails_the_generated_line_with_pty_re_enabled() {
    let line = generated_line().replacen("restrict,", "restrict,pty,", 1);
    let (status, out) = check("pty", &[line]);
    assert_eq!(status, 1, "{out}");
}

#[test]
fn the_script_fails_the_generated_line_without_restrict() {
    let line = generated_line().replacen("restrict,", "", 1);
    let (status, out) = check("norestrict", &[line]);
    assert_eq!(status, 1, "{out}");
}
