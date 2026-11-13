package dev.herdr.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatRow

// Spike for issue 17 (never merged). Sending from the chat: a `!` message is
// confirmed by its shell call (Claude Code logs no person's message for it).
// The chat's touch cover (`coversBelow`) was checked on the phone: a
// Robolectric tap test of it left later Compose tests failing, by test order.
@RunWith(RobolectricTestRunner::class)
class ChatSpikeSendTest {
    private fun call(input: String) = ChatCall("Shell", "! $input", input, "", true, false, emptyList())
    private fun shell(vararg inputs: String) = ChatRow("tools", "", "", emptyList(), inputs.map(::call))
    private fun said(text: String) = ChatRow("said", text, "", emptyList(), emptyList())

    @Test
    fun aBangMessageIsConfirmedByItsShellCall() {
        val state = ChatSpikeState()
        state.sent("!echo hi", 0, rows = 0)
        state.confirm(listOf(shell("echo hi")))
        assertEquals(emptyList<Pending>(), state.pending)
    }

    @Test
    fun anotherShellCallDoesNotConfirmIt() {
        val state = ChatSpikeState()
        state.sent("!echo hi", 0, rows = 0)
        state.confirm(listOf(shell("echo bye")))
        assertEquals(1, state.pending.size)
    }

    @Test
    fun aPlainMessageIsNotConfirmedByAShellCall() {
        val state = ChatSpikeState()
        state.sent("echo hi", 0, rows = 0)
        state.confirm(listOf(shell("echo hi")))
        assertEquals(1, state.pending.size)
    }

    @Test
    fun aBangMessageFoldedIntoTheLastToolRowIsConfirmed() {
        // Codex appends the command to the folded tool row that was last when it was sent.
        val state = ChatSpikeState()
        state.sent("!echo hi", 0, rows = 2)
        state.confirm(listOf(said("Done."), shell("sleep 8", "echo hi")))
        assertEquals(emptyList<Pending>(), state.pending)
    }

    @Test
    fun anOlderToolRowDoesNotConfirmIt() {
        val state = ChatSpikeState()
        state.sent("!echo hi", 0, rows = 3)
        state.confirm(listOf(shell("echo hi"), said("Done."), said("More.")))
        assertEquals(1, state.pending.size)
    }
}
