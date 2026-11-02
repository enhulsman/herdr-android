package dev.herdr.android.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.herdr.android.R
import dev.herdr.android.ui.components.DialogButton
import dev.herdr.android.ui.theme.HerdrBorder
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrScrim
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType

/**
 * The compose sheet (render 5; terminal-input spec, "A compose key for long
 * text"): the phone's full keyboard with autocorrect, swipe typing and
 * dictation; Paste sends the text as a bracketed paste without Enter.
 */
@Composable
fun BoxScope.ComposeSheet(
    agent: String,
    text: String,
    onText: (String) -> Unit,
    onCancel: () -> Unit,
    onPaste: () -> Unit,
    focus: Boolean = true,
) {
    val c = HerdrTheme.colors
    val title = stringResource(R.string.compose_title)
    Box(
        Modifier
            .matchParentSize()
            .background(HerdrScrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
    )
    val focusRequester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(c.popover, RoundedCornerShape(topStart = HerdrRadius.sheetTop, topEnd = HerdrRadius.sheetTop))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .semantics { paneTitle = title },
    ) {
        Box(
            Modifier
                .padding(top = 10.dp, bottom = 6.dp)
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .background(c.input, RoundedCornerShape(2.dp)),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = HerdrSpacing.s20, end = HerdrSpacing.s20, top = HerdrSpacing.s4, bottom = HerdrSpacing.s10),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(title, style = HerdrType.sheetTitle.copy(color = c.fg))
            BasicText(
                stringResource(R.string.compose_to, agent),
                Modifier.padding(start = HerdrSpacing.s12),
                style = HerdrType.secondary.copy(color = c.dimText),
                maxLines = 1,
            )
        }
        val shape = RoundedCornerShape(HerdrRadius.field)
        BasicTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier
                .padding(horizontal = HerdrSpacing.s16)
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 240.dp)
                .focusRequester(focusRequester)
                .background(c.canvas, shape)
                .border(HerdrBorder.thin, c.input, shape)
                .padding(horizontal = HerdrSpacing.s14, vertical = HerdrSpacing.s12),
            textStyle = fieldText.copy(color = c.fg),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
                keyboardType = KeyboardType.Text,
            ),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = HerdrSpacing.s20, end = HerdrSpacing.s16, top = HerdrSpacing.s12, bottom = HerdrSpacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s10),
        ) {
            BasicText(stringResource(R.string.compose_hint), Modifier.weight(1f), style = HerdrType.secondary.copy(color = c.dimText))
            DialogButton(stringResource(R.string.cancel), onCancel)
            DialogButton(stringResource(R.string.compose_paste), onPaste, solid = true)
        }
    }
}

/** `.field`: 16 sp, 1.45 lines. */
private val fieldText = HerdrType.body.copy(fontSize = 16.sp, lineHeight = 1.45.em)
