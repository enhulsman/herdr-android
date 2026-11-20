package dev.kudde.android.ui.theme

import dev.kudde.android.testing.KuddeTestRunner
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only port of herdr-app settings.rs at 07ba77e; D2 requires Double arithmetic. */
@RunWith(KuddeTestRunner::class)
class ThemeResolveTest {
    private val colors by lazy {
        JSONObject(File(requireNotNull(System.getProperty("kudde.designTokens"))).readText()).getJSONObject("colors")
    }

    @Test
    fun graphiteReproducesEveryRecordedColour() = assertRecorded("graphite", graphite)

    @Test
    fun paperReproducesEveryRecordedColour() = assertRecorded("paper", paper)

    @Test
    fun lightMeansWeightedBackgroundLuminanceStrictlyAboveOneHalf() {
        assertFalse(isLight(0x000000))
        assertTrue(isLight(0xffffff))
        assertFalse(isLight(0x7f7f7f))
        assertTrue(isLight(0x808080))
        assertFalse(isLight(0x2f8fd3)) // Weighted luminance is exactly 0.5.
        assertFalse(isLight(0xff0000))
        assertTrue(isLight(0x00ff00))
        assertFalse(isLight(graphite.bg))
        assertTrue(isLight(paper.bg))
    }

    private fun assertRecorded(name: String, palette: Palette) {
        val actual = resolve(palette)
        val recorded = colors.getJSONObject("themes").getJSONObject(name)
        assertEquals("$name fields", recorded.keys().asSequence().toSet(), actual.keys + setOf("mode", "ansi"))
        assertEquals("$name mode", if (isLight(palette.bg)) "light" else "dark", recorded.getString("mode"))
        for ((key, value) in actual) assertEquals("$name.$key", recorded.getString(key), value)
        val ansi = recorded.getJSONArray("ansi")
        assertEquals("$name ANSI length", 16, ansi.length())
        palette.ansi.forEachIndexed { i, value -> assertEquals("$name.ansi[$i]", ansi.getString(i), hex(value)) }
        val semantic = colors.getJSONObject("semantic")
        val states = palette.status ?: listOf(palette.ansi[3], palette.ansi[1], palette.ansi[4])
        for ((key, value) in listOf("working" to states[0], "blocked" to states[1], "done" to states[2], "connected" to palette.ansi[2])) {
            assertEquals("$name.$key", semantic.getJSONObject(key).getString(name), hex(value))
        }
        assertEquals("$name.idle", semantic.getJSONObject("idle").getString(name), actual.getValue("idle"))
    }

    private data class Palette(
        val bg: Int,
        val fg: Int,
        val selection: Int,
        val ansi: List<Int>,
        val status: List<Int>? = null,
    )

    private fun isLight(bg: Int): Boolean {
        fun channel(shift: Int) = ((bg ushr shift) and 255).toDouble() / 255.0
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0) > 0.5
    }

    private fun hex(rgb: Int) = "#%06x".format(rgb)

    private fun mix(a: Int, b: Int, t: Double): Int = listOf(16, 8, 0).fold(0) { result, shift ->
        val x = ((a ushr shift) and 255).toDouble()
        val y = ((b ushr shift) and 255).toDouble()
        result or ((x * (1.0 - t) + y * t).roundToInt() shl shift)
    }

    // Desk resolve() names mapped to their token names (foreground -> fg,
    // terminal_foreground -> termFg, solid_foreground -> solidFg). The tokens
    // do not record the desk's redundant surface/cursor/unknown aliases.
    private fun resolve(p: Palette): Map<String, String> {
        val bg = p.bg
        val light = isLight(bg)
        val fg = if (light) 0x1b1b1e else 0xeeeeef
        return mapOf(
            "bg" to hex(bg),
            "termFg" to hex(p.fg),
            "fg" to hex(fg),
            "chrome" to hex(if (light) mix(bg, 0xe8e8e2, 0.6) else mix(bg, 0, 0.32)),
            "canvas" to hex(if (light) mix(bg, 0xe8e8e2, 0.2) else mix(bg, 0, 0.16)),
            "popover" to hex(if (light) bg else mix(bg, 0xffffff, 0.06)),
            "raised" to hex(if (light) mix(bg, 0, 0.035) else mix(bg, 0xffffff, 0.09)),
            "hover" to if (light) "#0000000c" else "#ffffff0e",
            "selected" to if (light) "#00000012" else "#ffffff17",
            "border" to if (light) "#00000017" else "#ffffff18",
            "input" to if (light) "#00000026" else "#ffffff24",
            "muted" to hex(mix(bg, fg, if (light) 0.66 else 0.6)),
            "dim" to hex(mix(bg, fg, if (light) 0.46 else 0.38)),
            "idle" to hex(mix(bg, fg, 0.38)),
            "ring" to hex(mix(bg, fg, 0.7)),
            "accent" to hex(p.ansi[4]),
            "solid" to hex(fg),
            "solidFg" to hex(if (light) 0xffffff else mix(bg, 0, 0.28)),
            "selection" to hex(p.selection),
        )
    }

    // Independent palette inputs, copied from the two approved desk presets.
    // Expected values always come from design-tokens.json, never this fixture.
    private val graphite = Palette(
        bg = 0x17191d, fg = 0xd2d4d9, selection = 0x394454,
        ansi = listOf(
            0x45484f, 0xdc8c95, 0xa6bd91, 0xd8be87, 0x96b2d7, 0xb5a2ce, 0x8cbdc3, 0xd2d4d9,
            0x818793, 0xf0a5ad, 0xbad5a2, 0xead2a1, 0xb0cbef, 0xcbb7e6, 0xa4d5db, 0xf0f0f2,
        ),
        status = listOf(0xe5c07b, 0xf0705f, 0x76a8ff),
    )
    private val paper = Palette(
        bg = 0xffffff, fg = 0x25272c, selection = 0xcfdcf5,
        ansi = listOf(
            0x25272c, 0xc62a2a, 0x1a7f37, 0x8f5d00, 0x1f62d0, 0x7a45c8, 0x0f6f74, 0x5f6068,
            0x7b7e87, 0xd1242f, 0x1f883d, 0x9a6700, 0x0969da, 0x8250df, 0x1b7c83, 0x111214,
        ),
        status = listOf(0x9a6700, 0xc93c2c, 0x2f6fe4),
    )
}
