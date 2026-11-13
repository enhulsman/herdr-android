package dev.herdr.android.ui.chat

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// Spike for issue 17 (never merged). A thumbnail whose slot is handed another
// image (a row re-keyed after reading back) must show that image, not keep the
// one it had: tapping it opens the new key.
@RunWith(RobolectricTestRunner::class)
class ChatThumbKeyTest {
    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()

    private fun shown(cachedFirst: Boolean): Pair<ImageBitmap?, ImageBitmap> {
        val a = bitmap()
        val b = bitmap()
        val source = object : ChatImageSource {
            override fun cached(key: String) = if (cachedFirst) mapOf("a" to a, "b" to b)[key] else null
            override suspend fun load(key: String) = mapOf("a" to a, "b" to b)[key]
        }
        var key by mutableStateOf("a")
        var seen: ImageBitmap? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { seen = (rememberLoad(source, key) as? Load.Loaded)?.image }
        shadowOf(Looper.getMainLooper()).idle()
        key = "b"
        shadowOf(Looper.getMainLooper()).idle()
        return seen to b
    }

    @Test
    fun aCachedImageFollowsTheKey() {
        val (seen, b) = shown(cachedFirst = true)
        assertSame(b, seen)
    }

    @Test
    fun aLoadedImageFollowsTheKey() {
        val (seen, b) = shown(cachedFirst = false)
        assertSame(b, seen)
    }
}
