package dev.kudde.android.ui.terminal

import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.theme.Contrast
import dev.kudde.android.ui.theme.ThemeName
import dev.kudde.android.ui.theme.kuddeColors
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.kudde_mobile.Armed
import uniffi.kudde_mobile.RowKeyTap
import uniffi.kudde_mobile.TermPhase
import uniffi.kudde_mobile.TermState
import uniffi.kudde_mobile.TerminalViewInterface

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
    fun opensWithTheShowingTheme() {
        for (name in ThemeName.entries) {
            val theme = kuddeColors(name).terminalTheme()
            val terminal = TerminalSession(AgentKey("workstation", "t2"), main, theme)
            val opened = FakeTerminalView()
            assertTrue(terminal.open { supplied, listener ->
                assertEquals(theme, supplied)
                assertTrue(terminal.listener === listener)
                opened
            } === opened)
            assertTrue(opened.calls.isEmpty())
            assertEquals(if (name == ThemeName.Paper) 0x25272cu else 0xd2d4d9u, theme.fg)
            assertEquals(if (name == ThemeName.Paper) 0xffffffu else 0x17191du, theme.bg)
            assertEquals(16, theme.ansi.size)
            assertEquals(if (name == ThemeName.Paper) 0xc62a2au else 0xdc8c95u, theme.ansi[1])
            val other = kuddeColors(if (name == ThemeName.Paper) ThemeName.Graphite else ThemeName.Paper).terminalTheme()
            terminal.setTheme(other)
            assertEquals(listOf(other), opened.themes)
            assertEquals(listOf("theme"), opened.calls)
        }
    }

    @Test
    fun changingThemeOnlySetsTheEngineAndKeepsTheScrollPosition() {
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("live")), live = false, total = 100, offset = 58))
        runMain()
        val grid = session.grid
        val paper = kuddeColors(ThemeName.Paper).terminalTheme()
        session.setTheme(paper)
        session.setTheme(paper.copy()) // Recomposition with the same colours is silent.
        assertEquals(listOf("theme"), view.calls)
        assertEquals(listOf(paper), view.themes)
        assertTrue(grid === session.grid)
        assertEquals(58L, session.grid.offset)
        // Live defaults can belong to a program; only kept frames are filtered.
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("app")), bg = 0x002b36, live = false, total = 100, offset = 58))
        runMain()
        assertEquals(0x002b36, session.grid.defaultBg)
        assertEquals("app", session.grid.text())
        assertEquals(58L, session.grid.offset)
        assertEquals(listOf("theme"), view.calls)
    }

    @Test
    fun themeChangedBeforeOpenIsTheOnePassedAtOpen() {
        val terminal = TerminalSession(AgentKey("workstation", "t2"), main)
        val paper = kuddeColors(ThemeName.Paper).terminalTheme()
        terminal.setTheme(paper)
        terminal.open { supplied, _ ->
            assertEquals(paper, supplied)
            FakeTerminalView()
        }
    }

    @Test
    fun aClosedSessionDoesNotSetAThemeOrOpenAgain() {
        session.close()
        view.calls.clear()
        session.setTheme(kuddeColors(ThemeName.Paper).terminalTheme())
        assertNull(session.open { _, _ -> error("closed session opened") })
        assertTrue(view.calls.isEmpty())
    }

    @Test
    fun higherTextContrastDoesNotChangeTerminalColours() {
        for (name in ThemeName.entries) {
            assertEquals(kuddeColors(name).terminalTheme(), kuddeColors(name, Contrast.Aa).terminalTheme())
        }
    }

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
    fun theTerminalTakesThePhonesSizeOnlyWhileItShows() {
        // The chat over the terminal leaves the desk its size (Ezra, 2026-11-16).
        assertTrue(atPhoneSizeOnOpen(chat = false))
        assertFalse(atPhoneSizeOnOpen(chat = true))
        val view = FakeTerminalView()
        session.attach(view)
        session.follow(chat = true)
        session.follow(chat = false)
        assertEquals(listOf("watch", "lead"), view.calls)
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

    // Issue #22: compose text with a line break is refused where the program
    // did not ask for bracketed paste; the sheet keeps the text to edit.
    @Test
    fun aRefusedPasteKeepsTheComposeSheetAndItsText() {
        val screen = TerminalScreenState().apply { composeOpen = true; composeText = "make clean\nrm -rf build" }
        view.pasteTaken = false
        assertFalse(screen.sendCompose(session::paste))
        assertTrue(screen.composeOpen)
        assertEquals("make clean\nrm -rf build", screen.composeText)
        view.pasteTaken = true
        screen.composeText = "make clean"
        assertTrue(screen.sendCompose(session::paste))
        assertFalse(screen.composeOpen)
        assertEquals("", screen.composeText)
        assertEquals(listOf("paste make clean\nrm -rf build", "paste make clean"), view.calls)
    }

    @Test
    fun anEmptyComposeClosesWithoutAPaste() {
        val screen = TerminalScreenState().apply { composeOpen = true }
        assertTrue(screen.sendCompose(session::paste))
        assertFalse(screen.composeOpen)
        assertTrue(view.calls.isEmpty())
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
    val themes = mutableListOf<uniffi.kudde_mobile.TerminalTheme>()
    var armedAfterTap = Armed(ctrl = false, alt = false)

    override fun armed() = armedAfterTap
    override fun setTheme(theme: uniffi.kudde_mobile.TerminalTheme) {
        calls += "theme"
        themes += theme
    }
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
    /** What the core answers a paste: false refuses it (a line break without bracketed paste). */
    var pasteTaken = true
    override fun paste(text: String): Boolean {
        calls += "paste $text"
        return pasteTaken
    }
    override fun phoneSize(cols: UShort, rows: UShort) {
        calls += "size ${cols}x$rows"
    }
    override fun scroll(rows: Int) {
        calls += "scroll $rows"
    }
    override fun lead() {
        calls += "lead"
    }
    override fun watch() {
        calls += "watch"
    }
    override fun click(col: UShort, row: UShort) {
        calls += "click $col $row"
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
