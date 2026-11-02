package dev.herdr.android.ui.terminal

import dev.herdr.android.ui.agents.AgentKey
import org.junit.Assert.assertEquals
import org.junit.Test

// The terminal opens once the screen knows the phone's grid (the core types at
// that size); later sizes go out once the keyboard has settled.
class TerminalOpenerTest {
    private val session = TerminalSession(AgentKey("workstation", "t1")) { it.run() }
    private val view = FakeTerminalView()
    private val opened = mutableListOf<GridSize>()
    private var hubRunning = true
    private val opener = TerminalOpener(session) { size ->
        if (hubRunning) view.also { opened += size } else null
    }

    @Test
    fun opensAtTheFirstPhoneGridThenSendsSettledSizes() {
        opener.onPhoneGrid(GridSize(53, 45), 0)
        opener.tick(300)
        assertEquals(listOf(GridSize(53, 45)), opened)
        assertEquals(emptyList<String>(), view.calls)
        opener.onPhoneGrid(GridSize(53, 38), 1000)
        opener.onPhoneGrid(GridSize(53, 31), 1016)
        opener.tick(1100)
        opener.tick(1300)
        assertEquals(listOf("size 53x31"), view.calls)
        assertEquals(1, opened.size)
    }

    @Test
    fun withoutAHubItTriesAgainOnTheNextLayout() {
        hubRunning = false
        opener.onPhoneGrid(GridSize(53, 45), 0)
        assertEquals(emptyList<GridSize>(), opened)
        hubRunning = true
        opener.onPhoneGrid(GridSize(53, 45), 10)
        assertEquals(listOf(GridSize(53, 45)), opened)
    }
}
