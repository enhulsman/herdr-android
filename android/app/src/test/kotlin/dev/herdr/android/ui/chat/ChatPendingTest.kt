package dev.herdr.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatImage
import uniffi.herdr_mobile.ChatRow

// Spec "A sent message shows at once and its state is followed", "An Enter the
// agent dropped is pressed once more", "Slash and shell commands are sent as
// typed" and "A working line shows for the whole turn"; design D10.
class ChatPendingTest {
    private fun user(key: String, text: String, images: Int = 0) =
        ChatRow(key, "user", text, "", List(images) { ChatImage("$key/$it", "image/png") }, emptyList())
    private fun said(key: String, text: String) = ChatRow(key, "said", text, "", emptyList(), emptyList())
    private fun shell(key: String, vararg inputs: String) =
        ChatRow(key, "tools", "", "", emptyList(), inputs.map { ChatCall("Shell", "! $it", it, "", true, false, emptyList()) })

    private fun send(text: String, rows: List<ChatRow>, status: String = "idle", images: Int = 0, at: Long = 0, id: Long = 1) =
        pendingFor(id, text, images, rows, status, now = at).typed(at)

    private fun follow(pending: List<Pending>, rows: List<ChatRow>, status: String = "idle", now: Long = 1_000, queued: (Pending) -> Boolean = { false }) =
        followPending(pending, rows, status, now, queued)

    // --- arrival ---

    @Test
    fun aNormalSendTurnsIntoItsRow() {
        val before = listOf(said("0:0", "Hello"))
        val p = send("Run the tests", before)
        assertEquals(PendingState.Sending, p.state(now = 100))
        assertEquals(listOf(p), follow(listOf(p), before))
        assertEquals(emptyList<Pending>(), follow(listOf(p), before + user("120:0", "Run the tests")))
    }

    @Test
    fun anOlderRowWithTheSameTextDoesNotConfirmIt() {
        val before = listOf(user("0:0", "/usage"), said("40:0", "Done."))
        val p = send("/usage", before)
        assertEquals(1, follow(listOf(p), before).size)
        assertEquals(0, follow(listOf(p), before + user("90:0", "/usage")).size)
    }

    @Test
    fun readingBackOlderRowsConfirmsNothing() {
        val before = listOf(said("500:0", "Done."))
        val p = send("Run the tests", before)
        val readBack = listOf(user("10:0", "Run the tests"), said("90:0", "ok")) + before
        assertEquals(1, follow(listOf(p), readBack).size)
    }

    @Test
    fun spacesAroundTheTextAndTheTypedTrailingSpaceAreIgnored() {
        val p = send("  Read @/work/website/notes/plan.md\n", emptyList())
        assertEquals(0, follow(listOf(p), listOf(user("0:0", "Read @/work/website/notes/plan.md"))).size)
    }

    @Test
    fun aPhotoSentAloneIsConfirmedByARowWithImagesAndNoText() {
        val p = send("", emptyList(), images = 1)
        assertEquals(1, follow(listOf(p), listOf(user("0:0", "Something else"))).size)
        assertEquals(0, follow(listOf(p), listOf(user("0:0", "", images = 1))).size)
    }

    @Test
    fun twoMessagesWithTheSameTextEachTakeTheirOwnRow() {
        val before = listOf(said("0:0", "Hi"))
        val a = send("again", before, id = 1)
        val b = send("again", before, id = 2)
        val one = follow(listOf(a, b), before + user("50:0", "again"))
        assertEquals("the older one arrived first", listOf(b), one)
        assertEquals(0, follow(one, before + user("50:0", "again") + user("90:0", "again")).size)
    }

    @Test
    fun aMessageThatNeverArrivesDoesNotHoldBackTheNextOne() {
        val before = listOf(said("0:0", "Hi"))
        val lost = send("/usage", before, id = 1)
        val next = send("Run the tests", before, id = 2)
        assertEquals(listOf(lost), follow(listOf(lost, next), before + user("70:0", "Run the tests")))
    }

