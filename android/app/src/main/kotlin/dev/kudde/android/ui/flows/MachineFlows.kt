package dev.kudde.android.ui.flows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.kudde.android.hub.HubHost
import dev.kudde.android.ui.addmachine.FormErrors
import dev.kudde.android.ui.addmachine.MachineInput
import dev.kudde.android.ui.addmachine.ValidMachine
import dev.kudde.android.ui.addmachine.derivedMachineName
import dev.kudde.android.ui.addmachine.validateMachine
import dev.kudde.android.ui.hostkey.HostKeyPrompt
import dev.kudde.android.ui.hostkey.hostKeyChange
import dev.kudde.android.ui.hostkey.hostKeyPrompt
import dev.kudde.android.ui.machines.MachinesActions
import uniffi.kudde_mobile.HubException
import uniffi.kudde_mobile.HubInterface
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.MachineState
import uniffi.kudde_mobile.ReachKind

/** The app's screens; switched by state, without a navigation library. */
enum class Screen {
    /** The start screen: every agent, the ones that need you first (renders 1 and 2). */
    Agents,
    Machines,
    Setup,

    /** A machine presented a different host key: both fingerprints, and only "Forget this machine's key". */
    HostKeyChanged,

    /** The restricted authorized_keys line with its Copy button. */
    KeyCommand,
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

    /** The machine [Screen.HostKeyChanged] or [Screen.Setup] is about (none for the Phone key row). */
    var machine by mutableStateOf<String?>(null)
        private set

    /** Only a submitted step 1 is waiting for its first result. */
    var waitingFor by mutableStateOf<String?>(null)
        private set

    var setupStep by mutableStateOf(1)
        private set
    var input by mutableStateOf(MachineInput())
        private set
    private var addedThisVisit by mutableStateOf(false)
    private var reachedThisVisit by mutableStateOf(false)
    private var setupOrigin = Screen.Agents

    val nameLocked: Boolean
        get() = current().machines.find { it.name == machine }?.let {
            it.hostKey != null || it.lastConnectedMs != null
        } ?: false

