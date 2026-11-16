package dev.herdr.android.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import uniffi.herdr_mobile.ChatRefusal
import uniffi.herdr_mobile.ChatRow

// Sending from the message box (spec "Sending goes only to the agent whose
// log is shown", "An Enter the agent dropped is pressed once more", "A sent
// message shows at once and its state is followed", "Slash and shell
// commands are sent as typed", "A draft is kept for each terminal while the
// app runs").
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatSendTest {
    private val rows = listOf(ChatRow("10:0", "said", "Done.", "", emptyList(), emptyList()))
    private fun photo(name: String) = Attachment(name, byteArrayOf(1, 2), ImageBitmap(1, 1))

    /** Claude Code's input box between its two rules, holding [text]. */
    private fun claudeBox(text: String) = listOf("Done.", "─".repeat(40), "❯ $text", "─".repeat(40), "  ? for shortcuts")

    @Test
    fun aSendShowsItsBubbleAtOnceAndEmptiesTheBox() {
        val s = ChatState()
        s.draft = "Run the tests"
        val out = s.send(rows, "idle", now = 1_000)!!
        assertEquals("Run the tests", out.typed)
        assertEquals("", s.draft)
        assertEquals(listOf(out.pending), s.pending)
        assertEquals(PendingState.Sending, s.pending.single().state(1_000))
        assertEquals("10:0", out.pending.anchor)
    }

    @Test
    fun aLastWordStartingWithAtIsTypedWithASpace() {
        val s = ChatState()
        s.draft = "Read @/work/website/notes/plan.md"
        val out = s.send(rows, "idle", now = 0)!!
        assertEquals("Read @/work/website/notes/plan.md ", out.typed)
        assertEquals("the bubble matches the log's text", "Read @/work/website/notes/plan.md", out.pending.text)
    }

    @Test
    fun photosGoWithTheMessageAndLeaveTheBox() {
        val s = ChatState()
        s.draft = "What is on this screen?"
        s.attached = listOf(photo("photo-1-0.jpg"))
        val out = s.send(rows, "idle", now = 0)!!
        assertEquals(listOf("photo-1-0.jpg"), out.attached.map { it.name })
        assertEquals(1, out.pending.images)
        assertTrue(s.attached.isEmpty())
    }

    @Test
    fun anEmptyBoxSendsNothing() {
        val s = ChatState()
        s.draft = "  "
        assertNull(s.send(rows, "idle", now = 0))
        assertTrue(s.pending.isEmpty())
    }

    @Test
    fun aRefusalKeepsTheDraftAndSaysWhyWithTheTerminalButton() {
        val s = ChatState()
        s.draft = "Run the tests"
        s.attached = listOf(photo("a.jpg"))
        val out = s.send(rows, "blocked", now = 0)!!
        s.refused(out, ChatRefusal.Waiting)
        assertEquals("Run the tests", s.draft)
        assertEquals(listOf("a.jpg"), s.attached.map { it.name })
        assertTrue("nothing was typed: no bubble", s.pending.isEmpty())
        assertEquals(Refusal("The agent is waiting for you in the terminal", terminal = true), s.refusal)
    }

    @Test
    fun eachRefusalHasItsText() {
        val s = ChatState()
        for ((r, want) in listOf(
            ChatRefusal.Exited to Refusal("The agent has exited", terminal = false),
            ChatRefusal.Changed to Refusal("This is a different session now", terminal = true),
            ChatRefusal.Failed("Not connected") to Refusal("Not connected", terminal = false),
        )) {
            s.draft = "Hi"
            s.refused(s.send(rows, "idle", now = 0)!!, r)
            assertEquals(want, s.refusal)
        }
    }

    @Test
    fun aRefusalKeepsWhatWasTypedMeanwhileToo() {
        val s = ChatState()
        s.draft = "First"
        val out = s.send(rows, "idle", now = 0)!!
        s.draft = "Second"
        s.refused(out, ChatRefusal.Changed)
        assertEquals("First\nSecond", s.draft)
    }

    @Test
    fun aSendThatWentOutClearsTheRefusal() {
        val s = ChatState()
        s.draft = "Hi"
        s.refused(s.send(rows, "blocked", now = 0)!!, ChatRefusal.Waiting)
        s.send(rows, "idle", now = 0)
        assertNull(s.refusal)
    }

    @Test
    fun whileTheWaitingLineShowsASendIsRefusedAndTheDraftStays() {
        val s = ChatState()
        s.draft = "/usage"
        val usage = s.send(rows, "idle", now = 0)!!
        s.typed(usage.pending.id, now = 0)
        s.draft = "Run the tests"
        assertNull(s.send(rows, "idle", now = WAIT_MS))
        assertEquals(Refusal(DIALOG_FIRST, terminal = true), s.refusal)
        assertEquals("Run the tests", s.draft)
        assertEquals(1, s.pending.size)
        // After Dismiss a send goes through.
        s.update(usage.pending.id) { it.dismissWait() }
        assertNotNull(s.send(rows, "idle", now = WAIT_MS))
    }

    @Test
    fun aDroppedEnterIsPressedOnceMore() {
        val s = ChatState()
        s.draft = "Read @/work/website/notes/plan.md"
        val out = s.send(rows, "idle", now = 0)!!
        s.typed(out.pending.id, now = 0)
        val box = claudeBox("Read @/work/website/notes/plan.md ")
        assertNull("not before 1.5 s", s.enterAgain("claude", box, now = ENTER_AGAIN_MS - 1, canSend = true))
        assertEquals(out.pending.id, s.enterAgain("claude", box, now = ENTER_AGAIN_MS, canSend = true))
        assertNull("once per message, never twice", s.enterAgain("claude", box, now = ENTER_AGAIN_MS + 2_000, canSend = true))
    }

    @Test
    fun noEnterWhileTheSendRunsOrTheBoxHoldsOtherText() {
        val s = ChatState()
        s.draft = "Run the tests"
        val out = s.send(rows, "idle", now = 0)!!
        assertNull("not typed yet", s.enterAgain("claude", claudeBox("Run the tests"), now = 5_000, canSend = true))
        s.typed(out.pending.id, now = 0)
        assertNull(s.enterAgain("claude", claudeBox("Something the person typed"), now = 5_000, canSend = true))
        assertNull(s.enterAgain("claude", claudeBox(""), now = 5_000, canSend = true))
    }

    @Test
    fun noEnterWhileTheAgentCannotTakeAMessage() {
        // A dialog opened, the agent exited or the session changed: the
        // chat cannot send, so neither does the second Enter.
        val s = ChatState()
        s.draft = "Read @/work/website/notes/plan.md"
        val out = s.send(rows, "idle", now = 0)!!
        s.typed(out.pending.id, now = 0)
        val box = claudeBox("Read @/work/website/notes/plan.md ")
        assertNull(s.enterAgain("claude", box, now = ENTER_AGAIN_MS, canSend = false))
        // Once it can again, the Enter still goes, once.
        assertEquals(out.pending.id, s.enterAgain("claude", box, now = ENTER_AGAIN_MS + 500, canSend = true))
        assertNull(s.enterAgain("claude", box, now = ENTER_AGAIN_MS + 1_000, canSend = true))
    }
}
