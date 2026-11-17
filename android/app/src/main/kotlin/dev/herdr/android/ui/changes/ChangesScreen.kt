package dev.herdr.android.ui.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.R
import dev.herdr.android.ui.components.Chevron
import dev.herdr.android.ui.components.ChevronIcon
import dev.herdr.android.ui.theme.HerdrFonts
import dev.herdr.android.ui.theme.HerdrLayout
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import uniffi.herdr_mobile.ChangesDiff
import uniffi.herdr_mobile.ChangesEntry
import uniffi.herdr_mobile.ChangesList
import uniffi.herdr_mobile.ChangesView

// The Changes screen (OpenSpec change changes-view), drawn to the signed-off
// renders 14-23 in design/renders/screens.html: the list of uncommitted files,
// one file's diff, and their states. It only draws; reading is the route's.

/** Diff text: JetBrains Mono 12.2 at 1.45, ligatures off (`.diff`). */
private val DiffText = TextStyle(
    fontFamily = HerdrFonts.mono,
    fontSize = 12.2.sp,
    lineHeight = 17.7.sp,
    fontFeatureSettings = HerdrFonts.MONO_FEATURES,
)
private val Gutter = 22.dp
private val DiffEnd = 10.dp

/** Columns the cut mark and the CR box take after a line's last part. */
private const val CUT_TAIL = 8
private const val CR_TAIL = 4

/**
 * The Changes screen for [view]: the list, or the open file's diff. [whole]
 * shows a long diff in full. [onBack] leaves the diff for the list, or the
 * list for the terminal; the route decides which. [listScroll] and
 * [diffScroll] are the route's, so the list keeps its place under a diff and
 * a diff keeps its place when it is read again.
 */
@Composable
fun ChangesScreen(
    agent: String,
    space: String,
    view: ChangesView,
    whole: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onWhole: () -> Unit,
    modifier: Modifier = Modifier,
    listScroll: LazyListState = rememberLazyListState(),
    diffScroll: LazyListState = rememberLazyListState(),
) {
    val c = HerdrTheme.colors
    val open = view.open
    Column(modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (open == null) {
            ChangesTitle(CHANGES, listSubtitle(agent, space, view.list), onBack)
            Box(Modifier.fillMaxWidth().weight(1f)) { ListBody(view.list, listScroll, onOpen, onRetry) }
        } else {
            val entry = (view.list as? ChangesList.Listed)?.entries?.firstOrNull { it.path == open }
            val name = open.trimEnd('/').substringAfterLast('/')
            ChangesTitle(name, entry?.let(::diffSubtitle) ?: open.substringBeforeLast('/', ""), onBack)
            Box(Modifier.fillMaxWidth().weight(1f)) { DiffPane(entry?.kind ?: "changed", view.diff, whole, diffScroll, onRetry, onWhole) }
        }
    }
}

/** `.ctop`: back, the name, the line under it. */
@Composable
private fun ChangesTitle(name: String, sub: String, onBack: () -> Unit) {
    val c = HerdrTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = HerdrLayout.terminalTitleHeight).background(c.chrome)
            .padding(start = HerdrSpacing.s4, end = HerdrSpacing.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val back = stringResource(R.string.back)
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(HerdrRadius.button))
                .clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) { ChevronIcon(Chevron.Left, 22.dp, c.fg) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(name, style = HerdrType.terminalTitle.copy(color = c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) BasicText(sub, style = HerdrType.secondary.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** `.empty`: a title, a line under it, Retry where it helps. */
@Composable
private fun Empty(title: String?, line: String?, mono: Boolean = false, onRetry: (() -> Unit)? = null) {
    val c = HerdrTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) BasicText(title, style = HerdrType.terminalTitle.copy(color = c.fg, textAlign = TextAlign.Center))
        if (line != null) {
            val style = if (mono) DiffText.copy(fontSize = 12.5.sp, lineHeight = 18.sp) else HerdrType.settingRow.copy(fontSize = 14.sp, lineHeight = 20.sp)
            BasicText(line, style = style.copy(color = c.muted, textAlign = TextAlign.Center))
        }
        if (onRetry != null) OutlineButton(RETRY, Modifier.padding(top = 10.dp), onRetry)
    }
}

/** `.btn` and `.more`: 44 high, a hairline, radius 12. */
@Composable
private fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = HerdrTheme.colors
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(HerdrRadius.button))
            .border(1.dp, c.input, RoundedCornerShape(HerdrRadius.button))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = HerdrType.settingRow.copy(color = c.fg)) }
}

