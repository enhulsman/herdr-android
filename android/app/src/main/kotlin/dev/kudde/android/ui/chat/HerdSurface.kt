package dev.kudde.android.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.unit.dp

/** The working line's sheep strip. */
internal val HERD_WIDTH = 40.dp
internal val HERD_HEIGHT = 18.dp

/** The sheep draw at most about 30 times a second (a little under 1/30 s apart, so a 60 Hz display gives every other refresh). */
internal const val HERD_FPS = 30
private const val HERD_FRAME_NS = 1_000_000_000L / HERD_FPS - 4_000_000L

/** Whether a frame at [nanos] draws, the last having drawn at [drawnNanos] (negative: none yet). */
internal fun herdDue(nanos: Long, drawnNanos: Long): Boolean =
    drawnNanos < 0 || nanos - drawnNanos >= HERD_FRAME_NS

/**
 * The sheep hopping a fence, on a surface of their own (issue #48). A view in
 * the window redraws the whole window each frame, so the sheep on the screen's
 * own canvas cost a full redraw per frame. This surface is composited by the
 * system on its own and drawn from a thread of its own at the display's rate:
 * the main thread and the rest of the window do nothing while the sheep move.
 *
 * It draws only while its surface exists (the view is on a visible, started
 * screen); the window keeps a hole where it sits, so whatever is drawn over that
 * spot (the composer, a sheet) still covers it.
 */
internal class HerdSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    @Volatile private var sheep = 0
    @Volatile private var fence = 0
    @Volatile private var bg = 0
    private var thread: HandlerThread? = null
    private val px = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f * px
    }

    init {
        // Decoration: the verb beside it says the agent works.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // 32-bit and opaque: OPAQUE alone gives 16-bit colour, its background a shade off the chat's.
        holder.setFormat(PixelFormat.RGBX_8888)
        holder.addCallback(this)
    }

    /** The colours, from the theme (ARGB). */
    fun paint(sheep: Int, fence: Int, bg: Int) {
        this.sheep = sheep
        this.fence = fence
        this.bg = bg
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // The first frame at once: a new surface is black until drawn.
        draw(holder, 0f)
        val t = HandlerThread("herd").apply { start() }
        thread = t
        Handler(t.looper).post {
            val clock = Choreographer.getInstance()
            var start = -1L
            var drawn = -1L
            val frame = object : Choreographer.FrameCallback {
                override fun doFrame(nanos: Long) {
                    if (start < 0) start = nanos
                    if (herdDue(nanos, drawn)) {
                        drawn = nanos
                        val ms = (nanos - start) / 1_000_000
                        draw(holder, (ms % HOP_MS) / HOP_MS.toFloat())
                    }
                    clock.postFrameCallback(this)
                }
            }
            clock.postFrameCallback(frame)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    /** No drawing after this returns: the thread is ended and waited for. */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        thread?.let {
            it.quit()
            it.join()
        }
        thread = null
    }

    private fun draw(holder: SurfaceHolder, t: Float) {
        val canvas: Canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(bg)
            val w = canvas.width.toFloat()
            val ground = canvas.height - 2 * px
            val r = 2.6f * px
            val fx = w / 2
            val post = 7 * px
            line.color = fence
            canvas.drawLine(fx - 3 * px, ground, fx - 3 * px, ground - post, line)
            canvas.drawLine(fx + 3 * px, ground, fx + 3 * px, ground - post, line)
            canvas.drawLine(fx - 5 * px, ground - post * 0.6f, fx + 5 * px, ground - post * 0.6f, line)
            for (k in 0 until 3) {
                val s = sheepAt(t, k, w, r, px)
                fill.color = sheep
                fill.alpha = (android.graphics.Color.alpha(sheep) * s.alpha).toInt()
                canvas.drawCircle(s.x, ground - r - s.lift, r, fill)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}
