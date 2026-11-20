package dev.kudde.android.ui.spaces

import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentRowUi
import dev.kudde.android.ui.agents.AgentsEmpty
import dev.kudde.android.ui.agents.AgentsUi
import dev.kudde.android.ui.agents.NoticeUi
import dev.kudde.android.ui.agents.OtherRowUi
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.DeskNoteView
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.DeskTabView
import uniffi.kudde_mobile.HubSnapshot

/** Error and blocked both count as needing the person, as in the Agents view. */
data class SpaceCounts(val needsYou: Int, val done: Int, val working: Int, val idle: Int) {
    val attention: AgentAttention?
        get() = when {
            needsYou > 0 -> AgentAttention.NEEDS_YOU
            done > 0 -> AgentAttention.DONE
            working > 0 -> AgentAttention.WORKING
            idle > 0 -> AgentAttention.IDLE
            else -> null
        }
}

/** Full wording/plural rules are supplied by the caller; no unapproved variants. */
data class SpacesTexts(
    val unreadableTabs: (UInt) -> String,
    val spaceSpeech: (String, SpaceCounts) -> String,
    val tabSpeech: (name: String, terminals: Int, needsYou: Int) -> String,
)

sealed interface SpacesItem {
    val key: String

    data class MachineHeader(override val key: String, val name: String) : SpacesItem
    data class SpaceHeader(
        override val key: String,
        val name: String,
        val counts: SpaceCounts,
        val unreadableText: String?,
        val spokenText: String,
    ) : SpacesItem {
        val attention: AgentAttention? get() = counts.attention
    }
    data class TabLine(override val key: String, val tab: DeskTabView, val spokenText: String) : SpacesItem
    data class Agent(override val key: String, val row: AgentRowUi) : SpacesItem
    data class Other(override val key: String, val row: OtherRowUi) : SpacesItem
    data class LooseHeader(override val key: String, val text: String = "Not in a space") : SpacesItem
    data class LayoutNote(override val key: String, val text: String = "Layout could not be read") : SpacesItem
}

data class SpacesUi(
    val items: List<SpacesItem>,
    val notices: List<NoticeUi>,
    val machinesBadge: Boolean,
    val storageNotice: String?,
    /** Existing no-machines and failed-start states are shared with Agents. */
    val empty: AgentsEmpty?,
    val emptyText: String?,
    val startError: String?,
)

/**
 * Reuses the resolved Agents rows, including cards, requests and stale-time marks.
 * Rust's seated desk selects eligible terminals; never fall back to all rows,
 * since Other terminals may contain a sub-agent's shell (design D1/D6).
 */
fun spacesUi(snapshot: HubSnapshot, agents: AgentsUi, texts: SpacesTexts): SpacesUi {
    val rows = (agents.needsYou + agents.done + agents.working + agents.idle).associateBy { it.key }
    val others = agents.others.associateBy { it.key }
    fun counts(keys: List<AgentKey>): SpaceCounts {
        val states = keys.mapNotNull { rows[it]?.attention }
        return SpaceCounts(
            states.count { it == AgentAttention.NEEDS_YOU || it == AgentAttention.ERROR },
            states.count { it == AgentAttention.DONE },
            states.count { it == AgentAttention.WORKING },
            states.count { it == AgentAttention.IDLE },
        )
    }
    fun addresses(machine: String, tab: DeskTabView) = tab.cells.mapNotNull {
        (it.pane as? DeskPaneView.Terminal)?.let { pane -> AgentKey(machine, pane.address) }
    }
    val items = buildList {
        for (machine in snapshot.machines) {
            val desk = machine.desk ?: continue
            if (snapshot.machines.size > 1) add(SpacesItem.MachineHeader(listKey("machine", machine.name), machine.name))
            fun addRow(key: AgentKey) {
                val itemKey = listKey("row", key.machine, key.address)
                rows[key]?.let { add(SpacesItem.Agent(itemKey, it)) }
                    ?: others[key]?.let { add(SpacesItem.Other(itemKey, it)) }
            }
            if (desk.note == DeskNoteView.UNREADABLE) add(SpacesItem.LayoutNote(listKey("note", machine.name)))
            for (space in desk.spaces) {
                val state = counts(space.tabs.flatMap { addresses(machine.name, it) })
                add(SpacesItem.SpaceHeader(
                    listKey("space", machine.name, space.id), space.name, state,
                    space.unreadableTabs.takeIf { it > 0u }?.let(texts.unreadableTabs),
                    texts.spaceSpeech(space.name, state),
                ))
                for (tab in space.tabs) {
                    val keys = addresses(machine.name, tab)
                    val terminalCount = keys.count { it in rows || it in others }
                    add(SpacesItem.TabLine(listKey("tab", machine.name, tab.id), tab,
                        texts.tabSpeech(tab.name, terminalCount, counts(keys).needsYou)))
                    keys.forEach(::addRow)
                }
            }
            val loose = desk.loose.map { AgentKey(machine.name, it) }.filter { it in rows || it in others }
            if (loose.isNotEmpty()) {
                add(SpacesItem.LooseHeader(listKey("loose", machine.name)))
                loose.forEach(::addRow)
            }
        }
    }
    val empty = when {
        agents.empty == AgentsEmpty.StartFailed -> AgentsEmpty.StartFailed
        snapshot.machines.isEmpty() -> AgentsEmpty.NoMachines
        else -> null
    }
    return SpacesUi(
        items, agents.notices, agents.machinesBadge, agents.storageNotice, empty,
        if (empty == null && items.none { it is SpacesItem.SpaceHeader || it is SpacesItem.Agent || it is SpacesItem.Other }) "No spaces yet" else null,
        agents.startError,
    )
}

/** Length prefixes keep delimiter-like ids distinct; rows retain their key when moved. */
private fun listKey(kind: String, vararg parts: String): String =
    kind + parts.joinToString("") { ":${it.length}:$it" }
