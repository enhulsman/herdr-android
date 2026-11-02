package dev.herdr.android.ui.agents

import dev.herdr.android.hub.FakeHub
import dev.herdr.android.hub.HubHost
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.HubListener
import uniffi.herdr_mobile.HubSnapshot

// The read-only agent sheet (M1 pick 1b, decisions.md 2026-11-01): opening an
// agent marks it seen through the hub, so "finished, unseen" clears (agent-list
// spec: "finished, unseen -> opened on the phone -> idle on the phone"). Only
// opening marks: a finish while the sheet is open is not. "Other terminals"
// starts collapsed.
class AgentsHomeTest {
    private val hub = FakeHub()
    private lateinit var listener: HubListener
    private val hubHost = HubHost(createHub = { listener = it; hub }, mainExecutor = Executor { it.run() })
    private val home = AgentsHome(hubHost)

    init {
        hubHost.start()
    }

    private fun publish(s: HubSnapshot) {
        listener.onSnapshot(s)
        home.onSnapshot(s)
    }

    private val ws = listOf(connected("workstation"))

    @Test
    fun otherTerminalsStartCollapsed() {
        assertFalse(home.othersExpanded)
        home.toggleOthers()
        assertTrue(home.othersExpanded)
        home.toggleOthers()
        assertFalse(home.othersExpanded)
    }

    @Test
    fun openingAFinishedAgentMarksItSeen() {
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        home.open(AgentKey("workstation", "t1"))
        assertEquals(AgentKey("workstation", "t1"), home.openKey)
        assertEquals(listOf("seen workstation t1"), hub.calls)
    }

    @Test
    fun openingAnyAgentMarksItSeen() {
        publish(snapshot(ws, listOf(row("t1", AgentAttention.NEEDS_YOU))))
        home.open(AgentKey("workstation", "t1"))
        assertEquals(listOf("seen workstation t1"), hub.calls)
    }

    @Test
    fun anOtherTerminalHasNothingToMarkSeen() {
        publish(snapshot(ws, emptyList(), others = listOf(other("t3"))))
        home.open(AgentKey("workstation", "t3"))
        assertEquals(AgentKey("workstation", "t3"), home.openKey)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun aFinishWhileTheSheetIsOpenIsNotMarkedSeen() {
        // Only opening counts as seeing (agent-list spec): the hub stays connected for
        // 60 s after the app leaves the foreground, so a later finish may go unwatched.
        publish(snapshot(ws, listOf(row("t1", AgentAttention.WORKING))))
        home.open(AgentKey("workstation", "t1"))
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        assertEquals(listOf("seen workstation t1"), hub.calls)
        assertEquals(AgentKey("workstation", "t1"), home.openKey)
    }

    @Test
    fun openingAFinishedAgentMarksItSeenExactlyOnce() {
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        home.open(AgentKey("workstation", "t1"))
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        assertEquals(listOf("seen workstation t1"), hub.calls)
    }

    @Test
    fun closingStopsMarking() {
        publish(snapshot(ws, listOf(row("t1", AgentAttention.WORKING))))
        home.open(AgentKey("workstation", "t1"))
        home.close()
        assertNull(home.openKey)
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        assertEquals(listOf("seen workstation t1"), hub.calls)
    }

    @Test
    fun theSheetClosesWhenItsTerminalIsGone() {
        publish(snapshot(ws, listOf(row("t1", AgentAttention.IDLE))))
        home.open(AgentKey("workstation", "t1"))
        publish(snapshot(ws, emptyList()))
        assertNull(home.openKey)
    }

    @Test
    fun anAgentOnAnotherMachineWithTheSameAddressIsNotTheOneOpen() {
        val both = listOf(connected("workstation"), connected("server"))
        publish(snapshot(both, listOf(row("t1", AgentAttention.WORKING, machine = "server"), row("t1", AgentAttention.WORKING))))
        home.open(AgentKey("server", "t1"))
        publish(snapshot(both, listOf(row("t1", AgentAttention.DONE), row("t1", AgentAttention.WORKING, machine = "server"))))
        // Opening marked the server's t1; the workstation's t1 finishing is not the one open.
        assertEquals(listOf("seen server t1"), hub.calls)
    }
}
