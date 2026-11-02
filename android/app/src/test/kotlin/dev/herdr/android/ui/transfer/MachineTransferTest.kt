package dev.herdr.android.ui.transfer

import dev.herdr.android.hub.FakeHub
import dev.herdr.android.hub.HubHost
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.HubException

// Export and import through the system file picker (machine-connection spec:
// "The list SHALL be exportable and importable without keys"; scenario
// "Restoring the list on a new install"). The coordinator writes exactly what
// the hub exports, reads at most MAX_IMPORT_BYTES before anything is parsed,
// and hands the text to the hub's import, which adds new names only and never
// trusts a host key. Anything it cannot use leaves the list alone.
class MachineTransferTest {
    /** A document store in memory; documents named in [broken] fail as a provider would. */
    private class FakeStore : DocumentStore {
        val files = mutableMapOf<String, ByteArray>()
        val broken = mutableSetOf<String>()
        val reads = mutableListOf<Pair<String, Int>>()

        override fun write(document: String, text: String) {
            if (document in broken) throw IOException("the storage app refused the file")
            files[document] = text.toByteArray(Charsets.UTF_8)
        }

        override fun read(document: String, limit: Int): ByteArray {
            reads += document to limit
            if (document in broken) throw IOException("the file is gone")
            val bytes = files[document] ?: throw IOException("no such file")
            // As the real store: never more than one byte past the limit.
            return bytes.copyOf(minOf(bytes.size, limit + 1))
        }
    }

    private val hub = FakeHub()
    private val hubHost = HubHost(createHub = { hub }, mainExecutor = Executor { it.run() })
    private val store = FakeStore()
    private val transfer = MachineTransfer(hubHost, store, CoroutineScope(Dispatchers.Unconfined))

    init {
        hubHost.start()
    }

    private val export = """
        {
          "kind": "herdr-android machines",
          "version": 1,
          "machines": [
            { "name": "workstation", "host": "workstation.example.org", "port": 22, "user": "dev" },
            { "name": "server", "host": "192.0.2.10", "port": 2200, "user": "dev" }
          ]
        }
    """.trimIndent()

    @Test
    fun exportWritesExactlyTheHubsJson() = runBlocking {
        hub.exportJson = export
        transfer.export("content://docs/herdr-machines.json")
        assertArrayEquals(export.toByteArray(Charsets.UTF_8), store.files["content://docs/herdr-machines.json"])
        assertEquals(TransferResult.Exported, transfer.result.value)
        assertEquals(listOf("export"), hub.calls)
    }

    @Test
    fun aFailedWriteSaysWhy() = runBlocking {
        store.broken += "content://docs/out.json"
        transfer.export("content://docs/out.json")
        assertEquals(TransferResult.ExportFailed("the storage app refused the file"), transfer.result.value)
    }

    @Test
    fun exportWithoutARunningHubWritesNothing() = runBlocking {
        val down = HubHost(createHub = { throw IllegalStateException("no screen lock is set") }, mainExecutor = Executor { it.run() })
        val t = MachineTransfer(down, store, CoroutineScope(Dispatchers.Unconfined))
        t.export("content://docs/out.json")
        assertTrue(store.files.isEmpty())
        assertEquals(TransferResult.ExportFailed("no screen lock is set"), t.result.value)
    }

    @Test
    fun aCancelledPickerChangesNothing() = runBlocking {
        transfer.export(null)
        transfer.import(null)
        assertTrue(hub.calls.isEmpty())
        assertTrue(store.reads.isEmpty())
        assertNull(transfer.result.value)
    }

    @Test
    fun importHandsTheFileToTheHubAndReportsHowManyWereAdded() = runBlocking {
        store.files["content://docs/in.json"] = export.toByteArray()
        hub.importAdds = 2u
        transfer.import("content://docs/in.json")
        assertEquals(listOf("import $export"), hub.calls)
        assertEquals(TransferResult.Imported(2), transfer.result.value)
    }

    @Test
    fun importNeverTrustsAHostKeyItself() = runBlocking {
        // A file that carries a host key anyway: the hub drops it (machines.rs
        // and the real-hub test below); the app adds no confirm of its own.
        val withKey = export.replace("\"user\": \"dev\" }", "\"user\": \"dev\", \"host_key\": \"SHA256:abc\" }")
        store.files["content://docs/in.json"] = withKey.toByteArray()
        hub.importAdds = 2u
        transfer.import("content://docs/in.json")
        assertEquals(1, hub.calls.size)
        assertTrue(hub.calls.single().startsWith("import "))
        assertTrue(hub.calls.none { it.startsWith("confirm") })
    }

    @Test
    fun nothingNewIsStillAResult() = runBlocking {
        store.files["content://docs/in.json"] = export.toByteArray()
        hub.importAdds = 0u
        transfer.import("content://docs/in.json")
        assertEquals(TransferResult.Imported(0), transfer.result.value)
    }