    fun changeInput(value: MachineInput) {
        input = if (nameLocked) value.copy(name = machine.orEmpty()) else value
    }

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
        onAddPhoneKey = { name -> openSetup(name, atLeast = 2) },
        onContinueSetup = { name -> openSetup(name) },
        onChangeAddress = { name ->
            openSetup(name)
            changeAddress()
        },
        onPhoneKey = { show(Screen.KeyCommand, null) },
        onAddMachine = ::openAddMachine,
        onRemove = ::askRemove,
        onConfirmRemove = ::remove,
        onCancelRemove = ::cancelRemove,
    )

    /** The Agents home's Machines button and machine notices. */
    fun openMachines() = show(Screen.Machines, null)

    /** A tapped alert: the Agents home, where its terminal opens; closes any dialog. */
    fun openAgents() {
        trusting = null
        show(Screen.Agents, null)
    }

    /** Start a fresh visit; back from step 1 returns to its caller. */
    fun openAddMachine() {
        setupOrigin = screen.takeIf { it == Screen.Machines } ?: Screen.Agents
        show(Screen.Setup, null)
        setupStep = 1
        input = MachineInput()
        addedThisVisit = false
        reachedThisVisit = false
    }

    /** Resume a listed machine. Card desk actions explicitly open step 2. */
    fun openSetup(name: String, atLeast: Int = 1) {
        val m = current().machines.find { it.name == name } ?: return
        setupOrigin = screen.takeIf { it == Screen.Agents } ?: Screen.Machines
        show(Screen.Setup, name)
        setupStep = when {
            m.state is MachineState.Connected -> 3
            m.hostKey == null && m.state !is MachineState.HostKeyUnconfirmed -> 1
            else -> 2
        }.coerceAtLeast(atLeast.coerceIn(1, 2))
        val name = if (m.hostKey == null && m.lastConnectedMs == null && m.name == derivedMachineName(m.host)) "" else m.name
        input = MachineInput(name, m.host, m.port.toString(), m.user)
        addedThisVisit = false
        reachedThisVisit = setupStep >= 2
        if (m.state is MachineState.HostKeyMismatch) show(Screen.HostKeyChanged, name)
    }

    /** The only deliberate backward step; preserves this visit's ownership. */
    fun changeAddress() {
        if (screen != Screen.Setup || machine == null) return
        setupStep = 1
        trusting = null
        waitingFor = null
    }

    fun retry() {
        call { it.retry() }
    }

    /** The hub owns grace. Neither deadlines nor the number of snapshots matter. */
    fun failure(snapshot: HubSnapshot): MachineState? {
        val m = snapshot.machines.find { it.name == machine } ?: return null
        return when (val state = m.state) {
            is MachineState.Unreachable -> if (
                state.kind == ReachKind.CORE_STARTUP_CLOSED && m.startupRetryPending
            ) null else state
            MachineState.Connecting, MachineState.Paused,
            is MachineState.Connected, is MachineState.HostKeyUnconfirmed -> null
            else -> state
        }
    }

    private fun show(next: Screen, about: String?) {
        screen = next
        machine = about
        waitingFor = null
        confirmingForget = false
        removing = null
        error = null
    }

    /** The card opens the desk step; comparison is a separate deliberate action. */
    fun openHostKey(name: String) {
        when (current().machines.find { it.name == name }?.state) {
            is MachineState.HostKeyUnconfirmed -> openSetup(name, atLeast = 2)
            is MachineState.HostKeyMismatch -> show(Screen.HostKeyChanged, name)
            else -> Unit
        }
    }

    fun compareFingerprint() {
        val name = machine ?: return
        if (screen != Screen.Setup || setupStep != 2 || hostKeyPrompt(current(), name) == null) return
        error = null
        trusting = name
    }

    /** The trust dialog's content, or null when no dialog should show. */
    fun trustPrompt(snapshot: HubSnapshot): HostKeyPrompt? = trusting?.let { hostKeyPrompt(snapshot, it) }

    /** Follow settlements forward, closing dialogs whose keys are no longer presented. */
    fun onSnapshot(snapshot: HubSnapshot) {
        if (screen == Screen.Setup) {
            snapshot.machines.find { it.name == machine }?.let { m ->
                when (m.state) {
                    is MachineState.Connected -> {
                        setupStep = 3
                        reachedThisVisit = true
                        waitingFor = null
                    }
                    is MachineState.HostKeyMismatch -> show(Screen.HostKeyChanged, m.name)
                    is MachineState.HostKeyUnconfirmed -> {
                        if (setupStep < 2) setupStep = 2
                        reachedThisVisit = true
                        waitingFor = null
                    }
                    else -> {
                        // A confirmed pin also proves that the machine was reached,
                        // including before its first successful core connection.
                        if (m.hostKey != null) {
                            if (setupStep < 2) setupStep = 2
                            reachedThisVisit = true
                        }
                        if (m.state != MachineState.Connecting && m.state != MachineState.Paused) waitingFor = null
                    }
                }
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

    /** Form seam for the later production screen; own name is never a conflict. */
    fun submit(): FormErrors {
        val value = if (nameLocked) input.copy(name = machine.orEmpty()) else input
        val errors = validateMachine(value, current().machines.map { it.name }.filter { it != machine })
        errors.valid?.let(::save)
        return errors
    }

    /** Only an unpinned, never-connected entry may be replaced to follow a name edit. */
    fun save(valid: ValidMachine) {
        val old = current().machines.find { it.name == machine }
        val listedName = if (old != null && (old.hostKey != null || old.lastConnectedMs != null)) old.name else valid.name
        val saved = if (old == null) {
            call { it.addMachine(listedName, valid.host, valid.port, valid.user) }.also {
                if (it) addedThisVisit = true
            }
        } else if (old.hostKey != null || old.lastConnectedMs != null) {
            call { it.updateMachine(old.name, valid.host, valid.port, valid.user) }
        } else {
            if (!call { it.removeMachine(old.name) }) return
            // If adding is refused, retain the typed fields and clear the removed
            // identity so the next submit can retry the add.
            machine = null
            call { it.addMachine(valid.name, valid.host, valid.port, valid.user) }
        }
        if (saved) {
            machine = listedName
            waitingFor = listedName
            input = MachineInput(if (nameLocked) listedName else input.name, valid.host, valid.port.toString(), valid.user)
        }
    }

    /** "Trust and connect": confirms the fingerprint the dialog showed, nothing else. */
    fun trust(prompt: HostKeyPrompt) {
        if (trusting != prompt.machine || trustPrompt(current()) != prompt) return
        if (call { it.confirmHostKey(prompt.machine, prompt.fingerprint) }) {
            trusting = null
            waitingFor = null
        }
    }

    /** "Cancel": nothing is trusted and the machine stays unconfirmed. */
    fun cancelTrust() {
        trusting = null
        error = null
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
    fun back(): Boolean {
        return when {
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
            screen == Screen.Setup -> {
                if (setupStep == 1) {
                    val m = current().machines.find { it.name == machine }
                    if (addedThisVisit && !reachedThisVisit && m?.hostKey == null && m?.lastConnectedMs == null) {
                        val name = machine
                        if (name != null && !call { it.removeMachine(name) }) return true
                    }
                    show(setupOrigin, null)
                } else show(Screen.Machines, null)
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
            save = {
                listOf(
                    it.screen.name, it.machine, it.waitingFor, it.trusting, it.confirmingForget, it.removing,
                    it.setupStep, it.addedThisVisit, it.reachedThisVisit, it.setupOrigin.name,
                    it.input.name, it.input.host, it.input.port, it.input.user,
                )
            },
            restore = { saved ->
                MachineFlows(hubHost).apply {
                    screen = Screen.valueOf(saved[0] as String)
                    machine = saved[1] as String?
                    waitingFor = saved[2] as String?
                    trusting = saved[3] as String?
                    confirmingForget = saved[4] as Boolean
                    removing = saved[5] as String?
                    setupStep = saved[6] as Int
                    addedThisVisit = saved[7] as Boolean
                    reachedThisVisit = saved[8] as Boolean
                    setupOrigin = Screen.valueOf(saved[9] as String)
                    input = MachineInput(saved[10] as String, saved[11] as String, saved[12] as String, saved[13] as String)
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
