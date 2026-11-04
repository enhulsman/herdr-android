package dev.herdr.android.ui.agents

import androidx.compose.runtime.mutableLongStateOf
import com.github.takahirom.roborazzi.captureRoboImage
import dev.herdr.android.ui.theme.HerdrTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.herdr_mobile.AgentAttention
import uniffi.herdr_mobile.AgentsView
import uniffi.herdr_mobile.HubSnapshot
import uniffi.herdr_mobile.PlaceNote

// The "New" sheet against renders 9-12 (design/checkpoints/9-new-spaces.png,
// 10-new-folders.png, 11-new-error.png, 12-new-off.png), over the renders'
// list, and the states without a render: a start in flight, the notes for a
// space folder that is not a full path and a home not known yet, and paper.
// Reference phone 412 x 915 dp at density 2. Goldens: src/test/snapshots/<name>.png.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class NewTerminalScreenshotTest {
    private val res = RuntimeEnvironment.getApplication().resources

    /** The renders' list behind the sheet. */
    private val behind = listOf(
        row("t4", AgentAttention.WORKING, name = "pricing page", folder = "website", progress = 40, since = NOW - 30 * SECOND),
        row("t6", AgentAttention.WORKING, name = "changelog", folder = "docs", program = "codex", since = NOW - 9 * MINUTE),
        row("t7", AgentAttention.IDLE, name = "readme", folder = "docs", since = NOW - 12 * MINUTE),
    )

    private val spaces = startView(
        places = listOf(
            space("website", "/home/dev/src/website"),
            space("api", "/home/dev/src/api"),
            space("docs", "/home/dev/src/docs"),
        ),
    )

    /** Render 9: two machines, workstation's spaces, Claude Code and Codex found. */
    private val render9 = snapshot(listOf(connected("workstation", spaces), connected("server", startView())), behind)

    @Test fun spacesGraphite() = capture("new-spaces-graphite", dark = true, render9)

    @Test fun spacesPaper() = capture("new-spaces-paper", dark = false, render9)

    /** Render 10: server alone, no spaces, the check still running. */
    private val render10 = snapshot(
        listOf(
            unreachable("workstation", NOW - 5 * MINUTE),
            connected(
                "server",
                startView(
                    places = listOf(folder("~", "/home/dev"), folder("backups", "/home/dev/backups")),
                    agents = AgentsView.Checking,
                ),
            ),
        ),
        behind,
    )

    @Test fun foldersGraphite() = capture("new-folders-graphite", dark = true, render10)

    /** Render 11: "old site" refused, the check failed. */
    private val render11 = snapshot(
        listOf(
            connected(
                "workstation",
                startView(
                    places = listOf(space("website", "/home/dev/src/website"), space("old site", "/home/dev/src/old-site")),
                    agents = AgentsView.Unavailable,
                ),
            ),
        ),
        behind,
    )

    @Test fun errorGraphite() = capture(
        "new-error-graphite",
        dark = true,
        render11,
        sheet = NewSheetState(
            "workstation",
            place = "s-old site",
            error = "Could not start in /home/dev/src/old-site: No such file or directory",
        ),
    )

    @Test fun errorPaper() = capture(
        "new-error-paper",
        dark = false,
        render11,
        sheet = NewSheetState(
            "workstation",
            place = "s-old site",
            error = "Could not start in /home/dev/src/old-site: No such file or directory",
        ),
    )

    /** Render 12: nothing connected, "+" dimmed, the tap's snackbar. */
    private val render12 = snapshot(listOf(unreachable("workstation", NOW - 5 * MINUTE)), behind)

    @Test fun offGraphite() = capture("new-off-graphite", dark = true, render12, sheet = null, noMachine = true)

    @Test fun offPaper() = capture("new-off-paper", dark = false, render12, sheet = null, noMachine = true)

    /** No render: Claude Code tapped, the start in flight, the buttons off. */
    @Test fun startingGraphite() = capture(
        "new-starting-graphite",
        dark = true,
        render9,
        sheet = NewSheetState("workstation", place = "s-website", starting = true),
    )

    /** No render: a space folder that is not a full path, and one whose home is not known yet. */
    private val notes = snapshot(
        listOf(
            connected(
                "workstation",
                startView(
                    places = listOf(
                        space("notes", "/home/dev", note = PlaceNote.HOME_INSTEAD),
                        space("scratch", null, note = PlaceNote.HOME_UNKNOWN),
                    ),
                    agents = ready(agent("claude", "Claude Code"), agent("codex", "Codex"), agent("gemini", "Gemini CLI"), agent("opencode", "OpenCode")),
                ),
            ),
        ),
        behind,
    )

    @Test fun notesGraphite() = capture(
        "new-notes-graphite",
        dark = true,
        notes,
        sheet = NewSheetState("workstation", place = "s-scratch"),
    )

    private fun capture(
        name: String,
        dark: Boolean,
        snapshot: HubSnapshot,
        sheet: NewSheetState? = NewSheetState(snapshot.machines.first { it.start != null }.name),
        noMachine: Boolean = false,
    ) {
        val ui = agentsUi(snapshot, NOW, res, { "07:55" })
        val new = NewEntryUi(
            enabled = newEnabled(snapshot),
            sheet = sheet?.let { newSheetUi(snapshot, it, res) },
            noMachine = noMachine,
        )
        captureRoboImage("src/test/snapshots/$name.png") {
            HerdrTheme(dark = dark) {
                AgentsScreen(
                    ui = ui,
                    othersExpanded = false,
                    sheet = null,
                    clock = mutableLongStateOf(NOW),
                    spin = false,
                    actions = AgentsActions.Preview,
                    new = new,
                )
            }
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
    }
}
