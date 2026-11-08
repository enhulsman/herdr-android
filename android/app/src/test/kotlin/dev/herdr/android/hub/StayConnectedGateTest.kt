package dev.herdr.android.hub

import dev.herdr.android.lifecycle.FakeDevice
import dev.herdr.android.lifecycle.ForegroundTracker
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.herdr_mobile.MachineState

// stay-connected spec, "Reconnecting in the background never prompts" (design
// D6): with the option on, an unlock or an activity start first retries
// without a prompt; the prompt comes only if the key is still needed, after
// the retried attempt (the first snapshot without NeedsUserAuth ends the
// silent retry; a stale or repeated NeedsUserAuth meanwhile is ignored) or
// after a 10 s fallback when the Connecting in between was dropped. Each
// unlock retries machines waiting for the key or a backoff once. Background
// idle re-arms the prompt as V1's suspend does. Option off: UserAuthGateTest.
class StayConnectedGateTest {
    private val device = FakeDevice()
    private var retries = 0
    private var on = true
    private val gate = UserAuthGate(
        retry = { retries++ },
        stayConnected = { on },
        scheduler = device.timer,
        clock = device,
    )
    private val host = FakePromptHost()

    private fun needsKey() = gate.onSnapshot(snapshot("workstation" to MachineState.NeedsUserAuth))
    private fun connecting() = gate.onSnapshot(snapshot("workstation" to MachineState.Connecting))
    private fun connected() = gate.onSnapshot(snapshot("workstation" to MachineState.Connected("0.3.0")))

    /** The app is in the background and the machine has fallen into NeedsUserAuth. */
    private fun keyNeededInTheBackground() {
        gate.onHost(null)
        needsKey()
    }

    @Test
    fun nothingPromptsOrRetriesInTheBackgroundByItself() {
        keyNeededInTheBackground()
        device.advance(60_000)
        assertEquals(0, host.shown)
        assertEquals(0, retries)
    }

    // (a)
    @Test
    fun unlockThenActivityStartRetriesOnceWithoutAPrompt() {
        keyNeededInTheBackground()
        gate.onUnlocked()
        gate.onHost(host)
        assertEquals(1, retries)
        assertEquals(0, host.shown)
        connecting()
        connected()
        device.advance(60_000)
        assertEquals(1, retries)
        assertEquals(0, host.shown)
    }

    // (b)
    @Test
    fun activityStartThenUnlockRetriesOnceWithoutAPrompt() {
        keyNeededInTheBackground()
        gate.onHost(host)
        gate.onUnlocked()
        assertEquals(1, retries)
        assertEquals(0, host.shown)
        connecting()
        connected()
        assertEquals(0, host.shown)
    }

    // (c)
    @Test
    fun aStaleNeedsUserAuthQueuedBeforeTheRetryIsIgnored() {
        keyNeededInTheBackground()
        gate.onHost(host)
        needsKey()
        needsKey()
        assertEquals(0, host.shown)
        connecting()
        connected()
        assertEquals(0, host.shown)
        assertEquals(1, retries)
    }

    // (d)
    @Test
    fun aDroppedConnectingPromptsAtTheFallback() {
        keyNeededInTheBackground()
        gate.onHost(host)
        // The retried attempt fails again and only its NeedsUserAuth arrives.
        needsKey()
        device.advance(9_999)
        assertEquals(0, host.shown)
        device.advance(1)
        assertEquals(1, host.shown)
        host.finish(success = true)
        assertEquals(2, retries)
    }

    @Test
    fun theFallbackWithNoActivityPromptsNothingAndTheNextStartRetriesAgain() {
        keyNeededInTheBackground()
        gate.onUnlocked()
        device.advance(10_000)
        assertEquals(0, host.shown)
        gate.onHost(host)
        assertEquals(2, retries)
        assertEquals(0, host.shown)
    }

    @Test
    fun aConnectedResultCancelsTheFallback() {
        keyNeededInTheBackground()
        gate.onHost(host)
        connected()
        device.advance(60_000)
        assertEquals(0, host.shown)
    }

    // (e)
    @Test
    fun armedAndTheRetryReallyFailsGivesExactlyOnePrompt() {
        keyNeededInTheBackground()
        gate.onHost(host)
        connecting()
        needsKey()
        assertEquals(1, host.shown)
        host.finish(success = true)
        // V1's no-loop rule: the retried attempt failing again prompts no more.
        connecting()
        needsKey()
        device.advance(60_000)
        assertEquals(1, host.shown)
        assertEquals(2, retries)
    }

