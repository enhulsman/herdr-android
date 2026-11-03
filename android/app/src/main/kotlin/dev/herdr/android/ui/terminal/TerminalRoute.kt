package dev.herdr.android.ui.terminal

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.herdr.android.HerdrApp
import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.agents.AgentsHome
import dev.herdr.android.ui.agents.ProgramIcon
import dev.herdr.android.ui.agents.SheetTone
import dev.herdr.android.ui.agents.agentSheet
import dev.herdr.android.ui.agents.agentsUi
import kotlinx.coroutines.delay
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.TerminalViewInterface

/**
 * The terminal screen fed by the app: opens [key] through the hub once the
 * screen knows the phone's grid, shows the cached last screen meanwhile,
 * sends the phone size once the keyboard settles, keeps the zoom, and on
 * leaving detaches and caches the last screen. The queue strip and the
 * title-bar swipe open other agents through [home], which marks them seen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalRoute(app: HerdrApp, home: AgentsHome, key: AgentKey) {
    val context = LocalContext.current
    val res = context.resources
    val snapshot by app.hubHost.snapshot.collectAsState()
    val session = remember(key) { TerminalSession(key, context.mainExecutor) }
    val screen = remember(key) { TerminalScreenState(app.terminalZoom[key]) }
    val opener = remember(key) {
        TerminalOpener(session) { size ->
            // Opened at the phone's size (phone check round 2); closing lets the desk take its size back.
            app.hubHost.hub?.openTerminal(key.machine, key.address, size.cols.toUShort(), size.rows.toUShort(), true, session.listener)
        }
    }
    var phone by remember(key) { mutableStateOf<GridSize?>(null) }
    LaunchedEffect(phone) {
        val size = phone ?: return@LaunchedEffect
        opener.onPhoneGrid(size, SystemClock.uptimeMillis())
        delay(TerminalMetrics.SETTLE_MS)
        opener.tick(SystemClock.uptimeMillis())
    }

    // Instant open: the last screen, until the live first paint replaces it.
    DisposableEffect(key) {
        var cancelled = false
        app.terminalCacheExecutor.execute {
            val frame = app.terminalCache.load(key) ?: return@execute
            context.mainExecutor.execute { if (!cancelled) session.showCached(frame) }
        }
        onDispose {
            cancelled = true
            val last = session.close()
            if (last != null) app.terminalCacheExecutor.execute { app.terminalCache.store(key, last) }
        }
    }
    // Leaving the app may be the last chance to keep the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (session.painted) {
            session.grid.toFullFrameOrNull()?.let { frame ->
                app.terminalCacheExecutor.execute { app.terminalCache.store(key, frame) }
            }
        }
    }

    // The window resizes for the keyboard while the terminal is open, so the grid follows it.
    val activity = context as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { if (before != null) window.setSoftInputMode(before) }
    }

    val sink = remember(session) {
        object : KeyboardSink {
            override fun type(text: String) = session.type(text)
            override fun backspace() = session.backspace()
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (!imeVisible && !screen.typing) screen.inputOpen = false
    }

    val report = session.report
    LaunchedEffect(report) {
        if (report != null) {
            Toast.makeText(context, report, Toast.LENGTH_LONG).show()
            session.reportShown()
        }
    }

    BackHandler {
        when {
            screen.composeOpen -> {
                screen.composeOpen = false
                screen.composeText = ""
            }
            screen.selecting -> screen.selecting = false
            else -> home.close()
        }
    }

    val ui = remember(snapshot, key, res) { terminalUi(snapshot, key, res) }
    val actions = remember(key, home) {
        TerminalActions(
            onBack = home::close,
            onOpen = home::open,
            onSwipe = home::step,
            onPhoneGrid = { phone = it },
            onWatchView = { app.terminalZoom[key] = it.zoom },
        )
    }
    Box {
        val input = TerminalKeyboard(key, sink)
        TerminalScreen(ui, session, screen, actions, input)
    }
}

/** The title bar and queue strip for [key], from the hub's rows. */
fun terminalUi(snapshot: HubSnapshot, key: AgentKey, res: android.content.res.Resources): TerminalUi {
    val sheet = agentSheet(agentsUi(snapshot, 0L, res, { "" }), key, res)
    val header = sheet?.let { TerminalHeader(it.name, it.icon, it.lineTwo, it.state, it.tone) }
        ?: TerminalHeader(key.address, ProgramIcon.Terminal, key.machine, null, SheetTone.None)
    return TerminalUi(key, header, queueStrip(snapshot.agents, key))
}

/**
 * Opens the terminal once the screen first knows the phone's grid (the core
 * needs it to type at phone size), then hands later sizes to a [SizeSettler],
 * so the phone size goes out once the keyboard has settled.
 */
class TerminalOpener(
    private val session: TerminalSession,
    private val open: (GridSize) -> TerminalViewInterface?,
) {
    private var settler: SizeSettler? = null

    fun onPhoneGrid(size: GridSize, nowMs: Long) {
        val settle = settler
        if (settle != null) {
            settle.offer(size, nowMs)
            return
        }
        // Without a running hub there is nothing to open yet; the next layout tries again.
        val view = open(size) ?: return
        session.attach(view)
        settler = SizeSettler(TerminalMetrics.SETTLE_MS, opened = size, send = session::phoneSize)
    }

    fun tick(nowMs: Long) {
        settler?.tick(nowMs)
    }
}
