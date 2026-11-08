package dev.herdr.android.push

import android.content.res.Resources
import dev.herdr.android.R
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState

/** The status notification's title and second line; null: no second line. */
data class StatusLine(val title: String, val text: String?)

/**
 * The status notification's text, a pure function of the hub snapshot and
 * whether the hub started (stay-connected spec, "The status notification
 * shows counts only"; design D3). Counts come from agents on connected
 * machines only, as other rows are stale. No agent, project, folder or
 * machine name ever appears, so the lock screen can show it in full.
 */
object StatusText {
    fun of(snapshot: HubSnapshot, startError: String?, res: Resources): StatusLine {
        if (startError != null) return StatusLine(res.getString(R.string.status_note_open_app), null)
        val machines = snapshot.machines
        val connected = machines.filter { it.state is MachineState.Connected }.map { it.name }.toSet()
        if (connected.isEmpty()) {
            val title = when {
                machines.any { it.state.group() == Group.Key } -> R.string.status_note_unlock
                machines.any { it.state.group() == Group.Retrying } -> R.string.status_note_reconnecting
                machines.any { it.state.group() == Group.NeedsApp } -> R.string.status_note_open_app
                else -> R.string.status_note_no_machines
            }
            return StatusLine(res.getString(title), null)
        }
        val live = snapshot.agents.filter { it.machine in connected }
        val needYou = live.count { it.attention == AgentAttention.NEEDS_YOU || it.attention == AgentAttention.ERROR }
        val working = live.count { it.attention == AgentAttention.WORKING }
        val counts = buildList {
            if (needYou > 0) add(res.getQuantityString(R.plurals.status_note_need_you, needYou, needYou))
            if (working > 0) add(res.getString(R.string.status_note_working, working))
        }
        val title = counts.joinToString(res.getString(R.string.status_separator))
            .ifEmpty { res.getString(R.string.status_note_quiet) }
        val text = when {
            connected.size == machines.size -> res.getString(R.string.status_note_connected)
            machines.any { it.state.group() == Group.Key } ->
                res.getString(R.string.status_note_connected_some_unlock, connected.size, machines.size)
            else -> res.getString(R.string.status_note_connected_some, connected.size, machines.size)
        }
        return StatusLine(title, text)
    }

    private enum class Group { Connected, Key, Retrying, NeedsApp }

    private fun MachineState.group(): Group = when (this) {
        is MachineState.Connected -> Group.Connected
        MachineState.NeedsUserAuth -> Group.Key
        MachineState.Connecting,
        is MachineState.Unreachable,
        MachineState.KeyRejected,
        MachineState.NotInstalled,
        MachineState.Paused,
        -> Group.Retrying
        is MachineState.HostKeyUnconfirmed,
        is MachineState.HostKeyMismatch,
        is MachineState.TooOld,
        MachineState.AppTooOld,
        MachineState.NetworkOff,
        -> Group.NeedsApp
    }
}

/** Where the status notification goes: Android's notification manager, or a test's list. No alert tags. */
interface StatusSink {
    fun post(line: StatusLine)
    fun cancel()
}

/**
 * Posts the status line when it changes, so progress ticks never reach
 * Android's update rate limit; after [stop] it cancels the notification and
 * posts nothing more (the service's collection may deliver one more snapshot
 * after it stopped). Main thread only.
 */
class StatusNotifier(private val sink: StatusSink) {
    private var last: StatusLine? = null
    private var stopped = false

    fun update(line: StatusLine) {
        if (stopped || line == last) return
        last = line
        sink.post(line)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        sink.cancel()
    }
}
