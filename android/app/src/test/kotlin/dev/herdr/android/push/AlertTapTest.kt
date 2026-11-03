package dev.herdr.android.push

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import dev.herdr.android.HerdrApp
import dev.herdr.android.MainActivity
import dev.herdr.android.ui.agents.AgentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// Codex review, M3: only a tap on the app's own notification may open a
// terminal. The tap goes to AlertTapActivity, which other apps cannot start
// (not exported); the exported launcher takes no terminal from an intent.
@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION") // getActivityInfo(_, Int): Robolectric 4.17 lacks the ComponentInfoFlags overload.
class AlertTapTest {
    private val app get() = RuntimeEnvironment.getApplication() as HerdrApp
    private val context: Context get() = app

    @Test
    fun theTapActivityIsNotExportedAndTheLauncherIs() {
        val pm = context.packageManager
        val tap = pm.getActivityInfo(ComponentName(context, AlertTapActivity::class.java), 0)
        assertFalse(tap.exported)
        val main = pm.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertTrue(main.exported)
    }

    @Test
    fun anAgentNotificationTapsIntoTheTapActivity() {
        AndroidAlertSink(context).apply { createChannels() }.post(
            AlertNote(AlertChannel.NeedsYou, "agent:workstation/t3", "docs nav needs you", "workstation", AgentKey("workstation", "t3")),
        )
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(AlertTapActivity::class.java.name, intent.component?.className)
        assertEquals("workstation", intent.getStringExtra(AlertTapActivity.EXTRA_MACHINE))
        assertEquals("t3", intent.getStringExtra(AlertTapActivity.EXTRA_TERMINAL))
    }

    @Test
    fun aHealthNoticeOpensTheAppWithoutAnAgent() {
        AndroidAlertSink(context).apply { createChannels() }.post(
            AlertNote(AlertChannel.Health, "health:workstation", "No word", null, open = null),
        )
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull(intent.getStringExtra(AlertTapActivity.EXTRA_MACHINE))
    }

    @Test
    fun theTapActivityHandsTheAgentToTheAppAndOpensIt() {
        val intent = AlertTapActivity.intent(context, AgentKey("workstation", "t3"))
        val controller = Robolectric.buildActivity(AlertTapActivity::class.java, intent).create()
        assertEquals(AgentKey("workstation", "t3"), app.alertTap.value)
        val next = shadowOf(controller.get()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertNull("the launcher gets no agent in its intent", next.extras?.getString(AlertTapActivity.EXTRA_MACHINE))
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun aTapWithoutAnAgentHandsNothingOver() {
        val controller = Robolectric.buildActivity(AlertTapActivity::class.java).create()
        assertNull(app.alertTap.value)
        assertTrue(controller.get().isFinishing)
    }
}
