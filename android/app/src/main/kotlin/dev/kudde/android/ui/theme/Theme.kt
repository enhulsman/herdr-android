package dev.kudde.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

// The app's theme without Material: graphite in dark mode, paper in light mode,
// following the system (appearance spec). Screens read colours from
// KuddeTheme.colors and sizes from KuddeSpacing, KuddeRadius, KuddeBorder,
// KuddeLayout and KuddeType; never hard-coded on a screen.

private val LocalHerdrColors = staticCompositionLocalOf { kuddeColors(ThemeName.Graphite) }

@Composable
fun KuddeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    contrast: Contrast = Contrast.Default,
    content: @Composable () -> Unit,
) {
    val colors = kuddeColors(if (dark) ThemeName.Graphite else ThemeName.Paper, contrast)
    CompositionLocalProvider(LocalHerdrColors provides colors, content = content)
}

object KuddeTheme {
    val colors: KuddeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHerdrColors.current
}
