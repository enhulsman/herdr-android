package dev.kudde.android.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kudde.android.ui.components.rememberLoop
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType
import uniffi.kudde_mobile.ChatCall
import kotlin.math.PI
import kotlin.math.sin

// The line that shows the agent is still at it, for the whole turn, and the
// shimmer over a command while it runs (spec "A working line shows for the
// whole turn").

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

/**
 * Sheep hopping a fence, and the verb of the moment, counted from when the
 * line first showed. [now] reads the screen's clock (uptime ms), ticked by the
 * route while the agent works; the line recomposes only when the verb
 * changes. [animate] false holds the sheep still.
 */
@Composable
fun WorkingLine(now: () -> Long, animate: Boolean, modifier: Modifier = Modifier) {
    val c = KuddeTheme.colors
    val start = remember { now() }
    val verb by remember { derivedStateOf { workingVerb(now() - start) } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HerdHop(c.fg, c.dimText, animate)
        BasicText("$verb…", style = KuddeType.secondary.copy(color = c.dimText))
    }
}

/** How long a sheep takes to cross. */
const val HOP_MS = 2_100

/** Sheep [k] of three at [t] (0 to 1) along a strip [width] wide: where it is, how high it hops, how far it has faded in. */
internal data class Sheep(val x: Float, val lift: Float, val alpha: Float)

/**
 * Three sheep a third apart cross a strip [width] wide with a fence in the
 * middle; each hops as it passes the fence and fades at the edges. [r] is a
 * sheep's radius, [dp] one dp in pixels.
 */
internal fun sheepAt(t: Float, k: Int, width: Float, r: Float, dp: Float): Sheep {
    val p = (t + k / 3f) % 1f
    val x = r + p * (width - 2 * r)
    val near = ((x - (width / 2 - 9 * dp)) / (18 * dp)).coerceIn(0f, 1f)
    val lift = if (near in 0.001f..0.999f) sin(near * PI).toFloat() * 11 * dp else 0f
    return Sheep(x, lift, (minOf(p, 1 - p) * 8).coerceIn(0f, 1f))
}

@Composable
private fun HerdHop(sheep: Color, fence: Color, animate: Boolean) {
    val size = Modifier.size(width = HERD_WIDTH, height = HERD_HEIGHT)
    if (animate) {
        // Moving: a surface of their own, drawn off the main thread, so the
        // window is not redrawn for them (HerdSurface).
        val bg = KuddeTheme.colors.bg
        AndroidView(
            factory = ::HerdSurface,
            modifier = size,
            update = { it.paint(sheep.toArgb(), fence.toArgb(), bg.toArgb()) },
        )
        return
    }
    // Held still with the first sheep over the fence.
    Canvas(size) {
        val w = this.size.width
        val ground = this.size.height - 2.dp.toPx()
        val r = 2.6.dp.toPx()
        // The fence: two posts and a rail, in the middle.
        val fx = w / 2
        val post = 7.dp.toPx()
        drawLine(fence, Offset(fx - 3.dp.toPx(), ground), Offset(fx - 3.dp.toPx(), ground - post), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(fence, Offset(fx + 3.dp.toPx(), ground), Offset(fx + 3.dp.toPx(), ground - post), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(fence, Offset(fx - 5.dp.toPx(), ground - post * 0.6f), Offset(fx + 5.dp.toPx(), ground - post * 0.6f), 1.5.dp.toPx(), StrokeCap.Round)
        for (k in 0 until 3) {
            val s = sheepAt(0.5f, k, w, r, 1.dp.toPx())
            drawCircle(sheep.copy(alpha = sheep.alpha * s.alpha), r, Offset(s.x, ground - r - s.lift))
        }
    }
}

/** A light band sweeping left to right over the content, on a loop, while [on]; held midway unless [animate]. */
@Composable
fun Modifier.shimmer(on: Boolean, light: Color, animate: Boolean): Modifier {
    if (!on) return this
    val t by rememberLoop(animate, periodMs = 1_600, still = 0.5f)
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
