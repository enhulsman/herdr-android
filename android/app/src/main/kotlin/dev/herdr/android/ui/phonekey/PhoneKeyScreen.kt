package dev.herdr.android.ui.phonekey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.herdr.android.R
import dev.herdr.android.key.KeyManager
import dev.herdr.android.ui.components.DialogNote
import dev.herdr.android.ui.components.DialogText
import dev.herdr.android.ui.components.ErrorText
import dev.herdr.android.ui.components.MonoBox
import dev.herdr.android.ui.components.OutlineButton
import dev.herdr.android.ui.components.SubScreenTopBar
import dev.herdr.android.ui.hostkey.highlighted
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme

// The phone key line (no render; machine-connection spec, "The key is
// restricted to herdr-core on each machine"): the authorized_keys line exactly
// as the Rust side builds it (`restrict,command="..."` plus the key), with a
// Copy button. The line is a public key: the clip is not marked sensitive, and
// the button says "Copied" instead of a toast.

/** The line, or why there is none (no screen lock, a Keystore problem). */
@Immutable
data class PhoneKeyLine(val line: String?, val error: String?)

fun phoneKeyLine(keyManager: KeyManager): PhoneKeyLine = try {
    PhoneKeyLine(keyManager.authorizedKeysLine(), null)
} catch (e: Exception) {
    PhoneKeyLine(null, e.message?.takeIf { it.isNotBlank() } ?: e.toString())
}

/** [machine]: the machine that refused the key, or null from the Phone key row. */
@Composable
fun PhoneKeyScreen(
    machine: String?,
    key: PhoneKeyLine,
    copied: Boolean,
    onCopy: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HerdrTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.chrome)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SubScreenTopBar(stringResource(R.string.phone_key_title), stringResource(R.string.back), onBack)
        Column(
            Modifier.padding(start = HerdrSpacing.s20, end = HerdrSpacing.s20, top = HerdrSpacing.s8),
            verticalArrangement = Arrangement.spacedBy(HerdrSpacing.s14),
        ) {
            if (machine != null) {
                DialogText(highlighted(R.string.phone_key_where_machine, machine, c.fg))
            } else {
                DialogText(stringResource(R.string.phone_key_where_any))
            }
            if (key.line != null) {
                MonoBox(listOf(key.line))
                DialogNote(stringResource(R.string.phone_key_restricted))
                OutlineButton(
                    stringResource(if (copied) R.string.phone_key_copied else R.string.phone_key_copy),
                    { onCopy(key.line) },
                    Modifier.padding(top = HerdrSpacing.s4).fillMaxWidth(),
                )
            } else {
                ErrorText(stringResource(R.string.phone_key_unavailable, key.error.orEmpty()))
            }
        }
        Spacer(Modifier.height(HerdrSpacing.s24))
    }
}

/** The screen fed by the phone key; Copy puts the line on the system clipboard. */
@Composable
fun PhoneKeyRoute(keyManager: KeyManager, machine: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val key = remember(keyManager) { phoneKeyLine(keyManager) }
    var copied by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.phone_key_clip_label)
    PhoneKeyScreen(
        machine = machine,
        key = key,
        copied = copied,
        onCopy = { line ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, line))
            copied = true
        },
        onBack = onBack,
    )
}
