package dev.herdr.android.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdr.android.ui.agents.AgentKey
import java.util.concurrent.Executor
import uniffi.herdr_mobile.Armed
import uniffi.herdr_mobile.RowKeyTap
import uniffi.herdr_mobile.TermState
import uniffi.herdr_mobile.TerminalListener
import uniffi.herdr_mobile.TerminalViewInterface

/**
 * One open terminal on the phone. The Rust core (crates/herdr-mobile/src/term.rs)
 * owns the session: observe and active attaches, reattaching on size events and
 * reconnects, the phone size while typing. This side keeps the grid its frames
 * describe, the state and the held modifiers for the screen, and passes input
 * on. The [listener] hands everything to [main], so state changes only on the
 * main thread; after [close] nothing is applied or sent. Tested with a fake
 * terminal (TerminalSessionTest).
 */
class TerminalSession(val key: AgentKey, private val main: Executor) {
    val grid = TerminalGrid()

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
        return applyFrame(frame)
    }

    private inline fun send(block: TerminalViewInterface.() -> Unit) {
        if (closed) return
        view?.block()
    }

    fun type(text: String) = send { armed = typeText(text) }
    fun backspace() = send { armed = backspace() }
    fun tap(key: RowKeyTap) = send { armed = tap(key) }
    fun paste(text: String) = send { paste(text) }
    fun scroll(rows: Int) = send { if (rows != 0) scroll(rows) }
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
