package dev.herdr.android.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import dev.herdr.android.ui.agents.ProgramIcon
import dev.herdr.android.ui.agents.SheetTone
import dev.herdr.android.ui.terminal.TerminalActions
import dev.herdr.android.ui.terminal.TerminalHeader
import dev.herdr.android.ui.theme.HerdrTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatFound
import uniffi.herdr_mobile.ChatHealth
import uniffi.herdr_mobile.ChatImage
import uniffi.herdr_mobile.ChatPoll
import uniffi.herdr_mobile.ChatRefusal
import uniffi.herdr_mobile.ChatRow

// The chat screen with an invented session, in paper and graphite (task 4.2).
// Goldens: src/test/snapshots/chat-<scene>-<theme>.png.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatScreenshotTest {
    private val header = TerminalHeader("upload retry", ProgramIcon.Claude, "website · workstation", "done", SheetTone.Done)
    private val actions = TerminalActions(onBack = {}, onOpen = {}, onSwipe = { false }, onPhoneGrid = {})
    private val now = 100_000L

    private fun call(name: String, title: String, failed: Boolean = false, done: Boolean = true, out: String = "ok", images: List<ChatImage> = emptyList(), input: String = title) =
        ChatCall(name, title, input, out, done, failed, images)

    private fun row(key: String, kind: String, text: String = "", images: List<ChatImage> = emptyList(), calls: List<ChatCall> = emptyList(), from: String = "") =
        ChatRow(key, kind, text, from, images, calls)

    /** A synthetic picture: a two-colour gradient with a light disc. */
    private fun picture(w: Int, h: Int, from: Int, to: Int): ImageBitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), from, to, Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.7f, h * 0.35f, minOf(w, h) * 0.18f, Paint().apply { color = 0xCCFFFFFF.toInt(); isAntiAlias = true })
        return b.asImageBitmap()
    }

    private val shots = mapOf(
        "log:s:100:0" to picture(1080, 2340, 0xFF1E6FD9.toInt(), 0xFF0B2A55.toInt()),
        "log:s:200:0" to picture(1200, 800, 0xFFD93A1E.toInt(), 0xFFF2B33D.toInt()),
        "log:s:300:0" to picture(900, 900, 0xFF2E9E6B.toInt(), 0xFF0E3B2A.toInt()),
    )
    private val source = object : ChatImageSource {
        override fun cached(key: String) = shots[key]
        override suspend fun load(key: String) = shots[key]
    }
    private fun img(key: String) = ChatImage(key, "image/png")

    private val calls = listOf(
        call("Bash", "Find the retry code", out = "12: retry(3)", input = "rg -n retry src/upload.rs"),
        call("Read", "Read src/upload.rs", out = "fn upload() {}", input = "{\"file_path\":\"src/upload.rs\"}"),
        call("Bash", "Run the upload tests", failed = true, out = "test upload::retries ... FAILED\n1 failed", input = "cargo test -p upload -- --nocapture"),
    )

    private val rows = listOf(
        row("10:0", "user", "The upload job gives up after one try. Fix the **retry** and add a test."),
        row("90:0", "said", "Looking at the job first."),
        row("140:0", "tools", calls = calls),
        row(
            "900:0", "said",
            "The retry ran **once** because the counter started at the limit:\n\n- fixed the loop in `upload.rs`\n- added `retries_three_times`\n\n" +
                "```\ncargo test upload\n```\n**Done:** the fix and its test\n**Next:** push when you say so",
        ),
        row("1400:0", "agent", "The docs say **three** retries; the limit is in `upload.toml`.", from = "Check the upload docs"),
        row("1600:0", "note", "Conversation compacted"),
    )

    private fun poll(
        rows: List<ChatRow> = this.rows,
        agent: String = "claude",
        status: String = "done",
        found: ChatFound = ChatFound.FOUND,
        health: ChatHealth = ChatHealth.Ok,
        problem: String = "",
        exited: Boolean = false,
        used: ULong? = 283_000u,
        window: ULong? = null,
        percent: UInt? = null,
        queued: List<String> = emptyList(),
    ) = ChatPoll(
        found = found, agent = agent, title = "Upload retry fix", version = "2.1.325", status = status, health = health,
        problem = problem, exited = exited, canSend = !exited, cut = false, rows = rows,
        gaugeUsed = used, gaugeWindow = window, gaugePercent = percent, queued = queued, timings = "",
    )

    private fun capture(scene: String, poll: ChatPoll?, state: () -> ChatState = { ChatState() }) {
        for (dark in listOf(false, true)) {
            val s = state()
            captureRoboImage("src/test/snapshots/chat-$scene-${if (dark) "graphite" else "paper"}.png") {
                HerdrTheme(dark = dark) {
                    ChatScreen(header, actions, poll, s, source, now, onTerminal = {}, onPick = {}, onSend = {}, animate = false)
                }
            }
        }
    }

    /** A message typed [ago] ms before the shot, then followed as the poll has it. */
    private fun sent(text: String, ago: Long, status: String = "idle", images: Int = 0, queued: Boolean = false) = ChatState().apply {
        val p = sent(text, images, rows, status, now - ago - 100)
        update(p.id) { it.typed(now - ago) }
        pending = followPending(pending, rows, status, now) { queued }
    }

    @Test
    fun conversation() = capture("conversation", poll())

    @Test
    fun noMessagesYet() = capture("empty", poll(rows = emptyList(), found = ChatFound.EMPTY, used = null))

    @Test
    fun sending() = capture("sending", poll()) { sent("Also check the upload size limit", ago = 1_000, images = 2) }

    @Test
    fun queued() = capture("queued", poll(status = "working", queued = listOf("Then push it"))) {
        sent("Then push it", ago = 20_000, status = "working", queued = true)
    }

    @Test
    fun notArrived() = capture("not-arrived", poll()) { sent("Run the tests again", ago = 31_000) }

    @Test
    fun waitingLine() = capture("waiting", poll()) {
        // The screen shows the /usage dialog in place of the input box.
        sent("/usage", ago = 4_000).apply { sawScreen("claude", listOf("   Esc to cancel"), now) }
    }

    @Test
    fun refusedSend() = capture("refused", poll(status = "blocked")) {
        ChatState().apply {
            draft = "Use three retries"
            refusal = refusalOf(ChatRefusal.Waiting)
        }
    }

    /** A live turn: the running command shimmers and the working line sits at the bottom. */
    @Test
    fun working() = capture("working", poll(status = "working", rows = rows + row("1800:0", "tools", calls = listOf(call("Bash", "Check the build", done = false, out = "")))))

    @Test
    fun toolSheet() = capture("sheet", poll()) { ChatState().apply { sheet = "140:0" } }

    @Test
    fun toolDetail() = capture("detail", poll()) { ChatState().apply { sheet = "140:0"; detail = 2 } }

    private val withImages = listOf(
        row("10:0", "user", "The login button sits too low on this phone. Fix it?", images = listOf(img("log:s:100:0"), img("log:s:200:0"))),
        row("90:0", "said", "Taking a screenshot of the current screen first."),
        row("140:0", "tools", calls = listOf(call("Bash", "Take a screenshot"), call("Read", "Read shots/login.png", images = listOf(img("log:s:300:0"))))),
        row("300:0", "said", "The button is pushed down by the keyboard inset."),
    )

    @Test
    fun images() = capture("images", poll(rows = withImages))

    @Test
    fun viewer() = capture("viewer", poll(rows = withImages)) { ChatState().apply { viewer = Viewer("140:0", "log:s:300:0") } }

    @Test
    fun imageNotAvailable() = capture("image-missing", poll(rows = withImages + row("400:0", "user", "And this one?", images = listOf(img("missing")))))

    @Test
    fun degraded() = capture("degraded", poll(health = ChatHealth.Degraded))

    @Test
    fun broken() = capture("broken", poll(health = ChatHealth.Broken("message records renamed")))

    @Test
    fun exited() = capture("exited", poll(exited = true, status = "", found = ChatFound.NONE))

    @Test
    fun failedRead() = capture("problem", poll(problem = "Can't read new messages; retrying"))

    @Test
    fun gaugeClaude() = capture("gauge-claude", poll()) { ChatState().apply { gaugeOpen = true } }

    @Test
    fun gaugeCodex() = capture("gauge-codex", poll(agent = "codex", used = 223_000u, window = 475_000u, percent = 47u)) { ChatState().apply { gaugeOpen = true } }

    @Test
    fun looking() = capture("looking", null)

    @Test
    fun noConversation() = capture("none", poll(rows = emptyList(), found = ChatFound.MISSED, used = null))

    @Test
    fun attached() = capture("attach", poll(rows = withImages)) {
        ChatState().apply {
            draft = "Same bug here"
            attached = listOf(Attachment("a.png", ByteArray(0), shots.getValue("log:s:100:0")), Attachment("b.png", ByteArray(0), shots.getValue("log:s:200:0")))
        }
    }
}
