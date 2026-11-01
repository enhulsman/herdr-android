package dev.herdr.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.herdr_mobile.coreVersion

// Graphite bg, fg and muted from design-tokens.json; the full theme comes in M1.
private val Background = Color(0xFF17191D)
private val Foreground = Color(0xFFEEEEEF)
private val Muted = Color(0xFF98999B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(transparent), SystemBarStyle.dark(transparent))
        val version = coreVersion()
        setContent { Home(version) }
    }
}

@Composable
private fun Home(coreVersion: String) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        BasicText("herdr", style = TextStyle(color = Foreground, fontSize = 28.sp))
        BasicText("core $coreVersion", style = TextStyle(color = Muted, fontSize = 14.sp))
    }
}
