package dev.herdr.android.ui.theme

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

// The Kotlin theme mirrors design-tokens.json, the signed-off contract. Every
// value the theme carries is compared with the file, and every token in the
// sections the theme mirrors must have a Kotlin counterpart, so neither side can
// drift alone. Runs on Robolectric for its real org.json.
@RunWith(RobolectricTestRunner::class)
class ThemeTokensTest {
    private val tokens: JSONObject by lazy {
        val path = System.getProperty("herdr.designTokens")
        assertNotNull("the build passes herdr.designTokens", path)
        JSONObject(File(path!!).readText())
    }

    private fun hex(value: String): Color {
        val digits = value.removePrefix("#")
        require(digits.length == 6 || digits.length == 8) { "not a colour: $value" }
        val r = digits.substring(0, 2).toInt(16)
        val g = digits.substring(2, 4).toInt(16)
        val b = digits.substring(4, 6).toInt(16)
        val a = if (digits.length == 8) digits.substring(6, 8).toInt(16) else 0xFF
        return Color(red = r, green = g, blue = b, alpha = a)
    }

    private fun px(value: String): Dp = value.removeSuffix("px").toFloat().dp

    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()

    // Every colour field of HerdrColors, by its token name in colors.themes.<theme>.
    private fun themeColours(c: HerdrColors): Map<String, Color> = mapOf(
        "bg" to c.bg,
        "chrome" to c.chrome,
        "canvas" to c.canvas,
        "popover" to c.popover,
        "raised" to c.raised,
        "hover" to c.hover,
        "selected" to c.selected,
        "border" to c.border,
        "input" to c.input,
        "fg" to c.fg,
        "termFg" to c.termFg,
        "muted" to c.muted,
        "dim" to c.dim,
        "idle" to c.idle,
        "ring" to c.ring,
        "accent" to c.accent,
        "solid" to c.solid,
        "solidFg" to c.solidFg,
        "selection" to c.selection,
    )

    @Test
    fun bothThemesMatchTheTokensColourForColour() {
        val themes = tokens.getJSONObject("colors").getJSONObject("themes")
        assertEquals(setOf("graphite", "paper"), themes.keySet())
        for (name in ThemeName.entries) {
            val json = themes.getJSONObject(name.tokenKey)
            val colours = herdrColors(name, Contrast.Mac)
            assertEquals(name.tokenKey, json.getString("mode") == "dark", colours.isDark)
            val expected = json.keySet() - setOf("mode", "ansi")
            val actual = themeColours(colours)
            assertEquals("${name.tokenKey}: tokens without a Kotlin colour or the other way round", expected, actual.keys)
            for (key in expected) {
                assertEquals("${name.tokenKey}.$key", hex(json.getString(key)), actual.getValue(key))
            }
            // Mac contrast: dim text is the dim colour itself.
            assertEquals("${name.tokenKey}.dimText", colours.dim, colours.dimText)
            val ansi: JSONArray = json.getJSONArray("ansi")
            assertEquals(16, ansi.length())
            assertEquals("${name.tokenKey}.ansi", (0 until 16).map { hex(ansi.getString(it)) }, colours.ansi)
        }
    }

    @Test
    fun stateColoursMatchTheSemanticTokensPerTheme() {
        val semantic = tokens.getJSONObject("colors").getJSONObject("semantic")
        for (name in ThemeName.entries) {
            val c = herdrColors(name, Contrast.Mac)
            val states = mapOf(
                "working" to c.working,
                "blocked" to c.blocked,
                "done" to c.done,
                "idle" to c.idle,
                "connected" to c.connected,
            )
            for ((state, colour) in states) {
                val expected = hex(semantic.getJSONObject(state).getString(name.tokenKey))
                assertEquals("${name.tokenKey}.$state", expected, colour)
            }
        }
    }

