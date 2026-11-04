package dev.herdr.android

import android.content.ComponentName
import android.view.WindowManager.LayoutParams
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Issue 5: the terminal screen set `adjustResize` at runtime and Android put the
 * window back to its default (pan) while the app was in the background, so
 * coming back with the keyboard up panned the terminal over the keys row. The
 * window resizes for the keyboard on every screen, declared once in the manifest;
 * every screen pads for the keyboard itself (safe drawing insets).
 */
@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION") // getActivityInfo(_, Int): Robolectric 4.17 lacks the ComponentInfoFlags overload.
class SoftInputModeTest {
    @Test
    fun the_main_window_always_resizes_for_the_keyboard() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(LayoutParams.SOFT_INPUT_ADJUST_RESIZE, info.softInputMode and LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }
}
