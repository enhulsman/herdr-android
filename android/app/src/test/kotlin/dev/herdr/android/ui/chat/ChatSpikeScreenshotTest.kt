package dev.herdr.android.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
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
import uniffi.herdr_mobile.ChatImage
import uniffi.herdr_mobile.ChatPoll
import uniffi.herdr_mobile.ChatRow

// Spike for issue 17 (never merged): the try-it chat view with an invented
// session. Goldens: src/test/snapshots/chat-spike-*.png.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatSpikeScreenshotTest {
    private val header = TerminalHeader("upload retry", ProgramIcon.Claude, "website · workstation", "done", SheetTone.None)

    private fun call(name: String, title: String, failed: Boolean = false, done: Boolean = true, out: String = "ok", images: List<ChatImage> = emptyList()) =
        ChatCall(name, title, title, out, done, failed, images)

    private fun row(kind: String, text: String = "", images: List<ChatImage> = emptyList(), calls: List<ChatCall> = emptyList()) =
        ChatRow(kind, text, images, calls)

    /** A synthetic picture: a two-colour gradient with a light disc. */
    private fun picture(w: Int, h: Int, from: Int, to: Int): ImageBitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), from, to, Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.7f, h * 0.35f, minOf(w, h) * 0.18f, Paint().apply { color = 0xCCFFFFFF.toInt(); isAntiAlias = true })
        return b.asImageBitmap()
    }

    private val shots = mapOf(
        "log:100:0" to picture(1080, 2340, 0xFF1E6FD9.toInt(), 0xFF0B2A55.toInt()),
        "log:200:0" to picture(1200, 800, 0xFFD93A1E.toInt(), 0xFFF2B33D.toInt()),
        "log:300:0" to picture(900, 900, 0xFF2E9E6B.toInt(), 0xFF0E3B2A.toInt()),
    )
    private val source = object : ChatImageSource {
        override fun cached(key: String) = shots[key]
        override suspend fun load(key: String) = shots[key]
    }
    private fun img(key: String) = ChatImage(key, "image/png")

    private val withImages = ChatPoll(
        state = "ready", reason = "", agent = "claude", title = "Login screen", version = "2.1.323",
        canSend = true, cut = false,
        rows = listOf(
            row("user", "The login button sits too low on this phone. Fix it?", images = listOf(img("log:100:0"), img("log:200:0"))),
            row("said", "Taking a screenshot of the current screen first."),
            row("tools", calls = listOf(call("Bash", "Take a screenshot"), call("Read", "Read shots/login.png", images = listOf(img("log:300:0"))))),
            row("said", "The button is pushed down by the keyboard inset."),
            row("user", "And this one?", images = listOf(img("missing"))),
        ),
    )

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
            row("user", "What is in this screenshot?"),
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
    fun uploadsAreScaledOnlyWhenLarge() {
        assertEquals(null, uploadLongEdge(1080, 1920, 900_000))
        assertEquals(2000, uploadLongEdge(4000, 3000, 2_000_000))
        assertEquals("too many bytes: re-encoded at its own size", 1600, uploadLongEdge(1600, 1200, 5_000_000))
    }

    @Test
    fun thumbnailsKeepTheirShapeWithinLimits() {
        assertEquals(112.dp, thumbWidth(1000, 1000))
        assertEquals("a tall phone screenshot is clamped", 72.dp, thumbWidth(1080, 2340))
        assertEquals(168.dp, thumbWidth(1200, 800))
        assertEquals(240.dp, thumbWidth(4000, 500))
    }

    @Test
    fun images() = capture("chat-spike-images", withImages, ChatSpikeState(), dark = true)

    @Test
    fun imageViewer() = capture("chat-spike-viewer", withImages, ChatSpikeState().apply { viewer = "log:300:0" }, dark = true)

    @Test
    fun attachedImages() = capture(
        "chat-spike-attach", withImages,
        ChatSpikeState().apply {
            draft = "Same bug here"
            attached = listOf(Attachment("a.png", ByteArray(0), shots.getValue("log:100:0")), Attachment("b.png", ByteArray(0), shots.getValue("log:200:0")))
        },
        dark = false,
    )

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
                ChatSpikeScreen(header, poll, state, source, onBack = {}, onTerminal = {}, onPick = {}, onSend = {})
            }
        }
    }
}
