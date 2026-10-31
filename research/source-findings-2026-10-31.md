# herdr-core / herdr-app source findings for a native Android client

Date: 2026-10-31. Read-only source reading; nothing built or run.
Paths are relative to `research/src/`. `core/` = `herdr-core/`, `app/` = `herdr-app/`.
Clones: core HEAD `568a44d` (0.1.16 notes; crate version still 0.1.15, `core/crates/herdr-core/Cargo.toml:3`), tag `v0.1.15` present.
App HEAD `07ba77e`, released tag `v0.1.10` (`app/Cargo.toml:3` says 0.1.10).
`vendor/ghostty` is an uninitialised submodule in both clones (empty dir), so Ghostty's own build graph could not be read.

Corrections to the brief:
- herdr-app has no `ghostty-vt/` or `herdr-core/` subdirectory. It has `crates/ghostty-sys` (its own bindgen binding) and `vendor/ghostty` (submodule). It does **not** depend on any herdr-core crate (`app/Cargo.toml:18-35` lists no herdr-core).
- The desktop app does **not** ssh. It runs the local `hc connect` as a subprocess. Remote machines are reached through the local core (`hc machine add`) and addressed with `on` / `box/t3` (details in section 2).

---

## 1. Crate map of herdr-core

Workspace: `core/Cargo.toml:1-8`, members `crates/*`, `publish = false` (`core/Cargo.toml:8`). Nothing is on crates.io.

| Crate | What it is | Side | Native deps |
|---|---|---|---|
| `ghostty-vt` (`core/crates/ghostty-vt`) | Hand-written FFI to a subset of libghostty-vt (`core/crates/ghostty-vt/src/lib.rs:1`, extern block `:187-268`). Covers terminal, formatter, snapshot encode/decode, key/mouse encoders and OSC 7501 parsing (`OPT_PROGRAM_STATUS`, `:40`; `StatusReport`, `:284-296`). It has **no render-state / row-iterator API**, so it can't draw. | server (core keeps a screen per terminal) | Zig 0.16 builds `vendor/ghostty` into a static `libghostty-vt.a` (`build.rs:1`, `:26`, `:34`, `:49`, `:59`). Ghostty is pinned to `1e5cdb8` at HEAD and at v0.1.15 (`git ls-tree`). |
| `herdr-core` (`core/crates/herdr-core`) | One crate with a lib and the `herdr-core` bin (`Cargo.toml:8-10`, `main.rs`). The lib exposes every module publicly (`src/lib.rs:1-36`): server, holder, CLI, remote machines and the client helpers. | both: server plus CLI client in one crate | `clap`, `schemars`, `serde`, `serde_json`, `toml`, `tokio` (rt, net, io-util, process, sync, time, macros, signal), `libc` on unix, `windows-sys` on Windows (`Cargo.toml:12-46`). It also depends on `ghostty-vt` (`:14`). |

Modules a client would care about:
- `wire.rs`: frame constants and codec. `encode`/`decode_header` are pure; `read_frame` is tokio-async (`core/crates/herdr-core/src/wire.rs:1-60`). Credit window 256 KiB, repaint threshold 4 MiB (`:23-27`).
- `rpc.rs`: `RpcError` with stable `kind` and CLI exit codes (`rpc.rs:6-70`).
- `api/types.rs`: params and results. These derive `clap::Args` + `schemars` (21 `clap::Args` derives), and some hold tokio types (`types.rs:422`).
- `viewers.rs::Display`: the FACTS payload (`viewers.rs:15-26`).
- `client.rs`: a blocking one-call plain-mode helper for local sockets with autostart (`client.rs:72-116`, `call` at `:206-221`). It's tied to local socket paths, not to a stdio or ssh transport.

