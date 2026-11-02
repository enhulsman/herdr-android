package dev.herdr.android.ui.terminal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The phone's copy of the terminal's view, kept from the frames the Rust core
// sends (format: the module doc of crates/herdr-mobile/src/vt/mod.rs). Pure
// Kotlin, so it is tested on the JVM against the same golden grids as Rust
// (TerminalGridTest). Main thread only.

enum class CursorShape { Bar, Block, Underline, Hollow }

/** What applying a frame did. */
sealed interface FrameResult {
    /** [rows]: the rows the frame carried, in order; [full]: every row was replaced. */
    class Applied(val rows: IntArray, val full: Boolean, val resized: Boolean) : FrameResult

    /** Malformed or out of order; the grid is unchanged. */
    class Rejected(val reason: String) : FrameResult
}

class TerminalGrid {
    var cols = 0
        private set
    var rows = 0
        private set

    /** `0xRRGGBB`. */
    var defaultFg = 0
        private set
    var defaultBg = 0
        private set
    var cursorX = 0
        private set
    var cursorY = 0
        private set
    var cursorShown = false
        private set
    var cursorShape = CursorShape.Block
        private set

    /** The view is at the bottom, following output. */
    var live = true
        private set

    /** Rows in screen and scrollback together, and the view's first row among them. */
    var total = 0L
        private set
    var offset = 0L
        private set

    val isEmpty: Boolean get() = cols == 0

    private var cp = IntArray(0)
    private var fgWord = IntArray(0)
    private var bgWord = IntArray(0)
    private var attrs = ByteArray(0)
    private var width = ByteArray(0)
    private var extras = arrayOfNulls<IntArray>(0)

    /** Applies one frame, whole or not at all. */
    fun apply(frame: ByteArray): FrameResult {
        if (frame.size < HEADER_LEN) return FrameResult.Rejected("short header")
        val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val newCols = b.getShort(0).toInt() and 0xFFFF
        val newRows = b.getShort(2).toInt() and 0xFFFF
        val flags = b.get(8).toInt()
        val count = b.getShort(10).toInt() and 0xFFFF
        val full = flags and FLAG_FULL != 0
        if (newCols == 0 || newRows == 0) return FrameResult.Rejected("empty grid")
        if (newCols > MAX_SIDE || newRows > MAX_SIDE || newCols * newRows > MAX_CELLS) {
            return FrameResult.Rejected("grid too large")
        }
        val resized = newCols != cols || newRows != rows
        if (!full && resized) return FrameResult.Rejected("partial frame for another size")
        if (count > newRows) return FrameResult.Rejected("more rows than the grid")

        // Check every row first, so a bad frame changes nothing.
        val rowLen = 2 + newCols * CELL_LEN
        val starts = IntArray(count)
        val ys = IntArray(count)
        var at = HEADER_LEN
        for (r in 0 until count) {
            if (at + rowLen > frame.size) return FrameResult.Rejected("truncated row")
            val y = b.getShort(at).toInt() and 0xFFFF
            if (y >= newRows) return FrameResult.Rejected("row outside the grid")
            starts[r] = at
            ys[r] = y
            var more = 0
            for (x in 0 until newCols) more += frame[at + 2 + x * CELL_LEN + 14].toInt() and 0xFF
            at += rowLen + more * 4
            if (at > frame.size) return FrameResult.Rejected("truncated codepoints")
        }
        if (at != frame.size) return FrameResult.Rejected("trailing bytes")

        if (resized || full) {
            if (resized) allocate(newCols, newRows) else clear()
        }
        cols = newCols
        rows = newRows
        cursorX = b.getShort(4).toInt() and 0xFFFF
        cursorY = b.getShort(6).toInt() and 0xFFFF
        cursorShown = flags and FLAG_CURSOR != 0
        live = flags and FLAG_LIVE != 0
        cursorShape = when (b.get(9).toInt()) {
            0 -> CursorShape.Bar
            2 -> CursorShape.Underline
            3 -> CursorShape.Hollow
            else -> CursorShape.Block
        }
        defaultFg = b.getInt(12) and RGB
        defaultBg = b.getInt(16) and RGB
        total = b.getInt(20).toLong() and 0xFFFFFFFFL
        offset = b.getInt(24).toLong() and 0xFFFFFFFFL
        for (r in 0 until count) readRow(b, ys[r], starts[r] + 2)
        return FrameResult.Applied(ys, full, resized)
    }

