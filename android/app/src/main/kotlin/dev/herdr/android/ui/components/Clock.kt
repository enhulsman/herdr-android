package dev.herdr.android.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Wall-clock milliseconds, refreshed on each whole second while the screen is
 * started: the one clock the screens' ages and countdowns tick from. Read it
 * through derivedStateOf (or without observation) so only what changes recomposes.
 */
@Composable
fun rememberWallClock(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val t = System.currentTimeMillis()
                now.longValue = t
                delay(1_000 - t % 1_000)
            }
        }
    }
    return now
}

/**
 * Uptime milliseconds, refreshed every [everyMs] while [running] and the
 * screen is started, held otherwise: a stopped screen (screen off, app in the
 * background) costs nothing. Read it inside derivedStateOf or a draw, so a
 * tick recomposes only what it changes.
 */
@Composable
fun rememberUptimeTicker(running: Boolean, everyMs: Long): State<Long> {
    val now = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, running) {
        now.longValue = SystemClock.uptimeMillis()
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.longValue = SystemClock.uptimeMillis()
                delay(everyMs)
            }
        }
    }
    return now
}

/** Whether the screen is started: false with the screen off or the app in the background. */
@Composable
fun rememberStarted(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}
