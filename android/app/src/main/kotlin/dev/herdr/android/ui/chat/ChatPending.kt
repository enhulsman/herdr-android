package dev.herdr.android.ui.chat

import uniffi.herdr_mobile.ChatRow

// A message sent from the phone, from the tap until its row is in the log
// (spec "A sent message shows at once and its state is followed", design D10).
// Pure: the times are uptime milliseconds passed in by the caller.

/** What a pending bubble reads. */
enum class PendingState { Sending, Queued, NotArrived }

/** How long a message may stay away before its bubble says so. */
const val ARRIVE_MS = 30_000L

/** How long a slash command may show nothing before the waiting line. */
const val WAIT_MS = 3_000L

/** When the agent's input box may be read for a dropped Enter. */
const val ENTER_AGAIN_MS = 1_500L

/** The send refusal while the waiting line shows. */
const val DIALOG_FIRST = "Answer the dialog in the terminal first"

/**
 * A message on its way. Its row is looked for after [anchor], the key of the
 * conversation's last row when it was sent: rows read back or already there
 * never confirm it. With the anchor gone (`/clear` started a new log), the
 * whole conversation is searched.
 */
data class Pending(
    val id: Long,
    val text: String,
    val images: Int,
    val sentAt: Long,
    val anchor: String?,
    /** For a `!` command: its shell calls in the anchor row when it was sent. */
    val shellsAt: Int,
    val statusAtSend: String,
    /** When `terminal.send` took it; null while the send (or a photo upload) runs. */
    val typedAt: Long? = null,
    val queued: Boolean = false,
    val wasQueued: Boolean = false,
    /** When the agent stopped working after the message was shown queued. */
    val stoppedAt: Long? = null,
    val statusChanged: Boolean = false,
    val waitDismissed: Boolean = false,
    val enterPressed: Boolean = false,
) {
    private val trimmed get() = text.trim()
    val slash: Boolean get() = text.trimStart().startsWith("/")

    /** The shell command of a `!` message. */
    val command: String? get() = trimmed.takeIf { it.startsWith("!") }?.removePrefix("!")?.trim()

    /** A message to the agent itself, not a `/` or `!` command. */
    val plain: Boolean get() = slash.not() && command == null

    fun typed(now: Long) = copy(typedAt = now)
    fun dismissWait() = copy(waitDismissed = true)
    fun enteredAgain() = copy(enterPressed = true)

    /** The person's row for it: the same text, or images and no text for a photo sent alone. */
    fun matches(row: ChatRow): Boolean = when {
        row.kind != "user" -> false
        trimmed.isEmpty() -> row.text.isBlank() && row.images.isNotEmpty()
        else -> row.text.trim() == trimmed
    }

    internal fun shells(row: ChatRow): Int {
        val cmd = command ?: return 0
        return row.calls.count { it.name == "Shell" && it.input.trim() == cmd }
    }

    /** Claude Code's queue holds it: the texts its log reports enqueued, a photo's paths first. */
    fun queuedIn(queue: List<String>): Boolean =
        queue.any { q -> q.lines().drop(images).joinToString("\n").trim() == trimmed }

    fun state(now: Long): PendingState = when {
        queued -> PendingState.Queued
        expired(now) -> PendingState.NotArrived
        else -> PendingState.Sending
    }

    private fun expired(now: Long): Boolean {
        val typed = typedAt ?: return false
        if (wasQueued) return stoppedAt?.let { now - it >= ARRIVE_MS } ?: false
        return now - typed >= ARRIVE_MS
    }

    /** The slash command shows nothing yet: it most likely opened a dialog in the terminal. */
    fun waiting(now: Long): Boolean {
        val typed = typedAt ?: return false
        return slash && !waitDismissed && !statusChanged && now - typed >= WAIT_MS
    }

    val waitingText: String
        get() = "Waiting for the terminal: ${trimmed.substringBefore(' ')} may have opened a dialog there."

    /** Press Enter once more: the box still holds the message 1.5 s after it was typed. */
    fun needsEnter(now: Long, boxHoldsIt: Boolean): Boolean {
        val typed = typedAt ?: return false
        return !enterPressed && boxHoldsIt && now - typed >= ENTER_AGAIN_MS
    }
}

/** A message sent while the conversation held [rows], the agent's status [status]. */
fun pendingFor(id: Long, text: String, images: Int, rows: List<ChatRow>, status: String, now: Long): Pending {
    val last = rows.lastOrNull()
    val draft = Pending(id, text, images, now, last?.key, 0, status)
    return draft.copy(shellsAt = last?.let(draft::shells) ?: 0)
}

/**
 * After a poll: drops the messages whose row arrived, oldest first, each
 * taking a row of its own; notes for the rest whether the agent shows them
 * queued ([queued]), when it stopped working and whether the status moved.
 */
