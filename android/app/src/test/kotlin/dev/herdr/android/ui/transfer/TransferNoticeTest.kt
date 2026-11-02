package dev.herdr.android.ui.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The inline notice on Machines after an export or import: what it says, and
// whether it reads as an error. Pinned here so a wording change is deliberate.
@RunWith(RobolectricTestRunner::class)
class TransferNoticeTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private fun text(result: TransferResult) = transferNotice(result, res).text

    @Test
    fun exportDone() {
        val notice = transferNotice(TransferResult.Exported, res)
        assertEquals("Machine list exported, without keys or host keys.", notice.text)
        assertFalse(notice.isError)
    }

    @Test
    fun exportFailed() {
        val notice = transferNotice(TransferResult.ExportFailed("the storage app refused the file"), res)
        assertEquals("Could not export the machine list: the storage app refused the file.", notice.text)
        assertTrue(notice.isError)
        assertEquals("Could not export the machine list.", text(TransferResult.ExportFailed(null)))
    }

    @Test
    fun importedCounts() {
        assertEquals("Added 1 machine.", text(TransferResult.Imported(1)))
        assertEquals("Added 3 machines.", text(TransferResult.Imported(3)))
        assertEquals("Nothing added: every machine in the file is already listed.", text(TransferResult.Imported(0)))
        assertFalse(transferNotice(TransferResult.Imported(0), res).isError)
    }

    @Test
    fun importErrorsAreErrorsAndSayNothingWasImported() {
        val errors = mapOf(
            TransferResult.FileTooLarge to "Nothing imported: the file is over 1 MB, too large for a machine list.",
            TransferResult.FileEmpty to "Nothing imported: the file is empty.",
            TransferResult.NotAList to "Nothing imported: this app cannot read this file as a machine list.",
            TransferResult.ImportFailed("the port 0 is not a port") to "Nothing imported: the port 0 is not a port.",
            TransferResult.ReadFailed("the file is gone") to "Nothing imported: the file could not be read (the file is gone).",
            TransferResult.ReadFailed(null) to "Nothing imported: the file could not be read.",
            TransferResult.NoPicker to "No file picker found on this phone.",
        )
        for ((result, expected) in errors) {
            val notice = transferNotice(result, res)
            assertEquals(expected, notice.text)
            assertTrue("$result reads as an error", notice.isError)
        }
    }
}
