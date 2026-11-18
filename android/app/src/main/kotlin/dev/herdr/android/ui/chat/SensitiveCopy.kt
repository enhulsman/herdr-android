package dev.herdr.android.ui.chat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Puts [text] on the clipboard marked sensitive: chat text and tool output may
 * hold secrets from the desk, and Android 13+ then hides it in the clipboard
 * preview (issue #22). The flag's key is the same string before 13.
 */
fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("herdr", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
