package dev.herdr.android.ui.terminal

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.R
import dev.herdr.android.ui.agents.AgentKey
import dev.herdr.android.ui.agents.ProgramIcon
import dev.herdr.android.ui.agents.ProgramMark
import dev.herdr.android.ui.agents.SheetTone
import dev.herdr.android.ui.agents.StatePill
import dev.herdr.android.ui.components.Chevron
import dev.herdr.android.ui.components.ChevronIcon
import dev.herdr.android.ui.theme.HerdrBorder
import dev.herdr.android.ui.theme.HerdrChipShadow
import dev.herdr.android.ui.theme.HerdrLayout
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import uniffi.herdr_mobile.RowKeyTap
import uniffi.herdr_mobile.TermPhase
import kotlin.math.abs
import kotlin.math.floor

// The terminal screen (renders 3, 4, 4b, 5; terminal-view and terminal-input
// specs): the title bar, the queue strip, the grid watched at desk size or
// typed into at phone size, the status chip, the two keys rows, the
// compose sheet and the selection layer. Sizes from design-tokens.json
// (components.terminalTitleBar, queueStrip, watchingChip, sizeTag, keysRow,
// composeSheet; layout.terminalPadding, terminalFit).

/** The title bar's agent: name, mark, "folder · machine" and its state pill (none without status). */
data class TerminalHeader(
    val name: String,
    val icon: ProgramIcon,
    val lineTwo: String,
    val state: String?,
    val tone: SheetTone,
)

data class TerminalUi(val key: AgentKey, val header: TerminalHeader, val queue: QueueStrip?)

class TerminalActions(
    val onBack: () -> Unit,
    /** Opens another agent (the queue strip). */
    val onOpen: (AgentKey) -> Unit,
    /** A vertical swipe on the title bar; false when the queue has ended. */
    val onSwipe: (forward: Boolean) -> Boolean,
    /** The phone's own grid in the current layout, on every layout. */
    val onPhoneGrid: (GridSize) -> Unit,
    /** The person zoomed or panned while watching. */
    val onWatchView: (WatchView) -> Unit = {},
)

/** What the screen itself remembers while it is open. */
class TerminalScreenState(zoom: Float? = null) {
    /** The keyboard was asked for: the keys row shows even before the first key. */
    var inputOpen by mutableStateOf(false)
    var composeOpen by mutableStateOf(false)
    var composeText by mutableStateOf("")
    var selecting by mutableStateOf(false)
    var watch by mutableStateOf(WatchView(zoom ?: 1f, 0f, 0f))

    /** The phone leads at its own size; kept through a reattach until the core says it watches again. */
    var typing by mutableStateOf(false)

    fun follow(phase: TermPhase?) {
        when (phase) {
            TermPhase.Typing -> typing = true
            TermPhase.Watching -> typing = false
            else -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    ui: TerminalUi,
    session: TerminalSession,
    screen: TerminalScreenState,
    actions: TerminalActions,
    input: TerminalInputHandle,
    modifier: Modifier = Modifier,
    /** The compose field takes focus (and the keyboard) when the sheet opens; off for screenshots. */
    composeFocus: Boolean = true,
) {
    val c = HerdrTheme.colors
    val phase = session.state?.phase
    LaunchedEffect(phase) { screen.follow(phase) }
    val keysShown = screen.inputOpen || screen.typing
    val keyboardUp = WindowInsets.isImeVisible
    Box(modifier.fillMaxSize().background(c.chrome)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TitleBar(ui.header, actions)
            ui.queue?.let { QueueStripRow(it) { actions.onOpen(it.next) } }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                TerminalArea(session, screen, actions, input, keysShown, ui.key.machine)
            }
            if (keysShown) {
                KeysRow(
                    armed = session.armed,
                    onTap = session::tap,
                    onInterrupt = session::interrupt,
                    onCompose = {
                        input.hide()
                        screen.composeOpen = true
                    },
                    onKeyboard = {
                        if (keyboardUp) {
                            input.hide()
                        } else {
                            screen.inputOpen = true
                            input.show()
                        }
                    },
                )
            }
        }
        if (screen.composeOpen) {
            ComposeSheet(
                agent = ui.header.name,
                text = screen.composeText,
                onText = { screen.composeText = it },
                onCancel = {
                    screen.composeOpen = false
                    screen.composeText = ""
                },
                onPaste = {
                    if (screen.composeText.isNotEmpty()) session.paste(screen.composeText)
                    screen.composeOpen = false
                    screen.composeText = ""
                },
                focus = composeFocus,
            )
        }
    }
}

