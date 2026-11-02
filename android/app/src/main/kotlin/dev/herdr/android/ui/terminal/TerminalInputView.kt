package dev.herdr.android.ui.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

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

    private val bridge = KeyboardBridge(object : KeyboardSink {
        override fun type(text: String) {
            sink?.type(text)
        }

        override fun backspace() {
            sink?.backspace()
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
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = key(event) || super.onKeyDown(keyCode, event)

    /** A key press from either keyboard; true when it went to the terminal. */
    private fun key(event: KeyEvent): Boolean {
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
