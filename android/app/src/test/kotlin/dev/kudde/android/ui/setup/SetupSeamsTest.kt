package dev.kudde.android.ui.setup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.provider.Settings
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.agents.AgentsEmpty
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.phonekey.ExampleKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.setupCommand

@RunWith(KuddeTestRunner::class)
class SetupSeamsTest {
    private val res = RuntimeEnvironment.getApplication().resources

    @Test fun securityIsReadInitiallyAndOnEveryReturn() {
        var secure = false
        var reads = 0
        val device = HomeDeviceSecurity { reads++; secure }
        fun empty() = agentsUi(HubSnapshot(emptyList(), emptyList(), emptyList(), null),
            0, res, { "" }, isDeviceSecure = device.secure).empty
        assertEquals(1, reads)
        assertEquals(AgentsEmpty.NoScreenLock, empty())
        secure = true
        device.onReturn()
        assertEquals(2, reads)
        assertEquals(AgentsEmpty.NoMachines, empty())
        device.onReturn()
        assertEquals(3, reads)
        secure = false
        device.onReturn()
        assertEquals(AgentsEmpty.NoScreenLock, empty())
    }

    private fun settingsMissing(missing: Set<String>, expected: List<String>, success: Boolean) {
        val seen = mutableListOf<Intent>()
        assertEquals(success, openScreenLockSettings { intent ->
            seen += intent
            if (intent.action in missing) throw ActivityNotFoundException()
        })
        assertEquals(expected, seen.map { it.action })
        assertEquals(BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            seen.first().getIntExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, 0))
    }
    @Test fun enrollmentFirstAllowsPin() = settingsMissing(emptySet(), listOf(Settings.ACTION_BIOMETRIC_ENROLL), true)
    @Test fun missingEnrollmentFallsBackToSecurity() = settingsMissing(setOf(Settings.ACTION_BIOMETRIC_ENROLL),
        listOf(Settings.ACTION_BIOMETRIC_ENROLL, Settings.ACTION_SECURITY_SETTINGS), true)
    @Test fun missingSecurityFallsBackToSettings() = settingsMissing(setOf(Settings.ACTION_BIOMETRIC_ENROLL, Settings.ACTION_SECURITY_SETTINGS),
        listOf(Settings.ACTION_BIOMETRIC_ENROLL, Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS), true)
    @Test fun allSettingsMissingDoesNotCrash() = settingsMissing(setOf(Settings.ACTION_BIOMETRIC_ENROLL, Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS),
        listOf(Settings.ACTION_BIOMETRIC_ENROLL, Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS), false)

    private fun command() = setupCommand(ExampleKey.SPKI, "kudde", "ED25519")

    @Test fun shareHasExactD3CommandTitleAndNoSubject() {
        val command = command()
        val send = setupShareIntent(command, res)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(command, send.getStringExtra(Intent.EXTRA_TEXT))
        assertArrayEquals(command.toByteArray(), send.getStringExtra(Intent.EXTRA_TEXT)!!.toByteArray())
        assertEquals("kudde setup command", send.getStringExtra(Intent.EXTRA_TITLE))
        assertFalse(send.hasExtra(Intent.EXTRA_SUBJECT))
        var chooser: Intent? = null
        assertTrue(shareSetupCommand(command, res) { chooser = it })
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        @Suppress("DEPRECATION")
        val target = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(send.action, target.action)
        assertEquals(command, target.getStringExtra(Intent.EXTRA_TEXT))
    }
    @Test fun copyIsExactD3CommandAndNotSensitive() {
        val command = command()
        val clip = setupCommandClip(command, res)
        assertEquals(1, clip.itemCount)
        assertEquals(command, clip.getItemAt(0).text.toString())
        assertArrayEquals(command.toByteArray(), clip.getItemAt(0).text.toString().toByteArray())
        assertTrue(clip.description.hasMimeType("text/plain"))
        assertFalse(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) ?: false)
    }
    @Test fun missingShareTargetLeavesCopyAvailable() {
        val command = command()
        assertFalse(shareSetupCommand(command, res) { throw ActivityNotFoundException() })
        assertEquals(command, setupCommandClip(command, res).getItemAt(0).text.toString())
    }
}
