package dev.herdr.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.ui.theme.HerdrBorder
import dev.herdr.android.ui.theme.HerdrLayout
import dev.herdr.android.ui.theme.HerdrRadius
import dev.herdr.android.ui.theme.HerdrSpacing
import dev.herdr.android.ui.theme.HerdrTheme
import dev.herdr.android.ui.theme.HerdrType

// Small pieces shared by the screens, sized as in design/renders/screens.html
// (.top, .iconbtn, .btn) and design-tokens.json (components.button).

/** The herdr-app chevrons (design/renders/icons/), 24-unit viewBox, stroke 1.8. */
enum class Chevron(val points: List<Offset>) {
    Left(listOf(Offset(15f, 5f), Offset(8f, 12f), Offset(15f, 19f))),
    Right(listOf(Offset(9f, 5f), Offset(16f, 12f), Offset(9f, 19f))),
}

@Composable
fun ChevronIcon(chevron: Chevron, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val unit = this.size.width / 24f
        val path = Path().apply {
            chevron.points.forEachIndexed { i, p ->
                if (i == 0) moveTo(p.x * unit, p.y * unit) else lineTo(p.x * unit, p.y * unit)
            }
        }
        drawPath(path, color, style = Stroke(width = 1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The 60 dp title bar of a screen below the home: a 48 dp back button when
 * [onBack] is given, then the title at 20/600 (render 7 and 8).
 */
@Composable
fun SubScreenTopBar(title: String, backLabel: String, onBack: (() -> Unit)?) {
    val c = HerdrTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(HerdrLayout.topBarHeight)
            .padding(start = if (onBack != null) HerdrSpacing.s4 else HerdrSpacing.s20, end = HerdrSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HerdrSpacing.s4),
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(HerdrRadius.button))
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = backLabel },
                contentAlignment = Alignment.Center,
            ) {
                ChevronIcon(Chevron.Left, 22.dp, c.fg)
            }
        }
        BasicText(
            title,
            style = HerdrType.subScreenTitle.copy(color = c.fg, letterSpacing = HerdrType.screenTitle.letterSpacing),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The outlined button (components.button): 1 dp input border, radius 12. */
@Composable
fun OutlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val c = HerdrTheme.colors
    val shape = RoundedCornerShape(HerdrRadius.button)
    // .btn is 44 high; the compact one in a card's head is drawn 38 high (render
    // 7's Send) inside a 44 dp touch target.
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (compact) Modifier.heightIn(min = 38.dp) else Modifier.heightIn(min = 44.dp).fillMaxWidth())
                .border(HerdrBorder.thin, c.input, shape)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = HerdrType.body.copy(
                    color = c.fg,
                    fontSize = if (compact) 14.sp else 15.sp,
                    lineHeight = HerdrType.settingRow.lineHeight,
                ),
                maxLines = 1,
            )
        }
    }
}
