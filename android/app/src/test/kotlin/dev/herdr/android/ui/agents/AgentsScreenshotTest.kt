package dev.herdr.android.ui.agents

import androidx.compose.runtime.mutableLongStateOf
import com.github.takahirom.roborazzi.captureRoboImage
import dev.herdr.android.ui.theme.HerdrTheme
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.HubSnapshot

// The Agents home against renders 1 and 2 (design/checkpoints/1-home-dark.png,
// 2-home-light.png), and the views without a render, designed from the tokens:
// the whole list with "Other terminals" collapsed and expanded, a machine that
// dropped with its rows "as of hh:mm", the read-only agent sheet (M1 pick 1b)
// and both empty states. Reference phone 412 x 915 dp at density 2, without
// system bars; the spinner is drawn still (its reduced-motion quarter arc,
// which is also the renders' frame). Goldens: src/test/snapshots/<name>.png.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentsScreenshotTest {
    private val res = RuntimeEnvironment.getApplication().resources

    /** Render 1's content: workstation connected, server unreachable for 2 h. */
    private val render1 = snapshot(
        machines = listOf(connected("workstation"), unreachable("server", NOW - 2 * HOUR)),
        agents = listOf(
            row("t1", AgentAttention.NEEDS_YOU, name = "docs nav", folder = "website", kind = "Permission",
                request = "Bash: git push origin main", since = NOW - 20 * SECOND),
            row("t2", AgentAttention.NEEDS_YOU, name = "api tests", folder = "api", program = "codex", kind = "Question",
                request = "Which test runner should I use?", since = NOW - 3 * MINUTE),
            row("t3", AgentAttention.DONE, name = "og tags", folder = "website", program = "codex", since = NOW - 4 * MINUTE),
            row("t4", AgentAttention.WORKING, name = "pricing page", folder = "website", progress = 40, since = NOW - 30 * SECOND),
            row("t5", AgentAttention.WORKING, name = "rate limits", folder = "api", since = NOW - 6 * MINUTE),
            row("t6", AgentAttention.WORKING, name = "changelog", folder = "docs", program = "codex", since = NOW - 9 * MINUTE),
            row("t7", AgentAttention.IDLE, name = "readme", folder = "docs", since = NOW - 12 * MINUTE),
            row("t9", AgentAttention.IDLE, machine = "server", name = "backup notes", folder = "infra", since = NOW - 2 * HOUR - 5 * MINUTE),
        ),
        others = listOf(
            other("t10", name = "make", folder = "api", program = "make"),
            other("t11", name = "zsh", folder = "docs", program = "zsh"),
            other("t12", name = "npm", folder = "website", program = "npm"),
        ),
    )

    @Test fun render1Graphite() = capture("agents-graphite", dark = true, render1)

    @Test fun render1Paper() = capture("agents-paper", dark = false, render1)

    /** The whole of render 1's list: the server's row "as of", "Other terminals" collapsed. */
    @Test fun fullGraphite() = capture("agents-full-graphite", dark = true, render1, tall = true)

    @Test fun fullPaper() = capture("agents-full-paper", dark = false, render1, tall = true)

    @Test fun othersExpandedGraphite() = capture("agents-others-graphite", dark = true, render1, tall = true, othersExpanded = true)

    @Test fun othersExpandedPaper() = capture("agents-others-paper", dark = false, render1, tall = true, othersExpanded = true)

    /** The server dropped while one of its agents was waiting: its card keeps the request, "as of" when it last knew. */
    private val dropped = snapshot(
        machines = listOf(connected("workstation"), unreachable("server", NOW - 25 * MINUTE)),
        agents = listOf(
            row("t1", AgentAttention.NEEDS_YOU, machine = "server", name = "backup notes", folder = "infra", kind = "Permission",
                request = "Bash: rsync -a docs/ backup/", since = NOW - 40 * MINUTE),
            row("t4", AgentAttention.WORKING, name = "pricing page", folder = "website", progress = 70, since = NOW - 2 * MINUTE),
            row("t9", AgentAttention.IDLE, machine = "server", name = "nightly build", folder = "api", program = "codex", since = NOW - 3 * HOUR),
        ),
    )

    @Test fun unreachableGraphite() = capture("agents-unreachable-graphite", dark = true, dropped)

    @Test fun unreachablePaper() = capture("agents-unreachable-paper", dark = false, dropped)

    /** The read-only sheet over render 1, for the agent asking permission. */
    @Test fun sheetGraphite() = capture("agents-sheet-graphite", dark = true, render1, sheet = AgentKey("workstation", "t1"))

    @Test fun sheetPaper() = capture("agents-sheet-paper", dark = false, render1, sheet = AgentKey("workstation", "t1"))

    /** The sheet for the server's idle row, which only knows how things were at 06:00. */
    @Test fun sheetAsOfGraphite() = capture("agents-sheet-as-of-graphite", dark = true, render1, sheet = AgentKey("server", "t9"))

    @Test fun emptyMachinesGraphite() = capture("agents-empty-machines-graphite", dark = true, snapshot(emptyList(), emptyList()))

    @Test fun emptyMachinesPaper() = capture("agents-empty-machines-paper", dark = false, snapshot(emptyList(), emptyList()))

    /** The hub could not start (here: the key store would not open), so nothing can connect. */
    @Test fun startFailedGraphite() = capture("agents-start-failed-graphite", dark = true, snapshot(emptyList(), emptyList()), startError = START_ERROR)

    @Test fun startFailedPaper() = capture("agents-start-failed-paper", dark = false, snapshot(emptyList(), emptyList()), startError = START_ERROR)

    /** Machines but no agents yet; a plain terminal waits under "Other terminals". */
    private val noAgents = snapshot(
        machines = listOf(connected("workstation"), connected("server")),
        agents = emptyList(),
        others = listOf(other("t3", name = "zsh", folder = "docs", program = "zsh")),
    )

    @Test fun emptyAgentsGraphite() = capture("agents-empty-agents-graphite", dark = true, noAgents)

    @Test fun emptyAgentsPaper() = capture("agents-empty-agents-paper", dark = false, noAgents)

    private fun capture(
        name: String,
        dark: Boolean,
        snapshot: HubSnapshot,
        tall: Boolean = false,
        othersExpanded: Boolean = false,
        sheet: AgentKey? = null,
        startError: String? = null,
    ) {
        val ui = agentsUi(snapshot, NOW, res, ::utc, startError)
        val sheetUi = sheet?.let { agentSheet(ui, it, res) }
        if (tall) RuntimeEnvironment.setQualifiers("w412dp-h1400dp-xhdpi")
        captureRoboImage("src/test/snapshots/$name.png") {
            HerdrTheme(dark = dark) {
                AgentsScreen(
                    ui = ui,
                    othersExpanded = othersExpanded,
                    sheet = sheetUi,
                    clock = mutableLongStateOf(NOW),
                    spin = false,
                    actions = AgentsActions.Preview,
                )
            }
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L // 08:00 UTC
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
        const val START_ERROR = "The phone's key store could not be opened: keystore unavailable"

        fun utc(ms: Long): String =
            DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))
    }
}
