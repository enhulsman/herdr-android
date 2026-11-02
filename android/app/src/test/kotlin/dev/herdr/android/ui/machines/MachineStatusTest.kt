package dev.herdr.android.ui.machines

import android.content.res.Resources
import dev.herdr.android.hub.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView

// machine-connection spec: "A second machine is off" (unreachable with the
// time it was last connected), "herdr-core missing", "Older core", "Newer
// core", "Host key changed"; decisions.md 2026-11-01 ("Network permission is
// off", not "connection failed"); the overnight run's J1 verdict (the Machines
// screen must offer Unlock after a cancelled prompt). Render 7 for the wording
// of the connected and unreachable lines.
@RunWith(RobolectricTestRunner::class)
class MachineStatusTest {
    private val res: Resources = RuntimeEnvironment.getApplication().resources

    private fun card(
        state: MachineState,
        lastConnectedMs: Long? = null,
        nextRetryMs: Long? = null,
        nowMs: Long = NOW,
    ): MachineCardUi {
        val view = MachineView(
            "workstation", "workstation.example.org", 22u, "me", null, state,
            lastConnectedMs?.toULong(), nextRetryMs?.toULong(),
        )
        val ui = machinesUi(HubSnapshot(listOf(view), emptyList(), emptyList(), null), null, nowMs, res)
        return ui.machines.single()
    }

    @Test
    fun connectedShowsTheCoreVersionWithADot() {
        val c = card(MachineState.Connected("0.1.16", untested = false))
        assertEquals("Connected · herdr-core 0.1.16", c.statusText)
        assertEquals(StatusMark.Connected, c.mark)
        assertEquals(listOf(Tone.Muted, Tone.Muted), c.status.map { it.tone })
        assertNull(c.button)
        assertNull(c.link)
        assertNull(c.detail)
    }

    @Test
    fun anUntestedCoreAddsTheNoticeInTheWarningColour() {
        val c = card(MachineState.Connected("0.1.16", untested = true))
        assertEquals("Connected · herdr-core 0.1.16 · untested version", c.statusText)
        assertEquals(Segment("untested version", Tone.Warning), c.status.last())
    }

    @Test
    fun unreachableSaysWhenItWasLastConnectedAndThatItRetries() {
        val c = card(MachineState.Unreachable("connection refused"), lastConnectedMs = NOW - 2 * HOUR)
        assertEquals("Unreachable · last connected 2h ago · retrying", c.statusText)
        assertEquals(StatusMark.Ring, c.mark)
        assertEquals("connection refused", c.detail)
        assertNull(c.button)
    }

    // Render 7: "Unreachable · last connected 2h ago · retrying in 30 s".
    @Test
    fun unreachableCountsDownToTheNextAttempt() {
        val c = card(MachineState.Unreachable("connection refused"), NOW - 2 * HOUR, nextRetryMs = NOW + 30_000)
        assertEquals("Unreachable · last connected 2h ago · retrying in 30 s", c.statusText)
        assertEquals(Tone.Muted, c.status.last().tone)
    }

    @Test
    fun theCountdownRoundsUpAndEndsInRetrying() {
        fun retry(nextInMs: Long) =
            card(MachineState.Unreachable("x"), nextRetryMs = NOW + nextInMs).status.last().text
        assertEquals("retrying in 30 s", retry(29_001))
        assertEquals("retrying in 29 s", retry(29_000))
        assertEquals("retrying in 1 s", retry(1))
        // Due, or a clock that moved past it: the attempt is starting.
        assertEquals("retrying", retry(0))
        assertEquals("retrying", retry(-5_000))
    }

    @Test
    fun theCountdownTicksWithTheClock() {
        val next = NOW + 30_000
        fun at(nowMs: Long) = card(MachineState.Unreachable("x"), nextRetryMs = next, nowMs = nowMs).status.last().text
        assertEquals("retrying in 30 s", at(NOW))
        assertEquals("retrying in 20 s", at(NOW + 10_000))
        assertEquals("retrying in 1 s", at(NOW + 29_500))
        assertEquals("retrying", at(NOW + 30_000))
    }

    @Test
    fun everyRetryingStateCountsDown() {
        val next = NOW + 4_000
        assertEquals("Phone key not accepted · retrying in 4 s", card(MachineState.KeyRejected, nextRetryMs = next).statusText)
        assertEquals("herdr-core not found on this machine · retrying in 4 s", card(MachineState.NotInstalled, nextRetryMs = next).statusText)
    }

    @Test
    fun theScreenTicksEachSecondOnlyWhileACountdownShows() {
        val waiting = MachineView("server", "server.example.org", 22u, "me", null, MachineState.Unreachable("x"), null, (NOW + 9_000).toULong())
        val idle = waiting.copy(state = MachineState.Connected("0.1.16", untested = false), nextRetryMs = null)
        val counting = HubSnapshot(listOf(waiting), emptyList(), emptyList(), null)
        val quiet = HubSnapshot(listOf(idle), emptyList(), emptyList(), null)
        assertEquals(NOW / 1_000, machinesTick(counting, NOW + 999))
        assertEquals(NOW / 1_000 + 1, machinesTick(counting, NOW + 1_000))
        // Otherwise once a minute, for "last connected".
        assertEquals(machinesTick(quiet, NOW), machinesTick(quiet, NOW + 1_000))
        assertEquals(NOW / 60_000 + 1, machinesTick(quiet, NOW + 60_000))
    }

    // Without a time from the hub (none should come, but the field is optional).
    @Test
    fun unreachableNeverConnectedLeavesOutLastConnected() {
        assertEquals("Unreachable · retrying", card(MachineState.Unreachable("no route to host")).statusText)
    }

