package dev.kudde.android.ui.flows

import androidx.compose.runtime.saveable.SaverScope
import dev.kudde.android.hub.FakeHub
import dev.kudde.android.hub.HubHost
import dev.kudde.android.ui.addmachine.MachineInput
import dev.kudde.android.ui.addmachine.ValidMachine
import dev.kudde.android.ui.hostkey.hostKeyChange
import dev.kudde.android.ui.hostkey.hostKeyPrompt
import dev.kudde.android.ui.machines.MachinesActions
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.kudde_mobile.HubException
import uniffi.kudde_mobile.HubListener
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.MachineState
import uniffi.kudde_mobile.MachineView
import uniffi.kudde_mobile.ReachKind

// The flows behind the Machines screen's seams (machine-connection spec, "Host
// keys are confirmed once and then enforced"): Add machine, the first host key
// confirm (render 8), a changed host key, and the phone key line. Trusting
// confirms exactly the fingerprint the dialog showed; Cancel trusts nothing; a
// changed key offers no way to trust the new key, only forgetting the old one
// behind a second confirm.
class MachineFlowsTest {
    private val hub = FakeHub()
    private lateinit var listener: HubListener
    private val hubHost = HubHost(createHub = { listener = it; hub }, mainExecutor = Executor { it.run() })
    private val flows = MachineFlows(hubHost)

    init {
        hubHost.start()
    }

    private fun publish(vararg machines: MachineView): HubSnapshot {
        val s = HubSnapshot(machines.toList(), emptyList(), emptyList(), null)
        listener.onSnapshot(s)
        flows.onSnapshot(s)
        return s
    }

    private fun machine(name: String, state: MachineState, host: String = "$name.example.org", port: UShort = 22u) =
        MachineView(name, host, port, "dev", null, state, null)

    private val workstation = ValidMachine("workstation", "workstation.example.org", 22u, "dev")
    private val unconfirmed = MachineState.HostKeyUnconfirmed(PRESENTED, "ED25519")
    private val changed = MachineState.HostKeyMismatch(TRUSTED, PRESENTED, "ED25519")

    @Test
    fun addUsesSetup() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        assertEquals(Screen.Setup, flows.screen)

