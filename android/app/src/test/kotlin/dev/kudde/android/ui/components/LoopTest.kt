package dev.kudde.android.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.agents.SPINNER_TAG
import dev.kudde.android.ui.agents.SpinnerRing
import dev.kudde.android.ui.theme.KuddeTheme
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode

// Issue #48: while an agent works, the chat's working line and the agents
// list's spinners looped on every frame. On a Pixel 7 Pro at 60 Hz that kept
// the app near 60% of a core; the spinner's turning layer also counted as a
// layout change, so with any accessibility service on, Compose walked the
// whole screen's semantics tree every frame. The loops now step 12
// times a second, the spinner turns inside its own drawing, and the terminal
// under an open chat is not drawn.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoopTest {
    private val hosts = mutableListOf<ActivityController<ComponentActivity>>()

    /** Ends each test's screen, so no loop keeps running into the next test. */
    @After
    fun closeHosts() {
        for (c in hosts) c.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun host(content: @Composable () -> Unit): ActivityController<ComponentActivity> {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        c.get().setContent { KuddeTheme { content() } }
        hosts += c
        settle(0)
        return c
    }

    /** Runs the main looper (and the frames it posts) for a little while. */
    private fun settle(ms: Long) {
        repeat((ms / 16).toInt().coerceAtLeast(1)) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    /** A step: the frame time it drew at and the place it drew. */
    private data class Step(val atMs: Long, val place: Float)

    /**
     * Runs [stepLoop] for one second of made-up time on a display that
     * refreshes [hz] times a second, the loop starting [fromMs] in: each frame
     * lands on the next refresh after the loop asks for it.
     */
    private fun stepsInASecond(hz: Double, periodMs: Int = 900, fromMs: Double = 0.0): List<Step> {
        val refresh = 1_000.0 / hz
        var now = fromMs
        val steps = mutableListOf<Step>()
        class Done : RuntimeException()
        try {
            runBlocking {
                stepLoop(
                    periodMs,
                    frame = { onFrame ->
                        now = kotlin.math.ceil(now / refresh + 1e-9) * refresh
                        if (now > fromMs + 1_000.0) throw Done()
                        onFrame(now.toLong())
                        steps += Step(now.toLong(), Float.NaN)
                    },
                    wait = { ms -> now += ms },
                ) { steps += Step(-1, it) }
            }
        } catch (_: Done) {
        }
        // Each frame adds its place, then its time.
        return steps.chunked(2).filter { it.size == 2 }.map { (p, t) -> Step(t.atMs, p.place) }
    }

    private fun placesInASecond(hz: Double, periodMs: Int = 900): List<Float> =
        stepsInASecond(hz, periodMs).map { it.place }

    @Test
    fun aLoopStepsLoopFpsTimesASecondAt60Hz() {
        val places = placesInASecond(60.0)
        assertTrue("stepped ${places.size} times in a second", places.size in LOOP_FPS - 1..LOOP_FPS + 1)
        assertTrue(places.all { it in 0f..1f })
    }

    @Test
    fun aLoopStaysNearLoopFpsAtFasterRefreshRates() {
        for (hz in listOf(90.0, 120.0)) {
            val places = placesInASecond(hz)
            assertTrue("$hz Hz: ${places.size} steps", places.size in LOOP_FPS - 2..LOOP_FPS + 3)
        }
    }

    @Test
    fun aLoopStillGoesRoundOncePerPeriod() {
        // Steps are 1/LOOP_FPS s apart, so a 900 ms loop passes its start once
        // in the second: it turns at the same speed as before, in fewer steps.
        val places = placesInASecond(60.0, periodMs = 900)
        val wraps = places.zipWithNext().count { (a, b) -> b < a }
        assertEquals(1, wraps)
        val step = 1_000f / LOOP_FPS / 900
        assertTrue(places.zipWithNext().drop(1).all { (a, b) -> b < a || b - a in step * 0.75f..step * 1.25f })
    }

    @Test
    fun loopsStepInTheSameFrames() {
        // Each frame redraws the whole window: two loops (the sheep and a
        // shimmer) stepping out of turn would cost twice the frames.
        for (hz in listOf(60.0, 120.0)) {
            val a = stepsInASecond(hz, periodMs = 2_100, fromMs = 0.0).map { it.atMs }.filter { it > 200 }
            val b = stepsInASecond(hz, periodMs = 1_600, fromMs = 37.0).map { it.atMs }.filter { it > 200 && it <= a.last() }
            assertEquals("$hz Hz", a, b)
        }
    }

    @Test
    fun aStillLoopHoldsItsPlace() {
        val seen = mutableListOf<Float>()
        host {
            val t = rememberLoop(animate = false, periodMs = 900, still = 0.25f)
            LaunchedEffect(Unit) { snapshotFlow { t.value }.collect { seen += it } }
        }
        settle(500)
        assertEquals(listOf(0.25f), seen)
    }

    private fun spinnerBounds(c: ActivityController<ComponentActivity>): Rect {
        val composeView = (c.get().window.decorView.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup
        val root = composeView.getChildAt(0) as ViewRootForTest
        fun find(node: SemanticsNode): SemanticsNode? =
            if (node.config.getOrNull(SemanticsProperties.TestTag) == SPINNER_TAG) node
            else node.children.firstNotNullOfOrNull(::find)
        return find(root.semanticsOwner.unmergedRootSemanticsNode)!!.boundsInRoot
    }

    @Test
    fun aTurningSpinnerKeepsItsBounds() {
        // A layer rotation shows up in the bounds (and to accessibility as a
        // layout change); a turn drawn inside the spinner does not.
        val turned = host { SpinnerRing(turn = { 0.125f }) }
        val still = host { SpinnerRing(turn = { 0f }) }
        val bounds = spinnerBounds(turned)
        assertEquals("an eighth of a turn", spinnerBounds(still).size, bounds.size)
        assertEquals(bounds.width, bounds.height)
    }

    @Test
    fun aCoveredScreenIsNotDrawn() {
        var draws = 0
        val covered = mutableStateOf(true)
        val c = host {
            Box(Modifier.undrawnWhen(covered.value)) {
                Box(Modifier.size(10.dp).drawBehind { draws++ })
            }
        }
        fun draw() {
            settle(32)
            val view = c.get().window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
        }
        draw()
        assertEquals("drawn while covered", 0, draws)
        covered.value = false
        draw()
        assertTrue("drawn once uncovered", draws > 0)
    }
}