@Composable
private fun ListBody(list: ChangesList, state: LazyListState, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    when (list) {
        ChangesList.Reading -> Empty(null, READING)
        ChangesList.NotARepo -> Empty(NOT_A_REPO, null)
        is ChangesList.Failed -> Empty(FAILED, list.line, mono = true, onRetry = onRetry)
        is ChangesList.Listed -> if (list.entries.isEmpty()) {
            Empty(NONE, NONE_DETAIL)
        } else {
            LazyColumn(Modifier.fillMaxSize(), state) {
                itemsIndexed(list.entries, key = { _, e -> e.path }) { _, e -> FileRow(e, onOpen) }
                if (list.capped) item(key = "capped") { CapNote() }
            }
        }
    }
}

/** `.frow`: the file, its folder and status word, the counts; a repository is flat and does not open. */
@Composable
private fun FileRow(e: ChangesEntry, onOpen: (String) -> Unit) {
    val c = HerdrTheme.colors
    val r = remember(e) { rowText(e) }
    val border = c.border
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .drawBehind { drawLine(border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .then(if (e.opens) Modifier.clickable(role = Role.Button) { onOpen(e.path) } else Modifier)
            .clearAndSetSemantics { contentDescription = r.description }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(
                r.file,
                style = HerdrType.rowName.copy(fontSize = 15.5.sp, color = if (e.opens) c.fg else c.muted),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val tone = when (r.tone) {
                Tone.Added -> c.ansi[2]
                Tone.Removed -> c.ansi[1]
                Tone.Plain -> c.muted
            }
            BasicText(
                buildAnnotatedString {
                    if (r.dir.isNotEmpty()) append(r.dir + " · ")
                    withStyle(SpanStyle(color = tone)) { append(r.word) }
                },
                style = HerdrType.secondary.copy(color = c.dimText),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (e.opens && (r.plus != null || r.minus != null)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val count = DiffText.copy(fontSize = 13.sp)
                r.plus?.let { BasicText(it, style = count.copy(color = c.ansi[2])) }
                r.minus?.let { BasicText(it, style = count.copy(color = c.ansi[1])) }
            }
        }
    }
}

@Composable
private fun CapNote() {
    val c = HerdrTheme.colors
    BasicText(CAPPED, style = HerdrType.secondary.copy(color = c.dimText), modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
}

@Composable
private fun DiffPane(kind: String, diff: ChangesDiff?, whole: Boolean, state: LazyListState, onRetry: () -> Unit, onWhole: () -> Unit) {
    when (diff) {
        null, ChangesDiff.Reading -> Empty(null, READING)
        ChangesDiff.NoLongerChanged -> Empty(NO_LONGER_CHANGED, null)
        is ChangesDiff.Failed -> Empty(FAILED, diff.line, mono = true, onRetry = onRetry)
        is ChangesDiff.Lines -> {
            val body = remember(diff, kind, whole) { diffBody(kind, diff.lines, diff.headers.toInt(), diff.tooLarge, whole) }
            val only = body.rows.singleOrNull() as? DiffRow.Note
            if (only != null && !body.tooLarge) Empty(null, only.text) else Column(Modifier.fillMaxSize()) { DiffLines(body, state, onWhole) }
        }
    }
}

/** One drawn line of the diff: a part of a [DiffRow.Code], or a whole hunk or note. */
private sealed interface Drawn {
    data class Part(val mark: Mark, val text: String, val first: Boolean, val tail: String?) : Drawn
    data class Hunk(val text: String) : Drawn
    data class Note(val text: String) : Drawn
}

private fun drawn(rows: List<DiffRow>, columns: Int): List<Drawn> = rows.flatMap { row ->
    when (row) {
        is DiffRow.Hunk -> listOf(Drawn.Hunk(row.text))
        is DiffRow.Note -> listOf(Drawn.Note(row.text))
        is DiffRow.Code -> {
            val tail = if (row.cut) CUT_MARK else if (row.cr) CR_MARK else null
            val parts = wrap(row.text, columns, tail = if (row.cut) CUT_TAIL else if (row.cr) CR_TAIL else 0)
            parts.mapIndexed { i, p -> Drawn.Part(row.mark, p, i == 0, if (i == parts.lastIndex) tail else null) }
        }
    }
}

@Composable
private fun DiffLines(body: DiffBody, state: LazyListState, onWhole: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val cell = remember(measurer) { measurer.measure("M", DiffText).size.width.coerceAtLeast(1) }
    val c = HerdrTheme.colors
    // On a file past one call's 8 MB the note would sit under thousands of lines: it stays in view.
    if (body.tooLarge) {
        BasicText(
            TOO_LARGE,
            style = HerdrType.secondary.copy(color = c.dimText, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().background(c.chrome).padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val usable = with(LocalDensity.current) { (maxWidth - Gutter - DiffEnd).toPx() }
        val columns = (usable / cell).toInt().coerceAtLeast(8)
        val lines = remember(body, columns) { drawn(body.rows, columns) }
        LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(top = 6.dp)) {
            itemsIndexed(lines) { i, d ->
                when (d) {
                    is Drawn.Part -> PartLine(d)
                    is Drawn.Hunk -> HunkLine(d.text, first = i == 0)
                    is Drawn.Note -> NoteLine(d.text, italic = d.text == NO_NEWLINE)
                }
            }
            body.showWhole?.let { text -> item(key = "whole") { OutlineButton(text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), onWhole) } }
        }
    }
}

/** `.dl`: the gutter's `+` or `−`, the tinted line, `↪` on each wrapped part, the CR box or cut mark at the end. */
@Composable
private fun PartLine(d: Drawn.Part) {
    val c = HerdrTheme.colors
    val (tint, glyph) = when (d.mark) {
        Mark.Added -> c.ansi[2] to "+"
        Mark.Removed -> c.ansi[1] to "−"
        Mark.Context -> Color.Unspecified to ""
    }
    val bg = if (d.mark == Mark.Context) Color.Transparent else tint.copy(alpha = 0.16f)
    Row(Modifier.fillMaxWidth().background(bg).padding(end = DiffEnd).heightIn(min = 17.7.dp)) {
        BasicText(
            if (d.first) glyph else "",
            style = DiffText.copy(color = if (d.mark == Mark.Context) c.dim else tint, textAlign = TextAlign.Center),
            modifier = Modifier.width(Gutter),
        )
        BasicText(partText(d, c.dim), style = DiffText.copy(color = c.termFg), softWrap = false, maxLines = 1)
    }
}

private fun partText(d: Drawn.Part, dim: Color): AnnotatedString = buildAnnotatedString {
    if (!d.first) withStyle(SpanStyle(color = dim)) { append(CONTINUES) }
    append(d.text)
    when (d.tail) {
        CR_MARK -> withStyle(SpanStyle(color = dim, fontFamily = HerdrFonts.ui, fontSize = 9.5.sp, background = dim.copy(alpha = 0.18f))) { append(" $CR_MARK ") }
        CUT_MARK -> withStyle(SpanStyle(color = dim)) { append(" $CUT_MARK") }
        else -> Unit
    }
}

/** `.hunk`: a divider on the canvas colour with hairlines above and below. */
@Composable
private fun HunkLine(text: String, first: Boolean) {
    val c = HerdrTheme.colors
    val border = c.border
    BasicText(
        text,
        style = DiffText.copy(fontSize = 11.5.sp, color = c.dimText),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = if (first) 0.dp else 10.dp, bottom = 4.dp).fillMaxWidth().background(c.canvas)
            .drawBehind {
                val w = 1.dp.toPx()
                drawLine(border, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                drawLine(border, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
            }
            .padding(start = Gutter, end = DiffEnd, top = 3.dp, bottom = 3.dp),
    )
}

/** `.dnote`: one of the table's texts among the lines. */
@Composable
private fun NoteLine(text: String, italic: Boolean) {
    val c = HerdrTheme.colors
    BasicText(
        text,
        style = HerdrType.secondary.copy(fontSize = 12.5.sp, color = c.dimText, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
        modifier = Modifier.padding(start = Gutter, end = DiffEnd, top = 3.dp, bottom = 3.dp),
    )
}
