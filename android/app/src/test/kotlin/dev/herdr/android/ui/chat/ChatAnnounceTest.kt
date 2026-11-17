package dev.herdr.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.herdr_mobile.ChatRow

// accessibility spec, "The chat announces new answers": live "said" rows,
// each once, in order; never history, the person's rows, tools or notes.
class ChatAnnounceTest {
    private fun row(key: String, kind: String = "said") = ChatRow(key, kind, "Text $key", "", emptyList(), emptyList())

    @Test
    fun theFirstReadIsNotAnnounced() {
        val live = LiveRows()
        val heard = Announced()
        val rows = listOf(row("a"), row("b"))
        live.saw(rows, now = 1, live = false)
        assertEquals(emptyList<String>(), heard.next(rows, live))
    }

    @Test
    fun aLiveAnswerIsAnnouncedOnce() {
        val live = LiveRows()
        val heard = Announced()
        val first = listOf(row("a"))
        live.saw(first, now = 1, live = false)
        heard.next(first, live)
        val more = first + row("b")
        live.saw(more, now = 2, live = true)
        assertEquals(listOf("Text b"), heard.next(more, live))
        assertEquals(emptyList<String>(), heard.next(more, live))
    }

    @Test
    fun onlyAnswersAndInOrder() {
        val live = LiveRows()
        val heard = Announced()
        val rows = listOf(row("u", "user"), row("t", "tools"), row("n", "note"), row("x"), row("ag", "agent"), row("y"))
        live.saw(rows, now = 1, live = true)
        assertEquals(listOf("Text x", "Text y"), heard.next(rows, live))
    }

    @Test
    fun aReadBackIsNotAnnounced() {
        val live = LiveRows()
        val heard = Announced()
        val now = listOf(row("c"))
        live.saw(now, now = 1, live = false)
        val older = listOf(row("a"), row("b")) + now
        live.saw(older, now = 2, live = false)
        assertEquals(emptyList<String>(), heard.next(older, live))
    }
}