    private fun readRow(b: ByteBuffer, y: Int, cellsAt: Int) {
        var extraAt = cellsAt + cols * CELL_LEN
        for (x in 0 until cols) {
            val at = cellsAt + x * CELL_LEN
            val i = y * cols + x
            cp[i] = b.getInt(at)
            fgWord[i] = b.getInt(at + 4)
            bgWord[i] = b.getInt(at + 8)
            attrs[i] = b.get(at + 12)
            width[i] = b.get(at + 13)
            val more = b.get(at + 14).toInt() and 0xFF
            extras[i] = if (more == 0) {
                null
            } else {
                IntArray(more) { b.getInt(extraAt + it * 4) }.also { extraAt += more * 4 }
            }
        }
    }

    private fun allocate(cols: Int, rows: Int) {
        val n = cols * rows
        cp = IntArray(n)
        fgWord = IntArray(n)
        bgWord = IntArray(n)
        attrs = ByteArray(n)
        width = ByteArray(n)
        extras = arrayOfNulls(n)
    }

    private fun clear() {
        cp.fill(0)
        fgWord.fill(0)
        bgWord.fill(0)
        attrs.fill(0)
        width.fill(0)
        extras.fill(null)
    }

    private fun index(x: Int, y: Int) = y * cols + x

    private fun attr(x: Int, y: Int, bit: Int) = attrs[index(x, y)].toInt() and bit != 0

    fun isBold(x: Int, y: Int) = attr(x, y, ATTR_BOLD)
    fun isItalic(x: Int, y: Int) = attr(x, y, ATTR_ITALIC)
    fun isFaint(x: Int, y: Int) = attr(x, y, ATTR_FAINT)
    fun isUnderline(x: Int, y: Int) = attr(x, y, ATTR_UNDERLINE)
    fun isInverse(x: Int, y: Int) = attr(x, y, ATTR_INVERSE)
    fun isStrike(x: Int, y: Int) = attr(x, y, ATTR_STRIKE)
    fun isInvisible(x: Int, y: Int) = attr(x, y, ATTR_INVISIBLE)

    /** The first half of a wide character; its second half is a spacer. */
    fun isWide(x: Int, y: Int) = width[index(x, y)].toInt() == WIDTH_WIDE

    /** The tail of a wide character, or the spacer a wide character left at a line end. */
    fun isSpacer(x: Int, y: Int) = width[index(x, y)].toInt().let { it == WIDTH_SPACER || it == WIDTH_SPACER_HEAD }

    /** The cell's colours as drawn (`0xRRGGBB`): inverse applied. */
    fun fg(x: Int, y: Int): Int {
        val i = index(x, y)
        return if (attrs[i].toInt() and ATTR_INVERSE != 0) {
            resolve(bgWord[i], defaultBg)
        } else {
            resolve(fgWord[i], defaultFg)
        }
    }

    /** The cell's background as drawn, or [DEFAULT] for the terminal's own background. */
    fun bg(x: Int, y: Int): Int {
        val i = index(x, y)
        return if (attrs[i].toInt() and ATTR_INVERSE != 0) {
            resolve(fgWord[i], defaultFg)
        } else {
            val w = bgWord[i]
            if (w and COLOR_SET != 0) w and RGB else DEFAULT
        }
    }

    /** True when the cell draws nothing but its background. */
    fun isBlank(x: Int, y: Int): Boolean {
        val i = index(x, y)
        val c = cp[i]
        return c == 0 || c == ' '.code || isSpacer(x, y) || attrs[i].toInt() and ATTR_INVISIBLE != 0
    }

    /** The cell's whole grapheme as drawn: "" for no text, hidden text and spacers. */
    fun text(x: Int, y: Int): String {
        val i = index(x, y)
        if (cp[i] == 0 || isSpacer(x, y) || attrs[i].toInt() and ATTR_INVISIBLE != 0) return ""
        val sb = StringBuilder(2)
        appendCodepoint(sb, cp[i])
        extras[i]?.forEach { appendCodepoint(sb, it) }
        return sb.toString()
    }