/** Shows and hides the terminal keyboard (TerminalInputView); a no-op in previews. */
interface TerminalInputHandle {
    fun show()
    fun hide()

    object None : TerminalInputHandle {
        override fun show() = Unit
        override fun hide() = Unit
    }
}

// --- Title bar and queue strip ---------------------------------------------

@Composable
private fun TitleBar(header: TerminalHeader, actions: TerminalActions) {
    val c = HerdrTheme.colors
    val view = LocalView.current
    val threshold = with(LocalDensity.current) { SWIPE_STEP.toPx() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(HerdrLayout.terminalTitleHeight)
            .background(c.chrome)
            .pointerInput(actions) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        if (abs(travelled) >= threshold) {
                            // Up is forward: the next agent comes up from below.
                            val moved = actions.onSwipe(travelled < 0)
                            view.performHapticFeedback(
                                if (moved) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.REJECT,
                            )
                        }
                    },
                ) { change, dy ->
                    change.consume()
                    travelled += dy
                }
            }
            .padding(start = HerdrSpacing.s4, end = HerdrSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val back = stringResource(R.string.back)
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(HerdrRadius.button))
                .clickable(role = Role.Button, onClick = actions.onBack)
                .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            ChevronIcon(Chevron.Left, 22.dp, c.fg)
        }
        ProgramMark(header.icon, Modifier.padding(end = 6.dp), size = 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(header.name, style = HerdrType.terminalTitle.copy(color = c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(header.lineTwo, style = HerdrType.secondary.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (header.state != null && header.tone != SheetTone.None) StatePill(header.state, header.tone)
    }
}

/** `.queue`: 34 dp, the blocked edge, "N more needs you · next: <agent>, <kind>"; a tap opens that agent. */
@Composable
private fun QueueStripRow(strip: QueueStrip, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    val label = stringResource(R.string.terminal_queue_open)
    val rest = strip.nextKind?.let { stringResource(R.string.terminal_queue_next, strip.nextName, it.lowercase()) }
        ?: stringResource(R.string.terminal_queue_next_plain, strip.nextName)
    Row(
        Modifier
            .fillMaxWidth()
            .height(HerdrLayout.queueStripHeight)
            .drawBehind {
                drawRect(blockedMix(c.blocked, c.chrome))
                drawRect(c.border, size = size.copy(height = HerdrBorder.thin.toPx()))
                drawRect(c.blocked, size = size.copy(width = HerdrBorder.signature.toPx()))
            }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(start = 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s8),
    ) {
        BasicText(
            pluralStringResource(R.plurals.terminal_queue_count, strip.count, strip.count),
            style = HerdrType.secondary.copy(color = c.blocked, fontWeight = FontWeight.W600),
            maxLines = 1,
        )
        BasicText(
            rest,
            Modifier.weight(1f),
            style = HerdrType.secondary.copy(color = c.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ChevronIcon(Chevron.Up, 16.dp, c.muted)
    }
}

/** semantic.queueStripBackground: blocked mixed 9% into chrome. */
private fun blockedMix(blocked: Color, chrome: Color) = Color(
    red = chrome.red + (blocked.red - chrome.red) * 0.09f,
    green = chrome.green + (blocked.green - chrome.green) * 0.09f,
    blue = chrome.blue + (blocked.blue - chrome.blue) * 0.09f,
)

// --- The terminal ------------------------------------------------------------

@Composable
private fun TerminalArea(
    session: TerminalSession,
    screen: TerminalScreenState,
    actions: TerminalActions,
    input: TerminalInputHandle,
    keysShown: Boolean,
    machine: String,
) {
    val c = HerdrTheme.colors
    val density = LocalDensity.current
    val renderer = rememberTerminalRenderer()
    val label = stringResource(R.string.terminal_label)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val padX = with(density) { HerdrLayout.terminalPaddingX.toPx() }
        val padTop = with(density) { HerdrLayout.terminalPaddingTop.toPx() }
        val phoneFont = with(density) { HerdrType.terminalPhone.fontSize.toPx() }
        // The phone's grid always leaves room for the two keys rows, which typing shows.
        val keysPx = if (keysShown) 0f else with(density) { HerdrLayout.keysRowHeight.toPx() } * 2
        val phone = phoneGrid(widthPx, heightPx - keysPx, padX, padTop, phoneFont)
        SideEffect { actions.onPhoneGrid(phone) }

        // Only the grid's size and whether it is live recompose; a frame alone redraws the canvas.
        val shape = session.shape
        val grid = session.grid
        val watch = if (screen.typing || shape == null) {
            null
        } else {
            WatchLayout(widthPx, heightPx, padX, padTop, shape.cols, shape.rows, phoneFont)
        }
        val view = watch?.clamp(screen.watch) ?: WatchView.Fit
        val scroller = remember(screen.typing, shape?.rows) {
            RowScroller(phoneFont * TerminalMetrics.LINE_EM)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                // A desk-sized grid while typing starts, or a zoomed one, never draws over the bars.
                .clipToBounds()
                .semantics { contentDescription = label }
                // Keyed by what the handlers act on: moving to another agent's terminal swaps them.
                .pointerInput(session, screen, screen.typing, padX, padTop, phoneFont) {
                    detectTapGestures(
                        // A tap reads and clicks; the keyboard has its own key. At the phone's
                        // size the cell under the finger is the program's: a program that takes
                        // clicks gets one, as on the desk. Watching draws the desk's size, which
                        // the takeover changes, so a tap there only takes over at phone size.
                        onTap = { at ->
                            if (screen.typing) {
                                val col = floor((at.x - padX) / (phoneFont * TerminalMetrics.ADVANCE_EM)).toInt()
                                val row = floor((at.y - padTop) / (phoneFont * TerminalMetrics.LINE_EM)).toInt()
                                session.click(col.coerceAtLeast(0), row.coerceAtLeast(0))
                            } else {
                                session.lead()
                            }
                        },
                        onLongPress = { screen.selecting = true },
                    )
                }
                .pointerInput(session, screen, actions, screen.typing, shape?.cols, shape?.rows, widthPx, heightPx) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        var dy = pan.y
                        if (watch != null) {
                            var v = watch.clamp(screen.watch)
                            if (zoom != 1f) v = watch.zoomBy(v, zoom, centroid.x, centroid.y)
                            val (moved, rest) = watch.panBy(v, pan.x, pan.y)
                            if (moved != screen.watch) {
                                screen.watch = moved
                                actions.onWatchView(moved)
                            }
                            dy = rest
                        }
                        session.scroll(scroller.drag(dy))
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION")
            session.version
            drawRect(if (grid.isEmpty) c.bg else Color(0xFF000000 or grid.defaultBg.toLong()))
            if (grid.isEmpty) return@Canvas
            val damage = session.damage.take()
            if (watch == null) {
                translate(padX, padTop) {
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, grid, damage, phoneFont) }
                }
            } else {
                translate(view.panX, view.panY) {
                    scale(view.zoom, Offset.Zero) {
                        translate(padX, padTop) {
                            drawIntoCanvas { renderer.draw(it.nativeCanvas, grid, damage, watch.fitFontPx) }
                        }
                    }
                }
                // Below the desk's grid: the terminal's own background, painted above.
            }
        }
        StatusChip(session, screen, machine, grid, input)
        if (screen.typing && shape != null) SizeTag(shape.cols, shape.rows)
        if (shape != null && !shape.live) {
            LiveControl(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = HerdrSpacing.s16, bottom = if (screen.typing) 16.dp else 64.dp),
            ) {
                scroller.reset()
                session.live()
            }
        }
        if (screen.selecting && shape != null) {
            // The screen as it was when the long press opened the layer.
            val text = remember(grid) { grid.text() }
            SelectionLayer(text, grid) { screen.selecting = false }
        }
    }
}

