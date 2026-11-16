package dev.herdr.android.ui.changes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.herdr.android.ui.terminal.LineIcon
import dev.herdr.android.ui.terminal.LineIconImage
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.herdr_mobile.ChangesDiff
import uniffi.herdr_mobile.ChangesInterface
import uniffi.herdr_mobile.ChangesList
import uniffi.herdr_mobile.ChangesView

// The Changes screen over the terminal route (design D5, D6): it reads while
// it shows and the phone's screen is on, and sends nothing to the terminal,
// which stays as it was under it.

/** How long one wait for a reason to read lasts; leaving waits at most this long for the hub. */
private const val WAIT_MS = 5_000u

/**
 * Reads, shows, then waits for the hub to ask for the next read (a status or
 * turn event, the connection back); on cancellation (leaving, the screen
 * off) tells the hub, which then holds no events.
 */
suspend fun followChanges(changes: ChangesInterface, waitMs: UInt = WAIT_MS, show: (ChangesView) -> Unit) {
    try {
        while (true) {
            val view = withContext(Dispatchers.IO) { changes.read() }
            show(view)
            while (!withContext(Dispatchers.IO) { changes.waitChange(waitMs) }) Unit
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { changes.leave() }
    }
}

enum class ChangesBack { ToList, Close }

/** Back from a diff shows the list; from the list it closes the screen. */
fun backFrom(view: ChangesView): ChangesBack = if (view.open != null) ChangesBack.ToList else ChangesBack.Close

/** A tapped file, drawn reading before the hub has fetched it. */
fun opening(view: ChangesView, path: String): ChangesView = view.copy(open = path, diff = ChangesDiff.Reading)

/** The list again, without waiting for the hub. */
fun toList(view: ChangesView): ChangesView = view.copy(open = null, diff = null)

/**
 * The Changes screen for one terminal: [changes] is the hub's object (null
 * without a hub), [agent] and [space] name it in the title bar.
 */
@Composable
fun ChangesRoute(changes: ChangesInterface?, agent: String, space: String, onClose: () -> Unit) {
    var view by remember(changes) {
        mutableStateOf(ChangesView(if (changes == null) ChangesList.Failed(null) else ChangesList.Reading, null, null))
    }
    var whole by remember(changes) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listScroll = rememberLazyListState()
    // A diff keeps its place when it is read again; another file starts at its top.
    val diffScroll = remember(view.open) { LazyListState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(changes) {
        if (changes == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { followChanges(changes) { view = it } }
    }
    fun open(path: String?) {
        val c = changes ?: return
        whole = false
        view = if (path == null) toList(view) else opening(view, path)
        scope.launch { view = withContext(Dispatchers.IO) { c.open(path) } }
    }
    val back = {
        when (backFrom(view)) {
            ChangesBack.ToList -> open(null)
            ChangesBack.Close -> onClose()
        }
    }
    BackHandler(onBack = back)
    ChangesScreen(
        agent = agent,
        space = space,
        view = view,
        whole = whole,
        onBack = back,
        onOpen = { open(it) },
        onRetry = {
            val c = changes
            if (c != null) scope.launch { view = withContext(Dispatchers.IO) { c.read() } }
        },
        onWhole = { whole = true },
        listScroll = listScroll,
        diffScroll = diffScroll,
    )
}

/** `.chg`: the title bar's Changes button, in the terminal and the chat view (render 13). */
@Composable
fun ChangesButton(onClick: () -> Unit) {
    val c = HerdrTheme.colors
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(HerdrRadius.button))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = CHANGES_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) { LineIconImage(LineIcon.Changes, c.muted, 21.dp) }
}
