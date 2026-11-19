package dev.kudde.android.ui.changes

import uniffi.kudde_mobile.ChangesEntry
import uniffi.kudde_mobile.ChangesLine
import uniffi.kudde_mobile.ChangesList

// The Changes screen apart from drawing it (OpenSpec change changes-view):
// what each diff line is, wrapping, the marks, the 2,000-line cut, the list
// rows' texts. Every text the screen shows is one of the spec's table.

const val CHANGES = "Changes"
const val CHANGES_DESCRIPTION = "Show the files this agent changed"
const val READING = "Reading the changes…"
const val NOT_A_REPO = "Not a git repository"
const val NONE = "No changes since the last commit"
const val NONE_DETAIL = "Committed changes do not show here."
const val FAILED = "Could not read the changes"
const val RETRY = "Retry"
const val CAPPED = "Showing the first 1,000 changed files"
const val BINARY = "Binary file, not shown"
const val EMPTY_FILE = "Empty file"
const val NO_NEWLINE = "No newline at end of file"
const val TOO_LARGE = "Too large to show on the phone"
const val NO_LONGER_CHANGED = "No longer changed"
const val CONTINUES = "↪ "
const val CR_MARK = "CR"
const val CUT_MARK = "… (cut)"

val STATUS_WORDS = listOf("changed", "added", "deleted", "new", "conflict", "submodule", "repository")

/** Lines of a diff's body shown before "Show the whole diff (N lines)". */
const val SHOWN_FIRST = 2000

/** Columns a tab moves to: the next multiple of this. */
const val TAB_STOP = 4

private const val MINUS = "−"

enum class Mark { Added, Removed, Context }

sealed interface DiffRow {
    /** A line of the file: [text] without its `+`, `-` or space and without a carriage return. */
    data class Code(val mark: Mark, val text: String, val cr: Boolean = false, val cut: Boolean = false) : DiffRow
    /** An `@@` hunk header, drawn as a divider. */
    data class Hunk(val text: String) : DiffRow
    /** One of the table's texts in place of lines. */
    data class Note(val text: String) : DiffRow
}

/** What the diff screen draws: rows, then "Show the whole diff (N lines)" or "Too large to show on the phone". */
data class DiffBody(val rows: List<DiffRow>, val showWhole: String?, val tooLarge: Boolean)

/**
 * The rows of a fetched diff of an entry of [kind]. [headers] of [lines] are
 * file headers and are not drawn; the rest is cut at [SHOWN_FIRST] lines
 * unless [whole] or [tooLarge].
 */
fun diffBody(kind: String, lines: List<ChangesLine>, headers: Int, tooLarge: Boolean, whole: Boolean): DiffBody {
    val head = lines.take(headers).map { it.text }
    val body = lines.drop(headers)
    val long = !tooLarge && body.size > SHOWN_FIRST
    val showWhole = if (long && !whole) "Show the whole diff (${grouped(body.size.toLong())} lines)" else null
    if (body.isEmpty()) return DiffBody(headerOnly(head), null, tooLarge)
    if (kind == "submodule") submodule(body)?.let { return DiffBody(listOf(it), null, tooLarge) }
    val shown = if (showWhole != null) body.take(SHOWN_FIRST) else body
    return DiffBody(shown.map(::rowOf), showWhole, tooLarge)
}

/** A diff with no body: a mode change only, or an empty file added or deleted. */
private fun headerOnly(head: List<String>): List<DiffRow> {
    val old = head.firstOrNull { it.startsWith("old mode ") }?.removePrefix("old mode ")
    val new = head.firstOrNull { it.startsWith("new mode ") }?.removePrefix("new mode ")
    return when {
        old != null && new != null -> listOf(DiffRow.Note("Only the file mode changed ($old to $new)"))
        head.any { it.startsWith("new file mode ") || it.startsWith("deleted file mode ") } -> listOf(DiffRow.Note(EMPTY_FILE))
        else -> emptyList()
    }
}

private const val SUBPROJECT = "Subproject commit "

/** "Submodule: 3f2a9c1 to 8b04d7e" from `--submodule=short`'s lines; a side without a commit is zeros. */
private fun submodule(body: List<ChangesLine>): DiffRow? {
    fun side(sign: Char) = body.firstOrNull { it.text.startsWith(sign + SUBPROJECT) }?.text?.drop(1 + SUBPROJECT.length)?.take(7)
    val old = side('-')
    val new = side('+')
    if (old == null && new == null) return null
    return DiffRow.Note("Submodule: ${old ?: "0000000"} to ${new ?: "0000000"}")
}

private fun rowOf(line: ChangesLine): DiffRow {
    val t = line.text
    return when {
        t.startsWith("@@") -> DiffRow.Hunk(t)
        t.startsWith("\\") -> DiffRow.Note(NO_NEWLINE)
        t.startsWith("Binary files ") && t.endsWith(" differ") -> DiffRow.Note(BINARY)
        t.startsWith("+") -> code(Mark.Added, t.drop(1), line.cut)
        t.startsWith("-") -> code(Mark.Removed, t.drop(1), line.cut)
        t.startsWith(" ") -> code(Mark.Context, t.drop(1), line.cut)
        else -> code(Mark.Context, t, line.cut)
    }
}

