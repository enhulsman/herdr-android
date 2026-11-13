package dev.herdr.android.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.android.ui.components.Chevron
import dev.herdr.android.ui.components.ChevronIcon
import dev.herdr.android.ui.terminal.LineIcon
import dev.herdr.android.ui.terminal.LineIconImage
import dev.herdr.android.ui.terminal.TerminalHeader
import dev.herdr.android.ui.theme.HerdrLayout
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatPoll
import uniffi.herdr_mobile.ChatRow

// Spike for issue 17 (never merged): the try-it chat view. Wording and look
// are rough on purpose; the OpenSpec change designs the real one.

class ChatSpikeState {
    var draft by mutableStateOf("")
    var sheet by mutableStateOf<List<ChatCall>?>(null)
    var detail by mutableStateOf<ChatCall?>(null)
    /** The image shown full screen, by key. */
    var viewer by mutableStateOf<String?>(null)
    var attached by mutableStateOf<List<Attachment>>(emptyList())
}

@Composable
fun ChatSpikeScreen(
    header: TerminalHeader,
    poll: ChatPoll?,
    state: ChatSpikeState,
    images: ChatImageSource,
    onBack: () -> Unit,
    onTerminal: () -> Unit,
    onPick: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HerdrTheme.colors
    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            ChatTitle(header, onBack, onTerminal)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    poll == null -> Centered("Reading the session…")
                    poll.state != "ready" -> Fallback(poll.reason, onTerminal)
                    else -> Messages(poll, state, images)
                }
            }
            if (poll?.state == "ready") MessageBox(state, poll.canSend, onPick, onSend)
        }
        state.sheet?.let { calls -> ToolSheet(calls, state, images) }
        state.viewer?.let { key -> ImageViewer(key, images) { state.viewer = null } }
    }
}

@Composable
private fun ChatTitle(header: TerminalHeader, onBack: () -> Unit, onTerminal: () -> Unit) {
    val c = HerdrTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = HerdrLayout.terminalTitleHeight).background(c.chrome)
            .padding(start = HerdrSpacing.s4, end = HerdrSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(HerdrRadius.button)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            ChevronIcon(Chevron.Left, 22.dp, c.fg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(header.name, style = HerdrType.terminalTitle.copy(color = c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(header.lineTwo, style = HerdrType.secondary.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ViewSwitch("Terminal", onTerminal)
    }
}

/** The pill that switches between the terminal and the chat view. */
@Composable
fun ViewSwitch(label: String, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    BasicText(
        label,
        style = HerdrType.pill.copy(color = c.fg),
        modifier = Modifier.clip(RoundedCornerShape(HerdrRadius.chip)).background(c.raised).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Centered(text: String) {
    val c = HerdrTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = HerdrType.body.copy(color = c.dimText, textAlign = TextAlign.Center))
    }
}

@Composable
private fun Fallback(reason: String, onTerminal: () -> Unit) {
    val c = HerdrTheme.colors
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("This session can't be shown as a chat.", style = HerdrType.body.copy(color = c.fg, textAlign = TextAlign.Center))
        BasicText(reason, style = HerdrType.secondary.copy(color = c.dimText, textAlign = TextAlign.Center), modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        ViewSwitch("Show the terminal", onTerminal)
    }
}

@Composable
private fun Messages(poll: ChatPoll, state: ChatSpikeState, images: ChatImageSource) {
    val list = rememberLazyListState()
    val rows = poll.rows
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) list.scrollToItem(rows.size - 1 + if (poll.cut) 1 else 0) }
    if (rows.isEmpty()) return Centered("No messages yet.")
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (poll.cut) item { Note("Earlier messages are not loaded") }
        itemsIndexed(rows) { _, row ->
            when (row.kind) {
                "user" -> UserBubble(row, images) { state.viewer = it }
                "said" -> Copyable(row.text) { Said(row.text) }
                "agent" -> AgentCard(row)
                "tools" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolLine(row.calls) { state.sheet = row.calls }
                    val shots = row.calls.flatMap { it.images }
                    if (shots.isNotEmpty()) Thumbs(shots, images, end = false) { state.viewer = it }
                }
                else -> Note(row.text)
            }
        }
    }
}

