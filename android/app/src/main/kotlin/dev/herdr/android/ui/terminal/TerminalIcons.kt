package dev.herdr.android.ui.terminal

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The terminal screen's line icons, as in design/renders/screens.html (`ico`):
// the compose key's square pen (lucide square-pen, design/renders/icons/) and
// the watching chip's eye. Stroked in the caller's colour.

enum class LineIcon(val strokeWidth: Float, val paths: List<String>) {
    Pen(
        2f,
        listOf(
            "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
            "M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873" +
                "a2 2 0 0 1 .506-.852z",
        ),
    ),
    Eye(
        1.8f,
        listOf(
            "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z",
            "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
        ),
    ),
}

@Composable
fun LineIconImage(icon: LineIcon, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val vector = remember(icon, color) {
        val builder = ImageVector.Builder(icon.name, 24.dp, 24.dp, 24f, 24f)
        for (path in icon.paths) {
            builder.addPath(
                addPathNodes(path),
                stroke = SolidColor(color),
                strokeLineWidth = icon.strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        builder.build()
    }
    Image(vector, contentDescription = null, modifier = modifier.size(size))
}