@Composable
private fun rememberTerminalRenderer(): TerminalRenderer {
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    return remember(res) {
        TerminalRenderer(res.getFont(R.font.jetbrains_mono_regular), res.getFont(R.font.jetbrains_mono_bold))
    }
}

/** The bottom chip: watching at desk size, or the state while the terminal is not live. */
@Composable
private fun BoxScope.StatusChip(
    session: TerminalSession,
    screen: TerminalScreenState,
    machine: String,
    grid: TerminalGrid,
    input: TerminalInputHandle,
) {
    val c = HerdrTheme.colors
    val state = session.state
    val text = when (val phase = state?.phase) {
        TermPhase.Typing -> return
        TermPhase.Watching -> if (screen.inputOpen) return else null
        TermPhase.Waiting -> stringResource(R.string.terminal_waiting, machine)
        is TermPhase.Ended -> phase.reason.takeIf { it.isNotBlank() }?.let { stringResource(R.string.terminal_ended_reason, it) }
            ?: stringResource(R.string.terminal_ended)
        is TermPhase.Failed -> stringResource(R.string.terminal_failed, phase.detail)
        TermPhase.Attaching, null -> stringResource(R.string.terminal_attaching)
    }
    val shape = RoundedCornerShape(HerdrRadius.chip)
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = HerdrSpacing.s16, end = HerdrSpacing.s16, bottom = HerdrSpacing.s16)
            .heightIn(min = 40.dp)
            .dropShadow(shape, HerdrChipShadow)
            .clip(shape)
            .background(c.popover)
            .border(HerdrBorder.thin, c.border, shape)
            .clickable(role = Role.Button) {
                screen.inputOpen = true
                input.show()
            }
            .padding(horizontal = HerdrSpacing.s16, vertical = HerdrSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s8),
    ) {
        if (text == null) {
            LineIconImage(LineIcon.Eye, c.muted, 16.dp)
            val cols = state?.cols?.toInt()?.takeIf { it > 0 } ?: grid.cols
            val rows = state?.rows?.toInt()?.takeIf { it > 0 } ?: grid.rows
            val bold = stringResource(R.string.terminal_watching)
            val rest = stringResource(R.string.terminal_watching_rest, cols, rows)
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.fg, fontWeight = FontWeight.W500)) { append(bold) }
                    append(' ')
                    append(rest)
                },
                style = chipText.copy(color = c.muted),
                maxLines = 1,
            )
        } else {
            BasicText(text, style = chipText.copy(color = c.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** `.chip`: 13.5 sp Geist (render 3). */
private val chipText = HerdrType.secondary.copy(fontSize = 13.5.sp)

/** `.sizetag`: "phone size CxR", 11.5 dim on canvas, top right of the terminal. */
@Composable
private fun BoxScope.SizeTag(cols: Int, rows: Int) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.pill)
    BasicText(
        stringResource(R.string.terminal_phone_size, cols, rows),
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = HerdrSpacing.s6, end = HerdrSpacing.s8)
            .background(c.canvas, shape)
            .border(HerdrBorder.thin, c.border, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = HerdrType.secondary.copy(fontSize = 11.5.sp, color = c.dimText),
        maxLines = 1,
    )
}

/** "Live": back to the bottom while scrolled up (terminal-view spec, "Scrollback with a way back to live"). */
@Composable
private fun LiveControl(modifier: Modifier, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.chip)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .dropShadow(shape, HerdrChipShadow)
            .clip(shape)
            .background(c.popover)
            .border(HerdrBorder.thin, c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(stringResource(R.string.terminal_live), style = chipText.copy(color = c.fg, fontWeight = FontWeight.W500))
        ChevronIcon(Chevron.Down, 16.dp, c.fg)
    }
}