    // accessibility.followUp is prose: "dim for text graphite #8d8e90, paper
    // #6d6d70 (...); paper status #956401, #c73c2c, #2e69d6", status in the order
    // working, blocked, done (as in knownExceptions).
    @Test
    fun aaContrastSwapsInTheFollowUpValuesOnly() {
        val accessibility = tokens.getJSONObject("accessibility")
        val followUp = accessibility.getString("followUp")
        val dimText = Regex("""dim for text graphite (#[0-9a-fA-F]{6}), paper (#[0-9a-fA-F]{6})""")
            .find(followUp)
        assertNotNull("followUp names the dim text replacements", dimText)
        val status = Regex("""paper status (#[0-9a-fA-F]{6}), (#[0-9a-fA-F]{6}), (#[0-9a-fA-F]{6})""")
            .find(followUp)
        assertNotNull("followUp names the paper status replacements", status)

        val graphiteMac = herdrColors(ThemeName.Graphite, Contrast.Mac)
        val graphiteAa = herdrColors(ThemeName.Graphite, Contrast.Aa)
        assertEquals(hex(dimText!!.groupValues[1]), graphiteAa.dimText)
        // Graphite's status colours already pass; only dim text changes.
        assertEquals(graphiteMac.copy(dimText = graphiteAa.dimText), graphiteAa)

        val paperMac = herdrColors(ThemeName.Paper, Contrast.Mac)
        val paperAa = herdrColors(ThemeName.Paper, Contrast.Aa)
        assertEquals(hex(dimText.groupValues[2]), paperAa.dimText)
        assertEquals(hex(status!!.groupValues[1]), paperAa.working)
        assertEquals(hex(status.groupValues[2]), paperAa.blocked)
        assertEquals(hex(status.groupValues[3]), paperAa.done)
        assertEquals(
            paperMac.copy(
                dimText = paperAa.dimText,
                working = paperAa.working,
                blocked = paperAa.blocked,
                done = paperAa.done,
            ),
            paperAa,
        )
        // "dim stays for rings/counts"
        assertEquals(paperMac.dim, paperAa.dim)
        assertEquals(graphiteMac.dim, graphiteAa.dim)
    }

    /** What [HerdrTheme] gives its content, driven by the app's [ContrastSetting]. */
    private fun themedColours(setting: ContrastSetting, dark: Boolean): HerdrColors {
        var seen: HerdrColors? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { HerdrTheme(setting, dark = dark) { seen = HerdrTheme.colors } }
        shadowOf(Looper.getMainLooper()).idle()
        return seen!!
    }

    // The "Text contrast" setting: Higher puts the accessibility.followUp values
    // in front of every screen, in both themes; Like the Mac keeps the herdr-app ones.
    @Test
    fun higherTextContrastThemesTheAppWithTheFollowUpValues() {
        val followUp = tokens.getJSONObject("accessibility").getString("followUp")
        val dimText = Regex("""dim for text graphite (#[0-9a-fA-F]{6}), paper (#[0-9a-fA-F]{6})""").find(followUp)!!
        val status = Regex("""paper status (#[0-9a-fA-F]{6}), (#[0-9a-fA-F]{6}), (#[0-9a-fA-F]{6})""").find(followUp)!!
        val themes = tokens.getJSONObject("colors").getJSONObject("themes")

        val setting = ContrastSetting.open(RuntimeEnvironment.getApplication())
        val graphiteMac = themedColours(setting, dark = true)
        val paperMac = themedColours(setting, dark = false)
        assertEquals(hex(themes.getJSONObject("graphite").getString("dim")), graphiteMac.dimText)
        assertEquals(hex(themes.getJSONObject("paper").getString("dim")), paperMac.dimText)

        setting.toggle()
        val graphite = themedColours(setting, dark = true)
        val paper = themedColours(setting, dark = false)
        assertEquals(hex(dimText.groupValues[1]), graphite.dimText)
        assertEquals(hex(dimText.groupValues[2]), paper.dimText)
        assertEquals(hex(status.groupValues[1]), paper.working)
        assertEquals(hex(status.groupValues[2]), paper.blocked)
        assertEquals(hex(status.groupValues[3]), paper.done)
        // Rings and counts keep dim.
        assertEquals(hex(themes.getJSONObject("graphite").getString("dim")), graphite.dim)
        assertEquals(hex(themes.getJSONObject("paper").getString("dim")), paper.dim)
    }

    @Test
    fun macContrastIsTheDefaultAsTheTokensKeepIt() {
        val exceptions = tokens.getJSONObject("accessibility").getJSONArray("knownExceptions")
        assertTrue(
            "the tokens keep the Mac values on purpose",
            (0 until exceptions.length()).any { exceptions.getString(it).startsWith("Kept on purpose") },
        )
        assertEquals(Contrast.Mac, Contrast.Default)
    }

    @Test
    fun spacingMatchesTheTokens() {
        val spacing = tokens.getJSONObject("spacing")
        assertEquals(spacing.getInt("unit").dp, HerdrSpacing.unit)
        val scale = spacing.getJSONArray("scale")
        assertEquals((0 until scale.length()).map { scale.getInt(it).dp }, HerdrSpacing.scale)
        val named = listOf(
            HerdrSpacing.s2, HerdrSpacing.s4, HerdrSpacing.s6, HerdrSpacing.s8, HerdrSpacing.s10,
            HerdrSpacing.s12, HerdrSpacing.s14, HerdrSpacing.s16, HerdrSpacing.s20, HerdrSpacing.s24,
        )
        assertEquals(HerdrSpacing.scale, named)
    }

