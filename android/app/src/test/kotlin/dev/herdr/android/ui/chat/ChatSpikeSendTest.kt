package dev.herdr.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatRow

// Spike for issue 17 (never merged). Sending from the chat: `!` and `/`
// commands come back as the person's own row, `/clear` in a new, shorter log,
// and a `/` command that shows nothing (it opened a dialog) points to the
// terminal. The chat's touch cover (`coversBelow`) was checked on the phone: a
// Robolectric tap test of it left later Compose tests failing, by test order.
@RunWith(RobolectricTestRunner::class)
class ChatSpikeSendTest {
    private fun user(text: String) = ChatRow("user", text, "", emptyList(), emptyList())
    private fun said(text: String) = ChatRow("said", text, "", emptyList(), emptyList())
    private fun shell(input: String) = ChatRow("tools", "", "", emptyList(), listOf(ChatCall("Shell", "! $input", input, "", true, false, emptyList())))

    @Test
    fun aBangMessageIsConfirmedByItsOwnRow() {
        val state = ChatSpikeState()
        state.sent("!echo hi", 0, rows = 1, now = 0)
        state.confirm(listOf(said("Done."), user("!echo hi"), shell("echo hi")))
        assertEquals(emptyList<Pending>(), state.pending)
    }

    @Test
    fun aPlainMessageIsNotConfirmedByAShellCall() {
        val state = ChatSpikeState()
        state.sent("echo hi", 0, rows = 0, now = 0)
        state.confirm(listOf(shell("echo hi")))
        assertEquals(1, state.pending.size)
    }

    @Test
    fun clearIsConfirmedInTheNewShorterLog() {
        val state = ChatSpikeState()
        state.sent("/clear", 0, rows = 20, now = 0)
        state.confirm(listOf(user("/clear")))
        assertEquals(emptyList<Pending>(), state.pending)
    }

    @Test
    fun anOlderRowWithTheSameTextDoesNotConfirmIt() {
        val state = ChatSpikeState()
        state.sent("/usage", 0, rows = 2, now = 0)
        state.confirm(listOf(user("/usage"), said("Done.")))
        assertEquals(1, state.pending.size)
    }

    @Test
    fun aSlashCommandWithNoRowAfterThreeSecondsPointsToTheTerminal() {
        val p = Pending("/usage", 0, at = 0, sentAt = 1_000)
        assertFalse(p.waiting(now = 3_999))
        assertTrue(p.waiting(now = 4_000))
        assertFalse("a plain message just waits", Pending("Say hi", 0, at = 0, sentAt = 1_000).waiting(now = 60_000))
        assertEquals("Waiting for the terminal: /usage may have opened a dialog there.", Pending("/usage now", 0, 0, 0).waitingText)
    }
}
