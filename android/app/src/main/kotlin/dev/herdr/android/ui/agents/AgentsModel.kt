package dev.herdr.android.ui.agents

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import dev.herdr.android.R
import dev.herdr.android.ui.machines.agoText
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.AgentRow
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView
import uniffi.herdr_mobile.OtherTerminal

// What the Agents home shows (renders 1 and 2), worked out from a hub snapshot
// with every text resolved. The order of the rows and what counts as needing
// you, finished, working or idle come from Rust's attention model, the single
// source of truth: this file keeps the hub's order and only splits it into
// sections. Ages are not resolved here: they tick (AgentsScreen's Age), from
// [AgentRowUi.sinceMs] and [ageText].

/** A terminal, unique across machines. */
data class AgentKey(val machine: String, val address: String)

/** The mark before a row's name (herdr-app's program icons; design/renders/icons/). */
enum class ProgramIcon { Claude, Codex, Terminal }

fun programIcon(program: String?): ProgramIcon = when (program) {
    "claude" -> ProgramIcon.Claude
    "codex" -> ProgramIcon.Codex
    else -> ProgramIcon.Terminal
}

@Immutable
data class AgentRowUi(
    val key: AgentKey,
    val name: String,
    val icon: ProgramIcon,
    val attention: AgentAttention,
    val folder: String,
    /** "website · workstation". */
    val lineTwo: String,
    /** When the state last changed, ms since the epoch; the age ticks from it. */
    val sinceMs: Long?,
    /** 0-1 when the program reported progress. */
    val progress: Float?,
    /** "as of 14:05" while its machine is not connected. */
    val asOf: String?,
    /** The card's heading ("Permission", "Needs you", "Error"); needs-you rows only. */
    val requestKind: String?,
    /** What the program asks, in terminal type under the heading. */
    val request: String?,
    /** The whole state in one line ("Permission: Run npm test?"), for screen readers. */
    val label: String,
)

@Immutable
data class OtherRowUi(
    val key: AgentKey,
    val name: String,
    val icon: ProgramIcon,
    val folder: String,
    val lineTwo: String,
    val asOf: String?,
)

/** One line above the list for a machine that is not connected: [text] follows its name. */
data class NoticeUi(val machine: String, val text: String)

enum class AgentsEmpty {
    /** No machines listed: point to adding one. */
    NoMachines,

    /** Machines, but no terminal reports agent status. */
    NoAgents,

    /** The hub could not start (HubHost.startError), so nothing can connect. */
    StartFailed,
}

@Immutable
data class AgentsUi(
    val notices: List<NoticeUi>,
    val needsYou: List<AgentRowUi>,
    val done: List<AgentRowUi>,
    val working: List<AgentRowUi>,
    val idle: List<AgentRowUi>,
    val others: List<OtherRowUi>,
    /** The Machines button's mark: a machine is not connected. */
    val machinesBadge: Boolean,
    val empty: AgentsEmpty?,
    /** A storage problem from the hub. */
    val storageNotice: String?,
    /** Why the hub could not start, as the Machines screen shows it; with [AgentsEmpty.StartFailed]. */
    val startError: String? = null,
)

/**
 * [time] formats a moment as the phone shows times ("14:05" or "2:05 PM");
 * [nowMs] dates "last connected"; [startError] is HubHost.startError.
 */
fun agentsUi(
    snapshot: HubSnapshot,
    nowMs: Long,
    res: Resources,
    time: (Long) -> String,
    startError: String? = null,
): AgentsUi {
    val machines = snapshot.machines.associateBy { it.name }
    fun asOf(machine: String): String? {
        val m = machines[machine] ?: return null
        if (m.state is MachineState.Connected) return null
        return m.lastConnectedMs?.let { res.getString(R.string.agents_as_of, time(it.toLong())) }
    }
    val rows = snapshot.agents.map { agentRow(it, asOf(it.machine), res) }
    val others = snapshot.others.map { otherRow(it, asOf(it.machine), res) }
    val notices = snapshot.machines.mapNotNull { notice(it, nowMs, res) }
    return AgentsUi(
        notices = notices,
        needsYou = rows.filter { it.attention == AgentAttention.NEEDS_YOU || it.attention == AgentAttention.ERROR },
        done = rows.filter { it.attention == AgentAttention.DONE },
        working = rows.filter { it.attention == AgentAttention.WORKING },
        idle = rows.filter { it.attention == AgentAttention.IDLE },
        others = others,
        machinesBadge = snapshot.machines.any { waits(it.state) },
        empty = when {
            startError != null -> AgentsEmpty.StartFailed
            snapshot.machines.isEmpty() -> AgentsEmpty.NoMachines
            rows.isEmpty() -> AgentsEmpty.NoAgents
            else -> null
        },
        storageNotice = snapshot.notice,
        startError = startError,
    )
}