/**
 * The selection layer (terminal-view spec, "Select and copy from the local
 * screen"): the view's text from the local grid, never `terminal.read`, at
 * phone size in the terminal's colours, with the platform's selection handles
 * and Copy action.
 */
@Composable
private fun SelectionLayer(text: String, grid: TerminalGrid, onDone: () -> Unit) {
    val c = HerdrTheme.colors
    val bg = Color(0xFF000000 or grid.defaultBg.toLong())
    val fg = Color(0xFF000000 or grid.defaultFg.toLong())
    Column(Modifier.fillMaxSize().background(bg).clickable(remember { MutableInteractionSource() }, null) {}) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.chrome)
                .padding(start = HerdrSpacing.s16, end = HerdrSpacing.s8)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(stringResource(R.string.terminal_select_title), Modifier.weight(1f), style = HerdrType.secondary.copy(color = c.muted))
            BasicText(
                stringResource(R.string.terminal_select_done),
                Modifier
                    .clip(RoundedCornerShape(HerdrRadius.button))
                    .clickable(role = Role.Button, onClick = onDone)
                    .padding(horizontal = HerdrSpacing.s12, vertical = HerdrSpacing.s12),
                style = HerdrType.settingRow.copy(color = c.fg, fontWeight = FontWeight.W500),
            )
        }
        SelectionContainer {
            BasicText(
                text,
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = HerdrLayout.terminalPaddingX, vertical = HerdrLayout.terminalPaddingTop),
                style = HerdrType.terminalPhone.copy(color = fg),
                softWrap = false,
            )
        }
    }
}

// --- Keys row ----------------------------------------------------------------

private sealed interface RowKey {
    data class Tap(val label: String, val key: RowKeyTap, val description: Int? = null) : RowKey
    data object Keyboard : RowKey
    data object Compose : RowKey
}

