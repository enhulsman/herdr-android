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
// the compose key's square pen (lucide square-pen, design/renders/icons/), the
// keyboard key's keyboard (lucide keyboard) and
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
    // lucide keyboard (ISC), the rectangle as a path.
    Keyboard(
        2f,
        listOf(
            "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M6 8h.01", "M10 8h.01", "M14 8h.01", "M18 8h.01",
            "M8 12h.01", "M12 12h.01", "M16 12h.01",
            "M7 16h10",
        ),
    ),
    // The Agents top bar's "New" (`ico.plus`) and the sheet's Terminal (`ico.shell`).
    Plus(1.8f, listOf("M12 5v14", "M5 12h14")),
    Shell(2f, listOf("M4 17l6-6-6-6", "M12 19h8")),
    // lucide copy (ISC), the square as a path; spike for issue 17 (the chat's copy button).
    Copy(
        2f,
        listOf(
            "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z",
            "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
        ),
    ),
    Eye(
        1.8f,
        listOf(
            "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z",
            "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
        ),
    ),
    // Spike for issue 17 (the chat's tool sheet), lucide (ISC): search, file,
    // x, arrow-left, a check-and-circle list for agents, wrench; circles as two arcs.
    Search(2f, listOf("M19 11a8 8 0 1 1-16 0a8 8 0 1 1 16 0z", "m21 21-4.3-4.3")),
    File(2f, listOf("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z", "M14 2v4a2 2 0 0 0 2 2h4")),
    Close(2f, listOf("M18 6 6 18", "m6 6 12 12")),
    Back(2f, listOf("m12 19-7-7 7-7", "M19 12H5")),
    Tasks(2f, listOf("m3 7 2 2 4-4", "M8 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0z", "M13 7h8", "M13 17h8")),
    Wrench(
        2f,
        listOf(
            "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91" +
                "a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z",
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