fun followPending(pending: List<Pending>, rows: List<ChatRow>, status: String, now: Long, queued: (Pending) -> Boolean): List<Pending> {
    if (pending.isEmpty()) return pending
    val index = rows.withIndex().associate { it.value.key to it.index }
    val taken = HashSet<Int>()
    val left = ArrayList<Pending>(pending.size)
    for (p in pending) {
        val from = p.anchor?.let(index::get)
        val after = from?.plus(1) ?: 0
        val hit = (after until rows.size).firstOrNull { it !in taken && p.matches(rows[it]) }
        if (hit != null) {
            taken += hit
            continue
        }
        if (p.command != null) {
            val shells = ((from ?: 0) until rows.size).sumOf { p.shells(rows[it]) }
            if (shells > (if (from != null) p.shellsAt else 0)) continue
        }
        val q = queued(p)
        val was = p.wasQueued || q
        left += p.copy(
            queued = q,
            wasQueued = was,
            stoppedAt = if (!was || status == "working") null else p.stoppedAt ?: now,
            statusChanged = p.statusChanged || status != p.statusAtSend,
        )
    }
    return left
}

/** The refusal while a slash command's waiting line shows, or null. */
fun sendRefusal(pending: List<Pending>, now: Long): String? = if (pending.any { it.waiting(now) }) DIALOG_FIRST else null

/**
 * The agent is at it: its status says so, or a plain message is on its way
 * and the status has not caught up. A `/` command may only open a dialog, a
 * `!` command runs in the shell; blocked waits on the person.
 */
fun busy(status: String, pending: List<Pending>, now: Long): Boolean =
    status == "working" || pending.any { it.plain && it.state(now) != PendingState.NotArrived }

/** The pause before the next poll while the chat shows. */
fun pollMs(status: String, pending: List<Pending>, now: Long): Long =
    if (status == "working" || pending.any { it.state(now) != PendingState.NotArrived }) 500L else 5_000L

/** The message as typed into the terminal: a space after a last word starting with `@`. */
fun typedText(text: String): String {
    if (text.isEmpty() || text.last().isWhitespace()) return text
    val last = text.split(Regex("\\s")).last()
    return if (last.startsWith("@")) "$text " else text
}

private fun bare(s: String) = s.filterNot { it.isWhitespace() }

/**
 * The screen holds [text] from line [at] on, after [mark]: line by line, white
 * space ignored (a wrap or a line break), ending at a line's end.
 */
private fun holdsFrom(lines: List<String>, at: Int, mark: Char, text: String): Boolean {
    val want = bare(text)
    if (want.isEmpty()) return false
    val got = StringBuilder()
    for (j in at until lines.size) {
        got.append(bare(if (j == at) lines[j].trimStart().removePrefix(mark.toString()) else lines[j]))
        if (got.length == want.length) return got.toString() == want
        if (got.length > want.length || !want.startsWith(got)) return false
    }
    return false
}

private const val CLAUDE_PROMPT = '❯'
private const val CODEX_PROMPT = '›'
private val PLACEHOLDERS = Regex("^\\s*(\\[[^\\]\\n]*]\\s*)+")

/**
 * Codex shows a message sent mid-turn in its transcript (`› <text>` above the
 * Working line) until it takes it in; a held one sits in its input box.
 */
fun codexShows(lines: List<String>, text: String): Boolean =
    lines.indices.any { lines[it].trimStart().startsWith(CODEX_PROMPT) && holdsFrom(lines, it, CODEX_PROMPT, text) }

/**
 * The agent's input box holds exactly [text] (white space ignored): Claude
 * Code's between its last two rules, Codex's at its last prompt. For a photo
 * send the placeholders the agent shows for the paths come first.
 */
fun boxHolds(agent: String, lines: List<String>, text: String, images: Int): Boolean {
    val content: String = when (agent) {
        "claude" -> {
            val rules = lines.indices.filter { i -> lines[i].isNotBlank() && lines[i].trim().all { it == '─' } }
            if (rules.size < 2) return false
            val box = lines.subList(rules[rules.size - 2] + 1, rules.last())
            if (box.isEmpty() || !box[0].trimStart().startsWith(CLAUDE_PROMPT)) return false
            (listOf(box[0].trimStart().removePrefix(CLAUDE_PROMPT.toString())) + box.drop(1)).joinToString("\n")
        }
        "codex" -> {
            // Its box has no closing rule: the message ends at a line's end, hints may follow.
            val at = lines.indices.lastOrNull { lines[it].trimStart().startsWith(CODEX_PROMPT) } ?: return false
            val first = lines[at].trimStart().removePrefix(CODEX_PROMPT.toString())
            val rest = listOf(if (images > 0) first.replace(PLACEHOLDERS, "") else first) + lines.drop(at + 1)
            return holdsFrom(rest, 0, CODEX_PROMPT, text)
        }
        else -> return false
    }
    val shown = if (images > 0) content.replace(PLACEHOLDERS, "") else content
    val want = bare(text)
    return want.isNotEmpty() && bare(shown) == want
}
