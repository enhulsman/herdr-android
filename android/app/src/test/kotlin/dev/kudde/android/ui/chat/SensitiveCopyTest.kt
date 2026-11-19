package dev.kudde.android.ui.chat

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import dev.kudde.android.testing.KuddeTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

// Issue #22: what the chat copies may hold secrets from the desk (a token in a
// tool's output), so the copy is marked sensitive and Android 13+ hides it in
// the clipboard preview.
@RunWith(KuddeTestRunner::class)
class SensitiveCopyTest {
    @Test
    fun aChatCopyIsMarkedSensitive() {
        val context = RuntimeEnvironment.getApplication()
        copySensitive(context, "export TOKEN=abc")
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        assertEquals("export TOKEN=abc", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }
}
