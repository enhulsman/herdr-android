package dev.kudde.android.ui.spaces

import dev.kudde.android.ui.agents.*
import org.junit.Assert.*
import org.junit.Test
import uniffi.kudde_mobile.*

class SpacesModelTest {
    private fun cell(address: String) = DeskCellView(DeskPaneView.Terminal(address), 0f, 0f, 1f, 1f)
    private fun tab(id: String, vararg addresses: String) = DeskTabView(id, id, addresses.map(::cell))
    private fun space(id: String, vararg tabs: DeskTabView, unreadable: UInt = 0u) = DeskSpaceView(id, id, unreadable, tabs.toList())
    private fun layout(vararg spaces: DeskSpaceView, loose: List<String> = emptyList(), note: DeskNoteView? = null) = DeskView(spaces.toList(), loose, note)
    private fun sample() = snapshot(listOf(connected("workstation").copy(desk = layout(
        space("website", tab("main", "a", "b"), tab("2", "shell")), space("api", tab("api-main", "c"))))),
        listOf(row("c", AgentAttention.NEEDS_YOU), row("a", AgentAttention.WORKING), row("b", AgentAttention.DONE)), listOf(other("shell")))
    // Texts without a complete template in spec/design are supplied by the caller.
    private val texts = SpacesTexts(
        unreadableTabs = { if (it == 1u) "1 tab could not be read" else "$it tabs could not be read" },
        spaceSpeech = { name, counts -> buildList {
            add(name)
            if (counts.needsYou > 0) add("${counts.needsYou} needs you")
            if (counts.done > 0) add("${counts.done} done")
            if (counts.working > 0) add("${counts.working} working")
            if (counts.idle > 0) add("${counts.idle} idle")
        }.joinToString(", ") },
        tabSpeech = { name, count, needs -> "Tab $name, $count terminals" + if (needs > 0) ", $needs needs you" else "" },
    )
    private fun rows(s: HubSnapshot, asOf: String? = null): AgentsUi {
        val agents = s.agents.map {
            AgentRowUi(AgentKey(it.machine, it.address), it.name, ProgramIcon.Terminal, it.attention, it.folder,
                it.place ?: it.folder, it.sinceMs?.toLong(), null, asOf, it.requestKind, it.request, it.label)
        }
        return AgentsUi(listOf(NoticeUi("server", "unreachable")),
            agents.filter { it.attention == AgentAttention.NEEDS_YOU || it.attention == AgentAttention.ERROR },
            agents.filter { it.attention == AgentAttention.DONE }, agents.filter { it.attention == AgentAttention.WORKING },
            agents.filter { it.attention == AgentAttention.IDLE },
            s.others.map { OtherRowUi(AgentKey(it.machine, it.address), it.name, ProgramIcon.Terminal, it.folder, it.folder, asOf) },
            false, null, s.notice)
    }
    private fun ui(s: HubSnapshot = sample(), rows: AgentsUi = rows(s)) = spacesUi(s, rows, texts)
    private fun SpacesUi.headers() = items.filterIsInstance<SpacesItem.SpaceHeader>()
    private fun SpacesUi.agents() = items.filterIsInstance<SpacesItem.Agent>()
    private fun SpacesUi.tabs() = items.filterIsInstance<SpacesItem.TabLine>()

    @Test
    fun twoSpacesOneMachine() {
        val model = ui()
        assertEquals(listOf("website", "api"), model.headers().map { it.name })
        assertEquals(listOf("main", "2", "api-main"), model.tabs().map { it.tab.name })
        assertEquals(listOf("a", "b", "c"), model.agents().map { it.row.key.address })
        assertEquals(listOf("space", "tab", "agent", "agent", "tab", "other", "space", "tab", "agent"), model.items.map {
            when (it) { is SpacesItem.SpaceHeader -> "space"; is SpacesItem.TabLine -> "tab"; is SpacesItem.Agent -> "agent"; is SpacesItem.Other -> "other"; else -> "unexpected" }
        })
        assertFalse(model.items.any { it is SpacesItem.MachineHeader })
    }

    @Test
    fun machineHeadersOnlyWithTwoMachines() {
        val s = sample().let { it.copy(machines = it.machines + connected("server").copy(desk = layout(space("docs", tab("docs-main"))))) }
        assertEquals(listOf("workstation", "server"), ui(s).items.filterIsInstance<SpacesItem.MachineHeader>().map { it.name })
    }

    @Test
    fun stateListUnchangedAndCardKeepsItsDeskPlace() {
        val s = sample(); val existing = rows(s); val before = existing.copy()
        val model = ui(s, existing)
        assertEquals(before, existing)
        assertSame(existing.needsYou.single(), model.agents().last().row)
        assertEquals(listOf("c", "a", "b"), s.agents.map { it.address })
    }