    // (g)
    @Test
    fun unlockWithOnlyABackoffMachineRetriesOnce() {
        gate.onHost(null)
        gate.onSnapshot(snapshot("workstation" to MachineState.Unreachable("refused")))
        gate.onUnlocked()
        assertEquals(1, retries)
        // Not a silent retry: a later NeedsUserAuth in the foreground prompts at once.
        gate.onHost(host)
        connecting()
        needsKey()
        assertEquals(1, host.shown)
    }

    @Test
    fun unlockWithEveryMachineConnectedRetriesNothing() {
        gate.onHost(null)
        connected()
        gate.onUnlocked()
        gate.onSnapshot(snapshot("workstation" to MachineState.HostKeyUnconfirmed("SHA256:abc", "ED25519")))
        gate.onUnlocked()
        assertEquals(0, retries)
    }

    @Test
    fun eachUnlockRetriesOnce() {
        keyNeededInTheBackground()
        gate.onUnlocked()
        connecting()
        needsKey()
        gate.onUnlocked()
        assertEquals(2, retries)
        assertEquals(0, host.shown)
    }

    @Test
    fun anUnlockDuringASilentRetryDoesNothing() {
        keyNeededInTheBackground()
        gate.onUnlocked()
        gate.onUnlocked()
        assertEquals(1, retries)
    }

    // (h)
    @Test
    fun aCancelThenBackgroundIdleThenAReturnPromptsExactlyOnce() {
        gate.onHost(host)
        needsKey()
        host.finish(success = false)
        gate.onHost(null)
        gate.onBackgroundIdle()
        // An hour later Ezra taps an alert, the phone never locked in between.
        gate.onHost(host)
        assertEquals(1, retries)
        assertEquals(1, host.shown)
        connecting()
        needsKey()
        assertEquals(2, host.shown)
        host.finish(success = true)
        assertEquals(2, retries)
    }

    // (i)
    @Test
    fun aCancelThenARestartWithinSecondsNeitherRetriesNorPrompts() {
        gate.onHost(host)
        needsKey()
        host.finish(success = false)
        // The device-credential screen or a rotation stops and restarts the activity.
        gate.onHost(null)
        gate.onHost(host)
        device.advance(60_000)
        assertEquals(0, retries)
        assertEquals(1, host.shown)
    }

    // (j)
    @Test
    fun anUnlockWhileNotArmedRetriesButAFailureDoesNotPrompt() {
        gate.onHost(host)
        needsKey()
        host.finish(success = false)
        gate.onHost(null)
        gate.onUnlocked()
        assertEquals(1, retries)
        gate.onHost(host)
        connecting()
        needsKey()
        device.advance(60_000)
        assertEquals(1, host.shown)
        assertEquals(1, retries)
    }

    @Test
    fun aPromptAlreadyShowingIsNotJoinedByASilentRetry() {
        gate.onHost(host)
        needsKey()
        gate.onHost(null)
        gate.onHost(host)
        assertEquals(1, host.shown)
        assertEquals(0, retries)
    }

    // (f), the switch side: off means V1 even when built with the new parameters.
    @Test
    fun withTheOptionOffAnActivityStartPromptsAtOnce() {
        on = false
        keyNeededInTheBackground()
        gate.onHost(host)
        assertEquals(1, host.shown)
        assertEquals(0, retries)
    }

    // (h) across deep sleep, with the tracker wired as HerdrApp wires it: both
    // deadline callbacks held back past 60 s, so no background idle ran; the
    // return still re-arms the prompt (V1's suspend does it with Paused).
    @Test
    fun aCancelThenDeepSleepPastTheDeadlineThenAReturnRetriesThenPromptsOnce() {
        val tracker = ForegroundTracker(
            device.timer,
            device.alarm,
            device,
            onResume = {},
            onSuspend = {},
            keepConnected = { on },
            onBackgroundIdle = gate::onBackgroundIdle,
            onCaughtUp = gate::onBackgroundIdle,
        )
        fun start() {
            tracker.activityStarted()
            gate.onHost(host)
        }
        fun stop() {
            gate.onHost(null)
            tracker.activityStopped()
        }
        start()
        needsKey()
        host.finish(success = false)
        stop()
        device.sleep(5 * 60_000)
        start()
        assertEquals(1, retries)
        assertEquals(1, host.shown)
        connecting()
        needsKey()
        assertEquals(2, host.shown)
    }
}