    @Test
    fun aDeskMessageInBetweenConfirmsNothing() {
        val before = listOf(said("0:0", "Hi"))
        val p = send("Run the tests", before)
        assertEquals(1, follow(listOf(p), before + user("40:0", "typed at the desk")).size)
    }

    @Test
    fun aBangCommandIsConfirmedByItsOwnRow() {
        val before = listOf(said("0:0", "Done."))
        val p = send("!echo hi", before)
        assertEquals(0, follow(listOf(p), before + user("30:0", "!echo hi") + shell("30:1", "echo hi")).size)
    }

    @Test
    fun aBangCommandIsConfirmedByItsShellCallAlsoInTheLastFoldedRow() {
        // Its call folded into the run of calls that was last when it was sent.
        val before = listOf(shell("0:0", "ls"))
        val p = send("!echo hi", before)
        assertEquals(1, follow(listOf(p), before).size)
        assertEquals(0, follow(listOf(p), listOf(shell("0:0", "ls", "echo hi"))).size)
    }

    @Test
    fun aShellCallAlreadyThereDoesNotConfirmAnotherRun() {
        val before = listOf(shell("0:0", "echo hi"))
        val p = send("!echo hi", before)
        assertEquals(1, follow(listOf(p), before).size)
        assertEquals(0, follow(listOf(p), before + shell("60:0", "echo hi")).size)
    }

    @Test
    fun aPlainMessageIsNotConfirmedByAShellCall() {
        val p = send("echo hi", emptyList())
        assertEquals(1, follow(listOf(p), listOf(shell("0:0", "echo hi"))).size)
    }

    @Test
    fun clearIsConfirmedInTheNewLog() {
        // Older rows had been read back; the new log's keys start again.
        val old = listOf(user("100:0", "/clear"), said("200:0", "a"), said("90000:0", "b"))
        val p = send("/clear", old)
        assertEquals(0, follow(listOf(p), listOf(user("310:0", "/clear"))).size)
    }

    @Test
    fun theFirstMessageAfterClearIsConfirmedInTheNewLog() {
        val newLog = listOf(user("310:0", "/clear"))
        val p = send("Run the tests", newLog)
        assertEquals(0, follow(listOf(p), newLog + user("800:0", "Run the tests")).size)
    }

    // --- Queued, Sending, Not in the conversation yet ---

    @Test
    fun queuedWhileTheAgentShowsItQueuedAndNeverExpiresThen() {
        val p = send("Run the tests", emptyList(), status = "working")
        var list = follow(listOf(p), emptyList(), status = "working", now = 200) { true }
        assertEquals(PendingState.Queued, list[0].state(now = 200))
        list = follow(list, emptyList(), status = "working", now = 60_000) { true }
        assertEquals(PendingState.Queued, list[0].state(now = 60_000))
    }

    @Test
    fun anUnqueuedMessageExpiresThirtySecondsAfterItWasSent() {
        val p = send("Run the tests", emptyList(), at = 1_000)
        assertEquals(PendingState.Sending, p.state(now = 30_999))
        assertEquals(PendingState.NotArrived, p.state(now = 31_000))
    }

    @Test
    fun aMessageStillBeingSentDoesNotExpire() {
        // A photo upload can take a while; the 30 s count from the moment it was typed.
        val p = pendingFor(1, "What is on this screen?", 1, emptyList(), "idle", now = 0)
        assertEquals(PendingState.Sending, p.state(now = 120_000))
    }

    @Test
    fun aQueuedMessageExpiresThirtySecondsAfterTheAgentStopped() {
        val p = send("Run the tests", emptyList(), status = "working")
        var list = follow(listOf(p), emptyList(), status = "working", now = 500) { true }
        // Taken in: no longer queued, the agent still works, its row not yet written.
        list = follow(list, emptyList(), status = "working", now = 40_000) { false }
        assertEquals(PendingState.Sending, list[0].state(now = 40_000))
        list = follow(list, emptyList(), status = "done", now = 50_000) { false }
        assertEquals(PendingState.Sending, list[0].state(now = 79_999))
        assertEquals(PendingState.NotArrived, list[0].state(now = 80_000))
        // Working again: the count starts over when it stops.
        list = follow(list, emptyList(), status = "working", now = 81_000) { false }
        assertEquals(PendingState.Sending, list[0].state(now = 90_000))
    }

