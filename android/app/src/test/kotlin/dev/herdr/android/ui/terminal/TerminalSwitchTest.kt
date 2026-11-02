package dev.herdr.android.ui.terminal

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.agents.connected
import dev.herdr.android.ui.agents.row
import dev.herdr.android.ui.agents.snapshot
import dev.herdr.android.ui.theme.HerdrTheme
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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

// Moving from one agent's terminal to another's without leaving the screen (the
// title-bar swipe, the queue strip): what the person does next acts on the
// terminal now shown, never on the one left. The keyboard of the one left can
// still deliver late input (a keyboard commits a composing word when it
// notices the change); that goes nowhere, so it cannot take the size of a
// terminal that is only being watched (terminal-input spec, typing leads).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TerminalSwitchTest {
    private val a = AgentKey("workstation", "t1")
    private val b = AgentKey("workstation", "t2")
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class Typed : KeyboardSink {
        val sent = mutableListOf<String>()
        override fun type(text: String) {
            sent += text
        }

        override fun backspace() {
            sent += "⌫"
        }
    }

    private fun inputViews(view: View = activity.window.decorView): List<TerminalInputView> = when (view) {
        is TerminalInputView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { inputViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun keyboardUp() = shadowOf(activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible

    @Test
    fun aCompositionLeftInOneTerminalReachesNeitherTerminal() {
        val typed = mapOf(a to Typed(), b to Typed())
        var agent by mutableStateOf(a)
        var input: TerminalInputHandle = TerminalInputHandle.None
        activity.setContent { input = TerminalKeyboard(agent, typed.getValue(agent)) }
        idle()
        input.show()
        val first = inputViews().single()
        val old = first.onCreateInputConnection(EditorInfo())
        old.setComposingText("cat", 1)
        assertEquals(listOf("cat"), typed.getValue(a).sent)
        assertTrue(keyboardUp())

        agent = b
        idle()
        // The keyboard, still bound to the old connection, corrects and commits late.
        old.setComposingText("car", 1)
        old.commitText("car ", 1)
        old.finishComposingText()
        old.deleteSurroundingText(1, 0)
        old.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        first.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        assertEquals(listOf("cat"), typed.getValue(a).sent)
        assertEquals(emptyList<String>(), typed.getValue(b).sent)
        // The new terminal starts as one just opened: watching, keyboard down.
        assertFalse(keyboardUp())
        assertFalse(first.isFocused)

        // The new terminal's keyboard starts with nothing composed.
        val second = inputViews().single()
        assertNotSame(first, second)
        second.onCreateInputConnection(EditorInfo()).setComposingText("r", 1)
        assertEquals(listOf("r"), typed.getValue(b).sent)
    }

    private val hub = snapshot(
        machines = listOf(connected("workstation")),
        agents = listOf(row("t1", AgentAttention.WORKING), row("t2", AgentAttention.WORKING)),
    )

    private class Open(val key: AgentKey, val session: TerminalSession, val screen: TerminalScreenState)

    private fun watching(key: AgentKey): Open {
        val session = TerminalSession(key) { it.run() }
        session.attach(FakeTerminalView())
        val frame = SessionFixture.frame(96, 44, wide = true, bottom = false)
        session.listener.onState(TermState(TermPhase.Watching, 96u, 44u))
        session.listener.onFrame(frame)
        return Open(key, session, TerminalScreenState())
    }

    private fun touch(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        activity.window.decorView.dispatchTouchEvent(event)
        event.recycle()
        idle()
    }

    /** A press in the middle of the screen, on the terminal; held for a long press. */
    private fun press(holdMs: Long) {
        val x = activity.window.decorView.width / 2f
        val y = activity.window.decorView.height / 2f
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        // Compose times a long press with delays on a real-time thread, each resuming
        // on the main looper: hold in real time, letting the looper run as it goes.
        var held = 0L
        do {
            Thread.sleep(STEP_MS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS))
            held += STEP_MS
        } while (held < holdMs)
        touch(MotionEvent.ACTION_UP, down, x, y)
    }

    @Test
    fun tapAndLongPressActOnTheTerminalNowShown() {
        val first = watching(a)
        val second = watching(b)
        var open by mutableStateOf(first)
        val res = activity.resources
        val actions = TerminalActions(onBack = {}, onOpen = {}, onSwipe = { false }, onPhoneGrid = {})
        activity.setContent {
            HerdrTheme(dark = true) {
                val o = open
                TerminalScreen(terminalUi(hub, o.key, res), o.session, o.screen, actions, TerminalInputHandle.None, composeFocus = false)
            }
        }
        idle()
        // The person used the first terminal (its gesture handling starts with the first touch).
        press(holdMs = 0)
        assertTrue(first.screen.inputOpen)
        first.screen.inputOpen = false
        open = second
        idle()

        press(holdMs = 0)
        assertTrue(second.screen.inputOpen)
        assertFalse(first.screen.inputOpen)

        press(holdMs = 800)
        assertTrue(second.screen.selecting)
        assertFalse(first.screen.selecting)
    }

    private companion object {
        const val STEP_MS = 20L
    }
}
