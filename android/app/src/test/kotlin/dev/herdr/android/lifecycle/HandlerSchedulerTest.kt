package dev.herdr.android.lifecycle

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// The in-process half of the 60 s suspension: a main-thread Handler posted for
// the time left until the elapsed-realtime deadline.
@RunWith(RobolectricTestRunner::class)
class HandlerSchedulerTest {
    private val looper = shadowOf(Looper.getMainLooper())
    private val scheduler = HandlerScheduler(Handler(Looper.getMainLooper())) { 1_000L }

    @Test
    fun runsOnTheMainThreadWhenTheDeadlineIsReached() {
        var runs = 0
        scheduler.scheduleAt(61_000L) { runs++ }
        looper.idleFor(59_999, TimeUnit.MILLISECONDS)
        assertEquals(0, runs)
        looper.idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(1, runs)
        looper.idleFor(10, TimeUnit.MINUTES)
        assertEquals(1, runs)
    }

    @Test
    fun aCancelledTaskNeverRuns() {
        var runs = 0
        scheduler.scheduleAt(61_000L) { runs++ }.cancel()
        looper.idleFor(10, TimeUnit.MINUTES)
        assertEquals(0, runs)
    }

    @Test
    fun aDeadlineAlreadyPassedRunsAtOnce() {
        var runs = 0
        scheduler.scheduleAt(500L) { runs++ }
        looper.idle()
        assertEquals(1, runs)
    }
}
