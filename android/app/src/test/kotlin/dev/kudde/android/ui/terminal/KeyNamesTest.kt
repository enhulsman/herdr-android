package dev.kudde.android.ui.terminal

import android.content.res.Resources
import dev.kudde.android.testing.KuddeTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

// accessibility spec, "Every key has a spoken name".
@RunWith(KuddeTestRunner::class)
class KeyNamesTest {
    private val res: Resources = RuntimeEnvironment.getApplication().resources

    private val taps = KEY_LINES.flatten().filterIsInstance<RowKey.Tap>()

    @Test
    fun everyKeyHasASpokenName() {
        for (key in taps) assertNotNull("${key.label} has no spoken name", key.description)
    }

    @Test
    fun theSymbolKeysAreReadByName() {
        val spoken = taps.associate { it.label to res.getString(it.description!!) }
        assertEquals("Dash", spoken["-"])
        assertEquals("Slash", spoken["/"])
        assertEquals("Tilde", spoken["~"])
        assertEquals("Backslash", spoken["\\"])
        assertEquals("Pipe", spoken["|"])
    }
}
