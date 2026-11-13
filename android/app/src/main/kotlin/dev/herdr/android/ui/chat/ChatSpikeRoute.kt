package dev.herdr.android.ui.chat

import android.content.pm.ApplicationInfo
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.herdr.android.HerdrApp
import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.terminal.TerminalActions
import dev.herdr.android.ui.terminal.TerminalHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.herdr_mobile.ChatPoll
import uniffi.herdr_mobile.ChatUpload

// Spike for issue 17 (never merged): polls the hub's ChatSpike after each
// event of the terminal, and every half second while the agent works or a
// sent message waits (the log gains finished blocks without an event).

/** The chat switch shows on debug builds only. */
fun chatSpikeEnabled(context: android.content.Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/** The view chosen last, kept while the app runs: the chat stays on through swipes to other agents. */
object ChatSpikeChoice {
    var chosen by mutableStateOf(false)
}

private const val TAG = "ChatSpike"

/**
 * The chat over the terminal. A terminal with no chat (the fallback) shows
 * the terminal beneath, unless the chat was opened there by hand
 * ([explicit]): then it says why.
 */
@Composable
fun ChatSpikeRoute(
    app: HerdrApp,
    key: AgentKey,
    header: TerminalHeader,
    actions: TerminalActions,
    explicit: Boolean,
    onTerminal: () -> Unit,
) {
    val context = LocalContext.current
    val spike = remember(key) { app.hubHost.hub?.openChatSpike(key.machine, key.address) }
    DisposableEffect(spike) { onDispose { spike?.close() } }
    var poll by remember(key) { mutableStateOf<ChatPoll?>(null) }
    val state = remember(key) { ChatSpikeState() }
    val scope = rememberCoroutineScope()
    val images = remember(spike) { SpikeImages(spike) }
    // poll() and older() both change what the hub holds: one at a time.
    val lock = remember(spike) { Mutex() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        scope.launch {
            val picked = withContext(Dispatchers.IO) { uris.mapIndexedNotNull { i, uri -> runCatching { prepareAttachment(context, uri, i) }.getOrNull() } }
            if (picked.size < uris.size) Toast.makeText(context, "Some images couldn't be read", Toast.LENGTH_LONG).show()
            state.attached = state.attached + picked
        }
    }
    LaunchedEffect(spike) {
        while (spike != null) {
            val p = lock.withLock { withContext(Dispatchers.IO) { spike.poll() } }
            Log.d(TAG, "poll ${key.address} ${p.state} rows ${p.rows.size} status ${p.status} · ${p.timings}")
            if (p.state == "ready") state.confirm(p.rows)
            poll = p
            val fast = p.status == "working" || state.pending.isNotEmpty()
            val woke = withContext(Dispatchers.IO) { spike.waitChange(if (fast) 500u else 5000u) }
            if (woke) Log.d(TAG, "event ${key.address}")
        }
    }
    val shown = explicit || poll?.state != "fallback"
    BackHandler(enabled = shown && (state.viewer != null || state.sheet != null)) {
        when {
            state.viewer != null -> state.viewer = null
            state.detail != null -> state.closeDetail()
            else -> state.sheet = null
        }
    }
    if (!shown) return
    ChatSpikeScreen(
        header = header,
        actions = actions,
        poll = poll,
        state = state,
        images = images,
        onTerminal = onTerminal,
        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onOlder = {
            val s = spike
            if (s != null && !state.loadingOlder) scope.launch {
                state.loadingOlder = true
                val older = lock.withLock { withContext(Dispatchers.IO) { s.older() } }
                Log.d(TAG, "older ${key.address} rows ${older.rows.size} cut ${older.cut} · ${older.timings}")
                val before = poll
                if (older.state == "ready" && before != null) {
                    state.base += older.rows.size - before.rows.size
                    poll = older.copy(canSend = before.canSend, status = before.status)
                }
                state.loadingOlder = false
                state.olderLoads++
            }
        },
        onSend = { text ->
            val s = spike ?: return@ChatSpikeScreen
            val ups = state.attached.map { ChatUpload(it.name, it.bytes) }
            val attached = state.attached
            // Shown at once; the log holds it a moment later.
            state.sent(text, ups.size, poll?.rows?.size ?: 0)
            val mine = state.pending.last()
            state.draft = ""
            state.attached = emptyList()
            scope.launch {
                val error = withContext(Dispatchers.IO) { s.sendWithImages(text, ups) }
                if (error != null) {
                    state.pending = state.pending - mine
                    if (state.draft.isEmpty()) state.draft = text
                    state.attached = attached + state.attached
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
            }
        },
    )
}
