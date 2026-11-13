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
import androidx.compose.ui.input.pointer.pointerInput
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
import dev.herdr.android.ui.terminal.TerminalActions
import dev.herdr.android.ui.terminal.TerminalHeader
import dev.herdr.android.ui.terminal.TitleBar
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
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

/**
 * A message sent from the phone that the log does not hold yet; [at] is where
 * its row will be, [sentAt] when it went out (uptime ms).
 */
data class Pending(val text: String, val images: Int, val at: Int, val sentAt: Long = 0) {
    /**
     * The log's row for this message: the same text, or images and no text for
     * a photo sent alone. `!` and `/` commands come back as the person's row too.
     */
    fun matches(row: ChatRow): Boolean = when {
        row.kind != "user" -> false
        text.isBlank() -> row.text.isBlank() && row.images.isNotEmpty()
        else -> row.text.trim() == text.trim()
    }

    /**
     * A slash command that shows nothing after [WAIT_MS] most likely opened a
     * dialog in the terminal (Claude Code logs `/usage` only once it closes).
     */
    fun waiting(now: Long): Boolean = text.trimStart().startsWith("/") && now - sentAt >= WAIT_MS

    val waitingText: String
        get() = "Waiting for the terminal: ${text.trim().substringBefore(' ')} may have opened a dialog there."

    companion object {
        const val WAIT_MS = 3_000L
    }
}

class ChatSpikeState {
    var draft by mutableStateOf("")
    var sheet by mutableStateOf<List<ChatCall>?>(null)
    var detail by mutableStateOf<ChatCall?>(null)
    /** The image shown full screen, by key. */
    var viewer by mutableStateOf<String?>(null)
    var attached by mutableStateOf<List<Attachment>>(emptyList())
    var pending by mutableStateOf<List<Pending>>(emptyList())
    /** Rows put in front by reading back: row i has the stable position i - base. */
    var base by mutableStateOf(0)
    /** Follow new rows at the bottom; off once the person scrolls up. */
    var stick by mutableStateOf(true)
    var loadingOlder by mutableStateOf(false)
    /** Read-backs finished, so a top still in view asks for the next part. */
    var olderLoads by mutableStateOf(0)

    /** A tap on a folded line: one call opens straight to its detail. */
    fun openTools(calls: List<ChatCall>) {
        sheet = calls
        detail = calls.singleOrNull()
    }

    /** Back in the sheet: to the list, or closed when there is only one call. */
    fun closeDetail() {
        if (sheet?.size == 1) sheet = null
        detail = null
    }

    /** A message went out while the log held [rows] rows. */
    fun sent(text: String, images: Int, rows: Int, now: Long = android.os.SystemClock.uptimeMillis()) {
        pending = pending + Pending(text, images, rows - base, now)
        stick = true
    }

    /**
     * Drops the pending messages whose user row has arrived, oldest first;
     * each row confirms one message with its text, and those still waiting
     * look past it. A message typed at the desk meanwhile confirms nothing.
     */
    fun confirm(rows: List<ChatRow>) {
        if (pending.isEmpty()) return
        val users = rows.withIndex().filter { it.value.kind == "user" }.map { (it.index - base) to it.value }
        var after = Int.MIN_VALUE
        val left = mutableListOf<Pending>()
        // Fewer rows than when it was sent: a new log (`/clear`), its row is near the start.
        val count = rows.size - base
        for (p in pending.map { if (it.at > count) it.copy(at = 0) else it }) {
            val from = maxOf(p.at, after)
            val hit = if (left.isEmpty()) users.firstOrNull { (at, row) -> at >= from && p.matches(row) }?.first else null
            if (hit != null) after = hit + 1 else left += if (after > p.at) p.copy(at = after) else p
        }
        if (left != pending) pending = left
    }
}

/**
 * Takes every touch in its bounds, so none reaches what is drawn below. The
 * chat is drawn over the live terminal: without this a tap on an empty spot
 * reached the terminal's keys (it once pressed up).
 */
internal fun Modifier.coversBelow(): Modifier =
    pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

