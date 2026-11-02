package dev.herdr.android.ui.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// TerminalGrid against the frame format (crates/herdr-mobile/src/vt/mod.rs) and
// the terminal-view spec's "Cell-grid fidelity": the frames Rust writes from its
// golden streams must carry, cell by cell, the grid herdr-app's pipeline
// produces (the .grid goldens next to them).
class TerminalGridTest {
    @Test
    fun everyFixtureCellMatchesItsGolden() {
        for (name in FrameFixtures.names) {
            val grid = TerminalGrid()
            assertTrue("$name applies", grid.apply(FrameFixtures.frame(name)) is FrameResult.Applied)
            val want = Golden.parse(FrameFixtures.golden(name))
            assertEquals("$name size", want.cols to want.rows, grid.cols to grid.rows)
            assertEquals("$name default fg", want.fg, grid.defaultFg)
            assertEquals("$name default bg", want.bg, grid.defaultBg)
            val cursor = if (grid.cursorShown) Triple(grid.cursorX, grid.cursorY, grid.cursorShape) else null
            assertEquals("$name cursor", want.cursor, cursor)
            for (y in 0 until want.rows) {
                assertEquals("$name row $y text", want.text[y], grid.goldenRowText(y))
                for (x in 0 until want.cols) {
                    val at = "$name cell $x,$y"
                    val cell = want.cells[y][x]
                    assertEquals("$at fg", cell.fg, grid.fg(x, y))
                    assertEquals("$at bg", cell.bg ?: TerminalGrid.DEFAULT, grid.bg(x, y))
                    assertEquals("$at bold", cell.bold, grid.isBold(x, y))
                    assertEquals("$at italic", cell.italic, grid.isItalic(x, y))
                    assertEquals("$at faint", cell.faint, grid.isFaint(x, y))
                    assertEquals("$at underline", cell.underline, grid.isUnderline(x, y))
                    assertEquals("$at strike", cell.strike, grid.isStrike(x, y))
                    assertEquals("$at wide", cell.wide, grid.isWide(x, y))
                }
            }
        }
    }

    @Test
    fun fixturesCoverWhatTheSpecLists() {
        val grid = TerminalGrid().apply { apply(FrameFixtures.frame("styles-40x12")) }
        // inverse swaps default colours, 256-colour 208, a combining mark stays with its base
        assertEquals(grid.defaultBg, grid.fg(0, 1))
        assertEquals(grid.defaultFg, grid.bg(0, 1))
        assertEquals(0xff8700, grid.fg(0, 3))
        assertTrue(grid.rowText(4).contains("e\u0301"))
        // a wide character and its spacer; an emoji is one cell's text
        assertEquals("日", grid.text(0, 4))
        assertTrue(grid.isWide(0, 4))
        assertTrue(grid.isSpacer(1, 4))
        assertEquals("", grid.text(1, 4))
        assertEquals(CursorShape.Bar, grid.cursorShape)
        // hidden text is not text
        assertEquals("", grid.text(8, 1))
        val alt = TerminalGrid().apply { apply(FrameFixtures.frame("alt-screen-60x16")) }
        assertEquals(CursorShape.Underline, alt.cursorShape)
    }

    @Test
    fun aFullFrameReEncodesToTheSameBytes() {
        for (name in FrameFixtures.names) {
            val bytes = FrameFixtures.frame(name)
            val grid = TerminalGrid().apply { apply(bytes) }
            assertArrayEquals(name, bytes, grid.toFullFrame())
        }
    }

