package dev.herdr.android.stay

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// stay-connected spec, "Stay connected is an option, off by default": off on a
// fresh install and after an update from a version without it (absent = off),
// kept in its own app-private preferences file, read back after a restart.
@RunWith(RobolectricTestRunner::class)
class StayConnectedSettingTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun prefs(): SharedPreferences = app.getSharedPreferences(StayConnectedSetting.FILE, Context.MODE_PRIVATE)

    @Test
    fun aFreshInstallIsOff() {
        assertFalse(StayConnectedSetting(prefs()).isOn())
        assertFalse(StayConnectedSetting.open(app).on.value)
    }

    @Test
    fun toggleSwitchesOnAndOff() {
        val setting = StayConnectedSetting(prefs())
        setting.toggle()
        assertTrue(setting.isOn())
        assertTrue(setting.on.value)
        setting.toggle()
        assertFalse(setting.isOn())
    }

    @Test
    fun theChoiceSurvivesANewInstance() {
        StayConnectedSetting.open(app).toggle()
        assertTrue(StayConnectedSetting.open(app).isOn())
    }

    @Test
    fun itHasItsOwnFileAndKey() {
        StayConnectedSetting(prefs()).toggle()
        assertEquals(setOf(StayConnectedSetting.KEY), prefs().all.keys)
        assertTrue(StayConnectedSetting.FILE != dev.herdr.android.ui.theme.ContrastSetting.FILE)
    }

    @Test
    fun aValueOfAnotherTypeReadsAsOff() {
        prefs().edit().putString(StayConnectedSetting.KEY, "yes").commit()
        assertFalse(StayConnectedSetting(prefs()).isOn())
    }
}
