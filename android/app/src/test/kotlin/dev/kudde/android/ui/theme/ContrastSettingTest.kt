package dev.kudde.android.ui.theme

import android.content.Context
import android.content.SharedPreferences
import dev.kudde.android.testing.KuddeTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

// The "Text contrast" setting on the Machines screen: Like the Mac (the tokens'
// default) or Higher (accessibility.followUp). Stored in its own app-private
// preferences file, nowhere else, and read back after a restart.
@RunWith(KuddeTestRunner::class)
class ContrastSettingTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun prefs(): SharedPreferences = app.getSharedPreferences(ContrastSetting.FILE, Context.MODE_PRIVATE)

    @Test
    fun aFreshInstallIsLikeTheMac() {
        assertEquals(Contrast.Mac, ContrastSetting(prefs()).contrast.value)
        assertEquals(Contrast.Default, ContrastSetting.open(app).contrast.value)
    }

    @Test
    fun toggleSwitchesBetweenTheTwoAndBack() {
        val setting = ContrastSetting(prefs())
        setting.toggle()
        assertEquals(Contrast.Aa, setting.contrast.value)
        setting.toggle()
        assertEquals(Contrast.Mac, setting.contrast.value)
    }

    @Test
    fun theChoiceSurvivesANewInstance() {
        ContrastSetting.open(app).toggle()
        assertEquals(Contrast.Aa, ContrastSetting.open(app).contrast.value)
        ContrastSetting.open(app).toggle()
        assertEquals(Contrast.Mac, ContrastSetting.open(app).contrast.value)
    }

    @Test
    fun onlyItsOwnKeyIsWritten() {
        ContrastSetting(prefs()).toggle()
        assertEquals(setOf(ContrastSetting.KEY), prefs().all.keys)
    }

    @Test
    fun anUnknownStoredValueFallsBackToTheDefault() {
        prefs().edit().putString(ContrastSetting.KEY, "brighter").commit()
        assertEquals(Contrast.Default, ContrastSetting(prefs()).contrast.value)
        prefs().edit().putInt(ContrastSetting.KEY, 3).commit()
        assertEquals(Contrast.Default, ContrastSetting(prefs()).contrast.value)
    }

    @Test
    fun theAppHoldsOneSettingForEveryScreen() {
        val kudde = app as dev.kudde.android.KuddeApp
        assertEquals(Contrast.Default, kudde.contrast.contrast.value)
        kudde.contrast.toggle()
        assertEquals(Contrast.Aa, ContrastSetting.open(app).contrast.value)
    }
}
