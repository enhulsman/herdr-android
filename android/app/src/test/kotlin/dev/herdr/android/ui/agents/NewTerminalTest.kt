package dev.herdr.android.ui.agents

import dev.herdr.android.hub.FakeHub
import dev.herdr.android.hub.HubHost
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.AgentsView
import uniffi.herdr_mobile.HubException
import uniffi.herdr_mobile.HubListener
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.PlaceNote

// The "New" sheet (start-agents spec, tasks 3.2-3.4; renders 9-12): what it
// offers from the hub's choices, every text it shows, and the start itself:
// one start per tap however fast the taps, the hub's reason in the open sheet
// on a refusal, and on success the sheet closed and the new terminal handed
// to the Agents home to open. The start runs on [work], here a queue the test
// runs by hand, so a second tap lands while the first is in flight.
@RunWith(RobolectricTestRunner::class)
class NewTerminalTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val hub = FakeHub()
    private lateinit var listener: HubListener
    private val hubHost = HubHost(createHub = { listener = it; hub }, mainExecutor = Executor { it.run() })
    private val work = ArrayDeque<Runnable>()
    private val new = NewTerminal(hubHost, work = { work.addLast(it) }, main = { it.run() })

    init {
        hubHost.start()
    }

    private fun publish(s: HubSnapshot) {
        listener.onSnapshot(s)
        new.onSnapshot(s)
    }

    private fun runWork() {
        while (work.isNotEmpty()) work.removeFirst().run()
    }

    private fun ui() = newSheetUi(hubHost.snapshot.value, new.sheet!!, res)!!

    private val ws = connected("workstation", startView())

    // --- "New" and the machines ---

    @Test
    fun nothingConnectedDisablesNewAndSaysWhy() {
        publish(snapshot(listOf(unreachable("workstation", 0L)), emptyList()))
        assertFalse(newEnabled(hubHost.snapshot.value))
        new.tapNew()
        assertNull(new.sheet)
        // Render 12: the tap says why.
        assertTrue(new.noMachine)
        assertEquals("No machine connected", res.getString(dev.herdr.android.R.string.new_no_machine))
        new.noMachineShown()
        assertFalse(new.noMachine)
    }

    @Test
    fun aConnectedMachineEnablesNew() {
        publish(snapshot(listOf(unreachable("server", 0L), ws), emptyList()))
        assertTrue(newEnabled(hubHost.snapshot.value))
        new.tapNew()
        assertEquals("workstation", new.sheet?.machine)
        assertFalse(new.noMachine)
    }

    @Test
    fun oneMachineSkipsTheChoice() {
        publish(snapshot(listOf(ws, unreachable("server", 0L)), emptyList()))
        new.tapNew()
        assertNull(ui().machines)
    }

    @Test
    fun twoMachinesOfferTheChoiceFirstOneChosen() {
        publish(snapshot(listOf(ws, connected("server", startView())), emptyList()))
        new.tapNew()
        assertEquals(listOf("workstation", "server"), ui().machines)
        assertEquals("workstation", ui().machine)
    }

    @Test
    fun openingTheSheetTellsTheHubSoAFailedCheckRunsAgain() {
        publish(snapshot(listOf(ws, connected("server", startView())), emptyList()))
        new.tapNew()
        new.chooseMachine("server")
        assertEquals(listOf("sheet workstation", "sheet server"), hub.calls)
        assertEquals("server", ui().machine)
    }

    @Test
    fun theSheetClosesWhenItsMachineGoes() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        publish(snapshot(listOf(unreachable("workstation", 0L)), emptyList()))
        assertNull(new.sheet)
    }

    // --- Places and where it starts ---

    @Test
    fun spacesShowTheirFoldersUnderHomeAsTildeAndTheFirstIsChosen() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        val ui = ui()
        assertEquals("Space", ui.placesCaption)
        assertNull(ui.placesNote)
        assertEquals(listOf("website" to "~/src/website", "api" to "~/src/api"), ui.places.map { it.title to it.folder })
        assertEquals(listOf(true, false), ui.places.map { it.selected })
        // Render 9: the machine, the space and the whole folder before anything starts.
        assertEquals("Starts on workstation in website, as a new tab", ui.where.text)
        assertEquals(listOf("workstation", "website"), ui.where.bold.map { ui.where.text.substring(it) })
        assertEquals("/home/dev/src/website", ui.whereFolder)
    }

    @Test
    fun choosingAPlaceMovesWhereItStarts() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        new.choosePlace(ui().places[1].place)
        assertEquals(listOf(false, true), ui().places.map { it.selected })
        assertEquals("Starts on workstation in api, as a new tab", ui().where.text)
        assertEquals("/home/dev/src/api", ui().whereFolder)
    }

    @Test
    fun theChosenPlaceStaysChosenWhenTheListChanges() {
        // Folders: home is listed first once the check printed it.
        val before = startView(places = listOf(folder("api", "/home/dev/src/api")), home = null)
        publish(snapshot(listOf(connected("server", before)), emptyList()))
        new.tapNew()
        val after = startView(places = listOf(folder("~", "/home/dev"), folder("api", "/home/dev/src/api")))
        publish(snapshot(listOf(connected("server", after)), emptyList()))
        assertEquals(listOf(false, true), ui().places.map { it.selected })
    }

    @Test
    fun aMachineWithoutSpacesOffersFoldersAsANewSpace() {
        // Render 10.
        val view = startView(
            places = listOf(folder("~", "/home/dev"), folder("backups", "/home/dev/backups")),
            agents = AgentsView.Checking,
        )
        publish(snapshot(listOf(connected("server", view)), emptyList()))
        new.tapNew()
        val ui = ui()
        assertEquals("Folder", ui.placesCaption)
        assertEquals("no spaces on server yet, this makes the first", ui.placesNote)
        assertEquals(listOf("~" to "/home/dev", "backups" to "/home/dev/backups"), ui.places.map { it.title to it.folder })
        assertEquals("Starts on server as a new space", ui.where.text)
        assertEquals(listOf("server"), ui.where.bold.map { ui.where.text.substring(it) })
        assertEquals("/home/dev", ui.whereFolder)
    }

    @Test
    fun aSpaceFolderThatIsNotAFullPathSaysHomeInstead() {
        val view = startView(places = listOf(space("notes", "/home/dev", note = PlaceNote.HOME_INSTEAD)))
        publish(snapshot(listOf(connected("workstation", view)), emptyList()))
        new.tapNew()
        val place = ui().places.single()
        assertEquals("~", place.folder)
        assertEquals("not a full path, starts in home", place.note)
        assertEquals("/home/dev", ui().whereFolder)
    }

    @Test
    fun anUnknownHomeSaysWhereItStartsInstead() {
        val view = startView(places = listOf(space("notes", null, note = PlaceNote.HOME_UNKNOWN)), home = null)
        publish(snapshot(listOf(connected("workstation", view)), emptyList()))
        new.tapNew()
        val place = ui().places.single()
        assertEquals("~", place.folder)
        assertEquals("home folder not known yet", place.note)
        assertEquals("herdr-core's own folder", ui().whereFolder)
    }

    @Test
    fun foldersOutsideHomeStayWhole() {
        val view = startView(places = listOf(space("tmp", "/tmp/build")))
        publish(snapshot(listOf(connected("workstation", view)), emptyList()))
        new.tapNew()
        assertEquals("/tmp/build", ui().places.single().folder)
    }

    @Test
    fun aFolderThatOnlyStartsLikeHomeIsNotUnderIt() {
        val view = startView(places = listOf(space("other", "/home/devops/x")))
        publish(snapshot(listOf(connected("workstation", view)), emptyList()))
        new.tapNew()
        assertEquals("/home/devops/x", ui().places.single().folder)
    }

    // --- The agent row ---

    @Test
    fun installedAgentsAreOfferedInTheHubsOrder() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        val row = ui().agents as AgentsRowUi.Ready
        assertEquals(listOf("Claude Code", "Codex"), row.agents.map { it.name })
        assertEquals(listOf(ProgramIcon.Claude, ProgramIcon.Codex), row.agents.map { it.icon })
    }

    @Test
    fun theAgentRowHoldsItsPlaceWhileTheCheckRuns() {
        val view = startView(agents = AgentsView.Checking)
        publish(snapshot(listOf(connected("server", view)), emptyList()))
        new.tapNew()
        val row = ui().agents as AgentsRowUi.Checking
        // One row of tiles held, the height a row of found agents takes.
        assertEquals(AGENT_COLUMNS, row.slots)
        assertEquals("Checking which agents server has…", row.note)
    }

    @Test
    fun aFailedCheckOffersTerminalOnlyAndSaysSo() {
        // Render 11.
        val view = startView(agents = AgentsView.Unavailable)
        publish(snapshot(listOf(connected("workstation", view)), emptyList()))
        new.tapNew()
        assertEquals(AgentsRowUi.Unavailable("Could not check agents on workstation · Terminal only"), ui().agents)
    }

    @Test
    fun noAgentsFoundShowsTerminalAlone() {
        val view = startView(agents = ready())
        publish(snapshot(listOf(connected("workstation", view)), emptyList()))
        new.tapNew()
        assertEquals(AgentsRowUi.Ready(emptyList()), ui().agents)
    }

    // --- Starting ---

    @Test
    fun terminalStartsAtTheChosenPlace() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        new.choosePlace(ui().places[1].place)
        new.start(null)
        runWork()
        assertEquals(listOf("sheet workstation", "start workstation api terminal"), hub.calls)
    }

    @Test
    fun anAgentTileStartsItsWord() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        new.start("codex")
        runWork()
        assertEquals("start workstation website codex", hub.calls.last())
    }

    @Test
    fun aDoubleTapStartsOnce() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        new.start(null)
        // In flight: the buttons are off.
        assertTrue(ui().starting)
        new.start(null)
        new.start("claude")
        runWork()
        assertEquals(1, hub.calls.count { it.startsWith("start ") })
    }

    @Test
    fun aRefusalKeepsTheSheetOpenWithTheReason() {
        // Render 11: the hub's text for spawn_failed.
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        hub.refuse = HubException.Invalid("Could not start in /home/dev/src/website: No such file or directory")
        new.start(null)
        runWork()
        val ui = ui()
        assertEquals("Could not start in /home/dev/src/website: No such file or directory", ui.error)
        assertFalse(ui.starting)
        assertNull(new.opened.value)
        // Nothing is retried elsewhere.
        assertEquals(0, hub.calls.count { it.startsWith("start ") })
    }

    @Test
    fun aNewChoiceClearsTheReason() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        hub.refuse = HubException.Invalid("workstation is not reachable now")
        new.start(null)
        runWork()
        new.choosePlace(ui().places[1].place)
        assertNull(ui().error)
    }

    @Test
    fun theStartCanBeTriedAgainAfterARefusal() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        hub.refuse = HubException.Invalid("workstation is not reachable now")
        new.start(null)
        runWork()
        new.start(null)
        runWork()
        assertEquals(1, hub.calls.count { it.startsWith("start ") })
        assertNull(new.sheet)
    }

    @Test
    fun successClosesTheSheetAndHandsOverTheNewTerminal() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        hub.startAddress = "t42"
        new.start("claude")
        runWork()
        assertNull(new.sheet)
        assertEquals(AgentKey("workstation", "t42"), new.opened.value)
        new.openedTaken()
        assertNull(new.opened.value)
    }

    @Test
    fun aStartThatEndsAfterTheSheetClosedStillOpensItsTerminal() {
        // It started on the machine whatever the sheet does; the phone shows it.
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        new.start(null)
        new.close()
        runWork()
        assertNull(new.sheet)
        assertEquals(AgentKey("workstation", "t9"), new.opened.value)
    }

    @Test
    fun theSheetStaysWhileItsStartIsInFlightThoughTheMachineGoes() {
        publish(snapshot(listOf(ws), emptyList()))
        new.tapNew()
        new.start(null)
        publish(snapshot(listOf(machine("workstation", MachineState.Connecting)), emptyList()))
        assertNotNull(new.sheet)
        hub.refuse = HubException.Invalid("workstation is not connected")
        runWork()
        assertEquals("workstation is not connected", new.sheet?.error)
    }
}
