package dev.herdr.android.push

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.herdr.android.MainActivity
import dev.herdr.android.R

/**
 * The Stay connected status notification (design D3, D4): ongoing, silent,
 * on the low-importance "Status" channel, public on the lock screen as it
 * holds counts only. It has its own id and no tag, so it can never replace or
 * clear an agent alert (AndroidAlertSink uses id 1 with tags). A tap brings
 * the app back as it was left, the way the launcher does; it never opens a
 * terminal.
 */
class AndroidStatusSink(private val context: Context) : StatusSink {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun notification(line: StatusLine): Notification {
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val tap = PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, AlertChannel.Status.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(line.title)
            .apply { line.text?.let(::setContentText) }
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Shown at once, not after Android's 10 s grace for foreground-service notifications.
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun post(line: StatusLine) = manager.notify(ID, notification(line))

    override fun cancel() = manager.cancel(ID)

    /** Whether the notification can show: Android lets the app notify and the Status channel is not blocked. */
    fun shown(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(AlertChannel.Status.id)?.importance != NotificationManager.IMPORTANCE_NONE

    companion object {
        /** Alerts use 1 (AndroidAlertSink). */
        const val ID = 2
    }
}
