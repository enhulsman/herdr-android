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
 * [Scheduler] on a main-thread [Handler]: runs in the process whenever it is
 * awake, so it fires on time while an inexact alarm may be held back (Android
 * 12+ defers those even outside Doze). Its delay counts uptime, which stops in
 * deep sleep, so it can run late; [AlarmScheduler] covers that case.
 */
class HandlerScheduler(private val handler: Handler, private val clock: ElapsedClock) : Scheduler {
    override fun scheduleAt(elapsedRealtimeMs: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, (elapsedRealtimeMs - clock.elapsedRealtime()).coerceAtLeast(0))
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

/**
 * [Scheduler] on [AlarmManager]: an inexact elapsed-realtime alarm with a
 * listener (no receiver, no permission), so the time spent in deep sleep
 * counts, unlike a Handler's uptime. Android may deliver it late (Doze, and
 * on 12+ inexact alarms generally); [HandlerScheduler] covers the awake case
 * and [ForegroundTracker] catches up when the app comes back.
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
 * calls [onResume]. The deadline is on elapsed realtime and watched twice:
 * by [timer], in-process, which fires on time whenever the process is awake,
 * and by [alarm], an inexact wakeup alarm that still counts deep sleep;
 * whichever runs first suspends and cancels the other, and a late callback
 * from either never acts. A start before the deadline cancels both; a start
 * after it while neither has run (deep sleep, deferred alarm) suspends and
 * then resumes, so the connections are fresh. A configuration change stops
 * and starts within one frame, so it never suspends. The hub starts paused,
 * so the first start resumes it. Every return to the foreground also calls
 * [onForeground]. Main thread only.
 */
class ForegroundTracker(
    private val timer: Scheduler,
    private val alarm: Scheduler,
    private val clock: ElapsedClock,
    private val onResume: () -> Unit,
    private val onSuspend: () -> Unit,
    private val onForeground: () -> Unit = {},
    private val delayMs: Long = BACKGROUND_GRACE_MS,
) {
    private var started = 0
    private var suspended = true
    /** The timer and the alarm watching the current deadline; empty when none is armed. */
    private var pending: List<Cancellable> = emptyList()

    /** Bumped on every arm and disarm, so a callback that outlived its cancel does nothing. */
    private var generation = 0L
    private var deadline = 0L

    val isForeground: Boolean get() = started > 0

    fun activityStarted() {
        started++
        if (started > 1) return
        if (pending.isNotEmpty()) {
            disarm()
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
        disarm()
        deadline = clock.elapsedRealtime() + delayMs
        val armed = generation
        val fire = {
            if (armed == generation) {
                disarm()
                suspendNow()
            }
        }
        pending = listOf(timer.scheduleAt(deadline, fire), alarm.scheduleAt(deadline, fire))
    }

    private fun disarm() {
        generation++
        val armed = pending
        pending = emptyList()
        armed.forEach { it.cancel() }
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
