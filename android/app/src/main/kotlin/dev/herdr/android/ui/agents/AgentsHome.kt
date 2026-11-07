package dev.herdr.android.ui.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.herdr.android.hub.HubHost
import dev.herdr.android.ui.terminal.attentionStep
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
class AgentsHome(
    private val hubHost: HubHost,
    /** Monotonic ms, for how long a started terminal is waited for. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val isForeground: () -> Boolean,
) {
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

    /**
     * The agents in list order as they were when the person opened a terminal
     * (from the list or the queue strip): the title-bar swipe walks this order,
     * so the agents it opens keep their places while the hub re-sorts them.
     */
    private var walk: List<AgentKey> = emptyList()

    /**
     * The agents this walk opened before the current one, and the ones a swipe
     * down came back from: opening marks an agent seen, so it may no longer
     * want the person, yet a swipe down still goes back to it, like a back
     * button (phone check, round 2).
     */
    private val behind = ArrayDeque<AgentKey>()
    private val ahead = ArrayDeque<AgentKey>()

    /** An alert's agent or a started terminal, opened once the hub lists it. */
    private var pending: AgentKey? = null

    /** Set for a started terminal ([openWhenStarted]): it is waited for until then. */
    private var pendingUntil: Long? = null

    /**
     * A tapped alert (alerts spec, "Blocked while the app is closed"): opens
     * the agent's terminal now if the hub lists it, else once it does. Dropped
     * when the person opens something else, or when its machine lists agents
     * without it (answered and gone meanwhile), so a later terminal at the same
     * address never pops open.
     */
    fun openWhenListed(key: AgentKey) {
        pending = key
        pendingUntil = null
        openPending(hubHost.snapshot.value)
    }

    /**
     * A terminal the "New" sheet started (start-agents spec, "Started and
     * open"): the hub lists it on a later snapshot, an agent among the agents
     * and a fresh shell under "Other terminals", so it opens once listed. The
     * machine's other rows say nothing about it meanwhile; it is given up after
     * [STARTED_WAIT_MS], so a terminal listed much later never pops open.
     */
    fun openWhenStarted(key: AgentKey) {
        pending = key
        pendingUntil = now() + STARTED_WAIT_MS
        openPending(hubHost.snapshot.value)
    }

    private fun openPending(snapshot: HubSnapshot) {
        val key = pending ?: return
        val until = pendingUntil
        when {
            until != null && now() > until -> dropPending()
            listed(snapshot, key) -> open(key)
            until == null && snapshot.agents.any { it.machine == key.machine } -> dropPending()
        }
    }

    private fun dropPending() {
        pending = null
        pendingUntil = null
    }

    fun open(key: AgentKey) {
        dropPending()
        // Taken before marking seen re-sorts the list.
        walk = hubHost.snapshot.value.agents.map { AgentKey(it.machine, it.address) }
        behind.clear()
        ahead.clear()
        show(key)
    }

    /**
     * A vertical swipe on the open terminal's title bar (agent-list spec, "Jump
     * to the next agent that needs you"): opens the next ([forward]) or
     * previous agent in the queue; false at its end.
     */
    fun step(forward: Boolean): Boolean {
        val current = openKey ?: return false
        val snapshot = hubHost.snapshot.value
        val (retrace, other) = if (forward) ahead to behind else behind to ahead
        while (retrace.isNotEmpty()) {
            val key = retrace.removeLast()
            if (row(snapshot, key) != null) {
                other.addLast(current)
                show(key)
                return true
            }
        }
        val next = attentionStep(snapshot.agents, current, forward, walk) ?: return false
        if (forward) behind.addLast(current) else ahead.addLast(current)
        show(next)
        return true
    }

    /**
     * A sideways swipe on the title bar: the next ([next]) or previous agent in
     * list order, whatever its state, like tabs (phone check round 2). The order
     * is the walk's, so agents keep their places while the hub re-sorts; agents
     * gone are skipped, agents listed since follow. False at either end.
     */
    fun tab(next: Boolean): Boolean {
        val current = openKey ?: return false
        val listed = hubHost.snapshot.value.agents.map { AgentKey(it.machine, it.address) }
        val present = listed.toSet()
        val order = LinkedHashSet(walk).apply { addAll(listed) }.filter { it in present || it == current }
        val at = order.indexOf(current)
        val target = order.getOrNull(if (next) at + 1 else if (at < 0) -1 else at - 1) ?: return false
        // A new starting point for the queue's swipe down.
        behind.clear()
        ahead.clear()
        show(target)
        return true
    }

    private fun show(key: AgentKey) {
        openKey = key
        val row = row(hubHost.snapshot.value, key)
        finishMarked = row?.attention == AgentAttention.DONE
        if (row != null) markSeen(key)
    }

    fun close() {
        dropPending()
        openKey = null
        finishMarked = false
        walk = emptyList()
        behind.clear()
        ahead.clear()
    }

    /**
     * Follows the hub: a terminal that went away closes its sheet; a finish in
     * view is seen; a tapped alert's agent opens once listed.
     */
    fun onSnapshot(snapshot: HubSnapshot) {
        openPending(snapshot)
        val key = openKey ?: return
        if (!listed(snapshot, key)) {
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

    private fun listed(snapshot: HubSnapshot, key: AgentKey): Boolean = row(snapshot, key) != null ||
        snapshot.others.any { it.machine == key.machine && it.address == key.address }

    private fun row(snapshot: HubSnapshot, key: AgentKey): AgentRow? =
        snapshot.agents.find { it.machine == key.machine && it.address == key.address }

    private fun markSeen(key: AgentKey) {
        // Rows exist only while the hub runs; without it there is nothing to mark.
        hubHost.hub?.markSeen(key.machine, key.address)
    }

    companion object {
        /** How long a started terminal is waited for before it is given up. */
        const val STARTED_WAIT_MS = 10_000L

        /**
         * Keeps the open sheet, the group's state and a terminal still to open
         * across rotation and process death: a started terminal's result is
         * taken before the hub lists it, so it would not come again.
         */
        fun saver(
            hubHost: HubHost,
            now: () -> Long = { System.nanoTime() / 1_000_000 },
            isForeground: () -> Boolean,
        ): Saver<AgentsHome, Any> = listSaver(
            save = {
                listOf(
                    it.openKey?.machine,
                    it.openKey?.address,
                    it.othersExpanded,
                    it.pending?.machine,
                    it.pending?.address,
                    it.pendingUntil,
                )
            },
            restore = { saved ->
                AgentsHome(hubHost, now, isForeground).apply {
                    openKey = key(saved[0] as String?, saved[1] as String?)
                    othersExpanded = saved[2] as Boolean
                    pending = key(saved.getOrNull(3) as String?, saved.getOrNull(4) as String?)
                    pendingUntil = saved.getOrNull(5) as Long?
                }
            },
        )

        private fun key(machine: String?, address: String?): AgentKey? =
            if (machine != null && address != null) AgentKey(machine, address) else null
    }
}
