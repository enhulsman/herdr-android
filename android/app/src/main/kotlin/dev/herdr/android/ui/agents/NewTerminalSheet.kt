package dev.herdr.android.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.herdr.android.R
import dev.herdr.android.ui.terminal.LineIcon
import dev.herdr.android.ui.terminal.LineIconImage
import dev.herdr.android.ui.theme.HerdrBorder
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrScrim
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import kotlinx.coroutines.delay
import uniffi.herdr_mobile.StartPlace

// The "New" sheet, renders 9-11 (design/checkpoints/9-new-spaces.png,
// 10-new-folders.png, 11-new-error.png; sizes from .sheet, .cap, .chips,
// .mchip, .place, .radio, .where, .err, .starts, .tile and .agentsnote in
// design/renders/screens.html), and render 12's snackbar. Pure: it lays out a
// NewSheetUi and calls NewSheetActions.

/** What "New" shows on the Agents home: the "+" (dimmed when nothing is connected), the sheet, the snackbar. */
data class NewEntryUi(val enabled: Boolean, val sheet: NewSheetUi? = null, val noMachine: Boolean = false)

data class NewSheetActions(
    val onNew: () -> Unit,
    val onNoMachineGone: () -> Unit,
    val onMachine: (String) -> Unit,
    val onPlace: (StartPlace) -> Unit,
    /** Null: Terminal; else the agent's word. */
    val onStart: (String?) -> Unit,
    val onClose: () -> Unit,
) {
    companion object {
        /** Does nothing; for screenshots. */
        val Preview = NewSheetActions({}, {}, {}, {}, {}, {})
    }
}

// The render's sizes without a token of their own.
private val captionStyle = HerdrType.sectionHeader.copy(fontSize = 12.5.sp)
private val chipStyle = HerdrType.settingRow
private val folderStyle = HerdrType.cardMsg.copy(lineHeight = 1.3.em)
private val whereMono = HerdrType.cardMsg.copy(fontSize = 12.5.sp, lineHeight = 1.3.em)
private val errorStyle = HerdrType.body.copy(fontSize = 13.5.sp, lineHeight = 1.4.em)
private val tileStyle = HerdrType.secondary.copy(fontSize = 13.5.sp)
private val terminalStyle = HerdrType.settingRow.copy(fontSize = 15.sp, fontWeight = FontWeight(500))
private val TileHeight = 64.dp
private val TerminalHeight = 48.dp

@Composable
fun BoxScope.NewTerminalSheet(ui: NewSheetUi, actions: NewSheetActions) {
    val c = HerdrTheme.colors
    val title = stringResource(R.string.new_title)
    Box(
        Modifier
            .matchParentSize()
            .background(HerdrScrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = actions.onClose),
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(c.popover, RoundedCornerShape(topStart = HerdrRadius.sheetTop, topEnd = HerdrRadius.sheetTop))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .semantics { paneTitle = title }
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .padding(top = 10.dp, bottom = 6.dp)
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .background(c.input, RoundedCornerShape(2.dp)),
        )
        BasicText(
            title,
            Modifier.padding(start = HerdrSpacing.s20, end = HerdrSpacing.s20, top = HerdrSpacing.s4, bottom = HerdrSpacing.s6),
            style = HerdrType.sheetTitle.copy(color = c.fg),
        )
        ui.machines?.let { machines ->
            Caption(stringResource(R.string.new_machine), null)
            Row(
                Modifier.padding(start = HerdrSpacing.s20, end = HerdrSpacing.s20, bottom = HerdrSpacing.s4),
                horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s8),
            ) {
                for (m in machines) MachineChip(m, m == ui.machine, enabled = !ui.starting) { actions.onMachine(m) }
            }
        }
        Caption(ui.placesCaption, ui.placesNote)
        for (p in ui.places) PlaceRow(p, enabled = !ui.starting) { actions.onPlace(p.place) }
        Where(ui)
        ui.error?.let { ErrorBox(it) }
        Caption(stringResource(R.string.new_start), null)
        Starts(ui, actions.onStart)
        Spacer(Modifier.height(HerdrSpacing.s20))
    }
}

