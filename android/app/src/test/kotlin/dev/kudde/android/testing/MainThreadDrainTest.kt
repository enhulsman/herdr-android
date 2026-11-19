package dev.kudde.android.testing

import android.os.Looper
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

// A test that changed Compose state and ended without running the main thread
// (NewTerminalTest did) left Compose's dispatcher waiting on a callback the
// looper reset threw away: every later test's effects and recompositions
// stopped (ChatThumbKey, UptimeTicker and TerminalSwitch failed, only in a full
// run). KuddeTestRunner runs that callback before the reset.
@RunWith(KuddeTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MainThreadDrainTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun a_aTestEndsRightAfterAStateChange() {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        c.get().setContent { }
        idle()
        c.pause().stop().destroy()
        idle()
        var shown by mutableStateOf(false)
        shown = true
        assertTrue(shown)
    }

    @Test
    fun b_theNextTestsEffectsStillRun() {
        var ran = false
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        c.get().setContent { LaunchedEffect(Unit) { ran = true } }
        idle()
        c.pause().stop().destroy()
        idle()
        assertTrue("an effect never ran", ran)
    }

    @Test
    fun c_everyRobolectricTestUsesTheHerdrRunner() {
        // Gradle runs unit tests from the module directory.
        val plainRunner = "@RunWith(" + "RobolectricTestRunner::class)"
        val plain = File("src/test/kotlin").walk()
            .filter { it.extension == "kt" && plainRunner in it.readText() }
            .map { it.name }
            .toList()
        assertEquals("these use the plain runner, which can stall later tests", emptyList<String>(), plain)
    }
}
