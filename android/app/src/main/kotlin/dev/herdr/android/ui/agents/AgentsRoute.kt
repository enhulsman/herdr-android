package dev.herdr.android.ui.agents

import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import dev.herdr.android.HerdrApp
import dev.herdr.android.ui.components.rememberWallClock
import dev.herdr.android.ui.flows.MachineFlows
import dev.herdr.android.ui.terminal.TerminalRoute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Agents home fed by the app's hub, [flows] (Machines, Add machine) and
 * [home] (the open terminal, "Other terminals"). An open agent shows its
 * terminal screen (TerminalRoute) in place of the list. One clock ticks each second while the
 * screen is started; only the ages read it every tick, and the rows are
 * rebuilt once a minute for "last connected".
 */
@Composable
fun AgentsRoute(app: HerdrApp, flows: MachineFlows, home: AgentsHome) {
    val snapshot by app.hubHost.snapshot.collectAsState()
    val startError by app.hubHost.startError.collectAsState()
    val clock = rememberWallClock()
    val minute by remember(clock) { derivedStateOf { clock.value / 60_000 } }
    val context = LocalContext.current
    val res = context.resources
    val is24 = DateFormat.is24HourFormat(context)
    val time = remember(is24) { timeFormat(is24) }
    // Read without subscribing: the route follows [minute], not every tick.
    val ui = remember(snapshot, startError, minute, res, time) {
        agentsUi(snapshot, Snapshot.withoutReadObservation { clock.value }, res, time, startError)
    }
    val spin = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
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
    val new = app.newTerminal
    val newUi = NewEntryUi(
        enabled = newEnabled(snapshot),
        sheet = new.sheet?.let { newSheetUi(snapshot, it, res) },
        noMachine = new.noMachine,
    )
    val newActions = remember(new) {
        NewSheetActions(
            onNew = new::tapNew,
            onNoMachineGone = new::noMachineShown,
            onMachine = new::chooseMachine,
            onPlace = new::choosePlace,
            onStart = new::start,
            onClose = new::close,
        )
    }
    // Since M2 an agent opens its live terminal; the M1 read-only sheet is no longer shown.
    val open = home.openKey
    if (open != null) {
        TerminalRoute(app, home, open)
    } else {
        BackHandler(enabled = new.sheet != null, onBack = new::close)
        AgentsScreen(ui, home.othersExpanded, null, clock, spin, actions, new = newUi, newActions = newActions)
    }
}

/** "14:05", or "2:05 PM" when the phone uses a 12-hour clock. */
private fun timeFormat(is24: Boolean): (Long) -> String {
    val pattern = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
    return { ms -> pattern.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())) }
}