/** Not connected and not on its way: the Machines button's ring. */
private fun waits(state: MachineState): Boolean = when (state) {
    MachineState.Paused, MachineState.Connecting, is MachineState.Connected -> false
    else -> true
}

private fun agentRow(r: AgentRow, asOf: String?, res: Resources) = AgentRowUi(
    key = AgentKey(r.machine, r.address),
    name = r.name,
    icon = programIcon(r.program),
    attention = r.attention,
    folder = r.folder,
    lineTwo = res.getString(R.string.agents_line_two, r.folder, r.machine),
    sinceMs = r.sinceMs?.toLong(),
    progress = r.progress?.let { it.toInt().coerceIn(0, 100) / 100f },
    asOf = asOf,
    requestKind = r.requestKind,
    request = r.request,
    label = r.label,
)

private fun otherRow(o: OtherTerminal, asOf: String?, res: Resources) = OtherRowUi(
    key = AgentKey(o.machine, o.address),
    name = o.name,
    icon = programIcon(o.program),
    folder = o.folder,
    lineTwo = res.getString(R.string.agents_line_two, o.folder, o.machine),
    asOf = asOf,
)

/**
 * Only an unreachable machine gets a line (components.machineNotice); other
 * states that wait for the person ring the Machines button, which says the rest.
 */
private fun notice(m: MachineView, nowMs: Long, res: Resources): NoticeUi? {
    if (m.state !is MachineState.Unreachable) return null
    val parts = buildList {
        add(res.getString(R.string.notice_unreachable))
        m.lastConnectedMs?.let {
            add(res.getString(R.string.status_last_connected, agoText(nowMs - it.toLong(), res)))
        }
    }
    return NoticeUi(m.name, parts.joinToString(res.getString(R.string.status_separator)))
}

/** "<1m", "3m", "2h", "3d" (render 1); a machine clock ahead of the phone's reads as <1m. */
fun ageText(elapsedMs: Long, res: Resources): String {
    val minutes = elapsedMs / 60_000
    return when {
        minutes < 1 -> res.getString(R.string.age_now)
        minutes < 60 -> res.getString(R.string.age_minutes, minutes.toInt())
        minutes < 24 * 60 -> res.getString(R.string.age_hours, (minutes / 60).toInt())
        else -> res.getString(R.string.age_days, (minutes / (24 * 60)).toInt())
    }
}

/** The sheet's state colour: the section's, or none for a terminal without status. */
enum class SheetTone { NeedsYou, Done, Working, Idle, None }

@Immutable
data class AgentSheetUi(
    val key: AgentKey,
    val name: String,
    val icon: ProgramIcon,
    val lineTwo: String,
    val machine: String,
    val folder: String,
    /** "Needs you", "Working · 40%", "No status reported". */
    val state: String,
    val tone: SheetTone,
    val requestKind: String?,
    val request: String?,
    val sinceMs: Long?,
    val asOf: String?,
    /** For screen readers: the state with its request. */
    val label: String,
)

/** The sheet for [key], from the rows on screen; null once the terminal is gone. */
fun agentSheet(ui: AgentsUi, key: AgentKey, res: Resources): AgentSheetUi? {
    val row = (ui.needsYou + ui.done + ui.working + ui.idle).find { it.key == key }
    if (row != null) {
        val (word, tone) = when (row.attention) {
            AgentAttention.NEEDS_YOU, AgentAttention.ERROR -> R.string.agents_needs_you to SheetTone.NeedsYou
            AgentAttention.DONE -> R.string.agents_done to SheetTone.Done
            AgentAttention.WORKING -> R.string.agents_working to SheetTone.Working
            AgentAttention.IDLE -> R.string.agents_idle to SheetTone.Idle
        }
        val state = res.getString(word).let { w ->
            row.progress?.takeIf { row.attention == AgentAttention.WORKING }
                ?.let { res.getString(R.string.sheet_progress, w, Math.round(it * 100)) } ?: w
        }
        return AgentSheetUi(
            key = key,
            name = row.name,
            icon = row.icon,
            lineTwo = row.lineTwo,
            machine = key.machine,
            folder = row.folder,
            state = state,
            tone = tone,
            requestKind = row.requestKind,
            request = row.request,
            sinceMs = row.sinceMs,
            asOf = row.asOf,
            label = row.label,
        )
    }
    val other = ui.others.find { it.key == key } ?: return null
    val none = res.getString(R.string.sheet_no_status)
    return AgentSheetUi(
        key = key,
        name = other.name,
        icon = other.icon,
        lineTwo = other.lineTwo,
        machine = key.machine,
        folder = other.folder,
        state = none,
        tone = SheetTone.None,
        requestKind = null,
        request = null,
        sinceMs = null,
        asOf = other.asOf,
        label = none,
    )
}
