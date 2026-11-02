package dev.herdr.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

// Mirrors colors.themes and colors.semantic in design-tokens.json (the herdr-app
// presets run through resolve()). ThemeTokensTest compares every value with the
// file; change the tokens first, then this.

enum class ThemeName(val tokenKey: String) {
    Graphite("graphite"),
    Paper("paper"),
}

/**
 * Mac: the herdr-app values, kept on purpose (Ezra, 2026-11-01) although dim
 * text and paper's status headers are under 4.5:1. Aa: the replacements in
 * accessibility.followUp, all passing 4.5:1. The person picks one with the
 * "Text contrast" setting ([ContrastSetting]); [Contrast.Default] is what a
 * fresh install gets.
 */
enum class Contrast {
    Mac,
    Aa,
    ;

    companion object {
        val Default = Mac
    }
}

@Immutable
data class HerdrColors(
    val name: ThemeName,
    val isDark: Boolean,
    val bg: Color,
    val chrome: Color,
    val canvas: Color,
    val popover: Color,
    val raised: Color,
    val hover: Color,
    val selected: Color,
    val border: Color,
    val input: Color,
    val fg: Color,
    val termFg: Color,
    val muted: Color,
    /** For rings and counts; text uses [dimText]. */
    val dim: Color,
    /** Dim text (subtitles, times); differs from [dim] only under [Contrast.Aa]. */
    val dimText: Color,
    val idle: Color,
    val ring: Color,
    val accent: Color,
    val solid: Color,
    val solidFg: Color,
    val selection: Color,
    /** The 16 ANSI colours, 0-7 normal then 8-15 bright. */
    val ansi: List<Color>,
    // Agent states, same meaning as the Mac sidebar.
    val working: Color,
    /** Needs you (blocked or error): the only red in the app. */
    val blocked: Color,
    val done: Color,
    /** Machine status dot only. */
    val connected: Color,
)

/** `0xRRGGBB` opaque, or `0xRRGGBBAA` as written in the tokens (alpha last). */
private fun rgb(value: Long): Color = Color(0xFF000000 or value)

private fun rgba(value: Long): Color =
    Color(((value and 0xFF) shl 24) or (value ushr 8))

private val GraphiteMac = HerdrColors(
    name = ThemeName.Graphite,
    isDark = true,
    bg = rgb(0x17191d),
    chrome = rgb(0x101114),
    canvas = rgb(0x131518),
    popover = rgb(0x25272b),
    raised = rgb(0x2c2e31),
    hover = rgba(0xffffff0e),
    selected = rgba(0xffffff17),
    border = rgba(0xffffff18),
    input = rgba(0xffffff24),
    fg = rgb(0xeeeeef),
    termFg = rgb(0xd2d4d9),
    muted = rgb(0x98999b),
    dim = rgb(0x696a6d),
    dimText = rgb(0x696a6d),
    idle = rgb(0x696a6d),
    ring = rgb(0xaeaeb0),
    accent = rgb(0x96b2d7),
    solid = rgb(0xeeeeef),
    solidFg = rgb(0x111215),
    selection = rgb(0x394454),
    ansi = listOf(
        0x45484f, 0xdc8c95, 0xa6bd91, 0xd8be87, 0x96b2d7, 0xb5a2ce, 0x8cbdc3, 0xd2d4d9,
        0x818793, 0xf0a5ad, 0xbad5a2, 0xead2a1, 0xb0cbef, 0xcbb7e6, 0xa4d5db, 0xf0f0f2,
    ).map { rgb(it.toLong()) },
    working = rgb(0xe5c07b),
    blocked = rgb(0xf0705f),
    done = rgb(0x76a8ff),
    connected = rgb(0xa6bd91),
)

private val PaperMac = HerdrColors(
    name = ThemeName.Paper,
    isDark = false,
    bg = rgb(0xffffff),
    chrome = rgb(0xf1f1ee),
    canvas = rgb(0xfafaf9),
    popover = rgb(0xffffff),
    raised = rgb(0xf6f6f6),
    hover = rgba(0x0000000c),
    selected = rgba(0x00000012),
    border = rgba(0x00000017),
    input = rgba(0x00000026),
    fg = rgb(0x1b1b1e),
    termFg = rgb(0x25272c),
    muted = rgb(0x69696a),
    dim = rgb(0x969698),
    dimText = rgb(0x969698),
    idle = rgb(0xa8a8aa),
    ring = rgb(0x5f5f62),
    accent = rgb(0x1f62d0),
    solid = rgb(0x1b1b1e),
    solidFg = rgb(0xffffff),
    selection = rgb(0xcfdcf5),
    ansi = listOf(
        0x25272c, 0xc62a2a, 0x1a7f37, 0x8f5d00, 0x1f62d0, 0x7a45c8, 0x0f6f74, 0x5f6068,
        0x7b7e87, 0xd1242f, 0x1f883d, 0x9a6700, 0x0969da, 0x8250df, 0x1b7c83, 0x111214,
    ).map { rgb(it.toLong()) },
    working = rgb(0x9a6700),
    blocked = rgb(0xc93c2c),
    done = rgb(0x2f6fe4),
    connected = rgb(0x1a7f37),
)

// accessibility.followUp: dim for text only (dim stays for rings and counts);
// paper's status colours shift 1-3% toward the text colour.
private val GraphiteAa = GraphiteMac.copy(dimText = rgb(0x8d8e90))

private val PaperAa = PaperMac.copy(
    dimText = rgb(0x6d6d70),
    working = rgb(0x956401),
    blocked = rgb(0xc73c2c),
    done = rgb(0x2e69d6),
)

fun herdrColors(theme: ThemeName, contrast: Contrast = Contrast.Default): HerdrColors =
    when (theme) {
        ThemeName.Graphite -> if (contrast == Contrast.Mac) GraphiteMac else GraphiteAa
        ThemeName.Paper -> if (contrast == Contrast.Mac) PaperMac else PaperAa
    }

/**
 * The dim layer behind a dialog or sheet: `.scrim` in design/renders/screens.html
 * (#00000080), the same in both themes. Not in design-tokens.json, whose colours
 * come from the herdr-app presets; the renders are the contract for it.
 */
val HerdrScrim = Color(0x80000000)

/**
 * The one shadow (shadows.chip in design-tokens.json, `0 6 20 #00000066`): the
 * floating chip over the terminal. Same in both themes.
 */
val HerdrChipShadow = Shadow(radius = 20.dp, color = Color(0x66000000), offset = DpOffset(0.dp, 6.dp))