private fun code(mark: Mark, text: String, cut: Boolean): DiffRow.Code {
    val cr = !cut && text.endsWith("\r")
    return DiffRow.Code(mark, expandTabs(if (cr) text.dropLast(1) else text), cr, cut)
}

/** Tabs as spaces to the next stop of [TAB_STOP] columns, so wrapping counts what is drawn. */
fun expandTabs(text: String): String {
    if ('\t' !in text) return text
    val out = StringBuilder()
    for (c in text) {
        if (c == '\t') {
            val n = TAB_STOP - out.length % TAB_STOP
            repeat(n) { out.append(' ') }
        } else {
            out.append(c)
        }
    }
    return out.toString()
}

/**
 * [text] in parts that fit [columns]: the first part fills the width, each
 * further part leaves room for [CONTINUES]. A break goes after a space in the
 * second half of the part where there is one. The last part leaves [tail]
 * columns for a mark drawn after it (the CR box, the cut mark); when it
 * cannot, the mark gets an empty part of its own. Joined, the parts are
 * [text]. Columns are counted in UTF-16 units; a surrogate pair is never split.
 */
fun wrap(text: String, columns: Int, tail: Int = 0): List<String> {
    val first = columns.coerceAtLeast(1)
    val rest = (columns - CONTINUES.length).coerceAtLeast(1)
    val parts = mutableListOf<String>()
    var at = 0
    while (true) {
        val width = if (parts.isEmpty()) first else rest
        val left = text.length - at
        if (left + tail <= width) {
            parts += text.substring(at)
            return parts
        }
        if (left <= width) {
            // The text fits but its mark does not: the mark goes on a part of its own.
            parts += text.substring(at)
            parts += ""
            return parts
        }
        var end = at + width
        val space = text.lastIndexOf(' ', end - 1)
        if (space >= at + width / 2) end = space + 1
        if (text[end - 1].isHighSurrogate()) end = if (end > at + 1) end - 1 else end + 1
        parts += text.substring(at, end)
        at = end
    }
}

/** 20118 as "20,118", whatever the phone's language: the texts are English. */
fun grouped(n: Long): String {
    val digits = n.toString()
    val out = StringBuilder()
    digits.forEachIndexed { i, c ->
        if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
        out.append(c)
    }
    return out.toString()
}

enum class Tone { Added, Removed, Plain }

/** The colour of a status word, beside the word itself (never colour alone). */
fun toneOf(word: String): Tone = when (word) {
    "new", "added" -> Tone.Added
    "deleted", "conflict" -> Tone.Removed
    else -> Tone.Plain
}

/** "+3" and "−1"; a side not counted is null, as is a new or added file's 0 removed and a deleted one's 0 added. */
fun counts(kind: String, added: ULong?, removed: ULong?): Pair<String?, String?> {
    val onlyAdds = kind == "new" || kind == "added"
    val plus = added?.takeUnless { kind == "deleted" && it == 0uL }?.let { "+" + grouped(it.toLong()) }
    val minus = removed?.takeUnless { onlyAdds && it == 0uL }?.let { MINUS + grouped(it.toLong()) }
    return plus to minus
}

/** A list row: file name, its folder, the status word and its tone, counts, the row's description. */
data class RowText(
    val file: String,
    val dir: String,
    val word: String,
    val tone: Tone,
    val plus: String?,
    val minus: String?,
    val description: String = "",
)

fun rowText(e: ChangesEntry): RowText {
    val bare = e.path.trimEnd('/')
    val slash = bare.lastIndexOf('/')
    val file = if (slash < 0) e.path else e.path.substring(slash + 1)
    val dir = if (slash < 0) "" else bare.substring(0, slash)
    val (plus, minus) = counts(e.kind, e.added, e.removed)
    return RowText(file, dir, e.kind, toneOf(e.kind), plus, minus, description(e.path, e.kind, plus, minus, e))
}

private fun lines(n: ULong) = if (n == 1uL) "1 line" else "${grouped(n.toLong())} lines"

/** "src/a.kt, changed, 3 lines added, 1 line removed": what is drawn, in words. */
private fun description(path: String, word: String, plus: String?, minus: String?, e: ChangesEntry): String {
    val parts = mutableListOf(path, word)
    if (plus != null) e.added?.let { parts += "${lines(it)} added" }
    if (minus != null) e.removed?.let { parts += "${lines(it)} removed" }
    return parts.joinToString(", ")
}

/** The list's title bar: "docs nav · website · 6 files"; "1,000+ files" when capped. */
fun listSubtitle(agent: String, space: String, list: ChangesList): String {
    val parts = mutableListOf(agent, space).filter { it.isNotEmpty() }.toMutableList()
    if (list is ChangesList.Listed && list.entries.isNotEmpty()) {
        val n = list.entries.size
        parts += when {
            list.capped -> "${grouped(n.toLong())}+ files"
            n == 1 -> "1 file"
            else -> "${grouped(n.toLong())} files"
        }
    }
    return parts.joinToString(" · ")
}

/** The diff's title bar under the file name: "src/components · changed · +3 −1". */
fun diffSubtitle(e: ChangesEntry): String {
    val r = rowText(e)
    val counts = listOfNotNull(r.plus, r.minus).joinToString(" ")
    return listOf(r.dir, r.word, counts).filter { it.isNotEmpty() }.joinToString(" · ")
}
