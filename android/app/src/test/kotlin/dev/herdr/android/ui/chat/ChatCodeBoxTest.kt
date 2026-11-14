package dev.herdr.android.ui.chat

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// Spike for issue 17 (never merged). Inline code that wraps onto the next line
// got a box over the whole line before it (seen on the phone): each box must
// cover only the code's own characters on its line.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatCodeBoxTest {
    private fun layout(src: String, widthPx: Int) = run {
        val text = inline(src)
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), Density(1f), LayoutDirection.Ltr)
        text to measurer.measure(text, TextStyle(fontSize = 16.sp), constraints = Constraints(maxWidth = widthPx))
    }

    @Test
    fun aWrappedCodeSpanBoxesOnlyItsOwnCharacters() {
        // Wrapped after a slash, no space at the break: as on the phone.
        val (text, l) = layout("Committed on the branch `spike/chat-view/with-a-longer-name` today", widthPx = 300)
        val code = text.getStringAnnotations(CODE_TAG, 0, text.length)
        val first = l.getLineForOffset(code[0].start)
        val last = l.getLineForOffset(code[0].end - 1)
        assertTrue("the case needs the code to wrap", last > first)
        val boxes = codeBoxes(l, code)
        assertEquals(last - first + 1, boxes.size)
        // First line: starts at the code, not at the line's start, and does not run back past it.
        val codeLeft = l.getBoundingBox(code[0].start).left
        assertEquals(codeLeft, boxes.first().left, 0.5f)
        assertTrue("first box ends right of where it starts", boxes.first().right > boxes.first().left)
        // Last line: from the line's start to the code's last character, not beyond.
        assertEquals(l.getBoundingBox(code[0].end - 1).right, boxes.last().right, 0.5f)
        assertTrue(boxes.last().left <= l.getBoundingBox(l.getLineStart(last)).left + 0.5f)
    }

    @Test
    fun codeOnOneLineGetsOneBoxAroundIt() {
        val (text, l) = layout("Run `ls` now", widthPx = 1000)
        val code = text.getStringAnnotations(CODE_TAG, 0, text.length)
        val b = codeBoxes(l, code).single()
        assertEquals(l.getBoundingBox(code[0].start).left, b.left, 0.5f)
        assertEquals(l.getBoundingBox(code[0].end - 1).right, b.right, 0.5f)
    }
}
