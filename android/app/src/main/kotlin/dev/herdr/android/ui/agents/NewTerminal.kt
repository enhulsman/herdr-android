package dev.herdr.android.ui.agents

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdr.android.R
import dev.herdr.android.hub.HubHost
import dev.herdr.android.ui.flows.hubErrorText
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.herdr_mobile.AgentsView
import uniffi.herdr_mobile.HubException
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.PlaceNote
import uniffi.herdr_mobile.StartPlace
import uniffi.herdr_mobile.StartView

// The "New" sheet (start-agents spec; renders 9-12): a terminal, or an agent
// the machine has, started in a space (or a folder on a machine without
// spaces). The hub offers the choices (MachineView.start) and does the start;
// this file keeps what the person chose, runs the start off the main thread
// and works out every text the sheet shows (newSheetUi).

/** Tiles in a row of agents; the row held while the check runs is one row. */
const val AGENT_COLUMNS = 3

/** The open sheet: what the person chose, and how the last start went. */
@Immutable
data class NewSheetState(
    val machine: String,
    /** The chosen place's [placeKey]; the first when it is gone or null. */
    val place: String? = null,
    /** The hub's reason the last start was refused (design D7). */
    val error: String? = null,
    /** A start is in flight: the buttons are off. */
    val starting: Boolean = false,
)

/**
 * Process-wide (HerdrApp holds it), so a rotation while a start is in flight
 * neither loses its outcome nor lets a second start through. The start runs on
 * [work] and reports back on [main]; a new terminal is handed to the Agents
 * home through [opened].
 */
class NewTerminal(
    private val hubHost: HubHost,
    private val work: Executor,
    private val main: Executor,
) {
    var sheet by mutableStateOf<NewSheetState?>(null)
        private set

    /** "New" was tapped with nothing connected: the snackbar says why (render 12). */
    var noMachine by mutableStateOf(false)
        private set

    private val started = MutableStateFlow<AgentKey?>(null)

    /** A terminal this sheet started, until the Agents home takes it ([openedTaken]). */
    val opened: StateFlow<AgentKey?> = started.asStateFlow()

    /** The machine chosen last, chosen again while it is connected. */
    private var lastMachine: String? = null

    fun tapNew() {
        val machines = startMachines(hubHost.snapshot.value)
        if (machines.isEmpty()) {
            noMachine = true
            return
        }
        noMachine = false
        show(lastMachine?.takeIf { it in machines } ?: machines.first())
    }

    fun noMachineShown() {
        noMachine = false
    }

    fun chooseMachine(name: String) {
        val s = sheet ?: return
        if (s.starting || name == s.machine) return
        show(name)
    }

    private fun show(machine: String) {
        lastMachine = machine
        // Pinned now: the list changes under it (home is listed first once known).
        val first = startOf(hubHost.snapshot.value, machine)?.places?.firstOrNull()
        sheet = NewSheetState(machine, place = first?.let(::placeKey))
        // A check that failed runs again (start-agents spec, "The check fails").
        hubHost.hub?.startSheetOpened(machine)
    }

    fun choosePlace(place: StartPlace) {
        val s = sheet ?: return
        if (s.starting) return
        sheet = s.copy(place = placeKey(place), error = null)
    }

    /** Starts a terminal at the chosen place running the shell, or [agent] (a StartAgent.word). */
    fun start(agent: String?) {
        val s = sheet ?: return
        if (s.starting) return
        val view = startOf(hubHost.snapshot.value, s.machine) ?: return
        val place = chosen(view, s) ?: return
        val hub = hubHost.hub ?: return
        sheet = s.copy(starting = true, error = null)
        work.execute {
            val outcome = try {
                Result.success(hub.startTerminal(s.machine, place, agent))
            } catch (e: HubException) {
                Result.failure(e)
            }
            main.execute { finished(s.machine, outcome) }
        }
    }

    private fun finished(machine: String, outcome: Result<String>) {
        val s = sheet?.takeIf { it.machine == machine }
        outcome
            .onSuccess { address ->
                if (s != null) sheet = null
                started.value = AgentKey(machine, address)
            }
            .onFailure { e ->
                if (s != null) sheet = s.copy(starting = false, error = hubErrorText(e as HubException))
            }
    }

    fun openedTaken() {
        started.value = null
    }

    fun close() {
        sheet = null
    }

    /** The chosen machine is no longer connected: the sheet closes, unless its start is still to answer. */
    fun onSnapshot(snapshot: HubSnapshot) {
        val s = sheet ?: return
        if (!s.starting && startOf(snapshot, s.machine) == null) sheet = null
    }
}

/** Connected machines, whose sheet has something to offer, in the hub's order. */
private fun startMachines(snapshot: HubSnapshot): List<String> =
    snapshot.machines.filter { it.start != null }.map { it.name }

/** "New" starts something: a machine is connected. Otherwise it is dimmed and a tap says why. */
fun newEnabled(snapshot: HubSnapshot): Boolean = startMachines(snapshot).isNotEmpty()

