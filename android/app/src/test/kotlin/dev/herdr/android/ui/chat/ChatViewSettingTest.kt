package dev.herdr.android.ui.chat

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// spec "The chosen view stays when switching agents": the last chosen view is
// remembered across agents and app restarts; the terminal is the first view.
@RunWith(RobolectricTestRunner::class)
class ChatViewSettingTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun prefs(): SharedPreferences = app.getSharedPreferences(ChatViewSetting.FILE, Context.MODE_PRIVATE)

    @Test
    fun aFreshInstallShowsTheTerminal() {
        assertFalse(ChatViewSetting(prefs()).chat)
    }

    @Test
    fun theChoiceSurvivesARestart() {
        ChatViewSetting.open(app).choose(chat = true)
        assertTrue(ChatViewSetting.open(app).chat)
        ChatViewSetting.open(app).choose(chat = false)
        assertFalse(ChatViewSetting.open(app).chat)
    }

    @Test
    fun aValueOfAnotherTypeReadsAsTerminal() {
        prefs().edit().putString(ChatViewSetting.KEY, "chat").commit()
        assertFalse(ChatViewSetting(prefs()).chat)
    }
}
