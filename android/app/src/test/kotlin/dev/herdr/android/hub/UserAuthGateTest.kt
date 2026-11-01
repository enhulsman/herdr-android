package dev.herdr.android.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView

// machine-connection spec, "The phone key is hardware-bound and needs the
// owner" ("Opening from an alert": one prompt, then the connection; "Unlocked
// phone in other hands": no connection without the owner). design.md: when the
// window has passed, signing reports NeedsUserAuth, the app shows the system
// prompt and connects again; one prompt opens the window for every machine,
// and Hub.retry() tries every waiting machine again.
class UserAuthGateTest {
    private var retries = 0
    private val gate = UserAuthGate(retry = { retries++ })
    private val host = FakePromptHost()

    @Test
    fun aMachineNeedingAuthInTheForegroundGetsOnePromptThenOneRetry() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        assertEquals(1, host.shown)
        assertEquals(0, retries)
        host.finish(success = true)
        assertEquals(1, retries)
        assertEquals(1, host.shown)
    }

    @Test
    fun moreSnapshotsWhileThePromptIsUpDoNotStackPrompts() {
        gate.onHost(host)
        repeat(3) { gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth)) }
        assertEquals(1, host.shown)
    }

    @Test
    fun cancellingRetriesNothingAndDoesNotPromptAgainByItself() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        host.finish(success = false)
        repeat(3) { gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth)) }
        // The device-credential screen stops and restarts the activity.
        gate.onHost(null)
        gate.onHost(host)
        assertEquals(1, host.shown)
        assertEquals(0, retries)
        assertTrue(gate.needsUserAuth)
    }

    @Test
    fun afterACancelTheUserCanAskForThePromptAgain() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        host.finish(success = false)
        assertTrue(gate.unlock())
        assertEquals(2, host.shown)
        host.finish(success = true)
        assertEquals(1, retries)
    }

    @Test
    fun twoMachinesNeedingAuthShareOnePromptAndOneRetry() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth, "server" to MachineState.NeedsUserAuth))
        assertEquals(1, host.shown)
        host.finish(success = true)
        // Hub.retry() restarts every machine that is not connected.
        assertEquals(1, retries)
    }

    @Test
    fun aSecondMachineFallingIntoNeedsUserAuthWhileThePromptIsUpIsCoveredByTheSameRetry() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth, "server" to MachineState.Connecting))
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth, "server" to MachineState.NeedsUserAuth))
        host.finish(success = true)
        assertEquals(1, host.shown)
        assertEquals(1, retries)
    }

    @Test
    fun inTheBackgroundNothingPromptsUntilAnActivityIsShown() {
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        assertEquals(0, host.shown)
        gate.onHost(host)
        assertEquals(1, host.shown)
    }

    @Test
    fun noMachineNeedingAuthMeansNoPrompt() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.Connecting, "server" to MachineState.KeyRejected))
        assertEquals(0, host.shown)
        assertFalse(gate.needsUserAuth)
    }

    @Test
    fun aRetryThatStillNeedsAuthDoesNotLoopThePrompt() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        host.finish(success = true)
        // The snapshot from before the retry, then the retried attempt failing again.
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        gate.onSnapshot(snapshot("workstation" to MachineState.Connecting))
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        assertEquals(1, host.shown)
        assertEquals(1, retries)
        assertTrue(gate.needsUserAuth)
    }

    @Test
    fun onceConnectedALaterExpiryPromptsAgain() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        host.finish(success = true)
        gate.onSnapshot(snapshot("workstation" to MachineState.Connecting))
        gate.onSnapshot(snapshot("workstation" to MachineState.Connected("0.1.16", false)))
        // Half an hour later the link drops and the reconnect needs the owner.
        gate.onSnapshot(snapshot("workstation" to MachineState.Unreachable("no answer for 45 s")))
        gate.onSnapshot(snapshot("workstation" to MachineState.Connecting))
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        assertEquals(2, host.shown)
    }

    @Test
    fun afterACancelASuspendAndResumePromptsOnceMore() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        host.finish(success = false)
        gate.onHost(null)
        // 60 s in the background: the hub suspends every machine.
        gate.onSnapshot(snapshot("workstation" to MachineState.Paused))
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.Connecting))
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        assertEquals(2, host.shown)
    }

    @Test
    fun unlockWithoutAnActivityOrWhileThePromptIsUpDoesNothing() {
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        assertFalse(gate.unlock())
        gate.onHost(host)
        assertEquals(1, host.shown)
        assertFalse(gate.unlock())
        assertEquals(1, host.shown)
    }

    @Test
    fun aPromptEndingAfterItsActivityLeftStillCounts() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        gate.onHost(null)
        host.finish(success = true)
        assertEquals(1, retries)
    }

    @Test
    fun aSecondResultFromTheSamePromptIsIgnored() {
        gate.onHost(host)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        val result = host.pending!!
        result(true)
        result(true)
        assertEquals(1, retries)
    }
}

class FakePromptHost : PromptHost {
    var shown = 0
    var pending: ((Boolean) -> Unit)? = null

    override fun showPrompt(onResult: (Boolean) -> Unit) {
        shown++
        pending = onResult
    }

    fun finish(success: Boolean) {
        val result = checkNotNull(pending) { "no prompt is showing" }
        pending = null
        result(success)
    }
}

fun snapshot(vararg machines: Pair<String, MachineState>) = HubSnapshot(
    machines = machines.map { (name, state) ->
        MachineView(name, "$name.example.org", 22u, "me", null, state, null)
    },
    agents = emptyList(),
    others = emptyList(),
    notice = null,
)
