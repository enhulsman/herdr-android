package dev.herdr.android.ui.machines

import com.github.takahirom.roborazzi.captureRoboImage
import dev.herdr.android.ui.theme.HerdrTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.MachineState
import uniffi.herdr_mobile.MachineView

// The Machines screen against render 7 (design/checkpoints/7-machines.png),
// minus the alert rows that arrive in M3, and the states render 7 does not
// show, designed from the tokens. Reference phone: 412 x 915 dp at density 2,
// the renders' size (the system bars are not drawn here).
// Goldens: src/test/snapshots/machines-*.png (recordRoborazziDebug, verifyRoborazziDebug).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class MachinesScreenshotTest {
    private val res = RuntimeEnvironment.getApplication().resources

    /** Render 7: workstation connected on an untested core, server unreachable for 2 h. */
    private val render7 = listOf(
        machine("workstation", MachineState.Connected("0.1.16", untested = true)),
        machine("server", MachineState.Unreachable("connection refused"), lastConnectedMs = NOW - 2 * HOUR),
    )

    @Test fun render7Graphite() = capture("machines-graphite", dark = true, render7)

    @Test fun render7Paper() = capture("machines-paper", dark = false, render7)

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
        machine("workstation", MachineState.Connected("0.1.16", untested = false)),
        machine("server", MachineState.NotInstalled),
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
        machine("website", MachineState.KeyRejected),
        machine("api", MachineState.TooOld(have = "0.1.2", need = "0.1.10")),
        machine("docs", MachineState.AppTooOld),
    )

    @Test fun waitingGraphite() = capture("machines-waiting-graphite", dark = true, waiting, tall = true)

    @Test fun waitingPaper() = capture("machines-waiting-paper", dark = false, waiting, tall = true)

    private fun capture(name: String, dark: Boolean, machines: List<MachineView>, tall: Boolean = false) {
        val ui = machinesUi(HubSnapshot(machines, emptyList(), emptyList(), null), null, NOW, res)
        if (tall) RuntimeEnvironment.setQualifiers("w412dp-h1400dp-xhdpi")
        captureRoboImage("src/test/snapshots/$name.png") {
            HerdrTheme(dark = dark) { MachinesScreen(ui, MachinesActions.Preview.copy(onBack = {})) }
        }
    }

    private fun machine(name: String, state: MachineState, lastConnectedMs: Long? = null) =
        MachineView(name, "$name.example.org", 22u, "me", null, state, lastConnectedMs?.toULong())

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