Cross-compiling to `aarch64-linux-android`:
- Rust side: platform code is gated `cfg(unix)` / `target_os="linux"|"macos"` (`src/platform/mod.rs:5-14`). Android is `unix` but not `linux`, so systemd is excluded. **Unverified (low)**: whether `platform/unix.rs` builds on Bionic.
- ghostty-vt build: `zig_target()` maps the triple's OS to `linux` and keeps only a `musl|gnu|msvc` ABI suffix (`core/crates/ghostty-vt/build.rs:65-76`). `aarch64-linux-android` would become `-Dtarget=aarch64-linux`, with no Android ABI, no NDK sysroot and no Bionic. It needs patching. **Moderate.**
- App's `ghostty-sys`: `zig_target()` panics for any triple outside six desktop targets (`app/crates/ghostty-sys/build.rs:8-17`). It also needs bindgen/libclang (`:68-95`; uses `xcrun` on macOS, `:74`).
- Ghostty's own C/C++ deps (for example SIMD libraries) can't be checked: the submodule isn't checked out. **Unknown.**
- The two repos pin **different** Ghostty commits: core `1e5cdb8`, app `291fd05` (`git ls-tree HEAD vendor/ghostty`). The C API surface may differ between them.

## 2. Client library: does one exist? How does herdr-app connect?

- **There is no client library crate.** README "Build your own client" points only at `hc schema`, `schema/README.md` and `hc connect` (`core/README.md:107-111`). The single `herdr-core` crate drags in the server, tokio process/signal, clap and the Zig-built ghostty-vt (section 1).
- **herdr-app hand-rolls the client** in `app/src/hc.rs` (535 lines). It has its own frame constants and `encode`/`read_frame` (`hc.rs:14-20`, `:229-245`), JSON-RPC id routing over std threads and `mpsc` (`:254-333`), `call` with a 10 s default timeout (`:344-366`), and helpers for attach, input, credit, resize, facts, focus and detach (`:393-437`).
  - `EventLines` (`hc.rs:33-147`) applies the event-to-snapshot rules from `core/schema/README.md` (Events table). It is pure: serde_json plus std.
  - Compatibility check: generation 1 (`hc.rs:156`), required methods `USES` (`:158`), minimum core `distribution/core-version` = `0.1.11` (`:191`, `app/distribution/core-version`), `check()` (`:201-213`).
- **Transport**: spawns `<core_bin::path()> connect` with piped stdin/stdout (`hc.rs:254-261`). It sends `hello {"framed":true}` with no `generation` field (`:264`). `core_bin::path()` is a *local* herdr-core found via `settings.json` `core`, PATH, the login shell's PATH and usual install dirs (`app/src/core_bin.rs:1-8`, `:85-101`). **No ssh in the app.**
  - `hc connect` itself = `client::connect_or_start` (autostarts the core), then raw byte pumps (`core/crates/herdr-core/src/cli.rs:808-829`).
- **Remote machines**: the *local* core dials them. It runs the system `ssh` binary (`core/src/machines.rs:150-166`) with ControlMaster on unix (`:156-159`) and `BatchMode=yes, ConnectTimeout=10, ServerAliveInterval=15, ServerAliveCountMax=4` (`:228`). The remote command is `sh -c 'p=$(command -v herdr-core) || p=$HOME/.local/bin/herdr-core; ...; exec "$p" connect'` (`:212-226`). A direct Android client needs the same PATH fallback (**high**). Core-to-core `hello` also sends `core`/`calls_you` (`api/types.rs:99-110`), which a viewer must not send.
- **Reconnect**:
  - On reader EOF, every stream gets `Stream::Lost` and pending calls fail with "lost the core" (`hc.rs:322-330`).
  - A UI task waits `core.closed()`, then retries `Core::connect()` every 1000 ms forever (`app/src/window.rs:674-692`).
  - The poll loop resubscribes on a new core generation and rereads the local doc (`window.rs:600-626`). It also resubscribes when the event stream closes (`:650-651`).
  - Panes reattach via `reattach_if_needed` when the record has a `pid` (`window.rs:1899-1902`; `app/src/terminal.rs:218-231`).
- **Errors**:
  - attach errors split into `unavailable`/"lost the core"/"no reply", which give "reconnecting to core…" (`terminal.rs:298-302`, `:1351`), and `finished`, which closes the pane (`:305-309`).
  - Layout errors `needs_update`/`needs_login` become Blocked and are not retried on a timer (`app/src/mirror_sync.rs:66-72`). Others are Unavailable.
  - The machine list is polled with `machine.list` every 5th 500 ms tick, or when terminals changed (`window.rs:627-652`). `temporary` machines are skipped (`:1799-1801`).
- Every core call the app makes (grep): `terminal.run|list?|get|read|kill|label|keep|attach`, `events.subscribe`, `doc.get`, `doc.set`, `machine.list`, `server.status`. `terminal.run` always sets `keep:true` and env `TERM=xterm-256color, COLORTERM=truecolor, TERM_PROGRAM=herdr` (`hc.rs:370-377`).

