package dev.herdr.android.ui.agents

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdr.android.ui.theme.HerdrTheme

// The program marks of render 1: Claude Code and Codex from herdr-app's icons
// (design/renders/icons/claude.svg, codex.svg; Apache-2.0, THIRD_PARTY_NOTICES.md),
// their paths copied with the arc flags written out. Any other program gets a
// small terminal mark in the muted colour (no render: drawn from the tokens).

private const val CLAUDE_PATH =
    "M20.998 10.949H24v3.102h-3v3.028h-1.487V20H18v-2.921h-1.487V20H15v-2.921H9V20H7.488v-2.921H6V20H4.487v-2.921H3V14.05H0V10.95h3V5h17.998v5.949zM6 10.949h1.488V8.102H6v2.847zm10.51 0H18V8.102h-1.49v2.847z"

private const val CODEX_TILE =
    "M 19.503 0 H 4.496 A 4.496 4.496 0 0 0 0 4.496 v 15.007 A 4.496 4.496 0 0 0 4.496 24 h 15.007 " +
        "A 4.496 4.496 0 0 0 24 19.503 V 4.496 A 4.496 4.496 0 0 0 19.503 0 z"

private const val CODEX_MARK =
    "M 9.064 3.344 a 4.578 4.578 0 0 1 2.285 -.312 c 1 .115 1.891 .54 2.673 1.275 .01 .01 .024 .017 .037 .021 " +
        "a .09 .09 0 0 0 .043 0 4.55 4.55 0 0 1 3.046 .275 l .047 .022 .116 .057 a 4.581 4.581 0 0 1 2.188 2.399 " +
        "c .209 .51 .313 1.041 .315 1.595 a 4.24 4.24 0 0 1 -.134 1.223 .123 .123 0 0 0 .03 .115 " +
        "c .594 .607 .988 1.33 1.183 2.17 .289 1.425 -.007 2.71 -.887 3.854 l -.136 .166 " +
        "a 4.548 4.548 0 0 1 -2.201 1.388 .123 .123 0 0 0 -.081 .076 c -.191 .551 -.383 1.023 -.74 1.494 " +
        "-.9 1.187 -2.222 1.846 -3.711 1.838 -1.187 -.006 -2.239 -.44 -3.157 -1.302 a .107 .107 0 0 0 -.105 -.024 " +
        "c -.388 .125 -.78 .143 -1.204 .138 a 4.441 4.441 0 0 1 -1.945 -.466 4.544 4.544 0 0 1 -1.61 -1.335 " +
        "c -.152 -.202 -.303 -.392 -.414 -.617 a 5.81 5.81 0 0 1 -.37 -.961 4.582 4.582 0 0 1 -.014 -2.298 " +
        ".124 .124 0 0 0 .006 -.056 .085 .085 0 0 0 -.027 -.048 4.467 4.467 0 0 1 -1.034 -1.651 " +
        "3.896 3.896 0 0 1 -.251 -1.192 5.189 5.189 0 0 1 .141 -1.6 c .337 -1.112 .982 -1.985 1.933 -2.618 " +
        ".212 -.141 .413 -.251 .601 -.33 .215 -.089 .43 -.164 .646 -.227 a .098 .098 0 0 0 .065 -.066 " +
        "4.51 4.51 0 0 1 .829 -1.615 4.535 4.535 0 0 1 1.837 -1.388 z m 3.482 10.565 a .637 .637 0 0 0 0 1.272 " +
        "h 3.636 a .637 .637 0 1 0 0 -1.272 h -3.636 z M 8.462 9.23 a .637 .637 0 0 0 -1.106 .631 " +
        "l 1.272 2.224 -1.266 2.136 a .636 .636 0 1 0 1.095 .649 l 1.454 -2.455 a .636 .636 0 0 0 .005 -.64 L 8.462 9.23 z"

private val ClaudeIcon: ImageVector = ImageVector.Builder("claude", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(CLAUDE_PATH), fill = SolidColor(Color(0xFFD97757)), pathFillType = PathFillType.EvenOdd)
    .build()

private val CodexIcon: ImageVector = ImageVector.Builder("codex", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(CODEX_TILE), fill = SolidColor(Color.White))
    .addPath(
        addPathNodes(CODEX_MARK),
        fill = Brush.verticalGradient(
            0f to Color(0xFFB1A7FF),
            0.5f to Color(0xFF7A9DFF),
            1f to Color(0xFF3941FF),
            startY = 3f,
            endY = 21f,
        ),
    )
    .build()

/** A rounded screen with a prompt, stroked like the herdr-app line icons (1.5 on 24). */
private fun terminalIcon(color: Color): ImageVector = ImageVector.Builder("terminal", 24.dp, 24.dp, 24f, 24f)
    .path(stroke = SolidColor(color), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5.5f, 4f)
        horizontalLineTo(18.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 21f, 6.5f)
        verticalLineTo(17.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 18.5f, 20f)
        horizontalLineTo(5.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 3f, 17.5f)
        verticalLineTo(6.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 5.5f, 4f)
        close()
    }
    .path(stroke = SolidColor(color), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(7.5f, 9.5f)
        lineTo(10f, 12f)
        lineTo(7.5f, 14.5f)
        moveTo(12.5f, 15f)
        horizontalLineTo(16.5f)
    }
    .build()

/** The program's mark, 20 dp in a row (components.agentRow). */
@Composable
fun ProgramMark(icon: ProgramIcon, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val vector = when (icon) {
        ProgramIcon.Claude -> ClaudeIcon
        ProgramIcon.Codex -> CodexIcon
        ProgramIcon.Terminal -> terminalIcon(HerdrTheme.colors.muted)
    }
    Image(vector, contentDescription = null, modifier = modifier.size(size))
}
