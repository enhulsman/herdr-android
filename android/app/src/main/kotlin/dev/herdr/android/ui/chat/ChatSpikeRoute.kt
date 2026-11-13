package dev.herdr.android.ui.chat

import android.content.pm.ApplicationInfo
import android.widget.Toast
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
        onBack = onBack,
        onTerminal = onTerminal,
        onSend = { text ->
            val s = spike ?: return@ChatSpikeScreen
            scope.launch {
                val error = withContext(Dispatchers.IO) { s.send(text) }
                if (error == null) state.draft = "" else Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        },
    )
}
