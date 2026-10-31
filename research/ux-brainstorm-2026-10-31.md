# Herdr for Android: UX brainstorm

Sources read 2026-10-31: herdr-app README, docs/commands.md, defaults/keys.json, docs/mods.md; herdr-core README, schema/README.md, docs/machines.md, and `schema/hc.openrpc.json` (method list and params). **[spec]** marks speculation; everything else is in those files.

## 0. Four facts from the docs that shape everything

1. **The phone can steer without typing into the TTY.** Core has `terminal.send` (paste text + Enter, waits until nobody typed for ~3 s, refuses a blocked program unless `force`), `terminal.keys` (named keys like `esc`, `up`, `ctrl+c`, encoded for the program's keyboard mode), `terminal.interrupt` (esc if it reports status, else ctrl+c) and `terminal.put` (drop a file, e.g. an image, beside the terminal). So a composer-first design is native.
2. **Attaching actively resizes the terminal for every screen.** "The last active viewer sets the size"; `observe: true` viewers "never type or resize". A phone that attaches actively at 45 columns reflows the desktop's TUI. So the default is observe and draw at the terminal's real `cols`×`rows` (the `size` event); taking the size is an explicit act.
3. **Blocked can carry more than the caller assumed.** Each 7501 record has `state`, `msg`, `title`, `app`, `progress` (0-100), and for blocked a `kind`: `permission`, `question` or `auth`, "when the program said". Show `kind`/`msg` when present; when absent, the screen is all there is.
4. **Layout is shared, focus is not.** Spaces/tabs/splits live in each machine's core (the `herdr-app` document via `doc.get`/`doc.set`, versioned, `conflict` on stale write). Screens "keep their own focus and zoom". The doc's JSON format is not described in what I read **[spec: read the app source; start read-only]**.

Connection: `hc connect` speaks the protocol over stdin/stdout, so `ssh box hc connect` is a remote core. A desktop core that added machines merges their terminals and events (`vps/t7`). **[spec]** The phone connects over SSH to one hub core and sees everything; fallback to connecting to each machine directly when the hub (a sleeping laptop) is down.

## 1. Information architecture

| Surface | What it is | Phone | Tablet / unfolded |
|---|---|---|---|
| **Agents** (home) | The merged agents list across spaces and machines, sorted Blocked > Error > Done > Working > Idle | Start screen, full width | Left rail, always visible |
| **Spaces** | Machine > space > tab tree, with status dots rolled up | Drawer from left edge, or second tab of a 2-item bottom bar | Collapsible section of the rail |
| **Terminal** | One pane, full screen, with composer and keys row | Pushed screen; swipe between tabs | Right pane (list-detail) |
| **Tab overview** | A tab's split layout drawn as tappable tiles | Pinch-out from Terminal | Inline; real splits fit at ≥ ~100 cols **[spec]** |
| **Peek** | A sub-agent without a pane | Bottom sheet over the current terminal (half, drag to full) | Side sheet |
| **Machines** | `machine.list`, connection state, `machine.connect` | Settings sub-page | Same |

Two levels only: Agents → Terminal is the main path, Spaces for "where is my build". Unfolding keeps the open terminal and adds the rail **[spec]**.

## 2. Desktop → mobile mapping

| Desktop (command / key) | Mobile | Control | Priority |
|---|---|---|---|
| Agents sidebar, `agent.focus` | Agents home | Tap row | Must |
| `agent.next/prev` (alt-down/up) | Next/prev agent needing you | Vertical swipe on the terminal's title bar | Must |
| `space.select`, `space.next/prev` | Spaces drawer | Left-edge swipe, tap | Must |
| `tab.select`, `tab.next/prev` | Tab strip under the title | Horizontal swipe on the strip (not on the terminal; the terminal scrolls) | Must |
| `pane.go`, `pane.zoom` | Each pane is shown zoomed; tab overview to switch | Pinch-out → tiles → tap | Must |
| `terminal.peek` / `terminal.keep` | Peek sheet with "Keep as tab" | Tap sub-agent; button in sheet | Must |
| Ctrl-click address `t7`, `vps/t7` | Tap a detected address in terminal text | Tap → peek sheet | Must |
| `terminal.copy` | Copy selection or "Copy last output" | Long-press → text selection over `terminal.read` text | Must |
| `terminal.paste` / `paste_image` | Composer paste; image via `terminal.put` then insert path | Composer attach button, share-sheet target | Must (text), Later (image) |
| `tab.settle` / `tab.unsettle` | Put a tab away; it returns when an agent in it needs you | Swipe tab row left | Later |
| `terminal.kill`, `pane.close`, `tab.close`, `space.close` | Destructive actions | Long-press menu, confirm sheet, heavy haptic | Later |
| `space.new`, `tab.new` (with `argv`, `task`, `machine`) | "New agent run": pick machine, folder, template (`templates.list`), task text | FAB on Agents | Later |
| `pane.split`, `pane.swap` | Splitting on a phone | n/a | Drop (tablet: Later) |
| `layout.*`, `mod.*`, `app.theme`, palette | Layouts and mods are JS drawing the desktop window | n/a | Drop (theme: follow system) |
| `app.zoom_*` | Per-screen font size | Pinch in terminal | Must |
| Machine add (SSH) | Machines list, `machine.connect` after login | Settings | Later (add), Must (status) |

## 3. Terminal interaction

**Input model: composer first, direct typing opt-in.**
- Composer bar: a plain Android text field, so IME, autocorrect, dictation and paste just work. Send = `terminal.send`, which waits for a quiet terminal, so the phone never interleaves with typing at the desk.
- Composer shows the terminal's state on its edge: Working (send queues, button says "Send when idle" using `if: idle`), Blocked (send disabled; quick answers shown instead; "Type anyway" sets `force`).
- **Direct mode** (keyboard icon): raw keystrokes on the attach stream for TUIs, menus, vim. Entering it makes the phone an active viewer; prompt once: "Typing here resizes this terminal on your other screens. Keep their size / Use phone size". **[spec]** "Keep their size" sends RESIZE with the current `cols`/`rows` from the `size` event so nothing reflows.

**Extra-keys row** (above the IME, also in composer mode via `terminal.keys`): `esc` `tab` `ctrl` (sticky) `↑` `↓` `←` `→` `enter` `ctrl+c`, plus a swipe-up shelf for `y` `n` `1` `2` `3` `/` `|` `~`. Long-press `esc` = `terminal.interrupt` (reports what it observed; show that as a toast).

**Selection / copy:** long-press swaps in a native text layer built from `terminal.read` (screen + history) **[spec]**.

**Scrollback:** attach with `history` lines; flick scrolls locally; a "jump to live" pill appears when scrolled up. Older history on demand via `terminal.read --lines`.

**Zoom / reflow:** no reflow in observe mode (the program keeps its width). Fit-to-width by default, pinch to zoom, pan when zoomed, landscape for more columns.

**Splits on a narrow screen:** never draw splits side by side on a phone. Show one pane zoomed; a dot indicator shows sibling panes; swipe up on the dots for the tab overview tiles. Tablet ≥ ~100 cols: draw the real split tree from the layout doc **[spec]**.

## 4. Status and attention

| State (from 7501) | Badge | Notification | Quick actions |
|---|---|---|---|
| `blocked` | Amber, top of list | High priority, sound + haptic; title = `title` or terminal name, body = `msg` + `kind` if given, else last non-empty screen lines from `terminal.read` | Open; if `kind=permission`: "Allow" / "Deny" as **[spec]** `terminal.keys` presets per `app`; else inline reply (`terminal.send` with `force`) |
| `error` | Red | Normal priority | Open, Copy last 20 lines |
| `done` (and `turn` events) | Green until viewed, then Idle (as desktop) | Grouped, silent; "3 agents done" | Open, Send follow-up |
| `working` | Spinner, `progress` bar when given | None; optional ongoing notification for one pinned agent | Interrupt |
| no status ever (`reported` false) | Grey, no dot | None | n/a |

Honesty: label a screen excerpt "Screen:" so it is not read as the question; no one-tap Allow without `kind=permission` and a known `app`; show "as of hh:mm" when disconnected.

**Background delivery [spec, real gap]:** core has no push. Either a foreground service holding the event subscription (battery, permanent notification) or a relay on an always-on host forwarding via ntfy/FCM. Decide before trusting the notification mockups.

## 5. Five things that would make it feel smooth

1. **Notification → answer in two taps.** Blocked notification expands to the screen excerpt and the answer chips; answering never opens the app. After sending, the notification updates live to "Working" from the next `status` event, then dismisses.
2. **Attention swipe.** In a terminal, swipe the title bar up/down to jump to the next agent that needs you (`agent.next` order filtered to Blocked/Done), with a light haptic per step and a heavy one at the end of the queue. Clearing a morning's backlog becomes one thumb.
3. **Never disturb the desk.** Observe-by-default plus composer-via-`send` means the phone can never reflow or garble the desktop's terminals; the one moment it could (direct mode) is a deliberate, explained switch.
4. **Instant open.** Keep one framed connection and the event snapshot warm; on tap, show the last `terminal.read` text immediately, swap in the live stream when the attach's first paint arrives (shared-element transition from the list row) **[spec]**. Reconnect with `resumed: true` and refetch docs after `missed`.
5. **Share to agent.** Android share sheet target "Send to agent": text goes to the composer, a screenshot goes through `terminal.put` and its path is inserted. Pick the agent from a list ranked Blocked first.
