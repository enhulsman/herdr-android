package dev.herdr.android.hub

import dev.herdr.android.lifecycle.FakeDevice
import dev.herdr.android.lifecycle.ForegroundTracker
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import uniffi.herdr_mobile.Hub
import uniffi.herdr_mobile.HubInterface
import uniffi.herdr_mobile.HubListener
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.PushRegistration
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.SignException
import uniffi.herdr_mobile.SshSigner
import uniffi.herdr_mobile.StartPlace
import uniffi.herdr_mobile.TerminalListener
import uniffi.herdr_mobile.TerminalView
import uniffi.herdr_mobile.UniffiLib
import uniffi.herdr_mobile.uniffiCallbackInterfaceHubListener
import uniffi.herdr_mobile.uniffiCallbackInterfaceSshSigner

class HubHostTest {
    /** Runs posted work when [drain] is called, like the main looper. */
    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queue += command
        }
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private val main = QueueExecutor()
    private val fake = FakeHub()
    private var created = 0
    private var listener: HubListener? = null
    private val host = HubHost(
        createHub = { l ->
            created++
            listener = l
            fake
        },
        mainExecutor = main,
    )

    @Test
    fun snapshotsReachTheStateAndListenersOnTheMainThread() {
        val seen = mutableListOf<HubSnapshot>()
        host.addSnapshotListener { seen += it }
        host.start()
        val next = snapshot("workstation" to MachineState.Connecting)
        listener!!.onSnapshot(next)
        // Not yet: the hub's thread only posts.
        assertEquals(HubSnapshot(emptyList(), emptyList(), emptyList(), null), host.snapshot.value)
        main.drain()
        assertEquals(next, host.snapshot.value)
        assertEquals(listOf(next), seen)
    }

    @Test
    fun snapshotsArriveInOrder() {
        host.start()
        val a = snapshot("workstation" to MachineState.Connecting)
        val b = snapshot("workstation" to MachineState.NeedsUserAuth)
        listener!!.onSnapshot(a)
        listener!!.onSnapshot(b)
        main.drain()
        assertEquals(b, host.snapshot.value)
    }

    @Test
    fun theHubIsCreatedOnce() {
        assertSame(fake, host.start())
        assertSame(fake, host.start())
        assertEquals(1, created)
    }

    @Test
    fun resumeSuspendAndRetryGoToTheHub() {
        host.resume()
        host.retry()
        host.suspend()
        assertEquals(listOf("resume", "retry", "suspend"), fake.calls)
        // resume() created the hub first.
        assertEquals(1, created)
    }

    @Test
    fun aHubThatCannotStartIsReportedAndTriedAgainOnTheNextResume() {
        var fail = true
        val failing = HubHost(
            createHub = {
                if (fail) throw IllegalStateException("Secure lock screen must be enabled")
                fake
            },
            mainExecutor = main,
        )
        failing.resume()
        assertNull(failing.hub)
        assertEquals("Secure lock screen must be enabled", failing.startError.value)
        failing.suspend() // nothing to suspend, no crash
        fail = false
        failing.resume()
        assertNotNull(failing.hub)
        assertNull(failing.startError.value)
        assertEquals(listOf("resume"), fake.calls)
    }

    private var fail = true
    private var attempts = 0
    private val flaky = HubHost(
        createHub = {
            attempts++
            if (fail) throw java.security.KeyStoreException("keystore not ready")
            fake
        },
        mainExecutor = main,
    )
    private val clock = FakeDevice()
    private val tracker = ForegroundTracker(
        clock.timer,
        clock.alarm,
        clock,
        onResume = flaky::resume,
        onSuspend = flaky::suspend,
        onForeground = flaky::foreground,
    )

    @Test
    fun aHubThatFailedToStartStartsWhenTheUserReturnsWithinSixtySeconds() {
        tracker.activityStarted()
        assertNull(flaky.hub)
        fail = false
        tracker.activityStopped()
        clock.advance(20_000)
        tracker.activityStarted()
        assertNotNull(flaky.hub)
        // Started and connected, since the app is in the foreground.
        assertEquals(listOf("resume"), fake.calls)
    }

    @Test
    fun aHealthyHubIsNotRestartedOnReturnButToldOfIt() {
        fail = false
        tracker.activityStarted()
        repeat(3) {
            tracker.activityStopped()
            clock.advance(20_000)
            tracker.activityStarted()
        }
        assertEquals(1, attempts)
        // Each return within 60 s: machines waiting for Network try again.
        assertEquals(listOf("resume") + List(4) { "foreground" }, fake.calls)
    }

    @Test
    fun aHubThatFailedToStartIsNotConnectedWhileTheAppIsSuspended() {
        tracker.activityStarted()
        tracker.activityStopped()
        clock.advance(60_000)
        fail = false
        flaky.ensureStarted()
        assertNotNull(flaky.hub)
        assertEquals(emptyList<String>(), fake.calls)
    }

    @Test
    fun retryStartsAHubThatFailedToStart() {
        tracker.activityStarted()
        fail = false
        flaky.retry()
        assertNotNull(flaky.hub)
        assertEquals(listOf("resume"), fake.calls)
        flaky.retry()
        assertEquals(listOf("resume", "retry"), fake.calls)
    }

    @Test
    fun theRealHubReportsAnAddedMachineThroughTheListener() {
        // The generated bindings and the host build of the Rust library, end to end.
        val dir = Files.createTempDirectory("hub").toFile()
        val spki = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public.encoded
        val signer = object : SshSigner {
            override fun sign(data: ByteArray): ByteArray = throw SignException.NeedsUserAuth()
        }
        val direct = Executor { it.run() }
        // The Rust library is loaded once per test JVM, but each class loader
        // (this one and Robolectric's sandbox) has its own copy of the bindings,
        // and the last one to load registered its callback tables. Register
        // this loader's, or Rust calls a handle map that never saw the listener.
        uniffiCallbackInterfaceHubListener.register(UniffiLib)
        uniffiCallbackInterfaceSshSigner.register(UniffiLib)
        val real = HubHost(createHub = { l -> Hub(dir.path, spki, signer, l) }, mainExecutor = direct)
        try {
            real.start()!!.addMachine("server", "192.0.2.10", 22u, "me")
            val deadline = System.nanoTime() + 10_000_000_000
            while (real.snapshot.value.machines.isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            val machine = real.snapshot.value.machines.single()
            assertEquals("server", machine.name)
            // The hub starts suspended: nothing dials until resume().
            assertEquals(MachineState.Paused, machine.state)
        } finally {
            real.close()
            dir.deleteRecursively()
        }
    }
}

