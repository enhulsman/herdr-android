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
// spec: "finished, unseen -> opened on the phone -> idle on the phone"). A
// finish while the sheet is open counts as seen only while the app is in the
// foreground, as herdr-app's "looking" (model.rs set_word); in the background
// it stays unseen until the person is back. "Other terminals" starts collapsed.
class AgentsHomeTest {
    private val hub = FakeHub()
    private lateinit var listener: HubListener
    private val hubHost = HubHost(createHub = { listener = it; hub }, mainExecutor = Executor { it.run() })
    private var foreground = true
    private val home = AgentsHome(hubHost) { foreground }

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
    fun aFinishWhileTheSheetIsOpenInTheForegroundIsMarkedSeen() {
        // The person is looking at it (herdr-app model.rs: a finish counts as seen when you look at it).
        publish(snapshot(ws, listOf(row("t1", AgentAttention.WORKING))))
        home.open(AgentKey("workstation", "t1"))
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        // One for opening, one for the finish.
        assertEquals(listOf("seen workstation t1", "seen workstation t1"), hub.calls)
        assertEquals(AgentKey("workstation", "t1"), home.openKey)
        // The hub may report the finish again before it turns idle: still one mark.
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        assertEquals(2, hub.calls.size)
    }

    @Test
    fun aFinishWhileTheSheetIsOpenInTheBackgroundIsNotMarkedSeen() {
        // The hub stays connected for 60 s after the app leaves the screen; nobody is looking.
        publish(snapshot(ws, listOf(row("t1", AgentAttention.WORKING))))
        home.open(AgentKey("workstation", "t1"))
        foreground = false
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        assertEquals(listOf("seen workstation t1"), hub.calls)
    }

    @Test
    fun returningToTheForegroundOnAnOpenFinishedUnseenAgentMarksItSeenOnce() {
        publish(snapshot(ws, listOf(row("t1", AgentAttention.WORKING))))
        home.open(AgentKey("workstation", "t1"))
        foreground = false
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        foreground = true
        home.onForeground()
        assertEquals(listOf("seen workstation t1", "seen workstation t1"), hub.calls)
        // Neither the next snapshot nor another return marks it again.
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        home.onForeground()
        assertEquals(2, hub.calls.size)
    }

    @Test
    fun returningToTheForegroundMarksNothingWhenTheOpenAgentHasNotFinished() {
        publish(snapshot(ws, listOf(row("t1", AgentAttention.WORKING))))
        home.open(AgentKey("workstation", "t1"))
        foreground = false
        publish(snapshot(ws, listOf(row("t1", AgentAttention.NEEDS_YOU))))
        foreground = true
        home.onForeground()
        assertEquals(listOf("seen workstation t1"), hub.calls)
    }

    @Test
    fun returningToTheForegroundWithNoSheetOpenMarksNothing() {
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        home.onForeground()
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun aSecondFinishWhileStillOpenIsMarkedAgain() {
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        home.open(AgentKey("workstation", "t1"))
        publish(snapshot(ws, listOf(row("t1", AgentAttention.WORKING))))
        publish(snapshot(ws, listOf(row("t1", AgentAttention.DONE))))
        assertEquals(listOf("seen workstation t1", "seen workstation t1"), hub.calls)
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

    // agent-list spec, "Jump to the next agent that needs you", scenario "Clearing
    // a backlog": three agents need Ezra and he swipes up on the title bar twice;
    // the second and third open in turn, and a third swipe gives the end-of-queue
    // haptic. The hub re-sorts as he goes: an answered agent works, an opened
    // finish turns idle, and either drops below the ones still waiting.
    private fun key(a: String) = AgentKey("workstation", a)

    @Test
    fun clearingABacklogOfAgentsThatTurnWorkingOnceAnswered() {
        val needs = AgentAttention.NEEDS_YOU
        val working = AgentAttention.WORKING
        val idle = AgentAttention.IDLE
        publish(snapshot(ws, listOf(row("a", needs), row("b", needs), row("c", needs), row("d", idle))))
        home.open(key("a"))
        publish(snapshot(ws, listOf(row("b", needs), row("c", needs), row("a", working), row("d", idle))))
        assertTrue(home.step(forward = true))
        assertEquals(key("b"), home.openKey)
        publish(snapshot(ws, listOf(row("c", needs), row("a", working), row("b", working), row("d", idle))))
        assertTrue(home.step(forward = true))
        assertEquals(key("c"), home.openKey)
        publish(snapshot(ws, listOf(row("a", working), row("b", working), row("c", working), row("d", idle))))
        assertFalse(home.step(forward = true))
        assertEquals(key("c"), home.openKey)
    }

    @Test
    fun clearingABacklogOfFinishesThatTurnIdleOnceOpened() {
        val done = AgentAttention.DONE
        val working = AgentAttention.WORKING
        val idle = AgentAttention.IDLE
        publish(snapshot(ws, listOf(row("a", done), row("b", done), row("c", done), row("d", working))))
        home.open(key("a"))
        publish(snapshot(ws, listOf(row("b", done), row("c", done), row("d", working), row("a", idle))))
        assertTrue(home.step(forward = true))
        assertEquals(key("b"), home.openKey)
        publish(snapshot(ws, listOf(row("c", done), row("d", working), row("a", idle), row("b", idle))))
        assertTrue(home.step(forward = true))
        assertEquals(key("c"), home.openKey)
        publish(snapshot(ws, listOf(row("d", working), row("a", idle), row("b", idle), row("c", idle))))
        assertFalse(home.step(forward = true))
        assertEquals(key("c"), home.openKey)
        assertEquals(listOf("seen workstation a", "seen workstation b", "seen workstation c"), hub.calls)
    }

    @Test
    fun swipingDownWalksBackThroughTheOnesStillWaiting() {
        val needs = AgentAttention.NEEDS_YOU
        val working = AgentAttention.WORKING
        publish(snapshot(ws, listOf(row("a", needs), row("b", needs), row("c", needs))))
        home.open(key("a"))
        assertTrue(home.step(forward = true))
        assertTrue(home.step(forward = true))
        // b was answered meanwhile; a still waits.
        publish(snapshot(ws, listOf(row("a", needs), row("c", needs), row("b", working))))
        assertTrue(home.step(forward = false))
        assertEquals(key("a"), home.openKey)
        assertFalse(home.step(forward = false))
    }

    @Test
    fun openingFromTheListStartsANewWalk() {
        val needs = AgentAttention.NEEDS_YOU
        val working = AgentAttention.WORKING
        publish(snapshot(ws, listOf(row("a", needs), row("b", needs), row("c", needs))))
        home.open(key("c"))
        home.close()
        publish(snapshot(ws, listOf(row("a", needs), row("b", needs), row("c", working))))
        home.open(key("a"))
        assertTrue(home.step(forward = true))
        assertEquals(key("b"), home.openKey)
        assertFalse(home.step(forward = true))
    }
}