@Composable
private fun UserBubble(row: ChatRow, images: ChatImageSource, onOpen: (String) -> Unit) {
    val c = HerdrTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (row.images.isNotEmpty()) Thumbs(row.images, images, end = true, onOpen)
        if (row.text.isNotEmpty()) Copyable(row.text, end = true) {
            BasicText(
                row.text, style = HerdrType.body.copy(color = c.fg),
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp)).background(c.raised).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** The message's text is selectable as usual; a small Copy under it copies all of it. */
@Composable
private fun Copyable(text: String, end: Boolean = false, content: @Composable () -> Unit) {
    val c = HerdrTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        SelectionContainer { content() }
        CopyIcon(Modifier.clip(RoundedCornerShape(HerdrRadius.chip)).clickable {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }.padding(8.dp))
    }
}

/** Lucide's copy icon, as the app's other line icons. */
@Composable
private fun CopyIcon(modifier: Modifier = Modifier) {
    LineIconImage(LineIcon.Copy, HerdrTheme.colors.dimText, 16.dp, modifier.semantics { contentDescription = "Copy" })
}

/** A subagent's report or another session's message, folded after a few blocks. */
@Composable
private fun AgentCard(row: ChatRow) {
    val c = HerdrTheme.colors
    var open by remember(row.text) { mutableStateOf(false) }
    val long = markdownBlocks(row.text).size > 3 || row.text.length > 400
    Copyable(row.text) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(HerdrRadius.row)).border(1.dp, c.border, RoundedCornerShape(HerdrRadius.row))
                .clickable(enabled = long) { open = !open }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("Agent · ${row.from}", style = HerdrType.secondary.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Said(row.text, maxBlocks = if (long && !open) 3 else Int.MAX_VALUE)
            if (long) BasicText(if (open) "Show less" else "Show all", style = HerdrType.secondary.copy(color = c.fg, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun Said(text: String, maxBlocks: Int = Int.MAX_VALUE) {
    val c = HerdrTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in markdownBlocks(text).take(maxBlocks)) {
            when (b) {
                is MdBlock.Heading -> BasicText(inline(b.text, c.raised), style = HerdrType.body.copy(color = c.fg, fontWeight = FontWeight.SemiBold))
                is MdBlock.Para -> BasicText(inline(b.text, c.raised), style = HerdrType.body.copy(color = c.fg))
                is MdBlock.Item -> Row(Modifier.padding(start = (12 * b.depth).dp)) {
                    BasicText(b.marker, style = HerdrType.body.copy(color = c.fg), modifier = Modifier.widthIn(min = 18.dp).padding(end = 4.dp))
                    BasicText(inline(b.text, c.raised), style = HerdrType.body.copy(color = c.fg))
                }
                is MdBlock.Code -> CodeBlock(b.text)
                is MdBlock.Table -> MdTable(b)
            }
        }
    }
}

/** Tool calls in a row, folded: "Ran 3 commands ›", or the one call's title. */
fun toolLine(calls: List<ChatCall>): String {
    val running = calls.any { !it.done }
    val failed = calls.count { it.failed }
    val base = if (calls.size == 1) calls[0].title else {
        val shells = calls.all { it.name == "Bash" || it.name == "Shell" }
        if (shells) "Ran ${calls.size} commands" else "Used ${calls.size} tools"
    }
    return base + (if (failed > 0) " · $failed failed" else "") + (if (running) " · running" else "")
}

@Composable
private fun ToolLine(calls: List<ChatCall>, onOpen: () -> Unit) {
    val c = HerdrTheme.colors
    Row(Modifier.clip(RoundedCornerShape(HerdrRadius.row)).clickable(onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(toolLine(calls), style = HerdrType.settingRow.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        ChevronIcon(Chevron.Right, 16.dp, c.dimText)
    }
}

@Composable
private fun Note(text: String) {
    val c = HerdrTheme.colors
    BasicText(text, style = HerdrType.secondary.copy(color = c.dimText, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MessageBox(state: ChatSpikeState, canSend: Boolean, onPick: () -> Unit, onSend: (String) -> Unit) {
    val c = HerdrTheme.colors
    if (state.attached.isNotEmpty()) {
        Box(Modifier.padding(top = 8.dp)) { AttachedRow(state.attached) { a -> state.attached = state.attached - a } }
    }
    Row(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(c.raised).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onPick), contentAlignment = Alignment.Center) {
            BasicText("+", style = HerdrType.terminalTitle.copy(color = c.fg))
        }
        Box(Modifier.weight(1f).padding(start = 6.dp, top = 8.dp, bottom = 8.dp)) {
            if (state.draft.isEmpty()) {
                BasicText(if (canSend) "Message" else "The agent can't take a message now", style = HerdrType.body.copy(color = c.dimText))
            }
            BasicTextField(
                state.draft, { state.draft = it },
                textStyle = HerdrType.body.copy(color = c.fg),
                cursorBrush = SolidColor(c.fg),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val ready = canSend && (state.draft.isNotBlank() || state.attached.isNotEmpty())
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (ready) c.solid else c.hover)
                .clickable(enabled = ready) { onSend(state.draft) },
            contentAlignment = Alignment.Center,
        ) {
            BasicText("↑", style = HerdrType.terminalTitle.copy(color = if (ready) c.solidFg else c.dimText))
        }
    }
}

@Composable
private fun ToolSheet(calls: List<ChatCall>, state: ChatSpikeState, images: ChatImageSource) {
    val c = HerdrTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.6f)).clickable { state.sheet = null; state.detail = null }, contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(topStart = HerdrRadius.sheetTop, topEnd = HerdrRadius.sheetTop))
                .background(c.popover).clickable(enabled = false) {}.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val d = state.detail
            if (d == null) {
                BasicText(toolLine(calls), style = HerdrType.sheetTitle.copy(color = c.fg))
                for (call in calls) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(HerdrRadius.row)).clickable { state.detail = call }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BasicText(call.name, style = HerdrType.settingRow.copy(color = if (call.failed) c.blocked else c.fg))
                        BasicText(call.title, style = HerdrType.settingRow.copy(color = c.dimText), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable { state.detail = null }, contentAlignment = Alignment.Center) { ChevronIcon(Chevron.Left, 18.dp, c.fg) }
                    BasicText(d.name + if (!d.done) " · running" else if (d.failed) " · failed" else "", style = HerdrType.sheetTitle.copy(color = c.fg))
                }
                BasicText(d.title, style = HerdrType.secondary.copy(color = c.dimText))
                CodeBox(d.input)
                if (d.images.isNotEmpty()) Thumbs(d.images, images, end = false) { state.viewer = it }
                if (d.output.isNotEmpty()) CodeBox(d.output.take(4000) + if (d.output.length > 4000) "\n…" else "")
            }
        }
    }
}