## 3. The `herdr-app` layout document

- **Storage**: core `doc.get {name:"herdr-app", on}` and `doc.set {name, on, version, value}` (`app/src/mirror_sync.rs:76`, `:94`). Each machine's core holds its own document. `on` = machine name, null for the local core (`mirror_sync.rs:75`, `:91`).
  - Core semantics: opaque JSON, at most 1 MiB, CAS on `version`, `conflict` error on a stale write, null deletes but keeps the version. The `doc` event carries no value. Subscribe first, then read; refetch after `missed` or a reconnect (`core/schema/README.md:149-185`).
- **Writer**: herdr-app only, from `Document::capture(model, machine)` (`app/src/mirror.rs:90-113`).
  - Flush model: latest state, one request per machine, no operation queue (`mirror_sync.rs:1`). On `conflict` it rereads and *replaces* local state: "newest accepted document wins (no merging)" (`app/defaults/AGENTS.md:98`; `window.rs:1755-1796`, notice "Layout changed elsewhere" at `:1795`).
  - Pending writes are drained on quit with bounded timeouts (`window.rs:1702-1740`).
- **Versioning**: no schema-version field in the value. The only version is core's CAS counter. Compatibility rests on serde defaults: missing fields are OK (`mirror.rs:239-247` test). There's no `deny_unknown_fields` on `Node`, and no catch-all, so unknown fields are dropped on the next write by herdr-app. That's an inference from the struct definitions (**high**). The same code is in the released v0.1.10 (`git diff v0.1.10 HEAD -- src/mirror*.rs` is empty).
- **IDs are machine-local in the document**: `capture` strips the `machine/` prefix and `replace` re-adds it (`mirror.rs:62-88`, `:115-140`). Mod `props` are never qualified (`app/defaults/AGENTS.md:100`). New ids are `<prefix><32 hex random>` (`app/src/model.rs:157-161`). Prefixes: `s`/`sp` space, `tb` tab, `p` pane (`window.rs` `fresh_id_on` calls).
- **Types** (verbatim):

```rust
// app/src/mirror.rs:9-36
pub struct Document { #[serde(default)] pub spaces: Vec<SharedSpace> }
#[serde(default)]
pub struct SharedSpace { id: String, name: String, folder: String, tabs: Vec<SharedTab> }
struct SharedTab {
    #[serde(default)] id: String,
    #[serde(default)] name: String,
    #[serde(default = "empty_root")] root: Node,   // empty_root = row container with id ""
}
```

`Node` (`app/src/layout.rs:315-381`) is the same type the window layouts in `layouts/*.json` use:

```rust
pub struct Node {                                  // layout.rs:315
    #[serde(default)] pub id: String,
    pub cloned_from: Option<ClonedFrom>,           // {name, version}
    #[serde(flatten)] pub kind: Kind,
    pub size: Option<Size>,                        // untagged {px}|{weight}
    pub min: Option<f32>, pub max: Option<f32>,
    pub hidden: bool, pub title: Option<String>,
    pub background: Option<Background>, pub border: Option<Line>, pub radius: Option<f32>,
    pub shadow: Option<String>, pub padding: Option<Padding>, pub focus: Option<FocusLook>,
    #[serde(flatten)] pub look: Look,              // theme, colors, font, font_size, ui_font, terminal_padding, terminal_opacity
    pub gap: Option<f32>, pub divider: Option<Line>,
    pub movable: Option<bool>, pub closable: Option<bool>, pub resizable: Option<bool>,
    pub floating: Vec<Float>,
    pub titlebar: Option<Titlebar>, pub traffic_lights: Option<[f32; 2]>,
    pub preview: Option<String>, pub terminal_menu: Option<String>, pub window_drag: bool,
}
#[serde(tag = "type", rename_all = "lowercase")]   // layout.rs:272-291
pub enum Kind { Block { content: Content }, Row { children: Vec<Node> }, Column { children: Vec<Node> },
                Stack { children: Vec<Node>, active: Option<String>, header: Option<Header> } }
#[serde(untagged)]                                 // layout.rs:10-31
pub enum Content { Terminal { terminal: String },
                   Mod { #[serde(rename = "mod")] name: String, props: Value },
                   Context { #[serde(alias = "context")] tab: String, pane: Option<PaneFrame> },
                   Agent { agent: String } }
```

