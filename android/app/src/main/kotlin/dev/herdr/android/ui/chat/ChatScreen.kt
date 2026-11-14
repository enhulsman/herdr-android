package dev.herdr.android.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdr.android.ui.components.Chevron
import dev.herdr.android.ui.components.ChevronIcon
import dev.herdr.android.ui.terminal.LineIcon
import dev.herdr.android.ui.terminal.LineIconImage
import dev.herdr.android.ui.terminal.TerminalActions
import dev.herdr.android.ui.terminal.TerminalHeader
import dev.herdr.android.ui.terminal.TitleBar
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatFound
import uniffi.herdr_mobile.ChatHealth
import uniffi.herdr_mobile.ChatPoll
import uniffi.herdr_mobile.ChatRow

// The chat view over the terminal (spec "The chat shows the conversation as
// the agents' apps do" and the requirements after it). [now] is uptime ms,
// ticked by the route while something waits, fixed in screenshot tests;
// [animate] false (reduced motion, screenshots) holds the working line still.

const val LOOKING = "Looking for the conversation…"
const val NO_CONVERSATION = "No conversation found for this agent"
const val NO_MESSAGES = "No messages yet."
const val DEGRADED = "Some parts of this conversation aren't shown"
const val BROKEN = "This conversation can't be read any more"
const val EXITED = "The agent has exited"
const val NOT_ARRIVED = "Not in the conversation yet"
const val SHOW_TERMINAL = "Show the terminal"

/**
 * Takes every touch in its bounds, so none reaches what is drawn below. The
 * chat is drawn over the live terminal: without this a tap on an empty spot
 * reached the terminal's keys (it once pressed up).
 */
internal fun Modifier.coversBelow(): Modifier =
    pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

@Composable
fun ChatScreen(
    header: TerminalHeader,
    actions: TerminalActions,
    poll: ChatPoll?,
    state: ChatState,
    images: ChatImageSource,
    now: Long,
    onTerminal: () -> Unit,
    onPick: () -> Unit,
    onSend: () -> Unit,
    onOlder: () -> Unit = {},
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = HerdrTheme.colors
    // An exited agent's conversation stays, read-only (its lookup reads None).
    val found = poll != null && (poll.found == ChatFound.FOUND || poll.found == ChatFound.EMPTY || poll.exited)
    val broken = poll?.health is ChatHealth.Broken
    Box(modifier.fillMaxSize().background(c.bg).coversBelow()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            TitleBar(header, actions) {
                if (poll != null) Gauge(poll, state)
                ViewSwitch("Terminal", onTerminal)
            }
            if (state.gaugeOpen && poll != null) gaugeDetail(poll.gaugeUsed, poll.gaugeWindow)?.let { Strip(it) }
            if (found && poll?.health == ChatHealth.Degraded) Strip(DEGRADED)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    poll == null -> Centered(LOOKING)
                    !found -> Centered(NO_CONVERSATION, onTerminal)
                    else -> Messages(poll, state, images, now, animate, onOlder, onTerminal)
                }
            }
            when {
                !found -> Unit
                broken -> Bottom(BROKEN, onTerminal)
                poll?.exited == true -> Bottom(EXITED, null)
                else -> {
                    state.refusal?.let { r -> Bottom(r.text, if (r.terminal) onTerminal else null) }
                    MessageBox(state, onPick, onSend)
                }
            }
        }
        val rows = poll?.rows.orEmpty()
        sheetCalls(rows, state.sheet)?.let { calls -> ToolSheet(calls, state, images) }
        state.viewer?.let { v -> ImageViewer(v.image, images) { state.viewer = null } }
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

