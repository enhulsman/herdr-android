package dev.herdr.android.ui.flows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.herdr.android.hub.HubHost
import dev.herdr.android.ui.addmachine.ValidMachine
import dev.herdr.android.ui.hostkey.HostKeyPrompt
import dev.herdr.android.ui.hostkey.hostKeyChange
import dev.herdr.android.ui.hostkey.hostKeyPrompt
import dev.herdr.android.ui.machines.MachinesActions
import uniffi.herdr_mobile.HubException
import uniffi.herdr_mobile.HubInterface
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState

/** The app's screens; switched by state, without a navigation library. */
enum class Screen {
    /** The start screen: every agent, the ones that need you first (renders 1 and 2). */
    Agents,
    Machines,
    AddMachine,

    /** A machine presented a different host key: both fingerprints, and only "Forget this machine's key". */
    HostKeyChanged,

    /** The restricted authorized_keys line with its Copy button. */
    PhoneKey,
}

/**
 * Where the person is among the Machines flows, and the hub calls those flows
 * make (machine-connection spec: add machine, host keys, the restricted line).
 * Kept in Compose state so the screens follow it, and plain enough to test
 * without a composition (MachineFlowsTest).
 *
 * The host-key rules it keeps: a trust dialog exists only for a machine whose
 * state is HostKeyUnconfirmed and confirms exactly the fingerprint it showed;
 * a changed key leads to [Screen.HostKeyChanged], where the only call is
 * forgetting the old key, and only after [askForget]'s second confirm. After
 * forgetting, nothing opens the trust dialog by itself: the machine's card
 * offers it as for any first connection.
 *
 * Removing a machine asks first ([askRemove]); only the confirm's Remove
 * calls the hub, and a refusal is kept in [error] for the Machines screen.
 */
class MachineFlows(private val hubHost: HubHost) {
    var screen by mutableStateOf(Screen.Agents)
        private set

    /** The machine [Screen.HostKeyChanged] or [Screen.PhoneKey] is about (none for the Phone key row). */
    var machine by mutableStateOf<String?>(null)
        private set

    /**
     * A machine just added from the form, until its setup ends: trusted,
     * cancelled, or another outcome. Kept while its trust dialog is open, so
     * a suspension in the background (Paused, then Connecting) brings the
     * dialog back once the hub reports the unconfirmed key again.
     */
    var waitingFor by mutableStateOf<String?>(null)
        private set

    /** The machine whose trust dialog is open, over whichever screen is showing. */
    var trusting by mutableStateOf<String?>(null)
        private set

    /** The second confirm on [Screen.HostKeyChanged] is open. */
    var confirmingForget by mutableStateOf(false)
        private set

    /** The machine whose Remove confirm is open on the Machines screen. */
    var removing by mutableStateOf<String?>(null)
        private set

    /** Why the last hub call failed, shown where it was made. */
    var error by mutableStateOf<String?>(null)
        private set

    private fun current(): HubSnapshot = hubHost.snapshot.value

    /** The Machines screen's seams, wired to these flows. */
    fun actions(base: MachinesActions): MachinesActions = base.copy(
        onHostKey = ::openHostKey,
        onAddPhoneKey = { name -> show(Screen.PhoneKey, name) },
        onPhoneKey = { show(Screen.PhoneKey, null) },
        onAddMachine = { show(Screen.AddMachine, null) },
        onRemove = ::askRemove,
        onConfirmRemove = ::remove,
        onCancelRemove = ::cancelRemove,
    )

    /** The Agents home's Machines button and machine notices. */
    fun openMachines() = show(Screen.Machines, null)

    /** The empty Agents home's "Add machine"; back from the form lands on Machines. */
    fun openAddMachine() = show(Screen.AddMachine, null)

    private fun show(next: Screen, about: String?) {
        screen = next
        machine = about
        waitingFor = null
        confirmingForget = false
        removing = null
        error = null
    }

    /** The card's host-key row: the confirm for a first connection, the comparison for a changed key. */
    fun openHostKey(name: String) {
        when (current().machines.find { it.name == name }?.state) {
            is MachineState.HostKeyUnconfirmed -> {
                error = null
                trusting = name
            }
            is MachineState.HostKeyMismatch -> show(Screen.HostKeyChanged, name)
            else -> Unit
        }
    }

    /** The trust dialog's content, or null when no dialog should show. */
    fun trustPrompt(snapshot: HubSnapshot): HostKeyPrompt? = trusting?.let { hostKeyPrompt(snapshot, it) }

