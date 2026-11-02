package dev.herdr.android.ui.flows

import dev.herdr.android.hub.FakeHub
import dev.herdr.android.hub.HubHost
import dev.herdr.android.ui.addmachine.ValidMachine
import dev.herdr.android.ui.hostkey.hostKeyChange
import dev.herdr.android.ui.hostkey.hostKeyPrompt
import dev.herdr.android.ui.machines.MachinesActions
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.HubException
import uniffi.herdr_mobile.HubListener
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView

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
    fun addMachineOpensTheFormAndSavingAddsThroughTheHub() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        assertEquals(Screen.AddMachine, flows.screen)

        flows.save(workstation)
        assertEquals(listOf("add workstation workstation.example.org 22 dev"), hub.calls)
        assertEquals("workstation", flows.waitingFor)
        assertNull(flows.trusting)
    }

    @Test
    fun theConfirmOpensOverTheFormOnceTheHubReportsTheUnconfirmedKey() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", MachineState.Connecting))
        assertNull(flows.trusting)

        val s = publish(machine("workstation", unconfirmed))
        assertEquals("workstation", flows.trusting)
        assertEquals(Screen.AddMachine, flows.screen)
        val prompt = flows.trustPrompt(s)!!
        assertEquals(PRESENTED, prompt.fingerprint)
        assertEquals("ED25519", prompt.algorithm)
        assertEquals("workstation.example.org:22", prompt.address)
    }

    @Test
    fun trustConfirmsExactlyThePresentedFingerprintThenShowsMachines() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        val s = publish(machine("workstation", unconfirmed))

        flows.trust(flows.trustPrompt(s)!!)
        assertEquals(
            listOf("add workstation workstation.example.org 22 dev", "confirm workstation $PRESENTED"),
            hub.calls,
        )
        assertNull(flows.trusting)
        assertEquals(Screen.Machines, flows.screen)
    }

    @Test
    fun cancelTrustsNothingAndTheMachineStaysUnconfirmed() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        hub.calls.clear()

        flows.cancelTrust()
        assertEquals(emptyList<String>(), hub.calls)
        assertNull(flows.trusting)
        assertEquals(Screen.Machines, flows.screen)
        // Later snapshots do not bring the dialog back by themselves.
        publish(machine("workstation", unconfirmed))
        assertNull(flows.trusting)
    }

    @Test
    fun theMachinesCardOpensTheConfirmForAnUnconfirmedKey() {
        flows.openMachines()
        val s = publish(machine("workstation", unconfirmed))
        flows.actions(MachinesActions.Preview).onHostKey("workstation")
        assertEquals(Screen.Machines, flows.screen)
        assertEquals("workstation", flows.trusting)
        assertEquals(PRESENTED, flows.trustPrompt(s)!!.fingerprint)

        flows.cancelTrust()
        assertEquals(Screen.Machines, flows.screen)
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
    fun aTrustDialogClosesWhenTheMachineNoLongerWaitsForIt() {
        publish(machine("workstation", unconfirmed))
        flows.actions(MachinesActions.Preview).onHostKey("workstation")
        publish(machine("workstation", changed))
        assertNull(flows.trusting)
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun anOutcomeOtherThanTheHostKeyReturnsToMachines() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", MachineState.Unreachable("connection refused")))
        assertEquals(Screen.Machines, flows.screen)
        assertNull(flows.waitingFor)
        assertNull(flows.trusting)
    }

    @Test
    fun thePendingConfirmComesBackAfterTheAppWasSuspendedInTheBackground() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        assertEquals("workstation", flows.trusting)

        // Backgrounded for 60 s: the hub closes every connection.
        publish(machine("workstation", MachineState.Paused))
        assertNull(flows.trusting)
        // The form keeps waiting for its machine: its own saved name is no conflict.
        assertEquals(Screen.AddMachine, flows.screen)
        assertEquals("workstation", flows.waitingFor)

        // Back in the foreground: the machine reconnects and presents its key again.
        publish(machine("workstation", MachineState.Connecting))
        assertNull(flows.trusting)
        val s = publish(machine("workstation", unconfirmed))
        assertEquals("workstation", flows.trusting)
        assertEquals(Screen.AddMachine, flows.screen)
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
        assertEquals(Screen.Machines, flows.screen)
    }

    @Test
    fun theConfirmAfterASuspendShowsTheKeyPresentedNowAndConfirmsOnlyThatOne() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        publish(machine("workstation", MachineState.Paused))
        val s = publish(machine("workstation", MachineState.HostKeyUnconfirmed(OTHER, "ECDSA")))
        val prompt = flows.trustPrompt(s)!!
        assertEquals(OTHER, prompt.fingerprint)
        assertEquals("ECDSA", prompt.algorithm)
        flows.trust(prompt)
        assertEquals("confirm workstation $OTHER", hub.calls.last())
        assertTrue(hub.calls.none { it == "confirm workstation $PRESENTED" })
    }

    @Test
    fun aPendingMachineThatEndsElsewhereAfterASuspendReturnsToMachines() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        publish(machine("workstation", MachineState.Paused))
        publish(machine("workstation", MachineState.Unreachable("connection refused")))
        assertEquals(Screen.Machines, flows.screen)
        assertNull(flows.waitingFor)
        assertNull(flows.trusting)
        assertTrue(hub.calls.none { it.startsWith("confirm") })
    }

    @Test
    fun aMachineCardConfirmClosedBySuspendDoesNotReopenByItself() {
        // Only the form's pending machine comes back; a card's dialog is reopened from the card.
        flows.openMachines()
        publish(machine("workstation", unconfirmed))
        flows.actions(MachinesActions.Preview).onHostKey("workstation")
        publish(machine("workstation", MachineState.Paused))
        publish(machine("workstation", unconfirmed))
        assertNull(flows.trusting)
        assertEquals(Screen.Machines, flows.screen)
    }

    @Test
    fun aRefusedAddStaysOnTheFormWithTheReason() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        hub.refuse = HubException.Storage("Could not save the machine list: disk full.")
        flows.save(workstation)
        assertEquals(Screen.AddMachine, flows.screen)
        assertNull(flows.waitingFor)
        assertEquals("Could not save the machine list: disk full.", flows.error)
    }

    @Test
    fun aRefusedConfirmKeepsTheDialogWithTheReason() {
        val s = publish(machine("workstation", unconfirmed))
        flows.actions(MachinesActions.Preview).onHostKey("workstation")
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
    fun thePhoneKeyLineOpensFromTheCardAndFromThePhoneKeyRow() {
        val actions = flows.actions(MachinesActions.Preview)
        actions.onAddPhoneKey("website")
        assertEquals(Screen.PhoneKey, flows.screen)
        assertEquals("website", flows.machine)
        assertTrue(flows.back())

        actions.onPhoneKey()
        assertEquals(Screen.PhoneKey, flows.screen)
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
    fun theAgentsHomeIsTheStartAndReachesMachinesAndAddMachine() {
        assertEquals(Screen.Agents, flows.screen)
        flows.openMachines()
        assertEquals(Screen.Machines, flows.screen)
        assertTrue(flows.back())
        assertEquals(Screen.Agents, flows.screen)
        // The empty home's "Add machine" opens the form; back from it lands on Machines.
        flows.openAddMachine()
        assertEquals(Screen.AddMachine, flows.screen)
        assertTrue(flows.back())
        assertEquals(Screen.Machines, flows.screen)
    }

    @Test
    fun backFromTheConfirmOverTheFormIsCancel() {
        flows.actions(MachinesActions.Preview).onAddMachine()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        assertTrue(flows.back())
        assertNull(flows.trusting)
        assertEquals(Screen.Machines, flows.screen)
        assertEquals(listOf("add workstation workstation.example.org 22 dev"), hub.calls)
    }

    @Test
    fun anIpv6HostIsBracketedBeforeThePort() {
        val s = publish(machine("server", unconfirmed, host = "2001:db8::10", port = 2200u))
        assertEquals("[2001:db8::10]:2200", hostKeyPrompt(s, "server")!!.address)
    }

    private companion object {
        // Invented fingerprints.
        const val TRUSTED = "SHA256:Zt7oP1qW3eR5tY7uI9oP2aS4dF6gH8jK0lZ2xC4vB6n"
        const val PRESENTED = "SHA256:q3Vb8m2N4sR7tK0yL9wE1aZ5cX6vB3nM8jH2gF4dS0p"
        const val OTHER = "SHA256:Hk2mR8vT1nB6xQ4wZ9cL3pJ7sD5fG0aY2uE8iO6tN1r"
    }
}
