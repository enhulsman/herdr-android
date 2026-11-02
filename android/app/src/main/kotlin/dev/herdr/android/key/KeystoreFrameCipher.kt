package dev.herdr.android.key

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import dev.herdr.android.ui.terminal.FrameCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the instant-open cache's frames with AES-256-GCM under a Keystore key
 * that, like the phone key, works only within 30 minutes of the person
 * authenticating (biometric or device credential; unlocking the phone
 * counts). Outside that window it answers null and the cache is skipped:
 * opening a terminal never asks for authentication by itself (decided by the
 * owner, 2026-11-02). Sealed: version byte, IV length, IV, ciphertext and tag.
 */
class KeystoreFrameCipher(private val alias: String = ALIAS) : FrameCipher {
    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (store().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray? {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        if (!attempt { cipher.init(Cipher.ENCRYPT_MODE, key(create = true)) }) return null
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + body
    }

    override fun open(sealed: ByteArray): ByteArray? {
        require(sealed.size > 2 && sealed[0] == VERSION) { "not a sealed frame" }
        val ivLen = sealed[1].toInt() and 0xFF
        require(sealed.size > 2 + ivLen) { "short sealed frame" }
        val key = key(create = false) ?: throw IllegalStateException("the cache key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = GCMParameterSpec(TAG_BITS, sealed, 2, ivLen)
        if (!attempt { cipher.init(Cipher.DECRYPT_MODE, key, iv) }) return null
        return cipher.doFinal(sealed, 2 + ivLen, sealed.size - 2 - ivLen)
    }

    /** Runs [block]; false when the authentication window is closed. A key the system invalidated is deleted. */
    private inline fun attempt(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: UserNotAuthenticatedException) {
        false
    } catch (e: KeyPermanentlyInvalidatedException) {
        // A new screen lock or enrolment ended the key; the next seal makes a new one.
        store().deleteEntry(alias)
        false
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "herdr-terminal-cache"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VALIDITY_SECONDS = 30 * 60
        const val VERSION: Byte = 1
    }
}