    @Test
    fun spaceStateUsesUrgency() {
        assertEquals(AgentAttention.DONE, ui().headers()[0].attention)
        assertEquals(AgentAttention.NEEDS_YOU, ui().headers()[1].attention)
        val s = sample().let { it.copy(agents = it.agents.map { r -> r.copy(attention = AgentAttention.ERROR) }) }
        assertEquals(AgentAttention.NEEDS_YOU, ui(s).headers()[0].attention)
    }

    @Test
    fun shellOnlySpaceHasNoState() {
        val s = sample().copy(agents = emptyList())
        assertTrue(ui(s).headers().all { it.attention == null })
    }

    @Test
    fun notInSpaceFollowsSpacesInCoreOrder() {
        val s = sample().let { it.copy(machines = it.machines.map { m -> m.copy(desk = m.desk!!.copy(loose = listOf("loose-shell", "loose-agent"))) },
            agents = it.agents + row("loose-agent", AgentAttention.WORKING), others = it.others + other("loose-shell")) }
        val tail = ui(s).items.takeLast(3)
        assertEquals("Not in a space", (tail[0] as SpacesItem.LooseHeader).text)
        assertEquals("loose-shell", (tail[1] as SpacesItem.Other).row.key.address)
        assertEquals("loose-agent", (tail[2] as SpacesItem.Agent).row.key.address)
    }

    @Test
    fun subAgentHasNoRowAndParentIsUnchanged() {
        // seat excludes children from terminal cells and loose, not from all snapshot rows.
        val s = sample().let { it.copy(agents = it.agents + row("child", AgentAttention.NEEDS_YOU)) }
        val existing = rows(s)
        assertFalse(ui(s, existing).agents().any { it.row.key.address == "child" })
        assertSame(existing.working.single(), ui(s, existing).agents().first().row)
    }

    @Test
    fun subAgentShellStaysVacantAndOtherTerminalsStayUnchanged() {
        val vacant = DeskCellView(DeskPaneView.Vacant, .5f, 0f, .5f, 1f)
        val placeholder = DeskCellView(DeskPaneView.Placeholder("Desk only"), 0f, .5f, .5f, .5f)
        val s = snapshot(listOf(connected("workstation").copy(desk = layout(space("website", tab("main", "a").copy(cells = listOf(cell("a"), vacant, placeholder)))))),
            listOf(row("a", AgentAttention.IDLE)), listOf(other("child")))
        val existing = rows(s); val model = ui(s, existing)
        assertEquals(listOf(cell("a"), vacant, placeholder), model.tabs().single().tab.cells)
        assertFalse(model.items.any { it is SpacesItem.Other })
        assertEquals("child", existing.others.single().key.address)
    }

    @Test
    fun unreadableSingular() { assertUnreadable(1u, "1 tab could not be read") }
    @Test
    fun unreadablePlural() { assertUnreadable(3u, "3 tabs could not be read") }
    private fun assertUnreadable(count: UInt, text: String) {
        val s = snapshot(listOf(connected("workstation").copy(desk = layout(space("website", tab("main"), unreadable = count)))), emptyList())
        assertEquals(text, ui(s).headers().single().unreadableText)
        assertEquals("main", ui(s).tabs().single().tab.name)
    }

    @Test
    fun unreachableKeepsLayoutAndAsOfRows() {
        val s = sample().let { it.copy(machines = it.machines.map { m -> m.copy(state = MachineState.Unreachable("offline"), lastConnectedMs = 100u) }) }
        val existing = rows(s, "as of 14:05"); val model = ui(s, existing)
        assertEquals(listOf("website", "api"), model.headers().map { it.name })
        assertTrue(model.agents().all { it.row.asOf == "as of 14:05" })
        assertEquals("as of 14:05", model.items.filterIsInstance<SpacesItem.Other>().single().row.asOf)
        assertSame(existing.notices, model.notices)
    }

    @Test
    fun notSeenThisRunHasNoSpacesAndKeepsNotice() {
        val s = snapshot(listOf(unreachable("server", 100)), emptyList())
        assertTrue(ui(s).headers().isEmpty())
        assertEquals(rows(s).notices, ui(s).notices)
    }

    @Test
    fun removedMachineLeavesNoItems() {
        assertTrue(ui(sample().copy(machines = emptyList())).items.isEmpty())
    }

    @Test
    fun nothingToList() {
        val s = snapshot(listOf(connected("workstation")), emptyList())
        assertEquals("No spaces yet", ui(s).emptyText)
        assertTrue(ui(s).items.isEmpty())
    }

    @Test
    fun spokenSpaceCountsEachStateInUrgencyOrder() {
        val s = sample().let { it.copy(machines = it.machines.map { m -> m.copy(desk = layout(space("website", tab("main", "a", "b", "c")))) },
            agents = listOf(row("a", AgentAttention.NEEDS_YOU), row("b", AgentAttention.WORKING), row("c", AgentAttention.WORKING))) }
        assertEquals("website, 1 needs you, 2 working", ui(s).headers().single().spokenText)
        val all = s.copy(agents = s.agents + row("d", AgentAttention.DONE) + row("e", AgentAttention.IDLE),
            machines = s.machines.map { it.copy(desk = layout(space("website", tab("main", "a", "b", "c", "d", "e")))) })
        assertEquals("website, 1 needs you, 1 done, 2 working, 1 idle", ui(all).headers().single().spokenText)
    }

