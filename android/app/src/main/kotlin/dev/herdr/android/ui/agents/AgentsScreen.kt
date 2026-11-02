package dev.herdr.android.ui.agents

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.R
import dev.herdr.android.ui.components.Chevron
import dev.herdr.android.ui.components.ChevronIcon
import dev.herdr.android.ui.components.OutlineButton
import dev.herdr.android.ui.theme.HerdrBorder
import dev.herdr.android.ui.theme.HerdrLayout
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import uniffi.herdr_mobile.AgentAttention

// The Agents home, renders 1 and 2 (design/checkpoints/1-home-dark.png,
// 2-home-light.png; sizes from .top, .iconbtn, .notice, .sec, .row, .bar,
// .offline, .card and .st in design/renders/screens.html, and components in
// design-tokens.json). Pure: it lays out an AgentsUi and calls AgentsActions;
// AgentsRoute feeds it from the hub. The empty states and the sheet have no
// render and are drawn from the tokens in the same style.
//
// Ages tick from one shared clock ([clock]); only each age's text reads it, and
// only when its text changes does it redraw, so the list itself does not
// recompose on a tick.

data class AgentsActions(
    val onMachines: () -> Unit,
    val onAddMachine: () -> Unit,
    val onOpen: (AgentKey) -> Unit,
    val onCloseSheet: () -> Unit,
    val onToggleOthers: () -> Unit,
    /** Start the hub again after it failed to start. */
    val onRetry: () -> Unit,
) {
    companion object {
        /** Does nothing; for screenshots. */
        val Preview = AgentsActions({}, {}, {}, {}, {}, {})
    }
}

// Render 1's sizes without a token of their own.
private val tagStyle = HerdrType.secondary.copy(fontSize = 11.5.sp)
private val ProgressWidth = 150.dp
private val StateSlot = 18.dp

/** The row's mark: 20 dp (components.agentRow). */
private val MarkSize = 20.dp

/** The card's mark: render 1 draws it at the icon's own 1em, 16 dp (`.ic` is sized only in `.row`). */
private val CardMarkSize = 16.dp

/**
 * [spin]: the working spinner turns (900 ms a turn); false draws it still, as
 * with reduced motion, and leaves rows unanimated when they change section.
 */
@Composable
fun AgentsScreen(
    ui: AgentsUi,
    othersExpanded: Boolean,
    sheet: AgentSheetUi?,
    clock: State<Long>,
    spin: Boolean,
    actions: AgentsActions,
    modifier: Modifier = Modifier,
) {
    val c = HerdrTheme.colors
    Box(modifier.fillMaxSize().background(c.chrome)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(ui.machinesBadge, actions.onMachines)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                ui.storageNotice?.let { text ->
                    item(key = "storage") { StorageNotice(text) }
                }
                for (notice in ui.notices) {
                    item(key = "notice/${notice.machine}") { MachineNotice(notice, actions.onMachines) }
                }
                when (ui.empty) {
                    AgentsEmpty.NoMachines -> item(key = "empty") { NoMachines(actions.onAddMachine) }
                    AgentsEmpty.NoAgents -> item(key = "empty") { NoAgents() }
                    AgentsEmpty.StartFailed -> item(key = "empty") { StartFailed(ui.startError.orEmpty(), actions.onRetry) }
                    null -> Unit
                }
                agentSections(ui, clock, spin, actions)
                if (ui.others.isNotEmpty()) {
                    item(key = "section/others") {
                        Section(
                            stringResource(R.string.agents_other),
                            c.dimText,
                            ui.others.size,
                            slide(spin),
                            expanded = othersExpanded,
                            onToggle = actions.onToggleOthers,
                        )
                    }
                    if (othersExpanded) {
                        for (other in ui.others) {
                            item(key = "other/${other.key.machine}/${other.key.address}") {
                                OtherRow(other, slide(spin)) { actions.onOpen(other.key) }
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(HerdrSpacing.s24)) }
            }
        }
        sheet?.let { AgentSheet(it, clock, actions.onCloseSheet) }
    }
}

