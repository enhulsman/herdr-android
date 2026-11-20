package dev.kudde.android.ui.machines

import android.content.res.Resources
import dev.kudde.android.R
import uniffi.kudde_mobile.MachineState
import uniffi.kudde_mobile.MachineView
import uniffi.kudde_mobile.ReachKind

/** F1-F5. F5 supplies only a reason; there is no invented recovery sentence. */
data class ReachText(val reason: String, val recovery: String?)

/** Shared by the card model and setup, independently of retry countdowns. */
fun reachText(machine: MachineView, res: Resources): ReachText? {
    val failure = machine.state as? MachineState.Unreachable ?: return null
    fun text(reason: Int, recovery: Int) = ReachText(res.getString(reason, machine.host, machine.port.toInt()), res.getString(recovery))
    return when (failure.kind) {
        ReachKind.NO_ANSWER -> text(R.string.setup_reach_no_answer, R.string.setup_reach_no_answer_help)
        ReachKind.REFUSED -> text(R.string.setup_reach_refused, R.string.setup_reach_refused_help)
        ReachKind.NAME_NOT_FOUND -> text(R.string.setup_reach_name_not_found, R.string.setup_reach_name_not_found_help)
        ReachKind.NO_ROUTE -> text(R.string.setup_reach_no_route, R.string.setup_reach_no_route_help)
        ReachKind.OTHER, ReachKind.CORE_STARTUP_CLOSED ->
            ReachText(res.getString(R.string.setup_reach_other, machine.host, failure.detail), null)
    }
}

/** Only the hub's explicit startup grace hides a reach failure on setup. */
fun setupReachText(machine: MachineView, res: Resources): ReachText? =
    if ((machine.state as? MachineState.Unreachable)?.kind == ReachKind.CORE_STARTUP_CLOSED && machine.startupRetryPending) null
    else reachText(machine, res)
