package dev.kudde.android.ui.spaces

import dev.kudde.android.ui.agents.*
import org.junit.Assert.*
import org.junit.Test
import uniffi.kudde_mobile.*

class DeskOrderTest {
    private fun cell(address: String) = DeskCellView(DeskPaneView.Terminal(address), 0f, 0f, 1f, 1f)
    private fun tab(id: String, vararg addresses: String) = DeskTabView(id, id, addresses.map(::cell))
    private fun space(id: String, vararg tabs: DeskTabView) = DeskSpaceView(id, id, 0u, tabs.toList())
    private fun desk(vararg spaces: DeskSpaceView, loose: List<String> = emptyList()) = DeskView(spaces.toList(), loose, null)
    private fun order(desk: DeskView, rows: List<String> = listOf("a", "b", "c", "shell")) = DeskOrder.stops(
        snapshot(listOf(connected("workstation").copy(desk = desk)), rows.map { row(it, AgentAttention.WORKING) }),
    )
    private fun key(address: String) = AgentKey("workstation", address)

    @Test
    fun nextPaneIncludesShellAndPreviousReverses() {
        val stops = DeskOrder.stops(snapshot(listOf(connected("workstation").copy(desk = desk(space("s", tab("t", "a", "b", "shell"))))),
            listOf(row("a", AgentAttention.WORKING), row("b", AgentAttention.IDLE)), listOf(other("shell"))))
        assertEquals(key("b"), DeskOrder.next(stops, key("a"))!!.stop.key)
        assertEquals(key("shell"), DeskOrder.next(stops, key("b"))!!.stop.key)
        assertEquals(key("b"), DeskOrder.previous(stops, key("shell"))!!.stop.key)
        assertFalse(DeskOrder.next(stops, key("a"))!!.entersAnotherTab)
    }

    @Test
    fun nextTabThenNextSpace() {
        val stops = order(desk(space("s1", tab("t1", "a"), tab("t2", "b")), space("s2", tab("t3", "c"))))
        assertEquals(key("b"), DeskOrder.next(stops, key("a"))!!.stop.key)
        assertTrue(DeskOrder.next(stops, key("a"))!!.entersAnotherTab)
        assertEquals(key("c"), DeskOrder.next(stops, key("b"))!!.stop.key)
        assertTrue(DeskOrder.next(stops, key("b"))!!.entersAnotherTab)
        assertTrue(DeskOrder.previous(stops, key("c"))!!.entersAnotherTab)
    }

    @Test
    fun endsNeverWrap() {
        val stops = order(desk(space("s", tab("t", "a", "b"))))
        assertNull(DeskOrder.next(stops, key("b")))
        assertNull(DeskOrder.previous(stops, key("a")))
        assertNull(DeskOrder.next(emptyList(), key("a")))
    }

    @Test
    fun placeholderIsPassedOver() {
        val tab = tab("t", "a", "b").let { it.copy(cells = listOf(it.cells[0], DeskCellView(DeskPaneView.Placeholder("Desk only"), .3f, 0f, .3f, 1f), it.cells[1])) }
        assertEquals(key("b"), DeskOrder.next(order(desk(space("s", tab))), key("a"))!!.stop.key)
    }

    @Test
    fun subAgentVacantPaneIsPassedOverDespiteOtherRow() {
        val tab = tab("t", "a", "b").let { it.copy(cells = listOf(it.cells[0], DeskCellView(DeskPaneView.Vacant, .3f, 0f, .3f, 1f), it.cells[1])) }
        val s = snapshot(listOf(connected("workstation").copy(desk = desk(space("s", tab)))), listOf(row("a", AgentAttention.IDLE), row("b", AgentAttention.IDLE)), listOf(other("child")))
        assertEquals(listOf(key("a"), key("b")), DeskOrder.stops(s).map { it.key })
    }

