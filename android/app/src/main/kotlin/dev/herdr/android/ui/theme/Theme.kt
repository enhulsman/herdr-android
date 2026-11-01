package dev.herdr.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

// The app's theme without Material: graphite in dark mode, paper in light mode,
// following the system (appearance spec). Screens read colours from
// HerdrTheme.colors and sizes from HerdrSpacing, HerdrRadius, HerdrBorder,
// HerdrLayout and HerdrType; never hard-coded on a screen.

private val LocalHerdrColors = staticCompositionLocalOf { herdrColors(ThemeName.Graphite) }

@Composable
fun HerdrTheme(
    dark: Boolean = isSystemInDarkTheme(),
    contrast: Contrast = Contrast.Default,
    content: @Composable () -> Unit,
) {
    val colors = herdrColors(if (dark) ThemeName.Graphite else ThemeName.Paper, contrast)
    CompositionLocalProvider(LocalHerdrColors provides colors, content = content)
}

object HerdrTheme {
    val colors: HerdrColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHerdrColors.current
}