    @Test
    fun lastConnectedReadsInMinutesHoursAndDays() {
        fun ago(ms: Long) = card(MachineState.Unreachable("x"), lastConnectedMs = NOW - ms).status[1].text
        assertEquals("last connected just now", ago(20_000))
        assertEquals("last connected 5m ago", ago(5 * MINUTE + 10_000))
        assertEquals("last connected 59m ago", ago(59 * MINUTE))
        assertEquals("last connected 1h ago", ago(HOUR))
        assertEquals("last connected 23h ago", ago(23 * HOUR + 59 * MINUTE))
        assertEquals("last connected 3d ago", ago(3 * DAY + HOUR))
        // A clock that went backwards is not "in the future".
        assertEquals("last connected just now", ago(-5 * MINUTE))
    }

    @Test
    fun needsUserAuthOffersUnlock() {
        val c = card(MachineState.NeedsUserAuth)
        assertEquals("Phone key locked", c.statusText)
        assertEquals(CardButton.Unlock, c.button)
        assertEquals(StatusMark.Ring, c.mark)
    }

    @Test
    fun anUnconfirmedHostKeyLeadsToTheHostKeyCheck() {
        val c = card(MachineState.HostKeyUnconfirmed("SHA256:abc", "ED25519"))
        assertEquals("Host key not confirmed yet", c.statusText)
        assertEquals(CardLinkUi(CardLink.CheckHostKey, "Check the host key", "Connects once you trust its fingerprint"), c.link)
        assertNull(c.button)
    }

    @Test
    fun aChangedHostKeyDoesNotConnectAndLeadsToBothFingerprints() {
        val c = card(MachineState.HostKeyMismatch(confirmed = "SHA256:old", presented = "SHA256:new", algorithm = "ED25519"))
        assertEquals("Host key changed · not connecting", c.statusText)
        assertEquals(CardLink.CompareHostKeys, c.link?.kind)
        assertEquals("Compare the host keys", c.link?.label)
    }

    @Test
    fun aRejectedKeyRetriesAndLeadsToTheAuthorizedKeysLine() {
        val c = card(MachineState.KeyRejected)
        assertEquals("Phone key not accepted · retrying", c.statusText)
        assertEquals(CardLinkUi(CardLink.AddPhoneKey, "Add the phone key", "One line for this machine's authorized_keys"), c.link)
    }

    @Test
    fun notInstalledUsesTheSpecWording() {
        val c = card(MachineState.NotInstalled)
        assertEquals("herdr-core not found on this machine · retrying", c.statusText)
        assertNull(c.button)
    }

    @Test
    fun anOlderCoreNamesBothVersionsAndOffersTryAgain() {
        val c = card(MachineState.TooOld(have = "0.1.2", need = "0.1.10"))
        assertEquals("herdr-core 0.1.2 is older than this app supports", c.statusText)
        assertEquals("Update it to 0.1.10 or newer, then try again.", c.detail)
        assertEquals(CardButton.TryAgain, c.button)
    }

    @Test
    fun aNewerProtocolAsksForAnAppUpdate() {
        val c = card(MachineState.AppTooOld)
        assertEquals("This app is too old for this machine's herdr-core", c.statusText)
        assertEquals("Update the app to connect.", c.detail)
        assertNull(c.button)
    }

    @Test
    fun networkOffSaysSoInsteadOfConnectionFailed() {
        val c = card(MachineState.NetworkOff)
        assertEquals("Network permission is off", c.statusText)
        assertEquals("Allow Network for herdr in Android settings.", c.detail)
        assertEquals(CardButton.OpenSettings, c.button)
    }

    @Test
    fun pausedAndConnectingAreOneWord() {
        assertEquals("Paused", card(MachineState.Paused).statusText)
        assertEquals("Connecting", card(MachineState.Connecting).statusText)
        assertEquals(StatusMark.Ring, card(MachineState.Connecting).mark)
    }

    @Test
    fun machinesKeepTheHubsOrderAndNamesAndTheNoticeComesThrough() {
        val ui = machinesUi(
            snapshot("workstation" to MachineState.Connecting, "server" to MachineState.Paused)
                .copy(notice = "Could not save the machine list: disk full"),
            null,
            NOW,
            res,
        )
        assertEquals(listOf("workstation", "server"), ui.machines.map { it.name })
        assertEquals("Could not save the machine list: disk full", ui.notice)
    }

    @Test
    fun thePhoneKeyRowSaysWhatUnlocksItAndForHowLong() {
        val ui = machinesUi(snapshot(), null, NOW, res)
        assertEquals("Needs your fingerprint or screen lock, then valid 30 min", ui.phoneKeyDetail)
        assertEquals(emptyList<MachineCardUi>(), ui.machines)
    }

    @Test
    fun aHubThatCouldNotStartShowsWhyOnThePhoneKeyRow() {
        val ui = machinesUi(snapshot(), "The phone's key store could not be opened: locked", NOW, res)
        assertEquals("The phone's key store could not be opened: locked", ui.phoneKeyDetail)
    }

    @Test
    fun withNoMachinesTheExportRowIsDisabledAndSaysWhy() {
        val ui = machinesUi(snapshot(), null, NOW, res)
        assertFalse(ui.canExport)
        assertEquals("No machines to export yet", ui.exportDetail)
    }

    @Test
    fun withMachinesTheExportRowExportsWithoutKeys() {
        val ui = machinesUi(snapshot("workstation" to MachineState.Connecting), null, NOW, res)
        assertTrue(ui.canExport)
        assertEquals("Without keys", ui.exportDetail)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
