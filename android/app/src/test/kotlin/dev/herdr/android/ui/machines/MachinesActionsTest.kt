package dev.herdr.android.ui.machines

import dev.herdr.android.hub.FakeHub
import dev.herdr.android.hub.FakePromptHost
import dev.herdr.android.hub.HubHost
import dev.herdr.android.hub.UserAuthGate
import dev.herdr.android.hub.snapshot
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.herdr_mobile.MachineState

// The overnight run's J1 verdict: after a cancelled fingerprint prompt the app
// does not prompt again by itself, so the Machines screen's Unlock button must
// bring the prompt back (UserAuthGate.unlock), and a success retries the hub.
class MachinesActionsTest {
    private val hub = FakeHub()
    private val hubHost = HubHost(createHub = { hub }, mainExecutor = Executor { it.run() })
    private val gate = UserAuthGate(retry = hubHost::retry)
    private val prompt = FakePromptHost()
    private val actions = hubActions(gate, hubHost)

    @Test
    fun unlockAfterACancelledPromptShowsThePromptAgainAndRetriesOnSuccess() {
        hubHost.start()
        gate.onHost(prompt)
        gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
        prompt.finish(success = false)
        assertEquals(1, prompt.shown)

        actions.onUnlock()
        assertEquals(2, prompt.shown)
        prompt.finish(success = true)
        assertEquals(listOf("retry"), hub.calls)
    }

    @Test
    fun tryAgainRetriesTheHub() {
        hubHost.start()
        actions.onTryAgain()
        assertEquals(listOf("retry"), hub.calls)
    }
}
