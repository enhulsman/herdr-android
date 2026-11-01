package dev.herdr.android.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.ResourceFont
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

// Both bundled families load from resources at exactly the weights the tokens
// use, and each resource really is that family at that weight (read from the
// font's own name and OS/2 tables, so a swapped file is caught).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontResourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val families: JSONObject by lazy {
        JSONObject(File(System.getProperty("herdr.designTokens")!!).readText())
            .getJSONObject("typography").getJSONObject("families")
    }

    private fun resourceFonts(family: FontFamily): List<ResourceFont> =
        (family as FontListFontFamily).fonts.map { it as ResourceFont }

    @Test
    fun uiFamilyIsGeistAtTheTokenWeights() = check("ui", HerdrFonts.ui)

    @Test
    fun monoFamilyIsJetBrainsMonoAtTheTokenWeights() = check("mono", HerdrFonts.mono)

    private fun check(tokenKey: String, family: FontFamily) {
        val spec = families.getJSONObject(tokenKey)
        val name = spec.getString("name")
        val weightsJson = spec.getJSONArray("weights")
        val weights = (0 until weightsJson.length()).map { weightsJson.getInt(it) }.toSet()
        val fonts = resourceFonts(family)
        assertEquals("$name weights", weights, fonts.map { it.weight.weight }.toSet())
        assertEquals("$name has one font per weight", weights.size, fonts.size)
        for (font in fonts) {
            val label = "$name ${font.weight.weight}"
            val typeface = context.resources.getFont(font.resId)
            assertNotNull("$label loads", typeface)
            assertEquals("$label typeface weight", font.weight.weight, typeface.weight)
            val bytes = context.resources.openRawResource(font.resId).use { it.readBytes() }
            val tables = SfntTables(ByteBuffer.wrap(bytes))
            assertEquals("$label family name", name, tables.familyName())
            assertEquals("$label OS/2 weight class", font.weight.weight, tables.weightClass())
        }
    }
}

// Just enough of the OpenType table directory to read the family name and the
// weight class.
private class SfntTables(private val buf: ByteBuffer) {
    private val tables: Map<String, Int> = buildMap {
        val count = buf.getShort(4).toInt() and 0xFFFF
        for (i in 0 until count) {
            val record = 12 + 16 * i
            val tag = String(ByteArray(4) { buf.get(record + it) }, Charsets.US_ASCII)
            put(tag, buf.getInt(record + 8))
        }
    }

    fun weightClass(): Int = buf.getShort(tables.getValue("OS/2") + 4).toInt() and 0xFFFF

    // Typographic family (16) if present, else the legacy family (1); Windows
    // Unicode (3, 1) records, UTF-16BE.
    fun familyName(): String {
        val names = mutableMapOf<Int, String>()
        val table = tables.getValue("name")
        val count = buf.getShort(table + 2).toInt() and 0xFFFF
        val storage = table + (buf.getShort(table + 4).toInt() and 0xFFFF)
        for (i in 0 until count) {
            val record = table + 6 + 12 * i
            val platform = buf.getShort(record).toInt() and 0xFFFF
            val encoding = buf.getShort(record + 2).toInt() and 0xFFFF
            val nameId = buf.getShort(record + 6).toInt() and 0xFFFF
            val length = buf.getShort(record + 8).toInt() and 0xFFFF
            val offset = buf.getShort(record + 10).toInt() and 0xFFFF
            if (platform == 3 && encoding == 1 && (nameId == 1 || nameId == 16)) {
                val bytes = ByteArray(length) { buf.get(storage + offset + it) }
                names.putIfAbsent(nameId, String(bytes, Charsets.UTF_16BE))
            }
        }
        return names[16] ?: names.getValue(1)
    }
}
