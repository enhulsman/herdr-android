package dev.herdr.android.ui.chat

import android.content.ComponentCallbacks2
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdr.android.ui.agents.AgentKey
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatPoll
import uniffi.herdr_mobile.ChatRefusal
import uniffi.herdr_mobile.ChatRow

/** An image open full screen: the row it belongs to (its swipe set) and the image. */
data class Viewer(val row: String, val image: String)

/** A message on its way out: its bubble, the draft it came from, the text to type and the photos to upload. */
class Outgoing(val pending: Pending, val draft: String, val typed: String, val attached: List<Attachment>)

/** Why a send typed nothing, and whether a button to the terminal helps. */
data class Refusal(val text: String, val terminal: Boolean)

fun refusalOf(r: ChatRefusal): Refusal = when (r) {
    ChatRefusal.Waiting -> Refusal("The agent is waiting for you in the terminal", terminal = true)
    ChatRefusal.Exited -> Refusal("The agent has exited", terminal = false)
    ChatRefusal.Changed -> Refusal("This is a different session now", terminal = true)
    is ChatRefusal.Failed -> Refusal(r.reason, terminal = false)
}

/**
 * What one terminal's chat holds besides the poll: the draft, attached photos
 * and pending messages (spec "A draft is kept for each terminal while the app
 * runs"), and where the person is in it. Sheets and the viewer point at rows
 * by key, so they follow a row as the log grows and is read back.
 */
class ChatState {
    /** The last poll: drawn at once when the chat comes back (design D12). */
    var poll by mutableStateOf<ChatPoll?>(null)
    var draft by mutableStateOf("")
    var attached by mutableStateOf<List<Attachment>>(emptyList())
    private var pendingNow by mutableStateOf<List<Pending>>(emptyList())

    /** Setting it ends the dialog refusal once no slash command's wait is open. */
    var pending: List<Pending>
        get() = pendingNow
        set(value) {
            pendingNow = value
            if (refusal?.text == DIALOG_FIRST && value.none { it.waitOpen }) refusal = null
        }
    var refusal by mutableStateOf<Refusal?>(null)

    /** The turn whose answer is still to be read: the working line stays meanwhile. */
    var turnEnd by mutableStateOf<TurnEnd?>(null)

    /** The tool row whose sheet is open, and the call shown in detail. */
    var sheet by mutableStateOf<String?>(null)
    var detail by mutableStateOf<Int?>(null)
    var viewer by mutableStateOf<Viewer?>(null)

    /** Follow new rows at the bottom; off once the person scrolls up. */
    var stick by mutableStateOf(true)
    var loadingOlder by mutableStateOf(false)

    /** Read-backs finished, so a top still in view asks for the next part. */
    var olderLoads by mutableIntStateOf(0)
    var gaugeOpen by mutableStateOf(false)
    val live = LiveRows()
    private var ids = 0L

    /** Where the chat was left: the first row in view and how far it was scrolled; null at the bottom. */
    private var topKey: String? = null
    private var topOffset = 0

    /** The chat leaves the screen with [firstIndex] the first item in view (pending bubbles follow the rows). */
    fun keep(rows: List<ChatRow>, firstIndex: Int, firstOffset: Int) {
        topKey = if (stick) null else rows.getOrNull(firstIndex)?.key
        topOffset = firstOffset
    }

    /**
     * Where the list starts on return: the row it was left at, wherever the
     * rows dropped on leaving moved it; null for the bottom, which follows
     * new rows again (the row is gone, or it was left at the bottom).
     */
    fun startAt(rows: List<ChatRow>): Pair<Int, Int>? {
        val at = topKey?.let { k -> rows.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }
        if (at == null) {
            stick = true
            return null
        }
        return at to topOffset
    }

    /** A tap on a folded line: one call opens straight to its detail. */
    fun openTools(row: ChatRow) {
        sheet = row.key
        detail = if (row.calls.size == 1) 0 else null
    }

    /** Back in the sheet: to the list, or closed when there is only one call. */
    fun closeDetail(calls: List<ChatCall>) {
        if (calls.size <= 1) sheet = null
        detail = null
    }

    fun closeSheet() {
        sheet = null
        detail = null
    }

