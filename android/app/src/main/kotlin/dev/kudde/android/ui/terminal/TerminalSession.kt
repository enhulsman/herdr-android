package dev.kudde.android.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.theme.KuddeColors
import dev.kudde.android.ui.theme.ThemeName
import dev.kudde.android.ui.theme.kuddeColors
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import uniffi.kudde_mobile.Armed
import uniffi.kudde_mobile.RowKeyTap
import uniffi.kudde_mobile.TermState
import uniffi.kudde_mobile.TerminalListener
import uniffi.kudde_mobile.TerminalTheme
import uniffi.kudde_mobile.TerminalViewInterface

/**
 * One open terminal on the phone. The Rust core (crates/kudde-mobile/src/term.rs)
 * owns the session: observe and active attaches, reattaching on size events and
 * reconnects, the phone size while typing. This side keeps the grid its frames
 * describe, the state and the held modifiers for the screen, and passes input
 * on. The [listener] hands everything to [main], so state changes only on the
 * main thread; after [close] nothing is applied or sent. Tested with a fake
 * terminal (TerminalSessionTest).
 */
class TerminalSession(
    val key: AgentKey,
    private val main: Executor,
    initialTheme: TerminalTheme,
) {
    constructor(key: AgentKey, main: Executor) : this(key, main, kuddeColors(ThemeName.Graphite).terminalTheme())

    var grid = TerminalGrid()
        private set
    private var theme = initialTheme

    /** Bumps with every frame applied, so the terminal redraws. */
    var version by mutableIntStateOf(0)
        private set

    /** The grid's size and whether its view is live; null before the first frame. Changes far less than [version]. */
    var shape by mutableStateOf<GridShape?>(null)
        private set

    /** Rows changed since the renderer last drew. */
    val damage = RowDamage()

    var state by mutableStateOf<TermState?>(null)
        private set

    /** Ctrl and alt held for the next key (the keys row shows them latched). */
    var armed by mutableStateOf(Armed(ctrl = false, alt = false))
        private set

    /** The last interrupt's report, until the screen has shown it. */
    var report by mutableStateOf<String?>(null)
        private set

    /** The live first paint arrived; before it the grid may hold the cached screen. */
    var painted by mutableStateOf(false)
        private set

    private var view: TerminalViewInterface? = null
    private var closed = false

    val listener: TerminalListener = object : TerminalListener {
        override fun onFrame(frame: ByteArray) = main.execute { applyLive(frame) }
        override fun onState(state: TermState) = main.execute { if (!closed) this@TerminalSession.state = state }
        override fun onInterrupt(report: String) = main.execute { if (!closed) this@TerminalSession.report = report }
    }

    fun attach(view: TerminalViewInterface) {
        if (closed) return
        this.view = view
    }

    /** The defaults travel with open, before the engine can deliver its first frame. */
    fun open(open: (TerminalTheme, TerminalListener) -> TerminalViewInterface?): TerminalViewInterface? {
        if (closed) return null
        return open(theme, listener)?.also(::attach)
    }

    /** Only changes the local engine; no input, resize or reattach for a theme. */
    fun setTheme(theme: TerminalTheme) {
        if (closed || this.theme == theme) return
        view?.setTheme(theme)
        this.theme = theme
        // A cached first paint can already be visible when the mode changes.
        // Live frames may have defaults set by a program, so leave those alone.
        if (!painted && !grid.isEmpty &&
            (grid.defaultFg.toUInt() != theme.fg || grid.defaultBg.toUInt() != theme.bg)
        ) {
            grid = TerminalGrid()
            shape = null
            damage.markAll()
            version++
        }
    }

    private fun applyLive(frame: ByteArray) {
        if (closed) return
        if (applyFrame(frame)) painted = true
    }

    private fun applyFrame(frame: ByteArray): Boolean {
        val result = grid.apply(frame) as? FrameResult.Applied ?: return false
        if (result.full) damage.markAll() else damage.mark(result.rows)
        val now = GridShape(grid.cols, grid.rows, grid.live)
        if (now != shape) shape = now
        version++
        return true
    }

    /** The cached last screen, shown only while nothing live has been drawn; true when shown. */
    fun showCached(frame: ByteArray): Boolean {
        if (closed || painted) return false
        if (frame.size < TerminalGrid.HEADER_LEN) return false
        val header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        if ((header.getInt(12) and TerminalGrid.RGB).toUInt() != theme.fg ||
            (header.getInt(16) and TerminalGrid.RGB).toUInt() != theme.bg
        ) return false
        return applyFrame(frame)
    }

    private inline fun send(block: TerminalViewInterface.() -> Unit) {
        if (closed) return
        view?.block()
    }

    fun type(text: String) = send { armed = typeText(text) }
    fun backspace() = send { armed = backspace() }
    fun tap(key: RowKeyTap) = send { armed = tap(key) }
    /** False when the core refused it: a line break the program would take as Enter. */
    fun paste(text: String): Boolean = if (closed) true else view?.paste(text) ?: true
    fun scroll(rows: Int) = send { if (rows != 0) scroll(rows) }

    /** Takes over at the phone's size without typing (a tap while watching). */
    fun lead() = send { lead() }

    /**
     * The terminal takes the phone's size only while it shows: under the chat
     * it watches at the desk's size, and shown again it takes over.
     */
    fun follow(chat: Boolean) = send { if (chat) watch() else lead() }

    /** A tap on cell ([col], [row]) of the phone's grid; the core clicks a program that takes clicks. */
    fun click(col: Int, row: Int) = send { click(col.coerceIn(0, 0xFFFF).toUShort(), row.coerceIn(0, 0xFFFF).toUShort()) }
    fun live() = send { live() }
    fun interrupt() = send { interrupt() }
    fun phoneSize(size: GridSize) = send {
        phoneSize(size.cols.coerceIn(1, 0xFFFF).toUShort(), size.rows.coerceIn(1, 0xFFFF).toUShort())
    }

    fun reportShown() {
        report = null
    }

    /**
     * Detaches and frees the terminal; returns the last live screen as a full
     * frame for the instant-open cache, or null when nothing live was drawn.
     */
    fun close(): ByteArray? {
        if (closed) return null
        closed = true
        view?.let {
            it.detach()
            (it as? AutoCloseable)?.close()
        }
        view = null
        return if (painted) grid.toFullFrameOrNull() else null
    }
}

/** UniFFI takes RGB words, without Compose's alpha byte. */
fun KuddeColors.terminalTheme() = TerminalTheme(
    fg = (termFg.toArgb() and TerminalGrid.RGB).toUInt(),
    bg = (bg.toArgb() and TerminalGrid.RGB).toUInt(),
    ansi = ansi.map { (it.toArgb() and TerminalGrid.RGB).toUInt() },
)

/** Opened under the chat, the terminal watches at the desk's size (see [TerminalSession.follow]). */
fun atPhoneSizeOnOpen(chat: Boolean) = !chat

data class GridShape(val cols: Int, val rows: Int, val live: Boolean)

/** Rows to redraw, collected across frames until [take]. */
class RowDamage {
    class Taken(val all: Boolean, val rows: Set<Int>) {
        val isEmpty: Boolean get() = !all && rows.isEmpty()
    }

    private var all = false
    private val rows = HashSet<Int>()

    fun markAll() {
        all = true
        rows.clear()
    }

    fun mark(changed: IntArray) {
        if (!all) changed.forEach { rows += it }
    }

    fun take(): Taken = Taken(all, rows.toSet()).also {
        all = false
        rows.clear()
    }
}
