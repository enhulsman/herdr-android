package dev.herdr.android.ui.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.herdr.android.hub.HubHost
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.AgentRow
import uniffi.herdr_mobile.HubSnapshot

/**
 * The Agents home's own state: which agent's terminal is open (M2; in M1 a
 * read-only sheet, pick 1b) and whether "Other terminals" is expanded
 * (collapsed by default, agent-list spec).
 *
 * Opening an agent tells the hub the person saw it, so a finished, unseen agent
 * turns idle (agent-list spec, "finished, unseen -> opened on the phone"). A
 * finish while the sheet stays open counts as seen too, as in herdr-app
 * (model.rs set_word: a finish is seen when you are looking at it), but only
 * while [isForeground]: the hub keeps running for 60 s after the app leaves
 * the screen, and then nobody is looking. Coming back with the sheet still
 * open on a finished, unseen agent ([onForeground]) is looking at it. A
 * terminal without status has nothing to mark. Plain enough to test without a
 * composition (AgentsHomeTest).
 */
class AgentsHome(private val hubHost: HubHost, private val isForeground: () -> Boolean) {
    var openKey by mutableStateOf<AgentKey?>(null)
        private set

    var othersExpanded by mutableStateOf(false)
        private set

    fun toggleOthers() {
        othersExpanded = !othersExpanded
    }

    /**
     * The open agent's current finish (Done: finished, unseen) was already
     * marked, so the snapshots that repeat it until the hub turns it idle do
     * not mark it again. Cleared by any other state, so the next finish counts.
     */
    private var finishMarked = false

    fun open(key: AgentKey) {
        openKey = key
        val row = row(hubHost.snapshot.value, key)
        finishMarked = row?.attention == AgentAttention.DONE
        if (row != null) markSeen(key)
    }

    fun close() {
        openKey = null
        finishMarked = false
    }

    /** Follows the hub: a terminal that went away closes its sheet; a finish in view is seen. */
    fun onSnapshot(snapshot: HubSnapshot) {
        val key = openKey ?: return
        val listed = row(snapshot, key) != null ||
            snapshot.others.any { it.machine == key.machine && it.address == key.address }
        if (!listed) {
            close()
            return
        }
        markFinishIfLooking(snapshot)
    }

    /** The app came back to the foreground: an open sheet on a finished, unseen agent is being looked at. */
    fun onForeground() = markFinishIfLooking(hubHost.snapshot.value)

    private fun markFinishIfLooking(snapshot: HubSnapshot) {
        val key = openKey ?: return
        if (row(snapshot, key)?.attention != AgentAttention.DONE) {
            finishMarked = false
            return
        }
        if (finishMarked || !isForeground()) return
        finishMarked = true
        markSeen(key)
    }

    private fun row(snapshot: HubSnapshot, key: AgentKey): AgentRow? =
        snapshot.agents.find { it.machine == key.machine && it.address == key.address }

    private fun markSeen(key: AgentKey) {
        // Rows exist only while the hub runs; without it there is nothing to mark.
        hubHost.hub?.markSeen(key.machine, key.address)
    }

    companion object {
        /** Keeps the open sheet and the group's state across rotation and process death. */
        fun saver(hubHost: HubHost, isForeground: () -> Boolean): Saver<AgentsHome, Any> = listSaver(
            save = { listOf(it.openKey?.machine, it.openKey?.address, it.othersExpanded) },
            restore = { saved ->
                AgentsHome(hubHost, isForeground).apply {
                    val machine = saved[0] as String?
                    val address = saved[1] as String?
                    openKey = if (machine != null && address != null) AgentKey(machine, address) else null
                    othersExpanded = saved[2] as Boolean
                }
            },
        )
    }
}
