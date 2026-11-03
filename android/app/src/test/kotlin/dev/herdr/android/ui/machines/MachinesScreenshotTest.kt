package dev.herdr.android.ui.machines

import com.github.takahirom.roborazzi.captureRoboImage
import dev.herdr.android.push.AlertSetup
import dev.herdr.android.push.MachineAlerts
import dev.herdr.android.ui.theme.Contrast
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.transfer.TransferResult
import dev.herdr.android.ui.transfer.transferNotice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.herdr_mobile.AlertsRegistration
import uniffi.herdr_mobile.AlertsView
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView
import uniffi.herdr_mobile.PushStatusView

// The Machines screen against render 7 (design/checkpoints/7-machines.png),
// without alert rows (machines-*) and with them (machines-alerts-*), and the
// states render 7 does not show, designed from the tokens. Reference phone: 412 x 915 dp at density 2,
// the renders' size (the system bars are not drawn here).
// Goldens: src/test/snapshots/machines-*.png (recordRoborazziDebug, verifyRoborazziDebug).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class MachinesScreenshotTest {
    private val res = RuntimeEnvironment.getApplication().resources

    /** Render 7: workstation connected, server unreachable for 2 h, next try in 30 s. */
    private val render7 = listOf(
        machine("workstation", MachineState.Connected("0.1.16")),
        machine(
            "server",
            MachineState.Unreachable("connection refused"),
            lastConnectedMs = NOW - 2 * HOUR,
            nextRetryMs = NOW + 30_000,
        ),
    )

    @Test fun render7Graphite() = capture("machines-graphite", dark = true, render7)

    @Test fun render7Paper() = capture("machines-paper", dark = false, render7)

    /** Render 7 with its alert rows: accepted by ntfy 14:02, test received 13:58; server's last push 11:41. */
    private val render7Alerts = listOf(
        machine(
            "workstation",
            MachineState.Connected("0.1.16"),
            alerts = AlertsView(AlertsRegistration.REGISTERED, PushStatusView("0.1.0", at(14, 2).toULong(), 200u, "accepted 200", emptyList())),
        ),
        machine(
            "server",
            MachineState.Unreachable("connection refused"),
            lastConnectedMs = NOW - 2 * HOUR,
            nextRetryMs = NOW + 30_000,
            alerts = AlertsView(AlertsRegistration.REGISTERED, null),
        ),
    )
    private val render7Phone = PhoneAlerts(
        registered = true,
        setup = AlertSetup.Off,
        permitted = true,
        machines = mapOf(
            "workstation" to MachineAlerts(lastPushMs = at(14, 2), testAskedMs = at(13, 57), testReceivedMs = at(13, 58)),
            "server" to MachineAlerts(lastPushMs = at(11, 41)),
        ),
        timeText = ::clock,
    )

    @Test fun render7AlertsGraphite() = capture("machines-alerts-graphite", dark = true, render7Alerts, alerts = render7Phone, tall = true)

    @Test fun render7AlertsPaper() = capture("machines-alerts-paper", dark = false, render7Alerts, alerts = render7Phone, tall = true)

    /** No signed-off render: alerts not yet on, and on but not allowed to notify (alerts spec, "Permission denied"). */
    private val alertsOff = listOf(
        machine("workstation", MachineState.Connected("0.1.16")),
        machine("server", MachineState.Connected("0.1.16"), alerts = AlertsView(AlertsRegistration.REGISTERED, null)),
    )

    @Test fun alertsOffGraphite() = capture(
        "machines-alerts-off-graphite", dark = true, alertsOff.take(1),
        alerts = PhoneAlerts(false, AlertSetup.Off, true, emptyMap(), ::clock),
    )

    @Test fun alertsNotAllowedPaper() = capture(
        "machines-alerts-not-allowed-paper", dark = false, alertsOff.drop(1),
        alerts = PhoneAlerts(true, AlertSetup.Off, false, mapOf("server" to MachineAlerts(muted = true)), ::clock),
    )

    private val unlock = listOf(
        machine("workstation", MachineState.NeedsUserAuth),
        machine("server", MachineState.NeedsUserAuth),
    )

    @Test fun needsUnlockGraphite() = capture("machines-unlock-graphite", dark = true, unlock)

    @Test fun needsUnlockPaper() = capture("machines-unlock-paper", dark = false, unlock)

    private val networkOff = listOf(
        machine("workstation", MachineState.NetworkOff, lastConnectedMs = NOW - 5 * MINUTE),
        machine("server", MachineState.NetworkOff),
    )

    @Test fun networkOffGraphite() = capture("machines-network-off-graphite", dark = true, networkOff)

    @Test fun networkOffPaper() = capture("machines-network-off-paper", dark = false, networkOff)

    private val notInstalled = listOf(
        machine("workstation", MachineState.Connected("0.1.16")),
        machine("server", MachineState.NotInstalled, nextRetryMs = NOW + 8_000),
    )

    @Test fun notInstalledGraphite() = capture("machines-not-installed-graphite", dark = true, notInstalled)

    @Test fun notInstalledPaper() = capture("machines-not-installed-paper", dark = false, notInstalled)

    /** No signed-off render: the first launch, before any machine is added. */
    @Test fun emptyGraphite() = capture("machines-empty-graphite", dark = true, emptyList())

    @Test fun emptyPaper() = capture("machines-empty-paper", dark = false, emptyList())

    /** No signed-off render: the states that wait for the person, for review of their text. */
    private val waiting = listOf(
        machine("workstation", MachineState.HostKeyUnconfirmed("SHA256:q3Vb8m2N4sR7tK0yL9wE1aZ5cX6vB3nM8jH2gF4dS0p", "ED25519")),
        machine("server", MachineState.HostKeyMismatch("SHA256:old", "SHA256:new", "ED25519")),
        machine("website", MachineState.KeyRejected, nextRetryMs = NOW + 30_000),
        machine("api", MachineState.TooOld(have = "0.1.2", need = "0.1.10")),
        machine("docs", MachineState.AppTooOld),
    )

    @Test fun waitingGraphite() = capture("machines-waiting-graphite", dark = true, waiting, tall = true)

    @Test fun waitingPaper() = capture("machines-waiting-paper", dark = false, waiting, tall = true)

    /** The inline notices after the file picker (no signed-off render), under the "This phone" card. */
    @Test fun exportedGraphite() = capture("machines-exported-graphite", dark = true, render7, TransferResult.Exported)

    @Test fun exportedPaper() = capture("machines-exported-paper", dark = false, render7, TransferResult.Exported)

    /** Just imported on a fresh install: the machines wait for their host keys. */
    private val imported = listOf(
        machine("workstation", MachineState.HostKeyUnconfirmed("SHA256:q3Vb8m2N4sR7tK0yL9wE1aZ5cX6vB3nM8jH2gF4dS0p", "ED25519")),
        machine("server", MachineState.Connecting),
    )

    @Test fun importedGraphite() = capture("machines-imported-graphite", dark = true, imported, TransferResult.Imported(2))

    @Test fun importedPaper() = capture("machines-imported-paper", dark = false, imported, TransferResult.Imported(2))

    /** The first launch, after picking a file that is not a machine list. */
    @Test fun importErrorGraphite() = capture("machines-import-error-graphite", dark = true, emptyList(), TransferResult.NotAList)

    @Test fun importErrorPaper() = capture("machines-import-error-paper", dark = false, emptyList(), TransferResult.NotAList)

    /** Remove machine's confirm (no signed-off render; drawn like render 8's dialog) over render 7. */
    @Test fun removeGraphite() = capture("machines-remove-graphite", dark = true, render7, removing = "server")

    @Test fun removePaper() = capture("machines-remove-paper", dark = false, render7, removing = "server")

    /** A remove the hub refused: its reason in the notice style. */
    @Test fun removeErrorGraphite() =
        capture("machines-remove-error-graphite", dark = true, render7, error = "Could not save the machine list: disk full")

    @Test fun removeErrorPaper() =
        capture("machines-remove-error-paper", dark = false, render7, error = "Could not save the machine list: disk full")

    /** Text contrast set to Higher: the row says so and the whole screen takes the AA values. */
    @Test fun contrastHigherGraphite() = capture("machines-contrast-higher-graphite", dark = true, render7, contrast = Contrast.Aa)

    @Test fun contrastHigherPaper() = capture("machines-contrast-higher-paper", dark = false, render7, contrast = Contrast.Aa)

    private fun capture(
        name: String,
        dark: Boolean,
        machines: List<MachineView>,
        transfer: TransferResult? = null,
        tall: Boolean = false,
        removing: String? = null,
        error: String? = null,
        contrast: Contrast = Contrast.Default,
        alerts: PhoneAlerts? = null,
    ) {
        val ui = machinesUi(HubSnapshot(machines, emptyList(), emptyList(), null), null, NOW, res, contrast, alerts)
            .copy(transfer = transfer?.let { transferNotice(it, res) }, removing = removing, error = error)
        if (tall) RuntimeEnvironment.setQualifiers("w412dp-h1400dp-xhdpi")
        captureRoboImage("src/test/snapshots/$name.png") {
            HerdrTheme(dark = dark, contrast = contrast) { MachinesScreen(ui, MachinesActions.Preview.copy(onBack = {})) }
        }
    }

    private fun machine(
        name: String,
        state: MachineState,
        lastConnectedMs: Long? = null,
        nextRetryMs: Long? = null,
        alerts: AlertsView? = null,
    ) = MachineView(name, "$name.example.org", 22u, "me", null, state, lastConnectedMs?.toULong(), nextRetryMs?.toULong(), alerts)

    /** A time of day on NOW's date, in UTC, as [clock] shows it. */
    private fun at(hour: Int, minute: Int): Long = NOW - NOW % DAY + hour * HOUR + minute * MINUTE

    private fun clock(ms: Long): String = "%02d:%02d".format((ms % DAY) / HOUR, (ms % HOUR) / MINUTE)

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
