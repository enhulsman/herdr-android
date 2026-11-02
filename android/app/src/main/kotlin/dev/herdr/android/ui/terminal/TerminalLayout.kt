package dev.herdr.android.ui.terminal

import dev.herdr.android.ui.agents.AgentKey
import kotlin.math.floor

// The terminal's layout math, in pixels, pure so it is tested on the JVM
// (TerminalLayoutTest). Sizes come from design-tokens.json: layout.terminalFit,
// layout.terminalPadding (10 dp sides, 6 dp top: the Mac pane's padding) and
// typography.terminalPhone / terminalWatch (JetBrains Mono, 0.6 em advance,
// 1.25 em lines).

object TerminalMetrics {
    /** JetBrains Mono's advance: every glyph is 600 of 1000 units wide. */
    const val ADVANCE_EM = 0.6f
    const val LINE_EM = 1.25f

    /** The keyboard's slide is over when the size has held this long (then the phone size goes out once). */
    const val SETTLE_MS = 250L
}

data class GridSize(val cols: Int, val rows: Int)

/** The phone's own grid in an area of [widthPx] x [heightPx], inside the padding; at least 1 x 1. */
fun phoneGrid(widthPx: Float, heightPx: Float, padXPx: Float, padTopPx: Float, fontPx: Float): GridSize {
    val cols = floor((widthPx - 2 * padXPx) / (fontPx * TerminalMetrics.ADVANCE_EM) + EPSILON).toInt()
    val rows = floor((heightPx - padTopPx) / (fontPx * TerminalMetrics.LINE_EM) + EPSILON).toInt()
    return GridSize(cols.coerceAtLeast(1), rows.coerceAtLeast(1))
}

private const val EPSILON = 1e-3f

/** Watching: [zoom] 1 is fit-to-width; the pan moves the scaled grid (0, 0 at its top left). */
data class WatchView(val zoom: Float, val panX: Float, val panY: Float) {
    companion object {
        val Fit = WatchView(1f, 0f, 0f)
    }
}

/**
 * Watching at desk size (terminal-view spec, "Fit to width, zoom and pan"): the
 * desk's [cols] x [rows] scaled so the grid and its side padding fill the
 * width; pinch zooms between that and [maxFontPx] (the phone's readable size),
 * padding included, and a drag pans while the grid overflows.
 */
class WatchLayout(
    private val widthPx: Float,
    private val heightPx: Float,
    padXPx: Float,
    private val padTopPx: Float,
    cols: Int,
    private val rows: Int,
    maxFontPx: Float,
) {
    val fitFontPx: Float = (widthPx - 2 * padXPx) / (cols.coerceAtLeast(1) * TerminalMetrics.ADVANCE_EM)
    val maxZoom: Float = (maxFontPx / fitFontPx).coerceAtLeast(1f)

    /** The grid with its padding at [zoom], in pixels. */
    fun contentWidth(zoom: Float) = widthPx * zoom
    fun contentHeight(zoom: Float) = (padTopPx + rows * fitFontPx * TerminalMetrics.LINE_EM) * zoom

    fun clamp(view: WatchView): WatchView {
        val zoom = view.zoom.coerceIn(1f, maxZoom)
        val minX = widthPx - contentWidth(zoom)
        val minY = (heightPx - contentHeight(zoom)).coerceAtMost(0f)
        return WatchView(zoom, view.panX.coerceIn(minX, 0f), view.panY.coerceIn(minY, 0f))
    }

    /** Pinch by [factor] around the fingers at ([focusX], [focusY]): the point under them stays put. */
    fun zoomBy(view: WatchView, factor: Float, focusX: Float, focusY: Float): WatchView {
        val zoom = (view.zoom * factor).coerceIn(1f, maxZoom)
        val k = zoom / view.zoom
        return clamp(WatchView(zoom, focusX - (focusX - view.panX) * k, focusY - (focusY - view.panY) * k))
    }

    /** A drag: pans as far as the grid allows; returns the vertical part left over, for the scrollback. */
    fun panBy(view: WatchView, dx: Float, dy: Float): Pair<WatchView, Float> {
        val moved = clamp(WatchView(view.zoom, view.panX + dx, view.panY + dy))
        return moved to dy - (moved.panY - view.panY)
    }
}

/** The zoom each terminal was left at, for this run of the app (in memory only). */
class ZoomMemory {
    private val zooms = HashMap<AgentKey, Float>()

    operator fun get(key: AgentKey): Float? = zooms[key]

    operator fun set(key: AgentKey, zoom: Float) {
        zooms[key] = zoom
    }
}

/** Turns a drag into whole rows of scrolling; a finger moving down goes back in history (negative). */
class RowScroller(private val cellHeightPx: Float) {
    private var pending = 0f

    fun drag(dy: Float): Int {
        pending -= dy
        val rows = (pending / cellHeightPx).toInt()
        pending -= rows * cellHeightPx
        return rows
    }

    fun reset() {
        pending = 0f
    }
}

/**
 * Sends the phone's grid size once it has held for [settleMs] (the keyboard's
 * slide is over), never the same size twice and never the size the terminal
 * was [opened] with. Driven by [offer] on every layout and [tick] after the
 * delay.
 */
class SizeSettler(private val settleMs: Long, opened: GridSize? = null, private val send: (GridSize) -> Unit) {
    private var sent: GridSize? = opened
    private var pending: GridSize? = null
    private var since = 0L

    fun offer(size: GridSize, nowMs: Long) {
        if (size != pending) {
            pending = size
            since = nowMs
        }
    }

    fun tick(nowMs: Long) {
        val size = pending ?: return
        if (size != sent && nowMs - since >= settleMs) {
            sent = size
            send(size)
        }
    }
}