/** How full the session's context is; a tap shows the tokens. */
@Composable
private fun Gauge(poll: ChatPoll, state: ChatState) {
    val c = HerdrTheme.colors
    val label = gaugeLabel(poll.gaugeUsed, poll.gaugeWindow, poll.gaugePercent) ?: return
    BasicText(
        label,
        style = HerdrType.secondary.copy(color = c.dimText),
        modifier = Modifier.clip(RoundedCornerShape(HerdrRadius.chip)).clickable { state.gaugeOpen = !state.gaugeOpen }
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

/** A line under the title bar. */
@Composable
private fun Strip(text: String) {
    val c = HerdrTheme.colors
    BasicText(
        text,
        style = HerdrType.secondary.copy(color = c.dimText, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().background(c.chrome).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun Centered(text: String, onTerminal: (() -> Unit)? = null) {
    val c = HerdrTheme.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(text, style = HerdrType.body.copy(color = c.dimText, textAlign = TextAlign.Center))
        if (onTerminal != null) ViewSwitch(SHOW_TERMINAL, onTerminal)
    }
}

/** A line above the message box, or in its place: why, and the way to the terminal where it helps. */
@Composable
private fun Bottom(text: String, onTerminal: (() -> Unit)?) {
    val c = HerdrTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(text, style = HerdrType.secondary.copy(color = c.fg), modifier = Modifier.weight(1f))
        if (onTerminal != null) ViewSwitch(SHOW_TERMINAL, onTerminal)
    }
}

@Composable
private fun Messages(poll: ChatPoll, state: ChatState, images: ChatImageSource, now: Long, animate: Boolean, onOlder: () -> Unit, onTerminal: () -> Unit) {
    val rows = poll.rows
    val pending = state.pending
    if (rows.isEmpty() && pending.isEmpty()) {
        // The recent part may hold no message (one huge record): read further back.
        LaunchedEffect(poll.cut, state.olderLoads) { if (poll.cut && !state.loadingOlder) onOlder() }
        return Centered(if (poll.cut) "Loading earlier messages…" else NO_MESSAGES)
    }
    // Back from the terminal view or another agent: the place it was left at (design D12).
    val start = remember { state.startAt(rows) }
    val list = remember { start?.let { LazyListState(it.first, it.second) } ?: LazyListState() }
    val shownRows by rememberUpdatedState(rows)
    DisposableEffect(list) {
        onDispose { state.keep(shownRows, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
    }
    // Follow the bottom unless the person scrolled up; back at the bottom, follow again.
    // Only their own drag and the fling after it count: the app's scroll to the end
    // passes a moment with more below, which turned following off mid-turn.
    val dragged = list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(list) {
        var byPerson = false
        snapshotFlow { Triple(dragged.value, list.isScrollInProgress, list.canScrollForward) }.collect { (drag, moving, more) ->
            if (drag) byPerson = true
            state.stick = nextStick(state.stick, byPerson, more)
            if (!drag && !moving) byPerson = false
        }
    }
    // The keyboard opening shrinks the list: keep the bottom in view.
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.viewportSize.height }.collect { if (state.stick) list.toEnd() }
    }
    // A row growing at the bottom (the waiting line, an answer's next block) pushes
    // the end out of view with no new row: follow it unless the person is scrolling.
    LaunchedEffect(list) {
        snapshotFlow { list.canScrollForward }.collect { more -> if (more && state.stick && !list.isScrollInProgress) list.toEnd() }
    }
    val live = !poll.exited && poll.health !is ChatHealth.Broken
    val working = live && busy(poll.status, pending, now)
    var settled by remember { mutableStateOf(start != null) }
    LaunchedEffect(rows.lastOrNull()?.key, pending.size) {
        if (state.stick || !settled) list.toEnd()
        settled = true
    }
    // Opening a tool's detail or an image puts it in front of everything: the keyboard closes first.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun front(open: () -> Unit) {
        keyboard?.hide()
        focus.clearFocus()
        open()
    }
    // The top row in view reads the part before it, back to the start.
    // Not "index 0 in view": a row with nothing to show has no height and never counts as visible.
    val atTop by remember { derivedStateOf { !list.canScrollBackward } }
    // Keyed on the loads as well: while the top stays in view (a short list), each load asks for the next.
    LaunchedEffect(atTop, poll.cut, settled, state.olderLoads) { if (atTop && poll.cut && settled && !state.loadingOlder) onOlder() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row.kind) {
                    "user" -> UserBubble(row, images) { key -> front { state.viewer = Viewer(row.key, key) } }
                    "said" -> Copyable(row.text) { Said(row.text) }
                    "agent" -> AgentCard(row)
                    "tools" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolLine(row.calls, live = shimmers(row.calls, working), animate) { front { state.openTools(row) } }
                        val shots = row.calls.flatMap { it.images }
                        if (shots.isNotEmpty()) Thumbs(shots, images, end = false) { key -> front { state.viewer = Viewer(row.key, key) } }
                    }
                    else -> Note(row.text)
                }
            }
            items(pending, key = { "pending ${it.id}" }) { p ->
                PendingBubble(p, now, onTerminal, onDismiss = { state.drop(p.id) }, onDismissWait = { state.update(p.id) { it.dismissWait() } })
            }
            // For the whole turn, thinking or running a command alike, until the turn ends.
            if (working) item(key = "working") { WorkingLine(now, animate) }
        }
        if (poll.problem.isNotEmpty()) Chip(poll.problem, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        if (state.loadingOlder) Chip("Loading earlier messages…", Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier) {
    val c = HerdrTheme.colors
    BasicText(
        text,
        style = HerdrType.secondary.copy(color = c.fg),
        modifier = modifier.clip(RoundedCornerShape(HerdrRadius.chip)).background(c.raised).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The very end: the last item's bottom, also when it is taller than the list (a long answer). */
private suspend fun LazyListState.toEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    // scrollToItem shows an item's top; the rest of a tall one stays below. Scrolling stops at the end.
    scrollBy(1_000_000f)
}

private val BUBBLE = RoundedCornerShape(18.dp)
private val BUBBLE_MAX: Dp = 320.dp

/** A message on its way: dimmed until the log holds it, with what it waits on. */
@Composable
private fun PendingBubble(p: Pending, now: Long, onTerminal: () -> Unit, onDismiss: () -> Unit, onDismissWait: () -> Unit) {
    val c = HerdrTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val text = listOfNotNull(
            p.text.trim().takeIf { it.isNotEmpty() },
            if (p.images == 1) "1 image" else if (p.images > 1) "${p.images} images" else null,
        ).joinToString("\n")
        BasicText(
            text,
            style = HerdrType.body.copy(color = c.dimText),
            modifier = Modifier.widthIn(max = BUBBLE_MAX).clip(BUBBLE).background(c.raised).padding(horizontal = 14.dp, vertical = 10.dp),
        )
        when (p.state(now)) {
            PendingState.Sending -> Status("Sending…")
            PendingState.Queued -> Status("Queued")
            PendingState.NotArrived -> {
                Status(NOT_ARRIVED)
                Actions(onTerminal, onDismiss)
            }
        }
        if (p.waiting(now)) {
            BasicText(p.waitingText, style = HerdrType.secondary.copy(color = c.dimText, textAlign = TextAlign.End), modifier = Modifier.widthIn(max = BUBBLE_MAX))
            Actions(onTerminal, onDismissWait)
        }
    }
}

@Composable
private fun Status(text: String) {
    BasicText(text, style = HerdrType.secondary.copy(color = HerdrTheme.colors.dimText))
}

@Composable
private fun Actions(onTerminal: () -> Unit, onDismiss: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ViewSwitch("Dismiss", onDismiss)
        ViewSwitch(SHOW_TERMINAL, onTerminal)
    }
}

