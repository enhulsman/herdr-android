package dev.herdr.android.push

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.herdr_mobile.PushRegistration

/** How far setting up alerts on this phone got, while it has no registration. */
sealed interface AlertSetup {
    /** Never asked for, or the distributor took the registration back. */
    data object Off : AlertSetup

    /** Asked the distributor; its endpoint has not come yet. */
    data object Waiting : AlertSetup

    /** No UnifiedPush app on the phone, or none chosen. */
    data object NoDistributor : AlertSetup

    data class Failed(val reason: String) : AlertSetup
}

/** What the phone recorded about one machine's alerts; times are ms since the epoch. */
data class MachineAlerts(
    val muted: Boolean = false,
    /** The last push of any kind from it, or when it was first registered there (the silence baseline). */
    val lastPushMs: Long? = null,
    /** The [lastPushMs] the "Alert health" notice was posted for: one notice per silence. */
    val healthShownForMs: Long? = null,
    val testAskedMs: Long? = null,
    val testReceivedMs: Long? = null,
)

/**
 * The phone's alert state, in its own preferences file (outside every backup,
 * like the rest of the app's data): the UnifiedPush registration, how far
 * setup got, and per machine mute, last push, health notice and test times.
 * The push service and the silence check run with the app closed, so all of it
 * lives here rather than in the hub. [changes] counts writes, for the screens.
 * Per-machine keys end in the machine's name after a fixed prefix, so names
 * with any characters cannot collide.
 */
class PushStore(private val prefs: SharedPreferences) {
    private val counter = MutableStateFlow(0L)
    val changes: StateFlow<Long> = counter.asStateFlow()

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        counter.value += 1
    }

    var registration: PushRegistration?
        get() {
            val endpoint = prefs.getString(ENDPOINT, null) ?: return null
            val p256dh = prefs.getString(P256DH, null) ?: return null
            val auth = prefs.getString(AUTH, null) ?: return null
            return PushRegistration(endpoint, p256dh, auth)
        }
        set(value) = write {
            if (value == null) {
                remove(ENDPOINT).remove(P256DH).remove(AUTH)
            } else {
                putString(ENDPOINT, value.endpoint).putString(P256DH, value.p256dh).putString(AUTH, value.auth)
            }
        }

    var setup: AlertSetup
        get() = when (val s = prefs.getString(SETUP, null)) {
            "waiting" -> AlertSetup.Waiting
            "no-distributor" -> AlertSetup.NoDistributor
            null, "off" -> AlertSetup.Off
            else -> if (s.startsWith("failed:")) AlertSetup.Failed(s.removePrefix("failed:")) else AlertSetup.Off
        }
        set(value) = write {
            putString(
                SETUP,
                when (value) {
                    AlertSetup.Off -> "off"
                    AlertSetup.Waiting -> "waiting"
                    AlertSetup.NoDistributor -> "no-distributor"
                    is AlertSetup.Failed -> "failed:${value.reason}"
                },
            )
        }

    /** The notification permission was asked for once: a refusal after that only changes in Android settings. */
    var permissionAsked: Boolean
        get() = prefs.getBoolean(PERMISSION_ASKED, false)
        set(value) = write { putBoolean(PERMISSION_ASKED, value) }

    fun machine(name: String): MachineAlerts = MachineAlerts(
        muted = prefs.getBoolean(MUTED + name, false),
        lastPushMs = long(LAST_PUSH + name),
        healthShownForMs = long(HEALTH + name),
        testAskedMs = long(TEST_ASKED + name),
        testReceivedMs = long(TEST_RECEIVED + name),
    )

    fun setMuted(name: String, muted: Boolean) = write { putBoolean(MUTED + name, muted) }

    fun recordPush(name: String, nowMs: Long) = write { putLong(LAST_PUSH + name, nowMs) }

    /** The machine has the phone's registration: silence counts from now unless a push came before. */
    fun enroll(name: String, nowMs: Long) {
        if (!prefs.contains(LAST_PUSH + name)) recordPush(name, nowMs)
    }

    /** The machines the silence check watches. */
    fun enrolled(): List<String> =
        prefs.all.keys.filter { it.startsWith(LAST_PUSH) }.map { it.removePrefix(LAST_PUSH) }.sorted()

    fun markHealthShown(name: String, forMs: Long) = write { putLong(HEALTH + name, forMs) }

    fun testAsked(name: String, nowMs: Long) = write { putLong(TEST_ASKED + name, nowMs) }

    fun testReceived(name: String, nowMs: Long) = write { putLong(TEST_RECEIVED + name, nowMs) }

    /** Drops the record of machines no longer in the list (removed or renamed). */
    fun keepMachines(names: Set<String>) {
        val gone = prefs.all.keys.filter { key ->
            val prefix = MACHINE_PREFIXES.firstOrNull { key.startsWith(it) } ?: return@filter false
            key.removePrefix(prefix) !in names
        }
        if (gone.isNotEmpty()) write { gone.forEach { remove(it) } }
    }

    private fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null

    companion object {
        private const val ENDPOINT = "endpoint"
        private const val P256DH = "p256dh"
        private const val AUTH = "auth"
        private const val SETUP = "setup"
        private const val PERMISSION_ASKED = "permission-asked"
        private const val MUTED = "machine.muted/"
        private const val LAST_PUSH = "machine.last-push/"
        private const val HEALTH = "machine.health-shown/"
        private const val TEST_ASKED = "machine.test-asked/"
        private const val TEST_RECEIVED = "machine.test-received/"
        private val MACHINE_PREFIXES = listOf(MUTED, LAST_PUSH, HEALTH, TEST_ASKED, TEST_RECEIVED)

        fun open(context: Context) = PushStore(context.getSharedPreferences("alerts", Context.MODE_PRIVATE))
    }
}
