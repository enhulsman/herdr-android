package dev.herdr.android.ui.terminal

import android.content.Context
import android.os.IBinder
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.herdr.android.ui.agents.AgentKey

/**
 * The terminal keyboard's invisible view for [agent]'s terminal, typing into
 * [sink]; the handle shows and hides the phone keyboard. Each agent gets its
 * own view: moving to another agent retires the old one (its keyboard goes
 * down, and whatever its connections still deliver, a late commit of a
 * composing word say, goes nowhere), so input never crosses terminals and the
 * next terminal opens as if from the list. Tested in TerminalSwitchTest.
 */
@Composable
fun TerminalKeyboard(agent: AgentKey, sink: KeyboardSink): TerminalInputHandle = key(agent) {
    val context = LocalContext.current
    val inputView = remember(context) { TerminalInputView(context) }
    inputView.sink = sink
    DisposableEffect(inputView) { onDispose { inputView.retire() } }
    AndroidView({ inputView }, Modifier.size(1.dp))
    remember(inputView) {
        object : TerminalInputHandle {
            override fun show() = inputView.showKeyboard()
            override fun hide() = inputView.hideKeyboard()
        }
    }
}

/**
 * The terminal keyboard (terminal-input spec, "The keyboard behaves like a
 * terminal keyboard"): an invisible view that takes the phone keyboard's input
 * and asks for one without suggestions or autocorrect (as Termux does: a
 * visible-password text field, which keyboards leave alone). What the keyboard
 * sends goes through [KeyboardBridge] to [sink]; a hardware keyboard's keys
 * too. Long text with autocorrect and dictation goes through the compose
 * sheet instead.
 */
class TerminalInputView(context: Context) : View(context) {
    var sink: KeyboardSink? = null
        set(value) {
            if (!retired) field = value
        }

    /** Its terminal is no longer shown: nothing it receives goes anywhere. */
    private var retired = false

    /** This view asked for the keyboard and has not put it down since. */
    private var asked = false

    /** Kept from attaching, so the keyboard can still be hidden while the view leaves its window. */
    private var token: IBinder? = null

    private val bridge = KeyboardBridge(object : KeyboardSink {
        override fun type(text: String) {
            if (!retired) sink?.type(text)
        }

        override fun backspace() {
            if (!retired) sink?.backspace()
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = INPUT_TYPE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return Connection()
    }

    fun showKeyboard() {
        if (retired) return
        asked = true
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        asked = false
        val token = windowToken ?: token ?: return
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(token, 0)
    }

    /**
     * Its terminal was left: drops any composing word, lets go of the sink and
     * focus, and puts its keyboard down. Its input connections stay inert.
     */
    fun retire() {
        if (retired) return
        sink = null
        retired = true
        bridge.finish()
        if (asked || hasFocus()) hideKeyboard()
        if (hasFocus()) clearFocus()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        token = windowToken
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = key(event) || super.onKeyDown(keyCode, event)

    /** A key press from either keyboard; true when it went to the terminal. */
    private fun key(event: KeyEvent): Boolean {
        if (retired) return false
        if (event.action != KeyEvent.ACTION_DOWN) return event.keyCode in HANDLED
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> bridge.key(KeyboardBridge.Key.Backspace)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> bridge.key(KeyboardBridge.Key.Enter)
            else -> {
                val c = event.getUnicodeChar(event.metaState)
                if (c == 0 || c and KeyCharacterMapCombining != 0) return false
                bridge.commit(String(Character.toChars(c)))
            }
        }
        return true
    }

    private inner class Connection : BaseInputConnection(this, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            bridge.commit(text)
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            bridge.compose(text)
            return true
        }

        override fun finishComposingText(): Boolean {
            bridge.finish()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            bridge.delete(beforeLength)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = key(event)
    }

    companion object {
        const val INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        private val HANDLED = setOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        private const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT
    }
}
