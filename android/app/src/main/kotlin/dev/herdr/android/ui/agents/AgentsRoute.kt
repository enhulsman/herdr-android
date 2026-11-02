package dev.herdr.android.ui.agents

import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.herdr.android.HerdrApp
import dev.herdr.android.ui.flows.MachineFlows
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The Agents home fed by the app's hub, [flows] (Machines, Add machine) and
 * [home] (the sheet, "Other terminals"). One clock ticks each second while the
 * screen is started; only the ages read it every tick, and the rows are
 * rebuilt once a minute for "last connected".
 */
@Composable
fun AgentsRoute(app: HerdrApp, flows: MachineFlows, home: AgentsHome) {
    val snapshot by app.hubHost.snapshot.collectAsState()
    val startError by app.hubHost.startError.collectAsState()
    val clock = rememberClock()
    val minute by remember(clock) { derivedStateOf { clock.value / 60_000 } }
    val context = LocalContext.current
    val res = context.resources
    val is24 = DateFormat.is24HourFormat(context)
    val time = remember(is24) { timeFormat(is24) }
    // Read without subscribing: the route follows [minute], not every tick.
    val ui = remember(snapshot, startError, minute, res, time) {
        agentsUi(snapshot, Snapshot.withoutReadObservation { clock.value }, res, time, startError)
    }
    val sheet = home.openKey?.let { agentSheet(ui, it, res) }
    val spin = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    BackHandler(enabled = sheet != null) { home.close() }
    val actions = remember(flows, home, app) {
        AgentsActions(
            onMachines = flows::openMachines,
            onAddMachine = flows::openAddMachine,
            onOpen = home::open,
            onCloseSheet = home::close,
            onToggleOthers = home::toggleOthers,
            onRetry = app.hubHost::retry,
        )
    }
    AgentsScreen(ui, home.othersExpanded, sheet, clock, spin, actions)
}

/** "14:05", or "2:05 PM" when the phone uses a 12-hour clock. */
private fun timeFormat(is24: Boolean): (Long) -> String {
    val pattern = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
    return { ms -> pattern.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())) }
}

/** Wall-clock milliseconds, refreshed on each whole second while the screen is started. */
@Composable
private fun rememberClock(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val t = System.currentTimeMillis()
                now.longValue = t
                delay(1_000 - t % 1_000)
            }
        }
    }
    return now
}
