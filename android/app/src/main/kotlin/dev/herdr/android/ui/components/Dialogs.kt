package dev.herdr.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.ui.theme.HerdrBorder
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrScrim
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType

// The dialog of render 8 (.scrim, .dialog, .fp, .btn in design/renders/screens.html;
// components.hostKeyDialog and components.button in design-tokens.json), drawn in
// the screen's own composition so screenshots catch it and system back reaches
// the flow that owns it.

/** Render 8's dialog top: 200 px from the phone's top, less the 36 px system bar. */
// CSS spreads line-height above and below every line; Compose trims it off the
// first and last line unless told not to.
private val CssLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private val DialogTop = 164.dp

/**
 * A scrim over the whole screen and the dialog card on it. Tapping the scrim
 * is [onDismiss] (the same as the dialog's Cancel).
 */
@Composable
fun ModalDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.dialog)
    Box(
        Modifier
            .fillMaxSize()
            .background(HerdrScrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(start = HerdrSpacing.s20, end = HerdrSpacing.s20, top = DialogTop, bottom = HerdrSpacing.s20)
                .fillMaxWidth()
                .clip(shape)
                .background(c.popover)
                .border(HerdrBorder.thin, c.border, shape)
                // A CSS border takes room; a Compose border is drawn over the content.
                .padding(HerdrBorder.thin)
                // Taps inside the card are not taps on the scrim.
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = HerdrSpacing.s24, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(HerdrSpacing.s14),
        ) {
            BasicText(title, style = HerdrType.dialogTitle.copy(color = c.fg))
            content()
        }
    }
}

/** A dialog paragraph: 15 sp muted (`.dialog p`). */
@Composable
fun DialogText(text: AnnotatedString, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = HerdrType.body.copy(color = HerdrTheme.colors.muted, lineHeightStyle = CssLines))
}

@Composable
fun DialogText(text: String, modifier: Modifier = Modifier) = DialogText(AnnotatedString(text), modifier)

/** The small note under the fingerprint: 13 sp muted. */
@Composable
fun DialogNote(text: String) {
    BasicText(
        text,
        style = HerdrType.body.copy(fontSize = 13.sp, color = HerdrTheme.colors.muted, lineHeightStyle = CssLines),
    )
}

/** A line saying why the last action failed, in the tokens' error colour (error = blocked). */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = HerdrType.secondary.copy(color = HerdrTheme.colors.blocked))
}

/**
 * The fingerprint box (`.fp`): mono 13.5 on the canvas colour, one line per
 * entry, long ones wrapped. [lines] are e.g. the key type, then `SHA256:...`.
 */
@Composable
fun MonoBox(lines: List<String>, modifier: Modifier = Modifier) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.field)
    BasicText(
        lines.joinToString("\n"),
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.canvas)
            .border(HerdrBorder.thin, c.border, shape)
            .padding(HerdrBorder.thin)
            .padding(horizontal = HerdrSpacing.s14, vertical = HerdrSpacing.s12),
        style = HerdrType.fingerprint.copy(color = c.fg, lineHeightStyle = CssLines),
    )
}

/** The dialog's buttons, right-aligned 10 dp apart (`margin-top: 4px` on top of the 14 dp gap). */
@Composable
fun DialogButtons(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = HerdrSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s10, androidx.compose.ui.Alignment.End),
    ) { content() }
}

/**
 * A `.btn` sized to its label: outlined, or [solid] (solid background,
 * solidFg, 500), 44 dp high, radius 12.
 */
@Composable
fun DialogButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.button)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(if (solid) Modifier.background(c.solid) else Modifier.border(HerdrBorder.thin, c.input, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        BasicText(
            label,
            style = HerdrType.body.copy(
                color = if (solid) c.solidFg else c.fg,
                fontSize = 15.sp,
                fontWeight = if (solid) FontWeight.W500 else FontWeight.W400,
                lineHeight = HerdrType.settingRow.lineHeight,
            ),
            maxLines = 1,
        )
    }
}
