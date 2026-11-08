package dev.herdr.android.stay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.herdr.android.HerdrApp
import dev.herdr.android.push.AndroidStatusSink
import dev.herdr.android.push.StatusNotifier
import dev.herdr.android.push.StatusText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process at foreground priority while "Stay connected" is on, so
 * the hub's connections survive the background and Doze (stay-connected spec;
 * design D1). It holds no state: the hub lives on HerdrApp. Started only from
 * the foreground ([start]: the option turned on, or an activity start while
 * it is on) and stopped when the option is turned off. START_NOT_STICKY: when
 * Android or the person ends the process, nothing restarts it until the app
 * is opened. While it runs it posts the status notification and retries on
 * each unlock of the phone (design D6).
 */
class StayConnectedService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var sink: AndroidStatusSink
    private lateinit var notifier: StatusNotifier

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) app().userAuth.onUnlocked()
        }
    }

    private fun app() = application as HerdrApp

    override fun onCreate() {
        super.onCreate()
        sink = AndroidStatusSink(this)
        notifier = StatusNotifier(sink)
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlocked, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlocked, filter)
        }
        val hub = app().hubHost
        scope.launch {
            combine(hub.snapshot, hub.startError) { snapshot, error -> StatusText.of(snapshot, error, resources) }
                .collect(notifier::update)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must call startForeground within Android's few seconds, also one that only stops.
        val hub = app().hubHost
        val note = sink.notification(StatusText.of(hub.snapshot.value, hub.startError.value, resources))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AndroidStatusSink.ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(AndroidStatusSink.ID, note)
        }
        // Turned off between the start and now: go.
        if (!app().stayConnected.isOn()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        notifier.stop()
        unregisterReceiver(unlocked)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private fun intent(context: Context) = Intent(context, StayConnectedService::class.java)

        /** From the foreground only: Android 12+ refuses a foreground service started from the background. */
        fun start(context: Context) {
            try {
                context.startForegroundService(intent(context))
            } catch (_: IllegalStateException) {
                // Not in the foreground after all (ForegroundServiceStartNotAllowedException):
                // the next activity start tries again.
            }
        }

        fun stop(context: Context) {
            context.stopService(intent(context))
        }
    }
}
