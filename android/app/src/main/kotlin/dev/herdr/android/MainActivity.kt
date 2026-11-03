package dev.herdr.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.herdr.android.ui.addmachine.AddMachineRoute
import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.agents.AgentsHome
import dev.herdr.android.ui.agents.AgentsRoute
import dev.herdr.android.ui.flows.MachineFlows
import dev.herdr.android.ui.flows.Screen
import dev.herdr.android.ui.hostkey.HostKeyChangedActions
import dev.herdr.android.ui.hostkey.HostKeyChangedScreen
import dev.herdr.android.ui.hostkey.TrustDialog
import dev.herdr.android.ui.hostkey.hostKeyChange
import dev.herdr.android.ui.machines.MachinesRoute
import dev.herdr.android.ui.phonekey.PhoneKeyRoute
import dev.herdr.android.ui.theme.HerdrTheme
import org.unifiedpush.android.connector.UnifiedPush

class MainActivity : ComponentActivity() {
    /** The agent of a tapped alert, until the Agents home takes it. */
    private val alertOpen = mutableStateOf<AgentKey?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        // Light or dark bar icons follow the system, as the theme does.
        enableEdgeToEdge(SystemBarStyle.auto(transparent, transparent), SystemBarStyle.auto(transparent, transparent))
        val app = application as HerdrApp
        // A rotation recreates the activity with the same intent: the alert was taken already.
        if (savedInstanceState == null) takeAlert(intent)
        // The UnifiedPush way to keep a registration current: ask again on each start; a
        // changed endpoint arrives in HerdrPushService.
        if (app.pushStore.registration != null) UnifiedPush.register(this, HerdrApp.PUSH_INSTANCE)
        setContent { HerdrTheme(app.contrast) { Root(app, alertOpen) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeAlert(intent)
    }

    private fun takeAlert(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_AGENT) return
        val machine = intent.getStringExtra(EXTRA_MACHINE) ?: return
        val terminal = intent.getStringExtra(EXTRA_TERMINAL) ?: return
        alertOpen.value = AgentKey(machine, terminal)
    }

    companion object {
        const val ACTION_OPEN_AGENT = "dev.herdr.android.OPEN_AGENT"
        const val EXTRA_MACHINE = "machine"
        const val EXTRA_TERMINAL = "terminal"
    }
}

/**
 * Switches screens by [MachineFlows] state, starting on the Agents home; system
 * back closes a sheet or dialog first, then returns to Machines, then home.
 */
@Composable
private fun Root(app: HerdrApp, alertOpen: MutableState<AgentKey?>) {
    val flows = rememberSaveable(saver = MachineFlows.saver(app.hubHost)) { MachineFlows(app.hubHost) }
    val isForeground = { app.foreground.isForeground }
    val home = rememberSaveable(saver = AgentsHome.saver(app.hubHost, isForeground)) { AgentsHome(app.hubHost, isForeground) }
    // Back in the foreground with an agent's sheet open: a finish it shows is now seen.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { home.onForeground() }
    val snapshot by app.hubHost.snapshot.collectAsState()
    LaunchedEffect(snapshot) {
        flows.onSnapshot(snapshot)
        home.onSnapshot(snapshot)
    }
    // A tapped alert: its terminal, once the hub lists the agent.
    val alert = alertOpen.value
    LaunchedEffect(alert) {
        alert ?: return@LaunchedEffect
        alertOpen.value = null
        flows.openAgents()
        home.openWhenListed(alert)
    }
    // The export/import notice answers the picker just closed; it does not follow the person to other screens.
    LaunchedEffect(flows.screen) { if (flows.screen != Screen.Machines) app.transfer.dismiss() }
    BackHandler(enabled = flows.screen != Screen.Agents || flows.trusting != null || flows.confirmingForget || flows.removing != null) {
        flows.back()
    }
    Box {
        when (flows.screen) {
            // AgentsRoute's own BackHandler closes its sheet before this one leaves the app.
            Screen.Agents -> AgentsRoute(app, flows, home)
            Screen.Machines -> MachinesRoute(app, flows, onBack = { flows.back() })
            Screen.AddMachine -> AddMachineRoute(flows, snapshot)
            // onSnapshot returns to Machines once the machine no longer shows a changed key.
            Screen.HostKeyChanged -> hostKeyChange(snapshot, flows.machine)?.let { change ->
                HostKeyChangedScreen(
                    change,
                    flows.confirmingForget,
                    flows.error,
                    HostKeyChangedActions(
                        onBack = { flows.back() },
                        onForget = flows::askForget,
                        onConfirmForget = flows::forget,
                        onCancelForget = flows::cancelForget,
                    ),
                )
            }
            Screen.PhoneKey -> PhoneKeyRoute(app.keyManager, flows.machine, onBack = { flows.back() })
        }
        flows.trustPrompt(snapshot)?.let { prompt ->
            TrustDialog(prompt, flows.error, onTrust = flows::trust, onCancel = flows::cancelTrust)
        }
    }
}
