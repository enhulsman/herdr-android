package dev.herdr.android.ui.transfer

import dev.herdr.android.hub.HubHost
import dev.herdr.android.ui.flows.hubErrorText
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.herdr_mobile.HubException
import uniffi.herdr_mobile.HubInterface

/** The most an import reads; a machine list is a few hundred bytes per machine. */
const val MAX_IMPORT_BYTES = 1 shl 20

/**
 * Where the file picker's documents are read and written; [ContentResolverStore]
 * on the phone, an in-memory fake in tests. A document is the picker's URI as a
 * string. Both calls block and throw [IOException] when the provider fails.
 */
interface DocumentStore {
    /** Replaces the document's content with [text] as UTF-8. */
    fun write(document: String, text: String)

    /** The document's bytes, but never more than [limit] + 1, so a larger file shows as one. */
    fun read(document: String, limit: Int): ByteArray
}

/** How the last export or import ended; [transferNotice] puts it in words. */
sealed interface TransferResult {
    data object Exported : TransferResult

    data class ExportFailed(val reason: String?) : TransferResult

    /** The hub added [added] machines; the rest of the file was listed already. */
    data class Imported(val added: Int) : TransferResult

    data object FileTooLarge : TransferResult

    data object FileEmpty : TransferResult

    /** Not text, not JSON, or not this app's export. */
    data object NotAList : TransferResult

    /** The hub refused the list as a whole (a machine in it is invalid, or saving failed). */
    data class ImportFailed(val reason: String) : TransferResult

    data class ReadFailed(val reason: String?) : TransferResult

    /** No app on the phone answers the picker intent. */
    data object NoPicker : TransferResult
}

/**
 * Export and import of the machine list through the system file picker
 * (machine-connection spec: exportable and importable without keys). The
 * route launches the pickers and hands over the chosen document, or null when
 * the picker was cancelled.
 *
 * Process-wide (HerdrApp holds it): each export or import runs once in
 * [scope], which outlives the activity, and publishes how it ended on
 * [result]. A rotation while a file is read or the hub imports does not cancel
 * the work, and the recreated screen reads the outcome from [result].
 *
 * What it keeps: an export is exactly the hub's export JSON (no keys, no host
 * keys). An import reads at most [MAX_IMPORT_BYTES] before anything is parsed,
 * passes the text to the hub, which adds names not listed yet and nothing it
 * cannot use as a whole, and calls nothing else: an imported machine has no
 * host key and asks for one on its first connection.
 */
class MachineTransfer(
    private val hubHost: HubHost,
    private val store: DocumentStore,
    /** App-scoped, off the main thread (HerdrApp: SupervisorJob + Dispatchers.IO). */
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<TransferResult?>(null)
    val result: StateFlow<TransferResult?> = state.asStateFlow()

    /** Writes the export to [document]; null (picker cancelled) does nothing. */
    fun export(document: String?): Job? = document?.let { scope.launch { state.value = exportTo(it) } }

    /** Imports [document]; null (picker cancelled) does nothing. */
    fun import(document: String?): Job? = document?.let { scope.launch { state.value = importFrom(it) } }

    /** A picker is opening: the last notice no longer applies, also if this one is cancelled. */
    fun pickerOpened() {
        state.value = null
    }

    /** The picker could not be opened. */
    fun noPicker() {
        state.value = TransferResult.NoPicker
    }

    fun dismiss() {
        state.value = null
    }

    private fun exportTo(document: String): TransferResult {
        val hub = hub() ?: return TransferResult.ExportFailed(notRunning())
        return try {
            store.write(document, hub.exportMachines())
            TransferResult.Exported
        } catch (e: IOException) {
            TransferResult.ExportFailed(e.message)
        } catch (e: SecurityException) {
            TransferResult.ExportFailed(e.message)
        }
    }

    private fun importFrom(document: String): TransferResult {
        val bytes = try {
            store.read(document, MAX_IMPORT_BYTES)
        } catch (e: IOException) {
            return TransferResult.ReadFailed(e.message)
        } catch (e: SecurityException) {
            return TransferResult.ReadFailed(e.message)
        }
        if (bytes.size > MAX_IMPORT_BYTES) return TransferResult.FileTooLarge
        val text = utf8(bytes)?.removePrefix(BOM) ?: return TransferResult.NotAList
        if (text.isBlank()) return TransferResult.FileEmpty
        val hub = hub() ?: return TransferResult.ImportFailed(notRunning())
        return try {
            TransferResult.Imported(hub.importMachines(text).toInt())
        } catch (e: HubException.Unreadable) {
            TransferResult.NotAList
        } catch (e: HubException) {
            TransferResult.ImportFailed(hubErrorText(e))
        }
    }

    private fun hub(): HubInterface? = hubHost.hub ?: run {
        hubHost.ensureStarted()
        hubHost.hub
    }

    private fun notRunning(): String = hubHost.startError.value ?: "the connection core is not running"

    private companion object {
        const val BOM = "﻿"

        /** Strict UTF-8: a photo or other binary file is not a list, not a string of replacement characters. */
        fun utf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
