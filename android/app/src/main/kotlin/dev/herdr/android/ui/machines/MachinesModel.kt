package dev.herdr.android.ui.machines

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import dev.herdr.android.R
import dev.herdr.android.push.AlertSetup
import dev.herdr.android.push.MachineAlerts
import dev.herdr.android.ui.theme.Contrast
import dev.herdr.android.ui.transfer.TransferNoticeUi
import uniffi.herdr_mobile.AlertsRegistration
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView

// What the Machines screen shows, worked out from a hub snapshot with every
// text resolved, so the screen itself only lays it out (MachineStatusTest pins
// the text per state). States the hub retries by itself count down to its
// next attempt ("retrying in 30 s", render 7) from the screen's clock.

enum class Tone { Muted, Warning }

data class Segment(val text: String, val tone: Tone = Tone.Muted)

/** The 9 dp mark before the name: a filled dot only while connected. */
enum class StatusMark { Connected, Ring }

/** A button at the end of the card's head. */
enum class CardButton { Unlock, TryAgain, OpenSettings }

/** A row under the head that leads to a flow (5d builds the flows). */
enum class CardLink { CheckHostKey, CompareHostKeys, AddPhoneKey }

data class CardLinkUi(val kind: CardLink, val label: String, val detail: String?)

/** The button on a card's Alerts row. */
enum class AlertsButton { TurnOn, TryAgain, Allow }

/** Render 7's "Alerts" row: what this phone's alerts from the machine look like. */
data class AlertsRowUi(val detail: String, val button: AlertsButton?)

/** Render 7's "Send test alert" row, with its Send button. */
data class TestRowUi(val detail: String)

/**
 * The phone's side of alerts, for the cards: whether it has a registration,
 * how far setup got without one, whether Android lets it notify, and what it
 * recorded per machine (PushStore). [timeText] formats a time of day.
 */
data class PhoneAlerts(
    val registered: Boolean,
    val setup: AlertSetup,
    val permitted: Boolean,
    val machines: Map<String, MachineAlerts>,
    val timeText: (Long) -> String,
)

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
    /** Null: no alert rows (alerts unknown, as in screens before M3). */
    val alerts: AlertsRowUi? = null,
    /** The "Mute alerts" toggle's state; null hides the row. */
    val mute: Boolean? = null,
    val test: TestRowUi? = null,
) {
    val statusText: String get() = status.joinToString(separator) { it.text }
}

@Immutable
data class MachinesUi(
    val machines: List<MachineCardUi>,
    /** A storage problem from the hub. */
    val notice: String?,
    val phoneKeyDetail: String,
    /** Export needs a machine to export: dim and not tappable while the list is empty. */
    val canExport: Boolean,
    /** The export row's second line: "Without keys", or why it is disabled. */
    val exportDetail: String,
    /** How the last export or import went, under the "This phone" card. */
    val transfer: TransferNoticeUi? = null,
    /** The machine whose Remove confirm is open. */
    val removing: String? = null,
    /** Why the hub refused the last remove, in the notice style. */
    val error: String? = null,
    /** The "Text contrast" row's second line: "Like the Mac" or "Higher". */
    val textContrastDetail: String = "",
)

/**
 * [startError]: why the hub (and with it the phone key) could not start, from
 * HubHost; [alerts]: the phone's alert state, null for cards without alert rows.
 */
fun machinesUi(
    snapshot: HubSnapshot,
    startError: String?,
    nowMs: Long,
    res: Resources,
    contrast: Contrast = Contrast.Default,
    alerts: PhoneAlerts? = null,
): MachinesUi =
    MachinesUi(
        machines = snapshot.machines.map { m ->
            val card = machineCard(m, nowMs, res)
            if (alerts == null) card else withAlerts(card, m, alerts, nowMs, res)
        },
        notice = snapshot.notice,
        phoneKeyDetail = startError ?: res.getString(R.string.machines_phone_key_detail),
        canExport = snapshot.machines.isNotEmpty(),
        exportDetail = res.getString(
            if (snapshot.machines.isNotEmpty()) R.string.machines_export_detail else R.string.machines_export_detail_empty,
        ),
        textContrastDetail = res.getString(
            when (contrast) {
                Contrast.Mac -> R.string.machines_text_contrast_mac
                Contrast.Aa -> R.string.machines_text_contrast_higher
            },
        ),
    )

