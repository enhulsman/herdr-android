package dev.herdr.android.ui.terminal

import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.agents.row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.herdr_mobile.AgentAttention

// agent-list spec, "Jump to the next agent that needs you": a vertical swipe on
// the title bar steps through the agents that need you or finished unseen, in
// list order (the hub's order); and the queue strip (components.queueStrip).
class AttentionQueueTest {
    private val agents = listOf(
        row("a", AgentAttention.NEEDS_YOU, name = "docs nav", kind = "Permission"),
        row("b", AgentAttention.NEEDS_YOU, name = "api tests", kind = "Question"),
        row("c", AgentAttention.ERROR, name = "og tags", kind = "Error"),
        row("d", AgentAttention.DONE, name = "readme"),
        row("e", AgentAttention.WORKING, name = "pricing page"),
        row("f", AgentAttention.IDLE, name = "changelog"),
    )

    private fun key(a: String) = AgentKey("workstation", a)

    @Test
    fun swipingUpTwiceOpensTheSecondAndThirdThenTheQueueEnds() {
        val three = agents.take(3) + agents.drop(4)
        assertEquals(key("b"), attentionStep(three, key("a"), forward = true))
        assertEquals(key("c"), attentionStep(three, key("b"), forward = true))
        assertNull(attentionStep(three, key("c"), forward = true))
    }

    @Test
    fun finishedUnseenAgentsAreInTheQueueWorkingAndIdleOnesAreNot() {
        assertEquals(key("d"), attentionStep(agents, key("c"), forward = true))
        assertNull(attentionStep(agents, key("d"), forward = true))
    }

    @Test
    fun swipingDownGoesBack() {
        assertEquals(key("c"), attentionStep(agents, key("d"), forward = false))
        assertEquals(key("a"), attentionStep(agents, key("b"), forward = false))
        assertNull(attentionStep(agents, key("a"), forward = false))
    }

    @Test
    fun fromAWorkingAgentTheNextIsBelowIt() {
        val order = listOf(agents[4], agents[0], agents[5], agents[1])
        assertEquals(key("b"), attentionStep(order, key("f"), forward = true))
        assertEquals(key("a"), attentionStep(order, key("f"), forward = false))
    }

    @Test
    fun fromATerminalOutsideTheListTheQueueStartsAtTheTop() {
        assertEquals(key("a"), attentionStep(agents, key("zsh"), forward = true))
        assertNull(attentionStep(agents, key("zsh"), forward = false))
    }

    @Test
    fun theStripCountsTheOthersThatNeedYouAndNamesTheNext() {
        val strip = queueStrip(agents, key("a"))!!
        assertEquals(2, strip.count)
        assertEquals(key("b"), strip.next)
        assertEquals("api tests", strip.nextName)
        assertEquals("Question", strip.nextKind)
    }

    @Test
    fun theStripsNextWrapsToTheTop() {
        val strip = queueStrip(agents, key("c"))!!
        assertEquals(2, strip.count)
        assertEquals(key("a"), strip.next)
    }

    @Test
    fun noStripWhenNoOtherAgentNeedsYou() {
        assertNull(queueStrip(listOf(agents[0], agents[3], agents[4]), key("a")))
        assertEquals(1, queueStrip(listOf(agents[0], agents[3]), key("d"))!!.count)
    }

    @Test
    fun agentsOnOtherMachinesAreTheirOwnTerminals() {
        val list = listOf(
            row("t1", AgentAttention.NEEDS_YOU, machine = "workstation"),
            row("t1", AgentAttention.NEEDS_YOU, machine = "server"),
        )
        assertEquals(AgentKey("server", "t1"), attentionStep(list, AgentKey("workstation", "t1"), forward = true))
    }
}
