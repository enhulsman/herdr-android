package dev.herdr.android.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

// accessibility spec, "A screen reader can read the terminal": the visible
// grid as lines, box drawing, block elements and braille spinner marks
// removed, runs of spaces folded, empty lines left out.
class SpokenScreenTest {
    @Test
    fun aBoxedPermissionPromptReadsAsItsQuestionAndChoices() {
        val screen = """
            ╭──────────────────────────────────────────╮
            │ Bash command                             │
            │                                          │
            │   rm -r build                            │
            │                                          │
            │ Do you want to proceed?                  │
            │ ❯ 1. Yes                                 │
            │   2. No, and tell Claude what to do      │
            ╰──────────────────────────────────────────╯
        """.trimIndent()
        assertEquals(
            "Bash command\nrm -r build\nDo you want to proceed?\n❯ 1. Yes\n2. No, and tell Claude what to do",
            spokenScreen(screen),
        )
    }

    @Test
    fun spinnersAndBlocksGoSpacesFold() {
        assertEquals("Thinking… (12s)", spokenScreen("⠋  Thinking…    (12s)"))
        assertEquals("75%", spokenScreen("█████▌░░ 75%"))
        assertEquals("a b", spokenScreen("──── a ┃ b ────"))
    }

    @Test
    fun emptyLinesAndAnEmptyScreen() {
        assertEquals("one\ntwo", spokenScreen("one\n\n   \n│   │\ntwo\n"))
        assertEquals("", spokenScreen(""))
        assertEquals("", spokenScreen("╭──╮\n╰──╯"))
    }

    @Test
    fun ordinaryTextIsKept() {
        assertEquals("$ ls -la ~/src | grep -v x", spokenScreen("$ ls -la ~/src | grep -v x"))
        assertEquals("→ 3 files, ✓ done", spokenScreen("→ 3 files, ✓ done"))
    }
}
