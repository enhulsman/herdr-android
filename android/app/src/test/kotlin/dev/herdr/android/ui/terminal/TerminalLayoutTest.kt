package dev.herdr.android.ui.terminal

import dev.herdr.android.ui.agents.AgentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The terminal's layout math (design-tokens.json layout.terminalFit,
// layout.terminalPadding, typography.terminalPhone/terminalWatch), on the
// reference phone: 412 dp wide at density 2, so 824 px.
class TerminalLayoutTest {
    private val density = 2f
    private val padX = 10f * density
    private val padTop = 6f * density
    private val phoneFont = 12.2f * density

    @Test
    fun thePhoneGridIsAbout53ColumnsOn412dp() {
        // Render 4: the area between the queue strip and the keys row with the keyboard up.
        val grid = phoneGrid(824f, 31 * phoneFont * 1.25f + padTop + 5f, padX, padTop, phoneFont)
        assertEquals(GridSize(53, 31), grid)
    }

    @Test
    fun thePhoneGridFollowsTheVisibleHeight() {
        val shown = phoneGrid(824f, 960f, padX, padTop, phoneFont)
        val hidden = phoneGrid(824f, 1400f, padX, padTop, phoneFont)
        assertEquals(53, shown.cols)
        assertEquals(53, hidden.cols)
        assertEquals(((960f - padTop) / (phoneFont * 1.25f)).toInt(), shown.rows)
        assertEquals(((1400f - padTop) / (phoneFont * 1.25f)).toInt(), hidden.rows)
    }

    @Test
    fun aTinyAreaStillHasOneCell() {
        assertEquals(GridSize(1, 1), phoneGrid(10f, 5f, padX, padTop, phoneFont))
    }

    @Test
    fun watchingFitsTheDeskGridToTheWidthInsideThePadding() {
        // Render 3: 96 columns on 412 dp is 6.8 sp.
        val layout = WatchLayout(824f, 1500f, padX, padTop, cols = 96, rows = 44, maxFontPx = phoneFont)
        assertEquals(6.8f * density, layout.fitFontPx, 0.02f)
        assertEquals(phoneFont / layout.fitFontPx, layout.maxZoom, 0.001f)
        // The grid's height at fit: top padding and 44 rows of 1.25 em.
        assertEquals(padTop + 44 * layout.fitFontPx * 1.25f, layout.contentHeight(1f), 0.01f)
    }

    @Test
    fun aNarrowDeskGridIsNotZoomedBeyondFit() {
        val layout = WatchLayout(824f, 1500f, padX, padTop, cols = 40, rows = 10, maxFontPx = phoneFont)
        assertEquals(1f, layout.maxZoom, 0f)
        assertEquals(WatchView(1f, 0f, 0f), layout.zoomBy(WatchView.Fit, 3f, 400f, 300f))
    }

    @Test
    fun pinchZoomsAroundTheFingersAndBackToFit() {
        val layout = WatchLayout(824f, 1500f, padX, padTop, cols = 180, rows = 50, maxFontPx = phoneFont)
        val zoomed = layout.zoomBy(WatchView.Fit, 2f, 412f, 100f)
        assertEquals(2f, zoomed.zoom, 0.001f)
        // The point under the fingers stays put.
        assertEquals(412f, (412f - zoomed.panX) / 2f, 0.01f)
        assertEquals(-412f, zoomed.panX, 0.01f)
        // Never past the readable size; pinching back returns to fit-to-width.
        assertEquals(layout.maxZoom, layout.zoomBy(zoomed, 100f, 0f, 0f).zoom, 0.001f)
        assertEquals(WatchView.Fit, layout.zoomBy(zoomed, 0.01f, 300f, 300f))
    }