private fun machineCard(m: MachineView, nowMs: Long, res: Resources): MachineCardUi {
    fun s(id: Int, vararg args: Any) = Segment(res.getString(id, *args))
    val retrying = retryingSegment(m.nextRetryMs?.toLong(), nowMs, res)
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
        }
        is MachineState.Unreachable -> {
            detail = st.detail.takeIf { it.isNotBlank() }
            buildList {
                add(s(R.string.status_unreachable))
                m.lastConnectedMs?.let { add(s(R.string.status_last_connected, agoText(nowMs - it.toLong(), res))) }
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
            // Only Android settings change it: no countdown, tried again on the return.
            detail = res.getString(R.string.status_network_off_detail)
            button = CardButton.OpenSettings
            listOf(s(R.string.status_network_off))
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

/**
 * "retrying in 30 s" until the hub's next attempt, rounded up so it starts at
 * the full step; "retrying" once it is due (the hub keeps the failure while
 * the retry runs, so the card's detail and links stay put) or when the hub
 * gave no time.
 */
private fun retryingSegment(nextRetryMs: Long?, nowMs: Long, res: Resources): Segment {
    val left = nextRetryMs?.let { (it - nowMs + 999).floorDiv(1_000L) } ?: 0L
    return Segment(
        if (left > 0) res.getString(R.string.status_retrying_in, left.toInt())
        else res.getString(R.string.status_retrying),
    )
}

/**
 * The card's alert rows (render 7). Without a registration the Alerts row says
 * how far setup got, with a button to go on; with one but no permission to
 * notify, alerts are off with a way to allow them (alerts spec, "Permission
 * denied"). Otherwise it shows what the machine's herdr-push last told the
 * phone (as of the last connection: never "delivered") and the last push that
 * reached the phone. Mute and the test show on a connected machine; a muted
 * one keeps its toggle while away, so it can be undone.
 */
private fun withAlerts(card: MachineCardUi, m: MachineView, alerts: PhoneAlerts, nowMs: Long, res: Resources): MachineCardUi {
    if (!alerts.registered) {
        val row = when (val setup = alerts.setup) {
            AlertSetup.Off -> AlertsRowUi(res.getString(R.string.alerts_off), AlertsButton.TurnOn)
            AlertSetup.Waiting -> AlertsRowUi(res.getString(R.string.alerts_waiting), AlertsButton.TryAgain)
            AlertSetup.NoDistributor -> AlertsRowUi(res.getString(R.string.alerts_no_distributor), AlertsButton.TryAgain)
            is AlertSetup.Failed -> AlertsRowUi(res.getString(R.string.alerts_failed, setup.reason), AlertsButton.TryAgain)
        }
        return card.copy(alerts = row)
    }
    val mine = alerts.machines[m.name] ?: MachineAlerts()
    val connected = m.state is MachineState.Connected
    val mute = mine.muted.takeIf { connected || it }
    if (!alerts.permitted) {
        return card.copy(alerts = AlertsRowUi(res.getString(R.string.alerts_not_allowed), AlertsButton.Allow), mute = mute)
    }
    val parts = mutableListOf<String>()
    val view = m.alerts
    when (view?.registration ?: AlertsRegistration.NOT_YET) {
        AlertsRegistration.NOT_YET -> parts += res.getString(R.string.alerts_not_yet)
        AlertsRegistration.ENDPOINT_CHANGED -> parts += res.getString(R.string.alerts_endpoint_changed)
        AlertsRegistration.REGISTERED -> {
            val status = view?.status
            val endpoint = status?.endpointStatus
            when {
                status == null -> if (connected) parts += res.getString(R.string.alerts_no_watcher)
                endpoint == null -> parts += res.getString(R.string.alerts_no_push_yet)
                endpoint.startsWith("accepted") -> status.lastPushAtMs?.let {
                    parts += res.getString(R.string.alerts_accepted, alerts.timeText(it.toLong()))
                }
                else -> parts += res.getString(R.string.alerts_failing, failure(endpoint))
            }
        }
    }
    mine.lastPushMs?.let { at ->
        val time = alerts.timeText(at)
        parts += res.getString(if (parts.isEmpty()) R.string.alerts_last_push_first else R.string.alerts_last_push, time)
    }
    if (parts.isEmpty()) parts += res.getString(R.string.alerts_registered)
    return card.copy(
        alerts = AlertsRowUi(parts.joinToString(card.separator), null),
        mute = mute,
        test = if (connected) TestRowUi(testDetail(mine, nowMs, alerts.timeText, res)) else null,
    )
}

/** herdr-push's endpoint status without its verb: "refused 403" is "403", "failed: timed out" is "timed out". */
private fun failure(status: String): String = when {
    status.startsWith("refused ") -> status.removePrefix("refused ")
    ':' in status -> status.substringAfter(':').trim()
    else -> status
}

/** How long the app waits for a test push before saying none came (alerts spec, "A test alert proves the path"). */
const val TEST_WAIT_MS = 30_000L

private fun testDetail(m: MachineAlerts, nowMs: Long, timeText: (Long) -> String, res: Resources): String {
    val asked = m.testAskedMs ?: return res.getString(R.string.alerts_test_idle)
    val received = m.testReceivedMs
    return when {
        received != null && received >= asked -> res.getString(R.string.alerts_test_received, timeText(received))
        nowMs - asked < TEST_WAIT_MS -> res.getString(R.string.alerts_test_waiting)
        else -> res.getString(R.string.alerts_test_missed)
    }
}

/** A test sent within the last 30 s with no answer yet: the screen counts seconds. */
private fun testWaiting(alerts: PhoneAlerts?, nowMs: Long): Boolean =
    alerts?.machines?.values.orEmpty().any { m ->
        val asked = m.testAskedMs ?: return@any false
        (m.testReceivedMs ?: Long.MIN_VALUE) < asked && nowMs - asked < TEST_WAIT_MS
    }

/**
 * What the Machines screen's text depends on from the clock: whole seconds
 * while a countdown shows or a test alert is awaited, else whole minutes
 * ("last connected").
 */
fun machinesTick(snapshot: HubSnapshot, nowMs: Long, alerts: PhoneAlerts? = null): Long =
    if (snapshot.machines.any { it.nextRetryMs != null } || testWaiting(alerts, nowMs)) nowMs / 1_000 else nowMs / 60_000

/** "just now", "5m ago", "2h ago", "3d ago"; a clock that went backwards reads as just now. */
fun agoText(elapsedMs: Long, res: Resources): String {
    val minutes = elapsedMs / 60_000
    return when {
        minutes < 1 -> res.getString(R.string.status_ago_now)
        minutes < 60 -> res.getString(R.string.status_ago_minutes, minutes.toInt())
        minutes < 24 * 60 -> res.getString(R.string.status_ago_hours, (minutes / 60).toInt())
        else -> res.getString(R.string.status_ago_days, (minutes / (24 * 60)).toInt())
    }
}
