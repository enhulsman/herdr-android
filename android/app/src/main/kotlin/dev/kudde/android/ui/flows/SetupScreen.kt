package dev.kudde.android.ui.flows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kudde.android.R
import dev.kudde.android.ui.components.SubScreenTopBar
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType

/** Numbered stub only. Production screens wait for wording and render approval. */
@Composable
fun SetupScreen(step: Int, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(KuddeTheme.colors.chrome).safeDrawingPadding()) {
        SubScreenTopBar(stringResource(R.string.setup_title), stringResource(R.string.back), onBack)
        BasicText(stringResource(R.string.setup_step, step), style = KuddeType.body.copy(color = KuddeTheme.colors.muted))
    }
}