/**
 * The two keys rows (design-tokens.json `components.keysRow`, 2026-11-03),
 * Termux-like: modifiers and the stop and enter keys on the left, away from
 * the arrows; the keyboard key in the middle of the top row, compose under it.
 */
private val KEY_LINES: List<List<RowKey>> = listOf(
    listOf(
        RowKey.Tap("esc", RowKeyTap.Esc, R.string.key_esc),
        RowKey.Tap("ctrl", RowKeyTap.Ctrl, R.string.key_ctrl),
        RowKey.Tap("alt", RowKeyTap.Alt, R.string.key_alt),
        RowKey.Tap("tab", RowKeyTap.Tab, R.string.key_tab),
        RowKey.Keyboard,
        RowKey.Tap("-", RowKeyTap.Symbol("-")),
        RowKey.Tap("/", RowKeyTap.Symbol("/")),
        RowKey.Tap("↑", RowKeyTap.Up, R.string.key_up),
        RowKey.Tap("~", RowKeyTap.Symbol("~")),
    ),
    listOf(
        RowKey.Tap("^C", RowKeyTap.CtrlC, R.string.key_ctrl_c),
        RowKey.Tap("⇧⇥", RowKeyTap.ShiftTab, R.string.key_shift_tab),
        RowKey.Tap("\\", RowKeyTap.Symbol("\\")),
        RowKey.Tap("⏎", RowKeyTap.Enter, R.string.key_enter),
        RowKey.Compose,
        RowKey.Tap("|", RowKeyTap.Symbol("|")),
        RowKey.Tap("←", RowKeyTap.Left, R.string.key_left),
        RowKey.Tap("↓", RowKeyTap.Down, R.string.key_down),
        RowKey.Tap("→", RowKeyTap.Right, R.string.key_right),
    ),
)

/**
 * `.keys`: two rows of nine, 48 dp each, on chrome, always both while typing.
 * Ctrl and alt latch (solid) while held for the next key; a long press on
 * esc interrupts the agent; the keyboard key shows and hides the keyboard.
 */
@Composable
private fun KeysRow(
    armed: uniffi.herdr_mobile.Armed,
    onTap: (RowKeyTap) -> Unit,
    onInterrupt: () -> Unit,
    onCompose: () -> Unit,
    onKeyboard: () -> Unit,
) {
    val c = HerdrTheme.colors
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().background(c.chrome)) {
        for (line in KEY_LINES) {
            KeyLine {
                for (key in line) {
                    when (key) {
                        RowKey.Keyboard -> IconKey(LineIcon.Keyboard, R.string.key_keyboard, outlined = false, onClick = onKeyboard)
                        RowKey.Compose -> IconKey(LineIcon.Pen, R.string.key_compose, outlined = true, onClick = onCompose)
                        is RowKey.Tap -> {
                            val latched = (key.key == RowKeyTap.Ctrl && armed.ctrl) || (key.key == RowKeyTap.Alt && armed.alt)
                            val hold = if (key.key == RowKeyTap.Esc) {
                                {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onInterrupt()
                                }
                            } else {
                                null
                            }
                            Key(key.label, key.description, latched, onClick = { onTap(key.key) }, onLongClick = hold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyLine(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = HerdrTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(HerdrLayout.keysRowHeight)
            .drawBehind { drawRect(c.border, size = size.copy(height = HerdrBorder.thin.toPx())) }
            .padding(horizontal = 6.dp, vertical = HerdrSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s4),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Key(
    label: String,
    description: Int?,
    latched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = HerdrTheme.colors
    val spoken = description?.let { stringResource(it) }
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(HerdrRadius.key))
            .background(if (latched) c.solid else c.raised)
            .combinedClickableCompat(onClick, onLongClick)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = HerdrType.keysRow.copy(color = if (latched) c.solidFg else c.fg), maxLines = 1)
    }
}

/** An icon key: the keyboard key (raised, like the others) or compose (outlined with the input colour). */
@Composable
private fun androidx.compose.foundation.layout.RowScope.IconKey(icon: LineIcon, description: Int, outlined: Boolean, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.key)
    val spoken = stringResource(description)
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(shape)
            .then(if (outlined) Modifier.border(HerdrBorder.thin, c.input, shape) else Modifier.background(c.raised))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        LineIconImage(icon, c.fg, 18.dp)
    }
}

private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)

private val SWIPE_STEP = 40.dp
