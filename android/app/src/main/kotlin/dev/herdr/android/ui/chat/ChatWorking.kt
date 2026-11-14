package dev.herdr.android.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import uniffi.herdr_mobile.ChatCall
import kotlin.math.PI
import kotlin.math.sin

// Spike for issue 17 (never merged): the line that shows the agent is still at
// it, for the whole turn, and the shimmer over a command while it runs.

/**
 * The agent is at it: its status says so, or a message just went out and the
 * status has not caught up. A `/` or `!` command on its way does not count: a
 * slash command may only open a dialog, a `!` runs in the shell. Blocked waits
 * on the person, so it does not count either.
 */
fun busy(status: String, pending: List<Pending>): Boolean =
    status == "working" || pending.any { p -> p.text.trimStart().let { !it.startsWith("/") && !it.startsWith("!") } }

/** A tool line shimmers while one of its calls runs in a live turn, not for a call an old log left open. */
fun shimmers(calls: List<ChatCall>, busy: Boolean): Boolean = busy && calls.any { !it.done }

/** What the herd is up to, one at a time. */
val WORKING_VERBS = listOf(
    "Herding", "Rounding up", "Ruminating", "Grazing", "Mustering", "Wrangling", "Shepherding",
    "Corralling", "Trotting along", "Chewing the cud", "Counting sheep", "Mending fences",
    "Crossing the pasture", "Whistling for the dog",
)
const val VERB_MS = 4_000L

fun workingVerb(elapsedMs: Long): String = WORKING_VERBS[((elapsedMs / VERB_MS) % WORKING_VERBS.size).toInt()]

/** Sheep hopping a fence, one after another, and the verb of the moment. */
@Composable
fun WorkingLine(modifier: Modifier = Modifier) {
    val c = HerdrTheme.colors
    val start = remember { android.os.SystemClock.uptimeMillis() }
    var now by remember { mutableLongStateOf(start) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            now = android.os.SystemClock.uptimeMillis()
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HerdHop(c.fg, c.dimText)
        BasicText("${workingVerb(now - start)}…", style = HerdrType.secondary.copy(color = c.dimText))
    }
}

@Composable
private fun HerdHop(sheep: Color, fence: Color) {
    val t by rememberInfiniteTransition(label = "herd").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_100, easing = LinearEasing), RepeatMode.Restart), label = "hop",
    )
    Canvas(Modifier.size(width = 40.dp, height = 18.dp)) {
        val w = size.width
        val ground = size.height - 2.dp.toPx()
        val r = 2.6.dp.toPx()
        // The fence: two posts and a rail, in the middle.
        val fx = w / 2
        val post = 7.dp.toPx()
        drawLine(fence, Offset(fx - 3.dp.toPx(), ground), Offset(fx - 3.dp.toPx(), ground - post), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(fence, Offset(fx + 3.dp.toPx(), ground), Offset(fx + 3.dp.toPx(), ground - post), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(fence, Offset(fx - 5.dp.toPx(), ground - post * 0.6f), Offset(fx + 5.dp.toPx(), ground - post * 0.6f), 1.5.dp.toPx(), StrokeCap.Round)
        // Three sheep a third apart; each hops as it passes the fence and fades at the edges.
        for (k in 0 until 3) {
            val p = (t + k / 3f) % 1f
            val x = r + p * (w - 2 * r)
            val near = ((x - (fx - 9.dp.toPx())) / 18.dp.toPx()).coerceIn(0f, 1f)
            val hop = if (near in 0.001f..0.999f) sin(near * PI).toFloat() * 11.dp.toPx() else 0f
            val fade = (minOf(p, 1 - p) * 8).coerceIn(0f, 1f)
            drawCircle(sheep.copy(alpha = sheep.alpha * fade), r, Offset(x, ground - r - hop))
        }
    }
}

/** A light band sweeping left to right over the content, on a loop, while [on]. */
@Composable
fun Modifier.shimmer(on: Boolean, light: Color): Modifier {
    if (!on) return this
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val band = size.width * 0.35f
        val x = -band + t * (size.width + 2 * band)
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, light, Color.Transparent), startX = x - band, endX = x + band),
            blendMode = BlendMode.SrcAtop,
        )
    }
}
