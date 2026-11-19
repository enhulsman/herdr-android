package dev.kudde.android.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kudde.android.R
import dev.kudde.android.testing.KuddeTestRunner
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Pins kudde's icons (the ear tag, design/brand/): for the release and the
// debug launcher icon the full 108 dp layers, then under a circle and a
// rounded-square mask (the launcher picks one), then the themed monochrome on a
// dark tile; and the alert icon, white from alpha as the status bar draws it.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xhdpi")
class LauncherIconScreenshotTest {
    private val size = 216 // 108 dp at xhdpi
    private val gap = 24

    @Test
    fun releaseIcon() = captureAdaptive(R.mipmap.ic_launcher, "launcher-icon")

    @Test
    fun debugIcon() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Looked up by name: the debug icon lives in src/debug/res only.
        val id = context.resources.getIdentifier("ic_launcher_debug", "mipmap", context.packageName)
        assertNotEquals("the debug build has its own icon", 0, id)
        captureAdaptive(id, "launcher-icon-debug")
    }

    @Test
    fun notificationIcon() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = context.getDrawable(R.drawable.ic_notification)!!
        // 24 dp at xhdpi, then four times that to see the knock-outs.
        val sizes = listOf(48, 192)
        val out = Bitmap.createBitmap(sizes.sum() + gap * 3, sizes.max() + gap * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(0x2a, 0x2d, 0x31))
        var x = gap
        for (s in sizes) {
            canvas.save()
            canvas.translate(x.toFloat(), (gap + sizes.max() - s).toFloat())
            val d = icon.mutate()
            d.setBounds(0, 0, s, s)
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            d.draw(canvas)
            canvas.restore()
            x += s + gap
        }
        out.captureRoboImage("src/test/snapshots/notification-icon.png")
    }

    private fun captureAdaptive(id: Int, name: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = context.getDrawable(id) as AdaptiveIconDrawable
        assertNotNull("themed icons need the monochrome layer", icon.monochrome)
        val out = Bitmap.createBitmap(size * 4 + gap * 5, size + gap * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(0x80, 0x80, 0x80))
        fun panel(i: Int, clip: Path?, draw: (Canvas) -> Unit) {
            canvas.save()
            canvas.translate((gap + i * (size + gap)).toFloat(), gap.toFloat())
            clip?.let { canvas.clipPath(it) }
            draw(canvas)
            canvas.restore()
        }
        fun layers(c: Canvas) {
            icon.background.setBounds(0, 0, size, size)
            icon.foreground.setBounds(0, 0, size, size)
            icon.background.draw(c)
            icon.foreground.draw(c)
        }
        // The visible 72 dp: 18 dp in from each side.
        val inset = size / 6f
        val visible = RectF(inset, inset, size - inset, size - inset)
        panel(0, null, ::layers)
        panel(1, Path().apply { addOval(visible, Path.Direction.CW) }, ::layers)
        panel(2, Path().apply { addRoundRect(visible, 0.3f * visible.width(), 0.3f * visible.width(), Path.Direction.CW) }, ::layers)
        panel(3, Path().apply { addOval(visible, Path.Direction.CW) }) { c ->
            c.drawColor(Color.rgb(0x2a, 0x2d, 0x31))
            val mono = icon.monochrome!!.mutate()
            mono.setBounds(0, 0, size, size)
            mono.colorFilter = PorterDuffColorFilter(Color.rgb(0xd8, 0xdc, 0xe0), PorterDuff.Mode.SRC_IN)
            mono.draw(c)
        }
        out.captureRoboImage("src/test/snapshots/$name.png")
    }
}
