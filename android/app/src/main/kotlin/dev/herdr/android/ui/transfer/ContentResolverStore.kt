package dev.herdr.android.ui.transfer

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** [DocumentStore] over the documents the system file picker hands back. */
class ContentResolverStore(private val resolver: ContentResolver) : DocumentStore {
    override fun write(document: String, text: String) {
        val uri = Uri.parse(document)
        openForWriting(uri).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override fun read(document: String, limit: Int): ByteArray {
        val input = resolver.openInputStream(Uri.parse(document))
            ?: throw IOException("the storage app did not open the file")
        // Read at most one byte past the limit: enough to know the file is too large.
        // (InputStream.readNBytes needs API 33; minSdk is 31.)
        return input.use { stream ->
            val buffer = ByteArray(limit + 1)
            var filled = 0
            while (filled < buffer.size) {
                val n = stream.read(buffer, filled, buffer.size - filled)
                if (n < 0) break
                filled += n
            }
            buffer.copyOf(filled)
        }
    }

    /** Truncating ("wt"), falling back to "w" for storage apps that refuse "wt"; the picker's file is new anyway. */
    private fun openForWriting(uri: Uri): OutputStream {
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: resolver.openOutputStream(uri, "w")
        return stream ?: throw IOException("the storage app did not open the file")
    }
}