private fun LazyListScope.agentSections(ui: AgentsUi, clock: State<Long>, spin: Boolean, actions: AgentsActions) {
    fun section(id: String, label: Int, color: @Composable () -> Color, rows: List<AgentRowUi>, card: Boolean) {
        if (rows.isEmpty()) return
        item(key = "section/$id") {
            Section(stringResource(label), color(), rows.size, slide(spin))
        }
        for (r in rows) {
            item(key = "agent/${r.key.machine}/${r.key.address}") {
                val open = { actions.onOpen(r.key) }
                if (card) NeedsYouCard(r, clock, open, slide(spin)) else AgentRow(r, clock, spin, open, slide(spin))
            }
        }
    }
    section("needs", R.string.agents_needs_you, { HerdrTheme.colors.blocked }, ui.needsYou, card = true)
    section("done", R.string.agents_done, { HerdrTheme.colors.done }, ui.done, card = false)
    section("working", R.string.agents_working, { HerdrTheme.colors.working }, ui.working, card = false)
    section("idle", R.string.agents_idle, { HerdrTheme.colors.dimText }, ui.idle, card = false)
}

/** An agent moving between sections slides into place over 200 ms (motion tokens); never with reduced motion. */
private fun LazyItemScope.slide(spin: Boolean): Modifier =
    if (spin) Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(200)) else Modifier

@Composable
private fun TopBar(badge: Boolean, onMachines: () -> Unit) {
    val c = HerdrTheme.colors
    val label = stringResource(R.string.machines_title)
    Row(
        Modifier
            .fillMaxWidth()
            .height(HerdrLayout.topBarHeight)
            .padding(start = HerdrSpacing.s20, end = HerdrSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            stringResource(R.string.agents_title),
            Modifier.weight(1f),
            style = HerdrType.screenTitle.copy(color = c.fg),
            maxLines = 1,
        )
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(HerdrRadius.button))
                .clickable(role = Role.Button, onClick = onMachines)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            ServerIcon(c.muted)
            if (badge) {
                // .iconbtn .badge: 8 dp, 11 dp from the top and right, ringed in idle on chrome.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 11.dp, end = 11.dp)
                        .size(8.dp)
                        .background(c.chrome, CircleShape)
                        .border(1.5.dp, c.idle, CircleShape),
                )
            }
        }
    }
}

/** herdr-app's server icon (design/renders/icons/server.svg): two racks, stroke 1.5 on 24. */
@Composable
private fun ServerIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.5f * u)
        for (top in listOf(4f, 13f)) {
            drawRoundRect(color, Offset(3.5f * u, top * u), Size(17f * u, 7f * u), CornerRadius(2f * u), style = stroke)
        }
    }
}

/** A storage problem from the hub, on the hover background like a notice. */
@Composable
private fun StorageNotice(text: String) {
    val c = HerdrTheme.colors
    BasicText(
        text,
        Modifier
            .padding(start = HerdrLayout.screenPaddingX, end = HerdrLayout.screenPaddingX, bottom = HerdrSpacing.s4)
            .fillMaxWidth()
            .background(c.hover, RoundedCornerShape(HerdrRadius.notice))
            .padding(horizontal = HerdrSpacing.s12, vertical = 9.dp),
        style = HerdrType.secondary.copy(color = c.muted),
    )
}

/** components.machineNotice: "server unreachable · last connected 2h ago", leading to Machines. */
@Composable
private fun MachineNotice(notice: NoticeUi, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.notice)
    Row(
        Modifier
            .padding(start = HerdrLayout.screenPaddingX, end = HerdrLayout.screenPaddingX, bottom = HerdrSpacing.s4)
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(c.hover)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = HerdrSpacing.s12, vertical = HerdrSpacing.s6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s10),
    ) {
        Ring()
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.fg)) { append(notice.machine) }
                append(" ")
                append(notice.text)
            },
            Modifier.weight(1f),
            style = HerdrType.secondary.copy(color = c.muted),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ChevronIcon(Chevron.Right, 14.dp, c.muted)
    }
}

/** The idle mark: a 9 dp ring, 1.5 dp, in idle. */
@Composable
private fun Ring() {
    Box(Modifier.size(9.dp).border(1.5.dp, HerdrTheme.colors.idle, CircleShape))
}