class FakeHub : HubInterface {
    val calls = mutableListOf<String>()

    /** Thrown by the next add, confirm, forget, remove or import, as the hub would refuse it. */
    var refuse: Exception? = null

    private fun refused() = refuse?.let { refuse = null; throw it }

    override fun addMachine(name: String, host: String, port: UShort, user: String) {
        refused()
        calls += "add $name $host $port $user"
    }
    override fun confirmHostKey(name: String, fingerprint: String) {
        refused()
        calls += "confirm $name $fingerprint"
    }
    /** What exportMachines returns. */
    var exportJson = "{}"

    /** What the next import returns: how many machines it added. */
    var importAdds: UInt = 0u

    override fun exportMachines(): String {
        calls += "export"
        return exportJson
    }
    override fun forgetHostKey(name: String) {
        refused()
        calls += "forget $name"
    }
    override fun importMachines(json: String): UInt {
        refused()
        calls += "import $json"
        return importAdds
    }
    override fun markSeen(machine: String, address: String) { calls += "seen $machine $address" }
    override fun setPush(registration: PushRegistration?) { calls += "push ${registration?.endpoint}" }
    override fun sendTest(machine: String) { calls += "test $machine" }
    override fun removeMachine(name: String) {
        refused()
        calls += "remove $name"
    }
    override fun foreground() { calls += "foreground" }
    override fun resume() { calls += "resume" }
    override fun retry() { calls += "retry" }
    override fun snapshot(): HubSnapshot = HubSnapshot(emptyList(), emptyList(), emptyList(), null)
    override fun suspend() { calls += "suspend" }

    /** The terminal screen opens terminals through TerminalOpener, faked in its own tests. */
    override fun openChatSpike(machine: String, address: String): uniffi.herdr_mobile.ChatSpike = throw UnsupportedOperationException()

    override fun openTerminal(
        machine: String,
        address: String,
        phoneCols: UShort,
        phoneRows: UShort,
        atPhoneSize: Boolean,
        listener: TerminalListener,
    ): TerminalView = error("FakeHub does not open terminals")
    override fun updateMachine(name: String, host: String, port: UShort, user: String) { calls += "update $name" }

    /** What the next start returns: the new terminal's address. */
    var startAddress = "t9"

    override fun startSheetOpened(machine: String) { calls += "sheet $machine" }
    override fun startTerminal(machine: String, place: StartPlace, agent: String?): String {
        refused()
        calls += "start $machine ${place.title} ${agent ?: "terminal"}"
        return startAddress
    }
}
