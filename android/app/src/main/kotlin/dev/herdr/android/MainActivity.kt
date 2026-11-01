package dev.herdr.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import dev.herdr.android.ui.machines.MachinesRoute
import dev.herdr.android.ui.theme.HerdrTheme

/** The app's screens; switched by state, without a navigation library. */
enum class Screen {
    /** The start screen until the Agents home (step 6) takes over. */
    Machines,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        // Light or dark bar icons follow the system, as the theme does.
        enableEdgeToEdge(SystemBarStyle.auto(transparent, transparent), SystemBarStyle.auto(transparent, transparent))
        val app = application as HerdrApp
        setContent { HerdrTheme { Root(app) } }
    }
}

@Composable
private fun Root(app: HerdrApp) {
    val screen by rememberSaveable { mutableStateOf(Screen.Machines) }
    when (screen) {
        // The start screen has no back arrow; once the Agents home is the start,
        // Machines gets onBack = { screen = Screen.Agents }.
        Screen.Machines -> MachinesRoute(app, onBack = null)
    }
}
