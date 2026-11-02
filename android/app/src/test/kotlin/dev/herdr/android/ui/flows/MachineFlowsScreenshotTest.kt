package dev.herdr.android.ui.flows

import androidx.compose.foundation.layout.Box
import com.github.takahirom.roborazzi.captureRoboImage
import dev.herdr.android.ui.addmachine.AddMachineActions
import dev.herdr.android.ui.addmachine.AddMachineScreen
import dev.herdr.android.ui.addmachine.MachineInput
import dev.herdr.android.ui.addmachine.validateMachine
import dev.herdr.android.ui.hostkey.HostKeyChange
import dev.herdr.android.ui.hostkey.HostKeyChangedActions
import dev.herdr.android.ui.hostkey.HostKeyChangedScreen
import dev.herdr.android.ui.hostkey.HostKeyPrompt
import dev.herdr.android.ui.hostkey.TrustDialog
import dev.herdr.android.ui.phonekey.ExampleKey
import dev.herdr.android.ui.phonekey.PhoneKeyLine
import dev.herdr.android.ui.phonekey.PhoneKeyScreen
import dev.herdr.android.ui.theme.HerdrTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The Machines flows: the first-connection confirm against render 8
// (design/checkpoints/8-host-key.png: the dialog over the Add machine form),
// and the views without a render, designed from the tokens: the whole form
// (empty and with its checks), the changed-key screen and its forget confirm,
// and the phone key line. Reference phone 412 x 915 dp at density 2, without
// system bars. Goldens: src/test/snapshots/<name>.png.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class MachineFlowsScreenshotTest {
    private val noForm = AddMachineActions({}, {}, {})
    private val noChange = HostKeyChangedActions({}, {}, {}, {})

    @Test fun addMachineEmptyGraphite() = addMachineEmpty("add-machine-empty-graphite", dark = true)

    @Test fun addMachineEmptyPaper() = addMachineEmpty("add-machine-empty-paper", dark = false)

    private fun addMachineEmpty(name: String, dark: Boolean) = capture(name, dark) {
        AddMachineScreen(MachineInput(), errors = null, waitingFor = null, error = null, actions = noForm)
    }

    /** After Save: a name already listed, no host, a port out of range, a user with @. */
    private val badInput = MachineInput(name = "server", host = "", port = "70000", user = "dev@server")

    @Test fun addMachineErrorsGraphite() = addMachineErrors("add-machine-errors-graphite", dark = true)

    @Test fun addMachineErrorsPaper() = addMachineErrors("add-machine-errors-paper", dark = false)

    private fun addMachineErrors(name: String, dark: Boolean) = capture(name, dark) {
        AddMachineScreen(
            badInput,
            errors = validateMachine(badInput, listOf("server")),
            waitingFor = null,
            error = null,
            actions = noForm,
        )
    }

    /** Render 8: workstation just added, its first host key waiting for the person. */
    private val prompt = HostKeyPrompt("workstation", "workstation:22", "ED25519", PRESENTED)

    @Test fun hostKeyConfirmGraphite() = hostKeyConfirm("host-key-confirm-graphite", dark = true)

    @Test fun hostKeyConfirmPaper() = hostKeyConfirm("host-key-confirm-paper", dark = false)

    private fun hostKeyConfirm(name: String, dark: Boolean) = capture(name, dark) {
        Box {
            AddMachineScreen(
                MachineInput(name = "workstation", host = "workstation", user = "dev"),
                errors = null,
                waitingFor = "workstation:22",
                error = null,
                actions = noForm,
            )
            TrustDialog(prompt, error = null, onTrust = {}, onCancel = {})
        }
    }

    private val change = HostKeyChange("server", "server.example.org:22", TRUSTED, PRESENTED, "ED25519")

    @Test fun hostKeyChangedGraphite() = hostKeyChanged("host-key-changed-graphite", dark = true, confirming = false)

    @Test fun hostKeyChangedPaper() = hostKeyChanged("host-key-changed-paper", dark = false, confirming = false)

    @Test fun hostKeyForgetGraphite() = hostKeyChanged("host-key-forget-graphite", dark = true, confirming = true)

    @Test fun hostKeyForgetPaper() = hostKeyChanged("host-key-forget-paper", dark = false, confirming = true)

    private fun hostKeyChanged(name: String, dark: Boolean, confirming: Boolean) = capture(name, dark) {
        HostKeyChangedScreen(change, confirmingForget = confirming, error = null, actions = noChange)
    }

    @Test fun phoneKeyGraphite() = phoneKey("phone-key-graphite", dark = true)

    @Test fun phoneKeyPaper() = phoneKey("phone-key-paper", dark = false)

    private fun phoneKey(name: String, dark: Boolean) = capture(name, dark) {
        PhoneKeyScreen("website", PhoneKeyLine(ExampleKey.LINE, null), copied = false, onCopy = {}, onBack = {})
    }

    private fun capture(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        captureRoboImage("src/test/snapshots/$name.png") {
            HerdrTheme(dark = dark) { content() }
        }
    }

    private companion object {
        // Invented fingerprints; PRESENTED is render 8's.
        const val TRUSTED = "SHA256:Zt7oP1qW3eR5tY7uI9oP2aS4dF6gH8jK0lZ2xC4vB6n"
        const val PRESENTED = "SHA256:q3Vb8m2N4sR7tK0yL9wE1aZ5cX6vB3nM8jH2gF4dS0p"
    }
}
