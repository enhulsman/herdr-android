//! The SSH path against a throwaway OpenSSH server on 127.0.0.1 with its own
//! host key and `authorized_keys`: the spike's sign-in (task M0.6) and the
//! restricted `authorized_keys` line (task M1.2, machine-connection spec, "The
//! key is restricted to herdr-core on each machine"). The phone key is a
//! software P-256 key behind the same `SshSigner` callback the Keystore uses;
//! the refusal tests drive russh directly to ask for what the app never would.
//!
//! sshd's `SetEnv` gives each session a scratch HOME and PATH, so the remote
//! command finds only the herdr-core a test put there.
//!
//! Needs `/usr/sbin/sshd` and `ssh-keygen`; without them the tests say so and
//! pass, unless `HERDR_REQUIRE_SSHD` is set (CI sets it). The test that reaches
//! a real core also needs `HERDR_CORE_BIN` (scripts/fetch-herdr-core.sh) and
//! skips without it unless `HERDR_REQUIRE_CORE` is set.

use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{Duration, Instant};

use herdr_mobile::{
    SignError, SshError, SshSigner, authorized_keys_line, ssh_public_key_line, ssh_run_spike,
};
use p256::ecdsa::SigningKey;
use p256::ecdsa::signature::Signer as _;
use p256::pkcs8::{EncodePrivateKey, EncodePublicKey, LineEnding};
use russh::ChannelMsg;
use russh::client;
use russh::keys::{PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use tokio::io::AsyncReadExt as _;

const SSHD: &str = "/usr/sbin/sshd";
const NOT_HERE: &str = "herdr-core is not installed here";

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

impl Sshd {
    /// The session's HOME (sshd `SetEnv`).
    fn home(&self) -> PathBuf {
        self.dir.join("home")
    }

    fn log(&self) -> String {
        std::fs::read_to_string(self.dir.join("sshd.log")).unwrap_or_default()
    }
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

/// The PATH a session gets: system directories only, plus `extra` dirs under
/// the session HOME first.
fn session_path(home: &Path, extra: &[&str]) -> String {
    let mut dirs: Vec<String> = extra
        .iter()
        .map(|d| home.join(d).display().to_string())
        .collect();
    dirs.extend(["/usr/bin".into(), "/bin".into()]);
    dirs.join(":")
}

/// Starts sshd with `authorized` as its only authorized key line and `env`
/// set in every session on top of HOME (the scratch home) and PATH (system
/// dirs plus `path_dirs` under that home). None (and says why) when OpenSSH is
/// not installed.
fn sshd(name: &str, authorized: &str, path_dirs: &[&str], env: &[(&str, String)]) -> Option<Sshd> {
    if !Path::new(SSHD).exists() {
        assert!(
            std::env::var_os("HERDR_REQUIRE_SSHD").is_none(),
            "HERDR_REQUIRE_SSHD is set but {SSHD} is not installed"
        );
        eprintln!("skipped: {SSHD} is not installed");
        return None;
    }
    // Short: a real core's sockets go under it (108-byte socket path limit).
    let dir = std::env::temp_dir().join(format!("hsd-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("home")).unwrap();
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

    let home = dir.join("home");
    let mut set_env = vec![
        format!("HOME={}", home.display()),
        format!("PATH={}", session_path(&home, path_dirs)),
    ];
    set_env.extend(env.iter().map(|(k, v)| format!("{k}={v}")));
    let port = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let config = format!(
        "Port {port}\nListenAddress 127.0.0.1\nHostKey {host}\nAuthorizedKeysFile {keys}\n\
         PidFile {pid}\nUsePAM no\nStrictModes no\nPasswordAuthentication no\n\
         KbdInteractiveAuthentication no\nPubkeyAuthentication yes\nLogLevel VERBOSE\n\
         SetEnv {set_env}\n",
        host = host_key.display(),
        keys = dir.join("authorized_keys").display(),
        pid = dir.join("sshd.pid").display(),
        set_env = set_env.join(" "),
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
            sshd.log()
        );
        std::thread::sleep(Duration::from_millis(50));
    }
    Some(sshd)
}

/// A stand-in herdr-core at `dir` under the session HOME that prints `says`
/// and its arguments.
fn fake_core(sshd: &Sshd, dir: &str, says: &str) {
    let dir = sshd.home().join(dir);
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("herdr-core");
    std::fs::write(&path, format!("#!/bin/sh\necho \"{says}: $*\"\n")).unwrap();
    make_executable(&path);
}

fn make_executable(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).unwrap();
}

fn spike_line(key: &SigningKey) -> String {
    let line = ssh_public_key_line(spki(key), "herdr-android".into()).unwrap();
    format!("restrict,command=\"printf spike-ok\" {line}")
}

fn app_line(key: &SigningKey) -> String {
    authorized_keys_line(spki(key), "herdr-android".into()).unwrap()
}

fn run(
    sshd: &Sshd,
    key: &SigningKey,
    pinned: Option<String>,
    signer: Arc<SoftwareSigner>,
    command: &str,
) -> Result<herdr_mobile::SshRun, SshError> {
    ssh_run_spike(
        "127.0.0.1".into(),
        sshd.port,
        user(),
        spki(key),
        pinned,
        command.into(),
        signer,
        10_000,
    )
}

// The spike's sign-in (M0.6).

#[test]
fn signs_in_through_the_callback_and_runs_the_forced_command() {
    let phone = key(1);
    let Some(sshd) = sshd("ok", &spike_line(&phone), &[], &[]) else {
        return;
    };
    let signer = signer(phone.clone(), false);

    let first = run(&sshd, &phone, None, signer.clone(), "ignored").unwrap();
    assert_eq!(first.output, "spike-ok");
    assert_eq!(first.exit_status, Some(0));
    assert_eq!(first.signatures, 1);
    assert_eq!(first.host_key_fingerprint, sshd.host_key_fingerprint);

    // Pinned to the key it presented the first time: connects again.
    let pinned = Some(first.host_key_fingerprint);
    let second = run(&sshd, &phone, pinned, signer.clone(), "ignored").unwrap();
    assert_eq!(second.output, "spike-ok");
    assert_eq!(signer.calls.load(Ordering::Relaxed), 2);
}

#[test]
fn a_changed_host_key_refuses_before_anything_is_signed() {
    let phone = key(2);
    let Some(sshd) = sshd("mismatch", &app_line(&phone), &[], &[]) else {
        return;
    };
    let signer = signer(phone.clone(), false);
    let pinned = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".to_owned();

    match run(&sshd, &phone, Some(pinned), signer.clone(), "ignored") {
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
    let Some(sshd) = sshd("needs-auth", &app_line(&phone), &[], &[]) else {
        return;
    };
    let signer = signer(phone.clone(), true);

    assert!(matches!(
        run(&sshd, &phone, None, signer.clone(), "ignored"),
        Err(SshError::NeedsUserAuth)
    ));
    assert_eq!(signer.calls.load(Ordering::Relaxed), 1);
}

#[test]
fn an_unknown_key_is_rejected_without_signing() {
    let phone = key(4);
    let other = key(5);
    let Some(sshd) = sshd("rejected", &app_line(&other), &[], &[]) else {
        return;
    };
    let signer = signer(phone.clone(), false);

    assert!(matches!(
        run(&sshd, &phone, None, signer.clone(), "ignored"),
        Err(SshError::Rejected)
    ));
    // OpenSSH answers the unsigned key query with "no", so nothing is signed.
    assert_eq!(signer.calls.load(Ordering::Relaxed), 0);
}

// The restricted line (M1.2).

/// Quoting survives sshd: whatever command is asked for, the line runs the
/// remote command, which finds herdr-core in ~/.local/bin (not on PATH) and
/// execs `herdr-core connect`.
#[test]
fn the_line_runs_herdr_core_connect_from_local_bin_whatever_is_asked() {
    let phone = key(10);
    let Some(sshd) = sshd("local-bin", &app_line(&phone), &[], &[]) else {
        return;
    };
    fake_core(&sshd, ".local/bin", "local-bin herdr-core");
    let signer = signer(phone.clone(), false);

    for asked in ["herdr-core connect", "cat /etc/passwd", ""] {
        let ran = run(&sshd, &phone, None, signer.clone(), asked)
            .unwrap_or_else(|e| panic!("{e}: {}", sshd.log()));
        assert_eq!(
            ran.output, "local-bin herdr-core: connect\n",
            "asked {asked:?}"
        );
        assert_eq!(ran.exit_status, Some(0));
    }
}

#[test]
fn herdr_core_on_path_comes_before_local_bin() {
    let phone = key(11);
    let Some(sshd) = sshd("on-path", &app_line(&phone), &["tools"], &[]) else {
        return;
    };
    fake_core(&sshd, "tools", "on-path herdr-core");
    fake_core(&sshd, ".local/bin", "local-bin herdr-core");

    let ran = run(&sshd, &phone, None, signer(phone.clone(), false), "x").unwrap();
    assert_eq!(ran.output, "on-path herdr-core: connect\n");
}

#[test]
fn without_herdr_core_the_command_exits_127_and_says_so() {
    let phone = key(12);
    let Some(sshd) = sshd("missing", &app_line(&phone), &[], &[]) else {
        return;
    };
    // Present but not executable counts as missing too.
    std::fs::create_dir_all(sshd.home().join(".local/bin")).unwrap();
    std::fs::write(sshd.home().join(".local/bin/herdr-core"), "#!/bin/sh\n").unwrap();

    let ran = run(&sshd, &phone, None, signer(phone.clone(), false), "x").unwrap();
    assert_eq!(ran.exit_status, Some(127));
    assert_eq!(ran.output.trim_end(), NOT_HERE);
}

/// A russh client authenticated with `key` as an OpenSSH private key, for
/// requests the app never makes.
struct AnyHostKey;

impl client::Handler for AnyHostKey {
    type Error = russh::Error;

    async fn check_server_key(&mut self, _: &PublicKeyOrCertificate) -> Result<bool, Self::Error> {
        Ok(true)
    }
}

async fn raw_session(sshd: &Sshd, key: &SigningKey) -> client::Handle<AnyHostKey> {
    let pem = key.to_pkcs8_pem(LineEnding::LF).unwrap();
    let private = russh::keys::decode_secret_key(&pem, None).unwrap();
    let config = Arc::new(client::Config::default());
    let mut session = client::connect(config, ("127.0.0.1", sshd.port), AnyHostKey)
        .await
        .unwrap();
    let auth = session
        .authenticate_publickey(user(), PrivateKeyWithHashAlg::new(Arc::new(private), None))
        .await
        .unwrap();
    assert!(auth.success(), "{}", sshd.log());
    session
}

fn block_on<F: std::future::Future>(f: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            tokio::time::timeout(Duration::from_secs(20), f)
                .await
                .unwrap()
        })
}

/// Every message on a channel until it closes: (refused requests, output, exit).
async fn drain(channel: &mut russh::Channel<client::Msg>) -> (u32, String, Option<u32>) {
    let mut failures = 0;
    let mut output = Vec::new();
    let mut exit = None;
    while let Some(msg) = channel.wait().await {
        match msg {
            ChannelMsg::Failure => failures += 1,
            ChannelMsg::Data { data } | ChannelMsg::ExtendedData { data, .. } => {
                output.extend_from_slice(&data)
            }
            ChannelMsg::ExitStatus { exit_status } => exit = Some(exit_status),
            _ => {}
        }
    }
    (
        failures,
        String::from_utf8_lossy(&output).into_owned(),
        exit,
    )
}

#[test]
fn a_pty_is_refused_and_a_shell_runs_the_remote_command() {
    let phone = key(13);
    let Some(sshd) = sshd("pty", &app_line(&phone), &[], &[]) else {
        return;
    };
    fake_core(&sshd, ".local/bin", "local-bin herdr-core");

    let (failures, output, exit) = block_on(async {
        let session = raw_session(&sshd, &phone).await;
        let mut channel = session.channel_open_session().await.unwrap();
        channel
            .request_pty(true, "xterm-256color", 80, 24, 0, 0, &[])
            .await
            .unwrap();
        channel.request_shell(true).await.unwrap();
        drain(&mut channel).await
    });
    assert_eq!(failures, 1, "the pty request is refused");
    assert_eq!(output, "local-bin herdr-core: connect\n");
    assert_eq!(exit, Some(0));
}

#[test]
fn port_forwarding_is_refused_both_ways() {
    let phone = key(14);
    let Some(sshd) = sshd("forward", &app_line(&phone), &[], &[]) else {
        return;
    };
    // Something to forward to, which must never see a connection.
    let target = TcpListener::bind("127.0.0.1:0").unwrap();
    target.set_nonblocking(true).unwrap();
    let target_port = target.local_addr().unwrap().port();

    let (local, remote) = block_on(async {
        let session = raw_session(&sshd, &phone).await;
        let local = session
            .channel_open_direct_tcpip("127.0.0.1", target_port.into(), "127.0.0.1", 40000)
            .await
            .map(|_| ());
        let remote = session.tcpip_forward("127.0.0.1", 0).await;
        (local, remote)
    });
    assert!(
        matches!(local, Err(russh::Error::ChannelOpenFailure(_))),
        "direct-tcpip: {local:?}"
    );
    assert!(remote.is_err(), "tcpip-forward: {remote:?}");
    assert!(target.accept().is_err(), "the forward target was reached");
}

// A real core through the line (needs HERDR_CORE_BIN).

fn core_bin() -> Option<PathBuf> {
    match std::env::var_os("HERDR_CORE_BIN") {
        Some(p) => Some(PathBuf::from(p)),
        None => {
            assert!(
                std::env::var_os("HERDR_REQUIRE_CORE").is_none(),
                "HERDR_REQUIRE_CORE is set but HERDR_CORE_BIN is not"
            );
            eprintln!("skipped: HERDR_CORE_BIN is not set (scripts/fetch-herdr-core.sh)");
            None
        }
    }
}

/// Stops the scratch core the session started, also when the test fails, then
/// removes its scratch dirs.
struct StopCore {
    bin: PathBuf,
    env: Vec<(&'static str, String)>,
    scratch: PathBuf,
}

impl Drop for StopCore {
    fn drop(&mut self) {
        let _ = Command::new(&self.bin)
            .args(["server", "stop"])
            .env_clear()
            .envs(self.env.iter().map(|(k, v)| (*k, v.as_str())))
            .env("PATH", "/usr/bin:/bin")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        let _ = std::fs::remove_dir_all(&self.scratch);
    }
}

#[test]
fn a_real_core_answers_hello_through_the_line() {
    let Some(bin) = core_bin() else { return };
    let phone = key(15);
    let scratch = std::env::temp_dir().join(format!("hsc-{}", std::process::id()));
    let env = vec![
        ("HC_SESSION", "t".to_owned()),
        ("XDG_RUNTIME_DIR", scratch.join("r").display().to_string()),
        ("XDG_STATE_HOME", scratch.join("s").display().to_string()),
        ("XDG_CONFIG_HOME", scratch.join("c").display().to_string()),
    ];
    let Some(sshd) = sshd("real", &app_line(&phone), &[], &env) else {
        return;
    };
    let local_bin = sshd.home().join(".local/bin");
    std::fs::create_dir_all(&local_bin).unwrap();
    std::fs::copy(&bin, local_bin.join("herdr-core")).unwrap();
    let mut home_env = env.clone();
    home_env.push(("HOME", sshd.home().display().to_string()));
    let _stop = StopCore {
        bin: local_bin.join("herdr-core"),
        env: home_env,
        scratch: scratch.clone(),
    };

    let hello = block_on(async {
        let session = raw_session(&sshd, &phone).await;
        let channel = session.channel_open_session().await.unwrap();
        channel.exec(true, "anything").await.unwrap();
        let (mut r, mut w) = tokio::io::split(channel.into_stream());
        let hello = herdr_proto::handshake::handshake(&mut r, &mut w)
            .await
            .unwrap_or_else(|e| panic!("{e}: {}", sshd.log()));
        let r = std::io::Cursor::new(hello.leftover).chain(r);
        let client = herdr_proto::rpc::Client::start(r, w);
        let status = client.call("server.status", serde_json::json!({})).await;
        assert!(status.is_ok(), "server.status: {status:?}");
        hello.result
    });
    // The core that answered is the scratch one (its socket is in the scratch
    // runtime dir), never the user's own; `_stop` ends it with that same env.
    let socket = scratch.join("r/herdr-core/t.sock");
    assert!(
        std::os::unix::net::UnixStream::connect(&socket).is_ok(),
        "no scratch core listening at {}",
        socket.display()
    );
    let version = hello["version"].as_str().unwrap();
    let want = Command::new(&bin).arg("--version").output().unwrap();
    assert!(
        String::from_utf8_lossy(&want.stdout).contains(version),
        "hello says {version}"
    );
    assert_eq!(
        herdr_proto::compat::check(&hello, version),
        herdr_proto::compat::Compat::Ok
    );
}
