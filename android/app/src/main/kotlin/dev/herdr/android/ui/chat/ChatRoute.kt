package dev.herdr.android.ui.chat

import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.herdr.android.HerdrApp
import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.components.rememberStarted
import dev.herdr.android.ui.components.rememberUptimeTicker
import dev.herdr.android.ui.terminal.TerminalActions
import dev.herdr.android.ui.terminal.TerminalHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.herdr_mobile.ChatSent
import kotlinx.coroutines.withContext
import uniffi.herdr_mobile.Chat
import uniffi.herdr_mobile.ChatPoll
import uniffi.herdr_mobile.ChatRefusal
import uniffi.herdr_mobile.ChatUpload

// The chat view over the live terminal (issue 17). The terminal route keeps
// a ChatVisit per terminal it shows; the chat's poll loop runs while the chat
// shows, the terminal view's watch while the terminal does (design D5), and
// neither in the background.

/** How long the terminal view waits for an event before its next look at the terminal. */
private const val WATCH_MS = 10_000L

/** One visit to a terminal: its chat in the hub, the state held for it, the switch and the poll lock. */
class ChatVisit(val key: AgentKey, val chat: Chat?, val state: ChatState, val switch: ChatSwitch) {
    /** poll, watch and older all change what the hub holds: one at a time. */
    val lock = Mutex()
}

@Composable
fun rememberChatVisit(app: HerdrApp, key: AgentKey): ChatVisit {
    val hub = app.hubHost.hub
    return remember(key, hub) {
        val chat = hub?.openChat(key.machine, key.address)
        val state = app.chatStates.open(key)
        // What the hub holds is drawn at once; rows read back were dropped on leaving.
        val held = chat?.held()
        state.poll = held
        ChatVisit(key, chat, state, ChatSwitch(app.chatView.chat, held))
    }
}

/** A pending message the agent shows queued: Claude Code by its log, Codex by its screen. */
private fun queuedBy(poll: ChatPoll, screen: () -> List<String>): (Pending) -> Boolean = when (poll.agent) {
    "codex" -> { p -> codexQueued(p, poll.status, screen()) }
    else -> { p -> p.queuedIn(poll.queued) }
}

/**
 * Polls while the chat shows (after each event of the terminal, 0.5 s while
 * the agent works, a message waits or an answer is due, else 5 s) and watches while the
 * terminal shows, to decide the switch. [screen] is the terminal's text, for
 * Codex's queued messages and the agent's input box (a message whose Enter
 * the agent dropped gets one more, through the hub's checks).
 */
@Composable
fun ChatPolling(visit: ChatVisit, app: HerdrApp, screen: () -> List<String>) {
    val chat = visit.chat ?: return
    val state = visit.state
    val switch = visit.switch
    val showing = switch.chat
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(visit, showing) {
        if (showing) app.chatStates.shown = visit.key
        onDispose { if (app.chatStates.shown == visit.key) app.chatStates.shown = null }
    }
    LaunchedEffect(visit, showing) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var event = false
            try {
                while (switch.chat == showing) {
                    val p = visit.lock.withLock { withContext(Dispatchers.IO) { if (showing) chat.poll(event) else chat.watch(event) } }
                    val now = SystemClock.uptimeMillis()
                    if (showing) {
                        state.pending = followPending(state.pending, p.rows, p.status, now, queuedBy(p, screen))
                        if (state.pending.any { it.waitOpen }) state.sawScreen(p.agent, screen(), now)
                        if (state.enterAgain(p.agent, screen(), now, canSend = p.canSend && !p.exited) != null) {
                            withContext(Dispatchers.IO) { chat.enterAgain() }
                        }
                        // Rows first seen now arrived live; the first read and rows read back are history.
                        state.live.saw(p.rows, now, live = state.poll != null)
                    } else {
                        state.live.saw(p.rows, now, live = false)
                    }
                    state.turnEnd = nextTurnEnd(state.turnEnd, state.poll?.status, state.poll?.rows.orEmpty(), p.status, p.rows, now)
                    state.poll = p
                    switch.onPoll(p)
                    if (switch.chat != showing) break
                    val wait = if (showing) pollMs(p.status, state.pending, now, state.turnEnd) else WATCH_MS
                    event = withContext(Dispatchers.IO) { chat.waitChange(wait.toUInt()) }
                }
            } finally {
                // Off screen: no reads, and its return reads only what is new (design D12).
                if (showing) chat.leave()
            }
        }
    }
}

/** The chat over the terminal, while [ChatVisit.switch] shows it. */
@Composable
fun ChatRoute(visit: ChatVisit, app: HerdrApp, header: TerminalHeader, actions: TerminalActions, onChanges: (() -> Unit)? = null, onTerminal: () -> Unit) {
    val context = LocalContext.current
    val state = visit.state
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        app.chatScope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, uri -> runCatching { prepareAttachment(context, uri, i) }.getOrNull() }
            }
            if (picked.size < uris.size) Toast.makeText(context, "Some photos couldn't be read", Toast.LENGTH_LONG).show()
            state.attached = state.attached + picked
        }
    }
    val images = remember(visit) { DeskImages(visit.chat) }
    val motion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    // Loops run only on a started screen: with the screen off they kept the main thread busy.
    val animate = motion && rememberStarted()
    // The clock for pending messages and the working line, ticking only while something waits.
    val waiting = state.pending.isNotEmpty() || state.poll?.status == "working" || state.turnEnd != null
    val clock = rememberUptimeTicker(waiting, everyMs = 250)
    val now = remember(clock) { { clock.value } }
    BackHandler(enabled = state.viewer != null || state.sheet != null) {
        when {
            state.viewer != null -> state.viewer = null
            state.detail != null -> state.closeDetail(sheetCalls(state.poll?.rows.orEmpty(), state.sheet).orEmpty())
            else -> state.closeSheet()
        }
    }
    ChatScreen(
        header = header,
        actions = actions,
        poll = state.poll,
        state = state,
        images = images,
        now = now,
        onTerminal = onTerminal,
        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSend = {
            val poll = state.poll
            val out = state.send(poll?.rows.orEmpty(), poll?.status.orEmpty(), SystemClock.uptimeMillis())
            val chat = visit.chat
            if (out != null) {
                if (chat == null) {
                    state.refused(out, ChatRefusal.Failed("Not connected"))
                } else {
                    app.chatScope.launch {
                        val ups = out.attached.map { ChatUpload(it.name, it.bytes) }
                        when (val sent = withContext(Dispatchers.IO) { chat.sendWithImages(out.typed, ups) }) {
                            is ChatSent.Refused -> state.refused(out, sent.refusal)
                            ChatSent.Typed -> state.typed(out.pending.id, SystemClock.uptimeMillis(), sure = true)
                            ChatSent.Unsure -> state.typed(out.pending.id, SystemClock.uptimeMillis(), sure = false)
                        }
                    }
                }
            }
        },
        onOlder = {
            val chat = visit.chat
            if (chat != null && !state.loadingOlder) {
                scope.launch {
                    state.loadingOlder = true
                    try {
                        val older = visit.lock.withLock { withContext(Dispatchers.IO) { chat.older() } }
                        state.live.saw(older.rows, SystemClock.uptimeMillis(), live = false)
                        val before = state.poll
                        state.poll = if (before != null) older.copy(status = before.status, canSend = before.canSend) else older
                    } finally {
                        state.loadingOlder = false
                        state.olderLoads++
                    }
                }
            }
        },
        animate = animate,
        onChanges = onChanges,
    )
}
