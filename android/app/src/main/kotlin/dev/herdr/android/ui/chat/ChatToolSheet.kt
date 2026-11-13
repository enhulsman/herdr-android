package dev.herdr.android.ui.chat

import android.widget.Toast
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.herdr.android.ui.terminal.LineIcon
import dev.herdr.android.ui.terminal.LineIconImage
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrScrim
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import kotlin.math.abs
import kotlinx.coroutines.launch
import uniffi.herdr_mobile.ChatCall

// Spike for issue 17 (never merged): the sheet a folded tool line opens, after
// the Claude app's: three fixed heights, a row per call with its kind's icon,
// and a detail with copyable sections.

/** One call in the sheet: "Read  upload.rs", the text as code when it is a path or a command. */
data class SheetRow(val icon: LineIcon, val verb: String, val text: String, val mono: Boolean)

fun sheetRow(call: ChatCall): SheetRow {
    val kind = kindOf(call.name)
    val (icon, verb) = when (kind) {
        ToolKind.Command -> LineIcon.Shell to "Ran"
        ToolKind.Search -> LineIcon.Search to "Searched"
        ToolKind.Read -> LineIcon.Eye to "Read"
        ToolKind.Edit -> LineIcon.File to "Updated"
        ToolKind.Image -> LineIcon.Eye to "Viewed"
        ToolKind.Agent -> LineIcon.Tasks to "Ran agent"
        ToolKind.Other -> LineIcon.Wrench to call.name
    }
    val named = call.title.removePrefix("${call.name} ").takeIf { it != call.title }
    val firstLine = call.input.lineSequence().firstOrNull()?.trim().orEmpty()
    return when {
        call.title == call.name -> SheetRow(icon, verb, "", mono = false)
        named != null && (kind == ToolKind.Read || kind == ToolKind.Edit) -> SheetRow(icon, verb, named.substringAfterLast('/'), mono = true)
        named != null -> SheetRow(icon, verb, named, mono = true)
        call.title.removePrefix("! ") == firstLine -> SheetRow(icon, verb, firstLine, mono = true)
        else -> SheetRow(icon, verb, call.title, mono = false)
    }
}

/**
 * Where a sheet let go at height [h] comes to rest: the index of one of
 * [stops] (heights, low to high), or -1 to close. [velocity] is the finger's,
 * down positive; a fling moves one stop.
 */
fun sheetSettle(h: Float, velocity: Float, stops: List<Float>): Int {
    val fling = 1000f
    val slack = 8f
    return when {
        velocity > fling -> stops.indexOfLast { it < h - slack }
        velocity < -fling -> stops.indexOfFirst { it > h + slack }.let { if (it < 0) stops.lastIndex else it }
        h < stops[0] * 0.7f -> -1
        else -> stops.indices.minBy { abs(stops[it] - h) }
    }
}

/** The sheet stands out: lighter than the dimmed chat in Graphite, off-white in Paper. */
@Composable
private fun sheetColor(): Color = HerdrTheme.colors.let { if (it.isDark) it.popover else it.canvas }

@Composable
private fun boxColor(): Color = HerdrTheme.colors.let { if (it.isDark) it.canvas else it.chrome }

@Composable
internal fun ToolSheet(calls: List<ChatCall>, state: ChatSpikeState, images: ChatImageSource) {
    val close = { state.sheet = null; state.detail = null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screen = constraints.maxHeight.toFloat()
        val top = WindowInsets.safeDrawing.getTop(density).toFloat()
        // Default, midway and full, whatever the number of calls.
        val stops = remember(screen, top) { listOf(screen * 0.46f, screen * 0.62f, screen - top) }
        var h by remember { mutableFloatStateOf(stops[0]) }
        val scope = rememberCoroutineScope()
        fun settle(velocity: Float) {
            val to = sheetSettle(h, velocity, stops)
            scope.launch {
                animate(h, if (to < 0) 0f else stops[to]) { v, _ -> h = v }
                if (to < 0) close()
            }
        }
        val drag = rememberDraggableState { d -> h = (h - d).coerceIn(0f, stops.last()) }
        // The list grows the sheet before it scrolls, and pulls it down once scrolled to its top.
        val nested = remember(stops) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0 || h >= stops.last()) return Offset.Zero
                    val used = minOf(-available.y, stops.last() - h)
                    h += used
                    return Offset(0f, -used)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y <= 0 || source != NestedScrollSource.UserInput) return Offset.Zero
                    h = (h - available.y).coerceAtLeast(0f)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (stops.any { abs(it - h) < 1f }) return Velocity.Zero
                    settle(available.y)
                    return available
                }
            }
        }
        Box(Modifier.fillMaxSize().background(HerdrScrim).clickable(remember { MutableInteractionSource() }, null, onClick = close))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { h.toDp() })
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(sheetColor())
                .clickable(remember { MutableInteractionSource() }, null) {}
                .nestedScroll(nested),
        ) {
            Column(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { settle(it) })) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(HerdrTheme.colors.dim))
                }
                val d = state.detail
                if (d == null) SheetHeader(toolLine(calls), null, LineIcon.Close, close)
                else SheetHeader(d.name, callState(d), LineIcon.Back, state::closeDetail)
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                val d = state.detail
                if (d == null) CallList(calls) { state.detail = it } else CallDetail(d, images) { state.viewer = it }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun callState(d: ChatCall) = if (!d.done) "Running" else if (d.failed) "Failed" else "Completed"

