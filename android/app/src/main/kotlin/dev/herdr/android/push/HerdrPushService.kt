package dev.herdr.android.push

import dev.herdr.android.HerdrApp
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import uniffi.herdr_mobile.PushRegistration

/**
 * The UnifiedPush receiver (alerts spec, "Alerts arrive through UnifiedPush,
 * not Google"): the distributor starts it with the app closed. A new endpoint
 * is stored and handed to the hub, which writes it into each machine's
 * `hdroid-push` at once if connected, else at the next connection; messages,
 * decrypted by the connector with its web-push keys, go to [Alerts].
 */
class HerdrPushService : PushService() {
    private val app get() = application as HerdrApp

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet
        if (keys == null) {
            app.pushStore.setup = AlertSetup.Failed("no encryption keys")
            return
        }
        app.alertsOn(PushRegistration(endpoint.url, keys.pubKey, keys.auth))
    }

    override fun onMessage(message: PushMessage, instance: String) {
        app.alerts.onMessage(message.content, message.decrypted, System.currentTimeMillis())
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        app.pushStore.setup = AlertSetup.Failed(reason.name.lowercase().replace('_', ' '))
    }

    override fun onUnregistered(instance: String) {
        app.alertsOff()
    }
}
