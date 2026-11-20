package dev.kudde.android.ui.setup

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.Resources
import android.hardware.biometrics.BiometricManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kudde.android.R

/** No stored flag: consult Android initially and on every return from settings. */
class HomeDeviceSecurity(private val isDeviceSecure: () -> Boolean) {
    var secure by mutableStateOf(isDeviceSecure())
        private set

    fun onReturn() { secure = isDeviceSecure() }
}

/** Try actual launches: a listed activity can still disappear before it opens. */
fun openScreenLockSettings(launch: (Intent) -> Unit): Boolean {
    val intents = listOf(
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            launch(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Continue to the next Android settings screen.
        }
    }
    return false
}

/** The caller passes the displayed D3 command unchanged to both operations. */
fun setupCommandClip(command: String, res: Resources): ClipData =
    ClipData.newPlainText(res.getString(R.string.setup_command_title), command)

fun setupShareIntent(command: String, res: Resources): Intent =
    Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, command)
        .putExtra(Intent.EXTRA_TITLE, res.getString(R.string.setup_command_title))

fun shareSetupCommand(command: String, res: Resources, launch: (Intent) -> Unit): Boolean = try {
    launch(Intent.createChooser(setupShareIntent(command, res), null))
    true
} catch (_: ActivityNotFoundException) {
    false
}
