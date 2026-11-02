package dev.herdr.android.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "Text contrast" setting (Machines, under "This phone"): [Contrast.Mac]
 * ("Like the Mac", the tokens' default) or [Contrast.Aa] ("Higher"). Kept in its
 * own app-private preferences file, apart from the hub's files; one instance
 * per process lives on HerdrApp. Main thread only.
 */
class ContrastSetting(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val contrast: StateFlow<Contrast> = state.asStateFlow()

    fun toggle() {
        val next = if (state.value == Contrast.Mac) Contrast.Aa else Contrast.Mac
        prefs.edit().putString(KEY, next.stored()).apply()
        state.value = next
    }

    private fun read(): Contrast {
        // A value of another type (or none) reads as the default, never a crash.
        val stored = runCatching { prefs.getString(KEY, null) }.getOrNull()
        return Contrast.entries.firstOrNull { it.stored() == stored } ?: Contrast.Default
    }

    companion object {
        const val FILE = "settings"
        const val KEY = "text_contrast"

        /** Stored names, independent of the enum's Kotlin names. */
        private fun Contrast.stored(): String = when (this) {
            Contrast.Mac -> "mac"
            Contrast.Aa -> "higher"
        }

        fun open(context: Context): ContrastSetting =
            ContrastSetting(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/** [HerdrTheme] following [setting]: a change re-themes every screen at once. */
@Composable
fun HerdrTheme(
    setting: ContrastSetting,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val contrast by setting.contrast.collectAsState()
    HerdrTheme(dark = dark, contrast = contrast, content = content)
}