    /** A message went out: shown at once, the chat follows the bottom again. */
    fun sent(text: String, images: Int, rows: List<ChatRow>, status: String, now: Long): Pending {
        val p = pendingFor(++ids, text, images, rows, status, now)
        pending = pending + p
        stick = true
        refusal = null
        return p
    }

    /**
     * The send button: the draft and photos leave the box as a pending
     * bubble, to be typed as [Outgoing.typed]. Null when there is nothing to
     * send, or while a slash command's waiting line refuses sends (the draft
     * stays and the refusal says so).
     */
    fun send(rows: List<ChatRow>, status: String, now: Long): Outgoing? {
        val text = draft
        if (text.isBlank() && attached.isEmpty()) return null
        sendRefusal(pending, now)?.let {
            refusal = Refusal(it, terminal = true)
            return null
        }
        val out = Outgoing(sent(text, attached.size, rows, status, now), text, typedText(text), attached)
        draft = ""
        attached = emptyList()
        return out
    }

    /** Nothing was typed: the bubble goes, the draft and photos come back (with anything typed meanwhile), and why shows. */
    fun refused(out: Outgoing, r: ChatRefusal) {
        drop(out.pending.id)
        draft = listOf(out.draft, draft).filter { it.isNotBlank() }.joinToString("\n")
        attached = out.attached + attached
        refusal = refusalOf(r)
    }

    /** `terminal.send` took it ([sure]), or may have (no answer in time). */
    fun typed(id: Long, now: Long, sure: Boolean = true) = update(id) { it.typed(now, sure) }

    /**
     * The message the agent's input box still holds 1.5 s after it was typed
     * ([lines]: the terminal's screen), with no row for it: Enter is pressed
     * once more for it, once. Its id, or null.
     */
    fun enterAgain(agent: String, lines: List<String>, now: Long, canSend: Boolean): Long? {
        // Not into a dialog, a shell or another session: the send's own checks.
        if (!canSend) return null
        val p = pending.firstOrNull { it.needsEnter(now, boxHolds(agent, lines, it.text, it.images)) } ?: return null
        update(p.id) { it.enteredAgain() }
        return p.id
    }

    fun update(id: Long, change: (Pending) -> Pending) {
        pending = pending.map { if (it.id == id) change(it) else it }
    }

    fun drop(id: Long) {
        pending = pending.filterNot { it.id == id }
    }
}

/**
 * Each terminal's [ChatState] for the app's run (design D12), never written
 * to storage: the 8 viewed last, as the hub holds their chats. Main thread.
 */
class ChatStates(private val max: Int = 8) {
    private val held = LinkedHashMap<AgentKey, ChatState>(16, 0.75f, true)

    /** The terminal whose chat is on screen, kept by [trim]. */
    var shown: AgentKey? = null

    fun open(key: AgentKey): ChatState {
        val s = held.getOrPut(key) { ChatState() }
        while (held.size > max) held.remove(held.keys.first())
        return s
    }

    /** Android asked to free memory: every held chat but the one on screen goes. */
    fun trim() {
        held.keys.retainAll(setOfNotNull(shown))
    }
}

/** Android asks to free memory: not the plain move to the background, which every return would pay for. */
fun trimsChats(level: Int): Boolean =
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

/** The calls of the open sheet's row; null when that row is gone. */
fun sheetCalls(rows: List<ChatRow>, key: String?): List<ChatCall>? =
    key?.let { k -> rows.firstOrNull { it.key == k }?.calls?.takeIf { it.isNotEmpty() } }

private fun tokens(n: ULong): String = if (n < 1000u) "$n" else "${(n + 500u) / 1000u}k"

/** The title bar's gauge (design D8): Codex's share of its window, Claude Code's tokens. */
fun gaugeLabel(used: ULong?, window: ULong?, percent: UInt?): String? = when {
    used == null -> null
    window != null && percent != null -> "$percent%"
    else -> tokens(used)
}

/** The gauge's tap detail: "223k of 475k tokens", "283k tokens used". */
fun gaugeDetail(used: ULong?, window: ULong?): String? = when {
    used == null -> null
    window != null -> "${tokens(used)} of ${tokens(window)} tokens"
    else -> "${tokens(used)} tokens used"
}
