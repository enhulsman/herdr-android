package dev.kudde.android.ui.machines

import android.provider.Settings
import dev.kudde.android.testing.KuddeTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// "Network permission is off" offers Open settings: there is no public link to
// one permission's toggle, so it opens this app's App info page.
@RunWith(KuddeTestRunner::class)
class AppSettingsIntentTest {
    @Test
    fun openSettingsOpensThisAppsInfoPage() {
        val intent = appSettingsIntent("dev.kudde.example")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:dev.kudde.example", intent.dataString)
    }
}