/** `.cap`: 12.5 sp, 500, dim; a [note] after it at 400. */
@Composable
private fun Caption(text: String, note: String?) {
    val c = HerdrTheme.colors
    BasicText(
        buildAnnotatedString {
            append(text)
            if (note != null) {
                pushStyle(SpanStyle(fontWeight = FontWeight(400)))
                append(" · ")
                append(note)
                pop()
            }
        },
        Modifier.padding(start = HerdrSpacing.s20, end = HerdrSpacing.s20, top = HerdrSpacing.s14, bottom = HerdrSpacing.s6),
        style = captionStyle.copy(color = c.dimText),
    )
}

/** `.mchip`: 36 dp, the connected dot and the name; the chosen one on the selected background. */
@Composable
private fun MachineChip(name: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .height(36.dp)
            .clip(shape)
            .then(if (on) Modifier.background(c.selected) else Modifier)
            .border(HerdrBorder.thin, if (on) c.ring else c.input, shape)
            .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = HerdrSpacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s8),
    ) {
        Box(Modifier.size(8.dp).background(c.connected, CircleShape))
        BasicText(name, style = chipStyle.copy(color = c.fg), maxLines = 1)
    }
}

/** `.place`: the radio, the name and the folder in terminal type; a note after it in working. */
@Composable
private fun PlaceRow(place: PlaceUi, enabled: Boolean, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.row)
    Row(
        Modifier
            .padding(horizontal = HerdrSpacing.s8)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .then(if (place.selected) Modifier.background(c.selected) else Modifier)
            .selectable(selected = place.selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = HerdrSpacing.s12, vertical = HerdrSpacing.s6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s12),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(if (place.selected) 5.dp else 1.5.dp, if (place.selected) c.fg else c.input, CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HerdrSpacing.s2)) {
            BasicText(place.title, style = HerdrType.rowName.copy(color = c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(
                buildAnnotatedString {
                    append(place.folder)
                    place.note?.let {
                        pushStyle(SpanStyle(fontFamily = HerdrType.secondary.fontFamily, color = c.working))
                        append(" · ")
                        append(it)
                        pop()
                    }
                },
                style = folderStyle.copy(color = c.dimText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `.where`: "Starts on **workstation** in **website**, as a new tab" over the whole folder. */
@Composable
private fun Where(ui: NewSheetUi) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.row)
    Column(
        Modifier
            .padding(start = HerdrSpacing.s16, end = HerdrSpacing.s16, top = HerdrSpacing.s8)
            .fillMaxWidth()
            .background(c.canvas, shape)
            .border(HerdrBorder.thin, c.border, shape)
            .padding(horizontal = HerdrSpacing.s14, vertical = HerdrSpacing.s10),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BasicText(
            buildAnnotatedString {
                append(ui.where.text)
                for (r in ui.where.bold) addStyle(SpanStyle(color = c.fg, fontWeight = FontWeight(500)), r.first, r.last + 1)
            },
            style = HerdrType.secondary.copy(color = c.muted),
        )
        BasicText(ui.whereFolder, style = whereMono.copy(color = c.termFg))
    }
}

/** `.err`: the hub's reason, a blocked bar on its left. */
@Composable
private fun ErrorBox(text: String) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .padding(start = HerdrSpacing.s16, end = HerdrSpacing.s16, top = HerdrSpacing.s10)
            .fillMaxWidth()
            .clip(shape)
            .background(lerp(c.popover, c.blocked, 0.09f))
            .drawBehind { drawRect(c.blocked, size = size.copy(width = 3.dp.toPx())) },
    ) {
        BasicText(
            text,
            Modifier.padding(start = 15.dp, end = HerdrSpacing.s12, top = 9.dp, bottom = 9.dp),
            style = errorStyle.copy(color = c.fg),
        )
    }
}

/** `.starts`: Terminal across, then the agents three to a row, the held row, or the note. */
@Composable
private fun Starts(ui: NewSheetUi, onStart: (String?) -> Unit) {
    val c = HerdrTheme.colors
    val enabled = !ui.starting
    Column(
        Modifier.padding(horizontal = HerdrSpacing.s16).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HerdrSpacing.s8),
    ) {
        val terminal = stringResource(R.string.new_terminal)
        Row(
            Modifier
                .fillMaxWidth()
                .height(TerminalHeight)
                .alpha(if (enabled) 1f else 0.38f)
                .clip(RoundedCornerShape(HerdrRadius.button))
                .background(c.solid)
                .clickable(enabled = enabled, role = Role.Button) { onStart(null) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s10, Alignment.CenterHorizontally),
        ) {
            LineIconImage(LineIcon.Shell, c.solidFg, 20.dp)
            BasicText(terminal, style = terminalStyle.copy(color = c.solidFg))
        }
        when (val a = ui.agents) {
            is AgentsRowUi.Ready -> for (row in a.agents.chunked(AGENT_COLUMNS)) {
                TileRow(row.size) { i -> AgentTile(row[i], enabled) { onStart(row[i].word) } }
            }
            is AgentsRowUi.Checking -> {
                TileRow(a.slots) { i -> HeldTile(spinner = i == 0) }
                Note(a.note)
            }
            is AgentsRowUi.Unavailable -> Note(a.note)
        }
    }
}