    /**
     * Follows the hub: opens the confirm for a machine just added (again after
     * a suspension), closes views whose state moved on.
     */
    fun onSnapshot(snapshot: HubSnapshot) {
        waitingFor?.let { name ->
            when (snapshot.machines.find { it.name == name }?.state) {
                // Shows the key presented now; only Trust confirms it.
                is MachineState.HostKeyUnconfirmed -> trusting = name
                // Still on its way, suspended in the background, or not listed in this snapshot yet.
                null, MachineState.Connecting, MachineState.Paused -> Unit
                // Unreachable and the like: the machine's card says why.
                else -> show(Screen.Machines, null)
            }
        }
        trusting?.let { name ->
            if (hostKeyPrompt(snapshot, name) == null) {
                trusting = null
                error = null
            }
        }
        if (screen == Screen.HostKeyChanged && hostKeyChange(snapshot, machine) == null) {
            show(Screen.Machines, null)
        }
        removing?.let { name ->
            if (snapshot.machines.none { it.name == name }) removing = null
        }
    }

    /** The form passed its checks: add the machine, then wait for its host key. */
    fun save(valid: ValidMachine) {
        if (call { it.addMachine(valid.name, valid.host, valid.port, valid.user) }) {
            waitingFor = valid.name
        }
    }

    /** "Trust and connect": confirms the fingerprint the dialog showed, nothing else. */
    fun trust(prompt: HostKeyPrompt) {
        if (trusting != prompt.machine) return
        if (call { it.confirmHostKey(prompt.machine, prompt.fingerprint) }) {
            trusting = null
            show(Screen.Machines, null)
        }
    }

    /** "Cancel": nothing is trusted and the machine stays unconfirmed. */
    fun cancelTrust() {
        trusting = null
        error = null
        if (screen == Screen.AddMachine) show(Screen.Machines, null)
    }

    /** "Forget this machine's key": opens the second confirm; calls nothing. */
    fun askForget() {
        if (screen == Screen.HostKeyChanged) {
            error = null
            confirmingForget = true
        }
    }

    fun cancelForget() {
        confirmingForget = false
        error = null
    }

    /** The second confirm's "Forget key": the next connection asks as a first one. */
    fun forget() {
        val name = machine
        if (screen != Screen.HostKeyChanged || !confirmingForget || name == null) return
        if (call { it.forgetHostKey(name) }) show(Screen.Machines, null)
    }

    /** The card's "Remove machine": opens the confirm; calls nothing. */
    fun askRemove(name: String) {
        error = null
        removing = name
    }

    /** Cancel, the scrim or back: nothing is removed. */
    fun cancelRemove() {
        removing = null
        error = null
    }

    /**
     * The confirm's Remove: the hub forgets the machine, its trusted host key
     * and its seen turns. The confirm closes either way; a refusal stays in [error].
     */
    fun remove() {
        val name = removing ?: return
        removing = null
        call { it.removeMachine(name) }
    }

    /** System back: closes a dialog first, then returns to Machines, then to the Agents home; false there. */
    fun back(): Boolean = when {
        trusting != null -> {
            cancelTrust()
            true
        }
        confirmingForget -> {
            cancelForget()
            true
        }
        removing != null -> {
            cancelRemove()
            true
        }
        screen == Screen.Machines -> {
            show(Screen.Agents, null)
            true
        }
        screen != Screen.Agents -> {
            show(Screen.Machines, null)
            true
        }
        else -> false
    }

    /** Runs a hub call; on failure keeps the reason in [error] and returns false. */
    private inline fun call(block: (HubInterface) -> Unit): Boolean {
        val hub = hubHost.hub ?: run {
            hubHost.ensureStarted()
            hubHost.hub
        }
        if (hub == null) {
            error = hubHost.startError.value ?: "the connection core is not running"
            return false
        }
        return try {
            block(hub)
            error = null
            true
        } catch (e: HubException) {
            error = hubErrorText(e)
            false
        }
    }

    companion object {
        /** Keeps the flow across rotation and process death (the error is not kept). */
        fun saver(hubHost: HubHost): Saver<MachineFlows, Any> = listSaver(
            save = { listOf(it.screen.name, it.machine, it.waitingFor, it.trusting, it.confirmingForget, it.removing) },
            restore = { saved ->
                MachineFlows(hubHost).apply {
                    screen = Screen.valueOf(saved[0] as String)
                    machine = saved[1] as String?
                    waitingFor = saved[2] as String?
                    trusting = saved[3] as String?
                    confirmingForget = saved[4] as Boolean
                    removing = saved[5] as String?
                }
            },
        )
    }
}

/** The hub's refusal in words (its Rust Display text; the form's own checks normally come first). */
fun hubErrorText(e: HubException): String = when (e) {
    is HubException.Invalid -> e.detail
    is HubException.Duplicate -> "a machine named \"${e.name}\" is already listed"
    is HubException.NotFound -> "no machine named \"${e.name}\""
    is HubException.Unreadable -> "not a machine list: ${e.detail}"
    is HubException.Storage -> e.detail
}