    /** The cell's first codepoint, 0 for none. */
    fun codepoint(x: Int, y: Int): Int = cp[index(x, y)]

    /** True when the cell's grapheme has more than one codepoint. */
    fun hasExtras(x: Int, y: Int): Boolean = extras[index(x, y)] != null

    /** One row as text for copying: whole graphemes, spacers skipped, trailing spaces cut. */
    fun rowText(y: Int): String = buildString {
        for (x in 0 until cols) {
            if (isSpacer(x, y)) continue
            val t = text(x, y)
            if (t.isEmpty()) append(' ') else append(t)
        }
    }.trimEnd(' ')

    /** The view as text: a line per row, trailing empty rows cut. */
    fun text(): String {
        val lines = (0 until rows).map(::rowText).toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines.joinToString("\n")
    }

    fun toFullFrameOrNull(): ByteArray? = if (isEmpty) null else toFullFrame()

    /** The grid as one full frame, as Rust would send it (the instant-open cache keeps these). */
    fun toFullFrame(): ByteArray {
        check(!isEmpty) { "no frame yet" }
        val out = ByteArrayOutputStream(HEADER_LEN + rows * (2 + cols * CELL_LEN))
        val h = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(cols.toShort()).putShort(rows.toShort())
        h.putShort(cursorX.toShort()).putShort(cursorY.toShort())
        var flags = FLAG_FULL
        if (cursorShown) flags = flags or FLAG_CURSOR
        if (live) flags = flags or FLAG_LIVE
        h.put(flags.toByte())
        h.put(
            when (cursorShape) {
                CursorShape.Bar -> 0
                CursorShape.Block -> 1
                CursorShape.Underline -> 2
                CursorShape.Hollow -> 3
            }.toByte(),
        )
        h.putShort(rows.toShort())
        h.putInt(defaultFg).putInt(defaultBg)
        h.putInt(total.toInt()).putInt(offset.toInt()).putInt(0)
        out.write(h.array())
        val row = ByteBuffer.allocate(2 + cols * CELL_LEN).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until rows) {
            row.clear()
            row.putShort(y.toShort())
            var more = 0
            for (x in 0 until cols) {
                val i = index(x, y)
                row.putInt(cp[i]).putInt(fgWord[i]).putInt(bgWord[i])
                row.put(attrs[i]).put(width[i]).put((extras[i]?.size ?: 0).toByte()).put(0)
                more += extras[i]?.size ?: 0
            }
            out.write(row.array())
            if (more > 0) {
                val e = ByteBuffer.allocate(more * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (x in 0 until cols) extras[index(x, y)]?.forEach { e.putInt(it) }
                out.write(e.array())
            }
        }
        return out.toByteArray()
    }

    companion object {
        /** [bg] for the terminal's default background. */
        const val DEFAULT = -1

        const val HEADER_LEN = 32
        const val CELL_LEN = 16
        const val FLAG_FULL = 1
        const val FLAG_CURSOR = 2
        const val FLAG_LIVE = 4
        const val COLOR_SET = 0x01000000
        const val RGB = 0x00FFFFFF
        const val ATTR_BOLD = 1
        const val ATTR_ITALIC = 1 shl 1
        const val ATTR_FAINT = 1 shl 2
        const val ATTR_UNDERLINE = 1 shl 3
        const val ATTR_INVERSE = 1 shl 4
        const val ATTR_STRIKE = 1 shl 5
        const val ATTR_INVISIBLE = 1 shl 6
        const val WIDTH_WIDE = 1
        const val WIDTH_SPACER = 2
        const val WIDTH_SPACER_HEAD = 3

        /** Far above any real terminal; a frame beyond it is treated as garbage. */
        private const val MAX_SIDE = 4096
        private const val MAX_CELLS = 1 shl 20

        private fun resolve(word: Int, default: Int): Int = if (word and COLOR_SET != 0) word and RGB else default

        private fun appendCodepoint(sb: StringBuilder, c: Int) {
            if (Character.isValidCodePoint(c) && !(c in 0xD800..0xDFFF)) sb.appendCodePoint(c) else sb.append('�')
        }
    }
}
