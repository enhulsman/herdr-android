package dev.herdr.android

import android.app.Activity
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// The application object counts started activities for the whole process.
// Robolectric has no AndroidKeyStore, so the hub cannot start here: that must
// show as an error, never crash the app. A plain Activity, not MainActivity:
// MainActivity loads the uniffi bindings inside Robolectric's class loader,
// which re-registers the callback tables for the whole JVM (see HubHostTest).
@RunWith(RobolectricTestRunner::class)
class HerdrAppTest {
    private val app get() = RuntimeEnvironment.getApplication() as HerdrApp

    @Test
    fun theManifestNamesTheApplication() {
        assertTrue(RuntimeEnvironment.getApplication() is HerdrApp)
    }

    @Test
    fun startedActivitiesAreCountedAndARotationKeepsTheAppInTheForeground() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(app.foreground.isForeground)
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.SECONDS)
        assertTrue(app.foreground.isForeground)
        controller.pause().stop()
        assertEquals(false, app.foreground.isForeground)
    }

    @Test
    fun aKeystoreThatIsMissingIsAnErrorNotACrash() {
        Robolectric.buildActivity(Activity::class.java).setup()
        assertNotNull(app.hubHost.startError.value)
    }
}
