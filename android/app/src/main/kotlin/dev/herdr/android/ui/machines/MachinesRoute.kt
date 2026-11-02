package dev.herdr.android.ui.machines

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.herdr.android.R
import dev.herdr.android.HerdrApp
import dev.herdr.android.hub.HubHost
import dev.herdr.android.hub.UserAuthGate
import dev.herdr.android.ui.components.rememberWallClock
import dev.herdr.android.ui.flows.MachineFlows
import dev.herdr.android.ui.transfer.MachineTransfer
import dev.herdr.android.ui.transfer.transferNotice

/** The hub half of [MachinesActions]; MachineFlows.actions adds the flows. */
fun hubActions(userAuth: UserAuthGate, hubHost: HubHost, onBack: (() -> Unit)? = null): MachinesActions =
    MachinesActions.Preview.copy(
        onBack = onBack,
        onUnlock = { userAuth.unlock() },
        onTryAgain = hubHost::retry,
    )

/** What the import picker offers: JSON, and everything, since some storage apps label JSON as something else. */
private val IMPORT_TYPES = arrayOf("application/json", "*/*")

/**
 * The Machines screen fed by the app's hub and [flows]; "retrying in 30 s"
 * counts down and "last connected" ages from the shared wall clock. The system file pickers for export and import are
 * launched here; the app's [MachineTransfer] does the file and hub work in its
 * own scope, so a rotation only re-reads the outcome.
 */
@Composable
fun MachinesRoute(app: HerdrApp, flows: MachineFlows, onBack: (() -> Unit)?) {
    val transfer = app.transfer
    val transferResult by transfer.result.collectAsState()
    val snapshot by app.hubHost.snapshot.collectAsState()
    val startError by app.hubHost.startError.collectAsState()
    val clock = rememberWallClock()
    // Each second while a countdown shows, else each minute ("last connected").
    val tick by remember(clock) { derivedStateOf { machinesTick(snapshot, clock.value) } }
    val res = LocalContext.current.resources
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        transfer.export(uri?.toString())
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        transfer.import(uri?.toString())
    }
    val fileName = stringResource(R.string.machines_export_file_name)
    val actions = remember(app, flows, transfer, onBack, exportPicker, importPicker, fileName) {
        fun pick(launch: () -> Unit) = try {
            transfer.pickerOpened()
            launch()
        } catch (e: ActivityNotFoundException) {
            transfer.noPicker()
        }
        flows.actions(hubActions(app.userAuth, app.hubHost, onBack)).copy(
            onExport = { pick { exportPicker.launch(fileName) } },
            onImport = { pick { importPicker.launch(IMPORT_TYPES) } },
        )
    }
    // Read without subscribing: the route follows [tick], not every clock change.
    val machines = remember(snapshot, startError, tick, res) {
        machinesUi(snapshot, startError, Snapshot.withoutReadObservation { clock.value }, res)
    }
    val ui = machines.copy(
        transfer = transferResult?.let { transferNotice(it, res) },
        removing = flows.removing,
        // A trust dialog over this screen shows its own error.
        error = flows.error.takeIf { flows.trusting == null },
    )
    MachinesScreen(ui, actions)
}
