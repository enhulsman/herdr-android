package dev.kudde.android.ui.addmachine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.kudde.android.R
import dev.kudde.android.ui.components.DialogButton
import dev.kudde.android.ui.components.ErrorText
import dev.kudde.android.ui.components.SubScreenTopBar
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeSpacing
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType

// The Add machine form. Render 8 shows its top: the title and the Name and
// Host fields (label 14 muted, then a `.field` 6 dp below: canvas, 1 dp input
// border, radius 12, 16 sp text). Port and User, the note and the button have
// no render and follow the same style. Pure: AddMachineRoute keeps what was
// typed and when to show the checks.

/** What the form can ask for. */
data class AddMachineActions(
    val onBack: () -> Unit,
    val onChange: (MachineInput) -> Unit,
    val onSave: () -> Unit,
)

private val label = KuddeType.body.copy(fontSize = 14.sp, lineHeight = KuddeType.settingRow.lineHeight)
private val fieldText = KuddeType.body.copy(fontSize = 16.sp, lineHeight = 1.45.em)

/**
 * [errors]: the checks to show (null until the first Save). [waitingFor]:
 * after Save, the address the hub is connecting to, until its host key arrives.
 * [error]: why the hub refused the machine.
 */
@Composable
fun AddMachineScreen(
    input: MachineInput,
    errors: FormErrors?,
    waitingFor: String?,
    error: String?,
    actions: AddMachineActions,
    modifier: Modifier = Modifier,
) {
    val c = KuddeTheme.colors
    val editable = waitingFor == null
    Column(
        modifier
            .fillMaxSize()
            .background(c.chrome)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SubScreenTopBar(stringResource(R.string.add_title), stringResource(R.string.back), actions.onBack)
        Column(
            Modifier.padding(start = KuddeSpacing.s20, end = KuddeSpacing.s20, top = KuddeSpacing.s8),
            verticalArrangement = Arrangement.spacedBy(KuddeSpacing.s14),
        ) {
            FormField(
                stringResource(R.string.field_name),
                input.name,
                { actions.onChange(input.copy(name = it)) },
                errors?.name?.let { fieldErrorText(it, input.name.trim()) },
                editable,
                KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            )
            FormField(
                stringResource(R.string.field_host),
                input.host,
                { actions.onChange(input.copy(host = it)) },
                errors?.host?.let { fieldErrorText(it, "") },
                editable,
                KeyboardOptions(
                    KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
            FormField(
                stringResource(R.string.field_port),
                input.port,
                { actions.onChange(input.copy(port = it)) },
                errors?.port?.let { fieldErrorText(it, "") },
                editable,
                KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            )
            FormField(
                stringResource(R.string.field_user),
                input.user,
                { actions.onChange(input.copy(user = it)) },
                errors?.user?.let { fieldErrorText(it, "") },
                editable,
                KeyboardOptions(
                    KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
            )
            BasicText(
                stringResource(R.string.add_note),
                style = KuddeType.secondary.copy(color = c.dimText, lineHeight = 1.45.em),
            )
            error?.let { ErrorText(it) }
            if (waitingFor != null) {
                BasicText(
                    stringResource(R.string.add_waiting, waitingFor),
                    Modifier.padding(vertical = 11.dp),
                    style = KuddeType.body.copy(color = c.muted),
                )
            } else {
                DialogButton(
                    stringResource(R.string.add_save),
                    actions.onSave,
                    Modifier.padding(top = KuddeSpacing.s4).fillMaxWidth(),
                    solid = true,
                )
            }
        }
        Spacer(Modifier.height(KuddeSpacing.s24))
    }
}

@Composable
private fun FormField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    enabled: Boolean,
    keyboard: KeyboardOptions,
) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.field)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(title, style = label.copy(color = c.muted))
        BasicTextField(
            value,
            onValueChange,
            Modifier
                .fillMaxWidth()
                .semantics { if (error != null) error(error) },
            enabled = enabled,
            textStyle = fieldText.copy(color = c.fg),
            keyboardOptions = keyboard,
            singleLine = true,
            cursorBrush = SolidColor(c.fg),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(c.canvas, shape)
                        .border(KuddeBorder.thin, if (error != null) c.blocked else c.input, shape)
                        .padding(KuddeBorder.thin)
                        .padding(horizontal = KuddeSpacing.s14, vertical = KuddeSpacing.s12),
                ) { inner() }
            },
        )
        error?.let { ErrorText(it) }
    }
}

@Composable
private fun fieldErrorText(error: FieldError, name: String): String = when (error) {
    FieldError.NameRequired -> stringResource(R.string.error_name_required)
    FieldError.NameTaken -> stringResource(R.string.error_name_taken, name)
    FieldError.NameTooLong -> stringResource(R.string.error_name_too_long)
    FieldError.NameOneLine -> stringResource(R.string.error_name_one_line)
    FieldError.HostRequired -> stringResource(R.string.error_host_required)
    FieldError.HostInvalid -> stringResource(R.string.error_host_invalid)
    FieldError.PortInvalid -> stringResource(R.string.error_port_invalid)
    FieldError.UserRequired -> stringResource(R.string.error_user_required)
    FieldError.UserInvalid -> stringResource(R.string.error_user_invalid)
}
