package dev.herdr.android.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.herdr_mobile.ChatImage
import uniffi.herdr_mobile.Chat

// Images in the chat (spec "Images show in the chat", "Photos can be
// attached from the phone"). The agent's images are fetched one at a time
// when shown (Chat.image); the person's own go up with terminal.put and their
// paths are pasted before the message.

/** Where the screen gets an image's pixels; a fake in screenshot tests. */
interface ChatImageSource {
    fun cached(key: String): ImageBitmap?
    suspend fun load(key: String): ImageBitmap?
}

/** A photo the person attached, ready to send. */
class Attachment(val name: String, val bytes: ByteArray, val preview: ImageBitmap)

private const val THUMB_HEIGHT = 112

/** A thumbnail's width at the fixed height, its shape kept within limits. */
fun thumbWidth(width: Int, height: Int): Dp =
    (THUMB_HEIGHT * width.toFloat() / height.coerceAtLeast(1)).coerceIn(72f, 240f).dp

/**
 * The long edge to scale an attached photo to before sending, or null to send
 * it as it is: over 2000 px, or over 3.5 MB (an agent's image limit is about
 * 5 MB of base64).
 */
fun uploadLongEdge(width: Int, height: Int, bytes: Int): Int? {
    val long = maxOf(width, height)
    return when {
        long > 2000 -> 2000
        bytes > 3_500_000 -> long
        else -> null
    }
}

internal sealed interface Load {
    data object Loading : Load
    data object Missing : Load
    class Loaded(val image: ImageBitmap) : Load
}

/**
 * The image behind [key]. Remembered per key: produceState keeps its value
 * when the key changes, so a slot handed another image (a row re-keyed after
 * reading back) went on showing the old one while a tap opened the new one.
 */
@Composable
internal fun rememberLoad(source: ChatImageSource, key: String): Load {
    val state = remember(key) {
        mutableStateOf(if (key == "missing") Load.Missing else source.cached(key)?.let { Load.Loaded(it) } ?: Load.Loading)
    }
    LaunchedEffect(key) {
        if (state.value != Load.Loading) return@LaunchedEffect
        state.value = source.load(key)?.let { Load.Loaded(it) } ?: Load.Missing
    }
    return state.value
}

/** A row of thumbnails; a tap opens one full screen. */
@Composable
fun Thumbs(images: List<ChatImage>, source: ChatImageSource, end: Boolean, onOpen: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (end) Alignment.End else Alignment.Start),
    ) {
        for (im in images) key(im.key) { Thumb(im.key, source, onOpen) }
    }
}

@Composable
private fun Thumb(key: String, source: ChatImageSource, onOpen: (String) -> Unit) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    when (val load = rememberLoad(source, key)) {
        is Load.Loaded -> Image(
            load.image, contentDescription = "Image",
            contentScale = ContentScale.Crop,
            modifier = Modifier.height(THUMB_HEIGHT.dp).width(thumbWidth(load.image.width, load.image.height))
                .clip(shape).clickable { onOpen(key) },
        )
        Load.Loading -> Box(Modifier.size(THUMB_HEIGHT.dp).clip(shape).background(c.raised))
        Load.Missing -> Box(Modifier.size(THUMB_HEIGHT.dp).clip(shape).background(c.raised).padding(10.dp), contentAlignment = Alignment.Center) {
            BasicText("Image not available", style = HerdrType.secondary.copy(color = c.dimText, textAlign = TextAlign.Center))
        }
    }
}

/** One image full screen: pinch to zoom, drag when zoomed, double tap to zoom in or out. */
/** How far a finger moves sideways in the viewer to show the next image. */
private val SWIPE = 64.dp

@Composable
fun ImageViewer(key: String, keys: List<String>, source: ChatImageSource, onShow: (String) -> Unit, onClose: () -> Unit) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    val threshold = with(LocalDensity.current) { SWIPE.toPx() }
    val images by rememberUpdatedState(keys)
    val show by rememberUpdatedState(onShow)
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(key) {
                // Two fingers zoom, one pans a zoomed image; at its normal size a
                // sideways swipe moves to the row's next or previous image.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var pinched = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) pinched = true
                        if (pinched || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                        } else {
                            val pan = event.calculatePan()
                            dx += pan.x
                            dy += pan.y
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })
                    val step = if (pinched || scale > 1f) 0 else swipeStep(dx, dy, threshold)
                    if (step != 0) stepImage(images, key, step)?.let(show)
                }
            }
            .pointerInput(key) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val load = rememberLoad(source, key)) {
            is Load.Loaded -> Image(
                load.image, contentDescription = "Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Load.Loading -> BasicText("Loading…", style = HerdrType.body.copy(color = Color.White))
            Load.Missing -> BasicText("Image not available", style = HerdrType.body.copy(color = Color.White))
        }
        Box(
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
                .size(44.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("✕", style = HerdrType.terminalTitle.copy(color = Color.White))
        }
    }
}