    @Test
    fun aMessageThatHasNotArrivedStillTurnsIntoItsRow() {
        val p = send("Run the tests", emptyList())
        assertEquals(PendingState.NotArrived, p.state(now = 31_000))
        assertEquals(0, follow(listOf(p), listOf(user("0:0", "Run the tests")), now = 45_000).size)
    }

    @Test
    fun claudeCodesQueueIsMatchedByText() {
        val p = send("Run the tests", emptyList())
        assertTrue(p.queuedIn(listOf("other", "Run the tests")))
        assertFalse(p.queuedIn(listOf("Run the tests now")))
        // A photo's queued text has its paths first, one per line.
        val photo = send("What is this?", emptyList(), images = 2)
        assertTrue(photo.queuedIn(listOf("/tmp/a.png\n/tmp/b.png\nWhat is this?")))
    }

    @Test
    fun codexShowsAMidTurnMessageInItsTranscript() {
        val screen = listOf(
            "• Ran cargo test",
            "",
            "› Run the tests and tell me",
            "  what failed",
            "",
            "• Working (12s • esc to interrupt)",
            "",
            "› Ask Codex to do anything",
        )
        assertTrue(codexShows(screen, "Run the tests and tell me what failed"))
        assertTrue("wrapped differently", codexShows(screen, "Run the tests and tell me\nwhat failed"))
        assertFalse("only the start of a line", codexShows(screen, "Run the tests"))
        assertFalse(codexShows(screen, "Something else"))
    }

    // --- the trailing space and the second Enter ---

    @Test
    fun aLastWordStartingWithAtGetsASpace() {
        assertEquals("Read @/work/website/notes/plan.md ", typedText("Read @/work/website/notes/plan.md"))
        assertEquals("@notes.md ", typedText("@notes.md"))
        assertEquals("Look at\n@notes.md ", typedText("Look at\n@notes.md"))
        assertEquals("Read @notes.md now", typedText("Read @notes.md now"))
        assertEquals("already spaced @x ", typedText("already spaced @x "))
        assertEquals("mail me@example.org", typedText("mail me@example.org"))
        assertEquals("", typedText(""))
    }

    private val claudeBox = listOf(
        "⏺ Done.",
        "",
        "❯ an earlier queued message",
        "──────────────────────────────",
        "❯ Read @/work/website/notes/plan.md ",
        "──────────────────────────────",
        "  ⏵⏵ auto mode on (shift+tab to cycle)",
    )

    @Test
    fun claudeCodesInputBoxIsReadBetweenItsRules() {
        assertTrue(boxHolds("claude", claudeBox, "Read @/work/website/notes/plan.md", images = 0))
        assertFalse("a message queued above the box is not in it", boxHolds("claude", claudeBox, "an earlier queued message", 0))
        assertFalse("the box holds more", boxHolds("claude", claudeBox, "Read", 0))
        // A rule in an answer above (a separator the agent printed) is not the box's.
        val ruled = listOf("──────", "❯ Read @notes.md", "  some answer text") + claudeBox
        assertTrue(boxHolds("claude", ruled, "Read @/work/website/notes/plan.md", 0))
        assertFalse(boxHolds("claude", ruled, "Read @notes.md", 0))
    }

    @Test
    fun aWrappedMessageInTheBoxIsStillFound() {
        val box = listOf(
            "────────────",
            "❯ Read @/work/website/no",
            "  tes/plan.md and then",
            "  summarise it",
            "────────────",
        )
        assertTrue(boxHolds("claude", box, "Read @/work/website/notes/plan.md and then summarise it", 0))
        assertTrue(boxHolds("claude", box, "Read @/work/website/notes/plan.md and then\nsummarise it", 0))
    }

    @Test
    fun aPhotoSendIsComparedAfterThePlaceholders() {
        val box = listOf("────", "❯ [Image #1] [Image #2] What is this?", "────")
        assertTrue(boxHolds("claude", box, "What is this?", images = 2))
        assertFalse("placeholders count as text without photos", boxHolds("claude", box, "What is this?", images = 0))
    }

