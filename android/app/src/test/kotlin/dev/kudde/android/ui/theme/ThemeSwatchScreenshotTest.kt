package dev.kudde.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kudde.android.testing.KuddeTestRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Proves the screenshot wiring and pins the theme's look: every theme colour,
// the state colours, the ANSI row and the type scale in both bundled fonts.
// Goldens: src/test/snapshots/ (recordRoborazziDebug, verifyRoborazziDebug).
// Reference phone from design-tokens.json: 412 dp wide at density 2.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h732dp-xhdpi")
class ThemeSwatchScreenshotTest {
    @Test
    fun graphite() = capture("theme-swatch-graphite", dark = true)

    @Test
    fun paper() = capture("theme-swatch-paper", dark = false)

    private fun capture(name: String, dark: Boolean) {
        captureRoboImage("src/test/snapshots/$name.png") {
            KuddeTheme(dark = dark) { ThemeSwatch() }
        }
    }
}

@Composable
private fun ThemeSwatch() {
    val c = KuddeTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.chrome)
            .padding(KuddeLayout.screenPaddingX),
        verticalArrangement = Arrangement.spacedBy(KuddeSpacing.s12),
    ) {
        BasicText(c.name.tokenKey, style = KuddeType.screenTitle.copy(color = c.fg))
        Chips(
            listOf(c.bg, c.chrome, c.canvas, c.popover, c.raised, c.hover, c.selected, c.border, c.input, c.selection),
        )
        Chips(listOf(c.fg, c.termFg, c.muted, c.dim, c.idle, c.ring, c.accent, c.solid, c.solidFg))
        Chips(listOf(c.working, c.blocked, c.done, c.idle, c.connected))
        Chips(c.ansi.take(8))
        Chips(c.ansi.drop(8))
        Text("Needs you 2", KuddeType.sectionHeader, c.blocked)
        Text("docs nav", KuddeType.rowName, c.fg)
        Text("website · workstation   4m", KuddeType.secondary, c.dimText)
        Text("Machines", KuddeType.subScreenTitle, c.fg)
        Text("Pasted into the terminal. Enter is yours.", KuddeType.body, c.muted)
        // Signature card: popover, 1 dp border, 3 dp blocked left edge, canvas ask box.
        val card = RoundedCornerShape(KuddeRadius.card)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(card)
                .background(c.popover)
                .border(KuddeBorder.thin, c.border, card),
        ) {
            Box(Modifier.width(KuddeBorder.signature).height(KuddeLayout.rowMinHeight).background(c.blocked))
            Column(
                Modifier
                    .padding(KuddeSpacing.s12)
                    .fillMaxWidth()
                    .background(c.canvas, RoundedCornerShape(KuddeRadius.askBox))
                    .padding(KuddeSpacing.s8),
            ) {
                Text("Permission", KuddeType.cardKind, c.blocked)
                Text("git push --force-with-lease  === -> !=", KuddeType.cardMsg, c.termFg)
            }
        }
        Text("esc tab ctrl alt  a == b -> c", KuddeType.keysRow, c.fg)
        Text("SHA256:3f9a 0b1c 77de", KuddeType.fingerprint, c.termFg)
    }
}

@Composable
private fun Chips(colours: List<Color>) {
    val c = KuddeTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s4)) {
        for (colour in colours) {
            Box(
                Modifier
                    .size(KuddeSpacing.s24 + KuddeSpacing.s8)
                    .background(colour, RoundedCornerShape(KuddeRadius.pill))
                    .border(KuddeBorder.thin, c.border, RoundedCornerShape(KuddeRadius.pill)),
            )
        }
    }
}

@Composable
private fun Text(text: String, style: TextStyle, colour: Color) {
    BasicText(text, style = style.copy(color = colour))
}
