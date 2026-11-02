package dev.herdr.android.ui.terminal

import dev.herdr.android.ui.agents.AgentKey
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.Armed
import uniffi.herdr_mobile.RowKeyTap
import uniffi.herdr_mobile.TermPhase
import uniffi.herdr_mobile.TermState
import uniffi.herdr_mobile.TerminalViewInterface

// One open terminal on the phone: frames and states from the Rust core reach
// the grid on the main thread; input goes to the terminal; the instant-open
// cache shows the last screen only until the live first paint; leaving
// detaches (terminal-input spec, "Leaving the terminal stops leading").
class TerminalSessionTest {
    private val queued = ArrayDeque<Runnable>()
    private val main = Executor { queued += it }
    private fun runMain() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private val view = FakeTerminalView()
    private val session = TerminalSession(AgentKey("workstation", "t1"), main).also { it.attach(view) }

    @Test
    fun framesApplyOnTheMainThreadInOrder() {
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("one"))))
        session.listener.onFrame(frameOf(6, 2, mapOf(1 to cellsOf("two")), full = false))
        assertTrue(session.grid.isEmpty)
        runMain()
        assertEquals("one\ntwo", session.grid.text())
        assertTrue(session.painted)
        assertEquals(2, session.version)
    }

    @Test
    fun theCachedScreenShowsUntilTheLiveOneArrives() {
        val cached = frameOf(6, 2, mapOf(0 to cellsOf("cached")))
        assertTrue(session.showCached(cached))
        assertEquals("cached", session.grid.text())
        assertFalse(session.painted)
        // The live first paint replaces it in one step: no blank frame between.
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("live"))))
        runMain()
        assertEquals("live", session.grid.text())
        // A cache read that comes back late is ignored.
        assertFalse(session.showCached(cached))
        assertEquals("live", session.grid.text())
    }

    @Test
    fun aMalformedFrameIsDroppedAndTheScreenKept() {
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("ok"))))
        session.listener.onFrame(byteArrayOf(1, 2, 3))
        runMain()
        assertEquals("ok", session.grid.text())
        assertEquals(1, session.version)
    }

    @Test
    fun statesAndInterruptReportsArrive() {
        session.listener.onState(TermState(TermPhase.Watching, 96u, 44u))
        session.listener.onInterrupt("Sent ctrl+c")
        runMain()
        assertEquals(TermPhase.Watching, session.state?.phase)
        assertEquals("Sent ctrl+c", session.report)
        session.reportShown()
        assertNull(session.report)
    }

    @Test
    fun inputGoesToTheTerminalAndTheHeldModifiersFollow() {
        view.armedAfterTap = Armed(ctrl = true, alt = false)
        session.tap(RowKeyTap.Ctrl)
        assertEquals(Armed(ctrl = true, alt = false), session.armed)
        view.armedAfterTap = Armed(ctrl = false, alt = false)
        session.type("c")
        session.backspace()
        session.paste("a long prompt")
        session.scroll(-3)
        session.live()
        session.interrupt()
        session.phoneSize(GridSize(53, 31))
        assertEquals(Armed(ctrl = false, alt = false), session.armed)
        assertEquals(
            listOf("tap Ctrl", "type c", "backspace", "paste a long prompt", "scroll -3", "live", "interrupt", "size 53x31"),
            view.calls,
        )
    }

    @Test
    fun leavingDetachesAndHandsBackTheLastScreen() {
        val frame = frameOf(6, 2, mapOf(0 to cellsOf("last"), 1 to cellsOf("")))
        session.listener.onFrame(frame)
        runMain()
        assertArrayEquals(frame, session.close())
        assertEquals(listOf("detach", "destroy"), view.calls)
        // Nothing reaches the terminal or the grid afterwards.
        session.type("x")
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("late"))))
        runMain()
        assertEquals("last", session.grid.text())
        assertEquals(listOf("detach", "destroy"), view.calls)
        assertNull(session.close())
    }

    @Test
    fun aScreenThatNeverWentLiveIsNotCachedAgain() {
        session.showCached(frameOf(6, 2, mapOf(0 to cellsOf("cached"))))
        assertNull(session.close())
    }

    @Test
    fun theShapeChangesOnlyWithTheSizeOrLiveness() {
        assertNull(session.shape)
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("a"))))
        runMain()
        val first = session.shape
        assertEquals(GridShape(6, 2, live = true), first)
        session.listener.onFrame(frameOf(6, 2, mapOf(1 to cellsOf("b")), full = false))
        runMain()
        assertTrue(first === session.shape)
        session.listener.onFrame(frameOf(6, 2, mapOf(1 to cellsOf("b")), full = false, live = false))
        runMain()
        assertEquals(GridShape(6, 2, live = false), session.shape)
    }

    @Test
    fun rowDamageCollectsUntilDrawn() {
        session.listener.onFrame(frameOf(6, 3, mapOf(0 to cellsOf("a"))))
        runMain()
        assertTrue(session.damage.take().all)
        session.listener.onFrame(frameOf(6, 3, mapOf(1 to cellsOf("b")), full = false))
        session.listener.onFrame(frameOf(6, 3, mapOf(2 to cellsOf("c")), full = false))
        runMain()
        val damage = session.damage.take()
        assertFalse(damage.all)
        assertEquals(setOf(1, 2), damage.rows)
        assertTrue(session.damage.take().isEmpty)
    }
}

class FakeTerminalView : TerminalViewInterface, AutoCloseable {
    val calls = mutableListOf<String>()
    var armedAfterTap = Armed(ctrl = false, alt = false)

    override fun armed() = armedAfterTap
    override fun backspace(): Armed {
        calls += "backspace"
        return armedAfterTap
    }
    override fun detach() {
        calls += "detach"
    }
    override fun interrupt() {
        calls += "interrupt"
    }
    override fun live() {
        calls += "live"
    }
    override fun paste(text: String) {
        calls += "paste $text"
    }
    override fun phoneSize(cols: UShort, rows: UShort) {
        calls += "size ${cols}x$rows"
    }
    override fun scroll(rows: Int) {
        calls += "scroll $rows"
    }
    override fun tap(key: RowKeyTap): Armed {
        calls += "tap ${key::class.simpleName}"
        return armedAfterTap
    }
    override fun typeText(text: String): Armed {
        calls += "type $text"
        return armedAfterTap
    }
    override fun close() {
        calls += "destroy"
    }
}
