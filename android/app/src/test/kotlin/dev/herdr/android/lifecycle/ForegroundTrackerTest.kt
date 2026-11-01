package dev.herdr.android.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test

// machine-connection spec, "Connections end in the background": the app closes
// its connections 60 s after it leaves the foreground. The tracker counts
// started activities; a fake clock and alarm stand in for elapsedRealtime and
// AlarmManager (uptime stops in deep sleep, elapsed realtime does not).
class ForegroundTrackerTest {
    private val clock = FakeScheduler()
    private var resumes = 0
    private var suspends = 0
    private var foregrounds = 0
    private val events = mutableListOf<String>()
    private val tracker = ForegroundTracker(
        clock,
        clock,
        onResume = { resumes++; events += "resume" },
        onSuspend = { suspends++; events += "suspend" },
        onForeground = { foregrounds++ },
    )

    @Test
    fun theFirstStartResumesTheHubWhichStartsPaused() {
        tracker.activityStarted()
        assertEquals(1, resumes)
        assertEquals(0, suspends)
    }

    @Test
    fun sixtySecondsWithNoStartedActivitySuspendsOnce() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(59_999)
        assertEquals(0, suspends)
        clock.advance(1)
        assertEquals(1, suspends)
        clock.advance(10 * 60_000)
        assertEquals(1, suspends)
    }

    @Test
    fun aStartAtFiftyNineSecondsCancelsTheSuspend() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(59_000)
        tracker.activityStarted()
        clock.advance(10 * 60_000)
        assertEquals(0, suspends)
        // Never suspended, so nothing to resume beyond the first start.
        assertEquals(1, resumes)
    }

    @Test
    fun aStartAfterTheSuspendResumesOnce() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(60_000)
        assertEquals(1, suspends)
        tracker.activityStarted()
        assertEquals(2, resumes)
        clock.advance(10 * 60_000)
        assertEquals(2, resumes)
        assertEquals(1, suspends)
    }

    @Test
    fun aRotationNeverSuspends() {
        tracker.activityStarted()
        // A configuration change stops the old activity and starts the new one.
        repeat(5) {
            tracker.activityStopped()
            tracker.activityStarted()
            clock.advance(30_000)
        }
        clock.advance(10 * 60_000)
        assertEquals(0, suspends)
        assertEquals(1, resumes)
    }

    @Test
    fun twoOverlappingActivitiesSuspendOnlyWhenBothAreStopped() {
        tracker.activityStarted() // A
        tracker.activityStarted() // B on top
        tracker.activityStopped() // A goes behind B
        clock.advance(10 * 60_000)
        assertEquals(0, suspends)
        tracker.activityStopped() // B
        clock.advance(60_000)
        assertEquals(1, suspends)
        assertEquals(1, resumes)
    }

    @Test
    fun anUnbalancedStopDoesNotDriveTheCountBelowZero() {
        tracker.activityStopped()
        tracker.activityStarted()
        clock.advance(10 * 60_000)
        assertEquals(0, suspends)
        assertEquals(1, resumes)
    }

    @Test
    fun aReturnAfterSleepingPastTheDeadlineSuspendsThenResumes() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(10_000)
        // Pocketed: the CPU sleeps, the inexact alarm is deferred, five
        // minutes pass in real time and none in uptime.
        clock.sleep(5 * 60_000)
        assertEquals(10_000, clock.uptime)
        assertEquals(0, suspends)
        tracker.activityStarted()
        assertEquals(listOf("resume", "suspend", "resume"), events)
        // The deferred alarm was cancelled: it never suspends the fresh connections.
        clock.advance(10 * 60_000)
        assertEquals(1, suspends)
        assertEquals(2, resumes)
    }

    @Test
    fun theAlarmAtTheDeadlineSuspendsOnce() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.sleep(30_000)
        clock.advance(30_000)
        assertEquals(1, suspends)
        tracker.activityStarted()
        assertEquals(listOf("resume", "suspend", "resume"), events)
    }

    @Test
    fun aReturnBeforeTheDeadlineInRealTimeCancelsTheSuspend() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.sleep(59_000)
        tracker.activityStarted()
        clock.advance(10 * 60_000)
        assertEquals(0, suspends)
        assertEquals(listOf("resume"), events)
    }

    @Test
    fun everyReturnToTheForegroundIsReported() {
        // HubHost uses it to start a hub that failed to start, without
        // touching a healthy one.
        tracker.activityStarted()
        tracker.activityStarted()
        tracker.activityStopped()
        tracker.activityStopped()
        clock.advance(20_000)
        tracker.activityStarted()
        assertEquals(2, foregrounds)
    }

    @Test
    fun isForegroundFollowsTheCount() {
        assertEquals(false, tracker.isForeground)
        tracker.activityStarted()
        assertEquals(true, tracker.isForeground)
        tracker.activityStopped()
        assertEquals(false, tracker.isForeground)
    }
}

/**
 * A virtual clock with uptime and elapsed realtime, and alarms on elapsed
 * realtime. [advance] moves both and delivers due alarms; [sleep] moves only
 * elapsed realtime and delivers nothing, as a deferred inexact alarm in doze.
 */
class FakeScheduler : Scheduler, ElapsedClock {
    var uptime = 0L
        private set
    private var elapsed = 0L
    private val pending = mutableListOf<Entry>()

    private inner class Entry(val due: Long, val task: () -> Unit) : Cancellable {
        override fun cancel() {
            pending.remove(this)
        }
    }

    override fun elapsedRealtime(): Long = elapsed

    override fun scheduleAt(elapsedRealtimeMs: Long, task: () -> Unit): Cancellable =
        Entry(elapsedRealtimeMs, task).also { pending += it }

    fun sleep(ms: Long) {
        elapsed += ms
    }

    fun advance(ms: Long) {
        val until = elapsed + ms
        uptime += ms
        while (true) {
            val next = pending.filter { it.due <= until }.minByOrNull { it.due } ?: break
            pending.remove(next)
            elapsed = maxOf(elapsed, next.due)
            next.task()
        }
        elapsed = until
    }
}