@Composable
private fun UserBubble(row: ChatRow, images: ChatImageSource, onOpen: (String) -> Unit) {
    val c = HerdrTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (row.images.isNotEmpty()) Thumbs(row.images, images, end = true, onOpen)
        if (row.text.isNotEmpty()) {
            Copyable(row.text, end = true) {
                BasicText(
                    row.text,
                    style = HerdrType.body.copy(color = c.fg),
                    modifier = Modifier.widthIn(max = BUBBLE_MAX).clip(BUBBLE).background(c.raised).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** The message's text is selectable as usual; a small Copy under it copies all of it. */
@Composable
private fun Copyable(text: String, end: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        SelectionContainer { content() }
        CopyButton(text, Modifier.padding(8.dp))
    }
}

@Composable
private fun CopyButton(text: String, padding: Modifier, tint: Color = HerdrTheme.colors.dimText, size: Dp = 16.dp) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    CopyIcon(
        Modifier.clip(RoundedCornerShape(HerdrRadius.chip)).clickable {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }.then(padding),
        tint, size,
    )
}

/** Lucide's copy icon, as the app's other line icons. */
@Composable
internal fun CopyIcon(modifier: Modifier = Modifier, tint: Color = HerdrTheme.colors.dimText, size: Dp = 16.dp) {
    LineIconImage(LineIcon.Copy, tint, size, modifier.semantics { contentDescription = "Copy" })
}

/** A subagent's report or another session's message, folded after a few blocks. */
@Composable
private fun AgentCard(row: ChatRow) {
    val c = HerdrTheme.colors
    var open by remember(row.key) { mutableStateOf(false) }
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
                is MdBlock.Heading -> MdText(inline(b.text), HerdrType.body.copy(color = c.fg, fontWeight = FontWeight.SemiBold))
                is MdBlock.Para -> MdText(inline(b.text), HerdrType.body.copy(color = c.fg))
                is MdBlock.Item -> Row(Modifier.padding(start = (12 * b.depth).dp)) {
                    BasicText(b.marker, style = HerdrType.body.copy(color = c.fg), modifier = Modifier.widthIn(min = 18.dp).padding(end = 4.dp))
                    MdText(inline(b.text), HerdrType.body.copy(color = c.fg))
                }
                is MdBlock.Code -> CodeBlock(b.text)
                is MdBlock.Table -> MdTable(b)
            }
        }
    }
}

