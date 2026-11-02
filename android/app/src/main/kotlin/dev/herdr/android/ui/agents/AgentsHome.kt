package dev.herdr.android.ui.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.herdr.android.hub.HubHost
import uniffi.herdr_mobile.HubSnapshot

/**
 * The Agents home's own state: which agent's read-only sheet is open (M1 pick
 * 1b, until the terminal arrives in M2) and whether "Other terminals" is
 * expanded (collapsed by default, agent-list spec).
 *
 * Opening an agent tells the hub the person saw it, so a finished, unseen agent
 * turns idle (agent-list spec, "finished, unseen -> opened on the phone"). Only
 * opening counts: a finish while the sheet stays open is not marked, since the
 * hub keeps running for 60 s after the app leaves the screen and nobody may be
 * looking. A terminal without status has nothing to mark. Plain enough to test
 * without a composition (AgentsHomeTest).
 */
class AgentsHome(private val hubHost: HubHost) {
    var openKey by mutableStateOf<AgentKey?>(null)
        private set

    var othersExpanded by mutableStateOf(false)
        private set

    fun toggleOthers() {
        othersExpanded = !othersExpanded
    }

    fun open(key: AgentKey) {
        openKey = key
        if (hubHost.snapshot.value.agents.any { it.machine == key.machine && it.address == key.address }) {
            markSeen(key)
        }
    }

    fun close() {
        openKey = null
    }

    /** Follows the hub: a terminal that went away closes its sheet. */
    fun onSnapshot(snapshot: HubSnapshot) {
        val key = openKey ?: return
        val listed = snapshot.agents.any { it.machine == key.machine && it.address == key.address } ||
            snapshot.others.any { it.machine == key.machine && it.address == key.address }
        if (!listed) openKey = null
    }

    private fun markSeen(key: AgentKey) {
        // Rows exist only while the hub runs; without it there is nothing to mark.
        hubHost.hub?.markSeen(key.machine, key.address)
    }

    companion object {
        /** Keeps the open sheet and the group's state across rotation and process death. */
        fun saver(hubHost: HubHost): Saver<AgentsHome, Any> = listSaver(
            save = { listOf(it.openKey?.machine, it.openKey?.address, it.othersExpanded) },
            restore = { saved ->
                AgentsHome(hubHost).apply {
                    val machine = saved[0] as String?
                    val address = saved[1] as String?
                    openKey = if (machine != null && address != null) AgentKey(machine, address) else null
                    othersExpanded = saved[2] as Boolean
                }
            },
        )
    }
}
