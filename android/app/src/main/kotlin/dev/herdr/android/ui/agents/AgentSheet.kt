package dev.herdr.android.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.derivedStateOf
import dev.herdr.android.R
import dev.herdr.android.ui.components.OutlineButton
import dev.herdr.android.ui.machines.agoText
import dev.herdr.android.ui.theme.HerdrBorder
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrScrim
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType

// The read-only agent sheet (M1 pick 1b; no signed-off render). Drawn as
// render 5's sheet (.scrim, .sheet, .handle: popover, 22 dp top corners) with
// the terminal title bar's state pill (.pill) and the needs-you card's ask box.
// It lives in the screen's composition, so screenshots catch it and system
// back reaches AgentsHome. No terminal: that is M2.

private val rowLabel = HerdrType.secondary
private val rowValue = HerdrType.settingRow

@Composable
fun BoxScope.AgentSheet(sheet: AgentSheetUi, clock: State<Long>, onClose: () -> Unit) {
    val c = HerdrTheme.colors
    Box(
        Modifier
            .matchParentSize()
            .background(HerdrScrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(c.popover, RoundedCornerShape(topStart = HerdrRadius.sheetTop, topEnd = HerdrRadius.sheetTop))
            // Taps on the sheet are not taps on the scrim.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .semantics { paneTitle = sheet.name }
            .navigationBarsPadding()
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
        Row(
            Modifier.padding(start = HerdrSpacing.s20, end = HerdrSpacing.s16, top = HerdrSpacing.s4, bottom = HerdrSpacing.s14),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s12),
        ) {
            ProgramMark(sheet.icon, size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BasicText(sheet.name, style = HerdrType.sheetTitle.copy(color = c.fg), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(sheet.lineTwo, style = HerdrType.secondary.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatePill(sheet.state, sheet.tone)
        }
        if (sheet.requestKind != null || sheet.request != null) {
            Ask(sheet.requestKind, sheet.request, Modifier.padding(start = HerdrSpacing.s16, end = HerdrSpacing.s16, bottom = HerdrSpacing.s8))
        }
        Column(Modifier.padding(horizontal = HerdrSpacing.s20)) {
            Detail(stringResource(R.string.sheet_machine), first = true) { Value(sheet.machine) }
            Detail(stringResource(R.string.sheet_folder)) { Value(sheet.folder) }
            if (sheet.sinceMs != null || sheet.asOf != null) {
                Detail(stringResource(R.string.sheet_changed)) {
                    sheet.asOf?.let { Tag(it) }
                    sheet.sinceMs?.let { Ago(it, clock) }
                }
            }
        }
        BasicText(
            stringResource(R.string.sheet_later),
            Modifier.padding(start = HerdrSpacing.s20, end = HerdrSpacing.s20, top = HerdrSpacing.s14),
            style = HerdrType.secondary.copy(color = c.dimText),
        )
        OutlineButton(
            stringResource(R.string.sheet_close),
            onClose,
            Modifier
                .padding(start = HerdrSpacing.s16, end = HerdrSpacing.s16, top = HerdrSpacing.s14, bottom = HerdrSpacing.s16)
                .fillMaxWidth(),
        )
    }
}

/** `.pill`: 22 dp, radius 6, 12.5/600 in the state's colour on 15% of it; none without a state. */
@Composable
private fun StatePill(text: String, tone: SheetTone) {
    val c = HerdrTheme.colors
    val color: Color = when (tone) {
        SheetTone.NeedsYou -> c.blocked
        SheetTone.Done -> c.done
        SheetTone.Working -> c.working
        SheetTone.Idle -> c.dimText
        SheetTone.None -> c.dimText
    }
    Box(
        Modifier
            .heightIn(min = 22.dp)
            .background(color.copy(alpha = color.alpha * 0.15f), RoundedCornerShape(HerdrRadius.pill))
            .padding(horizontal = HerdrSpacing.s8, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = HerdrType.pill.copy(color = color), maxLines = 1)
    }
}

/** A label on the left, its value on the right, rows divided by a 1 dp border. */
@Composable
private fun Detail(label: String, first: Boolean = false, value: @Composable () -> Unit) {
    val c = HerdrTheme.colors
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(HerdrBorder.thin).background(c.border))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s8),
        ) {
            BasicText(label, Modifier.weight(1f), style = rowLabel.copy(color = c.dimText))
            value()
        }
    }
}

@Composable
private fun Value(text: String) {
    BasicText(text, style = rowValue.copy(color = HerdrTheme.colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** "3m ago", ticking from the shared clock like the list's ages. */
@Composable
private fun Ago(sinceMs: Long, clock: State<Long>) {
    val res = LocalContext.current.resources
    val text by remember(sinceMs, clock, res) { derivedStateOf { agoText(clock.value - sinceMs, res) } }
    BasicText(text, style = rowValue.copy(color = HerdrTheme.colors.fg), maxLines = 1)
}
