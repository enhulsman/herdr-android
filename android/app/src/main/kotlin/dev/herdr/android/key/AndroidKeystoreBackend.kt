package dev.herdr.android.key

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec

/** The phone key's properties, exactly those the M0.6 spike proved on the phone. */
object PhoneKey {
    const val ALGORITHM = KeyProperties.KEY_ALGORITHM_EC
    private const val VALIDITY_SECONDS = 30 * 60

    fun spec(alias: String, strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setIsStrongBoxBacked(strongBox)
            .build()
}

/** [KeyBackend] on the Android Keystore; keys there are never exportable. */
class AndroidKeystoreBackend : KeyBackend {
    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun contains(alias: String): Boolean = store().containsAlias(alias)

    override fun publicKey(alias: String): ByteArray =
        store().getCertificate(alias)?.publicKey?.encoded
            ?: throw UnrecoverableKeyException("no certificate for $alias")

    override fun generate(alias: String, strongBox: Boolean) {
        val generator = KeyPairGenerator.getInstance(PhoneKey.ALGORITHM, PROVIDER)
        try {
            generator.initialize(PhoneKey.spec(alias, strongBox))
            generator.generateKeyPair()
        } catch (e: Exception) {
            if (e.has<StrongBoxUnavailableException>()) throw StrongBoxMissingException(e)
            throw e
        }
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        try {
            val key = store().getKey(alias, null) as? PrivateKey
                ?: throw UnrecoverableKeyException("no private key for $alias")
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(key)
            signature.update(data)
            return signature.sign()
        } catch (e: Exception) {
            // The window can also close between initSign and sign, where the
            // Keystore wraps the reason.
            if (e.has<KeyPermanentlyInvalidatedException>()) throw KeyInvalidatedException(e)
            if (e.has<UserNotAuthenticatedException>()) throw KeyNeedsUserAuthException(e)
            throw e
        }
    }

    private inline fun <reified T : Throwable> Throwable.has(): Boolean =
        generateSequence(this) { it.cause }.take(8).any { it is T }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
