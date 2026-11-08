package dev.herdr.android.stay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "Stay connected" option (Machines, under "This phone"; stay-connected
 * spec). Off on a fresh install and after an update from a version without
 * it: an absent value, or one of another type, reads as off. Kept in its own
 * app-private preferences file; one instance per process lives on HerdrApp.
 * Main thread only.
 */
class StayConnectedSetting(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val on: StateFlow<Boolean> = state.asStateFlow()

    fun isOn(): Boolean = state.value

    fun toggle() {
        val next = !state.value
        prefs.edit().putBoolean(KEY, next).apply()
        state.value = next
    }

    private fun read(): Boolean = runCatching { prefs.getBoolean(KEY, false) }.getOrDefault(false)

    companion object {
        const val FILE = "stay_connected"
        const val KEY = "stay_connected"

        fun open(context: Context): StayConnectedSetting =
            StayConnectedSetting(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/** The Machines row's second line: "Off", "On · shows a notification", or "On · notifications are off". */
enum class StayConnectedRow {
    Off,
    On,
    OnUnseen,
    ;

    companion object {
        /** [notificationsShown]: Android lets the app notify and the Status channel is not blocked. */
        fun of(on: Boolean, notificationsShown: Boolean): StayConnectedRow = when {
            !on -> Off
            notificationsShown -> On
            else -> OnUnseen
        }
    }
}
