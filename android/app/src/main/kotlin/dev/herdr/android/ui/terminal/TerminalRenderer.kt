package dev.herdr.android.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Typeface

/**
 * Draws a [TerminalGrid] with JetBrains Mono (ligatures off) onto an Android
 * canvas. Colours arrive resolved to RGB in the frames (16, 256 and true
 * colour); bold uses the bold face, italic a slant, faint half opacity;
 * underline, strikethrough, inverse, hidden text, wide cells and the four
 * cursor shapes are drawn here.
 *
 * Rows are kept as recorded [Picture]s and only the rows a frame carried are
 * recorded again (as the M0.5 spike's bitmap did), so steady output costs a
 * row or two per frame. Rows are recorded at one font size ([draw]'s
 * `fontPx`) and scaled by the canvas for zoom, so a pinch redraws nothing;
 * a new font size, grid size or default colour records every row again.
 * Main thread only.
 */
class TerminalRenderer(private val regular: Typeface, private val bold: Typeface) {
    // Subpixel positions: whole-pixel advances would make a run of text drift off its cells.
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = regular
        fontFeatureSettings = MONO_FEATURES
    }
    private val fill = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var pictures = arrayOfNulls<Picture>(0)
    private var recorded: Recorded? = null

    private data class Recorded(val cols: Int, val rows: Int, val fontPx: Float, val fg: Int, val bg: Int)

    private var cellW = 0f
    private var cellH = 0f
    private var baseline = 0f
    private val run = StringBuilder()

    /** How many rows the last [draw] recorded (for tests and the frame-time check). */
    var lastRecorded = 0
        private set

    /**
     * Draws [grid]'s rows with their top left at the canvas origin, one cell
     * [fontPx] * 0.6 wide and * 1.25 high; [damage] names the rows that
     * changed since the last draw.
     */
    fun draw(canvas: Canvas, grid: TerminalGrid, damage: RowDamage.Taken, fontPx: Float) {
        lastRecorded = 0
        if (grid.isEmpty || fontPx <= 0f) return
        val want = Recorded(grid.cols, grid.rows, fontPx, grid.defaultFg, grid.defaultBg)
        val all = want != recorded || damage.all
        if (want != recorded) {
            recorded = want
            pictures = arrayOfNulls(grid.rows)
            measure(fontPx)
        }
        for (y in 0 until grid.rows) {
            if (all || pictures[y] == null || y in damage.rows) {
                pictures[y] = record(grid, y)
                lastRecorded++
            }
        }
        for (y in 0 until grid.rows) {
            val save = canvas.save()
            canvas.translate(0f, y * cellH)
            canvas.drawPicture(pictures[y]!!)
            canvas.restoreToCount(save)
        }
        drawCursor(canvas, grid)
    }

    private fun measure(fontPx: Float) {
        text.textSize = fontPx
        text.letterSpacing = 0f
        cellW = fontPx * TerminalMetrics.ADVANCE_EM
        cellH = fontPx * TerminalMetrics.LINE_EM
        // Runs must keep to the cells: if the font's advance at this size is
        // off by a fraction, letter spacing (in em) takes up the difference.
        val advance = text.measureText(PROBE) / PROBE.length
        if (advance > 0f) text.letterSpacing = (cellW - advance) / fontPx
        val m = text.fontMetrics
        baseline = (cellH - (m.descent - m.ascent)) / 2f - m.ascent
    }

    private fun record(grid: TerminalGrid, y: Int): Picture {
        val picture = Picture()
        val c = picture.beginRecording(Math.ceil((grid.cols * cellW).toDouble()).toInt(), Math.ceil(cellH.toDouble()).toInt())
        drawBackgrounds(c, grid, y)
        drawText(c, grid, y)
        picture.endRecording()
        return picture
    }

    private fun drawBackgrounds(c: Canvas, grid: TerminalGrid, y: Int) {
        fill.color = OPAQUE or grid.defaultBg
        c.drawRect(0f, 0f, grid.cols * cellW, cellH, fill)
        var x = 0
        while (x < grid.cols) {
            val bg = grid.bg(x, y)
            var end = x + 1
            while (end < grid.cols && grid.bg(end, y) == bg) end++
            if (bg != TerminalGrid.DEFAULT) {
                fill.color = OPAQUE or bg
                // Cells meet without hairline gaps when the canvas is scaled.
                c.drawRect(x * cellW, 0f, end * cellW + SEAM, cellH + SEAM, fill)
            }
            x = end
        }
    }

    private fun drawText(c: Canvas, grid: TerminalGrid, y: Int) {
        var runStart = 0
        var runStyle = 0L
        fun flush() {
            if (run.isEmpty()) return
            style(runStyle)
            c.drawText(run, 0, run.length, runStart * cellW, baseline, text)
            run.setLength(0)
        }
        for (x in 0 until grid.cols) {
            if (grid.isSpacer(x, y) || grid.isInvisible(x, y)) {
                flush()
                continue
            }
            decorate(c, grid, x, y)
            if (grid.isBlank(x, y)) {
                // A blank inside a run keeps it going: whole lines of words in one call.
                if (run.isNotEmpty() && runStart + run.length == x) run.append(' ') else flush()
                continue
            }
            val key = styleKey(grid, x, y)
            val cp = grid.codepoint(x, y)
            if (!grid.isWide(x, y) && !grid.hasExtras(x, y) && cp in RUN_RANGE) {
                if (run.isNotEmpty() && (key != runStyle || runStart + run.length != x)) flush()
                if (run.isEmpty()) {
                    runStart = x
                    runStyle = key
                }
                run.append(cp.toChar())
            } else {
                flush()
                style(key)
                c.drawText(grid.text(x, y), x * cellW, baseline, text)
            }
        }
        flush()
    }

    private fun styleKey(grid: TerminalGrid, x: Int, y: Int): Long {
        var flags = 0
        if (grid.isBold(x, y)) flags = flags or 1
        if (grid.isItalic(x, y)) flags = flags or 2
        if (grid.isFaint(x, y)) flags = flags or 4
        return (grid.fg(x, y).toLong() shl 8) or flags.toLong()
    }

    private fun style(key: Long) {
        val flags = (key and 0xFF).toInt()
        val rgb = (key shr 8).toInt()
        text.color = (if (flags and 4 != 0) FAINT else OPAQUE) or rgb
        text.typeface = if (flags and 1 != 0) bold else regular
        text.textSkewX = if (flags and 2 != 0) ITALIC_SKEW else 0f
    }

    /** Underline and strikethrough across the cell (both halves of a wide one), in its text colour. */
    private fun decorate(c: Canvas, grid: TerminalGrid, x: Int, y: Int) {
        val under = grid.isUnderline(x, y)
        val strike = grid.isStrike(x, y)
        if (!under && !strike) return
        val right = (x + if (grid.isWide(x, y)) 2 else 1) * cellW
        val thick = (text.textSize * LINE_THICKNESS).coerceAtLeast(1f)
        fill.color = (if (grid.isFaint(x, y)) FAINT else OPAQUE) or grid.fg(x, y)
        if (under) {
            val top = (baseline + text.textSize * 0.12f).coerceAtMost(cellH - thick)
            c.drawRect(x * cellW, top, right, top + thick, fill)
        }
        if (strike) {
            val mid = baseline - text.textSize * 0.28f
            c.drawRect(x * cellW, mid - thick / 2, right, mid + thick / 2, fill)
        }
    }

    /** The cursor in the terminal's default foreground (render 4's `.cursor`), never recorded. */
    private fun drawCursor(canvas: Canvas, grid: TerminalGrid) {
        val x = grid.cursorX
        val y = grid.cursorY
        if (!grid.cursorShown || x >= grid.cols || y >= grid.rows) return
        val width = if (grid.isWide(x, y)) 2 else 1
        val left = x * cellW
        val top = y * cellH
        val color = OPAQUE or grid.defaultFg
        when (grid.cursorShape) {
            CursorShape.Block -> {
                fill.color = color
                canvas.drawRect(left, top, left + width * cellW, top + cellH, fill)
                if (!grid.isBlank(x, y)) {
                    style(styleKey(grid, x, y))
                    text.color = OPAQUE or grid.defaultBg
                    canvas.drawText(grid.text(x, y), left, top + baseline, text)
                }
            }
            CursorShape.Bar -> {
                fill.color = color
                canvas.drawRect(left, top, left + (cellW * 0.12f).coerceAtLeast(1f), top + cellH, fill)
            }
            CursorShape.Underline -> {
                fill.color = color
                val h = (cellH * 0.08f).coerceAtLeast(1f)
                canvas.drawRect(left, top + cellH - h, left + width * cellW, top + cellH, fill)
            }
            CursorShape.Hollow -> {
                stroke.color = color
                stroke.strokeWidth = (cellW * 0.08f).coerceAtLeast(1f)
                val half = stroke.strokeWidth / 2
                canvas.drawRect(left + half, top + half, left + width * cellW - half, top + cellH - half, stroke)
            }
        }
    }

    companion object {
        /** Ligatures off, so `===` and `->` stay three and two cells (typography.families.mono). */
        const val MONO_FEATURES = "'liga' 0, 'calt' 0"
        private const val OPAQUE = 0xFF shl 24
        private const val FAINT = 0x80 shl 24
        private const val ITALIC_SKEW = -0.2f
        private const val LINE_THICKNESS = 0.06f
        private const val SEAM = 0.5f
        private const val PROBE = "MMMMMMMMMMMMMMMMMMMM"

        /** Codepoints drawn in runs: Latin text, where every glyph is one advance wide. */
        private val RUN_RANGE = 0x21..0x24F
    }
}
