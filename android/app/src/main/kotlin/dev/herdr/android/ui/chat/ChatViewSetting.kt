package dev.herdr.android.ui.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * The view chosen last, Terminal or Chat (spec "The chosen view stays when
 * switching agents"), kept across agents and app restarts in its own
 * app-private preferences file. Terminal on a fresh install; an absent value,
 * or one of another type, reads as Terminal. One instance lives on HerdrApp.
 * Main thread only.
 */
class ChatViewSetting(private val prefs: SharedPreferences) {
    var chat: Boolean = runCatching { prefs.getBoolean(KEY, false) }.getOrDefault(false)
        private set

    fun choose(chat: Boolean) {
        if (chat == this.chat) return
        this.chat = chat
        prefs.edit().putBoolean(KEY, chat).apply()
    }

    companion object {
        const val FILE = "chat_view"
        const val KEY = "chat"

        fun open(context: Context): ChatViewSetting =
            ChatViewSetting(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
