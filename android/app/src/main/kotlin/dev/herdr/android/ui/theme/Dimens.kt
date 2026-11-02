package dev.herdr.android.ui.theme

import androidx.compose.ui.unit.dp

// Mirrors spacing, borders and the layout sizes in design-tokens.json
// (1 CSS px in the renders = 1 dp). Checked by ThemeTokensTest.

/** The 4 dp grid and its steps. */
object HerdrSpacing {
    val unit = 4.dp
    val s2 = 2.dp
    val s4 = 4.dp
    val s6 = 6.dp
    val s8 = 8.dp
    val s10 = 10.dp
    val s12 = 12.dp
    val s14 = 14.dp
    val s16 = 16.dp
    val s20 = 20.dp
    val s24 = 24.dp
    val scale = listOf(s2, s4, s6, s8, s10, s12, s14, s16, s20, s24)
}

object HerdrRadius {
    val pill = 6.dp
    val key = 8.dp
    val askBox = 9.dp
    val notice = 10.dp
    val row = 12.dp
    val button = 12.dp
    val field = 12.dp
    val card = 14.dp
    val machineCard = 16.dp
    val chip = 20.dp
    val sheetTop = 22.dp
    val dialog = 24.dp
}

object HerdrBorder {
    val thin = 1.dp
    val default = 1.dp

    /** The needs-you edge: blocked colour, left side only. */
    val signature = 3.dp
}

object HerdrLayout {
    val screenPaddingX = 16.dp
    val listPaddingX = 8.dp
    val rowPaddingX = 12.dp
    val topBarHeight = 60.dp
    val terminalTitleHeight = 64.dp
    val queueStripHeight = 34.dp
    val keysRowHeight = 48.dp
    val sectionHeaderHeight = 40.dp
    val rowMinHeight = 64.dp

    /** The terminal grid's padding, painted in the terminal background (the Mac pane's, layout.terminalPadding). */
    val terminalPaddingX = 10.dp
    val terminalPaddingTop = 6.dp
}
