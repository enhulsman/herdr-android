package dev.herdr.android.push

import android.content.res.Resources
import dev.herdr.android.R
import dev.herdr.android.ui.agents.AgentKey
import org.json.JSONException
import org.json.JSONObject

/** The three notification channels (alerts spec, "Three notification channels"). */
enum class AlertChannel(val id: String) {
    NeedsYou("needs-you"),
    Finished("finished"),
    Health("alert-health"),
}

/** One notification to post; [tag] identifies it for replacing and clearing. */
data class AlertNote(
    val channel: AlertChannel,
    val tag: String,
    val title: String,
    val text: String?,
    /** Tapping it opens this agent's terminal; null opens the app. */
    val open: AgentKey?,
    /**
     * What a locked phone that hides sensitive content shows instead: no
     * agent name, as that may be a session title (spec agent-names). Null:
     * Android's own "contents hidden".
     */
    val publicTitle: String? = null,
)

/** Where notes go: Android's notification manager, or a test's list. */
interface AlertSink {
    fun post(note: AlertNote)
    fun cancel(tag: String)
}

/**
 * Turns pushes from herdr-push into notifications, and silence into one
 * "Alert health" notice (alerts spec). A push the connector could not decrypt,
 * or one without a machine, is ignored entirely: it proves nothing about the
 * path. Every other push counts as word from its machine and clears that
 * machine's health notice; a muted machine's pushes post nothing. Notification
 * text carries the agent's name, the kind, the desk's "space / tab" and the
 * machine only, never screen text or the program's message (the payload has
 * neither). The name may be a
 * session title, so each agent note has a public version without it.
 * [timeText] formats a time of day ("14:02").
 */
class Alerts(
    private val store: PushStore,
    private val sink: AlertSink,
    private val res: Resources,
    private val timeText: (Long) -> String,
) {
    fun onMessage(content: ByteArray, decrypted: Boolean, nowMs: Long) {
        if (!decrypted) return
        val push = parse(content) ?: return
        val machine = push.machine
        store.recordPush(machine, nowMs)
        sink.cancel(healthTag(machine))
        val muted = store.machine(machine).muted
        when (push.type) {
            "alert" -> {
                val terminal = push.terminal ?: return
                if (!muted) sink.post(agentNote(push, machine, terminal))
            }
            "cleared" -> push.terminal?.let { sink.cancel(agentTag(machine, it)) }
            "test" -> {
                store.testReceived(machine, nowMs)
                if (!muted) {
                    sink.post(
                        AlertNote(
                            AlertChannel.NeedsYou,
                            "test:$machine",
                            res.getString(R.string.alert_test_title, machine),
                            res.getString(R.string.alert_test_text),
                            open = null,
                            publicTitle = res.getString(R.string.alert_test_title, machine),
                        ),
                    )
                }
            }
            // "canary", and types a later herdr-push may add: word, nothing to show.
            else -> Unit
        }
    }

    /**
     * The periodic check: one notice per machine silent for [SILENCE_MS],
     * while alerts are on. A muted machine is checked too: its pushes still
     * count for alert health.
     */
    fun checkSilence(nowMs: Long) {
        if (store.registration == null) return
        for (machine in store.enrolled()) {
            val m = store.machine(machine)
            val last = m.lastPushMs ?: continue
            if (nowMs - last < SILENCE_MS || m.healthShownForMs == last) continue
            store.markHealthShown(machine, last)
            sink.post(
                AlertNote(
                    AlertChannel.Health,
                    healthTag(machine),
                    res.getString(R.string.alert_health_title, machine, timeText(last)),
                    res.getString(R.string.alert_health_text),
                    open = null,
                ),
            )
        }
    }

    private fun agentNote(push: Push, machine: String, terminal: String): AlertNote {
        val name = push.name?.takeIf { it.isNotBlank() } ?: terminal
        val open = AgentKey(machine, terminal)
        val tag = agentTag(machine, terminal)
        val place = push.place?.takeIf { it.isNotBlank() }
        if (push.state == "done") {
            val public = res.getString(R.string.alert_finished_public, machine)
            return AlertNote(AlertChannel.Finished, tag, res.getString(R.string.alert_finished_title, name), body(place, machine), open, public)
        }
        val kind = if (push.state == "error") res.getString(R.string.alert_kind_error) else push.kind?.let(::capitalized)
        val public = res.getString(R.string.alert_needs_you_public, machine)
        return AlertNote(AlertChannel.NeedsYou, tag, res.getString(R.string.alert_needs_you_title, name), body(kind, place, machine), open, public)
    }

    /** The second line: "Permission · website / 2 · WSL", absent parts left out. */
    private fun body(vararg parts: String?): String =
        parts.filterNotNull().reduce { a, b -> res.getString(R.string.alert_body, a, b) }

    private data class Push(
        val type: String,
        val machine: String,
        val terminal: String?,
        val name: String?,
        val place: String?,
        val state: String?,
        val kind: String?,
    )

    private fun parse(content: ByteArray): Push? {
        val json = try {
            JSONObject(content.decodeToString())
        } catch (_: JSONException) {
            return null
        }
        fun text(key: String): String? = json.opt(key) as? String
        val machine = text("machine")?.takeIf { it.isNotEmpty() } ?: return null
        return Push(text("type") ?: "", machine, text("terminal")?.takeIf { it.isNotEmpty() }, text("name"), text("place"), text("state"), text("kind")?.takeIf { it.isNotEmpty() })
    }

    companion object {
        /** 13 hours: two missed canaries (every 6 h) and an hour's slack. */
        const val SILENCE_MS = 13 * 60 * 60 * 1000L

        fun agentTag(machine: String, terminal: String) = "agent:$machine/$terminal"
        fun healthTag(machine: String) = "health:$machine"

        /** As the agent list heads a request: "permission" reads "Permission". */
        private fun capitalized(s: String) = s.replaceFirstChar { it.uppercaseChar() }
    }
}
