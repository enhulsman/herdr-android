package dev.herdr.android.ui.machines

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import dev.herdr.android.R
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView

// What the Machines screen shows, worked out from a hub snapshot with every
// text resolved, so the screen itself only lays it out (MachineStatusTest pins
// the text per state). Gaps against render 7: the hub does not say when it
// retries next, so an unreachable machine says "retrying" without "in 30 s".

enum class Tone { Muted, Warning }

data class Segment(val text: String, val tone: Tone = Tone.Muted)

/** The 9 dp mark before the name: a filled dot only while connected. */
enum class StatusMark { Connected, Ring }

/** A button at the end of the card's head. */
enum class CardButton { Unlock, TryAgain }

/** A row under the head that leads to a flow (5d builds the flows). */
enum class CardLink { CheckHostKey, CompareHostKeys, AddPhoneKey }

data class CardLinkUi(val kind: CardLink, val label: String, val detail: String?)

@Immutable
data class MachineCardUi(
    val name: String,
    val mark: StatusMark,
    val status: List<Segment>,
    val separator: String,
    /** A second, dim line: why it is unreachable, or what to do. */
    val detail: String?,
    val button: CardButton?,
    val link: CardLinkUi?,
) {
    val statusText: String get() = status.joinToString(separator) { it.text }
}

@Immutable
data class MachinesUi(
    val machines: List<MachineCardUi>,
    /** A storage problem from the hub. */
    val notice: String?,
    val phoneKeyDetail: String,
)

/** [startError]: why the hub (and with it the phone key) could not start, from HubHost. */
fun machinesUi(snapshot: HubSnapshot, startError: String?, nowMs: Long, res: Resources): MachinesUi =
    MachinesUi(
        machines = snapshot.machines.map { machineCard(it, nowMs, res) },
        notice = snapshot.notice,
        phoneKeyDetail = startError ?: res.getString(R.string.machines_phone_key_detail),
    )

private fun machineCard(m: MachineView, nowMs: Long, res: Resources): MachineCardUi {
    fun s(id: Int, vararg args: Any) = Segment(res.getString(id, *args))
    val retrying = s(R.string.status_retrying)
    var detail: String? = null
    var button: CardButton? = null
    var link: CardLinkUi? = null
    fun link(kind: CardLink, label: Int, detail: Int) {
        link = CardLinkUi(kind, res.getString(label), res.getString(detail))
    }

    val status: List<Segment> = when (val st = m.state) {
        MachineState.Paused -> listOf(s(R.string.status_paused))
        MachineState.Connecting -> listOf(s(R.string.status_connecting))
        is MachineState.Connected -> buildList {
            add(s(R.string.status_connected))
            add(s(R.string.status_core_version, st.version))
            if (st.untested) add(Segment(res.getString(R.string.status_untested), Tone.Warning))
        }
        is MachineState.Unreachable -> {
            detail = st.detail.takeIf { it.isNotBlank() }
            buildList {
                add(s(R.string.status_unreachable))
                m.lastConnectedMs?.let { add(s(R.string.status_last_connected, ago(nowMs - it.toLong(), res))) }
                add(retrying)
            }
        }
        MachineState.NeedsUserAuth -> {
            button = CardButton.Unlock
            listOf(s(R.string.status_needs_user_auth))
        }
        is MachineState.HostKeyUnconfirmed -> {
            link(CardLink.CheckHostKey, R.string.link_check_host_key, R.string.link_check_host_key_detail)
            listOf(s(R.string.status_host_key_unconfirmed))
        }
        is MachineState.HostKeyMismatch -> {
            link(CardLink.CompareHostKeys, R.string.link_compare_host_keys, R.string.link_compare_host_keys_detail)
            listOf(s(R.string.status_host_key_changed), s(R.string.status_not_connecting))
        }
        MachineState.KeyRejected -> {
            link(CardLink.AddPhoneKey, R.string.link_add_phone_key, R.string.link_add_phone_key_detail)
            listOf(s(R.string.status_key_rejected), retrying)
        }
        MachineState.NotInstalled -> listOf(s(R.string.status_not_installed), retrying)
        is MachineState.TooOld -> {
            detail = res.getString(R.string.status_too_old_detail, st.need)
            button = CardButton.TryAgain
            listOf(s(R.string.status_too_old, st.have))
        }
        MachineState.AppTooOld -> {
            detail = res.getString(R.string.status_app_too_old_detail)
            listOf(s(R.string.status_app_too_old))
        }
        MachineState.NetworkOff -> {
            detail = res.getString(R.string.status_network_off_detail)
            listOf(s(R.string.status_network_off), retrying)
        }
    }
    return MachineCardUi(
        name = m.name,
        mark = if (m.state is MachineState.Connected) StatusMark.Connected else StatusMark.Ring,
        status = status,
        separator = res.getString(R.string.status_separator),
        detail = detail,
        button = button,
        link = link,
    )
}

/** "just now", "5m ago", "2h ago", "3d ago"; a clock that went backwards reads as just now. */
private fun ago(elapsedMs: Long, res: Resources): String {
    val minutes = elapsedMs / 60_000
    return when {
        minutes < 1 -> res.getString(R.string.status_ago_now)
        minutes < 60 -> res.getString(R.string.status_ago_minutes, minutes.toInt())
        minutes < 24 * 60 -> res.getString(R.string.status_ago_hours, (minutes / 60).toInt())
        else -> res.getString(R.string.status_ago_days, (minutes / (24 * 60)).toInt())
    }
}
