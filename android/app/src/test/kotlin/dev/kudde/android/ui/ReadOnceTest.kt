package dev.kudde.android.ui

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentsActions
import dev.kudde.android.ui.agents.AgentsScreen
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.agents.connected
import dev.kudde.android.ui.agents.row
import dev.kudde.android.ui.agents.snapshot
import dev.kudde.android.ui.terminal.FakeTerminalView
import dev.kudde.android.ui.terminal.KEY_LINES
import dev.kudde.android.ui.terminal.RowKey
import dev.kudde.android.ui.terminal.SessionFixture
import dev.kudde.android.ui.terminal.TerminalActions
import dev.kudde.android.ui.terminal.TerminalGrid
import dev.kudde.android.ui.terminal.TerminalInputHandle
import dev.kudde.android.ui.terminal.TerminalScreen
import dev.kudde.android.ui.terminal.TerminalScreenState
import dev.kudde.android.ui.terminal.TerminalSession
import dev.kudde.android.ui.terminal.terminalUi
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.Armed
import uniffi.kudde_mobile.TermPhase
import uniffi.kudde_mobile.TermState

// accessibility spec: "Every key has a spoken name" (a key's label is not
// read after its name) and "A Needs you card is read once". Reads the merged
// semantics tree, which is what TalkBack is given: on the phone (2026-11-18)
// TalkBack read "Dash, dash" and "Permission ... Permission".
@RunWith(KuddeTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ReadOnceTest {
    private val res = RuntimeEnvironment.getApplication().resources

    @Test
    fun aKeyIsReadByItsNameOnly() {
        val key = AgentKey("workstation", "t1")
        val hub = snapshot(listOf(connected("workstation")), listOf(row("t1", AgentAttention.WORKING, name = "docs nav")))
        val session = TerminalSession(key) { it.run() }
        session.attach(FakeTerminalView().apply { armedAfterTap = Armed(ctrl = false, alt = false) })
        val frame = SessionFixture.frame(40, 20, wide = false, bottom = true)
        val grid = TerminalGrid().apply { apply(frame) }
        session.listener.onState(TermState(TermPhase.Typing, grid.cols.toUShort(), grid.rows.toUShort()))
        session.listener.onFrame(frame)
        val screen = TerminalScreenState().apply { typing = true; inputOpen = true }
        val actions = TerminalActions(onBack = {}, onOpen = {}, onSwipe = { false }, onPhoneGrid = {})

        val nodes = mergedNodes {
            TerminalScreen(terminalUi(hub, key, res), session, screen, actions, TerminalInputHandle.None)
        }

        for (tap in KEY_LINES.flatten().filterIsInstance<RowKey.Tap>()) {
            val name = res.getString(tap.description!!)
            val node = nodes.singleOrNull { it.descriptions == listOf(name) }
            assertNotNull("no node reads \"$name\"", node)
            assertEquals("\"$name\" also reads its label", emptyList<String>(), node!!.texts)
        }
    }

    @Test
    fun aNeedsYouCardReadsItsKindOnce() {
        val hub = snapshot(
            listOf(connected("workstation")),
            listOf(
                row("t1", AgentAttention.NEEDS_YOU, name = "docs nav", folder = "website", kind = "Permission",
                    label = "Permission: Bash: git push origin main", request = "Bash: git push origin main"),
            ),
        )
        val ui = agentsUi(hub, NOW, res, { "08:00" }, null)

        val nodes = mergedNodes {
            AgentsScreen(ui = ui, othersExpanded = false, sheet = null, clock = mutableLongStateOf(NOW), spin = false, actions = AgentsActions.Preview)
        }

        val card = nodes.singleOrNull { it.state == "Permission: Bash: git push origin main" }
        assertNotNull("no card states its request", card)
        val texts = card!!.texts
        assertTrue("the card lost its name: $texts", "docs nav" in texts)
        assertTrue("the kind is read twice: $texts", "Permission" !in texts)
        assertTrue("the request is read twice: $texts", "Bash: git push origin main" !in texts)
    }

    /** What TalkBack is given for one merged node. */
    private data class Spoken(val descriptions: List<String>, val texts: List<String>, val state: String?)

    private fun mergedNodes(content: @Composable () -> Unit): List<Spoken> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent { KuddeTheme(dark = true) { Box(Modifier.fillMaxSize()) { content() } } }
        shadowOf(Looper.getMainLooper()).idle()
        val composeView = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val root = composeView.getChildAt(0) as ViewRootForTest
        val all = mutableListOf<Spoken>()
        fun walk(node: SemanticsNode) {
            all += Spoken(
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty(),
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text },
                node.config.getOrNull(SemanticsProperties.StateDescription),
            )
            node.children.forEach(::walk)
        }
        walk(root.semanticsOwner.rootSemanticsNode)
        // Left open, the activity holds the app "started" for the tests that follow.
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        return all
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