/** X (list) or back arrow (detail) on the left, the title centred. */
@Composable
private fun SheetHeader(title: String, sub: String?, icon: LineIcon, onBack: () -> Unit) {
    val c = HerdrTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Box(Modifier.align(Alignment.CenterStart).size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            LineIconImage(icon, c.fg, 22.dp)
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(title, style = HerdrType.settingRow.copy(color = c.fg, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                BasicText(sub, style = HerdrType.secondary.copy(color = if (sub == "Failed") c.blocked else c.dimText))
            }
        }
    }
}

/** A row per call: the kind's icon on a thin line joining them, the verb, then what. */
@Composable
private fun CallList(calls: List<ChatCall>, onOpen: (ChatCall) -> Unit) {
    val c = HerdrTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for ((i, call) in calls.withIndex()) {
            val r = sheetRow(call)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(call) }.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(1.dp).height(14.dp).background(if (i > 0) c.border else Color.Transparent))
                    LineIconImage(r.icon, if (call.failed) c.blocked else c.fg, 20.dp)
                    Box(Modifier.width(1.dp).height(14.dp).background(if (i < calls.lastIndex) c.border else Color.Transparent))
                }
                Row(Modifier.weight(1f).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(r.verb, style = HerdrType.body.copy(color = c.fg))
                    if (r.text.isNotEmpty()) {
                        BasicText(
                            r.text,
                            style = (if (r.mono) HerdrType.cardMsg else HerdrType.body).copy(color = c.muted),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (call.failed) BasicText("failed", style = HerdrType.secondary.copy(color = c.blocked))
                    else if (!call.done) BasicText("running", style = HerdrType.secondary.copy(color = c.dimText))
                }
            }
        }
    }
}

/** Description, Command (or Input) and Output, each copyable; the call's images between. */
@Composable
private fun CallDetail(d: ChatCall, images: ChatImageSource, onImage: (String) -> Unit) {
    val command = kindOf(d.name) == ToolKind.Command
    val firstLine = d.input.lineSequence().firstOrNull()?.trim().orEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (d.title != d.name && d.title.removePrefix("! ") != firstLine) Section("Description", d.title, mono = false, copy = false)
        if (d.input.isNotBlank() && d.input != "{}") Section(if (command) "Command" else "Input", d.input.trimEnd(), mono = true, copy = true)
        if (d.images.isNotEmpty()) Thumbs(d.images, images, end = false, onOpen = onImage)
        if (d.output.isNotEmpty()) Section("Output", d.output.take(4000).trimEnd() + if (d.output.length > 4000) "\n…" else "", mono = true, copy = true)
        else if (d.done) Section("Output", "No output", mono = false, copy = false)
    }
}

@Composable
private fun Section(label: String, text: String, mono: Boolean, copy: Boolean) {
    val c = HerdrTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = HerdrType.secondary.copy(color = c.muted, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
            if (copy) {
                CopyIcon(Modifier.clip(RoundedCornerShape(HerdrRadius.chip)).clickable {
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }.padding(8.dp), tint = c.fg, size = 18.dp)
            }
        }
        BasicText(
            text,
            style = (if (mono) HerdrType.cardMsg.copy(color = c.termFg) else HerdrType.body.copy(color = c.fg)),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(HerdrRadius.row)).background(boxColor()).padding(14.dp),
        )
    }
}
