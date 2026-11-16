package dev.herdr.android.ui.components

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

// The chat's clock (spec task 5.6: the desk and battery cost of an open chat):
// it ticks only while something waits and the screen is started. With the
// screen off, the open chat of a working agent kept its main thread at about
// 18% of a core through the clock and the working line's loops.
@RunWith(RobolectricTestRunner::class)
class UptimeTickerTest {
    private val hosts = mutableListOf<ActivityController<ComponentActivity>>()

    /** Ends each test's screen, so no clock keeps ticking into the next test. */
    @After
    fun closeHosts() {
        for (c in hosts) c.pause().stop().destroy()
        settle(0)
    }

    private class Probe {
        val seen = mutableListOf<Long>()
        var started = true
    }

    private fun host(running: Boolean, probe: Probe): ActivityController<ComponentActivity> {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        c.get().setContent {
            val now = rememberUptimeTicker(running, everyMs = 50)
            val started = rememberStarted()
            SideEffect { probe.started = started }
            LaunchedEffect(Unit) { snapshotFlow { now.value }.collect { probe.seen += it } }
        }
        hosts += c
        settle(0)
        return c
    }

    /** Lets [ms] of real time pass (the delays run on a real clock), running the main looper as it goes. */
    private fun settle(ms: Long) {
        val until = System.currentTimeMillis() + ms
        do {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        } while (System.currentTimeMillis() < until)
    }

    @Test
    fun ticksWhileSomethingWaitsAndTheScreenIsStarted() {
        val probe = Probe()
        host(running = true, probe)
        val before = probe.seen.size
        settle(400)
        assertTrue("ticked ${probe.seen.size - before} times in 400 ms", probe.seen.size - before >= 3)
        assertTrue(probe.started)
    }

    @Test
    fun holdsStillWhileTheScreenIsStopped() {
        val probe = Probe()
        val c = host(running = true, probe)
        settle(150)
        c.pause().stop()
        settle(100)
        assertFalse("the screen reads as stopped", probe.started)
        val held = probe.seen.size
        settle(400)
        assertEquals("no tick with the screen off", held, probe.seen.size)
        c.start().resume()
        settle(300)
        assertTrue("ticks again once the screen is back", probe.seen.size > held)
        assertTrue(probe.started)
    }

    @Test
    fun holdsStillWhileNothingWaits() {
        val probe = Probe()
        host(running = false, probe)
        val held = probe.seen.size
        settle(400)
        assertEquals(held, probe.seen.size)
    }
}
