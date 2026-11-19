package dev.kudde.android.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uniffi.kudde_mobile.ChatFound
import uniffi.kudde_mobile.ChatHealth
import uniffi.kudde_mobile.ChatPoll

/**
 * The terminal's title bar offers the switch while the chat can follow: the
 * lookup found the log (or its empty session) and it reads Ok or Degraded.
 * A log that turns Broken keeps the switch it showed [before] on this visit;
 * one Broken when the terminal opens gets none.
 */
fun switchShown(poll: ChatPoll, before: Boolean): Boolean {
    val found = poll.found == ChatFound.FOUND || poll.found == ChatFound.EMPTY
    return found && (poll.health !is ChatHealth.Broken || before)
}

/** "Looking for the conversation…" until the desk has answered a lookup. */
fun chatLooking(poll: ChatPoll?): Boolean = poll == null || !poll.answered

/**
 * Which view one visit to a terminal shows, and whether the switch is
 * offered. [chosen] is the view chosen last, for every agent: with Chat
 * chosen a terminal opens on its chat when it has one, else on its terminal.
 * Until the first poll the chat shows as held ([held], design D12) or
 * "Looking for the conversation…"; the first answered poll decides, and
 * after it only a tap changes the view: an agent started in a watched shell
 * leaves the terminal on screen, and an exit or a lost log is said in the
 * chat, never by switching back unasked. A poll the desk has not answered
 * (the machine still connecting, issue 58) decides nothing.
 */
class ChatSwitch(chosen: Boolean, held: ChatPoll?) {
    var chosen by mutableStateOf(chosen)
        private set
    private val answered = held?.takeIf { it.answered }
    var offered by mutableStateOf(answered?.let { switchShown(it, before = false) } ?: false)
        private set
    var chat by mutableStateOf(chosen && (answered == null || offered))
        private set
    private var settled = false

    fun onPoll(poll: ChatPoll) {
        if (!poll.answered) return
        offered = switchShown(poll, before = offered)
        if (!settled) {
            chat = chosen && offered
            settled = true
        }
    }

    /** A tap on Chat. */
    fun toChat() {
        chosen = true
        chat = true
        settled = true
    }

    /** A tap on Terminal. */
    fun toTerminal() {
        chosen = false
        chat = false
        settled = true
    }
}
