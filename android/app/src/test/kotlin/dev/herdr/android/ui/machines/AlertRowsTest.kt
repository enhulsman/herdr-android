package dev.herdr.android.ui.machines

import android.content.res.Resources
import dev.herdr.android.push.AlertSetup
import dev.herdr.android.push.MachineAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.AlertsRegistration
import uniffi.herdr_mobile.AlertsView
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView
import uniffi.herdr_mobile.PushStatusView

// Render 7's alert rows (design/checkpoints/7-machines.png): "Alerts" with
// "Last accepted by ntfy 14:02 · last push here 14:02", "Mute alerts", "Send
// test alert" with "Test received 13:58"; an unreachable machine shows only
// "Alerts · Last push here 11:41". alerts spec: "Permission denied" (every
// machine shows alerts as off, with a way to grant it), "Endpoint changed while
// away", "Token revoked" ("Alerts failing: 403"), never "delivered"; "Test
// passes" ("test received", or that none came within 30 s).
@RunWith(RobolectricTestRunner::class)
class AlertRowsTest {
    private val res: Resources = RuntimeEnvironment.getApplication().resources

    private fun view(
        state: MachineState = MachineState.Connected("0.1.16"),
        registration: AlertsRegistration? = AlertsRegistration.REGISTERED,
        status: PushStatusView? = null,
    ) = MachineView(
        "workstation", "workstation.example.org", 22u, "me", null, state, null, null,
        registration?.let { AlertsView(it, status) },
    )

    private fun card(
        view: MachineView,
        registered: Boolean = true,
        setup: AlertSetup = AlertSetup.Off,
        permitted: Boolean = true,
        machine: MachineAlerts = MachineAlerts(),
        nowMs: Long = NOW,
    ): MachineCardUi {
        val alerts = PhoneAlerts(registered, setup, permitted, mapOf("workstation" to machine)) { "at${(it - NOW) / 1000}" }
        return machinesUi(HubSnapshot(listOf(view), emptyList(), emptyList(), null), null, nowMs, res, alerts = alerts).machines.single()
    }

    private fun status(endpoint: String?, at: Long? = NOW - 300_000, code: UShort? = 200u) =
        PushStatusView("0.1.0", at?.toULong(), code, endpoint, emptyList())

    @Test
    fun withoutAlertInfoTheCardHasNoAlertRows() {
        val c = machinesUi(HubSnapshot(listOf(view()), emptyList(), emptyList(), null), null, NOW, res).machines.single()
        assertNull(c.alerts)
        assertNull(c.mute)
        assertNull(c.test)
    }

    @Test
    fun offOnThePhoneOffersToTurnThemOn() {
        val c = card(view(registration = null), registered = false)
        assertEquals(AlertsRowUi("Off on this phone", AlertsButton.TurnOn), c.alerts)
        assertNull(c.mute)
        assertNull(c.test)
    }

    @Test
    fun waitingForTheDistributor() {
        val c = card(view(registration = null), registered = false, setup = AlertSetup.Waiting)
        assertEquals(AlertsRowUi("Waiting for the UnifiedPush app", AlertsButton.TryAgain), c.alerts)
    }

    @Test
    fun noDistributorSaysWhatIsMissing() {
        val c = card(view(registration = null), registered = false, setup = AlertSetup.NoDistributor)
        assertEquals(AlertsRowUi("Needs a UnifiedPush app, such as ntfy", AlertsButton.TryAgain), c.alerts)
    }

    @Test
    fun aFailedRegistrationSaysWhy() {
        val c = card(view(registration = null), registered = false, setup = AlertSetup.Failed("network"))
        assertEquals(AlertsRowUi("Could not register: network", AlertsButton.TryAgain), c.alerts)
    }

    @Test
    fun deniedPermissionShowsOffWithAWayToGrantIt() {
        val c = card(view(), permitted = false)
        assertEquals(AlertsRowUi("Off: notifications are not allowed", AlertsButton.Allow), c.alerts)
        assertNull(c.test)
    }

