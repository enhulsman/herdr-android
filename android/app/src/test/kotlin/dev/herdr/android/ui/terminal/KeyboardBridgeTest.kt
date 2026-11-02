package dev.herdr.android.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

// terminal-input spec, "The keyboard behaves like a terminal keyboard": what the
// phone keyboard sends through its input connection becomes typed text and
// backspaces for the terminal. Keyboards that compose words anyway (some do,
// even when asked for no suggestions) are followed edit by edit.
class KeyboardBridgeTest {
    private val sent = mutableListOf<String>()
    private val bridge = KeyboardBridge(object : KeyboardSink {
        override fun type(text: String) {
            sent += text
        }

        override fun backspace() {
            sent += "⌫"
        }
    })

    @Test
    fun lsxBackspaceEnterRunsLs() {
        bridge.commit("l")
        bridge.commit("s")
        bridge.commit("x")
        bridge.key(KeyboardBridge.Key.Backspace)
        bridge.key(KeyboardBridge.Key.Enter)
        assertEquals(listOf("l", "s", "x", "⌫", "\n"), sent)
    }

    @Test
    fun aComposingWordIsSentAsItGrows() {
        bridge.compose("h")
        bridge.compose("he")
        bridge.compose("hel")
        bridge.commit("help")
        assertEquals(listOf("h", "e", "l", "p"), sent)
    }

    @Test
    fun aCorrectedComposingWordIsRetyped() {
        bridge.compose("teh")
        bridge.commit("the ")
        assertEquals(listOf("teh", "⌫", "⌫", "he "), sent)
    }

    @Test
    fun finishingKeepsWhatWasSent() {
        bridge.compose("ok")
        bridge.finish()
        bridge.commit("!")
        assertEquals(listOf("ok", "!"), sent)
    }

    @Test
    fun deletingBeforeTheCursorIsBackspaces() {
        bridge.commit("ab")
        bridge.delete(2)
        bridge.delete(0)
        assertEquals(listOf("ab", "⌫", "⌫"), sent)
    }

    @Test
    fun deletingInsideAComposingWordShortensIt() {
        bridge.compose("cat")
        bridge.delete(1)
        bridge.compose("car")
        assertEquals(listOf("cat", "⌫", "r"), sent)
    }

    @Test
    fun aNewlineInCommittedTextIsEnter() {
        bridge.commit("\n")
        assertEquals(listOf("\n"), sent)
    }

    @Test
    fun emojiAreOneEdit() {
        bridge.compose("👍")
        bridge.compose("👍🏽")
        assertEquals(listOf("👍", "🏽"), sent)
    }
}