    @Test
    fun aGridSurvivesTheRoundTripAfterPartialFrames() {
        val grid = TerminalGrid()
        grid.apply(frameOf(6, 3, mapOf(0 to cellsOf("abc"), 1 to cellsOf("def"), 2 to cellsOf("ghi"))))
        grid.apply(
            frameOf(
                6, 3, mapOf(1 to listOf(FCell("é", fg = 0x112233, attrs = 1), FCell("👍🏽", width = 1), FCell(width = 2))),
                full = false, cursor = 4 to 2, shape = 0, live = false, total = 90, offset = 10,
            ),
        )
        val copy = TerminalGrid()
        assertTrue(copy.apply(grid.toFullFrame()) is FrameResult.Applied)
        assertArrayEquals(grid.toFullFrame(), copy.toFullFrame())
        assertEquals(listOf("abc", "é👍🏽", "ghi"), (0 until 3).map(copy::rowText))
        assertEquals(0x112233, copy.fg(0, 1))
        assertTrue(copy.isBold(0, 1))
        assertEquals(4 to 2, copy.cursorX to copy.cursorY)
        assertEquals(CursorShape.Bar, copy.cursorShape)
        assertFalse(copy.live)
        assertEquals(90L to 10L, copy.total to copy.offset)
    }

    @Test
    fun aPartialFrameChangesOnlyItsRows() {
        val grid = TerminalGrid()
        grid.apply(frameOf(5, 3, mapOf(0 to cellsOf("one"), 1 to cellsOf("two"), 2 to cellsOf("three"))))
        val result = grid.apply(frameOf(5, 3, mapOf(1 to cellsOf("2"), 2 to cellsOf("3")), full = false))
        assertTrue(result is FrameResult.Applied)
        result as FrameResult.Applied
        assertFalse(result.full)
        assertEquals(listOf(1, 2), result.rows.toList())
        assertEquals("one\n2\n3", grid.text())
    }

    @Test
    fun aFullFrameClearsRowsItDoesNotCarry() {
        val grid = TerminalGrid()
        grid.apply(frameOf(4, 2, mapOf(0 to cellsOf("old"), 1 to cellsOf("old"))))
        grid.apply(frameOf(4, 2, mapOf(0 to cellsOf("new"))))
        assertEquals("new", grid.text())
    }

    @Test
    fun aNewSizeNeedsAFullFrame() {
        val grid = TerminalGrid()
        grid.apply(frameOf(4, 2, mapOf(0 to cellsOf("keep"))))
        val partial = grid.apply(frameOf(8, 2, mapOf(0 to cellsOf("x")), full = false))
        assertTrue(partial is FrameResult.Rejected)
        assertEquals(4 to 2, grid.cols to grid.rows)
        assertEquals("keep", grid.text())
        assertTrue(grid.apply(frameOf(8, 3, mapOf(2 to cellsOf("resized")))) is FrameResult.Applied)
        assertEquals(8 to 3, grid.cols to grid.rows)
        assertEquals("\n\nresized", grid.text())
    }

    @Test
    fun aPartialFrameBeforeAnyFullOneIsRejected() {
        assertTrue(TerminalGrid().apply(frameOf(4, 2, mapOf(0 to cellsOf("x")), full = false)) is FrameResult.Rejected)
    }

    @Test
    fun malformedFramesAreRejectedAndLeaveTheGridAlone() {
        val good = frameOf(4, 2, mapOf(0 to cellsOf("ab"), 1 to listOf(FCell("é"))))
        val grid = TerminalGrid().apply { apply(good) }
        val bad = buildList {
            add(ByteArray(0))
            add(good.copyOf(31))
            // every truncation of the row data, including inside the extra codepoints
            for (n in 32 until good.size) add(good.copyOf(n))
            add(good + byteArrayOf(0))
            // zero columns, a row outside the grid, more rows announced than sent
            add(good.copyOf().also { it[0] = 0; it[1] = 0 })
            add(good.copyOf().also { it[32] = 9 })
            add(good.copyOf().also { it[10] = 3 })
            // a size that would not fit in memory
            add(good.copyOf().also { it[0] = -1; it[1] = -1; it[2] = -1; it[3] = -1 })
        }
        for (frame in bad) {
            assertTrue("${frame.size} bytes", grid.apply(frame) is FrameResult.Rejected)
            assertEquals("ab\né", grid.text())
        }
    }

