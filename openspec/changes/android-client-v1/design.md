# Design: herdr for Android, version 1

Facts cited as `file:line` refer to the upstream clones read on 2026-10-31
(`research/source-findings-2026-10-31.md` holds the full citations).

## 1. Shape

```
 Phone                                             Each machine (WSL, Mac)
 ┌───────────────────────────────┐                 ┌──────────────────────────┐
 │ Kotlin / Compose UI           │                 │ herdr-core 0.1.15        │
 │  screens, notifications,      │  SSH over       │   ▲ local connect        │
 │  Keystore key, UnifiedPush    │  Tailscale      │   │                      │
 │        │ uniffi               │ ─────────────▶  │ herdr-push (user svc)    │
 │ Rust core (libherdr_mobile)   │  forced command │   │ encrypted POST       │
 │  ssh, frame codec, RPC,       │  herdr-core     └───┼──────────────────────┘
 │  event state, attention,      │  connect            ▼
 │  layout doc, libghostty-vt    │ ◀── UnifiedPush ── ntfy (VPS) 
 └───────────────────────────────┘
```

- **One Rust crate, two binaries' worth of code.** `crates/herdr-proto` (frame
  codec, JSON-RPC client, event-stream state, attention model, layout doc types)
  is shared by the app's Rust core (`crates/herdr-mobile`, built as an Android
  `.so` with uniffi bindings) and by `herdr-push` (a Linux/macOS binary).
- **Not a dependency on the `herdr-core` crate.** It is unpublished and pulls in
  the server and Zig. Copy and adapt the small pure pieces instead, with their
  Apache-2.0 notices: frame codec (`app/src/hc.rs:14-20,229-245`), RPC routing and
  compat check (`hc.rs:149-213,254-437`), `EventLines` (`hc.rs:33-147`), the
  attention model (`model.rs:86-95,437-525`), the layout types (`layout.rs`,
  `mirror.rs`). RPC param types are generated or hand-written from
  `research/hc.openrpc-0.1.15.json`. Confidence: high.
- **Terminal engine: libghostty-vt** at the herdr-core pin `1e5cdb8`, built by
  Zig 0.16.0 for `aarch64-linux-android` (verified to build and link on
  2026-10-31; not yet run on a device). `-Dtarget` is passed explicitly because
  herdr-core's `zig_target()` drops the Android ABI. Fallback if it fails on a
  device: Termux `terminal-emulator`/`terminal-view` (Apache-2.0).
