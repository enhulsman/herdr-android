package dev.herdr.android.hub

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
 * Main thread only.
 */
class UserAuthGate(private val retry: () -> Unit) {
    private var host: PromptHost? = null
    private var showing = false
    private var armed = true

    /** Some machine is waiting for the person; a later screen offers [unlock]. */
    var needsUserAuth = false
        private set

    fun onHost(host: PromptHost?) {
        this.host = host
        maybePrompt()
    }

    fun onSnapshot(snapshot: HubSnapshot) {
        needsUserAuth = snapshot.machines.any { it.state == MachineState.NeedsUserAuth }
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

    private fun maybePrompt() {
        if (armed && needsUserAuth && !showing) host?.let(::show)
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
}
