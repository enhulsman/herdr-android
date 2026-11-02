package dev.herdr.android.ui.phonekey

import dev.herdr.android.key.FakeBackend
import dev.herdr.android.key.KeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.authorizedKeysLine

// The phone key line screen shows the restricted authorized_keys line exactly
// as the Rust side builds it (machine-connection spec, "The key is restricted
// to herdr-core on each machine": `restrict` plus a forced `command=`).
class PhoneKeyLineTest {
    @Test
    fun theShownLineIsRestrictedToTheHerdrCoreCommand() {
        val key = phoneKeyLine(KeyManager(FakeBackend()))
        val line = key.line!!
        assertNull(key.error)
        assertTrue(line, line.startsWith("restrict,command=\"sh -c '"))
        assertTrue(line, line.contains("herdr-core"))
        assertTrue(line, line.contains("exec \\\"\$p\\\" connect'\" ecdsa-sha2-nistp256 AAAA"))
        assertTrue(line, line.endsWith(" herdr-android"))
        assertTrue(line, '\n' !in line)
    }

    @Test
    fun theLineIsTheKeyManagersLine() {
        val manager = KeyManager(FakeBackend())
        assertEquals(manager.authorizedKeysLine(), phoneKeyLine(manager).line)
    }

    @Test
    fun aKeyThatCannotBeCreatedShowsWhyInsteadOfALine() {
        val key = phoneKeyLine(KeyManager(FakeBackend().apply { generateFails = true }))
        assertNull(key.line)
        assertTrue(key.error!!, key.error!!.contains("could not be created"))
    }

    @Test
    fun theScreenshotsLineIsWhatRustBuildsForTheExampleKey() {
        assertEquals(ExampleKey.LINE, authorizedKeysLine(ExampleKey.SPKI, "herdr-android"))
    }
}

/** An invented phone key for screenshots: the P-256 base point, which no Keystore hands out. */
object ExampleKey {
    val SPKI: ByteArray = (
        "3059301306072a8648ce3d020106082a8648ce3d03010703420004" +
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    const val LINE = "restrict,command=\"sh -c 'p=\$(command -v herdr-core) || p=\$HOME/.local/bin/herdr-core; " +
        "[ -x \\\"\$p\\\" ] || { echo \\\"herdr-core is not installed here\\\" >&2; exit 127; }; " +
        "exec \\\"\$p\\\" connect'\" ecdsa-sha2-nistp256 " +
        "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBGsX0fLhLEJH+Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT+NC4v4af5uO5+tKfA+eFivOM1drMV7Oy7ZAaDe/UfU= " +
        "herdr-android"
}