        flows.save(workstation)
        assertEquals(listOf("add workstation workstation.example.org 22 dev"), hub.calls)
        assertEquals("workstation", flows.waitingFor)
        assertNull(flows.trusting)
    }

    @Test
    fun compareOverSetup() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", MachineState.Connecting))
        assertNull(flows.trusting)

        val s = publish(machine("workstation", unconfirmed))
        flows.compareFingerprint()
        assertEquals("workstation", flows.trusting)
        assertEquals(Screen.Setup, flows.screen)
        val prompt = flows.trustPrompt(s)!!
        assertEquals(PRESENTED, prompt.fingerprint)
        assertEquals("ED25519", prompt.algorithm)
        assertEquals("workstation.example.org:22", prompt.address)
    }

    @Test
    fun trustStaysInSetup() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        val s = publish(machine("workstation", unconfirmed))
        flows.compareFingerprint()

        flows.trust(flows.trustPrompt(s)!!)
        assertEquals(
            listOf("add workstation workstation.example.org 22 dev", "confirm workstation $PRESENTED"),
            hub.calls,
        )
        assertNull(flows.trusting)
        assertEquals(Screen.Setup, flows.screen)
    }

    @Test
    fun cancelKeepsSetup() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        flows.compareFingerprint()
        hub.calls.clear()

        flows.cancelTrust()
        assertEquals(emptyList<String>(), hub.calls)
        assertNull(flows.trusting)
        assertEquals(Screen.Setup, flows.screen)
        // Later snapshots do not bring the dialog back by themselves.
        publish(machine("workstation", unconfirmed))
        assertNull(flows.trusting)
    }

    @Test
    fun cardOpensDesk() {
        flows.openMachines()
        val s = publish(machine("workstation", unconfirmed))
        flows.actions(MachinesActions.Preview).onHostKey("workstation")
        flows.compareFingerprint()
        assertEquals(Screen.Setup, flows.screen)
        assertEquals("workstation", flows.trusting)
        assertEquals(PRESENTED, flows.trustPrompt(s)!!.fingerprint)

        flows.cancelTrust()
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(emptyList<String>(), hub.calls)
    }

    @Test
    fun aChangedKeyHasNoTrustPrompt() {
        val s = publish(machine("server", changed))
        assertNull(hostKeyPrompt(s, "server"))
        val change = hostKeyChange(s, "server")!!
        assertEquals(TRUSTED, change.trusted)
        assertEquals(PRESENTED, change.presented)
        assertEquals("ED25519", change.algorithm)
    }

    @Test
    fun theMachinesCardOpensTheChangedKeyScreenWithoutAnyTrustDialog() {
        val s = publish(machine("server", changed))
        flows.actions(MachinesActions.Preview).onHostKey("server")
        assertEquals(Screen.HostKeyChanged, flows.screen)
        assertEquals("server", flows.machine)
        assertNull(flows.trusting)
        assertNull(flows.trustPrompt(s))
    }

    @Test
    fun forgettingNeedsTheSecondConfirmAndNeverTrustsTheNewKey() {
        publish(machine("server", changed))
        flows.actions(MachinesActions.Preview).onHostKey("server")

        flows.askForget()
        assertTrue(flows.confirmingForget)
        assertEquals(emptyList<String>(), hub.calls)

        flows.cancelForget()
        assertFalse(flows.confirmingForget)
        assertEquals(Screen.HostKeyChanged, flows.screen)
        assertEquals(emptyList<String>(), hub.calls)

        flows.askForget()
        flows.forget()
        assertEquals(listOf("forget server"), hub.calls)
        assertEquals(Screen.Machines, flows.screen)
        assertFalse(flows.confirmingForget)

        // The next connection asks again as a first connection, from the card:
        // nothing opens or trusts it by itself.
        publish(machine("server", unconfirmed))
        assertNull(flows.trusting)
        assertTrue(hub.calls.none { it.startsWith("confirm") })
    }

    @Test
    fun forgetWithoutTheConfirmDoesNothing() {
        publish(machine("server", changed))
        flows.actions(MachinesActions.Preview).onHostKey("server")
        flows.forget()
        assertEquals(emptyList<String>(), hub.calls)
        assertEquals(Screen.HostKeyChanged, flows.screen)
    }

    @Test
    fun theChangedKeyScreenClosesOnceTheMachineIsNoLongerChanged() {
        publish(machine("server", changed))
        flows.actions(MachinesActions.Preview).onHostKey("server")
        publish(machine("server", MachineState.Connecting))
        assertEquals(Screen.Machines, flows.screen)
    }

    @Test
    fun compareClosesOnMismatch() {
        publish(machine("workstation", unconfirmed))
        flows.actions(MachinesActions.Preview).onHostKey("workstation")
        flows.compareFingerprint()
        publish(machine("workstation", changed))
        assertNull(flows.trusting)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun failureKeepsSetup() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", MachineState.Unreachable(ReachKind.REFUSED, "connection refused")))
        assertEquals(Screen.Setup, flows.screen)
        assertNull(flows.waitingFor)
        assertNull(flows.trusting)
    }

    @Test
    fun backgroundKeepsSetup() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        flows.compareFingerprint()
        assertEquals("workstation", flows.trusting)

        // Backgrounded for 60 s: the hub closes every connection.
        publish(machine("workstation", MachineState.Paused))
        assertNull(flows.trusting)
        // The desk step keeps its machine, while the dialog closes.
        assertEquals(2, flows.setupStep)
        assertEquals(Screen.Setup, flows.screen)
        assertEquals("workstation", flows.machine)

        // Back in the foreground: the machine reconnects and presents its key again.
        publish(machine("workstation", MachineState.Connecting))
        assertEquals(2, flows.setupStep)
        assertNull(flows.trusting)
        val s = publish(machine("workstation", unconfirmed))
        flows.compareFingerprint()
        assertEquals("workstation", flows.trusting)
        assertEquals(Screen.Setup, flows.screen)
        val prompt = flows.trustPrompt(s)!!
        assertEquals(PRESENTED, prompt.fingerprint)
        // Nothing was trusted by itself.
        assertEquals(listOf("add workstation workstation.example.org 22 dev"), hub.calls)

        flows.trust(prompt)
        assertEquals(
            listOf("add workstation workstation.example.org 22 dev", "confirm workstation $PRESENTED"),
            hub.calls,
        )
        assertNull(flows.trusting)
        assertNull(flows.waitingFor)
        assertEquals("workstation", flows.machine)
        assertEquals(Screen.Setup, flows.screen)
    }

    @Test
    fun suspendFingerprint() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        publish(machine("workstation", MachineState.Paused))
        val s = publish(machine("workstation", MachineState.HostKeyUnconfirmed(OTHER, "ECDSA")))
        flows.compareFingerprint()
        val prompt = flows.trustPrompt(s)!!
        assertEquals(OTHER, prompt.fingerprint)
        assertEquals("ECDSA", prompt.algorithm)
        flows.trust(prompt)
        assertEquals("confirm workstation $OTHER", hub.calls.last())
        assertTrue(hub.calls.none { it == "confirm workstation $PRESENTED" })
    }

    @Test
    fun suspendedFailureKeepsSetup() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        publish(machine("workstation", MachineState.Paused))
        publish(machine("workstation", MachineState.Unreachable(ReachKind.REFUSED, "connection refused")))
        assertEquals(Screen.Setup, flows.screen)
        assertNull(flows.waitingFor)
        assertNull(flows.trusting)
        assertTrue(hub.calls.none { it.startsWith("confirm") })
    }

    @Test
    fun cardDialogStaysClosed() {
        // Returning keeps step 2, but comparison must be opened deliberately.
        flows.openMachines()
        publish(machine("workstation", unconfirmed))
        flows.actions(MachinesActions.Preview).onHostKey("workstation")
        flows.compareFingerprint()
        publish(machine("workstation", MachineState.Paused))
        publish(machine("workstation", unconfirmed))
        assertNull(flows.trusting)
        assertEquals(Screen.Setup, flows.screen)
    }

    @Test
    fun refusedAddKeepsSetup() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        hub.refuse = HubException.Storage("Could not save the machine list: disk full.")
        flows.save(workstation)
        assertEquals(Screen.Setup, flows.screen)
        assertNull(flows.waitingFor)
        assertEquals("Could not save the machine list: disk full.", flows.error)
    }

    @Test
    fun refusedTrustKeepsDialog() {
        val s = publish(machine("workstation", unconfirmed))
        flows.actions(MachinesActions.Preview).onHostKey("workstation")
        flows.compareFingerprint()
        hub.refuse = HubException.NotFound("workstation")
        flows.trust(flows.trustPrompt(s)!!)
        assertEquals("workstation", flows.trusting)
        assertNotNull(flows.error)
    }

    @Test
    fun withoutARunningHubNothingIsCalledAndTheReasonIsShown() {
        val stopped = MachineFlows(HubHost(createHub = { throw IllegalStateException("no screen lock") }, Executor { it.run() }))
        stopped.save(workstation)
        assertEquals("no screen lock", stopped.error)
        assertNull(stopped.waitingFor)
    }

    @Test
    fun keyRowsUseSetup() {
        val actions = flows.actions(MachinesActions.Preview)
        publish(machine("website", MachineState.KeyRejected))
        actions.onAddPhoneKey("website")
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(2, flows.setupStep)
        assertEquals("website", flows.machine)
        assertTrue(flows.back())

        actions.onPhoneKey()
        assertEquals(Screen.KeyCommand, flows.screen)
        assertNull(flows.machine)
    }

    @Test
    fun backClosesDialogsFirstThenReturnsToMachines() {
        publish(machine("server", changed))
        flows.actions(MachinesActions.Preview).onHostKey("server")
        flows.askForget()
        assertTrue(flows.back())
        assertFalse(flows.confirmingForget)
        assertEquals(Screen.HostKeyChanged, flows.screen)
        assertTrue(flows.back())
        assertEquals(Screen.Machines, flows.screen)
        // Machines returns to the Agents home, the start screen; back there leaves the app.
        assertTrue(flows.back())
        assertEquals(Screen.Agents, flows.screen)
        assertFalse(flows.back())
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun homeOpensSetup() {
        assertEquals(Screen.Agents, flows.screen)
        flows.openMachines()
        assertEquals(Screen.Machines, flows.screen)
        assertTrue(flows.back())
        assertEquals(Screen.Agents, flows.screen)
        // The empty home returns to its caller after step 1.
        flows.openAddMachine()
        assertEquals(Screen.Setup, flows.screen)
        assertTrue(flows.back())
        assertEquals(Screen.Agents, flows.screen)
    }

    @Test
    fun backCancelsCompare() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        flows.compareFingerprint()
        assertTrue(flows.back())
        assertNull(flows.trusting)
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(listOf("add workstation workstation.example.org 22 dev"), hub.calls)
    }

    // Remove machine (owner approved, 2026-11-02): the card's action asks first;
    // only the confirm's Remove calls the hub, Cancel, back and the scrim call nothing.

    @Test
    fun removeAsksFirstAndRemovesOnlyAfterTheConfirm() {
        flows.openMachines()
        publish(machine("workstation", MachineState.Connecting), machine("server", MachineState.Connecting))
        val actions = flows.actions(MachinesActions.Preview)
        actions.onRemove("server")
        assertEquals("server", flows.removing)
        assertTrue(hub.calls.isEmpty())

        actions.onConfirmRemove()
        assertEquals(listOf("remove server"), hub.calls)
        assertNull(flows.removing)
        assertNull(flows.error)
        assertEquals(Screen.Machines, flows.screen)
    }

    @Test
    fun cancellingTheRemoveConfirmCallsNothing() {
        flows.openMachines()
        publish(machine("workstation", MachineState.Connecting))
        val actions = flows.actions(MachinesActions.Preview)
        actions.onRemove("workstation")
        actions.onCancelRemove()
        assertNull(flows.removing)
        // A late Remove after Cancel does nothing either.
        flows.remove()
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun backClosesTheRemoveConfirmWithoutRemoving() {
        flows.openMachines()
        publish(machine("workstation", MachineState.Connecting))
        flows.askRemove("workstation")
        assertTrue(flows.back())
        assertNull(flows.removing)
        assertEquals(Screen.Machines, flows.screen)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun aRefusedRemoveClosesTheConfirmAndKeepsTheHubsReason() {
        flows.openMachines()
        publish(machine("workstation", MachineState.Connecting))
        flows.askRemove("workstation")
        hub.refuse = HubException.Storage("Could not save the machine list: disk full")
        flows.remove()
        assertNull(flows.removing)
        assertEquals("Could not save the machine list: disk full", flows.error)
        assertTrue(hub.calls.isEmpty())
        // Asking again starts clean.
        flows.askRemove("workstation")
        assertNull(flows.error)
    }

    @Test
    fun theRemoveConfirmClosesWhenTheMachineIsGone() {
        flows.openMachines()
        publish(machine("workstation", MachineState.Connecting))
        flows.askRemove("workstation")
        publish()
        assertNull(flows.removing)
        flows.remove()
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun anIpv6HostIsBracketedBeforeThePort() {
        val s = publish(machine("server", unconfirmed, host = "2001:db8::10", port = 2200u))
        assertEquals("[2001:db8::10]:2200", hostKeyPrompt(s, "server")!!.address)
    }

    // Before 3.3 these tests cannot compile: Setup, setupStep, input, openSetup,
    // changeAddress, failure and retry do not exist. No Gradle run is permitted.
    @Test fun stateSteps() {
        flows.openAddMachine()
        assertEquals(1, flows.setupStep)
        for ((state, pin, step) in listOf(
            Triple(MachineState.Paused, null, 1),
            Triple(unconfirmed, null, 2),
            Triple(MachineState.KeyRejected, TRUSTED, 2),
            Triple(MachineState.Connected("1.0"), TRUSTED, 3),
        )) {
            flows.openMachines()
            publish(machine("workstation", state).copy(hostKey = pin))
            flows.openSetup("workstation")
            assertEquals(step, flows.setupStep)
            assertEquals("workstation.example.org", flows.input.host)
            assertEquals("dev", flows.input.user)
            assertEquals("22", flows.input.port)
        }
    }

    @Test fun continueWaits() {
        flows.openAddMachine()
        flows.save(workstation)
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(1, flows.setupStep)
        assertEquals("workstation", flows.waitingFor)
        publish(machine("workstation", MachineState.Connecting))
        assertEquals(1, flows.setupStep)
        publish(machine("workstation", unconfirmed))
        assertEquals(2, flows.setupStep)
        assertNull(flows.trusting)
        assertTrue(hub.calls.none { it.startsWith("confirm") })
    }

    @Test fun failureStays() {
        flows.openAddMachine()
        flows.save(workstation)
        val state = MachineState.Unreachable(ReachKind.NO_ANSWER, "timeout")
        val s = publish(machine("workstation", state))
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(1, flows.setupStep)
        assertEquals(state, flows.failure(s))
        assertNull(flows.waitingFor)
    }

    @Test fun unpinnedReplacement() {
        publish(machine("workstation", MachineState.Unreachable(ReachKind.REFUSED, "refused")))
        flows.openSetup("workstation")
        assertFalse(flows.nameLocked)
        flows.save(ValidMachine("server", "server.example.org", 2200u, "other"))
        assertEquals(listOf("remove workstation", "add server server.example.org 2200 other"), hub.calls)
        assertEquals("server", flows.machine)
    }

    // Before the edit, save turns an empty Name into an explicit old name,
    // so changing the address cannot derive the replacement's name.
    @Test fun derivedNameFollowsEditedAddress() {
        flows.openAddMachine()
        flows.changeInput(MachineInput(host = workstation.host, user = "dev"))
        flows.submit()
        publish(machine("workstation", MachineState.Unreachable(ReachKind.NO_ANSWER, "timeout")))
        flows.changeInput(flows.input.copy(host = "server.example.org"))
        flows.submit()
        assertEquals(listOf(
            "add workstation workstation.example.org 22 dev", "remove workstation",
            "add server server.example.org 22 dev",
        ), hub.calls)
        assertEquals("server", flows.machine)
    }

    @Test fun returningUnpinnedNameFollowsAddress() {
        publish(machine("workstation", MachineState.Unreachable(ReachKind.NO_ANSWER, "timeout")))
        flows.openSetup("workstation")
        flows.changeInput(flows.input.copy(host = "server.example.org"))
        flows.submit()
        assertEquals("add server server.example.org 22 dev", hub.calls.last())
    }

    @Test fun validationExcludesOwnName() {
        publish(machine("workstation", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        flows.openSetup("workstation")
        flows.changeAddress()
        flows.changeInput(flows.input.copy(user = "other"))
        assertTrue(flows.submit().ok)
        assertEquals(listOf("update workstation"), hub.calls)
    }

    @Test fun invalidInputCallsNothing() {
        flows.openAddMachine()
        flows.changeInput(MachineInput(port = "70000"))
        val errors = flows.submit()
        assertNotNull(errors.host)
        assertNotNull(errors.port)
        assertTrue(hub.calls.isEmpty())
    }

    private fun pinnedUpdate(state: MachineState) {
        // The Kotlin seam must select updateMachine, whose native pin semantics
        // are covered by the Rust hub tests (2.4), rather than remove/add.
        publish(machine("workstation", state).copy(hostKey = TRUSTED, lastConnectedMs = null))
        flows.openSetup("workstation")
        flows.changeAddress()
        assertTrue(flows.nameLocked)
        flows.changeInput(flows.input.copy(name = "renamed", user = "other"))
        assertEquals("workstation", flows.input.name)
        flows.save(ValidMachine("renamed", workstation.host, workstation.port, "other"))
        assertEquals(listOf("update workstation"), hub.calls)
        assertEquals(listOf(listOf("workstation", workstation.host, "22", "other")), hub.updates)
        assertEquals("workstation", flows.machine)
        // Replay the hub's next result: A remains the trusted pin, B mismatches.
        publish(machine("workstation", changed).copy(hostKey = TRUSTED))
        assertEquals(Screen.HostKeyChanged, flows.screen)
        assertEquals(TRUSTED, hostKeyChange(hubHost.snapshot.value, flows.machine)!!.trusted)
        assertEquals(PRESENTED, hostKeyChange(hubHost.snapshot.value, flows.machine)!!.presented)
        assertNull(flows.trusting)
    }

    @Test fun keyRejectedPin() = pinnedUpdate(MachineState.KeyRejected)
    @Test fun missingCorePin() = pinnedUpdate(MachineState.NotInstalled)

    @Test fun previouslyConnectedUpdates() {
        publish(machine("workstation", MachineState.Paused).copy(lastConnectedMs = 1uL))
        flows.openSetup("workstation")
        assertTrue(flows.nameLocked)
        flows.save(workstation.copy(name = "renamed"))
        assertEquals(listOf("update workstation"), hub.calls)
        assertEquals("workstation", flows.machine)
    }

    @Test fun hostAndPortEditsUseUpdate() {
        for (valid in listOf(workstation.copy(host = "server.example.org"), workstation.copy(port = 2200u))) {
            hub.calls.clear()
            publish(machine("workstation", MachineState.KeyRejected).copy(hostKey = TRUSTED))
            flows.openSetup("workstation")
            flows.changeAddress()
            flows.save(valid)
            assertEquals(listOf("update workstation"), hub.calls)
            assertEquals(listOf("workstation", valid.host, valid.port.toString(), valid.user), hub.updates.last())
            // Native update clears A for either endpoint change; comparison is required again.
            publish(machine("workstation", unconfirmed).copy(host = valid.host, port = valid.port))
            flows.compareFingerprint()
            assertEquals(PRESENTED, flows.trustPrompt(hubHost.snapshot.value)!!.fingerprint)
        }
    }

    @Test fun dialogRequired() {
        val s = publish(machine("workstation", unconfirmed))
        flows.openSetup("workstation")
        flows.trust(hostKeyPrompt(s, "workstation")!!)
        assertTrue(hub.calls.isEmpty())
        flows.compareFingerprint()
        val old = flows.trustPrompt(s)!!
        val next = publish(machine("workstation", MachineState.HostKeyUnconfirmed(OTHER, "ECDSA")))
        assertEquals(OTHER, flows.trustPrompt(next)!!.fingerprint)
        flows.trust(old)
        assertTrue(hub.calls.isEmpty())
        flows.trust(flows.trustPrompt(next)!!)
        assertEquals(listOf("confirm workstation $OTHER"), hub.calls)
        assertEquals(2, flows.setupStep)
    }

    @Test fun hubRetrySnapshots() {
        val state = MachineState.Unreachable(ReachKind.CORE_STARTUP_CLOSED, "closed")
        val m = machine("workstation", state).copy(hostKey = TRUSTED)
        publish(m)
        flows.openSetup("workstation")
        for ((pending, deadline) in listOf(true to 10uL, true to null, false to 20uL)) {
            val s = publish(m.copy(startupRetryPending = pending, nextRetryMs = deadline))
            assertEquals(if (pending) null else state, flows.failure(s))
            assertEquals(2, flows.setupStep)
        }
    }

    @Test fun repeatedAndSkippedSnapshots() {
        val state = MachineState.Unreachable(ReachKind.CORE_STARTUP_CLOSED, "closed")
        val m = machine("workstation", state).copy(hostKey = TRUSTED)
        publish(m)
        flows.openSetup("workstation")
        repeat(3) { assertNull(flows.failure(publish(m.copy(startupRetryPending = true)))) }
        repeat(3) { assertEquals(state, flows.failure(publish(m))) }
        // A collector may miss the entire grace interval.
        flows.openMachines()
        flows.openSetup("workstation")
        assertEquals(state, flows.failure(publish(m)))
    }

    @Test fun firstOutcomesShow() {
        val outcomes = listOf(
            MachineState.NetworkOff, MachineState.NeedsUserAuth, MachineState.KeyRejected,
            MachineState.NotInstalled, MachineState.TooOld("0.1", "1.0"), MachineState.AppTooOld,
            MachineState.Unreachable(ReachKind.NO_ANSWER, "timeout"),
            MachineState.Unreachable(ReachKind.REFUSED, "refused"),
            MachineState.Unreachable(ReachKind.NAME_NOT_FOUND, "lookup"),
            MachineState.Unreachable(ReachKind.NO_ROUTE, "route"),
            MachineState.Unreachable(ReachKind.OTHER, "other"),
        )
        for (state in outcomes) {
            flows.openMachines()
            val s = publish(machine("workstation", state).copy(hostKey = TRUSTED, startupRetryPending = true))
            flows.openSetup("workstation")
            assertEquals(state, flows.failure(s))
            assertEquals(Screen.Setup, flows.screen)
        }
        val s = publish(machine("workstation", unconfirmed))
        flows.openSetup("workstation")
        flows.compareFingerprint()
        assertNotNull(flows.trustPrompt(s))
        publish(machine("workstation", changed).copy(nextRetryMs = null))
        assertEquals(Screen.HostKeyChanged, flows.screen)
        flows.askForget()
        flows.forget()
        assertEquals(listOf("forget workstation"), hub.calls)
    }

    private fun immediate(state: MachineState) {
        val s = publish(machine("workstation", state).copy(hostKey = TRUSTED, startupRetryPending = true))
        flows.openSetup("workstation")
        assertEquals(state, flows.failure(s))
    }

    @Test fun networkOffImmediate() = immediate(MachineState.NetworkOff)
    @Test fun authImmediate() = immediate(MachineState.NeedsUserAuth)
    @Test fun rejectionImmediate() = immediate(MachineState.KeyRejected)
    @Test fun missingImmediate() = immediate(MachineState.NotInstalled)
    @Test fun versionImmediate() = immediate(MachineState.TooOld("0.1", "1.0"))
    @Test fun appVersionImmediate() = immediate(MachineState.AppTooOld)
    @Test fun reachImmediate() = immediate(MachineState.Unreachable(ReachKind.OTHER, "failed"))

    @Test fun interruptionDoesNotRearm() {
        val state = MachineState.Unreachable(ReachKind.CORE_STARTUP_CLOSED, "closed")
        val m = machine("workstation", state).copy(hostKey = TRUSTED)
        publish(m.copy(startupRetryPending = true))
        flows.openSetup("workstation")
        assertNull(flows.failure(hubHost.snapshot.value))
        publish(m.copy(state = MachineState.Paused))
        assertEquals(state, flows.failure(publish(m)))
        flows.retry()
        assertEquals("retry", hub.calls.last())
        assertEquals(state, flows.failure(publish(m)))
        val restored = restore()
        assertEquals(state, restored.failure(publish(m)))
        // Restart has the same default-false hub flag; UI state grants nothing.
        assertEquals(state, flows.failure(publish(m.copy(nextRetryMs = null))))
    }

    @Test fun connectedAndDrop() {
        publish(machine("workstation", unconfirmed))
        flows.openSetup("workstation")
        publish(machine("workstation", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        publish(machine("workstation", MachineState.Connected("1.0")).copy(hostKey = TRUSTED))
        assertEquals(3, flows.setupStep)
        publish(machine("workstation", MachineState.Unreachable(ReachKind.OTHER, "dropped")))
        assertEquals(3, flows.setupStep)
        assertEquals(Screen.Setup, flows.screen)
    }

    @Test fun connectedAutomatically() {
        publish(machine("workstation", unconfirmed))
        flows.openSetup("workstation")
        publish(machine("workstation", MachineState.Connected("1.0")))
        assertEquals(3, flows.setupStep)
    }

    @Test fun connectedAfterHubRetry() {
        val m = machine("workstation", MachineState.Unreachable(ReachKind.CORE_STARTUP_CLOSED, "closed"))
            .copy(hostKey = TRUSTED, startupRetryPending = true, nextRetryMs = 10uL)
        publish(m)
        flows.openSetup("workstation")
        assertNull(flows.failure(hubHost.snapshot.value))
        publish(m.copy(nextRetryMs = null))
        assertEquals(2, flows.setupStep)
        publish(m.copy(state = MachineState.Connected("1.0"), startupRetryPending = false))
        assertEquals(3, flows.setupStep)
    }

    @Test fun backEachStep() {
        flows.openAddMachine()
        flows.changeInput(MachineInput(host = "typed", user = "dev"))
        flows.back()
        assertEquals(Screen.Agents, flows.screen)
        assertTrue(hub.calls.isEmpty())
        flows.openMachines()
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", MachineState.Unreachable(ReachKind.NO_ANSWER, "timeout")))
        flows.back()
        assertEquals("remove workstation", hub.calls.last())
        assertEquals(Screen.Machines, flows.screen)
        for (state in listOf(unconfirmed, MachineState.Connected("1.0"))) {
            hub.calls.clear()
            publish(machine("workstation", state))
            flows.openSetup("workstation")
            flows.back()
            assertEquals(Screen.Machines, flows.screen)
            assertTrue(hub.calls.isEmpty())
        }
    }

    private fun restore(): MachineFlows {
        val saver = MachineFlows.saver(hubHost)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        return saver.restore(with(saver) { scope.save(flows) }!!)!!
    }

    @Test fun restoreEachStep() {
        flows.openAddMachine()
        val typed = MachineInput("", "workstation.example.org", "2200", "dev")
        flows.changeInput(typed)
        assertEquals(typed, restore().input)
        assertEquals(1, restore().setupStep)
        flows.save(workstation)
        var restored = restore()
        restored.onSnapshot(publish(machine("workstation", MachineState.Unreachable(ReachKind.NO_ANSWER, "timeout"))))
        restored.back()
        assertEquals("remove workstation", hub.calls.last())
        for (state in listOf(unconfirmed, MachineState.Connected("1.0"))) {
            publish(machine("workstation", state))
            flows.openSetup("workstation")
            restored = restore()
            assertEquals(flows.setupStep, restored.setupStep)
            assertEquals("workstation", restored.machine)
            restored.onSnapshot(publish(machine("workstation", MachineState.Connecting)))
            assertEquals(flows.setupStep, restored.setupStep)
        }
    }

    @Test fun alertKeepsMachine() {
        flows.openAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        flows.openAgents()
        assertEquals(Screen.Agents, flows.screen)
        assertTrue(hub.calls.none { it.startsWith("remove") })
        assertEquals("workstation", hubHost.snapshot.value.machines.single().name)
        flows.openSetup("workstation")
        assertEquals(2, flows.setupStep)
    }

    @Test fun cardRows() {
        publish(machine("workstation", unconfirmed))
        val actions = flows.actions(MachinesActions.Preview)
        actions.onHostKey("workstation")
        assertEquals(2, flows.setupStep)
        assertNull(flows.trusting)
        actions.onAddPhoneKey("workstation")
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(2, flows.setupStep)
    }

    private companion object {
        // Invented fingerprints.
        const val TRUSTED = "SHA256:Zt7oP1qW3eR5tY7uI9oP2aS4dF6gH8jK0lZ2xC4vB6n"
        const val PRESENTED = "SHA256:q3Vb8m2N4sR7tK0yL9wE1aZ5cX6vB3nM8jH2gF4dS0p"
        const val OTHER = "SHA256:Hk2mR8vT1nB6xQ4wZ9cL3pJ7sD5fG0aY2uE8iO6tN1r"
    }
}
