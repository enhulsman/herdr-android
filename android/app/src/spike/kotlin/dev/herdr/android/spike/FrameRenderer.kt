package dev.herdr.android.spike

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import dev.herdr.android.R
import dev.herdr.android.ui.terminal.FrameResult
import dev.herdr.android.ui.terminal.RowDamage
import dev.herdr.android.ui.terminal.TerminalGrid
import dev.herdr.android.ui.terminal.TerminalMetrics
import dev.herdr.android.ui.terminal.TerminalRenderer
import kotlin.math.ceil

// Draws frames from the Rust core into one bitmap with the app's own frame
// parser (TerminalGrid) and renderer (TerminalRenderer), so the spike measures
// the code the terminal screen runs: rows are recorded again only when a frame
// carries them, and every row is replayed into the bitmap.
class FrameRenderer(cols: Int, rows: Int, widthPx: Int, resources: Resources) {
    private val grid = TerminalGrid()
    private val damage = RowDamage()
    private val renderer = TerminalRenderer(
        resources.getFont(R.font.jetbrains_mono_regular),
        resources.getFont(R.font.jetbrains_mono_bold),
    )
    private val fontPx = widthPx / (cols * TerminalMetrics.ADVANCE_EM)
    val bitmap: Bitmap = Bitmap.createBitmap(
        widthPx,
        ceil(rows * fontPx * TerminalMetrics.LINE_EM).toInt(),
        Bitmap.Config.ARGB_8888,
    )
    private val canvas = Canvas(bitmap)

    fun apply(frame: ByteArray) {
        val result = grid.apply(frame)
        check(result is FrameResult.Applied) { "frame rejected: ${(result as FrameResult.Rejected).reason}" }
        if (result.full) damage.markAll() else damage.mark(result.rows)
        renderer.draw(canvas, grid, damage.take(), fontPx)
    }
}
