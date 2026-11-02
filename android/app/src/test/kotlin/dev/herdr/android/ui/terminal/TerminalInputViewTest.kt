package dev.herdr.android.ui.terminal

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// terminal-input spec, "The keyboard behaves like a terminal keyboard": the
// terminal asks for a keyboard without suggestions or autocorrect, and maps
// what it sends, backspace and enter included, to terminal keys.
@RunWith(RobolectricTestRunner::class)
class TerminalInputViewTest {
    private val sent = mutableListOf<String>()
    private val view = TerminalInputView(RuntimeEnvironment.getApplication()).apply {
        sink = object : KeyboardSink {
            override fun type(text: String) {
                sent += text
            }

            override fun backspace() {
                sent += "⌫"
            }
        }
    }

    @Test
    fun itAsksForAKeyboardWithoutSuggestions() {
        val info = EditorInfo()
        view.onCreateInputConnection(info)
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
        assertTrue(view.onCheckIsTextEditor())
    }

    @Test
    fun lsxBackspaceEnter() {
        val ic = view.onCreateInputConnection(EditorInfo())
        ic.commitText("l", 1)
        ic.commitText("s", 1)
        ic.commitText("x", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf("l", "s", "x", "⌫", "\n"), sent)
    }

    @Test
    fun composingAndDeletingGoThroughTheBridge() {
        val ic = view.onCreateInputConnection(EditorInfo())
        ic.setComposingText("gi", 1)
        ic.setComposingText("git", 1)
        ic.finishComposingText()
        ic.deleteSurroundingText(1, 0)
        assertEquals(listOf("gi", "t", "⌫"), sent)
    }

    @Test
    fun aHardwareKeyTypesItsCharacter() {
        view.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        view.onKeyDown(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("a", "⌫"), sent)
    }
}
