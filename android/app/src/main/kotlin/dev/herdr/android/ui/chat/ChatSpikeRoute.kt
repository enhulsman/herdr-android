package dev.herdr.android.ui.chat

import android.content.pm.ApplicationInfo
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
import dev.herdr.android.ui.terminal.TerminalHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.herdr_mobile.ChatPoll
import uniffi.herdr_mobile.ChatUpload

// Spike for issue 17 (never merged): polls the hub's ChatSpike every 2 s while
// open; the real feature would read on turn events.

/** The chat switch shows on debug builds only. */
fun chatSpikeEnabled(context: android.content.Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

@Composable
fun ChatSpikeRoute(app: HerdrApp, key: AgentKey, header: TerminalHeader, onBack: () -> Unit, onTerminal: () -> Unit) {
    val context = LocalContext.current
    val spike = remember(key) { app.hubHost.hub?.openChatSpike(key.machine, key.address) }
    DisposableEffect(spike) { onDispose { spike?.close() } }
    var poll by remember(key) { mutableStateOf<ChatPoll?>(null) }
    val state = remember(key) { ChatSpikeState() }
    val scope = rememberCoroutineScope()
    val images = remember(spike) { SpikeImages(spike) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        scope.launch {
            val picked = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { prepareAttachment(context, it) }.getOrNull() } }
            if (picked.size < uris.size) Toast.makeText(context, "Some images couldn't be read", Toast.LENGTH_LONG).show()
            state.attached = state.attached + picked
        }
    }
    BackHandler(enabled = state.viewer != null || state.sheet != null) {
        if (state.viewer != null) state.viewer = null else { state.sheet = null; state.detail = null }
    }
    LaunchedEffect(spike) {
        while (spike != null) {
            poll = withContext(Dispatchers.IO) { spike.poll() }
            delay(2000)
        }
    }
    ChatSpikeScreen(
        header = header,
        poll = poll,
        state = state,
        images = images,
        onBack = onBack,
        onTerminal = onTerminal,
        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSend = { text ->
            val s = spike ?: return@ChatSpikeScreen
            val ups = state.attached.map { ChatUpload(it.name, it.bytes) }
            scope.launch {
                val error = withContext(Dispatchers.IO) { s.sendWithImages(text, ups) }
                if (error == null) {
                    state.draft = ""
                    state.attached = emptyList()
                } else {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
            }
        },
    )
}
