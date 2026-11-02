package dev.herdr.android.ui.hostkey

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.R
import dev.herdr.android.ui.components.DialogButton
import dev.herdr.android.ui.components.DialogButtons
import dev.herdr.android.ui.components.DialogNote
import dev.herdr.android.ui.components.DialogText
import dev.herdr.android.ui.components.ErrorText
import dev.herdr.android.ui.components.ModalDialog
import dev.herdr.android.ui.components.MonoBox
import dev.herdr.android.ui.components.OutlineButton
import dev.herdr.android.ui.components.SubScreenTopBar
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType

// The host-key views (machine-connection spec, "Host keys are confirmed once and
// then enforced"): the first-connection confirm exactly as render 8, and the
// changed-key screen, which has no render. The changed-key screen shows both
// fingerprints and offers one action, "Forget this machine's key", behind a
// second confirm; it has no way to trust the new key.

/** A string resource with its one `%1$s` drawn in [highlight] and the rest in the paragraph's colour. */
@Composable
fun highlighted(id: Int, value: String, highlight: Color): AnnotatedString {
    val template = stringResource(id)
    val at = template.indexOf("%1\$s")
    return buildAnnotatedString {
        if (at < 0) {
            append(template)
            return@buildAnnotatedString
        }
        append(template.substring(0, at))
        withStyle(SpanStyle(color = highlight)) { append(value) }
        append(template.substring(at + 4))
    }
}

/** Render 8: "Trust this machine?"; [onTrust] gets back the [prompt] it showed. */
@Composable
fun TrustDialog(prompt: HostKeyPrompt, error: String?, onTrust: (HostKeyPrompt) -> Unit, onCancel: () -> Unit) {
    val c = HerdrTheme.colors
    ModalDialog(stringResource(R.string.trust_title), onDismiss = onCancel) {
        DialogText(highlighted(R.string.trust_body, prompt.address, c.fg))
        MonoBox(listOf(prompt.algorithm, prompt.fingerprint))
        DialogNote(stringResource(R.string.trust_after))
        error?.let { ErrorText(it) }
        DialogButtons {
            DialogButton(stringResource(R.string.cancel), onCancel)
            DialogButton(stringResource(R.string.trust_confirm), { onTrust(prompt) }, solid = true)
        }
    }
}

/** What the changed-key screen can do: go back, or start forgetting the trusted key. */
data class HostKeyChangedActions(
    val onBack: () -> Unit,
    /** "Forget this machine's key": opens the second confirm. */
    val onForget: () -> Unit,
    /** The second confirm's "Forget key". */
    val onConfirmForget: () -> Unit,
    val onCancelForget: () -> Unit,
)

// The form labels' size (render 8's "Name", 14 muted).
private val label = HerdrType.body.copy(fontSize = 14.sp, lineHeight = HerdrType.settingRow.lineHeight)

@Composable
fun HostKeyChangedScreen(
    change: HostKeyChange,
    confirmingForget: Boolean,
    error: String?,
    actions: HostKeyChangedActions,
    modifier: Modifier = Modifier,
) {
    val c = HerdrTheme.colors
    Box(modifier.fillMaxSize().background(c.chrome)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            SubScreenTopBar(stringResource(R.string.changed_title), stringResource(R.string.back), actions.onBack)
            Column(
                Modifier.padding(start = HerdrSpacing.s20, end = HerdrSpacing.s20, top = HerdrSpacing.s8),
                verticalArrangement = Arrangement.spacedBy(HerdrSpacing.s14),
            ) {
                DialogText(highlighted(R.string.changed_body, change.machine, c.fg))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(stringResource(R.string.changed_trusted), style = label.copy(color = c.muted))
                    MonoBox(listOf(change.trusted))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(stringResource(R.string.changed_presented), style = label.copy(color = c.muted))
                    MonoBox(listOf(change.algorithm, change.presented))
                }
                DialogNote(stringResource(R.string.changed_why))
                if (!confirmingForget) error?.let { ErrorText(it) }
                OutlineButton(
                    stringResource(R.string.changed_forget),
                    actions.onForget,
                    Modifier.padding(top = HerdrSpacing.s4).fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(HerdrSpacing.s24))
        }
        if (confirmingForget) {
            ModalDialog(stringResource(R.string.forget_title), onDismiss = actions.onCancelForget) {
                DialogText(stringResource(R.string.forget_body, change.machine))
                error?.let { ErrorText(it) }
                DialogButtons {
                    DialogButton(stringResource(R.string.cancel), actions.onCancelForget)
                    DialogButton(stringResource(R.string.forget_confirm), actions.onConfirmForget, solid = true)
                }
            }
        }
    }
}
