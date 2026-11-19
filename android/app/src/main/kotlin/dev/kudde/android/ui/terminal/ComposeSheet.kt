package dev.kudde.android.ui.terminal

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
import dev.kudde.android.R
import dev.kudde.android.ui.components.DialogButton
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeScrim
import dev.kudde.android.ui.theme.KuddeSpacing
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType

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
    val c = KuddeTheme.colors
    val title = stringResource(R.string.compose_title)
    Box(
        Modifier
            .matchParentSize()
            .background(KuddeScrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
    )
    val focusRequester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(c.popover, RoundedCornerShape(topStart = KuddeRadius.sheetTop, topEnd = KuddeRadius.sheetTop))
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
            Modifier.fillMaxWidth().padding(start = KuddeSpacing.s20, end = KuddeSpacing.s20, top = KuddeSpacing.s4, bottom = KuddeSpacing.s10),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(title, style = KuddeType.sheetTitle.copy(color = c.fg))
            BasicText(
                stringResource(R.string.compose_to, agent),
                Modifier.padding(start = KuddeSpacing.s12),
                style = KuddeType.secondary.copy(color = c.dimText),
                maxLines = 1,
            )
        }
        val shape = RoundedCornerShape(KuddeRadius.field)
        BasicTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier
                .padding(horizontal = KuddeSpacing.s16)
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 240.dp)
                .focusRequester(focusRequester)
                .background(c.canvas, shape)
                .border(KuddeBorder.thin, c.input, shape)
                .padding(horizontal = KuddeSpacing.s14, vertical = KuddeSpacing.s12),
            textStyle = fieldText.copy(color = c.fg),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
                keyboardType = KeyboardType.Text,
            ),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = KuddeSpacing.s20, end = KuddeSpacing.s16, top = KuddeSpacing.s12, bottom = KuddeSpacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s10),
        ) {
            BasicText(stringResource(R.string.compose_hint), Modifier.weight(1f), style = KuddeType.secondary.copy(color = c.dimText))
            DialogButton(stringResource(R.string.cancel), onCancel)
            DialogButton(stringResource(R.string.compose_paste), onPaste, solid = true)
        }
    }
}

/** `.field`: 16 sp, 1.45 lines. */
private val fieldText = KuddeType.body.copy(fontSize = 16.sp, lineHeight = 1.45.em)