@Composable
fun ChatSpikeScreen(
    header: TerminalHeader,
    actions: TerminalActions,
    poll: ChatPoll?,
    state: ChatSpikeState,
    images: ChatImageSource,
    onTerminal: () -> Unit,
    onPick: () -> Unit,
    onSend: (String) -> Unit,
    onOlder: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = HerdrTheme.colors
    Box(modifier.fillMaxSize().background(c.bg).coversBelow()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            TitleBar(header, actions, "Terminal", onTerminal)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    poll == null -> Centered("Reading the session…")
                    poll.state != "ready" -> Fallback(poll.reason, onTerminal)
                    else -> Messages(poll, state, images, onOlder, onTerminal)
                }
            }
            if (poll?.state == "ready") MessageBox(state, poll.canSend, onPick, onSend)
        }
        state.sheet?.let { calls -> ToolSheet(calls, state, images) }
        state.viewer?.let { key -> ImageViewer(key, images) { state.viewer = null } }
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
private fun Messages(poll: ChatPoll, state: ChatSpikeState, images: ChatImageSource, onOlder: () -> Unit, onTerminal: () -> Unit) {
    val list = rememberLazyListState()
    val rows = poll.rows
    val pending = state.pending
    if (rows.isEmpty() && pending.isEmpty()) {
        // The recent part may hold no message (one huge record): read further back.
        LaunchedEffect(poll.cut, state.olderLoads) { if (poll.cut && !state.loadingOlder) onOlder() }
        return Centered(if (poll.cut) "Loading earlier messages…" else "No messages yet.")
    }
    // Follow the bottom unless the person scrolled up; back at the bottom, follow again.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (moving, more) -> if (moving) state.stick = !more }
    }
    // The keyboard opening shrinks the list: keep the bottom in view.
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.viewportSize.height }.collect {
            val last = list.layoutInfo.totalItemsCount - 1
            if (state.stick && last >= 0) list.scrollToItem(last)
        }
    }
    val end = rows.size - state.base + pending.size
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(end) {
        if (state.stick || !settled) list.scrollToItem(rows.size + pending.size - 1)
        settled = true
    }
    // The top row in view reads the part before it, back to the start.
    // Not "index 0 in view": a row with nothing to show has no height and never counts as visible.
    val atTop by remember { derivedStateOf { !list.canScrollBackward } }
    // Keyed on the loads as well: while the top stays in view (a short list), each load asks for the next.
    LaunchedEffect(atTop, poll.cut, settled, state.olderLoads) { if (atTop && poll.cut && settled && !state.loadingOlder) onOlder() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(rows, key = { i, _ -> i - state.base }) { _, row ->
                when (row.kind) {
                    "user" -> UserBubble(row, images) { state.viewer = it }
                    "said" -> Copyable(row.text) { Said(row.text) }
                    "agent" -> AgentCard(row)
                    "tools" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolLine(row.calls) { state.openTools(row.calls) }
                        val shots = row.calls.flatMap { it.images }
                        if (shots.isNotEmpty()) Thumbs(shots, images, end = false) { state.viewer = it }
                    }
                    else -> Note(row.text)
                }
            }
            itemsIndexed(pending, key = { i, p -> "pending ${p.at} $i" }) { _, p -> PendingBubble(p, onTerminal) }
        }
        if (poll.reason.isNotEmpty()) {
            BasicText(
                poll.reason,
                style = HerdrType.secondary.copy(color = HerdrTheme.colors.fg),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(RoundedCornerShape(HerdrRadius.chip))
                    .background(HerdrTheme.colors.raised).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (state.loadingOlder) {
            BasicText(
                "Loading earlier messages…",
                style = HerdrType.secondary.copy(color = HerdrTheme.colors.fg),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(HerdrRadius.chip))
                    .background(HerdrTheme.colors.raised).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** A message on its way: dimmed until the log holds it. */
@Composable
private fun PendingBubble(p: Pending, onTerminal: () -> Unit) {
    val c = HerdrTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val text = listOfNotNull(
            p.text.takeIf { it.isNotBlank() },
            if (p.images == 1) "1 image" else if (p.images > 1) "${p.images} images" else null,
        ).joinToString("\n")
        BasicText(
            text, style = HerdrType.body.copy(color = c.dimText),
            modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp)).background(c.raised).padding(horizontal = 14.dp, vertical = 10.dp),
        )
        var now by remember(p) { mutableStateOf(android.os.SystemClock.uptimeMillis()) }
        LaunchedEffect(p) {
            while (!p.waiting(now)) {
                kotlinx.coroutines.delay(250)
                now = android.os.SystemClock.uptimeMillis()
            }
        }
        if (p.waiting(now)) {
            BasicText(p.waitingText, style = HerdrType.secondary.copy(color = c.dimText, textAlign = androidx.compose.ui.text.style.TextAlign.End), modifier = Modifier.widthIn(max = 320.dp))
            ViewSwitch("Show the terminal", onTerminal)
        } else {
            BasicText("Sending…", style = HerdrType.secondary.copy(color = c.dimText))
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
internal fun CopyIcon(modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = HerdrTheme.colors.dimText, size: androidx.compose.ui.unit.Dp = 16.dp) {
    LineIconImage(LineIcon.Copy, tint, size, modifier.semantics { contentDescription = "Copy" })
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

/** Tool calls in a row, folded: "Ran 7 commands, read 2 files", or the one call's title. */
fun toolLine(calls: List<ChatCall>): String {
    val running = calls.any { !it.done }
    val failed = calls.count { it.failed }
    val base = if (calls.size == 1) calls[0].title else toolKinds(calls)
    return base + (if (failed > 0) " · $failed failed" else "") + (if (running) " · running" else "")
}

internal enum class ToolKind(val one: String, val many: String) {
    Command("ran 1 command", "ran %d commands"),
    Search("searched for 1 pattern", "searched for %d patterns"),
    Read("read 1 file", "read %d files"),
    Edit("edited 1 file", "edited %d files"),
    Image("viewed 1 image", "viewed %d images"),
    Agent("started 1 agent", "started %d agents"),
    Other("used 1 tool", "used %d tools"),
}

internal fun kindOf(name: String) = when (name) {
    "Bash", "Shell" -> ToolKind.Command
    "Grep", "Glob" -> ToolKind.Search
    "Read" -> ToolKind.Read
    "Edit", "Write", "MultiEdit", "NotebookEdit" -> ToolKind.Edit
    "View image" -> ToolKind.Image
    "Agent", "Task" -> ToolKind.Agent
    else -> ToolKind.Other
}

/** "Ran 7 commands, read 2 files, started 1 agent": each kind once, in [ToolKind] order. */
private fun toolKinds(calls: List<ChatCall>): String {
    val counts = calls.groupingBy { kindOf(it.name) }.eachCount()
    val parts = ToolKind.entries.mapNotNull { k -> counts[k]?.let { n -> if (n == 1) k.one else k.many.format(n) } }
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
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
