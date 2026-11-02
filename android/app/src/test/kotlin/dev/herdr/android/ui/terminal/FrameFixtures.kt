package dev.herdr.android.ui.terminal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Frames for the Kotlin tests: the fixtures Rust writes from its golden
// streams (src/test/resources/frames, kept equal to the first frames by
// crates/herdr-mobile/src/vt/grid.rs), and a small builder for hand-made frames
// in the same format (crates/herdr-mobile/src/vt/mod.rs).

object FrameFixtures {
    val names = listOf("styles-40x12", "alt-screen-60x16", "session-80x24")

    fun frame(name: String): ByteArray = resource("$name.frame").readBytes()

    fun golden(name: String): String = resource("$name.grid").readBytes().toString(Charsets.UTF_8)

    private fun resource(file: String) =
        requireNotNull(FrameFixtures::class.java.getResourceAsStream("/frames/$file")) { "missing fixture $file" }
}

/** One cell for [frameOf]: text is the whole grapheme ("" for none). */
data class FCell(
    val text: String = "",
    val fg: Int? = null,
    val bg: Int? = null,
    val attrs: Int = 0,
    val width: Int = 0,
)

/**
 * A frame in the wire format. [rows] maps y to that row's cells (padded with
 * blanks to [cols]); [full] sets the full-frame flag.
 */
fun frameOf(
    cols: Int,
    rows: Int,
    content: Map<Int, List<FCell>>,
    full: Boolean = true,
    cursor: Pair<Int, Int> = 0 to 0,
    cursorShown: Boolean = true,
    shape: Int = 1,
    live: Boolean = true,
    fg: Int = 0xd2d4d9,
    bg: Int = 0x17191d,
    total: Int = rows,
    offset: Int = 0,
): ByteArray {
    val out = ByteArrayOutputStream()
    val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
    header.putShort(cols.toShort()).putShort(rows.toShort())
    header.putShort(cursor.first.toShort()).putShort(cursor.second.toShort())
    var flags = 0
    if (full) flags = flags or 1
    if (cursorShown) flags = flags or 2
    if (live) flags = flags or 4
    header.put(flags.toByte()).put(shape.toByte())
    header.putShort(content.size.toShort())
    header.putInt(fg).putInt(bg).putInt(total).putInt(offset).putInt(0)
    out.write(header.array())
    for ((y, cells) in content.toSortedMap()) {
        val row = ByteBuffer.allocate(2 + cols * 16).order(ByteOrder.LITTLE_ENDIAN)
        row.putShort(y.toShort())
        val extras = ByteBuffer.allocate(4 * 256 * cols).order(ByteOrder.LITTLE_ENDIAN)
        for (x in 0 until cols) {
            val cell = cells.getOrNull(x) ?: FCell()
            val cps = cell.text.codePoints().toArray()
            row.putInt(cps.firstOrNull() ?: 0)
            row.putInt(cell.fg?.let { it or 0x01000000 } ?: 0)
            row.putInt(cell.bg?.let { it or 0x01000000 } ?: 0)
            row.put(cell.attrs.toByte()).put(cell.width.toByte()).put((cps.size - 1).coerceAtLeast(0).toByte()).put(0)
            for (i in 1 until cps.size) extras.putInt(cps[i])
        }
        out.write(row.array())
        out.write(extras.array(), 0, extras.position())
    }
    return out.toByteArray()
}

/** Plain text in one style, a cell per character. */
fun cellsOf(text: String, fg: Int? = null, bg: Int? = null, attrs: Int = 0): List<FCell> =
    text.map { FCell(it.toString(), fg, bg, attrs) }
