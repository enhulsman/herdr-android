package dev.herdr.android.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import dev.herdr.android.MainActivity
import dev.herdr.android.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Posts [AlertNote]s through Android's notification manager, on the three
 * alert channels it creates (alerts spec, "Three notification channels"), and
 * creates the fourth, "Status", for AndroidStatusSink: "Needs
 * you" at high importance (sound and the lock screen, as Android settings
 * allow), "Finished" and "Alert health" at low importance, silent. Sound,
 * vibration and Do Not Disturb are left to Android. Tapping an agent's
 * notification opens its terminal through [AlertTapActivity]; others open the app.
 */
class AndroidAlertSink(private val context: Context) : AlertSink {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Creating a channel that exists only renames it; the person's settings stay. */
    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                // No lock-screen override: Android ignores one set by the app
                // (checked on the phone, session-names task 3.0); each note's
                // public version and the phone's own setting decide.
                NotificationChannel(AlertChannel.NeedsYou.id, context.getString(R.string.channel_needs_you), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(AlertChannel.Finished.id, context.getString(R.string.channel_finished), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(AlertChannel.Health.id, context.getString(R.string.channel_alert_health), NotificationManager.IMPORTANCE_LOW),
                // Stay connected (design D4): quiet, but with its status-bar icon.
                NotificationChannel(AlertChannel.Status.id, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    override fun post(note: AlertNote) {
        // An agent's tap goes through AlertTapActivity, which only the app can start.
        val intent = note.open?.let { AlertTapActivity.intent(context, it) }
            ?: Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            context,
            note.tag.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, note.channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(note.title)
            .apply { note.text?.let(::setContentText) }
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(if (note.channel == AlertChannel.Health) Notification.CATEGORY_STATUS else Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .apply {
                note.publicTitle?.let { title ->
                    setPublicVersion(
                        Notification.Builder(context, note.channel.id)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(title)
                            .build(),
                    )
                }
            }
            .build()
        manager.notify(note.tag, ID, notification)
    }

    override fun cancel(tag: String) = manager.cancel(tag, ID)

    private companion object {
        /** Notifications are told apart by tag alone. */
        const val ID = 1
    }
}

/** "14:02", or "2:02 PM" when the phone uses the 12-hour clock, in the phone's time zone. */
fun timeOfDay(context: Context): (Long) -> String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    val format = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    return { ms -> format.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())) }
}