    @Test
    fun aDragPansWhileZoomedAndStopsAtTheEdges() {
        val layout = WatchLayout(824f, 1000f, padX, padTop, cols = 180, rows = 100, maxFontPx = phoneFont)
        val zoomed = layout.zoomBy(WatchView.Fit, 2f, 0f, 0f)
        val (panned, rest) = layout.panBy(zoomed, -300f, -200f)
        assertEquals(WatchView(2f, -300f, -200f), panned)
        assertEquals(0f, rest, 0f)
        // Past the right edge: the grid's right side meets the area's.
        val (edge, _) = layout.panBy(panned, -10_000f, 0f)
        assertEquals(824f - 824f * 2f, edge.panX, 0.01f)
        // Down past the top: what is left over scrolls the scrollback.
        val (top, over) = layout.panBy(panned, 0f, 500f)
        assertEquals(0f, top.panY, 0f)
        assertEquals(300f, over, 0.01f)
    }

    @Test
    fun atFitAVerticalDragIsLeftForScrolling() {
        val layout = WatchLayout(824f, 1500f, padX, padTop, cols = 96, rows = 44, maxFontPx = phoneFont)
        val (view, rest) = layout.panBy(WatchView.Fit, 30f, -120f)
        assertEquals(WatchView.Fit, view)
        assertEquals(-120f, rest, 0f)
    }

    @Test
    fun aRememberedZoomIsClampedToTheNewSize() {
        val layout = WatchLayout(824f, 1000f, padX, padTop, cols = 100, rows = 40, maxFontPx = phoneFont)
        val view = layout.clamp(WatchView(5f, 900f, -99_999f))
        assertEquals(layout.maxZoom, view.zoom, 0.001f)
        assertEquals(0f, view.panX, 0f)
        assertEquals(1000f - layout.contentHeight(view.zoom), view.panY, 0.01f)
    }

    @Test
    fun zoomIsRememberedPerTerminal() {
        val memory = ZoomMemory()
        val a = AgentKey("workstation", "t1")
        val b = AgentKey("server", "t1")
        assertNull(memory[a])
        memory[a] = 2.5f
        assertEquals(2.5f, memory[a]!!, 0f)
        assertNull(memory[b])
    }

    @Test
    fun flicksBecomeWholeRowsUpIsNegative() {
        val scroll = RowScroller(cellHeightPx = 20f)
        // Finger down: back in history.
        assertEquals(0, scroll.drag(15f))
        assertEquals(-1, scroll.drag(10f))
        assertEquals(-3, scroll.drag(60f))
        // Finger up: towards live.
        assertEquals(2, scroll.drag(-45f))
        scroll.reset()
        assertEquals(0, scroll.drag(-19f))
    }

    @Test
    fun thePhoneSizeIsSentOnceTheKeyboardSettles() {
        val sent = mutableListOf<GridSize>()
        val settle = SizeSettler(settleMs = 250) { sent += it }
        // The keyboard slides in: a new height every frame.
        settle.offer(GridSize(53, 45), 0)
        settle.offer(GridSize(53, 40), 16)
        settle.offer(GridSize(53, 33), 32)
        settle.offer(GridSize(53, 31), 48)
        settle.tick(200)
        assertEquals(emptyList<GridSize>(), sent)
        settle.tick(298)
        assertEquals(listOf(GridSize(53, 31)), sent)
        // The same size again sends nothing; hiding it sends the full height once.
        settle.offer(GridSize(53, 31), 1000)
        settle.tick(1300)
        settle.offer(GridSize(53, 45), 2000)
        settle.tick(2100)
        settle.tick(2250)
        settle.tick(2400)
        assertEquals(listOf(GridSize(53, 31), GridSize(53, 45)), sent)
    }

    @Test
    fun theFirstSizeIsTheOneTheTerminalOpenedWith() {
        val sent = mutableListOf<GridSize>()
        val settle = SizeSettler(settleMs = 250, opened = GridSize(53, 45)) { sent += it }
        settle.offer(GridSize(53, 45), 0)
        settle.tick(500)
        assertEquals(emptyList<GridSize>(), sent)
    }
}