private fun startOf(snapshot: HubSnapshot, machine: String): StartView? =
    snapshot.machines.find { it.name == machine }?.start

/** A place's identity across snapshots: the list changes as home becomes known. */
private fun placeKey(place: StartPlace): String = place.spaceId ?: place.folder ?: place.title

private fun chosen(view: StartView, s: NewSheetState): StartPlace? =
    view.places.find { placeKey(it) == s.place } ?: view.places.firstOrNull()

/** Text with some parts in bold (the machine and the space in "Starts on ..."). */
@Immutable
data class BoldText(val text: String, val bold: List<IntRange>)

@Immutable
data class PlaceUi(
    val place: StartPlace,
    val title: String,
    /** The folder, `~/...` under home for a space (render 9), whole for a folder (render 10). */
    val folder: String,
    /** Why it starts elsewhere than the space says. */
    val note: String?,
    val selected: Boolean,
)

@Immutable
data class AgentTileUi(val word: String, val name: String, val icon: ProgramIcon)

sealed interface AgentsRowUi {
    /** The check has not answered: [slots] tiles held, one row (render 10). */
    data class Checking(val note: String, val slots: Int = AGENT_COLUMNS) : AgentsRowUi

    data class Ready(val agents: List<AgentTileUi>) : AgentsRowUi

    /** The check failed: Terminal only (render 11). */
    data class Unavailable(val note: String) : AgentsRowUi
}

@Immutable
data class NewSheetUi(
    /** The machines to choose from; null with one, which skips the choice. */
    val machines: List<String>?,
    val machine: String,
    /** "Space", or "Folder" on a machine without spaces. */
    val placesCaption: String,
    /** After the caption on a machine without spaces. */
    val placesNote: String?,
    val places: List<PlaceUi>,
    /** "Starts on workstation in website, as a new tab". */
    val where: BoldText,
    /** The whole folder it starts in. */
    val whereFolder: String,
    val agents: AgentsRowUi,
    val error: String?,
    val starting: Boolean,
)

/** What the open sheet shows; null when its machine offers nothing (no longer connected). */
fun newSheetUi(snapshot: HubSnapshot, sheet: NewSheetState, res: Resources): NewSheetUi? {
    val view = startOf(snapshot, sheet.machine) ?: return null
    val machines = startMachines(snapshot)
    val chosen = chosen(view, sheet)
    val home = view.home
    val places = view.places.map { p ->
        val folder = p.folder
        PlaceUi(
            place = p,
            title = p.title,
            folder = when {
                folder == null -> "~"
                view.fromSpaces -> tilde(folder, home)
                else -> folder
            },
            note = when (p.note) {
                PlaceNote.NONE -> null
                PlaceNote.HOME_INSTEAD -> res.getString(R.string.new_place_home_instead)
                PlaceNote.HOME_UNKNOWN -> res.getString(R.string.new_place_home_unknown)
            },
            selected = p == chosen,
        )
    }
    val m = sheet.machine
    val where = if (view.fromSpaces && chosen != null) {
        bold(res.getString(R.string.new_where_space), m, chosen.title)
    } else {
        bold(res.getString(R.string.new_where_new_space), m)
    }
    val agents = when (val a = view.agents) {
        AgentsView.Checking -> AgentsRowUi.Checking(res.getString(R.string.new_agents_checking, m))
        AgentsView.Unavailable -> AgentsRowUi.Unavailable(res.getString(R.string.new_agents_unavailable, m))
        is AgentsView.Ready -> AgentsRowUi.Ready(a.agents.map { AgentTileUi(it.word, it.name, programIcon(it.program)) })
    }
    return NewSheetUi(
        machines = machines.takeIf { it.size > 1 },
        machine = m,
        placesCaption = res.getString(if (view.fromSpaces) R.string.new_space else R.string.new_folder),
        placesNote = if (view.fromSpaces) null else res.getString(R.string.new_folder_note, m),
        places = places,
        where = where,
        whereFolder = chosen?.folder ?: res.getString(R.string.new_where_core_folder),
        agents = agents,
        error = sheet.error,
        starting = sheet.starting,
    )
}

/** [folder] as `~/...` when it is under [home]. */
private fun tilde(folder: String, home: String?): String = when {
    home == null -> folder
    folder == home -> "~"
    folder.startsWith("${home.trimEnd('/')}/") -> "~/" + folder.removePrefix("${home.trimEnd('/')}/")
    else -> folder
}

/** Fills `%1$s`, `%2$s` ... in [template] with [args], each in bold. */
private fun bold(template: String, vararg args: String): BoldText {
    val out = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    var i = 0
    val slot = Regex("%(\\d)\\\$s")
    for (match in slot.findAll(template)) {
        out.append(template, i, match.range.first)
        val arg = args[match.groupValues[1].toInt() - 1]
        ranges += out.length until out.length + arg.length
        out.append(arg)
        i = match.range.last + 1
    }
    out.append(template, i, template.length)
    return BoldText(out.toString(), ranges)
}