    @Test
    fun acceptedShowsWhenNtfyTookItAndTheLastPushHere() {
        val c = card(view(status = status("accepted 200")), machine = MachineAlerts(lastPushMs = NOW - 300_000))
        assertEquals(AlertsRowUi("Last accepted by ntfy at-300 · last push here at-300", null), c.alerts)
    }

    @Test
    fun aRefusedPushShowsAsFailingWithItsStatus() {
        assertEquals("Alerts failing: 403", card(view(status = status("refused 403", code = 403u))).alerts?.detail)
        assertEquals("Alerts failing: timed out", card(view(status = status("failed: timed out", code = null))).alerts?.detail)
        assertEquals("Alerts failing: host not allowed", card(view(status = status("ignored: host not allowed"))).alerts?.detail)
    }

    @Test
    fun registeredButNotPushedTo() {
        assertEquals("Registered, no push yet", card(view(status = status(null))).alerts?.detail)
    }

    @Test
    fun connectedWithoutAStatusDocumentMeansNoWatcher() {
        assertEquals("No answer from herdr-push", card(view(status = null)).alerts?.detail)
    }

    @Test
    fun anUnreachableMachineShowsOnlyTheLastPush() {
        val c = card(
            view(MachineState.Unreachable("refused"), status = null),
            machine = MachineAlerts(lastPushMs = NOW - 60_000),
        )
        assertEquals(AlertsRowUi("Last push here at-60", null), c.alerts)
        assertNull(c.test)
        assertNull(c.mute)
    }

    @Test
    fun anUnreachableMachineWithNothingKnownIsJustRegistered() {
        assertEquals("Registered", card(view(MachineState.Paused, status = null)).alerts?.detail)
    }

    @Test
    fun notYetAndEndpointChanged() {
        assertEquals("Registers at the next connection", card(view(MachineState.Paused, AlertsRegistration.NOT_YET)).alerts?.detail)
        assertEquals(
            "Not registered since endpoint change",
            card(view(MachineState.Paused, AlertsRegistration.ENDPOINT_CHANGED)).alerts?.detail,
        )
        // The hub without the registration yet (it starts with the app): not yet.
        assertEquals("Registers at the next connection", card(view(MachineState.Paused, registration = null)).alerts?.detail)
    }

    @Test
    fun aConnectedMachineOffersMuteAndTest() {
        val c = card(view(status = status("accepted 200")))
        assertEquals(false, c.mute)
        assertEquals(TestRowUi("Checks the whole path to this phone"), c.test)
    }

    @Test
    fun aMutedMachineKeepsItsToggleWhileAway() {
        val c = card(view(MachineState.Paused, status = null), machine = MachineAlerts(muted = true))
        assertEquals(true, c.mute)
    }

    @Test
    fun theTestRowFollowsTheRequest() {
        fun test(m: MachineAlerts, now: Long = NOW) = card(view(status = status("accepted 200")), machine = m, nowMs = now).test?.detail
        assertEquals("Waiting for the test alert", test(MachineAlerts(testAskedMs = NOW - 29_000)))
        assertEquals("No test alert within 30 s", test(MachineAlerts(testAskedMs = NOW - 30_000)))
        assertEquals("Test received at-2", test(MachineAlerts(testAskedMs = NOW - 5_000, testReceivedMs = NOW - 2_000)))
        // An answer to an earlier request does not count for the new one.
        assertEquals("Waiting for the test alert", test(MachineAlerts(testAskedMs = NOW - 1_000, testReceivedMs = NOW - 9_000)))
    }

    @Test
    fun theClockTicksEachSecondWhileATestWaits() {
        val snapshot = HubSnapshot(listOf(view()), emptyList(), emptyList(), null)
        val waiting = PhoneAlerts(true, AlertSetup.Off, true, mapOf("workstation" to MachineAlerts(testAskedMs = NOW - 1_000))) { "" }
        assertEquals(NOW / 1_000, machinesTick(snapshot, NOW, waiting))
        val done = waiting.copy(machines = mapOf("workstation" to MachineAlerts(testAskedMs = NOW - 31_000)))
        assertEquals(NOW / 60_000, machinesTick(snapshot, NOW, done))
    }

    private companion object {
        const val NOW = 1_730_650_000_000L
    }
}
