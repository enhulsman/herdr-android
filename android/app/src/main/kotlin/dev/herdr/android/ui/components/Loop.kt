package dev.herdr.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.delay

// The looping animations (the working line's shimmer, the agents list's
// spinner) step 12 times a second instead of on every refresh (issue #48):
// each frame redraws the whole window, so a loop on every refresh kept the app
// near 60% of a core. The sheep hop on a surface of their own (HerdSurface).

/** How often a loop moves, a second. */
const val LOOP_FPS = 12

/** The time between steps. */
private const val STEP_MS = 1_000L / LOOP_FPS

/**
 * How much sooner than a step is due the next frame is asked for: the frame
 * lands on the refresh after the wait, about half a 60 Hz refresh later on
 * average.
 */
private const val STEP_EARLY_MS = 8L

/**
 * How long to wait after a frame at [frameMs] before asking for the next. Steps
 * fall on one grid of [STEP_MS] in frame time, the same for every loop, so loops
 * on screen together step in the same frame: the window redraws all of itself
 * each frame, and loops out of turn would cost a frame each.
 */
internal fun stepWait(frameMs: Long): Long {
    val due = ((frameMs + STEP_MS / 2) / STEP_MS + 1) * STEP_MS
    return (due - frameMs - STEP_EARLY_MS).coerceAtLeast(0)
}

/**
 * Where a loop of [periodMs] is (0 to 1): moving about [LOOP_FPS] times a
 * second while [animate], held at [still] otherwise (reduced motion, a stopped
 * screen, and screenshots, which a loop never lets settle). Read it in a draw
 * so a step redraws only what shows the loop.
 */
@Composable
fun rememberLoop(animate: Boolean, periodMs: Int, still: Float): State<Float> {
    val place = remember { mutableFloatStateOf(still) }
    LaunchedEffect(animate, periodMs, still) {
        if (!animate) {
            place.floatValue = still
            return@LaunchedEffect
        }
        stepLoop(
            periodMs,
            frame = { onFrame -> withFrameMillis(onFrame) },
            wait = { delay(it) },
        ) { place.floatValue = it }
    }
    return place
}

/**
 * The loop itself: on each [frame] it gives [onPlace] its place from the frame
 * time, counted from the first frame, then [wait]s until the next step is due
 * ([stepWait]).
 * The place is set inside the frame, so it draws in that same frame.
 */
internal suspend fun stepLoop(
    periodMs: Int,
    frame: suspend (onFrame: (Long) -> Unit) -> Unit,
    wait: suspend (Long) -> Unit,
    onPlace: (Float) -> Unit,
): Nothing {
    var start = -1L
    var last = 0L
    while (true) {
        frame { t ->
            if (start < 0) start = t
            last = t
            onPlace(((t - start) % periodMs) / periodMs.toFloat())
        }
        wait(stepWait(last))
    }
}

/**
 * Leaves out this content's drawing while something opaque covers it (the
 * terminal under an open chat): the window redraws all of it on every frame,
 * hidden or not.
 */
fun Modifier.undrawnWhen(covered: Boolean): Modifier =
    drawWithContent { if (!covered) drawContent() }
