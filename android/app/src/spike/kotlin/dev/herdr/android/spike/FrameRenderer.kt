package dev.herdr.android.spike

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

// Draws frames from the Rust core (format: crates/herdr-mobile/src/vt/mod.rs)
// into one bitmap, redrawing only the rows a frame carries. Spike code: the
// system monospace font, ASCII drawn in runs, anything else one cell at a time.
class FrameRenderer(private val cols: Int, private val rows: Int, widthPx: Int) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fill = Paint()
    val cellWidth: Float
    val cellHeight: Float
    val bitmap: Bitmap
    private val canvas: Canvas
    private val run = CharArray(cols)
    private var defaultFg = 0xFFEEEEEF.toInt()
    private var defaultBg = 0xFF17191D.toInt()
    var cursorX = 0
        private set
    var cursorY = 0
        private set
    var cursorShown = false
        private set

    init {
        cellWidth = widthPx.toFloat() / cols
        paint.textSize = 10f
        paint.textSize = 10f * cellWidth / paint.measureText("M")
        cellHeight = ceil(paint.fontSpacing)
        bitmap = Bitmap.createBitmap(widthPx, ceil(cellHeight * rows).toInt(), Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap)
        canvas.drawColor(defaultBg)
    }

    fun apply(frame: ByteArray) {
        val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        check(b.getShort(0).toInt() == cols && b.getShort(2).toInt() == rows) { "frame size" }
        cursorX = b.getShort(4).toInt()
        cursorY = b.getShort(6).toInt()
        cursorShown = b.get(8).toInt() and FLAG_CURSOR != 0
        val count = b.getShort(10).toInt() and 0xFFFF
        defaultFg = OPAQUE or b.getInt(12)
        defaultBg = OPAQUE or b.getInt(16)
        val rowLen = 2 + cols * CELL_LEN
        for (r in 0 until count) {
            val at = HEADER_LEN + r * rowLen
            drawRow(b, b.getShort(at).toInt(), at + 2)
        }
    }

    private fun drawRow(b: ByteBuffer, y: Int, cells: Int) {
        val top = y * cellHeight
        fill.color = defaultBg
        canvas.drawRect(0f, top, bitmap.width.toFloat(), top + cellHeight, fill)

        // Backgrounds, merged into runs of one colour.
        var x = 0
        while (x < cols) {
            val bg = background(b, cells + x * CELL_LEN)
            var end = x + 1
            while (end < cols && background(b, cells + end * CELL_LEN) == bg) end++
            if (bg != defaultBg) {
                fill.color = bg
                canvas.drawRect(x * cellWidth, top, end * cellWidth, top + cellHeight, fill)
            }
            x = end
        }

        // Text: ASCII in runs of one style, other codepoints placed per cell.
        val baseline = top - paint.ascent()
        var runStart = -1
        var runLen = 0
        var runStyle = 0L
        fun flush() {
            if (runLen > 0) {
                style(runStyle)
                canvas.drawText(run, 0, runLen, runStart * cellWidth, baseline, paint)
            }
            runLen = 0
            runStart = -1
        }
        for (c in 0 until cols) {
            val at = cells + c * CELL_LEN
            val cp = b.getInt(at)
            val attrs = b.get(at + 12).toInt() and 0xFF
            val wide = b.get(at + 13).toInt()
            if (cp == 0 || cp == 0x20 || wide == WIDE_SPACER_TAIL || attrs and ATTR_INVISIBLE != 0) {
                flush()
                continue
            }
            val key = (foreground(b, at).toLong() shl 8) or attrs.toLong()
            if (cp < 0x80) {
                if (runLen > 0 && (key != runStyle || runStart + runLen != c)) flush()
                if (runLen == 0) {
                    runStart = c
                    runStyle = key
                }
                run[runLen++] = cp.toChar()
            } else {
                flush()
                style(key)
                canvas.drawText(String(Character.toChars(cp)), c * cellWidth, baseline, paint)
            }
        }
        flush()
    }

    private fun style(key: Long) {
        val attrs = (key and 0xFF).toInt()
        var color = (key shr 8).toInt()
        if (attrs and ATTR_FAINT != 0) color = (color and 0x00FFFFFF) or (0x99 shl 24)
        paint.color = color
        paint.isFakeBoldText = attrs and ATTR_BOLD != 0
        paint.textSkewX = if (attrs and ATTR_ITALIC != 0) -0.25f else 0f
        paint.isUnderlineText = attrs and ATTR_UNDERLINE != 0
        paint.isStrikeThruText = attrs and ATTR_STRIKETHROUGH != 0
    }

    private fun foreground(b: ByteBuffer, at: Int): Int {
        val inverse = b.get(at + 12).toInt() and ATTR_INVERSE != 0
        return if (inverse) resolve(b.getInt(at + 8), defaultBg) else resolve(b.getInt(at + 4), defaultFg)
    }

    private fun background(b: ByteBuffer, at: Int): Int {
        val inverse = b.get(at + 12).toInt() and ATTR_INVERSE != 0
        return if (inverse) resolve(b.getInt(at + 4), defaultFg) else resolve(b.getInt(at + 8), defaultBg)
    }

    private fun resolve(word: Int, default: Int): Int =
        if (word and COLOR_SET != 0) OPAQUE or (word and 0x00FFFFFF) else default

    private companion object {
        const val HEADER_LEN = 20
        const val CELL_LEN = 16
        const val FLAG_CURSOR = 2
        const val COLOR_SET = 0x01000000
        const val OPAQUE = 0xFF shl 24
        const val ATTR_BOLD = 1
        const val ATTR_ITALIC = 1 shl 1
        const val ATTR_FAINT = 1 shl 2
        const val ATTR_UNDERLINE = 1 shl 3
        const val ATTR_INVERSE = 1 shl 4
        const val ATTR_STRIKETHROUGH = 1 shl 5
        const val ATTR_INVISIBLE = 1 shl 6
        const val WIDE_SPACER_TAIL = 2
    }
}
