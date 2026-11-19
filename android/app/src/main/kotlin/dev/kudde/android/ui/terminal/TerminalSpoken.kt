package dev.kudde.android.ui.terminal

/**
 * The terminal's visible text as a screen reader should hear it
 * (accessibility spec, "A screen reader can read the terminal"): box
 * drawing, block elements and braille spinner marks removed, runs of spaces
 * folded to one, empty lines left out.
 */
fun spokenScreen(text: String): String =
    text.lineSequence()
        .map { line ->
            line.filterNot(::isDecoration).replace(SPACES, " ").trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

private val SPACES = Regex(" {2,}")

/** Box drawing (U+2500-257F), block elements (U+2580-259F), braille patterns (U+2800-28FF). */
private fun isDecoration(c: Char): Boolean = c in '─'..'▟' || c in '⠀'..'⣿'
