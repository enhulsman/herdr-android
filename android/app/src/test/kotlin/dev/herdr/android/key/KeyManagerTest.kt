package dev.herdr.android.key

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.SignException

// machine-connection spec, "The phone key is hardware-bound and needs the
// owner" ("Key survives an update"): the key is created once and reused, and an
// error never deletes or re-creates it. The Keystore sits behind KeyBackend; the
// fake keeps its keys across KeyManager instances, as the Keystore keeps them
// across process restarts.
class KeyManagerTest {
    private val backend = FakeBackend()

    @Test
    fun theKeyIsCreatedOnceInStrongBoxAndReusedAfterARestart() {
        val first = KeyManager(backend).publicKey()
        val afterRestart = KeyManager(backend).publicKey()
        assertEquals(listOf(Generate(KeyManager.ALIAS, strongBox = true)), backend.generated)
        assertArrayEquals(first, afterRestart)
    }

    @Test
    fun withoutStrongBoxTheKeyGoesToTheTeeOnce() {
        backend.strongBox = false
        KeyManager(backend).publicKey()
        KeyManager(backend).publicKey()
        assertEquals(
            listOf(Generate(KeyManager.ALIAS, strongBox = true), Generate(KeyManager.ALIAS, strongBox = false)),
            backend.attempts,
        )
        assertEquals(listOf(Generate(KeyManager.ALIAS, strongBox = false)), backend.generated)
    }

    @Test
    fun anUnreadableExistingKeyIsReportedNeverReplaced() {
        KeyManager(backend).publicKey()
        backend.readFails = true
        val e = assertThrows(KeyUnavailableException::class.java) { KeyManager(backend).publicKey() }
        assertTrue(e.message!!.isNotEmpty())
        assertEquals(1, backend.generated.size)
        assertEquals(0, backend.deleted)
    }

    @Test
    fun aKeystoreThatCannotBeOpenedIsReportedWithoutCreatingAKey() {
        backend.containsFails = true
        assertThrows(KeyUnavailableException::class.java) { KeyManager(backend).publicKey() }
        assertEquals(emptyList<Generate>(), backend.attempts)
    }

    @Test
    fun aFailedCreationIsReportedAndNotRetriedWithWeakerSettings() {
        // Only a missing StrongBox falls back to the TEE (as in the spike); any
        // other failure, such as no screen lock, is surfaced.
        backend.generateFails = true
        assertThrows(KeyUnavailableException::class.java) { KeyManager(backend).publicKey() }
        assertEquals(listOf(Generate(KeyManager.ALIAS, strongBox = true)), backend.attempts)
    }

    @Test
    fun thePublicKeyLineMatchesTheOpenSshEncodingOfTheP256Generator() {
        // The P-256 base point (private key 1): a fixed vector.
        val x = "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"
        val y = "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"
        val point = byteArrayOf(4) + x.hexToByteArray() + y.hexToByteArray()
        backend.keys[KeyManager.ALIAS] = P256_SPKI_PREFIX + point
        assertEquals(
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBGsX0fLhLEJH+Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT+NC4v4af5uO5+tKfA+eFivOM1drMV7Oy7ZAaDe/UfU= herdr-android",
            KeyManager(backend).publicKeyLine(),
        )
        assertEquals(expectedLine(point, "herdr-android"), KeyManager(backend).publicKeyLine())
    }

