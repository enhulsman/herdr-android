package dev.herdr.android.ui.theme

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
import dev.herdr.android.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Pins the release icon (design/renders/icons/herdr.svg as an adaptive icon):
// the full 108 dp layers, then under a circle and a rounded-square mask (the
// launcher picks one), then the themed monochrome on a dark tile.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xhdpi")
class LauncherIconScreenshotTest {
    private val size = 216 // 108 dp at xhdpi
    private val gap = 24

    @Test
    fun releaseIcon() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
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
        out.captureRoboImage("src/test/snapshots/launcher-icon.png")
    }
}
