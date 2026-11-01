package dev.herdr.android.lifecycle

import android.app.AlarmManager
import android.os.Handler
import android.os.SystemClock

fun interface Cancellable {
    fun cancel()
}

/** Time since boot including deep sleep ([SystemClock.elapsedRealtime]); injectable for tests. */
fun interface ElapsedClock {
    fun elapsedRealtime(): Long

    companion object {
        val SYSTEM = ElapsedClock { SystemClock.elapsedRealtime() }
    }
}

/** Runs a task on the main thread at an elapsed-realtime moment; injectable so tests own the clock. */
fun interface Scheduler {
    fun scheduleAt(elapsedRealtimeMs: Long, task: () -> Unit): Cancellable
}

/**
 * [Scheduler] on [AlarmManager]: an inexact elapsed-realtime alarm with a
 * listener (no receiver, no permission), so the time spent in deep sleep
 * counts, unlike a Handler's uptime. Doze may deliver it late;
 * [ForegroundTracker] catches that up when the app comes back.
 */
class AlarmScheduler(private val alarms: AlarmManager, private val handler: Handler) : Scheduler {
    override fun scheduleAt(elapsedRealtimeMs: Long, task: () -> Unit): Cancellable {
        val listener = AlarmManager.OnAlarmListener { task() }
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedRealtimeMs, TAG, listener, handler)
        return Cancellable { alarms.cancel(listener) }
    }

    private companion object {
        const val TAG = "herdr:suspend"
    }
}

/**
 * Counts started activities for the whole process (machine-connection spec,
 * "Connections end in the background"). When none has been started for
 * [delayMs] of real time, [onSuspend] closes every connection; the next start
 * calls [onResume]. A start before the deadline cancels the suspension; a
 * start after it whose alarm has not run yet (deferred in doze) suspends and
 * then resumes, so the connections are fresh. A configuration change stops
 * and starts within one frame, so it never suspends. The hub starts paused,
 * so the first start resumes it. Every return to the foreground also calls
 * [onForeground]. Main thread only.
 */
class ForegroundTracker(
    private val scheduler: Scheduler,
    private val clock: ElapsedClock,
    private val onResume: () -> Unit,
    private val onSuspend: () -> Unit,
    private val onForeground: () -> Unit = {},
    private val delayMs: Long = BACKGROUND_GRACE_MS,
) {
    private var started = 0
    private var suspended = true
    private var pending: Cancellable? = null
    private var deadline = 0L

    val isForeground: Boolean get() = started > 0

    fun activityStarted() {
        started++
        if (started > 1) return
        pending?.let {
            it.cancel()
            pending = null
            if (clock.elapsedRealtime() >= deadline) suspendNow()
        }
        if (suspended) {
            suspended = false
            onResume()
        }
        onForeground()
    }

    fun activityStopped() {
        if (started == 0) return
        started--
        if (started > 0) return
        pending?.cancel()
        deadline = clock.elapsedRealtime() + delayMs
        pending = scheduler.scheduleAt(deadline) {
            pending = null
            suspendNow()
        }
    }

    private fun suspendNow() {
        if (suspended) return
        suspended = true
        onSuspend()
    }

    companion object {
        const val BACKGROUND_GRACE_MS = 60_000L
    }
}