    @Test
    fun fromSubAgentTerminalNothingOpens() {
        val stops = order(desk(space("s", tab("t", "a", "b"))))
        assertNull(DeskOrder.next(stops, key("child")))
        assertNull(DeskOrder.previous(stops, key("child")))
    }

    @Test
    fun unreachableMachineIsPassedOverInPhoneOrder() {
        val layout = desk(space("s", tab("t", "a")))
        val s = snapshot(listOf(connected("workstation").copy(desk = layout), unreachable("server", 100).copy(desk = layout), connected("third").copy(desk = layout)),
            listOf("third", "server", "workstation").map { row("a", AgentAttention.IDLE, machine = it) })
        val step = DeskOrder.next(DeskOrder.stops(s), key("a"))!!
        assertEquals(AgentKey("third", "a"), step.stop.key)
        assertTrue(step.entersAnotherTab)
    }

    @Test
    fun openedFromStateListUsesDeskOrder() {
        val stops = order(desk(space("s", tab("t", "a", "c", "b"))), listOf("c", "b", "a"))
        assertEquals(key("b"), DeskOrder.next(stops, key("c"))!!.stop.key)
    }

    @Test
    fun reorderedAroundCurrentUsesNewLayout() {
        assertEquals(key("c"), DeskOrder.next(order(desk(space("s", tab("t", "a", "b", "c")))), key("b"))!!.stop.key)
        assertEquals(key("a"), DeskOrder.next(order(desk(space("s", tab("t", "c", "b", "a")))), key("b"))!!.stop.key)
    }

    @Test
    fun movedOpenPaneUsesItsNewTab() {
        val stops = order(desk(space("s", tab("main", "b", "c"), tab("2", "a"), tab("3", "shell"))))
        assertEquals("2", stops.single { it.key == key("a") }.tabId)
        assertEquals(key("shell"), DeskOrder.next(stops, key("a"))!!.stop.key)
    }

    @Test
    fun closedPaneWithLiveTerminalBecomesLoose() {
        val stops = order(desk(space("s", tab("t", "b", "c")), loose = listOf("a", "shell")))
        assertNull(stops.single { it.key == key("a") }.tabId)
        assertEquals(key("c"), DeskOrder.previous(stops, key("a"))!!.stop.key)
        assertTrue(DeskOrder.previous(stops, key("a"))!!.entersAnotherTab)
        assertFalse(DeskOrder.next(stops, key("a"))!!.entersAnotherTab)
    }

    @Test
    fun currentOutsideStopsAndMissingRowsHaveNoInventedStop() {
        val stops = order(desk(space("s", tab("t", "a", "missing", "b")), loose = listOf("missing")))
        assertEquals(listOf(key("a"), key("b")), stops.map { it.key })
        assertNull(DeskOrder.next(stops, key("gone")))
        assertNull(DeskOrder.previous(stops, key("gone")))
    }

    @Test
    fun absentDeskAndDisconnectedCurrentHaveNoStops() {
        val s = snapshot(listOf(connected("workstation"), unreachable("server", 100).copy(desk = desk(space("s", tab("t", "a"))))), listOf(row("a", AgentAttention.IDLE)))
        assertTrue(DeskOrder.stops(s).isEmpty())
    }

    @Test
    fun strongerFlagChecksMachineSpaceTabAndLooseBoundaries() {
        val first = DeskStop(key("a"), "space", "tab")
        val targets = listOf(
            DeskStop(key("b"), "space", "tab") to false,
            DeskStop(key("b"), "space", "other-tab") to true,
            DeskStop(key("b"), "other-space", "tab") to true,
            DeskStop(AgentKey("server", "b"), "space", "tab") to true,
            DeskStop(key("b"), null, null) to true,
        )
        for ((target, expected) in targets) {
            val stops = listOf(first, target)
            assertEquals(expected, DeskOrder.next(stops, first.key)!!.entersAnotherTab)
            assertEquals(expected, DeskOrder.previous(stops, target.key)!!.entersAnotherTab)
        }
    }
}
