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
import dev.herdr.android.key.KeystoreFrameCipher
import dev.herdr.android.lifecycle.AlarmScheduler
import dev.herdr.android.lifecycle.ElapsedClock
import dev.herdr.android.lifecycle.ForegroundTracker
import dev.herdr.android.lifecycle.HandlerScheduler
import dev.herdr.android.push.AlertSetup
import dev.herdr.android.push.Alerts
import dev.herdr.android.push.AndroidAlertSink
import dev.herdr.android.push.PushStore
import dev.herdr.android.push.SilenceCheckService
import dev.herdr.android.push.timeOfDay
import dev.herdr.android.stay.StayConnectedService
import dev.herdr.android.stay.StayConnectedSetting
import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.agents.NewTerminal
import dev.herdr.android.ui.terminal.TerminalCache
import dev.herdr.android.ui.terminal.ZoomMemory
import dev.herdr.android.ui.theme.ContrastSetting
import dev.herdr.android.ui.transfer.ContentResolverStore
import dev.herdr.android.ui.transfer.MachineTransfer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush
import uniffi.herdr_mobile.AlertsRegistration
import uniffi.herdr_mobile.Hub
import uniffi.herdr_mobile.PushRegistration

/**
 * The process-wide objects: the phone key, the hub and its snapshots, the
 * fingerprint prompt on NeedsUserAuth, export/import of the machine list,
 * and the started-activity count that
 * resumes the hub in the foreground and suspends it 60 s after the last
 * activity stops, unless Stay connected is on: then StayConnectedService
 * keeps the hub connected and those 60 s only detach an open terminal
 * ([terminalIdle]). Nothing touches the Keystore or the network until the
 * first activity starts.
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

    /** The "New" sheet's state and its start; outlives activities so a rotation never loses a start. */
    lateinit var newTerminal: NewTerminal
        private set

    /** The "Text contrast" setting the theme follows, in its own preferences file. */
    lateinit var contrast: ContrastSetting
        private set

    /** The "Stay connected" option, in its own preferences file. */
    lateinit var stayConnected: StayConnectedSetting
        private set

    private val idle = MutableStateFlow(0)

    /** Bumped each time the app has spent 60 s in the background with Stay connected on; an open terminal detaches. */
    val terminalIdle: StateFlow<Int> = idle.asStateFlow()

    /** The instant-open cache of each terminal's last screen; used on [terminalCacheExecutor] only. */
    lateinit var terminalCache: TerminalCache
        private set

    /** One thread for the cache's files and Keystore calls, so loads and stores stay in order. */
    val terminalCacheExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "terminal-cache") }

    /** The zoom each terminal was left at while watching, for this run of the app. */
    val terminalZoom = ZoomMemory()

    /** The phone's alert state; the push service and the silence check use it with the app closed. */
    lateinit var pushStore: PushStore
        private set

    /** Pushes to notifications, silence to "Alert health". */
    lateinit var alerts: Alerts
        private set

    /** The agent of a tapped alert (AlertTapActivity), until the Agents home takes it. */
    val alertTap = MutableStateFlow<AgentKey?>(null)

    private var promptActivity: Activity? = null

    override fun onCreate() {
        super.onCreate()
        contrast = ContrastSetting.open(this)
        stayConnected = StayConnectedSetting.open(this)
        pushStore = PushStore.open(this)
        val sink = AndroidAlertSink(this).apply { createChannels() }
        alerts = Alerts(pushStore, sink, resources) { ms -> timeOfDay(this)(ms) }
        if (pushStore.registration != null) SilenceCheckService.schedule(this)
        keyManager = KeyManager(AndroidKeystoreBackend())
        hubHost = HubHost(
            createHub = { listener ->
                // The registration goes in before the hub connects: a hub
                // without it would take this phone's entries back.
                Hub(hubDirectory().path, keyManager.publicKey(), keyManager.signer(), listener)
                    .apply { setPush(pushStore.registration) }
            },
            mainExecutor = mainExecutor,
        )
        val main = Handler(mainLooper)
        userAuth = UserAuthGate(
            retry = hubHost::retry,
            stayConnected = stayConnected::isOn,
            scheduler = HandlerScheduler(main, ElapsedClock.SYSTEM),
            clock = ElapsedClock.SYSTEM,
        )
        hubHost.addSnapshotListener(userAuth::onSnapshot)
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        transfer = MachineTransfer(hubHost, ContentResolverStore(contentResolver), io)
        newTerminal = NewTerminal(hubHost, work = { io.launch { it.run() } }, main = mainExecutor)
        hubHost.addSnapshotListener(newTerminal::onSnapshot)
        foreground = ForegroundTracker(
            HandlerScheduler(main, ElapsedClock.SYSTEM),
            AlarmScheduler(getSystemService(AlarmManager::class.java), main),
            ElapsedClock.SYSTEM,
            onResume = hubHost::resume,
            onSuspend = hubHost::suspend,
            onForeground = hubHost::foreground,
            keepConnected = stayConnected::isOn,
            onBackgroundIdle = {
                idle.value++
                userAuth.onBackgroundIdle()
            },
            // Back after deep sleep past the deadline: no detach, but the key prompt re-arms.
            onCaughtUp = { userAuth.onBackgroundIdle() },
        )
        registerActivityLifecycleCallbacks(Callbacks())
        terminalCache = TerminalCache(File(noBackupFilesDir, "terminal-cache"), KeystoreFrameCipher(), System::currentTimeMillis)
        terminalCacheExecutor.execute { terminalCache.prune() }
        // A machine removed (or renamed) takes its cached screens with it.
        var machines: Set<String>? = null
        hubHost.addSnapshotListener { snapshot ->
            val names = snapshot.machines.map { it.name }.toSet()
            if (names != machines) {
                machines = names
                terminalCacheExecutor.execute { terminalCache.keepMachines(names) }
                pushStore.keepMachines(names)
            }
            // Silence counts from a machine's registration on (a dead topic never speaks at all).
            val now = System.currentTimeMillis()
            snapshot.machines
                .filter { it.alerts?.registration == AlertsRegistration.REGISTERED }
                .forEach { pushStore.enroll(it.name, now) }
        }
    }

    /**
     * "Turn on" or "Try again" on a card's Alerts row: the distributor the
     * person uses (asking which when there are several), then a registration;
     * its endpoint arrives in HerdrPushService.
     */
    fun turnOnAlerts(activity: Activity) {
        pushStore.setup = AlertSetup.Waiting
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(this, PUSH_INSTANCE) else pushStore.setup = AlertSetup.NoDistributor
        }
    }

    /** A new endpoint from the distributor: kept, and given to the hub for the machines. */
    fun alertsOn(registration: PushRegistration) {
        pushStore.registration = registration
        pushStore.setup = AlertSetup.Off
        // HubHost.start is synchronized on it: a hub being created now reads the store after this write.
        synchronized(hubHost) { hubHost.hub?.setPush(registration) }
        SilenceCheckService.schedule(this)
    }

    /** The distributor took the registration back: alerts are off, and machines lose this phone's entry. */
    fun alertsOff() {
        pushStore.registration = null
        pushStore.setup = AlertSetup.Off
        synchronized(hubHost) { hubHost.hub?.setPush(null) }
        SilenceCheckService.cancel(this)
    }

    companion object {
        /** The one UnifiedPush registration this app makes. */
        const val PUSH_INSTANCE = "alerts"
    }

    /** The machine list and seen turns: app-private and outside every backup. */
    private fun hubDirectory(): File = File(noBackupFilesDir, "hub").apply { mkdirs() }

    private inner class Callbacks : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            // From the foreground only (design D1): every start while on, so it comes back after a process end.
            if (stayConnected.isOn()) StayConnectedService.start(this@HerdrApp)
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
