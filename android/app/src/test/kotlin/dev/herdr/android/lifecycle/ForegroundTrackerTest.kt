package dev.herdr.android.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test

// machine-connection spec, "Connections end in the background": the app closes
// its connections 60 s after it leaves the foreground. The tracker counts
// started activities; a fake device stands in for elapsedRealtime, a main-thread
// Handler timer (runs on uptime, which stops in deep sleep) and the inexact
// AlarmManager alarm (elapsed realtime, may be deferred). Whichever of the two
// fires first suspends; the other is cancelled.
class ForegroundTrackerTest {
    private val clock = FakeDevice()
    private var resumes = 0
    private var suspends = 0
    private var foregrounds = 0
    private val events = mutableListOf<String>()
    private val tracker = ForegroundTracker(
        clock.timer,
        clock.alarm,
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
    fun theInProcessTimerSuspendsAtSixtySecondsAndCancelsTheAlarm() {
        // Android 12+ may hold an inexact alarm back long after its time even
        // while the process is awake; the in-process timer does not wait for it.
        clock.alarmsDeferred = true
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(59_999)
        assertEquals(0, suspends)
        clock.advance(1)
        assertEquals(1, suspends)
        assertEquals(0, clock.alarm.pending)
        assertEquals(0, clock.timer.pending)
        clock.deliverDeferredAlarms()
        clock.advance(10 * 60_000)
        assertEquals(1, suspends)
    }

    @Test
    fun theAlarmFiringFirstSuspendsOnceAndCancelsTheTimer() {
        tracker.activityStarted()
        tracker.activityStopped()
        // Deep sleep: uptime stands still, so the Handler timer cannot fire;
        // the wakeup alarm can.
        clock.sleep(60_000)
        clock.deliverDeferredAlarms()
        assertEquals(1, suspends)
        assertEquals(0, clock.timer.pending)
        assertEquals(0, clock.alarm.pending)
        clock.advance(10 * 60_000)
        assertEquals(1, suspends)
        assertEquals(listOf("resume", "suspend"), events)
    }

    @Test
    fun aReturnBeforeTheDeadlineCancelsBothTimerAndAlarm() {
        tracker.activityStarted()
        tracker.activityStopped()
        assertEquals(1, clock.timer.pending)
        assertEquals(1, clock.alarm.pending)
        clock.advance(30_000)
        tracker.activityStarted()
        assertEquals(0, clock.timer.pending)
        assertEquals(0, clock.alarm.pending)
        clock.advance(10 * 60_000)
        clock.deliverDeferredAlarms()
        assertEquals(0, suspends)
    }

    @Test
    fun aStaleDeliveryAfterACancelNeverSuspendsTheFreshConnections() {
        // A cancel can lose the race with a delivery already queued on the
        // main thread; such a late callback must do nothing.
        clock.alarmsDeferred = true
        clock.alarm.cancelIgnored = true
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(60_000)
        assertEquals(1, suspends)
        tracker.activityStarted()
        clock.deliverDeferredAlarms()
        assertEquals(1, suspends)
        assertEquals(listOf("resume", "suspend", "resume"), events)
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
 * A virtual device with uptime and elapsed realtime. [timer] runs tasks on
 * uptime, as a main-thread Handler (its delay is fixed when posted); [alarm]
 * runs them on elapsed realtime, as an inexact AlarmManager alarm.
 * [advance] moves both clocks and delivers what falls due, in time order,
 * except alarms while [alarmsDeferred]; [sleep] moves only elapsed realtime and
 * delivers nothing (deep sleep); [deliverDeferredAlarms] delivers every alarm
 * already due (a held-back alarm finally running).
 */
class FakeDevice : ElapsedClock {
    var uptime = 0L
        private set
    private var elapsed = 0L
    var alarmsDeferred = false

    inner class Queue(private val onUptime: Boolean) : Scheduler {
        val entries = mutableListOf<Entry>()
        val pending: Int get() = entries.size

        /** The cancel is lost, as one racing a delivery already queued. */
        var cancelIgnored = false

        inner class Entry(val due: Long, val task: () -> Unit) : Cancellable {
            override fun cancel() {
                if (!cancelIgnored) entries.remove(this)
            }
        }

        override fun scheduleAt(elapsedRealtimeMs: Long, task: () -> Unit): Cancellable {
            val due = if (onUptime) uptime + maxOf(0L, elapsedRealtimeMs - elapsed) else elapsedRealtimeMs
            return Entry(due, task).also { entries += it }
        }

        /** Ms from now until the next entry is due on this queue's clock. */
        fun nextIn(): Long? = entries.minOfOrNull { it.due - if (onUptime) uptime else elapsed }

        fun runNext() {
            val now = if (onUptime) uptime else elapsed
            val next = entries.filter { it.due <= now }.minByOrNull { it.due } ?: return
            entries.remove(next)
            next.task()
        }
    }

    val timer = Queue(onUptime = true)
    val alarm = Queue(onUptime = false)

    override fun elapsedRealtime(): Long = elapsed

    fun sleep(ms: Long) {
        elapsed += ms
    }

    fun advance(ms: Long) {
        var left = ms
        while (true) {
            val t = timer.nextIn()
            val a = if (alarmsDeferred) null else alarm.nextIn()
            val step = listOfNotNull(t, a).minOrNull()?.coerceAtLeast(0) ?: break
            if (step > left) break
            uptime += step
            elapsed += step
            left -= step
            if (t != null && t <= step) timer.runNext() else alarm.runNext()
        }
        uptime += left
        elapsed += left
    }

    fun deliverDeferredAlarms() {
        while (alarm.entries.any { it.due <= elapsed }) alarm.runNext()
    }
}
