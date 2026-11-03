package dev.herdr.android.ui.machines

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.herdr.android.R
import dev.herdr.android.HerdrApp
import dev.herdr.android.hub.HubHost
import dev.herdr.android.hub.UserAuthGate
import dev.herdr.android.push.timeOfDay
import dev.herdr.android.ui.components.rememberWallClock
import dev.herdr.android.ui.flows.MachineFlows
import dev.herdr.android.ui.theme.ContrastSetting
import dev.herdr.android.ui.transfer.MachineTransfer
import dev.herdr.android.ui.transfer.transferNotice
import uniffi.herdr_mobile.HubException

/** The hub half of [MachinesActions]; MachineFlows.actions adds the flows. */
fun hubActions(userAuth: UserAuthGate, hubHost: HubHost, onBack: (() -> Unit)? = null): MachinesActions =
    MachinesActions.Preview.copy(
        onBack = onBack,
        onUnlock = { userAuth.unlock() },
        onTryAgain = hubHost::retry,
    )

/**
 * This app's App info page in Android settings, for "Network permission is
 * off": Android has no public link to one permission's toggle.
 */
fun appSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

/** This app's notification settings, for "Allow" once Android stops asking. */
fun notificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

/** Whether Android lets the app notify: the permission (Android 13 on) and the app-wide switch. */
private fun notificationsAllowed(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

/** The "Text contrast" row switches [setting]; the theme follows it app-wide. */
fun withTextContrast(actions: MachinesActions, setting: ContrastSetting): MachinesActions =
    actions.copy(onTextContrast = setting::toggle)

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
    val contrast by app.contrast.contrast.collectAsState()
    val clock = rememberWallClock()
    val res = LocalContext.current.resources
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        transfer.export(uri?.toString())
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        transfer.import(uri?.toString())
    }
    val fileName = stringResource(R.string.machines_export_file_name)
    val context = LocalContext.current
    // Alerts: the store's writes (the push service's too) and the permission, read again on each return.
    val alertChanges by app.pushStore.changes.collectAsState()
    var permitted by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permitted = notificationsAllowed(context) }
    // "Turn on" asks for the permission first; the registration follows whatever the answer.
    var registerAfterAsking by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = notificationsAllowed(context)
        if (registerAfterAsking) {
            registerAfterAsking = false
            (context as? Activity)?.let(app::turnOnAlerts)
        }
    }
    val actions = remember(app, flows, transfer, onBack, exportPicker, importPicker, fileName, context, askPermission) {
        val activity = context as? Activity
        val store = app.pushStore
        val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        fun ask() {
            store.permissionAsked = true
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        fun pick(launch: () -> Unit) = try {
            transfer.pickerOpened()
            launch()
        } catch (e: ActivityNotFoundException) {
            transfer.noPicker()
        }
        withTextContrast(flows.actions(hubActions(app.userAuth, app.hubHost, onBack)), app.contrast).copy(
            onExport = { pick { exportPicker.launch(fileName) } },
            onImport = { pick { importPicker.launch(IMPORT_TYPES) } },
            onOpenSettings = {
                try {
                    context.startActivity(appSettingsIntent(context.packageName))
                } catch (_: ActivityNotFoundException) {
                    // No settings app to open: the card's detail still says where.
                }
            },
            onAlerts = { button ->
                val allowed = notificationsAllowed(context)
                when {
                    button != AlertsButton.Allow && (allowed || !canAsk || store.permissionAsked) ->
                        activity?.let(app::turnOnAlerts)
                    button != AlertsButton.Allow -> {
                        registerAfterAsking = true
                        ask()
                    }
                    // Android asks again until the person says no twice; after that only settings change it.
                    canAsk && (!store.permissionAsked || activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true) ->
                        ask()
                    else -> try {
                        context.startActivity(notificationSettingsIntent(context.packageName))
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(appSettingsIntent(context.packageName))
                    }
                }
            },
            onMute = { name, muted -> store.setMuted(name, muted) },
            onSendTest = { name ->
                val hub = app.hubHost.hub
                if (hub != null) {
                    try {
                        hub.sendTest(name)
                        store.testAsked(name, System.currentTimeMillis())
                    } catch (_: HubException) {
                        // Not connected any more: the row goes with the connection.
                    }
                }
            },
        )
    }
    val phoneAlerts = remember(alertChanges, permitted, snapshot, context) {
        val store = app.pushStore
        PhoneAlerts(
            registered = store.registration != null,
            setup = store.setup,
            permitted = permitted,
            machines = snapshot.machines.associate { it.name to store.machine(it.name) },
            timeText = timeOfDay(context),
        )
    }
    // Each second while a countdown shows or a test alert is awaited, else each minute ("last connected").
    val currentAlerts by rememberUpdatedState(phoneAlerts)
    val tick by remember(clock) { derivedStateOf { machinesTick(snapshot, clock.value, currentAlerts) } }
    // Read without subscribing: the route follows [tick], not every clock change.
    val machines = remember(snapshot, startError, tick, res, contrast, phoneAlerts) {
        machinesUi(snapshot, startError, Snapshot.withoutReadObservation { clock.value }, res, contrast, phoneAlerts)
    }
    val ui = machines.copy(
        transfer = transferResult?.let { transferNotice(it, res) },
        removing = flows.removing,
        // A trust dialog over this screen shows its own error.
        error = flows.error.takeIf { flows.trusting == null },
    )
    MachinesScreen(ui, actions)
}