    @Test
    fun codexsInputBoxIsItsLastPrompt() {
        val held = listOf("› an older message", "", "• Done", "", "› Read @notes.md", "", "  ⏎ send   ⌃J newline")
        assertTrue(boxHolds("codex", held, "Read @notes.md", 0))
        assertFalse(boxHolds("codex", held, "an older message", 0))
        assertFalse(boxHolds("unknown", held, "Read @notes.md", 0))
    }

    @Test
    fun enterIsPressedOnceMoreOnlyWhenTheBoxStillHoldsTheMessage() {
        val p = send("Read @notes.md", emptyList(), at = 10_000)
        assertFalse("too soon", p.needsEnter(now = 11_499, boxHoldsIt = true))
        assertTrue(p.needsEnter(now = 11_500, boxHoldsIt = true))
        assertFalse("the box holds other text or nothing", p.needsEnter(now = 12_000, boxHoldsIt = false))
        assertFalse("once per message, never twice", p.enteredAgain().needsEnter(now = 20_000, boxHoldsIt = true))
        assertFalse("not before it was typed", pendingFor(1, "x", 0, emptyList(), "idle", 0).needsEnter(now = 9_000, boxHoldsIt = true))
    }

    // --- the waiting line after a slash command ---

    @Test
    fun aSlashCommandWithNoRowAfterThreeSecondsShowsTheWaitingLine() {
        val p = send("/usage", emptyList(), status = "idle", at = 1_000)
        assertFalse(p.waiting(now = 3_999))
        assertTrue(p.waiting(now = 4_000))
        assertEquals("Waiting for the terminal: /usage may have opened a dialog there.", send("/usage now", emptyList()).waitingText)
        assertFalse("a plain message just waits", send("Say hi", emptyList()).waiting(now = 60_000))
    }

    @Test
    fun theWaitingLineRefusesSendsUntilItEnds() {
        val p = send("/usage", emptyList(), status = "idle", at = 0)
        assertEquals(DIALOG_FIRST, sendRefusal(listOf(p), now = 5_000))
        assertNull("dismissed", sendRefusal(listOf(p.dismissWait()), now = 5_000))
        val changed = follow(listOf(p), emptyList(), status = "working", now = 4_000).single()
        assertFalse("the status changed since it was sent", changed.waiting(now = 5_000))
        val back = follow(listOf(changed), emptyList(), status = "idle", now = 6_000).single()
        assertFalse("ended for good", back.waiting(now = 7_000))
        assertNull(sendRefusal(listOf(back), now = 7_000))
        assertEquals("its row arrived", 0, follow(listOf(p), listOf(user("0:0", "/usage")), now = 5_000).size)
    }

    // --- the working line and the poll pace ---

    @Test
    fun aPlainMessageOnItsWayShowsTheWorkingLine() {
        assertTrue(busy("working", emptyList(), now = 0))
        assertTrue(busy("done", listOf(send("Fix the retry", emptyList())), now = 100))
        assertFalse("a slash command may only open a dialog", busy("done", listOf(send("/usage", emptyList())), now = 100))
        assertFalse("a ! command runs in the shell", busy("idle", listOf(send("  !git status", emptyList())), now = 100))
        assertFalse("not after it has expired", busy("idle", listOf(send("Fix the retry", emptyList())), now = 31_000))
        for (s in listOf("done", "idle", "blocked", "unknown", "")) assertFalse(s, busy(s, emptyList(), now = 0))
    }

    @Test
    fun pollsEveryHalfSecondWhileWorkingOrAMessageWaits() {
        assertEquals(500L, pollMs("working", emptyList(), now = 0))
        assertEquals(500L, pollMs("idle", listOf(send("/usage", emptyList())), now = 100))
        assertEquals(5_000L, pollMs("idle", emptyList(), now = 0))
        assertEquals(5_000L, pollMs("blocked", emptyList(), now = 0))
        assertEquals("an expired bubble no longer waits", 5_000L, pollMs("idle", listOf(send("x", emptyList())), now = 31_000))
    }
}