    @Test
    fun selectionTextSkipsSpacersHiddenTextAndTrailingSpaces() {
        val grid = TerminalGrid()
        grid.apply(
            frameOf(
                10, 3,
                mapOf(
                    0 to listOf(FCell("日", width = 1), FCell(width = 2), FCell("x"), FCell(" "), FCell(" ")),
                    1 to listOf(FCell("p"), FCell("w", attrs = 64), FCell("d")),
                ),
            ),
        )
        assertEquals("日x", grid.rowText(0))
        assertEquals("p d", grid.rowText(1))
        assertEquals("", grid.rowText(2))
        assertEquals("日x\np d", grid.text())
    }

    @Test
    fun outOfRangeCodepointsDoNotCrash() {
        val grid = TerminalGrid()
        val frame = frameOf(3, 1, mapOf(0 to cellsOf("abc")))
        // cell 0's codepoint: a surrogate; cell 1's: beyond Unicode
        frame[34] = 0x00; frame[35] = 0xD8.toByte(); frame[36] = 0; frame[37] = 0
        frame[50] = 0; frame[51] = 0; frame[52] = 0x20; frame[53] = 0x7F
        assertTrue(grid.apply(frame) is FrameResult.Applied)
        assertEquals("��c", grid.rowText(0))
    }

    @Test
    fun anEmptyGridHasNoFrame() {
        val grid = TerminalGrid()
        assertTrue(grid.isEmpty)
        assertNull(grid.toFullFrameOrNull())
    }
}

/** A row as the golden writes it: a blank for an empty cell unless it follows a wide one, trailing blanks cut. */
private fun TerminalGrid.goldenRowText(y: Int): String = buildString {
    for (x in 0 until cols) {
        val t = text(x, y)
        if (t.isNotEmpty()) append(t) else if (!(x > 0 && isWide(x - 1, y))) append(' ')
    }
}.trimEnd(' ')

/** A parsed `.grid` golden (format: `Grid::golden` in crates/herdr-mobile/src/vt/grid.rs). */
data class Golden(
    val cols: Int,
    val rows: Int,
    val fg: Int,
    val bg: Int,
    val cursor: Triple<Int, Int, CursorShape>?,
    val text: List<String>,
    val cells: List<List<Cell>>,
) {
    data class Cell(
        var fg: Int,
        var bg: Int? = null,
        var bold: Boolean = false,
        var italic: Boolean = false,
        var faint: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var wide: Boolean = false,
    )

    companion object {
        fun parse(golden: String): Golden {
            val lines = golden.lines()
            val (cols, rows) = lines[0].removePrefix("grid ").split("x").map(String::toInt)
            val defaults = Regex("default fg=(\\w+) bg=(\\w+)").find(lines[1])!!.groupValues
            val fg = defaults[1].toInt(16)
            val bg = defaults[2].toInt(16)
            val cursor = lines[2].removePrefix("cursor ").takeIf { it != "none" }?.let {
                val (pos, shape) = it.split(" ")
                val (x, y) = pos.split(",").map(String::toInt)
                Triple(x, y, CursorShape.valueOf(shape.replaceFirstChar(Char::uppercase)))
            }
            val text = MutableList(rows) { "" }
            val cells = List(rows) { List(cols) { Cell(fg) } }
            var y = -1
            for (line in lines.drop(3)) {
                if (line.startsWith("row ")) {
                    y = line.substringBefore(":").removePrefix("row ").toInt()
                    text[y] = line.substringAfter(": ")
                } else if (line.startsWith("  ")) {
                    val parts = line.trim().split(" ")
                    val (from, to) = parts[0].split("-").map(String::toInt)
                    for (x in from..to) {
                        val c = cells[y][x]
                        for (p in parts.drop(1)) when {
                            p.startsWith("fg=") -> c.fg = p.removePrefix("fg=").toInt(16)
                            p.startsWith("bg=") -> c.bg = p.removePrefix("bg=").toInt(16)
                            p == "bold" -> c.bold = true
                            p == "italic" -> c.italic = true
                            p == "faint" -> c.faint = true
                            p == "underline" -> c.underline = true
                            p == "strike" -> c.strike = true
                            p == "wide" -> c.wide = true
                            else -> error("unknown style $p")
                        }
                    }
                }
            }
            return Golden(cols, rows, fg, bg, cursor, text, cells)
        }
    }
}
