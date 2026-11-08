package dev.herdr.android.ui.agents

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState

// The Agents home's rows from a hub snapshot (agent-list spec; task M1.4). The
// order and the sections come from Rust's attention model: these tests check
// that the screen keeps that order and splits it into sections, and every text
// a row shows: line two, the card's kind and request, the age, "as of hh:mm"
// for a machine that is not connected, the unreachable notice and the empty states.
@RunWith(RobolectricTestRunner::class)
class AgentsModelTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private fun ui(snapshot: HubSnapshot, now: Long = NOW) = agentsUi(snapshot, now, res, ::utc)

    @Test
    fun sectionsKeepTheHubsOrder() {
        val s = snapshot(
            machines = listOf(connected("workstation")),
            agents = listOf(
                row("t1", AgentAttention.NEEDS_YOU),
                row("t2", AgentAttention.ERROR),
                row("t3", AgentAttention.DONE),
                row("t4", AgentAttention.WORKING),
                row("t5", AgentAttention.WORKING),
                row("t6", AgentAttention.IDLE),
            ),
        )
        val ui = ui(s)
        // An error needs you too: it is a card at the top, in the hub's order.
        assertEquals(listOf("t1", "t2"), ui.needsYou.map { it.key.address })
        assertEquals(listOf("t3"), ui.done.map { it.key.address })
        assertEquals(listOf("t4", "t5"), ui.working.map { it.key.address })
        assertEquals(listOf("t6"), ui.idle.map { it.key.address })
        assertNull(ui.empty)
    }

    @Test
    fun lineTwoIsTheFolderThenTheMachine() {
        val ui = ui(snapshot(listOf(connected("workstation")), listOf(row("t1", AgentAttention.IDLE, folder = "website"))))
        assertEquals("website · workstation", ui.idle.single().lineTwo)
    }

    // session-names: the desk's place replaces the folder when there is one.
    @Test
    fun lineTwoIsTheDeskPlaceThenTheMachine() {
        val ui = ui(
            snapshot(
                listOf(connected("workstation")),
                listOf(row("t1", AgentAttention.IDLE, name = "Pricing page copy", folder = "website", place = "website / 2")),
                others = listOf(other("t3", folder = "api", place = "api / 1")),
            ),
        )
        assertEquals("Pricing page copy", ui.idle.single().name)
        assertEquals("website / 2 · workstation", ui.idle.single().lineTwo)
        assertEquals("api / 1 · workstation", ui.others.single().lineTwo)
    }

    @Test
    fun theTerminalHeaderShowsTheSessionAndThePlace() {
        val s = snapshot(
            listOf(connected("workstation")),
            listOf(row("t1", AgentAttention.WORKING, name = "Pricing page copy", place = "website / 2")),
        )
        val header = dev.herdr.android.ui.terminal.terminalUi(s, AgentKey("workstation", "t1"), res).header
        assertEquals("Pricing page copy", header.name)
        assertEquals("website / 2 · workstation", header.lineTwo)
    }

    @Test
    fun aCardShowsTheKindAboveTheRequest() {
        val blocked = row("t1", AgentAttention.NEEDS_YOU, label = "Permission: Run npm test?", kind = "Permission", request = "Run npm test?")
        val bare = row("t2", AgentAttention.NEEDS_YOU, label = "Needs you", kind = "Needs you", request = null)
        val ui = ui(snapshot(listOf(connected("workstation")), listOf(blocked, bare)))
        assertEquals("Permission", ui.needsYou[0].requestKind)
        assertEquals("Run npm test?", ui.needsYou[0].request)
        assertEquals("Permission: Run npm test?", ui.needsYou[0].label)
        assertEquals("Needs you", ui.needsYou[1].requestKind)
        assertNull(ui.needsYou[1].request)
    }

    @Test
    fun progressIsAFractionOnlyWhenReported() {
        val ui = ui(
            snapshot(
                listOf(connected("workstation")),
                listOf(row("t1", AgentAttention.WORKING, progress = 40), row("t2", AgentAttention.WORKING)),
            ),
        )
        assertEquals(0.4f, ui.working[0].progress!!, 0.0001f)
        assertNull(ui.working[1].progress)
    }

    @Test
    fun theIconFollowsTheProgram() {
        assertEquals(ProgramIcon.Claude, programIcon("claude"))
        assertEquals(ProgramIcon.Codex, programIcon("codex"))
        assertEquals(ProgramIcon.Terminal, programIcon("aider"))
        assertEquals(ProgramIcon.Terminal, programIcon(null))
    }

    @Test
    fun agesReadAsInRenderOne() {
        assertEquals("<1m", ageText(0, res))
        assertEquals("<1m", ageText(59_999, res))
        assertEquals("1m", ageText(MINUTE, res))
        assertEquals("59m", ageText(59 * MINUTE + 59_000, res))
        assertEquals("1h", ageText(HOUR, res))
        assertEquals("23h", ageText(24 * HOUR - 1, res))
        assertEquals("2d", ageText(48 * HOUR, res))
        // The machine's clock ahead of the phone's: not a negative age.
        assertEquals("<1m", ageText(-5 * MINUTE, res))
    }

    @Test
    fun rowsOfAMachineThatIsNotConnectedSayAsOfWhen() {
        val s = snapshot(
            machines = listOf(
                connected("workstation"),
                unreachable("server", lastConnected = NOW - 2 * HOUR),
            ),
            agents = listOf(
                row("t1", AgentAttention.WORKING, machine = "workstation"),
                row("t9", AgentAttention.IDLE, machine = "server"),
            ),
            others = listOf(other("t4", machine = "server")),
        )
        val ui = ui(s)
        assertNull(ui.working.single().asOf)
        assertEquals("as of 06:00", ui.idle.single().asOf)
        assertEquals("as of 06:00", ui.others.single().asOf)
    }

    @Test
    fun anUnreachableMachineGetsANoticeWithItsLastConnection() {
        val s = snapshot(
            machines = listOf(
                connected("workstation"),
                unreachable("server", lastConnected = NOW - 2 * HOUR),
                machine("website", MachineState.NeedsUserAuth),
                machine("api", MachineState.KeyRejected),
                machine("docs", MachineState.Unreachable("refused"), lastConnected = null),
                machine("paused", MachineState.Paused),
                machine("connecting", MachineState.Connecting),
            ),
            agents = emptyList(),
        )
        val ui = ui(s)
        // Only unreachable machines get a line (components.machineNotice); the rest are on Machines.
        assertEquals(
            listOf(
                NoticeUi("server", "unreachable · last connected 2h ago"),
                NoticeUi("docs", "unreachable"),
            ),
            ui.notices,
        )
        assertTrue(ui.machinesBadge)
    }

    @Test
    fun aMachineWaitingForThePersonRingsTheMachinesButtonWithoutANotice() {
        val ui = ui(snapshot(listOf(connected("workstation"), machine("server", MachineState.NeedsUserAuth)), emptyList()))
        assertTrue(ui.notices.isEmpty())
        assertTrue(ui.machinesBadge)
    }

    @Test
    fun aHubThatCouldNotStartSaysWhyInsteadOfNoMachines() {
        val ui = agentsUi(snapshot(emptyList(), emptyList()), NOW, res, ::utc, startError = "the phone key could not be read: locked")
        assertEquals(AgentsEmpty.StartFailed, ui.empty)
        assertEquals("the phone key could not be read: locked", ui.startError)
    }

    @Test
    fun allConnectedMeansNoNoticeAndNoBadge() {
        val ui = ui(snapshot(listOf(connected("workstation")), listOf(row("t1", AgentAttention.IDLE))))
        assertTrue(ui.notices.isEmpty())
        assertFalse(ui.machinesBadge)
    }

    @Test
    fun otherTerminalsAreListedApart() {
        val ui = ui(
            snapshot(
                listOf(connected("workstation")),
                listOf(row("t1", AgentAttention.IDLE)),
                others = listOf(other("t3", name = "make", folder = "api", program = "make")),
            ),
        )
        val o = ui.others.single()
        assertEquals("make", o.name)
        assertEquals("api · workstation", o.lineTwo)
        assertEquals(ProgramIcon.Terminal, o.icon)
    }

    @Test
    fun withoutMachinesTheHomePointsToAddingOne() {
        assertEquals(AgentsEmpty.NoMachines, ui(snapshot(emptyList(), emptyList())).empty)
    }

    @Test
    fun machinesWithoutAgentsSaySo() {
        assertEquals(AgentsEmpty.NoAgents, ui(snapshot(listOf(connected("workstation")), emptyList())).empty)
        // Other terminals alone are not agents: the empty line stays, the group shows below it.
        val withOthers = ui(snapshot(listOf(connected("workstation")), emptyList(), others = listOf(other("t3"))))
        assertEquals(AgentsEmpty.NoAgents, withOthers.empty)
        assertEquals(1, withOthers.others.size)
    }

    @Test
    fun theSheetShowsNameMachineFolderStateAndRequest() {
        val s = snapshot(
            listOf(connected("workstation"), unreachable("server", NOW - 2 * HOUR)),
            listOf(
                row("t1", AgentAttention.NEEDS_YOU, name = "docs nav", folder = "website", kind = "Permission", request = "Bash: git push origin main", label = "Permission: Bash: git push origin main", since = NOW - 3 * MINUTE),
                row("t2", AgentAttention.WORKING, progress = 40),
                row("t9", AgentAttention.IDLE, machine = "server"),
            ),
            others = listOf(other("t3", name = "make", folder = "api")),
        )
        val ui = ui(s)
        val blocked = agentSheet(ui, AgentKey("workstation", "t1"), res)!!
        assertEquals("docs nav", blocked.name)
        assertEquals("workstation", blocked.machine)
        assertEquals("website", blocked.folder)
        assertEquals("Needs you", blocked.state)
        assertEquals(SheetTone.NeedsYou, blocked.tone)
        assertEquals("Permission", blocked.requestKind)
        assertEquals("Bash: git push origin main", blocked.request)
        assertEquals(NOW - 3 * MINUTE, blocked.sinceMs)

        assertEquals("Working · 40%", agentSheet(ui, AgentKey("workstation", "t2"), res)!!.state)
        assertEquals("as of 06:00", agentSheet(ui, AgentKey("server", "t9"), res)!!.asOf)

        val build = agentSheet(ui, AgentKey("workstation", "t3"), res)!!
        assertEquals("No status reported", build.state)
        assertEquals(SheetTone.None, build.tone)
        assertNull(build.request)

        assertNull(agentSheet(ui, AgentKey("workstation", "gone"), res))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L // 2027-01-15 08:00 UTC
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        fun utc(ms: Long): String =
            DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))
    }
}
