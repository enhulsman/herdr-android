package dev.herdr.android.hub

import dev.herdr.android.lifecycle.Cancellable
import dev.herdr.android.lifecycle.ElapsedClock
import dev.herdr.android.lifecycle.Scheduler
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState

/** Shows the system authentication prompt over the foreground activity. */
fun interface PromptHost {
    /** Calls [onResult] once: true when the person authenticated. */
    fun showPrompt(onResult: (Boolean) -> Unit)
}

/**
 * Turns NeedsUserAuth into one prompt (design.md §2): when a machine needs the
 * key's authentication window and an activity is in the foreground, one
 * prompt; on success one [retry], which tries every waiting machine (one
 * authentication opens the window for all of them). A cancel or failure
 * retries nothing and prompts no more by itself; [unlock] is the person
 * asking again. The automatic prompt comes back once no machine needs
 * authentication or is connecting (it connected, or the hub was suspended),
 * so a retry that still needs authentication cannot loop the prompt.
 *
 * While [stayConnected] holds (stay-connected design D6) the hub is never
 * suspended, so the window may have opened without the app: an activity
 * start, or an unlock of the phone ([onUnlocked]), first retries silently,
 * and the prompt comes only if the key is still needed after the retried
 * attempt, or at a 10 s fallback on [scheduler] when its Connecting was
 * dropped. Meanwhile a NeedsUserAuth snapshot is ignored, as it may be stale.
 * [onBackgroundIdle] re-arms the prompt, the role the Paused snapshot of a
 * suspend plays when the option is off. Main thread only.
 */
class UserAuthGate(
    private val retry: () -> Unit,
    private val stayConnected: () -> Boolean = { false },
    private val scheduler: Scheduler = Scheduler { _, _ -> Cancellable {} },
    private val clock: ElapsedClock = ElapsedClock { 0L },
) {
    private var host: PromptHost? = null
    private var showing = false
    private var armed = true

    /** A silent retry is under way: until its result, NeedsUserAuth may be stale and prompts nothing. */
    private var retrying = false
    private var fallback: Cancellable? = null

    /** Some machine waits for the key or out a backoff: an unlock retries it. */
    private var waiting = false

    /** Some machine is waiting for the person; a later screen offers [unlock]. */
    var needsUserAuth = false
        private set

    fun onHost(host: PromptHost?) {
        this.host = host
        if (host != null && stayConnected() && needsUserAuth && armed && !showing && !retrying) {
            silentRetry()
            return
        }
        maybePrompt()
    }

    fun onSnapshot(snapshot: HubSnapshot) {
        needsUserAuth = snapshot.machines.any { it.state == MachineState.NeedsUserAuth }
        waiting = needsUserAuth || snapshot.machines.any { it.state.isBackoff() }
        if (retrying) {
            // A stale or repeated result from before the retry; the Connecting in between may have been dropped.
            if (needsUserAuth) return
            // kick moves every waiting machine to Connecting in one publish: the retried attempt has begun.
            endRetry()
        }
        if (!armed && !needsUserAuth && snapshot.machines.none { it.state == MachineState.Connecting }) {
            armed = true
        }
        maybePrompt()
    }

    /** The person asked to unlock; false when no activity can show the prompt or one is up. */
    fun unlock(): Boolean {
        if (showing) return false
        val host = host ?: return false
        show(host)
        return true
    }

    /**
     * The phone was unlocked (ACTION_USER_PRESENT, while Stay connected runs):
     * one silent retry when a machine waits for the key, whatever [armed] is,
     * as the unlock may have opened the window; else one plain [retry] when a
     * machine waits out a backoff that CPU sleep may have stalled.
     */
    fun onUnlocked() {
        if (!stayConnected() || retrying || showing) return
        when {
            needsUserAuth -> silentRetry()
            waiting -> retry()
        }
    }

    /** The app spent 60 s in the background with Stay connected on: the automatic prompt may come again. */
    fun onBackgroundIdle() {
        armed = true
    }

    private fun silentRetry() {
        retrying = true
        fallback?.cancel()
        // On the Handler, so it stalls in deep sleep: with no activity it only clears [retrying].
        fallback = scheduler.scheduleAt(clock.elapsedRealtime() + SILENT_RETRY_FALLBACK_MS) {
            fallback = null
            retrying = false
            maybePrompt()
        }
        retry()
    }

    private fun endRetry() {
        retrying = false
        fallback?.cancel()
        fallback = null
    }

    private fun maybePrompt() {
        if (armed && needsUserAuth && !showing && !retrying) host?.let(::show)
    }

    private fun show(host: PromptHost) {
        showing = true
        armed = false
        var answered = false
        host.showPrompt { success ->
            if (answered) return@showPrompt
            answered = true
            showing = false
            if (success) retry()
        }
    }

    private fun MachineState.isBackoff(): Boolean =
        this is MachineState.Unreachable || this == MachineState.KeyRejected || this == MachineState.NotInstalled

    companion object {
        const val SILENT_RETRY_FALLBACK_MS = 10_000L
    }
}
