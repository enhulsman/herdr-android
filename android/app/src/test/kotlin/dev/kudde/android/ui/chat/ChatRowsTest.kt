package dev.kudde.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.kudde_mobile.ChatCall
import uniffi.kudde_mobile.ChatImage
import uniffi.kudde_mobile.ChatRow

// Spec "The chat shows the conversation as the agents' apps do", "Images show
// in the chat", "New answers come in as if typed", "The chat follows the
// bottom and the keyboard", "A working line shows for the whole turn".
class ChatRowsTest {
    private fun row(key: String, kind: String = "said", text: String = "", images: List<String> = emptyList(), calls: List<ChatCall> = emptyList()) =
        ChatRow(key, kind, text, "", images.map { ChatImage(it, "image/png") }, calls)
    private fun call(name: String, vararg images: String, done: Boolean = true, failed: Boolean = false) =
        ChatCall(name, "$name title", "", "", done, failed, images.map { ChatImage(it, "image/png") })

    // --- Markdown ---

    @Test
    fun aSingleLineBreakInsideAParagraphIsKept() {
        val blocks = markdownBlocks("**Done:** the parser\n**Running:** nothing\n**Next:** 4.2")
        assertEquals(listOf(MdBlock.Para("**Done:** the parser\n**Running:** nothing\n**Next:** 4.2")), blocks)
        assertEquals(3, inline((blocks[0] as MdBlock.Para).text).text.lines().size)
    }

    @Test
    fun aBlankLineStillStartsANewParagraph() {
        assertEquals(listOf(MdBlock.Para("one"), MdBlock.Para("two")), markdownBlocks("one\n\ntwo"))
    }