- **Drawing:** the Rust core turns libghostty-vt state into a frame of styled cell
  runs (as the Mac's `vt.rs` does, minus its GPUI ties); Kotlin draws runs on a
  Compose `Canvas` with a monospace font and a glyph cache. Target: 60 fps at
  200x60 cells on the phone. Confidence: moderate; measure in M2.

## 2. Connecting

- **Direct, one connection per machine.** The phone has no local core, so it
  does what the desktop core does for remote machines: SSH (in Rust, `russh`)
  running the same remote command, including the `~/.local/bin`
  fallback (`core/src/machines.rs:212-226`). Then `hello {framed:true}` and the
  compat check against the pinned schema.
- **No hub.** Connecting to one core that has added the others would make the
  phone depend on a laptop that sleeps. Each machine is connected on its own;
  terminals a core lists for machines *it* added (`machine/t7`) are skipped so
  nothing appears twice. Open question Q1.
- **Phone key:** ECDSA P-256 generated in the Android Keystore (hardware-backed,
  never exportable), used through a signing callback from Rust. Fallback if russh
  cannot sign through a callback: Ed25519 key in app-private storage, wrapped by a
  Keystore key. Confidence: moderate (spike in M1).
- **authorized_keys line per machine** (installed by Ezra, shown by the app):
  `restrict,command="<the remote command>" ecdsa-sha2-nistp256 ... herdr-android`.
  Honest scope: this removes pty, port and agent forwarding and any other
  command, but herdr-core's `machine.exec` is itself a shell, so the key is still
  shell-equivalent on that machine. The app never calls `machine.exec` except for
  the read-only git commands of `changes-view`.
- **Host keys:** first connect shows the fingerprint for confirmation; a later
  mismatch refuses to connect.
- **Reconnect:** 1 s retry with backoff to 30 s, resubscribe events, reattach the
  open terminal with `resumed:true`, refetch docs after `missed`
  (`window.rs:600-692`).

## 3. The size rule

A herdr-core terminal has one size, set by its most recently active non-observe
viewer (`core/src/viewers.rs:289-311`). The desktop never uses `observe`.

- The phone attaches with `observe:true` by default and draws the terminal at its
  real `cols`x`rows`, scaled to fit width; it never sends RESIZE, FOCUS or input
  on that attach.
- Answers from the composer and keys row go through RPC (`terminal.send`,
  `terminal.keys`, `terminal.interrupt`), which does not make the phone a viewer
  that sets the size.
- **Direct typing** reattaches non-observe. Before the first keystroke the app
  asks once per terminal: *Keep their size* (RESIZE with the current size) or
  *Use phone size*. On 0.1.15 a FACTS frame also counts as activity
  (`v0.1.15 viewers.rs:213-215`; removed in 0.1.16): the phone sends no FACTS on
  observe attaches.

## 4. Shared layout

The `herdr-app` document (`{spaces:[{id,name,folder,tabs:[{id,name,root:Node}]}]}`)
is read with `doc.get` and followed through `doc` events. The phone never writes
it: the newest write wins and fields a client does not know are dropped
(`mirror_sync.rs`). Phone-only view state (open terminal, zoom, seen) is stored
on the phone.

## 5. Alerts

- **`herdr-push`** runs per machine as a user service (systemd on WSL, launchd on
  the Mac). It connects to its local core, subscribes to events, and on a
  transition into `blocked` or `done` sends one web-push-encrypted message
  (RFC 8291, aes128gcm) to each registered phone endpoint.
- **Registration through a core document.** The phone writes
  `herdr-android/push` on each machine: `{endpoint, p256dh, auth}` per phone.
  The watcher follows that doc. No extra port, no inbound connection to the
  watcher.
- **Payload:** `{v:1, machine, terminal, name, state, kind?, at}`; never screen
  text or `msg`. ntfy sees ciphertext only.
- **Watcher liveness:** the watcher writes `herdr-android/push-status`
  `{seen_at, version}` every 5 minutes; the app shows a warning on that machine
  when it is connected but the watcher is silent for over 15 minutes.
- **ntfy:** one anonymous write-only grant per phone endpoint topic, as for
  MollySocket. The ntfy app's default server must be `https://ntfy.example.org`
  before the app registers.

## 6. Release and tests

- Tag `v*` → GitHub Actions builds the Rust core (cargo-ndk, Zig 0.16.0), the
  APK (arm64-v8a), runs all tests, then a `sign-and-release` job in the protected
  environment `release` waits for Ezra's approval, signs with `apksigner`, and
  attaches the APK to the GitHub release. Obtainium tracks the repo. Actions are
  pinned by commit SHA; the key backup lives in Bitwarden.
- Tests: Rust unit tests (codec, event state, attention, doc types, push
  encryption against RFC 8291 test vectors); protocol tests against a scratch
  herdr-core 0.1.15 with its own `HC_SESSION` and XDG paths; Compose screenshot
  tests (Roborazzi, JVM, no emulator) compared to the signed-off designs; one
  emulator job (x86_64 image) that loads the real `.so` and renders a terminal.
- minSdk 31, target the current SDK; ABIs arm64-v8a (release) and x86_64 (tests).

## 7. Open questions

- **Q1** (maintainers): does `terminal.list` on a core with added machines
  include their terminals by default, and is the `machine/` prefix a reliable way
  to tell them apart?
- **Q2** (maintainers): is a client-owned document name like
  `herdr-android/push` acceptable, or is there a reserved namespace?
- **Q3** (spike, M1): can russh authenticate with a signature produced by the
  Android Keystore through a callback?
- **Q4** (spike, M2): does libghostty-vt run correctly on the device, and does
  Compose Canvas drawing hold 60 fps at 200x60?
- **Q5** (M4): does the self-hosted ntfy require VAPID for UnifiedPush endpoints
  from this app, as Molly required it?
