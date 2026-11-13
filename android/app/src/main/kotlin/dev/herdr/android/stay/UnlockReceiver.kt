package dev.herdr.android.stay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Hears each unlock of the phone (ACTION_USER_PRESENT) while Stay connected
 * runs (design D6). Registered exported: SystemUI sends this broadcast from an
 * app uid, not the system uid, so a not-exported receiver never gets it (seen
 * on Android 17). It is a protected broadcast, so no other app can send it.
 */
class UnlockReceiver(private val onUnlocked: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) onUnlocked()
    }

    fun register(context: Context) {
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(this, filter)
        }
    }

    fun unregister(context: Context) = context.unregisterReceiver(this)
}
