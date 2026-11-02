package dev.herdr.android.spike

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.herdr_mobile.TerminalSpike
import uniffi.herdr_mobile.VtException
import uniffi.herdr_mobile.syntheticSession

// Task M0.5, the device smoke test (the spike build type): libghostty-vt plays an invented agent
// session at 200x60, one 4 KiB chunk per display frame, and the screen reports
// frame times. Results also go to logcat under the tag herdr-spike.
private const val TAG = "herdr-spike"
private const val COLS = 200
private const val ROWS = 60
private const val CHUNK = 4096
private const val FRAMES = 900
private const val WARMUP = 60

class SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(transparent), SystemBarStyle.dark(transparent))
        setContent { SpikeScreen() }
    }
}

@Composable
private fun SpikeScreen() {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF17191D))
            .safeDrawingPadding(),
    ) {
        val widthPx = constraints.maxWidth
        val resources = LocalContext.current.resources
        val renderer = remember(widthPx) { FrameRenderer(COLS, ROWS, widthPx, resources) }
        var tick by remember { mutableIntStateOf(0) }
        var report by remember { mutableStateOf("Running…") }
        val heightDp = with(LocalDensity.current) { renderer.bitmap.height.toDp() }

        LaunchedEffect(renderer) {
            val check = gridCheck()
            Log.i(TAG, check)
            report = "$check\nRunning…"
            report = try {
                "$check\n" + run(renderer) { tick++ }
            } catch (e: VtException) {
                "$check\nThe terminal engine failed: ${e.message}"
            }
            Log.i(TAG, report)
        }

        Column {
            Canvas(Modifier.fillMaxWidth().height(heightDp)) {
                tick // redraw on every frame the loop applies
                drawImage(renderer.bitmap.asImageBitmap())
            }
            BasicText(
                report,
                Modifier.padding(16.dp),
                style = TextStyle(color = Color(0xFFEEEEEF), fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            )
        }
    }
}

/** Feeds the session, one chunk per frame; returns the timing summary. */
private suspend fun run(renderer: FrameRenderer, frameApplied: () -> Unit): String {
    val stream = syntheticSession(COLS.toUShort(), (CHUNK * FRAMES).toUInt())
    val engine = LongArray(FRAMES)
    val draw = LongArray(FRAMES)
    val interval = LongArray(FRAMES)
    var bytesOut = 0L
    TerminalSpike(COLS.toUShort(), ROWS.toUShort()).use { terminal ->
        var last = 0L
        for (i in 0 until FRAMES) {
            val now = withFrameNanos { it }
            if (i > 0) interval[i] = now - last
            last = now
            val t0 = System.nanoTime()
            terminal.feed(stream.copyOfRange(i * CHUNK, (i + 1) * CHUNK))
            val frame = terminal.frame()
            val t1 = System.nanoTime()
            renderer.apply(frame)
            val t2 = System.nanoTime()
            engine[i] = t1 - t0
            draw[i] = t2 - t1
            bytesOut += frame.size
            frameApplied()
        }
    }
    val measured = WARMUP until FRAMES
    val total = LongArray(FRAMES) { engine[it] + draw[it] }
    val slow = measured.count { interval[it] > 17_000_000L }
    return buildString {
        appendLine("200x60, 4 KiB per frame, ${FRAMES - WARMUP} frames after warm-up")
        appendLine("engine+encode  " + stats(engine, measured))
        appendLine("draw rows      " + stats(draw, measured))
        appendLine("total work     " + stats(total, measured))
        appendLine("frame interval " + stats(interval, measured))
        appendLine("frames over 16.7 ms: $slow")
        append("frame data: ${bytesOut / FRAMES / 1024} KiB per frame on average")
    }
}

private fun stats(values: LongArray, range: IntRange): String {
    val sorted = range.map { values[it] }.sorted()
    fun ms(p: Double) = "%.1f".format(sorted[((sorted.size - 1) * p).toInt()] / 1e6)
    return "p50 ${ms(0.5)}  p95 ${ms(0.95)}  max ${ms(1.0)} ms"
}

/** The same check as the Rust tests, on the device's own build of the library. */
private fun gridCheck(): String = try {
    TerminalSpike(20u, 4u).use { t ->
        t.feed("hello\r\n\u001b[1;31mworld\u001b[0m 日本".toByteArray())
        val text = t.text()
        if (text == "hello\nworld 日本") "Grid check: ok" else "Grid check FAILED: ${text.replace("\n", "⏎")}"
    }
} catch (e: VtException) {
    "Grid check FAILED: ${e.message}"
}