    @Test
    fun radiiAndBorderWidthsMatchTheTokens() {
        val borders = tokens.getJSONObject("borders")
        val radius = borders.getJSONObject("radius")
        val radii = mapOf(
            "pill" to HerdrRadius.pill,
            "key" to HerdrRadius.key,
            "askBox" to HerdrRadius.askBox,
            "notice" to HerdrRadius.notice,
            "row" to HerdrRadius.row,
            "button" to HerdrRadius.button,
            "field" to HerdrRadius.field,
            "card" to HerdrRadius.card,
            "machineCard" to HerdrRadius.machineCard,
            "chip" to HerdrRadius.chip,
            "sheetTop" to HerdrRadius.sheetTop,
            "dialog" to HerdrRadius.dialog,
        )
        assertEquals(radius.keySet(), radii.keys)
        for ((key, value) in radii) assertEquals("radius.$key", px(radius.getString(key)), value)

        val width = borders.getJSONObject("width")
        val widths = mapOf(
            "thin" to HerdrBorder.thin,
            "default" to HerdrBorder.default,
            "signature" to HerdrBorder.signature,
        )
        assertEquals(width.keySet(), widths.keys)
        for ((key, value) in widths) assertEquals("width.$key", px(width.getString(key)), value)
    }

    @Test
    fun layoutSizesMatchTheTokens() {
        val layout = tokens.getJSONObject("layout")
        val sizes = mapOf(
            "screenPaddingX" to HerdrLayout.screenPaddingX,
            "listPaddingX" to HerdrLayout.listPaddingX,
            "rowPaddingX" to HerdrLayout.rowPaddingX,
            "topBarHeight" to HerdrLayout.topBarHeight,
            "terminalTitleHeight" to HerdrLayout.terminalTitleHeight,
            "queueStripHeight" to HerdrLayout.queueStripHeight,
            "keysRowHeight" to HerdrLayout.keysRowHeight,
            "sectionHeaderHeight" to HerdrLayout.sectionHeaderHeight,
            "rowMinHeight" to HerdrLayout.rowMinHeight,
        )
        // Every plain number in layout is a size the theme carries.
        val numeric = layout.keySet().filter { layout.get(it) is Number }.toSet()
        assertEquals(numeric, sizes.keys)
        for ((key, value) in sizes) assertEquals("layout.$key", layout.getDouble(key).toFloat().dp, value)
    }

    @Test
    fun terminalPaddingMatchesTheTokens() {
        val padding = tokens.getJSONObject("layout").getJSONObject("terminalPadding")
        assertEquals(padding.getDouble("x").toFloat().dp, HerdrLayout.terminalPaddingX)
        assertEquals(padding.getDouble("y").toFloat().dp, HerdrLayout.terminalPaddingTop)
    }

    private fun em(value: String): TextUnit = value.removeSuffix("em").toFloat().em

    @Test
    fun typeScaleMatchesTheTokens() {
        val typography = tokens.getJSONObject("typography")
        val scale = typography.getJSONObject("scale")
        assertEquals(scale.keySet(), HerdrType.all.keys)
        for (key in scale.keySet()) {
            val spec = scale.getJSONObject(key)
            val style: TextStyle = HerdrType.all.getValue(key)
            val family = when (spec.getString("family")) {
                "ui" -> HerdrFonts.ui
                "mono" -> HerdrFonts.mono
                else -> error("unknown family in $key")
            }
            assertEquals("$key family", family, style.fontFamily)
            when (val size = spec.get("size")) {
                is Number -> assertEquals("$key size", size.toFloat().sp, style.fontSize)
                // terminalWatch: fitted to the width at run time.
                else -> assertEquals("$key size is computed", TextUnit.Unspecified, style.fontSize)
            }
            assertEquals("$key weight", FontWeight(spec.optInt("weight", 400)), style.fontWeight)
            val letterSpacing = spec.optString("letterSpacing", "")
            assertEquals(
                "$key letterSpacing",
                if (letterSpacing.isEmpty()) TextUnit.Unspecified else em(letterSpacing),
                style.letterSpacing,
            )
            val lineHeight = spec.optDouble("lineHeight", Double.NaN)
            assertEquals(
                "$key lineHeight",
                if (lineHeight.isNaN()) TextUnit.Unspecified else lineHeight.toFloat().em,
                style.lineHeight,
            )
            // Colour comes from the theme at the call site, never baked into the style.
            assertEquals("$key colour", Color.Unspecified, style.color)
            if (family == HerdrFonts.mono) {
                assertEquals("$key ligatures off", HerdrFonts.MONO_FEATURES, style.fontFeatureSettings)
            } else {
                assertNull("$key", style.fontFeatureSettings)
            }
        }
    }

    // "ligatures off (calt/liga disabled)"
    @Test
    fun monoFeaturesTurnOffLigaAndCalt() {
        val features = HerdrFonts.MONO_FEATURES.split(',').map { it.trim() }.toSet()
        assertEquals(setOf("\"liga\" 0", "\"calt\" 0"), features)
    }
}