/** A row of [count] tiles at a third of the width each. */
@Composable
private fun TileRow(count: Int, tile: @Composable RowScope.(Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s8)) {
        for (i in 0 until AGENT_COLUMNS) {
            if (i < count) tile(i) else Spacer(Modifier.weight(1f))
        }
    }
}

/** `.tile`: 64 dp, the program's mark over its name. */
@Composable
private fun RowScope.AgentTile(agent: AgentTileUi, enabled: Boolean, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.row)
    Column(
        Modifier
            .weight(1f)
            .height(TileHeight)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(shape)
            .border(HerdrBorder.thin, c.input, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(HerdrSpacing.s6, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProgramMark(agent.icon, size = 20.dp)
        BasicText(agent.name, style = tileStyle.copy(color = c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.tile.ghost`: a dashed place held for an agent while the check runs; the first shows a still spinner. */
@Composable
private fun RowScope.HeldTile(spinner: Boolean) {
    val c = HerdrTheme.colors
    Box(
        Modifier
            .weight(1f)
            .height(TileHeight)
            .drawBehind {
                val w = HerdrBorder.thin.toPx()
                drawRoundRect(
                    c.input,
                    topLeft = Offset(w / 2, w / 2),
                    size = size.copy(width = size.width - w, height = size.height - w),
                    cornerRadius = CornerRadius(HerdrRadius.row.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (spinner) StillRing(c.dimText)
    }
}

/** `ico.spin` drawn still, in [color]: a 24% ring with a quarter arc, 20 dp. */
@Composable
private fun StillRing(color: Color) {
    Canvas(Modifier.size(20.dp)) {
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

/** `.agentsnote`: 13 sp, dim. */
@Composable
private fun Note(text: String) {
    BasicText(
        text,
        Modifier.padding(start = HerdrSpacing.s2, end = HerdrSpacing.s2, top = HerdrSpacing.s2),
        style = HerdrType.secondary.copy(color = HerdrTheme.colors.dimText),
    )
}

/** Render 12's `.snack`: "No machine connected", gone after a few seconds. */
@Composable
fun BoxScope.NoMachineSnack(onGone: () -> Unit) {
    val c = HerdrTheme.colors
    LaunchedEffect(Unit) {
        delay(SNACK_MS)
        onGone()
    }
    BasicText(
        stringResource(R.string.new_no_machine),
        Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = HerdrSpacing.s16, end = HerdrSpacing.s16, bottom = HerdrSpacing.s16)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(c.solid, RoundedCornerShape(HerdrRadius.button))
            .padding(horizontal = HerdrSpacing.s16, vertical = 14.dp),
        style = chipStyle.copy(color = c.solidFg),
    )
}

private const val SNACK_MS = 3_000L
