package dev.herdr.android.spike

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

// Task M0.7, Spike C: register with the UnifiedPush distributor (ntfy), hand
// the endpoint and web push keys to the sender over logcat (tag
// herdr-spike-push), and show each message the connector decrypts with its
// length and SHA-256, so the sender can check the bytes arrived intact.
private const val TAG = "herdr-spike-push"
private const val INSTANCE = "spike"
private const val CHANNEL = "spike-push"

class PushSpikeActivity : ComponentActivity() {
    private var report by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(transparent), SystemBarStyle.dark(transparent))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Push spike", NotificationManager.IMPORTANCE_HIGH),
        )
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        report = "distributors: ${UnifiedPush.getDistributors(this)}\n" +
            "current: ${UnifiedPush.getAckDistributor(this)}\n"

        setContent {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF17191D))
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicText(
                    "Register",
                    Modifier
                        .clickable { register() }
                        .padding(vertical = 12.dp),
                    style = TextStyle(color = Color(0xFF8FC1FF), fontSize = 18.sp),
                )
                BasicText(
                    report,
                    style = TextStyle(color = Color(0xFFEEEEEF), fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                )
            }
        }
    }

    private fun register() {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { ok ->
            val line = "distributor ${if (ok) UnifiedPush.getAckDistributor(this) else "none chosen"}"
            Log.i(TAG, line)
            report += "$line\n"
            if (ok) {
                UnifiedPush.register(this, INSTANCE)
                report += "registration sent; the endpoint arrives in logcat\n"
            }
        }
    }
}

class SpikePushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet
        // One line the sender reads: endpoint, p256dh, auth.
        Log.i(TAG, "endpoint ${endpoint.url} ${keys?.pubKey} ${keys?.auth} temporary=${endpoint.temporary}")
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val sha = MessageDigest.getInstance("SHA-256").digest(message.content)
            .joinToString("") { "%02x".format(it) }
        val summary = "message ${message.content.size} bytes, decrypted=${message.decrypted}, sha256 $sha"
        Log.i(TAG, summary)
        val text = message.content.decodeToString().takeIf { message.content.size < 300 && '\u0000' !in it }
        notify("Push spike: ${message.content.size} bytes", text ?: "sha256 ${sha.take(16)}…, decrypted=${message.decrypted}")
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.i(TAG, "registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        Log.i(TAG, "unregistered")
    }

    private fun notify(title: String, text: String) {
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build()
        getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
