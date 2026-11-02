package dev.herdr.android.ui.machines

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.herdr.android.HerdrApp
import dev.herdr.android.hub.HubHost
import dev.herdr.android.hub.UserAuthGate
import dev.herdr.android.ui.flows.MachineFlows
import kotlinx.coroutines.delay

/** The hub half of [MachinesActions]; MachineFlows.actions adds the flows. */
fun hubActions(userAuth: UserAuthGate, hubHost: HubHost, onBack: (() -> Unit)? = null): MachinesActions =
    MachinesActions.Preview.copy(
        onBack = onBack,
        onUnlock = { userAuth.unlock() },
        onTryAgain = hubHost::retry,
    )

/** The Machines screen fed by the app's hub and [flows]; "last connected" refreshes every 30 s. */
@Composable
fun MachinesRoute(app: HerdrApp, flows: MachineFlows, onBack: (() -> Unit)?) {
    val snapshot by app.hubHost.snapshot.collectAsState()
    val startError by app.hubHost.startError.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val res = LocalContext.current.resources
    val actions = remember(app, flows, onBack) { flows.actions(hubActions(app.userAuth, app.hubHost, onBack)) }
    MachinesScreen(machinesUi(snapshot, startError, now, res), actions)
}
