package dev.herdr.android.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

// Spike for issue 17 (never merged): just enough Markdown for agent answers.

sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Item(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

private val TABLE_RULE = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

private fun cells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

fun markdownBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
        para.clear()
    }
    val lines = src.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val depth = (line.length - trimmed.length) / 2
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                out += MdBlock.Code(code.toString().trimEnd())
            }
            trimmed.startsWith("|") && i + 1 < lines.size && TABLE_RULE.matches(lines[i + 1].trim()) -> {
                flush()
                val header = cells(trimmed)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    rows += cells(lines[i]); i++
                }
                i--
                out += MdBlock.Table(header, rows)
            }
            trimmed.startsWith("#") -> {
                flush(); out += MdBlock.Heading(trimmed.trimStart('#').trim())
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flush(); out += MdBlock.Item("•", trimmed.substring(2), depth)
            }
            Regex("^\\d+[.)] ").containsMatchIn(trimmed) -> {
                flush()
                val m = Regex("^(\\d+[.)]) (.*)").find(trimmed)!!
                out += MdBlock.Item(m.groupValues[1], m.groupValues[2], depth)
            }
            trimmed.isEmpty() -> flush()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(trimmed)
            }
        }
        i++
    }
    flush()
    return out
}

/** Marks inline `code`; [MdText] paints its box behind the text, under any selection. */
const val CODE_TAG = "code"

/**
 * `code`, **bold**, *italic*, [text](url) as the text. Code is marked with
 * [CODE_TAG], not given a span background: Compose paints span backgrounds
 * over the selection highlight, which then vanished inside code.
 */
fun inline(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("**", i) && src.indexOf("**", i + 2) > i -> {
                val end = src.indexOf("**", i + 2)
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(src.substring(i + 2, end)); pop()
                i = end + 2
            }
            src[i] == '`' && src.indexOf('`', i + 1) > i -> {
                val end = src.indexOf('`', i + 1)
                pushStringAnnotation(CODE_TAG, ""); pushStyle(SpanStyle(fontFamily = FontFamily.Monospace)); append(src.substring(i + 1, end)); pop(); pop()
                i = end + 1
            }
            src[i] == '*' && i + 1 < src.length && src[i + 1] != ' ' && src.indexOf('*', i + 1) > i -> {
                val end = src.indexOf('*', i + 1)
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(src.substring(i + 1, end)); pop()
                i = end + 1
            }
            src[i] == '[' && Regex("^\\[([^\\]]+)]\\(([^)]+)\\)").find(src.substring(i)) != null -> {
                val m = Regex("^\\[([^\\]]+)]\\(([^)]+)\\)").find(src.substring(i))!!
                pushStyle(SpanStyle(fontWeight = FontWeight.Medium)); append(m.groupValues[1]); pop()
                i += m.value.length
            }
            else -> { append(src[i]); i++ }
        }
    }
}
