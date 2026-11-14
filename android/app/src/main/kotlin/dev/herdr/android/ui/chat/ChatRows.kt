package dev.herdr.android.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatRow

// The chat's rows apart from drawing them: tool lines, image order, which
// rows fade in, following the bottom, inline code boxes.

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

/** A row's images in the order shown: the person's own, then each call's. */
fun imageKeys(row: ChatRow): List<String> = (row.images + row.calls.flatMap { it.images }).map { it.key }

/** The image [step] places from [key] among [keys] (a swipe in the viewer); null past either end. */
fun stepImage(keys: List<String>, key: String, step: Int): String? {
    val at = keys.indexOf(key)
    if (at < 0) return null
    return keys.getOrNull(at + step)
}

/** The images the open viewer swipes through: those of its row, or the one image when the row is gone. */
fun viewerImages(rows: List<ChatRow>, viewer: Viewer): List<String> =
    rows.firstOrNull { it.key == viewer.row }?.let(::imageKeys)?.takeIf { viewer.image in it } ?: listOf(viewer.image)

/** A drag in the viewer at its normal size: +1 (left, the next image), -1 (right), or 0. */
fun swipeStep(dx: Float, dy: Float, threshold: Float): Int = when {
    kotlin.math.abs(dx) < threshold || kotlin.math.abs(dx) <= kotlin.math.abs(dy) -> 0
    dx < 0 -> 1
    else -> -1
}

/** How long a row that arrives live takes to come in. */
const val FADE_MS = 600L

/**
 * Which rows arrived while the chat was open (design D9). A row first seen
 * by a live poll is live from then; rows from the first read or a read-back
 * are history and show at once. Keys no longer held are forgotten.
 */
class LiveRows {
    private val seen = HashMap<String, Long?>()

    fun saw(rows: List<ChatRow>, now: Long, live: Boolean) {
        val keys = rows.mapTo(HashSet()) { it.key }
        seen.keys.retainAll(keys)
        for (r in rows) if (r.key !in seen) seen[r.key] = if (live) now else null
    }

    /** When a live row was first seen; null for history. */
    fun since(key: String): Long? = seen[key]
}

/** How far a row has come in: 1 for history or once [FADE_MS] has passed. */
fun fadeIn(since: Long?, now: Long): Float =
    if (since == null) 1f else ((now - since).toFloat() / FADE_MS).coerceIn(0f, 1f)

/** How long a row's fade still runs when it is drawn at [now]: it ends [FADE_MS] after the row was seen. */
fun fadeLeftMs(since: Long?, now: Long): Long = if (since == null) 0L else (since + FADE_MS - now).coerceIn(0L, FADE_MS)

/** Following the bottom changes only on the person's own scroll: off when they leave the end, on when back. */
internal fun nextStick(stick: Boolean, byPerson: Boolean, more: Boolean): Boolean = if (byPerson) !more else stick

/**
 * One box per line an inline code range covers, from its first character's
 * left edge to its last one's right edge on that line. Measured by the
 * characters themselves: the position of the offset after a wrapped line's
 * last character is the next line's start, which drew a box backwards over
 * the whole line before the code.
 */
internal fun codeBoxes(l: TextLayoutResult, codes: List<AnnotatedString.Range<String>>): List<Rect> =
    codes.filter { it.start < it.end }.flatMap { a ->
        (l.getLineForOffset(a.start)..l.getLineForOffset(a.end - 1)).mapNotNull { line ->
            val from = maxOf(a.start, l.getLineStart(line))
            val to = minOf(a.end, l.getLineEnd(line, visibleEnd = true))
            if (from >= to) return@mapNotNull null
            val first = l.getBoundingBox(from)
            val last = l.getBoundingBox(to - 1)
            Rect(minOf(first.left, last.left), l.getLineTop(line), maxOf(first.right, last.right), l.getLineBottom(line))
        }
    }
