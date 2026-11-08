package dev.herdr.android.push

import android.content.res.Resources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.AgentRow
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView

// stay-connected spec, "The status notification shows counts only": counts
// from connected machines only ("need you" = needs you + error, "working" =
// working), the second line by how many machines are connected, and with none
// connected the title by precedence: start error, key, retrying, needs the
// app, no machines. No agent, project, folder or machine names, ever.
@RunWith(RobolectricTestRunner::class)
class StatusTextTest {
    private val res: Resources = RuntimeEnvironment.getApplication().resources

    private fun line(snapshot: HubSnapshot, startError: String? = null) = StatusText.of(snapshot, startError, res)

    @Test
    fun agentsBusyCountsNeedsYouAndErrorTogether() {
        val s = hub(
            listOf("workstation" to CONNECTED),
            agent("workstation", AgentAttention.NEEDS_YOU),
            agent("workstation", AgentAttention.NEEDS_YOU),
            agent("workstation", AgentAttention.ERROR),
            agent("workstation", AgentAttention.WORKING),
            agent("workstation", AgentAttention.WORKING),
            agent("workstation", AgentAttention.WORKING),
        )
        assertEquals(StatusLine("3 need you · 3 working", "Connected"), line(s))
    }

    @Test
    fun oneNeedsYouIsSingularAndZeroWorkingIsLeftOut() {
        val s = hub(listOf("workstation" to CONNECTED), agent("workstation", AgentAttention.NEEDS_YOU))
        assertEquals(StatusLine("1 needs you", "Connected"), line(s))
    }

    @Test
    fun onlyWorkingLeavesOutNeedYou() {
        val s = hub(
            listOf("workstation" to CONNECTED),
            agent("workstation", AgentAttention.WORKING),
            agent("workstation", AgentAttention.WORKING),
            agent("workstation", AgentAttention.WORKING),
        )
        assertEquals(StatusLine("3 working", "Connected"), line(s))
    }

    @Test
    fun idleAndFinishedAreNotCounted() {
        val s = hub(
            listOf("workstation" to CONNECTED),
            agent("workstation", AgentAttention.IDLE),
            agent("workstation", AgentAttention.DONE),
        )
        assertEquals(StatusLine("Nothing needs you", "Connected"), line(s))
    }

    @Test
    fun aConnectedMachineWithNoAgentsIsQuiet() {
        assertEquals(StatusLine("Nothing needs you", "Connected"), line(hub(listOf("workstation" to CONNECTED))))
    }

    @Test
    fun agentsOnAMachineThatIsDownAreStaleAndNotCounted() {
        val s = hub(
            listOf("workstation" to CONNECTED, "server" to MachineState.Unreachable("timed out")),
            agent("workstation", AgentAttention.WORKING),
            agent("server", AgentAttention.NEEDS_YOU),
            agent("server", AgentAttention.NEEDS_YOU),
        )
        assertEquals(StatusLine("1 working", "Connected to 1 of 2 machines"), line(s))
    }

    @Test
    fun anotherMachineWaitingForTheKeySaysUnlock() {
        val s = hub(listOf("workstation" to CONNECTED, "server" to MachineState.NeedsUserAuth))
        assertEquals(
            StatusLine("Nothing needs you", "Connected to 1 of 2 machines · unlock to reconnect"),
            line(s),
        )
    }

    @Test
    fun twoOfThreeConnected() {
        val s = hub(listOf("a" to CONNECTED, "b" to CONNECTED, "c" to MachineState.NetworkOff))
        assertEquals("Connected to 2 of 3 machines", line(s).text)
    }

    @Test
    fun noneConnectedAndOneWaitsForTheKey() {
        val s = hub(listOf("workstation" to MachineState.NeedsUserAuth, "server" to MachineState.Unreachable("x")))
        assertEquals(StatusLine("Disconnected · unlock to reconnect", null), line(s))
    }

    @Test
    fun noneConnectedAndRetrying() {
        for (state in listOf(
            MachineState.Connecting,
            MachineState.Unreachable("refused"),
            MachineState.KeyRejected,
            MachineState.NotInstalled,
            MachineState.Paused,
        )) {
            assertEquals("$state", StatusLine("Reconnecting", null), line(hub(listOf("workstation" to state))))
        }
    }

    @Test
    fun retryingOutranksNeedingTheApp() {
        val s = hub(listOf("a" to MachineState.AppTooOld, "b" to MachineState.Connecting))
        assertEquals(StatusLine("Reconnecting", null), line(s))
    }

    @Test
    fun noneConnectedAndOnlyStatesThatNeedThePerson() {
        for (state in listOf(
            MachineState.HostKeyUnconfirmed("SHA256:abc", "ED25519"),
            MachineState.HostKeyMismatch("SHA256:abc", "SHA256:def", "ED25519"),
            MachineState.TooOld("0.1.0", "0.2.0"),
            MachineState.AppTooOld,
            MachineState.NetworkOff,
        )) {
            assertEquals("$state", StatusLine("Not connected · open the app", null), line(hub(listOf("workstation" to state))))
        }
    }

    @Test
    fun noMachines() {
        assertEquals(StatusLine("No machines", null), line(hub(emptyList())))
    }

    @Test
    fun aHubThatCouldNotStartSaysOpenTheApp() {
        assertEquals(StatusLine("Not connected · open the app", null), line(hub(emptyList()), startError = "no screen lock"))
    }

    @Test
    fun noLineCarriesANameFolderOrPlace() {
        val s = hub(
            listOf("workstation" to CONNECTED, "server" to MachineState.NeedsUserAuth),
            agent("workstation", AgentAttention.NEEDS_YOU, name = "Pricing page copy"),
            agent("workstation", AgentAttention.WORKING, name = "docs nav"),
        )
        val shown = line(s).let { "${it.title} ${it.text}" }
        for (secret in listOf("workstation", "server", "Pricing", "docs", "website", "~/code")) {
            assertFalse(secret, shown.contains(secret))
        }
    }

    @Test
    fun progressTicksGiveAnEqualLine() {
        val a = hub(listOf("workstation" to CONNECTED), agent("workstation", AgentAttention.WORKING, progress = 10u))
        val b = hub(listOf("workstation" to CONNECTED), agent("workstation", AgentAttention.WORKING, progress = 80u))
        assertEquals(line(a), line(b))
    }

    @Test
    fun noneConnectedHasNoSecondLine() {
        assertNull(line(hub(listOf("workstation" to MachineState.Connecting))).text)
    }

    private companion object {
        val CONNECTED = MachineState.Connected("0.3.0")

        fun hub(machines: List<Pair<String, MachineState>>, vararg agents: AgentRow) = HubSnapshot(
            machines = machines.map { (name, state) -> MachineView(name, "$name.example.org", 22u, "me", null, state, null) },
            agents = agents.toList(),
            others = emptyList(),
            notice = null,
        )

        fun agent(machine: String, attention: AgentAttention, name: String = "agent", progress: UByte? = null) = AgentRow(
            machine = machine,
            address = "t1",
            name = name,
            attention = attention,
            label = "label",
            progress = progress,
            sinceMs = null,
            folder = "~/code/website",
            place = "website / 2",
            program = "claude",
            requestKind = null,
            request = null,
        )
    }
}
