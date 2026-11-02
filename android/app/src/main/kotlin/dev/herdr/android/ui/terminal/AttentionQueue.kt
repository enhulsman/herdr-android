package dev.herdr.android.ui.terminal

import dev.herdr.android.ui.agents.AgentKey
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.AgentRow

// Moving between the agents that want the person, from an open terminal. The
// order is the hub's (Rust's attention model sorts the list); this file only
// walks it. Tested in AttentionQueueTest.

private fun AgentRow.key() = AgentKey(machine, address)

private fun AgentRow.needsYou() = attention == AgentAttention.NEEDS_YOU || attention == AgentAttention.ERROR

/** Needs you, or finished and not seen yet: what the title-bar swipe steps through. */
private fun AgentRow.wantsYou() = needsYou() || attention == AgentAttention.DONE

/**
 * The agent a vertical swipe on the title bar opens (agent-list spec, "Jump to
 * the next agent that needs you"): the next ([forward]) or previous one that
 * needs you or finished unseen, in list order from [current]; null at the end
 * of the queue. A terminal not in the list stands before the first row.
 */
fun attentionStep(agents: List<AgentRow>, current: AgentKey, forward: Boolean): AgentKey? {
    val at = agents.indexOfFirst { it.key() == current }
    val candidates = agents.withIndex().filter { (i, row) -> i != at && row.wantsYou() }
    val found = if (forward) {
        candidates.firstOrNull { it.index > at }
    } else {
        candidates.lastOrNull { it.index < at && at >= 0 }
    }
    return found?.value?.key()
}

/** The queue strip (components.queueStrip): how many others need you, and the next of them. */
data class QueueStrip(val count: Int, val next: AgentKey, val nextName: String, val nextKind: String?)

/**
 * Null when no other agent needs you. The next is the first one after
 * [current] in list order, else the first from the top.
 */
fun queueStrip(agents: List<AgentRow>, current: AgentKey): QueueStrip? {
    val at = agents.indexOfFirst { it.key() == current }
    val others = agents.withIndex().filter { (i, row) -> i != at && row.needsYou() }
    if (others.isEmpty()) return null
    val next = (others.firstOrNull { it.index > at } ?: others.first()).value
    return QueueStrip(others.size, next.key(), next.name, next.requestKind)
}
