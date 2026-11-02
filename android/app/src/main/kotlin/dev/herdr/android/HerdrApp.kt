package dev.herdr.android

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import dev.herdr.android.hub.BiometricPromptHost
import dev.herdr.android.hub.HubHost
import dev.herdr.android.hub.UserAuthGate
import dev.herdr.android.key.AndroidKeystoreBackend
import dev.herdr.android.key.KeyManager
import dev.herdr.android.lifecycle.ForegroundTracker
import dev.herdr.android.lifecycle.AlarmScheduler
import dev.herdr.android.lifecycle.ElapsedClock
import dev.herdr.android.lifecycle.HandlerScheduler
import dev.herdr.android.ui.theme.ContrastSetting
import dev.herdr.android.ui.transfer.ContentResolverStore
import dev.herdr.android.ui.transfer.MachineTransfer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import uniffi.herdr_mobile.Hub

/**
 * The process-wide objects: the phone key, the hub and its snapshots, the
 * fingerprint prompt on NeedsUserAuth, export/import of the machine list,
 * and the started-activity count that
 * resumes the hub in the foreground and suspends it 60 s after the last
 * activity stops. Nothing touches the Keystore or the network until the first
 * activity starts.
 */
class HerdrApp : Application() {
    lateinit var keyManager: KeyManager
        private set
    lateinit var hubHost: HubHost
        private set
    lateinit var userAuth: UserAuthGate
        private set
    lateinit var foreground: ForegroundTracker
        private set

    /** Export and import of the machine list; outlives activities so a rotation never cuts one short. */
    lateinit var transfer: MachineTransfer
        private set

    /** The "Text contrast" setting the theme follows, in its own preferences file. */
    lateinit var contrast: ContrastSetting
        private set

    private var promptActivity: Activity? = null

    override fun onCreate() {
        super.onCreate()
        contrast = ContrastSetting.open(this)
        keyManager = KeyManager(AndroidKeystoreBackend())
        hubHost = HubHost(
            createHub = { listener ->
                Hub(hubDirectory().path, keyManager.publicKey(), keyManager.signer(), listener)
            },
            mainExecutor = mainExecutor,
        )
        userAuth = UserAuthGate(retry = hubHost::retry)
        hubHost.addSnapshotListener(userAuth::onSnapshot)
        transfer = MachineTransfer(
            hubHost,
            ContentResolverStore(contentResolver),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        val main = Handler(mainLooper)
        foreground = ForegroundTracker(
            HandlerScheduler(main, ElapsedClock.SYSTEM),
            AlarmScheduler(getSystemService(AlarmManager::class.java), main),
            ElapsedClock.SYSTEM,
            onResume = hubHost::resume,
            onSuspend = hubHost::suspend,
            onForeground = hubHost::ensureStarted,
        )
        registerActivityLifecycleCallbacks(Callbacks())
    }

    /** The machine list and seen turns: app-private and outside every backup. */
    private fun hubDirectory(): File = File(noBackupFilesDir, "hub").apply { mkdirs() }

    private inner class Callbacks : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            foreground.activityStarted()
            promptActivity = activity
            userAuth.onHost(BiometricPromptHost(activity))
        }

        override fun onActivityStopped(activity: Activity) {
            if (promptActivity === activity) {
                promptActivity = null
                userAuth.onHost(null)
            }
            foreground.activityStopped()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