- **What a tab root actually holds**: `Tab::new`/`with_pane` creates `Node::container(tab_id, Row, [pane])` (`model.rs:97-110`). Panes are `Block{Terminal{terminal:"t3"}}` (`model.rs:530-532`) or `Block{Mod{mod, props}}` (`AGENTS.md` pane.split). Splits are nested row/column containers. `Context`/`Agent`/`Float`/look fields are layout-file features that the tab tree can carry but the shipped flows don't put there (**moderate**).
- **Not in the document (app-local)**:
  - `View` = active space, per-space active tab, per-tab focused/before/zoomed/settled, `recent`, and attention `{word, seen}` (`mirror.rs:38-60`). Saved to `~/.config/herdr-app/view.json` (`window.rs:505`, `:1063`; `AGENTS.md:84`).
  - Space colors live in mods' `shared.json` key `space.colors` (`app/defaults/mods/spaces/main.js:289`).
- **Terminal adoption**: terminals carry labels `herdr.space`/`herdr.tab` with machine-local ids. A labelled terminal not in the doc gets placed into that space/tab (`window.rs:1953-1980`, `:2207`; `AGENTS.md` model section). `herdr.right-click=program` is per terminal (`window.rs:1836`, `:2873`).

## 4. Terminal rendering in herdr-app

- **Bytes to ghostty**:
  - `TerminalView::attach` builds a fresh `Vt` and calls `core.attach(address, cols, rows, display)` with `history: 2000` (`terminal.rs:236-269`; `hc.rs:393-406`).
  - The reader task batches `Stream` items into `ingest` (`terminal.rs:348-403`). Each `Data` goes to `vt.write`, which is `ghostty_terminal_vt_write` (`vt.rs:1159`). It then sends `CREDIT(len)` (`terminal.rs:368`) and forwards ghostty's own replies (for example kitty graphics acks) as input (`:369-371`).
  - The first paint from core is VT bytes (CAN, then formatter output, then `CSI ?9001h/l`, then the 7501 reports re-encoded) (`core/src/viewers.rs:330-350`). So the viewer **must** run a VT parser. The protocol has no cell-grid snapshot.
- **ghostty to frame**: `Vt::frame()` (`vt.rs:1287`) reads `ghostty_render_state_*` rows and cells into `Frame { rows: Vec<Rc<Vec<Cell>>>, all, cursor, cursor_cell, bg }` (`vt.rs:105-115`). It caches unchanged rows by row id (`vt.rs:470-473`). `Cell` = text + fg/bg + bold/italic/faint/underline/strike/wide (`vt.rs:75-86`).
  - Kitty images: `placements()`/`image_bgra()` (`vt.rs:722`, `:854`).
  - Keys and mouse: Ghostty encoders (`encode_key` `vt.rs:1443`, `encode_mouse` `:1476`).
  - Selection: Ghostty selection gestures (`vt.rs:900`). Links: `links.rs` regex (fancy-regex).
  - Scrollback cap: 10,000 lines / 10 MB (`vt.rs:597-599`).
