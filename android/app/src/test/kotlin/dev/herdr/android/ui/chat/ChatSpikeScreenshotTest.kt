package dev.herdr.android.ui.chat

import com.github.takahirom.roborazzi.captureRoboImage
import dev.herdr.android.ui.agents.ProgramIcon
import dev.herdr.android.ui.agents.SheetTone
import dev.herdr.android.ui.terminal.TerminalHeader
import dev.herdr.android.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatPoll
import uniffi.herdr_mobile.ChatRow

// Spike for issue 17 (never merged): the try-it chat view with an invented
// session. Goldens: src/test/snapshots/chat-spike-*.png.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatSpikeScreenshotTest {
    private val header = TerminalHeader("upload retry", ProgramIcon.Claude, "website · workstation", "done", SheetTone.None)

    private fun call(name: String, title: String, failed: Boolean = false, done: Boolean = true, out: String = "ok") =
        ChatCall(name, title, title, out, done, failed)

    private fun row(kind: String, text: String = "", images: UInt = 0u, calls: List<ChatCall> = emptyList()) =
        ChatRow(kind, text, images, calls)

    private val calls = listOf(
        call("Bash", "Find the retry code", out = "12: retry(3)"),
        call("Read", "Read src/upload.rs", out = "fn upload() {}"),
        call("Bash", "cargo test", failed = true, out = "test upload::retries ... FAILED\n1 failed"),
    )

    private val poll = ChatPoll(
        state = "ready", reason = "", agent = "claude", title = "Upload retry fix", version = "2.1.323",
        canSend = true, cut = true,
        rows = listOf(
            row("user", "The upload job gives up after one try. Fix the **retry** and add a test."),
            row("said", "Looking at the job first."),
            row("tools", calls = calls),
            row("said", "The retry ran **once** because the counter started at the limit:\n\n- fixed the loop in `upload.rs`\n- added `retries_three_times`\n\n```\ncargo test upload\n```\nAll green now."),
            row("note", "Conversation compacted"),
            row("user", "What is in this screenshot?", images = 1u),
            row("tools", calls = listOf(call("Bash", "Check the build", done = false, out = ""))),
        ),
    )

    @Test
    fun foldedLineSaysWhatRan() {
        assertEquals("Ran 2 commands", toolLine(listOf(call("Bash", "a"), call("Shell", "b"))))
        assertEquals("Used 3 tools · 1 failed", toolLine(calls))
        assertEquals("Check the build · running", toolLine(listOf(call("Bash", "Check the build", done = false))))
    }

    @Test
    fun markdownBlocks() {
        val b = markdownBlocks("# Title\n\npara one\ncontinues\n\n- a\n  - b\n1. c\n```\nx\n```")
        assertEquals(
            listOf(
                MdBlock.Heading("Title"), MdBlock.Para("para one continues"), MdBlock.Item("•", "a", 0),
                MdBlock.Item("•", "b", 1), MdBlock.Item("1.", "c", 0), MdBlock.Code("x"),
            ),
            b,
        )
    }

    @Test
    fun chatGraphite() = capture("chat-spike-graphite", poll, ChatSpikeState(), dark = true)

    @Test
    fun chatPaper() = capture("chat-spike-paper", poll, ChatSpikeState(), dark = false)

    @Test
    fun toolSheet() = capture("chat-spike-sheet", poll, ChatSpikeState().apply { sheet = calls }, dark = true)

    @Test
    fun toolDetail() = capture("chat-spike-detail", poll, ChatSpikeState().apply { sheet = calls; detail = calls[2] }, dark = true)

    @Test
    fun fallback() = capture(
        "chat-spike-fallback",
        poll.copy(state = "fallback", reason = "unreadable log: not this agent's format", rows = emptyList()),
        ChatSpikeState(),
        dark = true,
    )

    @Test
    fun cannotSend() = capture("chat-spike-busy", poll.copy(canSend = false), ChatSpikeState(), dark = true)

    private fun capture(name: String, poll: ChatPoll, state: ChatSpikeState, dark: Boolean) {
        captureRoboImage("src/test/snapshots/$name.png") {
            HerdrTheme(dark = dark) {
                ChatSpikeScreen(header, poll, state, onBack = {}, onTerminal = {}, onSend = {})
            }
        }
    }
}
