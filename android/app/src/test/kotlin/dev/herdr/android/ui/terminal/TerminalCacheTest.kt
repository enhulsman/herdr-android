package dev.herdr.android.ui.terminal

import dev.herdr.android.ui.agents.AgentKey
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The instant-open cache (terminal-view spec, "Opening is instant"): the last
// full frame per terminal, sealed by a cipher (the Keystore one on the phone, a
// fake here), wiped with its machine and after 7 days unused.
class TerminalCacheTest {
    private val dir: File = Files.createTempDirectory("terminal-cache").toFile()
    private var now = 1_800_000_000_000L
    private val cipher = FakeCipher()
    private val cache = TerminalCache(dir, cipher) { now }

    private val docs = AgentKey("workstation", "t1")
    private val api = AgentKey("workstation", "t2")
    private val backup = AgentKey("server", "t1")
    private val frame = frameOf(4, 2, mapOf(0 to cellsOf("hi")))

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aStoredFrameComesBack() {
        cache.store(docs, frame)
        assertArrayEquals(frame, cache.load(docs))
        assertNull(cache.load(api))
    }

    @Test
    fun nothingIsStoredInTheClear() {
        cache.store(docs, frame)
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, files.size)
        assertFalse(files.single().readBytes().contentEquals(frame))
        // Neither name appears in the paths.
        assertFalse(files.single().path.contains("workstation"))
        assertFalse(files.single().path.contains("t1"))
    }

    @Test
    fun aClosedAuthenticationWindowSkipsTheCache() {
        cache.store(docs, frame)
        cipher.locked = true
        assertNull(cache.load(docs))
        cache.store(api, frame)
        cipher.locked = false
        // The sealed frame stays for later; nothing was written while locked.
        assertArrayEquals(frame, cache.load(docs))
        assertNull(cache.load(api))
    }

    @Test
    fun anUnreadableEntryIsDropped() {
        cache.store(docs, frame)
        dir.walkTopDown().filter { it.isFile }.single().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.load(docs))
        assertTrue(dir.walkTopDown().none { it.isFile })
    }

    @Test
    fun removingAMachineWipesItsTerminals() {
        cache.store(docs, frame)
        cache.store(api, frame)
        cache.store(backup, frame)
        cache.keepMachines(setOf("server"))
        assertNull(cache.load(docs))
        assertNull(cache.load(api))
        assertArrayEquals(frame, cache.load(backup))
    }

    @Test
    fun entriesUnusedForSevenDaysAreWiped() {
        cache.store(docs, frame)
        cache.store(api, frame)
        now += 6 * DAY
        assertArrayEquals(frame, cache.load(docs)) // opening counts as use
        now += 2 * DAY
        cache.prune()
        assertArrayEquals(frame, cache.load(docs))
        assertNull(cache.load(api))
    }

    @Test
    fun aStaleEntryIsNotShownEvenBeforeAPrune() {
        cache.store(docs, frame)
        now += 7 * DAY + 1
        assertNull(cache.load(docs))
        assertTrue(dir.walkTopDown().none { it.isFile })
    }

    @Test
    fun storingAgainReplacesTheFrame() {
        val newer = frameOf(4, 2, mapOf(0 to cellsOf("new")))
        cache.store(docs, frame)
        cache.store(docs, newer)
        assertArrayEquals(newer, cache.load(docs))
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}

/** Reverses the bytes behind a marker; refuses while [locked], as an expired key would. */
class FakeCipher : FrameCipher {
    var locked = false

    override fun seal(plain: ByteArray): ByteArray? =
        if (locked) null else MARK + plain.reversedArray()

    override fun open(sealed: ByteArray): ByteArray? {
        if (locked) return null
        if (sealed.size < MARK.size || !sealed.copyOf(MARK.size).contentEquals(MARK)) throw IllegalArgumentException("not sealed")
        return sealed.copyOfRange(MARK.size, sealed.size).reversedArray()
    }

    private companion object {
        val MARK = byteArrayOf(0x53, 0x45, 0x41, 0x4c)
    }
}
