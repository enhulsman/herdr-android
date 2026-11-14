package dev.herdr.android.ui.chat

import android.content.ComponentCallbacks2
import dev.herdr.android.ui.agents.AgentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.ChatRow

// What the phone holds of each terminal's chat for the app's run (design
// D12; spec "A chat is held while the terminal view shows", "A draft is kept
// for each terminal while the app runs"): its state per terminal, the 8
// viewed last, all but the shown one on a memory trim, and the place it was
// left at.
class ChatHeldTest {
    private fun key(n: Int) = AgentKey("workstation", "t$n")
    private fun row(key: String) = ChatRow(key, "said", "Text $key", "", emptyList(), emptyList())

    @Test
    fun eachTerminalKeepsItsOwnStateWhileTheAppRuns() {
        val held = ChatStates()
        val one = held.open(key(1))
        one.draft = "Half a message"
        val two = held.open(key(2))
        assertNotSame(one, two)
        assertEquals("another agent's chat never shows it", "", two.draft)
        assertSame(one, held.open(key(1)))
        assertEquals("Half a message", held.open(key(1)).draft)
    }

    @Test
    fun theNinthTerminalDropsTheOneViewedLongestAgo() {
        val held = ChatStates()
        for (n in 0 until 8) held.open(key(n)).draft = "draft $n"
        held.open(key(0))
        held.open(key(8))
        assertEquals("draft 0", held.open(key(0)).draft)
        assertEquals("t1 was viewed longest ago", "", held.open(key(1)).draft)
    }

    @Test
    fun aMemoryTrimKeepsOnlyTheChatOnScreen() {
        val held = ChatStates()
        for (n in 0 until 3) held.open(key(n)).draft = "draft $n"
        held.shown = key(1)
        held.trim()
        assertEquals("draft 1", held.open(key(1)).draft)
        assertEquals("", held.open(key(0)).draft)
        held.shown = null
        held.trim()
        assertEquals("", held.open(key(1)).draft)
    }

    @Test
    fun onlyAMemoryPressureLevelTrims() {
        assertFalse("every move to the background", trimsChats(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertTrue(trimsChats(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(trimsChats(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(trimsChats(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        assertFalse(trimsChats(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test
    fun aChatLeftScrolledUpReturnsToTheSameRow() {
        val s = ChatState()
        val rows = (1..200).map { row("$it:0") }
        s.stick = false
        s.keep(rows, firstIndex = 120, firstOffset = 37)
        // Rows read back were dropped on leaving: the indices moved, the key did not.
        val after = rows.drop(50) + row("900:0")
        assertEquals(70 to 37, s.startAt(after))
        assertFalse(s.stick)
    }

    @Test
    fun aChatLeftAtTheBottomReturnsToTheBottom() {
        val s = ChatState()
        val rows = (1..20).map { row("$it:0") }
        s.keep(rows, firstIndex = 15, firstOffset = 0)
        assertNull(s.startAt(rows + row("900:0")))
        assertTrue(s.stick)
    }

    @Test
    fun aPlaceInsideRowsDroppedOnLeavingReturnsToTheBottom() {
        val s = ChatState()
        val rows = (1..200).map { row("$it:0") }
        s.stick = false
        s.keep(rows, firstIndex = 10, firstOffset = 4)
        assertNull(s.startAt(rows.drop(50)))
        assertTrue("following the bottom again", s.stick)
    }

    @Test
    fun aPlaceOnAPendingBubbleReturnsToTheBottom() {
        val s = ChatState()
        val rows = (1..5).map { row("$it:0") }
        s.stick = false
        s.keep(rows, firstIndex = 5, firstOffset = 0)
        assertNull(s.startAt(rows))
    }

    @Test
    fun aChatNeverShownOpensAtTheBottom() {
        assertNull(ChatState().startAt(listOf(row("1:0"))))
    }
}
