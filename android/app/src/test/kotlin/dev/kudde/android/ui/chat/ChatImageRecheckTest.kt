package dev.kudde.android.ui.chat

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.kudde.android.testing.KuddeTestRunner
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode

@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatImageRecheckTest {
    private val hosts = mutableListOf<ActivityController<ComponentActivity>>()
    private val copy = "copy:example"
    private var now = 0L
    private var calls = 0
    private var bytes: ByteArray? = png(android.graphics.Color.RED)
    private var fail = false
    private val source = DeskImages(
        fetch = { calls++; if (fail) error("unavailable"); bytes },
        now = { now },
        dispatcher = Dispatchers.Unconfined,
    )

    @After
    fun closeHosts() {
        for (host in hosts) host.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private class Slot {
        var visible by mutableStateOf(true)
        var seen: ImageBitmap? = null
        val frames = mutableListOf<Load>()
    }

    private fun mount(viewer: Boolean = false): Slot {
        val slot = Slot()
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        hosts += host
        host.get().setContent {
            if (slot.visible) {
                val load = rememberLoad(source, copy, force = viewer)
                slot.frames += load
                slot.seen = (load as? Load.Loaded)?.image
            }
        }
        idle()
        return slot
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun remount(slot: Slot) {
        slot.visible = false
        idle()
        slot.frames.clear()
        slot.visible = true
        idle()
    }

    @Test
    fun reopeningViewerRechecksImmediatelyAndUpdatesMountedThumbnail() {
        val thumb = mount()
        val first = thumb.seen!!
        bytes = png(android.graphics.Color.BLUE)
        val viewer = mount(viewer = true)
        assertSame(first, (viewer.frames.first() as Load.Loaded).image)
        assertEquals(android.graphics.Color.BLUE, viewer.seen!!.asAndroidBitmap().getPixel(0, 0))
        assertSame(viewer.seen, thumb.seen)
        assertEquals(2, calls)
        bytes = png(android.graphics.Color.GREEN)
        remount(viewer)
        assertEquals(3, calls)
        assertEquals(android.graphics.Color.GREEN, viewer.seen!!.asAndroidBitmap().getPixel(0, 0))
        assertSame(viewer.seen, thumb.seen)
        assertTrue(viewer.frames.all { it is Load.Loaded })
    }

    @Test
    fun thumbnailRemountIsThrottledUntilThirtySeconds() {
        val thumb = mount()
        val first = thumb.seen!!
        bytes = png(android.graphics.Color.BLUE)
        now = 29_999
        remount(thumb)
        assertSame(first, thumb.seen)
        assertEquals(1, calls)
        now = 30_000
        remount(thumb)
        assertSame(first, (thumb.frames.first() as Load.Loaded).image)
        assertEquals(android.graphics.Color.BLUE, thumb.seen!!.asAndroidBitmap().getPixel(0, 0))
        assertEquals(2, calls)
        assertTrue(thumb.frames.all { it is Load.Loaded })
    }

    @Test
    fun failedRecheckKeepsPictureAndStillThrottlesThumbnails() = runBlocking {
        val first = source.load(copy)!!
        fail = true
        now = 30_000
        assertSame(first, source.revalidate(copy, false))
        now = 59_999
        assertSame(first, source.revalidate(copy, false))
        assertEquals(2, calls)
        assertSame(first, source.revalidate(copy, true))
        assertEquals(3, calls)
        assertSame(first, source.cached(copy))
    }

    @Test
    fun failedViewerRecheckNeverShowsLoadingOrMissing() {
        val thumb = mount()
        val first = thumb.seen!!
        fail = true
        val viewer = mount(viewer = true)
        assertSame(first, viewer.seen)
        assertSame(first, thumb.seen)
        assertTrue(viewer.frames.all { it is Load.Loaded })
        assertEquals(2, calls)
    }

    @Test
    fun emptyOrInvalidRecheckKeepsPicture() = runBlocking {
        val first = source.load(copy)!!
        bytes = null
        assertSame(first, source.revalidate(copy, true))
        bytes = byteArrayOf(1, 2, 3)
        assertSame(first, source.revalidate(copy, true))
        assertSame(first, source.cached(copy))
    }

    @Test
    fun unchangedBytesKeepDecodedBitmap() = runBlocking {
        val first = source.load(copy)!!
        bytes = bytes!!.clone()
        assertSame(first, source.revalidate(copy, true))
        assertEquals(2, calls)
    }

    @Test
    fun throttleIsPerKey() = runBlocking {
        source.load(copy)
        source.load("copy:other")
        source.revalidate(copy, false)
        source.revalidate("copy:other", false)
        assertEquals(2, calls)
    }

    @Test
    fun ordinaryKeysKeepTheirCachedBehavior() = runBlocking {
        for (key in listOf("file:example", "log:example", "missing", "uploaded")) {
            val first = source.load(key)!!
            bytes = png(android.graphics.Color.BLUE)
            assertSame(first, source.load(key))
            assertSame(first, source.revalidate(key, true))
        }
        assertEquals(4, calls)
    }
}