/**
 * Text with inline code: each [CODE_TAG] range gets its box drawn behind the
 * text, line by line, so a selection highlight still shows on top of it.
 */
@Composable
private fun MdText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    val box = HerdrTheme.colors.raised
    val codes = remember(text) { text.getStringAnnotations(CODE_TAG, 0, text.length) }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text, style = style, onTextLayout = { layout = it },
        modifier = if (codes.isEmpty()) modifier else modifier.drawBehind {
            val l = layout ?: return@drawBehind
            val pad = 2.dp.toPx()
            val r = CornerRadius(4.dp.toPx())
            for (b in codeBoxes(l, codes)) drawRoundRect(box, Offset(b.left - pad, b.top), Size(b.width + 2 * pad, b.height), r)
        },
    )
}

@Composable
private fun ToolLine(calls: List<ChatCall>, live: Boolean, animate: Boolean, onOpen: () -> Unit) {
    val c = HerdrTheme.colors
    Row(Modifier.clip(RoundedCornerShape(HerdrRadius.row)).clickable(onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // While it runs, a light sweeps over it, as the desk's agent shows a running command.
        BasicText(
            toolLine(calls),
            style = HerdrType.settingRow.copy(color = c.dimText),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).shimmer(live, c.fg, animate),
        )
        ChevronIcon(Chevron.Right, 16.dp, c.dimText)
    }
}

@Composable
private fun Note(text: String) {
    BasicText(text, style = HerdrType.secondary.copy(color = HerdrTheme.colors.dimText, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MessageBox(state: ChatState, onPick: () -> Unit, onSend: () -> Unit) {
    val c = HerdrTheme.colors
    if (state.attached.isNotEmpty()) {
        Box(Modifier.padding(top = 8.dp)) { AttachedRow(state.attached) { a -> state.attached = state.attached - a } }
    }
    Row(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(c.raised).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onPick).semantics { contentDescription = "Attach photos" }, contentAlignment = Alignment.Center) {
            BasicText("+", style = HerdrType.terminalTitle.copy(color = c.fg))
        }
        Box(Modifier.weight(1f).padding(start = 6.dp, top = 8.dp, bottom = 8.dp)) {
            if (state.draft.isEmpty()) BasicText("Message", style = HerdrType.body.copy(color = c.dimText))
            BasicTextField(
                state.draft, { state.draft = it },
                textStyle = HerdrType.body.copy(color = c.fg),
                cursorBrush = SolidColor(c.fg),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val ready = state.draft.isNotBlank() || state.attached.isNotEmpty()
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (ready) c.solid else c.hover)
                .clickable(enabled = ready, onClick = onSend).semantics { contentDescription = "Send" },
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
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(HerdrRadius.row)).background(c.canvas)) {
        BasicText(text, style = HerdrType.cardMsg.copy(color = c.termFg), modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 40.dp))
        Box(Modifier.align(Alignment.TopEnd)) { CopyButton(text, Modifier.padding(10.dp)) }
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
                    MdText(
                        inline(cellsRow.getOrElse(col) { "" }),
                        HerdrType.secondary.copy(color = c.fg, fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal),
                        modifier = Modifier.width(widths[col]).padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
