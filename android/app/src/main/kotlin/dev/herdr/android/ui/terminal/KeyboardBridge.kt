package dev.herdr.android.ui.terminal

/** Where the terminal keyboard's input goes: the open terminal (TerminalSession). */
interface KeyboardSink {
    fun type(text: String)
    fun backspace()
}

/**
 * Turns what a phone keyboard sends through its input connection into typed
 * text and backspaces (terminal-input spec, "The keyboard behaves like a
 * terminal keyboard"). The terminal keyboard asks for no suggestions, but some
 * keyboards compose words anyway: a composing word is sent as it changes,
 * edit by edit (backspaces for what changed, then the new end), so the
 * terminal always shows what the keyboard shows. Pure, tested in
 * KeyboardBridgeTest; TerminalInputView feeds it.
 */
class KeyboardBridge(private val sink: KeyboardSink) {
    enum class Key { Backspace, Enter }

    /** What the terminal has of the word being composed, as codepoints. */
    private var composing = IntArray(0)

    fun compose(text: CharSequence) {
        replaceComposing(text.toString())
    }

    fun commit(text: CharSequence) {
        replaceComposing(text.toString())
        composing = IntArray(0)
    }

    fun finish() {
        composing = IntArray(0)
    }

    fun delete(before: Int) {
        if (before <= 0) return
        repeat(before) { sink.backspace() }
        composing = composing.copyOf((composing.size - before).coerceAtLeast(0))
    }

    fun key(key: Key) {
        composing = IntArray(0)
        when (key) {
            Key.Backspace -> sink.backspace()
            Key.Enter -> sink.type("\n")
        }
    }

    private fun replaceComposing(text: String) {
        val next = text.codePoints().toArray()
        var same = 0
        while (same < composing.size && same < next.size && composing[same] == next[same]) same++
        repeat(composing.size - same) { sink.backspace() }
        if (same < next.size) sink.type(String(next, same, next.size - same))
        composing = next
    }
}
