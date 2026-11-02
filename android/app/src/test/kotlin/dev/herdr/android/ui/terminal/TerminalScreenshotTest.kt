package dev.herdr.android.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.agents.connected
import dev.herdr.android.ui.agents.row
import dev.herdr.android.ui.agents.snapshot
import dev.herdr.android.ui.theme.HerdrTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.Armed
import uniffi.herdr_mobile.RowKeyTap
import uniffi.herdr_mobile.TermPhase
import uniffi.herdr_mobile.TermState

// The terminal screen against renders 3, 4, 4b and 5
// (design/checkpoints/3-terminal-watch.png, 4-terminal-type.png,
// 4b-terminal-type-hidden.png, 5-compose.png), graphite, from frames in the
// wire format with an invented agent session. Reference phone 412 x 915 dp at
// density 2, without system bars; the phone keyboard (drawn by Gboard in the
// renders) is an empty area of its height. Goldens: src/test/snapshots/<name>.png.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TerminalScreenshotTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val key = AgentKey("workstation", "t1")
    private val hub = snapshot(
        machines = listOf(connected("workstation")),
        agents = listOf(
            row("t1", AgentAttention.NEEDS_YOU, name = "docs nav", folder = "website", kind = "Permission",
                request = "Bash: git push origin main"),
            row("t2", AgentAttention.NEEDS_YOU, name = "api tests", folder = "api", program = "codex", kind = "Question",
                request = "Which test runner should I use?"),
            row("t4", AgentAttention.WORKING, name = "pricing page", folder = "website"),
        ),
    )

    /** Render 3: the desk's 96 x 44 fitted to the width, the space below it not terminal. */
    @Test
    fun watchingGraphite() {
        val session = session(TermPhase.Watching, SessionFixture.frame(96, 44, wide = true, bottom = false))
        capture("terminal-watch-graphite", session, TerminalScreenState())
    }

    /** Render 4: typing at phone size with the keyboard up; ctrl held. */
    @Test
    fun typingGraphite() {
        val size = phoneSizeFor(heightDp = 915 - KEYBOARD - 64 - 34 - 48)
        val session = session(TermPhase.Typing, SessionFixture.frame(size.cols, size.rows, wide = false, bottom = true))
        session.tap(RowKeyTap.Ctrl)
        capture("terminal-type-graphite", session, typing(), keyboardDp = KEYBOARD)
    }

    /** Render 4b: the keyboard hidden while typing; the grid takes the full height, the keys row stays. */
    @Test
    fun typingKeyboardHiddenGraphite() {
        val size = phoneSizeFor(heightDp = 915 - 64 - 34 - 48)
        val session = session(TermPhase.Typing, SessionFixture.frame(size.cols, size.rows, wide = false, bottom = true))
        session.tap(RowKeyTap.Ctrl)
        capture("terminal-type-hidden-graphite", session, typing())
    }

    /** Render 5: the compose sheet over the terminal, above the keyboard. */
    @Test
    fun composeGraphite() {
        val size = phoneSizeFor(heightDp = 915 - KEYBOARD - 64 - 34 - 48)
        val session = session(TermPhase.Typing, SessionFixture.frame(size.cols, size.rows, wide = false, bottom = false))
        val screen = typing().apply {
            composeOpen = true
            composeText = "Push it, then open a PR against main with a short summary of"
        }
        capture("terminal-compose-graphite", session, screen, keyboardDp = KEYBOARD, composeFocus = false)
    }

    /** Scrolled up while watching: "Live" returns to the bottom. No render; from the tokens. */
    @Test
    fun scrolledUpGraphite() {
        val frame = SessionFixture.frame(96, 44, wide = true, bottom = false, live = false)
        capture("terminal-scrolled-graphite", session(TermPhase.Watching, frame), TerminalScreenState())
    }

    /** The machine dropped: the last screen stays, with what is happening. No render; from the tokens. */
    @Test
    fun waitingGraphite() {
        val frame = SessionFixture.frame(96, 44, wide = true, bottom = false)
        capture("terminal-waiting-graphite", session(TermPhase.Waiting, frame), TerminalScreenState())
    }

    private fun typing() = TerminalScreenState().apply {
        typing = true
        inputOpen = true
    }

    private fun phoneSizeFor(heightDp: Int): GridSize {
        val d = 2f
        return phoneGrid(412 * d, heightDp * d, 10 * d, 6 * d, 12.2f * d)
    }

    private fun session(phase: TermPhase, frame: ByteArray): TerminalSession {
        val session = TerminalSession(key) { it.run() }
        val view = FakeTerminalView().apply { armedAfterTap = Armed(ctrl = true, alt = false) }
        session.attach(view)
        val grid = TerminalGrid().apply { apply(frame) }
        session.listener.onState(TermState(phase, grid.cols.toUShort(), grid.rows.toUShort()))
        session.listener.onFrame(frame)
        return session
    }

    private fun capture(
        name: String,
        session: TerminalSession,
        screen: TerminalScreenState,
        keyboardDp: Int = 0,
        composeFocus: Boolean = true,
    ) {
        val ui = terminalUi(hub, key, res)
        val actions = TerminalActions(onBack = {}, onOpen = {}, onSwipe = { false }, onPhoneGrid = {})
        captureRoboImage("src/test/snapshots/$name.png") {
            HerdrTheme(dark = true) {
                Column {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        TerminalScreen(ui, session, screen, actions, TerminalInputHandle.None, composeFocus = composeFocus)
                    }
                    if (keyboardDp > 0) {
                        Box(Modifier.fillMaxWidth().height(keyboardDp.dp).background(HerdrTheme.colors.canvas))
                    }
                }
            }
        }
    }

    private companion object {
        /** Render 4's keyboard with the gesture bar, in dp. */
        const val KEYBOARD = 259
    }
}

