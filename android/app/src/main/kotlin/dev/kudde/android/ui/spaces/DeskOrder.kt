package dev.kudde.android.ui.spaces

import dev.kudde.android.ui.agents.AgentKey
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.MachineState

/** A stop's position in the current desk. Null space/tab ids mean a loose terminal. */
data class DeskStop(val key: AgentKey, val spaceId: String?, val tabId: String?)

data class DeskStep(val stop: DeskStop, val entersAnotherTab: Boolean)

/** Stateless: callers read the current snapshot when the gesture ends (design D3). */
object DeskOrder {
    fun stops(snapshot: HubSnapshot): List<DeskStop> {
        val listed = buildSet {
            snapshot.agents.forEach { add(AgentKey(it.machine, it.address)) }
            snapshot.others.forEach { add(AgentKey(it.machine, it.address)) }
        }
        return buildList {
            for (machine in snapshot.machines) {
                if (machine.state !is MachineState.Connected) continue
                val desk = machine.desk ?: continue
                for (space in desk.spaces) for (tab in space.tabs) for (cell in tab.cells) {
                    val pane = cell.pane as? DeskPaneView.Terminal ?: continue
                    val key = AgentKey(machine.name, pane.address)
                    if (key in listed) add(DeskStop(key, space.id, tab.id))
                }
                for (address in desk.loose) {
                    val key = AgentKey(machine.name, address)
                    if (key in listed) add(DeskStop(key, null, null))
                }
            }
        }
    }

    fun next(stops: List<DeskStop>, current: AgentKey): DeskStep? = step(stops, current, 1)

    fun previous(stops: List<DeskStop>, current: AgentKey): DeskStep? = step(stops, current, -1)

    private fun step(stops: List<DeskStop>, current: AgentKey, direction: Int): DeskStep? {
        val index = stops.indexOfFirst { it.key == current }
        if (index < 0) return null
        val from = stops[index]
        val to = stops.getOrNull(index + direction) ?: return null
        return DeskStep(to, from.key.machine != to.key.machine || from.spaceId != to.spaceId || from.tabId != to.tabId)
    }
}
