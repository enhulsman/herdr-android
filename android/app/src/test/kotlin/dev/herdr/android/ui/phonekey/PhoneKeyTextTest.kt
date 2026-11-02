package dev.herdr.android.ui.phonekey

import dev.herdr.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// What the phone key screen says the restricted line allows. herdr-core's
// machine.exec runs commands as the user (design.md), so the key is
// shell-equivalent: the text must not promise "no shell".
@RunWith(RobolectricTestRunner::class)
class PhoneKeyTextTest {
    private val text = RuntimeEnvironment.getApplication().getString(R.string.phone_key_restricted)

    @Test
    fun theRestrictionTextSaysTheKeyIsShellEquivalent() {
        assertEquals(
            "With this key, SSH runs only herdr-core: no terminal session, no forwarding. " +
                "herdr-core can still run commands as this user, so treat the key like a shell login.",
            text,
        )
        assertFalse(text.contains("no shell", ignoreCase = true))
    }
}