    @Test
    fun listsHeadingsCodeAndTablesAreBlocks() {
        val src = "# Plan\n- first\n  - nested\n1. one\n```\nlet x = 1;\n  indented\n```\n| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(
            listOf(
                MdBlock.Heading("Plan"),
                MdBlock.Item("•", "first", 0),
                MdBlock.Item("•", "nested", 1),
                MdBlock.Item("1.", "one", 0),
                MdBlock.Code("let x = 1;\n  indented"),
                MdBlock.Table(listOf("a", "b"), listOf(listOf("1", "2"))),
            ),
            markdownBlocks(src),
        )
    }

    @Test
    fun aQuoteIsABlockWithoutItsMarkers() {
        // Seen in a real answer: a drafted reply quoted between paragraphs.
        val src = "So the reply is in Dutch too:\n> Hoi, sorry voor de late reactie.\n> Past woensdag?\nYour calendar is free."
        assertEquals(
            listOf(
                MdBlock.Para("So the reply is in Dutch too:"),
                MdBlock.Quote(listOf(MdBlock.Para("Hoi, sorry voor de late reactie.\nPast woensdag?"))),
                MdBlock.Para("Your calendar is free."),
            ),
            markdownBlocks(src),
        )
    }

    @Test
    fun aQuoteHoldsItsOwnParagraphsAndLists() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(MdBlock.Para("one"), MdBlock.Item("•", "two", 0), MdBlock.Para("three")))),
            markdownBlocks("> one\n>\n> - two\n>three"),
        )
    }

    @Test
    fun aGreaterThanSignInsideALineIsText() {
        assertEquals(listOf(MdBlock.Para("a > b")), markdownBlocks("a > b"))
    }

    @Test
    fun inlineCodeIsMarkedNotPaintedAsASpanBackground() {
        val s = inline("Run `cargo test` and `ls`")
        assertEquals("Run cargo test and ls", s.text)
        assertTrue("a span background would paint over the selection", s.spanStyles.none { it.item.background != androidx.compose.ui.graphics.Color.Unspecified })
        assertEquals(listOf("cargo test", "ls"), s.getStringAnnotations(CODE_TAG, 0, s.length).map { s.text.substring(it.start, it.end) })
    }

    // --- tool lines ---

    @Test
    fun aRunOfCallsIsNamedByKindInAFixedOrder() {
        val calls = listOf(call("Read"), call("Bash"), call("Bash"), call("Task"), call("Read"), call("Shell"))
        assertEquals("Ran 3 commands, read 2 files, started 1 agent", toolLine(calls))
        assertEquals("Read title", toolLine(listOf(call("Read"))))
        assertEquals("Ran 2 commands · 1 failed · running", toolLine(listOf(call("Bash", failed = true), call("Bash", done = false))))
    }

    // --- images ---

    @Test
    fun aRowsImagesSwipeInTheOrderShown() {
        val tools = row("0:0", "tools", calls = listOf(call("Read", "a"), call("Bash"), call("View image", "b", "c")))
        assertEquals(listOf("a", "b", "c"), imageKeys(tools))
        assertEquals("c", stepImage(imageKeys(tools), "b", +1))
        assertEquals("a", stepImage(imageKeys(tools), "b", -1))
        assertNull("no wrap at the end", stepImage(imageKeys(tools), "c", +1))
        assertNull(stepImage(imageKeys(tools), "a", -1))
        assertNull("an image no longer in the row", stepImage(imageKeys(tools), "gone", +1))
        assertEquals(listOf("p1", "p2"), imageKeys(row("1:0", "user", "look", images = listOf("p1", "p2"))))
    }

    @Test
    fun aSwipeStepsOnlyWhenLongAndMostlySideways() {
        assertEquals("a swipe left: the next image", +1, swipeStep(dx = -200f, dy = 20f, threshold = 100f))
        assertEquals(-1, swipeStep(dx = 200f, dy = 0f, threshold = 100f))
        assertEquals("too short", 0, swipeStep(dx = -60f, dy = 0f, threshold = 100f))
        assertEquals("mostly down", 0, swipeStep(dx = -150f, dy = 300f, threshold = 100f))
    }

    @Test
    fun theViewerSwipesThroughTheImagesOfItsOwnRow() {
        val rows = listOf(
            row("0:0", "tools", calls = listOf(call("Read", "a"), call("View image", "b", "c"))),
            row("5:0", "user", "look", images = listOf("p1")),
        )
        // Spec: the second of three thumbnails, a swipe left, the third.
        val keys = viewerImages(rows, Viewer("0:0", "b"))
        assertEquals(listOf("a", "b", "c"), keys)
        assertEquals("c", stepImage(keys, "b", swipeStep(dx = -200f, dy = 0f, threshold = 100f)))
        assertEquals("its row gone: only the image open", listOf("x"), viewerImages(rows, Viewer("9:0", "x")))
    }

    // --- fading in what arrives live ---

    @Test
    fun onlyRowsFirstSeenAfterTheFirstReadFadeIn() {
        val live = LiveRows()
        live.saw(listOf(row("0:0"), row("10:0")), now = 1_000, live = false)
        assertNull(live.since("0:0"))
        live.saw(listOf(row("0:0"), row("10:0"), row("20:0")), now = 2_000, live = true)
        assertEquals(2_000L, live.since("20:0"))
        assertNull("already seen", live.since("10:0"))
        // Seen again later: still from when it first came.
        live.saw(listOf(row("0:0"), row("10:0"), row("20:0")), now = 3_000, live = true)
        assertEquals(2_000L, live.since("20:0"))
    }

    @Test
    fun rowsReadBackShowAtOnce() {
        val live = LiveRows()
        live.saw(listOf(row("500:0")), now = 0, live = false)
        live.saw(listOf(row("0:0"), row("90:0"), row("500:0")), now = 1_000, live = false)
        assertNull(live.since("0:0"))
        assertNull(live.since("90:0"))
    }

    @Test
    fun aRowInsertedAboveTheEndStillFades() {
        // Codex places a late command above its turn's answer.
        val live = LiveRows()
        live.saw(listOf(row("0:0"), row("50:0")), now = 0, live = false)
        live.saw(listOf(row("0:0"), row("80:0"), row("50:0")), now = 500, live = true)
        assertEquals(500L, live.since("80:0"))
    }

    @Test
    fun theFadeTakesAtMostSixHundredMilliseconds() {
        assertEquals(1f, fadeIn(since = null, now = 0), 0f)
        assertEquals(0f, fadeIn(since = 1_000, now = 1_000), 0f)
        assertEquals(0.5f, fadeIn(since = 1_000, now = 1_300), 0.001f)
        assertEquals(1f, fadeIn(since = 1_000, now = 1_600), 0f)
        assertEquals(1f, fadeIn(since = 1_000, now = 9_000), 0f)
    }

    @Test
    fun aRowDrawnLateFinishesItsFadeOnTime() {
        assertEquals("history: no fade", 0L, fadeLeftMs(since = null, now = 0))
        assertEquals(FADE_MS, fadeLeftMs(since = 1_000, now = 1_000))
        assertEquals("drawn 200 ms after it was seen", 400L, fadeLeftMs(since = 1_000, now = 1_200))
        assertEquals(0L, fadeLeftMs(since = 1_000, now = 5_000))
    }

    // --- following the bottom ---

    @Test
    fun onlyThePersonsOwnScrollChangesFollowing() {
        // The app's scroll to the end passes a moment with more below: following stays on.
        assertTrue(nextStick(stick = true, byPerson = false, more = true))
        // The person scrolls up: off; back at the end: on again.
        assertFalse(nextStick(stick = true, byPerson = true, more = true))
        assertTrue(nextStick(stick = false, byPerson = true, more = false))
        // Off stays off while the app moves things.
        assertFalse(nextStick(stick = false, byPerson = false, more = false))
    }

    // --- the working line ---

    @Test
    fun theVerbChangesEveryFourSecondsAndCycles() {
        assertEquals(workingVerb(0), workingVerb(VERB_MS - 1))
        assertNotEquals(workingVerb(0), workingVerb(VERB_MS))
        assertEquals(workingVerb(0), workingVerb(VERB_MS * WORKING_VERBS.size))
        assertEquals(4_000L, VERB_MS)
        assertTrue(WORKING_VERBS.containsAll(listOf("Herding", "Rounding up")))
        assertTrue(WORKING_VERBS.all { it.isNotBlank() && !it.endsWith("…") })
        assertEquals(WORKING_VERBS.size, WORKING_VERBS.toSet().size)
    }

    @Test
    fun onlyALiveRunningCallShimmers() {
        val running = listOf(call("Bash", done = false))
        assertTrue(shimmers(running, busy = true))
        assertFalse("a call left open in an old log", shimmers(running, busy = false))
        assertFalse(shimmers(listOf(call("Bash")), busy = true))
    }
}