    @Test
    fun spokenTabMapCountsTerminalsAndNeedsYou() {
        val s = sample().let { it.copy(machines = it.machines.map { m -> m.copy(desk = layout(space("website", tab("main", "a", "b", "c", "shell")))) }) }
        assertEquals("Tab main, 4 terminals, 1 needs you", ui(s).tabs().single().spokenText)
        val three = s.copy(others = emptyList())
        assertEquals("Tab main, 3 terminals, 1 needs you", ui(three).tabs().single().spokenText)
        assertEquals("Tab main, 2 terminals", ui().tabs().first().spokenText)
    }

    @Test
    fun stableKeysSurviveMoveRenameAndInsertionWithoutCollisions() {
        val s = sample(); val before = ui(s)
        val changed = s.copy(machines = s.machines.map { m -> m.copy(desk = layout(space("new", tab("new")), space("renamed", tab("moved", "a")),
            space("website", tab("main", "b"), tab("2", "shell")), space("api", tab("api-main", "c")))) })
        val after = ui(changed)
        assertEquals(before.agents().first().key, after.agents().first().key)
        assertEquals(before.headers().first().key, after.headers().single { it.name == "website" }.key)
        assertEquals(before.tabs().first().key, after.tabs().single { it.tab.id == "main" }.key)
        assertEquals(after.items.size, after.items.map { it.key }.toSet().size)
        val two = ui(s.copy(machines = s.machines + s.machines.single().copy(name = "server"),
            agents = s.agents + s.agents.map { it.copy(machine = "server") }, others = s.others + s.others.map { it.copy(machine = "server") }))
        assertEquals(two.items.size, two.items.map { it.key }.toSet().size)
    }

    @Test
    fun unreadableLayoutNoteAndLooseRowsComeFromDesk() {
        val s = snapshot(listOf(connected("workstation").copy(desk = layout(loose = listOf("shell"), note = DeskNoteView.UNREADABLE))), emptyList(), listOf(other("shell")))
        assertEquals("Layout could not be read", ui(s).items.filterIsInstance<SpacesItem.LayoutNote>().single().text)
        assertEquals("shell", ui(s).items.filterIsInstance<SpacesItem.Other>().single().row.key.address)
        assertNull(ui(s).emptyText)
    }

    @Test
    fun sharedStorageAndStartFailureArePreserved() {
        val s = sample(); val existing = rows(s).copy(storageNotice = "storage unavailable", startError = "start failed", empty = AgentsEmpty.StartFailed)
        val model = ui(s, existing)
        assertEquals(existing.storageNotice, model.storageNotice)
        assertEquals(existing.startError, model.startError)
        assertEquals(AgentsEmpty.StartFailed, model.empty)
    }

    @Test
    fun noDocumentUsesSeatedLooseTerminalsWithoutIncludingChildren() {
        val s = snapshot(listOf(connected("workstation").copy(desk = layout(loose = listOf("shell", "a"), note = DeskNoteView.NO_DOCUMENT))),
            listOf(row("a", AgentAttention.IDLE)), listOf(other("shell"), other("child")))
        val model = ui(s)
        assertEquals(listOf("Not in a space"), model.items.filterIsInstance<SpacesItem.LooseHeader>().map { it.text })
        assertEquals(listOf("shell"), model.items.filterIsInstance<SpacesItem.Other>().map { it.row.key.address })
        assertEquals(listOf("a"), model.agents().map { it.row.key.address })
        assertTrue(model.items.none { it is SpacesItem.LayoutNote })
    }

    @Test
    fun statusChangeAndSpaceRenamePreserveKeysAndOrder() {
        val s = sample()
        val before = ui(s)
        val changed = s.copy(agents = s.agents.map { it.copy(attention = AgentAttention.NEEDS_YOU) },
            machines = s.machines.map { m -> m.copy(desk = m.desk!!.copy(spaces = m.desk!!.spaces.map { it.copy(name = "renamed") })) })
        val after = ui(changed)
        assertEquals(before.items.map { it.key }, after.items.map { it.key })
        assertEquals(before.agents().map { it.row.key }, after.agents().map { it.row.key })
        assertEquals(listOf("renamed", "renamed"), after.headers().map { it.name })
        assertTrue(after.headers().all { it.attention == AgentAttention.NEEDS_YOU })
    }

    @Test
    fun delimiterLikeNamesCannotCollideInListKeys() {
        val s = snapshot(listOf(
            connected("a:b").copy(desk = layout(loose = listOf("c"))),
            connected("a").copy(desk = layout(loose = listOf("b:c"))),
        ), listOf(row("c", AgentAttention.IDLE, machine = "a:b"), row("b:c", AgentAttention.IDLE, machine = "a")))
        val model = ui(s)
        assertEquals(model.items.size, model.items.map { it.key }.toSet().size)
    }
}
