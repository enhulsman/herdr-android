package dev.herdr.android.ui.machines

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// "Network permission is off" offers Open settings: there is no public link to
// one permission's toggle, so it opens this app's App info page.
@RunWith(RobolectricTestRunner::class)
class AppSettingsIntentTest {
    @Test
    fun openSettingsOpensThisAppsInfoPage() {
        val intent = appSettingsIntent("dev.herdr.example")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:dev.herdr.example", intent.dataString)
    }
}