/** A code block: wraps, with a copy button. */
@Composable
private fun CodeBlock(text: String) {
    val c = HerdrTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(HerdrRadius.row)).background(c.canvas)) {
        BasicText(text, style = HerdrType.cardMsg.copy(color = c.termFg), modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 40.dp))
        CopyIcon(Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(HerdrRadius.chip)).clickable {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }.padding(10.dp))
    }
}

/** A Markdown table: columns sized to their text, scrolled sideways when wide. */
@Composable
private fun MdTable(t: MdBlock.Table) {
    val c = HerdrTheme.colors
    val n = t.header.size
    val widths = (0 until n).map { col ->
        val longest = (listOf(t.header) + t.rows).maxOf { it.getOrNull(col)?.length ?: 0 }
        (longest * 9 + 28).coerceIn(56, 220).dp
    }
    Column(
        Modifier.horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(HerdrRadius.row))
            .border(1.dp, c.border, RoundedCornerShape(HerdrRadius.row)),
    ) {
        for ((r, cellsRow) in (listOf(t.header) + t.rows).withIndex()) {
            Row(Modifier.background(if (r == 0) c.raised else c.bg)) {
                for (col in 0 until n) {
                    BasicText(
                        inline(cellsRow.getOrElse(col) { "" }, c.raised),
                        style = HerdrType.secondary.copy(color = c.fg, fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal),
                        modifier = Modifier.width(widths[col]).padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBox(text: String) {
    val c = HerdrTheme.colors
    BasicText(
        text, style = HerdrType.cardMsg.copy(color = c.termFg),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(HerdrRadius.row)).background(c.canvas).padding(10.dp),
    )
}