- **frame to pixels**: GPUI. `paint_grid` shapes each run with `window.text_system().shape_line` and draws `paint_quad`/`paint_glyph` (`terminal.rs:1017-1104`, `:1106-1270`). Box and powerline sprites are drawn as GPUI paths (`app/src/sprites.rs:5`).
  - Redraw runs on a shared ~30 fps clock; right after typing it's immediate (`terminal.rs:405-410`). Hidden panes don't redraw (`:396-401`).
  - GPU stack: `gpui-pre` 0.3.8 (Zed's gpui snapshot; `app/Cargo.toml:20-22`). Linux uses `gpui-pre-wgpu` (wgpu; `vendor/gpui-pre-linux/Cargo.toml:143-147`). macOS uses Metal (`vendor/gpui-pre-macos/Cargo.toml:94`). Windows uses Direct3D11/DirectComposition (`vendor/gpui-pre-windows/Cargo.toml:164-171`). There's no Android backend in the vendored gpui crates.
- **What ports to Android**:
  - Most portable: `vt.rs` (std + `ghostty_sys`). It's coupled to `crate::settings::theme()` (`vt.rs:64-73`, where `settings.rs` imports gpui types, `settings.rs:9`), plus `crate::sprites::is_sprite` (gpui, `vt.rs:39`), `links` and `win32_keys`. Reusable after cutting those ties (**high**).
  - Desktop-only: `terminal.rs` (GPUI view, IME via GPUI `EntityInputHandler`, clipboard via GPUI, desktop key map `map_key` `terminal.rs:926`), `sprites.rs`, the whole window and mods runtime.
- **Resize semantics (core)**:
  - The leader is the most recently *active* non-observe viewer. It sets size and display facts; with no viewer, the terminal runs at the headless size, default 200x120 (`core/src/viewers.rs:289-311`, `core/src/config.rs:41-42`).
  - A new non-observe viewer is active immediately unless `resumed:true` (`viewers.rs:75`). `apply_size` runs on attach (`:78`).
  - Activity = DATA input (`:222-230`), RESIZE (`:232-235`), FOCUS gained (`:256-260`) (`touch` at `:211-220`).
  - `observe:true` viewers never type or resize: `touch` returns None, so their input isn't forwarded (`:214-216`; params `api/types.rs:353-361`).
  - `resumed:true` means "sets the size only once active again" (`types.rs:373-376`). If every viewer is non-active, `max_by_key(active_at)` still picks one, so a lone resumed viewer's size applies (`viewers.rs:292`; inference, **moderate**).
  - **0.1.15 vs HEAD**: in v0.1.15, FACTS counts as activity (`v0.1.15:viewers.rs:213-215` calls `touch`). At HEAD (0.1.16, commit `d7e9d7c`), FACTS no longer makes a viewer active; it only applies when that viewer already leads (`viewers.rs:237-254`). `schema/README.md:70-71` still lists FACTS as activity.
- **What the desktop app does**:
  - Attaches with the terminal's current cols/rows from the record (`window.rs:1372-1375`; comment `terminal.rs:174-175`), so attaching doesn't resize.
  - Sends RESIZE whenever the drawn canvas size or cell changes (`terminal.rs:1430-1440`).
  - Sends FOCUS on focus change (`:1333-1340`) and FACTS only when colors or cell changed (`:129-148`).
  - Never uses `observe` or `resumed` (grep).
  - Its replies-as-input path (`terminal.rs:371`) also counts as activity, but only when non-empty (`hc.rs:408-412`).

## 5. Status and notifications

- **Source**: OSC 7501, parsed by libghostty in core (`core/crates/ghostty-vt/src/lib.rs:284-296`, `:333-338`). Stored verbatim as `Status { state, kind, progress, app, title, msg }` (`core/src/status.rs:12-29`). `kind` is permission/question/auth, only when blocked (`status.rs:17-19`). Per-terminal `status` is the root record; `records` are keyed sub-records; `turn` counts how often `working` ended (`status.rs:1-3`, `:70-80`).
- **Delivery**: `events.subscribe` (framed: its own channel, credited 1 MiB up front, `hc.rs:381-391`). `status` events replace `{status, records, reported}` and `turn` events set `turn` (`core/schema/README.md` Events table; `hc.rs:126-128`). The first paint also replays the 7501 reports (`viewers.rs:345-348`).
- **App derivation**:
  - `terminals_listed` takes `status.state` as the word and `status.app` as the agent name (`window.rs:1843-1869`, `:1883`). It **ignores** `kind`, `progress`, `msg` and `title` (grep: no use in `src/` or `defaults/`).
  - `Model::set_word` (`model.rs:437-456`) tracks `seen`: going from working/blocked to anything else sets `seen = looking`.
  - UI status: `working`, `blocked` (blocked|error), `done` (any other word while unseen), `idle` (`model.rs:496-505`).
  - Rollup priority blocked > done > working > idle (`model.rs:86-95`, `:522-525`).
  - "Looking" = visible in the active tab while the window is active (`window.rs:1879`, `:1884`).
  - `mark_seen` runs on view (`model.rs:482-494`). `seen` is persisted per screen in `view.json` (`mirror.rs:56-60`, `:174-182`).
- **Alerts**:
  - `Model::alert`: entering blocked/error always gives Blocked. Busy to non-busy while not looking gives Done (`model.rs:458-470`).
  - `alert_for` suppresses sub-agents (record has a `parent`) and terminals not in any pane (`model.rs:472-480`; `window.rs:1886`).
  - No alerts on the first listing after start (`window.rs:1892-1897`).
  - Actions: play sound `blocked`/`done` if `settings.sounds.enabled`, un-settle the tab, and unless looking, a toast "<name> needs you" / "<name> finished" whose action is `agent.focus {terminal}` (`window.rs:1913-1951`).
  - No OS-level notifications (grep: toasts only, drawn by the `toast` mod).
- **Settings**: `settings.json` `sounds { enabled (default true), done?, blocked? }` (MP3 paths) (`app/src/settings.rs:488-505`). UI in Settings > Alerts (`app/defaults/frame/settings/main.js:640-657`). App-local.

## 6. Mods, palette, Files, Changes, themes, Settings > Machines

- **Mods runtime**: JS on quickjs-jit through `gpui-kit`'s `gpui-shell`, which renders GPUI elements (`app/Cargo.toml:22`; `vendor/gpui-kit/crates/shell/Cargo.toml:24-52`; `app/defaults/AGENTS.md:27-37`: no HTML/CSS/webview). The API is in `app/defaults/herdr.d.ts` (`exec` `:13`, `hc` `:16`, `readTextFile` `:25`, `machines` `:132`, `spaces` `:135`, `agents` `:176`, `commands` `:235`, `theme(s)` `:265-269`, `shared/setShared` `:385-388`, `watchFolder` `:403`). Mods can't run on Android without that runtime (**high**).
  - `exec(..., {on})` and `readTextFile(..., {on})` shell out to the `hc` CLI: `hc exec --json --on <m> --timeout 30 -- cmd`, and a remote read is `cat` via exec (`app/src/mods.rs:739-760`, `:796-809`, `:835-851`). `hc([...])` runs the local CLI (`mods.rs:851`).
  - Core has RPC equivalents `machine.exec`, `file.read`, `file.write` and `file.edit` (OpenRPC method list), which the app's mods don't use directly.
- **Command palette**: `app/defaults/frame/palette/main.js` lists `commands()` (the app's own table, `app/src/commands.rs:30`) plus `layouts()`, and runs them with `run()` (`palette/main.js:80-98`, `:161`). Entirely app-local.
- **Files panel** (`app/defaults/mods/files/main.js`):
  - Listing: `exec("sh", [-c, script...], {on})` (`:24`); PowerShell on local Windows (`:18`).
  - Git: `exec("git", ...)` (`:82-86`). File preview: `readTextFile` (`:126`).
  - Live refresh: `watchFolder` (local `notify`, `app/src/files.rs`), skipped for remote (`files/main.js:40`).
  - Data comes from the machine via `hc exec`; no dedicated core RPC.
- **Changes panel** (`app/defaults/mods/changes/main.js`): `exec("git", [...READ, "diff", HEAD...], {on})` (`:71-74`, `:126`), `readTextFile` (`:118`), mutating git calls (`:158`). Same transport as Files.
- **Themes**:
  - Presets `graphite`, `ink`, `moss`, `paper` are hard-coded palettes in `app/src/settings.rs:32-78`, listed at `:80`. Defaults are dark=`graphite`, light=`paper` (`:83-84`).
  - Shipped theme files `atom-one-dark` and `win98` live in `app/defaults/themes/*.json` (`settings.rs:97-101`). Format: `{background, foreground, selection, cursor?, ansi[16], colors?}` (`settings.rs:103-114`).
  - Chrome colors are derived from the palette (`resolve`, `settings.rs:306+`). Status colors working/blocked/done come from the preset or from ansi yellow/red/blue (`settings.rs:25-30`, `:310`).
  - Theme choice comes from layout `look.theme` (one name or `{light,dark}`) and `settings.json` `theme`/`appearance` (`layout.rs:176-198`; `settings.rs:369-402`). All app-local.
- **Settings > Machines** (`app/defaults/frame/settings/main.js:11`, `:510-560`): `hc(["machine","ls","--json"])`, filters `temporary`, and runs `hc machine update|rename|rm` through the local CLI. Adding a machine is the `machine-add` mod (`app/defaults/mods/machine-add/main.js:68-91`, `hc machine add`). Data comes from the local core's machine registry; on Android there's no local core, so this is moot for direct ssh.

## 7. Third-party clients, protocol stability, mobile

- Third-party clients are explicitly invited: "Core draws nothing. Use it from your shell, an agent, or your own app." (`core/README.md:6`). See also "Build your own client" (`core/README.md:107-111`).
- Stability:
  - "Status: preview (protocol generation 1, version 0.x)" (`core/schema/README.md:12`).
  - "The API only grows" (`:14-19`). `released.openrpc.json` is guarded by a test against removed methods or fields, new required inputs, narrower inputs and new result kinds. A real break needs a new generation.
  - Errors carry a stable `data.kind` (`:36`). `hello` returns `generation`, `version`, `methods` and `features` (`api/types.rs:113-121`; features `["framed","attach","events","status","shell"]`, `core/src/api.rs:16`).
  - The schema is unchanged between v0.1.15 and HEAD (`git diff v0.1.15 HEAD -- schema` is empty), and `schema/hc.openrpc.json` is byte-identical to `research/hc.openrpc-0.1.15.json`.
  - Core AGENTS: "Do not change documented behavior silently" (`core/AGENTS.md:53`). App AGENTS: "Preserve compatibility with released versions and installed user data" (`app/AGENTS.md:11`). "Same for herdr-core: what the app asks of it goes through Can too" (`app/AGENTS.md:28-29`).
- The **`herdr-app` document format is undocumented** as a contract outside `app/defaults/AGENTS.md:92-160` (behavioural) and the Rust types.
- **Mobile / Android / iOS: no mention anywhere** in either repo's docs, AGENTS.md, SKILL.md or changelogs (grep).
- Core changes after 0.1.15 that matter (`core/CHANGELOG.md` 0.1.16, 2026-10-31):
  - FACTS no longer counts as activity (section 4).
  - Viewer recovery repaints for viewers that join mid-sequence or fall behind (`viewers.rs` diff, `recovering`).
  - Holder reconnect keeps terminals from freezing (#47).
  - Theme/appearance changes reach programs (#46).
  - Configurable shell.
  - No API or schema change.

## 8. Verdict

| Piece an Android client needs | Verdict | Basis | Confidence |
|---|---|---|---|
| Transport: ssh to host, then `herdr-core connect` over stdio | Reimplement (Kotlin ssh lib or a Rust ssh crate); copy core's remote shell line with the `~/.local/bin` fallback | app has no ssh (`hc.rs:254`); core's line at `machines.rs:212-228` | high |
| Frame codec (`[u32 ch][u8 kind][u32 len]`, CREDIT, CLOSE, RESIZE, FOCUS, FACTS) | Reuse with changes: copy `app/src/hc.rs` `encode`/`read_frame` (~25 lines) or `core/.../wire.rs` `encode`/`decode_header`. Not as a dependency (`herdr-core` crate pulls in the server and Zig) | `hc.rs:14-20,229-245`; `wire.rs:1-60` | high |
| JSON-RPC routing, hello/compat check, timeouts | Reuse with changes: `hc.rs` `Core`, `check()`, `USES`, `RpcError`, swapping the subprocess for an ssh channel | `hc.rs:149-213,254-437` | high |
| RPC param/result types | Reimplement: generate from OpenRPC, or hand-write serde structs. The core's `api/types.rs` is tied to clap/schemars/tokio inside a non-published crate | `api/types.rs`; `Cargo.toml:8` | high |
| Event stream to terminal snapshot (`EventLines`) | Reuse as-is (pure serde_json + std) | `hc.rs:33-147` | high |
| Reconnect/backoff and reattach policy | Reuse with changes: logic is simple (1 s retry, resubscribe, reattach on `pid`, refetch docs after `missed`); move it to Kotlin or the Rust core | `window.rs:600-692`; `terminal.rs:218-231` | high |
| `herdr-app` document model (Document/SharedSpace/SharedTab/Node) | Reuse with changes: lift `mirror.rs`, `layout.rs` types (serde only) and `model.rs` tree ops. Write back the full `Node` (all fields) or desktop data is lost | `mirror.rs:9-36`; `layout.rs:9-381` | high |
| Doc CAS sync (latest state, conflict means reread and replace) | Reuse with changes (`mirror_sync.rs` is small and pure apart from `Core`) | `mirror_sync.rs:1-111` | high |
| Per-screen view state (focus, zoom, seen, recent) | Reimplement in Kotlin (local storage); format can mirror `View` | `mirror.rs:38-60` | high |
| VT state (libghostty-vt) | Reuse with changes: `app/crates/ghostty-sys` bindgen plus `app/src/vt.rs` minus gpui couplings. Needs an Android Zig target in `build.rs` and a decision on which Ghostty pin to use (core `1e5cdb8` vs app `291fd05`) | `vt.rs`; `ghostty-sys/build.rs:8-17` | moderate (Android Zig/NDK build unverified) |
| Glyph rendering (cells to pixels), IME, clipboard, sprites | Reimplement (Compose Canvas or a custom GPU view); GPUI has no Android backend | `terminal.rs:1017-1270`; vendored gpui backends | high |
| Key and mouse encoding | Reuse as-is via ghostty encoders in `vt.rs` (`encode_key`/`encode_mouse`); reimplement the Android key mapping (`map_key` is desktop) | `vt.rs:1443-1531`; `terminal.rs:926` | moderate |
| Resize ownership (observe / resumed / active) | Reimplement policy: attach `observe:true` to watch, `resumed:true` to defer, active only when the user types. Mind that RESIZE, FOCUS gain, any DATA (including ghostty replies) and, on 0.1.15, FACTS make the phone the leader | `viewers.rs:75,211-260,289-311`; `v0.1.15 viewers.rs:213-215` | high |
| Agent states and attention (working/blocked/done/idle, seen, rollup, alerts) | Reuse with changes: port `model.rs:86-95,437-525` (small, pure) to Kotlin or the Rust core. Optionally add `kind`/`msg`, which the desktop ignores | `model.rs`; `status.rs:12-29` | high |
| Notifications and sounds | Reimplement (Android notifications; desktop has only in-app toasts plus MP3s) | `window.rs:1913-1951` | high |
| Mods, command palette, Files, Changes, Settings UI | Reimplement in Kotlin for whatever is wanted. The mod runtime (quickjs + GPUI) doesn't port. Files and Changes data can come from core `machine.exec` / `file.read` RPC instead of the `hc` CLI | `mods.rs:739-851`; `herdr.d.ts`; OpenRPC | high |
| Themes (graphite/ink/moss/paper, atom-one-dark, win98) | Reuse with changes: copy palette constants and `resolve()` derivation (data only) | `settings.rs:32-101,306+`; `defaults/themes/*.json` | high |
| Machines registry / Settings > Machines | Reimplement as an app-local host list (the desktop's comes from the local core's `machine.list`, which a phone dialing hosts directly doesn't have) | `settings/main.js:510-560`; `window.rs:652` | moderate |
| Program icons and names (`programs.json`, `icons/`) | Reuse as-is (data files) | `app/defaults/programs.json`; `defaults/icons/` | high |

## Addendum 2026-10-31: Ghostty builds for Android upstream (static read, not yet built)

- Ghostty at the herdr-core pin (`1e5cdb8`) has deliberate Android support in libghostty-vt: `vendor/ghostty/src/build/GhosttyLibVt.zig:273-278` sets 16 KB max page size ("required for Android 15+") and adds NDK paths via `pkg/android-ndk` (finds the NDK through `ANDROID_NDK_HOME`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`).
- Needs Zig 0.16.0 (`build.zig.zon:6`). The herdr-core `zig_target()` mapping (`crates/ghostty-vt/build.rs:65-77`) drops the `android` ABI, so our build must pass `-Dtarget=aarch64-linux-android` itself.
- The actual build attempt was blocked by the session's auto-mode permission check (running external build code); pending the user running it.
- Build result (run by Ezra, 2026-10-31): `zig build -Demit-lib-vt -Doptimize=ReleaseFast -Dcpu=baseline -Dtarget=aarch64-linux-android` with NDK 27.1 succeeded silently. Output in `research/build-android/`: `libghostty-vt.a` and `libghostty-vt.so.0.1.0` (ELF aarch64, 16 KB-aligned LOAD segments, needs only bionic `libc.so`/`libm.so`, symbol versions LIBC and LIBC_N, 205 exported `ghostty_*` functions). A C test calling `ghostty_terminal_new`/`vt_write`/`free` links against both the shared and static library with NDK clang for `aarch64-linux-android26`.
- Not yet verified: running it on a device (no emulator or system image installed). First check of M2.