/** components.sectionHeader: 40 dp, the state's word in its colour, the count in dim. */
@Composable
private fun Section(
    label: String,
    color: Color,
    count: Int,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val c = HerdrTheme.colors
    Row(
        modifier
            .padding(horizontal = HerdrLayout.listPaddingX)
            .fillMaxWidth()
            .height(HerdrLayout.sectionHeaderHeight)
            .then(
                if (onToggle != null) {
                    Modifier
                        .clip(RoundedCornerShape(HerdrRadius.row))
                        .clickable(role = Role.Button, onClick = onToggle)
                        .semantics { stateDescription = if (expanded == true) "expanded" else "collapsed" }
                } else {
                    Modifier
                },
            )
            .padding(start = HerdrLayout.rowPaddingX, end = HerdrLayout.rowPaddingX, top = HerdrSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        BasicText(label, style = HerdrType.sectionHeader.copy(color = color))
        BasicText(count.toString(), style = HerdrType.sectionHeader.copy(color = c.dimText))
        if (expanded != null) {
            Spacer(Modifier.weight(1f))
            ChevronIcon(if (expanded) Chevron.Down else Chevron.Right, 14.dp, c.dimText)
        }
    }
}

/** The signature card (components.needsYouCard): 3 dp red left edge, the kind above the request. */
@Composable
private fun NeedsYouCard(row: AgentRowUi, clock: State<Long>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.card)
    Column(
        modifier
            .padding(start = HerdrLayout.listPaddingX, end = HerdrLayout.listPaddingX, top = HerdrSpacing.s4, bottom = HerdrSpacing.s8)
            .fillMaxWidth()
            .clip(shape)
            .cardEdge(c.popover, c.border, c.blocked)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = row.label }
            // The CSS borders take room: 3 left, 1 elsewhere, then padding 12 14 13 13.
            .padding(start = 3.dp + 13.dp, top = 1.dp + 12.dp, end = 1.dp + 14.dp, bottom = 1.dp + 13.dp),
        verticalArrangement = Arrangement.spacedBy(HerdrSpacing.s10),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s12)) {
            ProgramMark(row.icon, size = CardMarkSize)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BasicText(row.name, style = HerdrType.rowName.copy(color = c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(row.lineTwo, style = HerdrType.secondary.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            row.asOf?.let { Tag(it) }
            Age(row.sinceMs, clock)
        }
        Ask(row.requestKind, row.request)
    }
}

/** The ask box: canvas, radius 9, padding 9 11; kind (cardKind, blocked) over msg (cardMsg, termFg). */
@Composable
fun Ask(kind: String?, request: String?, modifier: Modifier = Modifier) {
    if (kind == null && request == null) return
    val c = HerdrTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.canvas, RoundedCornerShape(HerdrRadius.askBox))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        kind?.let { BasicText(it, style = HerdrType.cardKind.copy(color = c.blocked)) }
        request?.let { BasicText(it, style = HerdrType.cardMsg.copy(color = c.termFg), maxLines = 4, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * Draws `.card`'s borders: popover under a 1 dp border all round, with the
 * left edge 3 dp in [edge] curving into the corners as CSS draws it.
 */
private fun Modifier.cardEdge(fill: Color, border: Color, edge: Color): Modifier = drawBehind {
    val r = HerdrRadius.card.toPx()
    val one = 1.dp.toPx()
    val left = HerdrBorder.signature.toPx()
    drawRoundRect(fill, cornerRadius = CornerRadius(r))
    drawRoundRect(border, cornerRadius = CornerRadius(r))
    drawRoundRect(fill, Offset(one, one), Size(size.width - 2 * one, size.height - 2 * one), CornerRadius(r - one))
    val outer = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
    val inner = Path().apply {
        addRoundRect(
            RoundRect(
                left, one, size.width - one, size.height - one,
                topLeftCornerRadius = CornerRadius(r - left, r - one),
                bottomLeftCornerRadius = CornerRadius(r - left, r - one),
                topRightCornerRadius = CornerRadius(r - one),
                bottomRightCornerRadius = CornerRadius(r - one),
            ),
        )
    }
    val ring = Path().apply { op(outer, inner, PathOperation.Difference) }
    clipRect(right = r) { drawPath(ring, edge) }
}

/** components.agentRow: mark, name over "folder · machine" (and a progress bar), age, state mark. */
@Composable
private fun AgentRow(row: AgentRowUi, clock: State<Long>, spin: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = HerdrTheme.colors
    ListRow(onClick, row.label, modifier) {
        ProgramMark(row.icon, size = MarkSize)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(
                row.name,
                style = HerdrType.rowName.copy(color = if (row.attention == AgentAttention.IDLE) c.muted else c.fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(row.lineTwo, style = HerdrType.secondary.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.progress?.let { ProgressBar(it) }
        }
        row.asOf?.let { Tag(it) }
        Age(row.sinceMs, clock)
        Box(Modifier.width(StateSlot), contentAlignment = Alignment.Center) {
            when (row.attention) {
                AgentAttention.WORKING -> Spinner(spin)
                AgentAttention.DONE -> Box(Modifier.size(9.dp).background(c.done, CircleShape))
                else -> Ring()
            }
        }
    }
}

/** A terminal that never reported status: no age and no state mark. */
@Composable
private fun OtherRow(row: OtherRowUi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    ListRow(onClick, null, modifier) {
        ProgramMark(row.icon, size = MarkSize)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(row.name, style = HerdrType.rowName.copy(color = c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(row.lineTwo, style = HerdrType.secondary.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        row.asOf?.let { Tag(it) }
    }
}

@Composable
private fun ListRow(onClick: () -> Unit, state: String?, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .padding(horizontal = HerdrLayout.listPaddingX)
            .fillMaxWidth()
            .heightIn(min = HerdrLayout.rowMinHeight)
            .clip(RoundedCornerShape(HerdrRadius.row))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { state?.let { stateDescription = it } }
            .padding(horizontal = HerdrLayout.rowPaddingX, vertical = HerdrSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s12),
        content = content,
    )
}

/** `.bar`: 150 x 3, radius 2, border colour behind the working colour. */
@Composable
private fun ProgressBar(fraction: Float) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .padding(top = HerdrSpacing.s4)
            .width(ProgressWidth)
            .height(3.dp)
            .clip(shape)
            .background(c.border),
    ) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(c.working))
    }
}

/** `.offline`, here "as of 14:05": 11.5 sp dim in a 1 dp border, radius 5. */
@Composable
fun Tag(text: String) {
    val c = HerdrTheme.colors
    BasicText(
        text,
        Modifier
            .border(HerdrBorder.thin, c.border, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = tagStyle.copy(color = c.dimText),
        maxLines = 1,
    )
}

/**
 * The state's age ("3m"), from [clock]. Only this text reads the clock, through
 * a derived state, so a tick redraws it only when the text changes.
 */
@Composable
fun Age(sinceMs: Long?, clock: State<Long>) {
    if (sinceMs == null) return
    val res = LocalContext.current.resources
    val text by remember(sinceMs, clock, res) { derivedStateOf { ageText(clock.value - sinceMs, res) } }
    BasicText(text, style = HerdrType.secondary.copy(color = HerdrTheme.colors.dimText), maxLines = 1)
}

/** The working spinner (animations.spinner): a 24% ring with a quarter arc, 15 dp, 3.2 on 24, one turn per 900 ms. */
@Composable
private fun Spinner(spin: Boolean) {
    val color = HerdrTheme.colors.working
    val turn: State<Float>? = if (spin) {
        rememberInfiniteTransition(label = "spinner").animateFloat(
            0f,
            360f,
            infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "turn",
        )
    } else {
        null
    }
    Canvas(Modifier.size(15.dp).graphicsLayer { rotationZ = turn?.value ?: 0f }) {
        val u = size.width / 24f
        val stroke = 3.2f * u
        val radius = 9.4f * u
        drawCircle(color.copy(alpha = color.alpha * 0.24f), radius, style = Stroke(stroke))
        drawArc(
            color,
            startAngle = -90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(2 * radius, 2 * radius),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** First launch: no machines yet (no signed-off render; the Machines screen's text and button). */
@Composable
private fun NoMachines(onAddMachine: () -> Unit) {
    EmptyBlock(stringResource(R.string.agents_empty_machines_title), stringResource(R.string.machines_empty)) {
        OutlineButton(stringResource(R.string.machines_add), onAddMachine, Modifier.fillMaxWidth())
    }
}

/** The hub could not start: the reason as the Machines screen gives it, and Try again (no signed-off render). */
@Composable
private fun StartFailed(reason: String, onRetry: () -> Unit) {
    EmptyBlock(stringResource(R.string.agents_start_failed_title), reason) {
        OutlineButton(stringResource(R.string.action_try_again), onRetry, Modifier.fillMaxWidth())
    }
}

@Composable
private fun NoAgents() {
    EmptyBlock(stringResource(R.string.agents_empty_agents_title), stringResource(R.string.agents_empty_agents_body))
}

@Composable
private fun EmptyBlock(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val c = HerdrTheme.colors
    Column(
        Modifier
            .padding(horizontal = HerdrLayout.screenPaddingX)
            .padding(top = HerdrSpacing.s12, bottom = HerdrSpacing.s8)
            .fillMaxWidth()
            .padding(horizontal = HerdrSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(HerdrSpacing.s6),
    ) {
        BasicText(title, style = HerdrType.machineName.copy(color = c.fg))
        BasicText(body, style = HerdrType.body.copy(color = c.muted))
        action?.let {
            Spacer(Modifier.height(HerdrSpacing.s10))
            it()
        }
    }
}