/** Attached photos above the message box, each with a remove button. */
@Composable
fun AttachedRow(attached: List<Attachment>, onRemove: (Attachment) -> Unit) {
    val c = HerdrTheme.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in attached) {
            Box {
                Image(a.preview, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape).background(c.bg.copy(alpha = 0.85f)).clickable { onRemove(a) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("✕", style = HerdrType.secondary.copy(color = c.fg))
                }
            }
        }
    }
}

/** Images from the desk, decoded to at most 2048 px and kept by size. */
class DeskImages(private val chat: Chat?) : ChatImageSource {
    private val cache = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }

    override fun cached(key: String): ImageBitmap? = cache.get(key)

    override suspend fun load(key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(key) ?: chat?.image(key)?.let { decodeSampled(it, 2048) }?.also { cache.put(key, it) }
    }
}

/**
 * Decodes at a power-of-two sample: the long edge at most `maxEdge`, or with
 * `atLeast` the smallest sample that still keeps it at `maxEdge` or more.
 */
fun decodeSampled(bytes: ByteArray, maxEdge: Int, atLeast: Boolean = false): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    val long = maxOf(bounds.outWidth, bounds.outHeight)
    if (atLeast) while (long / (sample * 2) >= maxEdge) sample *= 2
    else while (long / sample > maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

private val SENDABLE = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** An attached photo's file name beside the terminal: unique within a pick and across picks. */
fun photoName(stamp: Long, index: Int, ext: String) = "photo-$stamp-$index.$ext"

/** What puts a photo upright: mirrored left to right first if [mirror], then turned [degrees] clockwise. */
data class ExifTurn(val degrees: Float, val mirror: Boolean) {
    val none: Boolean get() = degrees == 0f && !mirror

    fun matrix(): Matrix = Matrix().apply {
        if (mirror) postScale(-1f, 1f)
        postRotate(degrees)
    }
}

/** The turn for a photo's EXIF orientation, the mirrored ones included. */
fun exifTurn(orientation: Int): ExifTurn = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTurn(0f, true)
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTurn(180f, false)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTurn(180f, true)
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTurn(270f, true)
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTurn(90f, false)
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTurn(90f, true)
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTurn(270f, false)
    else -> ExifTurn(0f, false)
}

/** A picked photo as it will be sent: small enough, in a type agents take. */
fun prepareAttachment(context: Context, uri: Uri, index: Int = 0): Attachment? {
    val type = context.contentResolver.getType(uri) ?: "image/jpeg"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    val stamp = System.currentTimeMillis()
    // A camera photo is stored sideways or mirrored with an orientation tag;
    // re-encoding drops the tag, so it is turned upright first (and always re-encoded).
    val turn = runCatching {
        exifTurn(ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    }.getOrDefault(ExifTurn(0f, false))
    val long = uploadLongEdge(bounds.outWidth, bounds.outHeight, bytes.size)
        ?: if (type in SENDABLE && turn.none) null else maxOf(bounds.outWidth, bounds.outHeight).coerceAtMost(2000)
    val (name, out) = if (long == null) {
        photoName(stamp, index, type.substringAfter('/')) to bytes
    } else {
        val full = decodeSampled(bytes, long, atLeast = true)?.asAndroidBitmap() ?: return null
        val k = long.toFloat() / maxOf(full.width, full.height)
        val scaled = if (k < 1f) Bitmap.createScaledBitmap(full, (full.width * k).toInt(), (full.height * k).toInt(), true) else full
        val upright = if (turn.none) scaled else Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, turn.matrix(), true)
        val buf = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, 85, buf)
        photoName(stamp, index, "jpg") to buf.toByteArray()
    }
    val preview = decodeSampled(out, 256) ?: return null
    return Attachment(name, out, preview)
}