    @Test
    fun theReadIsCappedBeforeAnythingIsParsed() = runBlocking {
        store.files["content://docs/big.json"] = ByteArray(MAX_IMPORT_BYTES + 10) { ' '.code.toByte() }
        transfer.import("content://docs/big.json")
        assertEquals(listOf("content://docs/big.json" to MAX_IMPORT_BYTES), store.reads)
        assertTrue(hub.calls.isEmpty())
        assertEquals(TransferResult.FileTooLarge, transfer.result.value)
    }

    @Test
    fun aFileOfExactlyTheCapIsRead() = runBlocking {
        val padded = export + " ".repeat(MAX_IMPORT_BYTES - export.length)
        store.files["content://docs/in.json"] = padded.toByteArray()
        hub.importAdds = 2u
        transfer.import("content://docs/in.json")
        assertEquals(TransferResult.Imported(2), transfer.result.value)
    }

    @Test
    fun anEmptyFileIsNotHandedOn() = runBlocking {
        store.files["content://docs/empty.json"] = ByteArray(0)
        transfer.import("content://docs/empty.json")
        assertTrue(hub.calls.isEmpty())
        assertEquals(TransferResult.FileEmpty, transfer.result.value)
    }

    @Test
    fun aBlankFileIsEmptyToo() = runBlocking {
        store.files["content://docs/blank.json"] = " \n\t".toByteArray()
        transfer.import("content://docs/blank.json")
        assertTrue(hub.calls.isEmpty())
        assertEquals(TransferResult.FileEmpty, transfer.result.value)
    }

    @Test
    fun aFileThatIsNotTextIsNotAList() = runBlocking {
        store.files["content://docs/photo.json"] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        transfer.import("content://docs/photo.json")
        assertTrue(hub.calls.isEmpty())
        assertEquals(TransferResult.NotAList, transfer.result.value)
    }

    @Test
    fun aLeadingByteOrderMarkIsDropped() = runBlocking {
        store.files["content://docs/in.json"] = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + export.toByteArray()
        hub.importAdds = 2u
        transfer.import("content://docs/in.json")
        assertEquals(listOf("import $export"), hub.calls)
    }

    @Test
    fun malformedJsonIsNotAListAndAddsNothing() = runBlocking {
        store.files["content://docs/in.json"] = "{ not json".toByteArray()
        hub.refuse = HubException.Unreadable("expected ident at line 1 column 4")
        transfer.import("content://docs/in.json")
        assertEquals(TransferResult.NotAList, transfer.result.value)
    }

    @Test
    fun anInvalidMachineInTheFileSaysWhich() = runBlocking {
        store.files["content://docs/in.json"] = export.toByteArray()
        hub.refuse = HubException.Invalid("the user \"bad user\" has a space")
        transfer.import("content://docs/in.json")
        assertEquals(TransferResult.ImportFailed("the user \"bad user\" has a space"), transfer.result.value)
    }

    @Test
    fun anUnreadableDocumentSaysWhy() = runBlocking {
        store.broken += "content://docs/in.json"
        transfer.import("content://docs/in.json")
        assertTrue(hub.calls.isEmpty())
        assertEquals(TransferResult.ReadFailed("the file is gone"), transfer.result.value)
    }

    @Test
    fun anImportOutlivesTheScreenThatStartedItAndRunsOnce() = runBlocking {
        // Rotation while the file is being read: the screen that launched the
        // picker and watched the result goes away; the import must still finish
        // once, and the recreated screen must see how it ended.
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : DocumentStore {
            override fun write(document: String, text: String) = Unit
            override fun read(document: String, limit: Int): ByteArray {
                reading.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return export.toByteArray()
            }
        }
        val owner = CoroutineScope(Dispatchers.IO)
        val t = MachineTransfer(hubHost, slow, owner)
        hub.importAdds = 2u

        val oldScreen = CoroutineScope(Dispatchers.Default)
        val seenByOldScreen = mutableListOf<TransferResult?>()
        oldScreen.launch { t.result.collect { seenByOldScreen += it } }
        val job = t.import("content://docs/in.json")!!
        assertTrue(reading.await(10, TimeUnit.SECONDS))
        oldScreen.cancel()
        release.countDown()
        job.join()

        val seenByNewScreen = t.result.filterNotNull().first()
        assertEquals(TransferResult.Imported(2), seenByNewScreen)
        assertEquals(1, hub.calls.count { it.startsWith("import ") })
        owner.cancel()
    }

    @Test
    fun startingAPickerClearsTheLastNotice() = runBlocking {
        hub.exportJson = export
        transfer.export("content://docs/out.json")
        assertEquals(TransferResult.Exported, transfer.result.value)
        transfer.pickerOpened()
        assertNull(transfer.result.value)
        // The picker is cancelled: no stale notice comes back.
        transfer.import(null)
        assertNull(transfer.result.value)
    }

    @Test
    fun noFilePickerIsAResultToo() {
        transfer.noPicker()
        assertEquals(TransferResult.NoPicker, transfer.result.value)
        transfer.dismiss()
        assertNull(transfer.result.value)
    }
}
