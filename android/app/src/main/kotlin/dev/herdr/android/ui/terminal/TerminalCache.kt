package dev.herdr.android.ui.terminal

import dev.herdr.android.ui.agents.AgentKey
import java.io.File
import java.security.MessageDigest

// The instant-open cache (terminal-view spec, "Opening is instant"): the last
// full frame of each terminal, so opening it shows its last screen until the
// live first paint arrives. App-private, sealed by a [FrameCipher] (on the
// phone AES-GCM under a Keystore key bound to the same 30-minute user
// authentication as the phone key: KeystoreFrameCipher), wiped when its
// machine is removed and after 7 days unused. Tested with a fake cipher
// (TerminalCacheTest). Blocking file and Keystore work: call it off the main
// thread.

/** Seals frames for the cache. */
interface FrameCipher {
    /** The sealed bytes, or null when the key cannot be used now (its authentication window closed). */
    fun seal(plain: ByteArray): ByteArray?

    /** The frame, or null when the key cannot be used now; throws when [sealed] is not something it sealed. */
    fun open(sealed: ByteArray): ByteArray?
}

/** [dir] holds a folder per machine and a file per terminal, both named by hash. */
class TerminalCache(private val dir: File, private val cipher: FrameCipher, private val now: () -> Long) {

    fun load(key: AgentKey): ByteArray? {
        val file = file(key)
        if (!file.isFile) return null
        if (stale(file)) {
            delete(file)
            return null
        }
        val frame = try {
            cipher.open(file.readBytes()) ?: return null
        } catch (e: Exception) {
            // Not something the key sealed (a new key, a torn write): never shown.
            delete(file)
            return null
        }
        file.setLastModified(now())
        return frame
    }

    /** Keeps [frame] as [key]'s last screen; skipped while the key cannot be used. */
    fun store(key: AgentKey, frame: ByteArray) {
        val sealed = try {
            cipher.seal(frame)
        } catch (e: Exception) {
            null
        } ?: return
        val file = file(key)
        file.parentFile?.mkdirs()
        // Written aside and renamed, so a load never reads half a frame.
        val part = File(file.parentFile, file.name + ".part")
        part.writeBytes(sealed)
        if (!part.renameTo(file)) {
            part.delete()
            return
        }
        file.setLastModified(now())
    }

    /** Wipes the terminals of every machine not in [machines] (a machine removed or renamed). */
    fun keepMachines(machines: Set<String>) {
        val keep = machines.map(::hash).toSet()
        dir.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    /** Wipes every terminal unused for 7 days. */
    fun prune() {
        dir.listFiles()?.filter { it.isDirectory }?.forEach { machine ->
            machine.listFiles()?.filter { it.isFile && stale(it) }?.forEach(::delete)
            if (machine.list()?.isEmpty() == true) machine.delete()
        }
    }

    private fun stale(file: File) = now() - file.lastModified() > MAX_UNUSED_MS

    private fun delete(file: File) {
        file.delete()
        file.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
    }

    private fun file(key: AgentKey) = File(File(dir, hash(key.machine)), hash(key.address))

    private fun hash(name: String): String =
        MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        const val MAX_UNUSED_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
