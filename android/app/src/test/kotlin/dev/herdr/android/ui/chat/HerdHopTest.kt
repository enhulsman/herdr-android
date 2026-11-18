package dev.herdr.android.ui.chat

import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.herdr.android.testing.HerdrTestRunner
import dev.herdr.android.ui.theme.HerdrTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

// Issue #48: the sheep of the working line hop on a surface of their own while
// they move, drawn off the main thread at the display's rate, so the rest of
// the window is not redrawn for them. Held still (reduced motion, a stopped
// screen, screenshots) they are drawn with the screen as before.
@RunWith(HerdrTestRunner::class)
class HerdHopTest {
    private val hosts = mutableListOf<ActivityController<ComponentActivity>>()

    @After
    fun closeHosts() {
        for (c in hosts) c.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun surfaces(animate: Boolean): Int {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        hosts += c
        c.get().setContent { HerdrTheme { WorkingLine(now = { 0L }, animate = animate) } }
        shadowOf(Looper.getMainLooper()).idle()
        fun count(v: View): Int =
            (if (v is SurfaceView) 1 else 0) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).sumOf { count(g.getChildAt(it)) } } ?: 0)
        return count(c.get().window.decorView)
    }

    @Test
    fun movingSheepHopOnASurfaceOfTheirOwn() = assertEquals(1, surfaces(animate = true))

    @Test
    fun stillSheepAreDrawnWithTheScreen() = assertEquals(0, surfaces(animate = false))

    private val width = 140f
    private val r = 9f
    private val dp = 3.5f

    @Test
    fun aSheepHopsOnlyAtTheFence() {
        // Sheep k = 0 crosses the middle (the fence) at t = 0.5.
        val atFence = sheepAt(0.5f, 0, width, r, dp)
        assertEquals(width / 2, atFence.x, 0.5f)
        assertTrue("lifted over the fence: ${atFence.lift}", atFence.lift > 10 * dp)
        assertEquals(0f, sheepAt(0.2f, 0, width, r, dp).lift, 0f)
        assertEquals(0f, sheepAt(0.8f, 0, width, r, dp).lift, 0f)
    }

    @Test
    fun sheepFadeAtTheEdgesAndAreAThirdApart() {
        assertEquals(0f, sheepAt(0f, 0, width, r, dp).alpha, 0f)
        assertEquals(1f, sheepAt(0.5f, 0, width, r, dp).alpha, 0f)
        val a = sheepAt(0.1f, 0, width, r, dp).x
        val b = sheepAt(0.1f, 1, width, r, dp).x
        assertEquals((width - 2 * r) / 3, b - a, 0.5f)
    }

    @Test
    fun theSheepDrawAboutThirtyTimesASecond() {
        // A 60 Hz display: every other refresh; at 120 Hz every fourth.
        for ((hz, want) in listOf(60 to 30, 120 to 30, 90 to 30)) {
            var drawn = -1L
            var count = 0
            for (i in 0 until hz) {
                val nanos = 5_000_000_000L + i * 1_000_000_000L / hz
                if (herdDue(nanos, drawn)) {
                    drawn = nanos
                    count++
                }
            }
            assertTrue("$hz Hz: $count draws", count in want - 1..want + 1)
        }
    }

    @Test
    fun theFirstFrameAlwaysDraws() = assertTrue(herdDue(1L, -1L))
}
