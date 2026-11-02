package dev.herdr.android.hub

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.herdr_mobile.HubInterface
import uniffi.herdr_mobile.HubListener
import uniffi.herdr_mobile.HubSnapshot

/**
 * Owns the process's one Rust [uniffi.herdr_mobile.Hub] and publishes its
 * snapshots as [snapshot] for Compose and to listeners, always on the main
 * thread. The hub is created on the first [start] or [resume]. Whether it
 * started is kept apart from whether the app wants it connected: when
 * creating it fails (no screen lock, a Keystore hiccup) the reason is in
 * [startError], and [ensureStarted] (every return to the foreground) and
 * [retry] try again, connecting it only while the app is resumed. A hub that
 * started is never created twice.
 *
 * [networkAllowed] reads GrapheneOS's Network permission. The hub hears it
 * before every resume, on every return to the foreground and on [retry]: with
 * it off the name lookup fails before any socket is refused, so the hub
 * could not tell the cause itself.
 */
class HubHost(
    private val createHub: (HubListener) -> HubInterface,
    private val mainExecutor: Executor,
    private val networkAllowed: () -> Boolean = { true },
) : AutoCloseable {
    private val state = MutableStateFlow(EMPTY)
    val snapshot: StateFlow<HubSnapshot> = state.asStateFlow()

    private val error = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = error.asStateFlow()

    @Volatile
    var hub: HubInterface? = null
        private set

    private val listeners = CopyOnWriteArrayList<(HubSnapshot) -> Unit>()

    // Called on the hub's thread: hand over and return.
    private val listener = object : HubListener {
        override fun onSnapshot(snapshot: HubSnapshot) {
            mainExecutor.execute {
                state.value = snapshot
                listeners.forEach { it(snapshot) }
            }
        }
    }

    fun addSnapshotListener(listener: (HubSnapshot) -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun start(): HubInterface? {
        hub?.let { return it }
        return try {
            createHub(listener).also {
                hub = it
                error.value = null
            }
        } catch (e: Exception) {
            error.value = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
            null
        }
    }

    /** The app is in the foreground (ForegroundTracker's lifecycle, not whether the hub started). */
    @Volatile
    private var resumed = false

    fun resume() {
        resumed = true
        start()?.let {
            it.setNetworkAllowed(networkAllowed())
            it.resume()
        }
    }

    fun suspend() {
        resumed = false
        hub?.suspend()
    }

    /** Starts a hub that failed to start, connecting it if the app is resumed; a running hub is left alone. */
    @Synchronized
    fun ensureStarted() {
        hub?.let {
            it.setNetworkAllowed(networkAllowed())
            return
        }
        val started = start() ?: return
        started.setNetworkAllowed(networkAllowed())
        if (resumed) started.resume()
    }

    fun retry() {
        val running = hub
        if (running == null) {
            ensureStarted()
        } else {
            running.setNetworkAllowed(networkAllowed())
            running.retry()
        }
    }

    @Synchronized
    override fun close() {
        (hub as? AutoCloseable)?.close()
        hub = null
    }

    private companion object {
        val EMPTY = HubSnapshot(emptyList(), emptyList(), emptyList(), null)
    }
}