    @Test
    fun thePublicKeyLineMatchesTheOpenSshEncodingOfARandomKey() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val public = pair.public as ECPublicKey
        backend.keys[KeyManager.ALIAS] = public.encoded
        val point = byteArrayOf(4) + public.w.affineX.toFixed32() + public.w.affineY.toFixed32()
        assertEquals(expectedLine(point, "herdr-android"), KeyManager(backend).publicKeyLine())
    }

    @Test
    fun theAuthorizedKeysLineRestrictsThePublicKeyToHerdrCore() {
        val manager = KeyManager(backend)
        val line = manager.authorizedKeysLine()
        assertTrue(line, line.startsWith("restrict,command=\""))
        assertTrue(line, line.endsWith("\" " + manager.publicKeyLine()))
    }

    @Test
    fun theSignerReturnsTheKeystoreSignature() {
        val manager = KeyManager(backend)
        manager.publicKey()
        assertArrayEquals(byteArrayOf(1, 2, 3) + "data".toByteArray(), manager.signer().sign("data".toByteArray()))
    }

    @Test
    fun anExpiredAuthenticationWindowAsksForTheUser() {
        val manager = KeyManager(backend)
        manager.publicKey()
        backend.signFailure = KeyNeedsUserAuthException()
        assertThrows(SignException.NeedsUserAuth::class.java) { manager.signer().sign(byteArrayOf(0)) }
    }

    @Test
    fun anInvalidatedKeyFailsToSignAndIsKept() {
        val manager = KeyManager(backend)
        manager.publicKey()
        backend.signFailure = KeyInvalidatedException(IllegalStateException("lock screen removed"))
        val e = assertThrows(SignException.Failed::class.java) { manager.signer().sign(byteArrayOf(0)) }
        assertTrue(e.detail, e.detail.contains("can no longer be used"))
        assertEquals(1, backend.generated.size)
        assertEquals(0, backend.deleted)
        assertTrue(backend.keys.containsKey(KeyManager.ALIAS))
    }

    @Test
    fun anyOtherSigningErrorFailsWithItsDetail() {
        val manager = KeyManager(backend)
        manager.publicKey()
        backend.signFailure = IllegalStateException("keystore busy")
        val e = assertThrows(SignException.Failed::class.java) { manager.signer().sign(byteArrayOf(0)) }
        assertTrue(e.detail, e.detail.contains("keystore busy"))
    }

    @Test
    fun theSignerIsOneObject() {
        val manager = KeyManager(backend)
        assertSame(manager.signer(), manager.signer())
    }

    private fun BigInteger.toFixed32(): ByteArray {
        val bytes = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }

    /** `ecdsa-sha2-nistp256 <base64(string alg, string curve, string point)> comment` (RFC 5656). */
    private fun expectedLine(point: ByteArray, comment: String): String {
        val out = ByteArrayOutputStream()
        fun string(b: ByteArray) {
            out.write(java.nio.ByteBuffer.allocate(4).putInt(b.size).array())
            out.write(b)
        }
        string("ecdsa-sha2-nistp256".toByteArray())
        string("nistp256".toByteArray())
        string(point)
        return "ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(out.toByteArray())} $comment"
    }
}

private val P256_SPKI_PREFIX = (
    "3059301306072a8648ce3d020106082a8648ce3d030107034200"
    ).hexToByteArray()

private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

data class Generate(val alias: String, val strongBox: Boolean)

/** The Keystore in memory. It has no delete: the app never needs one. */
class FakeBackend : KeyBackend {
    val keys = mutableMapOf<String, ByteArray>()
    val attempts = mutableListOf<Generate>()
    val generated = mutableListOf<Generate>()
    val deleted = 0
    var strongBox = true
    var readFails = false
    var containsFails = false
    var generateFails = false
    var signFailure: Exception? = null

    override fun contains(alias: String): Boolean {
        if (containsFails) throw java.security.KeyStoreException("AndroidKeyStore not found")
        return alias in keys
    }

    override fun publicKey(alias: String): ByteArray {
        if (readFails) throw java.security.UnrecoverableKeyException("cannot read")
        return keys.getValue(alias)
    }

    override fun generate(alias: String, strongBox: Boolean) {
        attempts += Generate(alias, strongBox)
        if (generateFails) throw IllegalStateException("Secure lock screen must be enabled")
        if (strongBox && !this.strongBox) throw StrongBoxMissingException()
        generated += Generate(alias, strongBox)
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        keys[alias] = pair.public.encoded
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        signFailure?.let { throw it }
        check(alias in keys)
        return byteArrayOf(1, 2, 3) + data
    }
}
