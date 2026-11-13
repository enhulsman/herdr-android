package dev.herdr.android.ui.chat

import dev.herdr.android.ui.terminal.LineIcon
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.herdr_mobile.ChatCall

// Spike for issue 17 (never merged): the tool sheet's rows and its heights.
class ChatToolSheetTest {
    private fun call(name: String, title: String, input: String = "{}") = ChatCall(name, title, input, "", true, false, emptyList())

    @Test
    fun rowsSayWhatEachCallDid() {
        assertEquals(SheetRow(LineIcon.Eye, "Read", "upload.rs", mono = true), sheetRow(call("Read", "Read src/upload.rs")))
        assertEquals(SheetRow(LineIcon.File, "Updated", "retry.rs", mono = true), sheetRow(call("Edit", "Edit src/retry.rs")))
        assertEquals(SheetRow(LineIcon.Shell, "Ran", "Find the retry code", mono = false), sheetRow(call("Bash", "Find the retry code", "rg retry")))
        assertEquals("a command with no description is shown as code", SheetRow(LineIcon.Shell, "Ran", "cargo test", mono = true), sheetRow(call("Bash", "cargo test", "cargo test\n")))
        assertEquals(SheetRow(LineIcon.Shell, "Ran", "ls src", mono = true), sheetRow(call("Shell", "! ls src", "ls src")))
        assertEquals(SheetRow(LineIcon.Search, "Searched", "retry\\(", mono = true), sheetRow(call("Grep", "Grep retry\\(")))
        assertEquals(SheetRow(LineIcon.Tasks, "Ran agent", "Check the upload docs", mono = false), sheetRow(call("Agent", "Check the upload docs")))
        assertEquals(SheetRow(LineIcon.Wrench, "WebFetch", "", mono = false), sheetRow(call("WebFetch", "WebFetch")))
    }

    private val stops = listOf(400f, 560f, 860f)

    @Test
    fun aLetGoSheetSettlesOnAFixedHeight() {
        assertEquals(0, sheetSettle(400f, 0f, stops))
        assertEquals("nearest stop", 1, sheetSettle(520f, 0f, stops))
        assertEquals(2, sheetSettle(800f, 0f, stops))
        assertEquals("a fling up goes one stop up", 1, sheetSettle(420f, -2000f, stops))
        assertEquals(2, sheetSettle(860f, -2000f, stops))
        assertEquals("a fling down goes one stop down", 0, sheetSettle(540f, 2000f, stops))
        assertEquals("a fling down from the default closes", -1, sheetSettle(400f, 2000f, stops))
        assertEquals("dragged well below the default closes", -1, sheetSettle(200f, 0f, stops))
        assertEquals("a little below the default comes back", 0, sheetSettle(330f, 0f, stops))
    }
}