/**
 * An invented Claude Code session in the wire format, after design/renders/screens.html
 * (termLines): the desk's colours are graphite's terminal colours.
 */
object SessionFixture {
    private const val FG = 0xd2d4d9
    private const val BG = 0x17191d
    private const val A1 = 0xdc8c95
    private const val A2 = 0xa6bd91
    private const val A4 = 0x96b2d7
    private const val A6 = 0x8cbdc3
    private const val A8 = 0x818793
    private const val A15 = 0xf0f0f2
    private const val BOLD = 1

    private data class Seg(val text: String, val fg: Int? = null, val bold: Boolean = false)

    private class Line(val segs: List<Seg>, val bg: Int? = null)

    private fun line(vararg segs: Seg, bg: Int? = null) = Line(segs.toList(), bg)
    private fun t(text: String, fg: Int? = null, bold: Boolean = false) = Seg(text, fg, bold)
    private val dot = t("●", A15, bold = true)

    /** [bg] mixed [p] into the terminal background, as the renders' diff lines. */
    private fun mix(color: Int, p: Float): Int {
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        fun m(shift: Int) = (ch(BG, shift) + (ch(color, shift) - ch(BG, shift)) * p).toInt().coerceIn(0, 255)
        return (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    private fun lines(cols: Int, wide: Boolean): List<Line> {
        val add = mix(A2, 0.16f)
        val del = mix(A1, 0.16f)
        val head = buildList {
            if (wide) {
                add(line(t(">", A8), t(" fix the docs nav so the current page is marked, then push")))
            } else {
                add(line(t(">", A8), t(" fix the docs nav so the current page is marked,")))
                add(line(t("  then push")))
            }
            add(line())
            add(line(dot, t(" Search"), t("(pattern: \"docs-nav\", path: \"src\")", A8)))
            add(line(t("  ⎿  Found 3 files", A8)))
            add(line())
            add(line(dot, t(" Read"), t("(src/layouts/Docs.astro)", A8)))
            add(line(t("  ⎿  Read 57 lines", A8)))
            add(line())
            if (wide) {
                add(line(dot, t(" The nav gets "), t("active", A6), t(" from the layout but never uses it.")))
            } else {
                add(line(dot, t(" The nav gets "), t("active", A6), t(" from the layout but never uses")))
                add(line(t("  it.")))
            }
            add(line())
        }
        val w = cols - 2
        fun boxRow(text: String, fg: Int? = null, bold: Boolean = false) =
            line(t("│", A4), t(text.padEnd(w), fg, bold), t("│", A4))
        return head + buildList {
            add(line(dot, t(" Read"), t("(src/components/DocsNav.astro)", A8)))
            add(line(t("  ⎿  Read 84 lines", A8)))
            add(line())
            add(line(dot, t(" Update"), t("(src/components/DocsNav.astro)", A8)))
            add(line(t("  ⎿  Updated with 2 additions and 1 removal", A8)))
            add(line(t("      41", A8), t("    <nav class=\"docs-nav\">")))
            add(line(t("      42", A8), t(" "), t("-", A1), t("    <a href=\"/guide\">Guide</a>"), bg = del))
            if (wide) {
                add(line(t("      42", A8), t(" "), t("+", A2), t("    <a href=\"/guide\" aria-current={active === \"guide\"}>Guide</a>"), bg = add))
            } else {
                add(line(t("      42", A8), t(" "), t("+", A2), t("    <a href=\"/guide\""), bg = add))
                add(line(t("      43", A8), t(" "), t("+", A2), t("      aria-current={active === \"guide\"}>"), bg = add))
            }
            add(line(t("      43", A8), t("    <a href=\"/api\">API</a>")))
            add(line())
            add(line(dot, t(" Active link now has aria-current. Build passes.")))
            add(line())
            add(line(dot, t(" Bash"), t("(git push origin main)", A8)))
            add(line(t("╭" + "─".repeat(w) + "╮", A4)))
            add(boxRow(" Bash command", bold = true))
            add(boxRow(""))
            add(boxRow("   git push origin main"))
            add(boxRow("   Push the docs nav fix", A8))
            add(boxRow(""))
            add(boxRow(" Do you want to proceed?"))
            add(boxRow(" ❯ 1. Yes", A4))
            add(boxRow("   2. Yes, and don't ask again for git push"))
            add(boxRow("   3. No, and tell Claude what to do differently"))
            add(line(t("╰" + "─".repeat(w) + "╯", A4)))
        }
    }

    /**
     * The session on a [cols] x [rows] grid: from the top, or its last rows at
     * the [bottom] of the screen (after Claude Code redraws at a new size).
     */
    fun frame(cols: Int, rows: Int, wide: Boolean, bottom: Boolean, live: Boolean = true): ByteArray {
        var all = lines(cols, wide)
        if (all.size > rows) all = all.takeLast(rows)
        val first = if (bottom) rows - all.size else 0
        val content = all.withIndex().associate { (i, l) ->
            val cells = mutableListOf<FCell>()
            for (seg in l.segs) {
                for (cp in seg.text.codePoints()) {
                    cells += FCell(String(Character.toChars(cp)), seg.fg, l.bg, if (seg.bold) BOLD else 0)
                }
            }
            while (cells.size < cols) cells += FCell(bg = l.bg)
            (first + i) to cells.take(cols)
        }
        return frameOf(
            cols, rows, content, cursorShown = false, fg = FG, bg = BG,
            live = live, total = rows + if (live) 0 else 120, offset = if (live) 0 else 60,
        )
    }
}
