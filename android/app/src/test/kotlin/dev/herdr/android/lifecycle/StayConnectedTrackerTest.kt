package dev.herdr.android.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test

// stay-connected spec, "While on, connections stay up in the background" and
// "An open terminal detaches after 60 s in the background" (design D2): with
// the option on, the 60 s deadline means "background idle", never a suspend;
// a return after deep sleep past the deadline does nothing (the person is
// back); the option is read when the deadline fires. Off keeps V1
// (ForegroundTrackerTest).
class StayConnectedTrackerTest {
    private val clock = FakeDevice()
    private var on = true
    private val events = mutableListOf<String>()
    private val tracker = ForegroundTracker(
        clock.timer,
        clock.alarm,
        clock,
        onResume = { events += "resume" },
        onSuspend = { events += "suspend" },
        onForeground = { events += "foreground" },
        keepConnected = { on },
        onBackgroundIdle = { events += "idle" },
    )

    @Test
    fun sixtySecondsInTheBackgroundIsIdleNotSuspend() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(59_999)
        assertEquals(listOf("resume", "foreground"), events)
        clock.advance(1)
        assertEquals(listOf("resume", "foreground", "idle"), events)
        clock.advance(10 * 60_000)
        assertEquals(listOf("resume", "foreground", "idle"), events)
    }

    @Test
    fun aReturnAfterIdleDoesNotResumeAHubThatNeverSuspended() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(5 * 60_000)
        events.clear()
        tracker.activityStarted()
        assertEquals(listOf("foreground"), events)
    }

    @Test
    fun aReturnBeforeSixtySecondsIsNeitherIdleNorSuspend() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(20_000)
        tracker.activityStarted()
        clock.advance(10 * 60_000)
        assertEquals(listOf("resume", "foreground", "foreground"), events)
    }

    @Test
    fun aReturnAfterDeepSleepPastTheDeadlineDoesNothingAndCancelsTheAlarm() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(10_000)
        clock.sleep(5 * 60_000)
        events.clear()
        tracker.activityStarted()
        assertEquals(listOf("foreground"), events)
        // The deferred alarm was cancelled: no idle lands while the person is back.
        clock.deliverDeferredAlarms()
        clock.advance(10 * 60_000)
        assertEquals(listOf("foreground"), events)
    }

    @Test
    fun theAlarmFiringInDeepSleepIsIdleOnce() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.sleep(60_000)
        clock.deliverDeferredAlarms()
        clock.advance(10 * 60_000)
        assertEquals(listOf("resume", "foreground", "idle"), events)
    }

    @Test
    fun everyBackgroundStayIsIdleOnce() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(60_000)
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(60_000)
        assertEquals(listOf("resume", "foreground", "idle", "foreground", "idle"), events)
    }

    @Test
    fun turnedOffBeforeTheDeadlineFiresSuspends() {
        tracker.activityStarted()
        tracker.activityStopped()
        on = false
        clock.advance(60_000)
        assertEquals(listOf("resume", "foreground", "suspend"), events)
        events.clear()
        tracker.activityStarted()
        assertEquals(listOf("resume", "foreground"), events)
    }

    @Test
    fun turnedOnBeforeTheDeadlineFiresIsIdle() {
        on = false
        tracker.activityStarted()
        tracker.activityStopped()
        on = true
        clock.advance(60_000)
        assertEquals(listOf("resume", "foreground", "idle"), events)
    }

    @Test
    fun offAfterSleepingPastTheDeadlineStillSuspendsThenResumes() {
        on = false
        tracker.activityStarted()
        tracker.activityStopped()
        clock.sleep(5 * 60_000)
        events.clear()
        tracker.activityStarted()
        assertEquals(listOf("suspend", "resume", "foreground"), events)
    }

    @Test
    fun aRotationIsNeverIdle() {
        tracker.activityStarted()
        tracker.activityStopped()
        tracker.activityStarted()
        clock.advance(10 * 60_000)
        assertEquals(listOf("resume", "foreground", "foreground"), events)
    }
}
