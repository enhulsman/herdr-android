package dev.herdr.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.herdr.android.R

// Mirrors typography in design-tokens.json. Both families are bundled (OFL;
// licences in assets/licenses/, sources in THIRD_PARTY_NOTICES.md). Styles carry
// no colour: callers take it from HerdrTheme.colors. Checked by ThemeTokensTest
// and FontResourcesTest.

object HerdrFonts {
    /** Geist at the weights the UI uses. */
    val ui = FontFamily(
        Font(R.font.geist_regular, FontWeight.W400),
        Font(R.font.geist_medium, FontWeight.W500),
        Font(R.font.geist_semibold, FontWeight.W600),
    )

    /** JetBrains Mono, for terminal text, requests, keys and fingerprints. */
    val mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.W400),
        Font(R.font.jetbrains_mono_medium, FontWeight.W500),
        Font(R.font.jetbrains_mono_bold, FontWeight.W700),
    )

    /** Ligatures off, so `===` and `->` stay three and two cells. */
    const val MONO_FEATURES = "\"liga\" 0, \"calt\" 0"
}

private fun ui(size: Float, weight: Int, letterSpacing: Float? = null, lineHeight: Float? = null) =
    TextStyle(
        fontFamily = HerdrFonts.ui,
        fontSize = size.sp,
        fontWeight = FontWeight(weight),
        letterSpacing = letterSpacing?.em ?: TextStyle.Default.letterSpacing,
        lineHeight = lineHeight?.em ?: TextStyle.Default.lineHeight,
    )

private fun mono(size: Float?, lineHeight: Float? = null, weight: Int = 400) =
    TextStyle(
        fontFamily = HerdrFonts.mono,
        fontSize = size?.sp ?: TextStyle.Default.fontSize,
        fontWeight = FontWeight(weight),
        lineHeight = lineHeight?.em ?: TextStyle.Default.lineHeight,
        fontFeatureSettings = HerdrFonts.MONO_FEATURES,
    )

object HerdrType {
    val screenTitle = ui(22f, 600, letterSpacing = -0.01f)
    val subScreenTitle = ui(20f, 600)
    val dialogTitle = ui(21f, 600)
    val sheetTitle = ui(18f, 600)
    val terminalTitle = ui(17f, 500)
    val machineName = ui(17f, 500)
    val rowName = ui(16f, 400)
    val body = ui(15f, 400, lineHeight = 1.5f)
    val settingRow = ui(14.5f, 400)

    /** In the section's state colour; the count in dim. */
    val sectionHeader = ui(13f, 500)

    /** Row subtitles and times, in dim text. */
    val secondary = ui(13f, 400)
    val pill = ui(12.5f, 600)

    /** The kind on a needs-you card, in blocked. */
    val cardKind = ui(12f, 600, letterSpacing = 0.01f)

    /** The request on a needs-you card, in termFg. */
    val cardMsg = mono(13f, lineHeight = 1.45f)
    val keysRow = mono(13.5f)
    val fingerprint = mono(13.5f, lineHeight = 1.6f)

    /** Typing at phone size: about 56 columns on 412 dp. */
    val terminalPhone = mono(12.2f, lineHeight = 1.25f)

    /** Watching at desk size: size set at run time to width / (cols * 0.6). */
    val terminalWatch = mono(null, lineHeight = 1.25f)

    /** By token name, as in typography.scale. */
    val all: Map<String, TextStyle> = mapOf(
        "screenTitle" to screenTitle,
        "subScreenTitle" to subScreenTitle,
        "dialogTitle" to dialogTitle,
        "sheetTitle" to sheetTitle,
        "terminalTitle" to terminalTitle,
        "machineName" to machineName,
        "rowName" to rowName,
        "body" to body,
        "settingRow" to settingRow,
        "sectionHeader" to sectionHeader,
        "secondary" to secondary,
        "pill" to pill,
        "cardKind" to cardKind,
        "cardMsg" to cardMsg,
        "keysRow" to keysRow,
        "fingerprint" to fingerprint,
        "terminalPhone" to terminalPhone,
        "terminalWatch" to terminalWatch,
    )
}
