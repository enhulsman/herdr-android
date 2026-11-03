package dev.herdr.android.ui.terminal

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.agents.connected
import dev.herdr.android.ui.agents.row
import dev.herdr.android.ui.agents.snapshot
import dev.herdr.android.ui.theme.HerdrLayout
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.TermPhase
import uniffi.herdr_mobile.TermState

// Touch on the terminal itself, through the real view hierarchy (terminal-view
// spec: "Scrollback with a way back to live", "Fit to width, zoom and pan"). The
// first phone round found the terminal ignoring drags in every mode while the
// screenshot tests and the layout maths passed, so these drive real
// MotionEvents into the activity.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TerminalTouchTest {
    private val key = AgentKey("workstation", "t1")
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val hub = snapshot(machines = listOf(connected("workstation")), agents = listOf(row("t1", AgentAttention.WORKING)))

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The phone keyboard as the screen asks for it. */
    private class Keyboard : TerminalInputHandle {
        val asked = mutableListOf<String>()
        override fun show() {
            asked += "show"
        }

        override fun hide() {
            asked += "hide"
        }
    }

    private val keyboard = Keyboard()

    private fun open(phase: TermPhase, cols: Int, rows: Int, typing: Boolean): Triple<FakeTerminalView, TerminalScreenState, TerminalSession> {
        val view = FakeTerminalView()
        val session = TerminalSession(key) { it.run() }
        session.attach(view)
        session.listener.onState(TermState(phase, cols.toUShort(), rows.toUShort()))
        session.listener.onFrame(SessionFixture.frame(cols, rows, wide = cols > 60, bottom = false))
        val screen = TerminalScreenState().apply {
            this.typing = typing
        }
        val actions = TerminalActions(onBack = {}, onOpen = {}, onSwipe = { false }, onPhoneGrid = {})
        activity.setContent {
            HerdrTheme(dark = true) {
                TerminalScreen(terminalUi(hub, key, activity.resources), session, screen, actions, keyboard, composeFocus = false)
            }
        }
        idle()
        return Triple(view, screen, session)
    }

    private fun send(event: MotionEvent) {
        activity.window.decorView.dispatchTouchEvent(event)
        event.recycle()
        idle()
    }

    /** One finger from ([x], [y0]) to ([x], [y1]) in [steps] moves. */
    private fun drag(x: Float, y0: Float, y1: Float, steps: Int = 12) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y0, 0))
        for (i in 1..steps) {
            val y = y0 + (y1 - y0) * i / steps
            send(MotionEvent.obtain(down, down + i * 10L, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        send(MotionEvent.obtain(down, down + (steps + 1) * 10L, MotionEvent.ACTION_UP, x, y1, 0))
    }

    /** Two fingers on a horizontal line around ([cx], [cy]), spreading from [from] to [to] px apart. */
    private fun pinch(cx: Float, cy: Float, from: Float, to: Float, steps: Int = 12) {
        val down = SystemClock.uptimeMillis()
        val props = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun coords(gap: Float) = arrayOf(
            MotionEvent.PointerCoords().apply { x = cx - gap / 2; y = cy; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = cx + gap / 2; y = cy; pressure = 1f; size = 1f },
        )
        fun event(t: Long, action: Int, count: Int, gap: Float) =
            MotionEvent.obtain(down, t, action, count, props, coords(gap), 0, 0, 1f, 1f, 0, 0, 0, 0)
        send(event(down, MotionEvent.ACTION_DOWN, 1, from))
        send(event(down + 5, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, from))
        for (i in 1..steps) {
            send(event(down + 5 + i * 10L, MotionEvent.ACTION_MOVE, 2, from + (to - from) * i / steps))
        }
        val end = down + 5 + (steps + 1) * 10L
        send(event(end, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, to))
        send(event(end + 5, MotionEvent.ACTION_UP, 1, to))
    }

    private val midX get() = activity.window.decorView.width / 2f
    private val midY get() = activity.window.decorView.height / 2f

    private fun scrolled(view: FakeTerminalView) = view.calls.filter { it.startsWith("scroll ") }.sumOf { it.removePrefix("scroll ").toInt() }

    @Test
    fun aDragDownWhileWatchingScrollsBackInHistory() {
        val (view, _, _) = open(TermPhase.Watching, 96, 44, typing = false)
        drag(midX, midY - 300f, midY + 300f)
        assertTrue("scroll calls: ${view.calls}", scrolled(view) < 0)
    }

    @Test
    fun aDragDownWhileTypingScrollsBackInHistory() {
        val (view, _, _) = open(TermPhase.Typing, 53, 40, typing = true)
        drag(midX, midY - 300f, midY + 300f)
        assertTrue("scroll calls: ${view.calls}", scrolled(view) < 0)
    }

    @Test
    fun aDragUpAfterScrollingBackGoesForward() {
        val (view, _, _) = open(TermPhase.Typing, 53, 40, typing = true)
        drag(midX, midY - 300f, midY + 300f)
        val back = scrolled(view)
        drag(midX, midY + 300f, midY - 300f)
        assertTrue("scroll calls: ${view.calls}", scrolled(view) > back)
    }

    /** A quick tap at ([x], [y]). */
    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        send(MotionEvent.obtain(down, down + 30, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** The terminal's top on screen: under the system bar and the title bar (one agent: no queue strip). */
    private fun terminalTop(): Float {
        val d = activity.resources.displayMetrics.density
        val bar = activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.top ?: 0
        return bar + HerdrLayout.terminalTitleHeight.value * d
    }

    @Test
    fun aTapWhileTypingOnlyClicksTheCellUnderTheFinger() {
        val (view, screen, _) = open(TermPhase.Typing, 53, 40, typing = true)
        val d = activity.resources.displayMetrics.density
        val font = HerdrType.terminalPhone.fontSize.value * d
        val padX = HerdrLayout.terminalPaddingX.value * d
        val padTop = HerdrLayout.terminalPaddingTop.value * d
        // the middle of cell (7, 3)
        val x = padX + font * TerminalMetrics.ADVANCE_EM * 7.5f
        val y = terminalTop() + padTop + font * TerminalMetrics.LINE_EM * 3.5f
        tap(x, y)
        assertEquals("calls: ${view.calls}", listOf("click 7 3"), view.calls)
        assertEquals("the keyboard stays down: reading and clicking", emptyList<String>(), keyboard.asked)
        assertFalse(screen.inputOpen)
    }

    @Test
    fun aTapWhileWatchingTakesOverAtPhoneSizeWithoutTheKeyboard() {
        val (view, screen, _) = open(TermPhase.Watching, 96, 44, typing = false)
        tap(midX, midY)
        assertEquals("calls: ${view.calls}", listOf("lead"), view.calls)
        assertEquals(emptyList<String>(), keyboard.asked)
        assertFalse(screen.inputOpen)
    }

    /** The middle of a key in the two keys rows: [col] of 9, [line] 0 (top) or 1, above the system bar. */
    private fun keyAt(col: Int, line: Int): Pair<Float, Float> {
        val d = activity.resources.displayMetrics.density
        val bottomBar = activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
        val bottom = activity.window.decorView.height - bottomBar
        val rowH = HerdrLayout.keysRowHeight.value * d
        val y = bottom - rowH * (2 - line) + rowH / 2
        val inner = activity.window.decorView.width - 12 * d
        val keyW = (inner - 8 * 4 * d) / 9
        val x = 6 * d + col * (keyW + 4 * d) + keyW / 2
        return x to y
    }

    @Test
    fun theKeyboardKeyInTheMiddleOfTheTopRowBringsTheKeyboardUp() {
        val (view, screen, _) = open(TermPhase.Typing, 53, 40, typing = true)
        val (x, y) = keyAt(4, 0)
        tap(x, y)
        assertEquals(listOf("show"), keyboard.asked)
        assertTrue(screen.inputOpen)
        assertTrue("no key typed: ${view.calls}", view.calls.isEmpty())
    }

    @Test
    fun bothKeysRowsTypeTheirKeys() {
        val (view, _, _) = open(TermPhase.Typing, 53, 40, typing = true)
        val keys = mapOf(
            (0 to 0) to "tap Esc", (3 to 0) to "tap Tab", (7 to 0) to "tap Up",
            (0 to 1) to "tap CtrlC", (1 to 1) to "tap ShiftTab", (3 to 1) to "tap Enter",
            (6 to 1) to "tap Left", (7 to 1) to "tap Down", (8 to 1) to "tap Right",
        )
        for ((at, call) in keys) {
            view.calls.clear()
            val (x, y) = keyAt(at.first, at.second)
            tap(x, y)
            assertEquals("key at $at", listOf(call), view.calls)
        }
    }

    @Test
    fun aPinchOutWhileWatchingZoomsIn() {
        val (_, screen, _) = open(TermPhase.Watching, 160, 44, typing = false)
        pinch(midX, midY, 200f, 600f)
        assertTrue("zoom: ${screen.watch.zoom}", screen.watch.zoom > 1f)
    }
}
