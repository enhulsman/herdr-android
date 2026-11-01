package dev.herdr.android.key

/**
 * The few Keystore operations the phone key needs, so [KeyManager] can be
 * tested without a device. Deliberately without a delete: the app never
 * removes its key (machines trust it), whatever goes wrong.
 */
interface KeyBackend {
    fun contains(alias: String): Boolean

    /** The public half as an X.509 SubjectPublicKeyInfo (DER). */
    fun publicKey(alias: String): ByteArray

    /** Creates the key; throws [StrongBoxMissingException] when the phone has no StrongBox. */
    fun generate(alias: String, strongBox: Boolean)

    /**
     * ECDSA over SHA-256, DER signature. Throws [KeyNeedsUserAuthException] when
     * the authentication window has passed, [KeyInvalidatedException] when the
     * key can never be used again.
     */
    fun sign(alias: String, data: ByteArray): ByteArray
}

class StrongBoxMissingException(cause: Throwable? = null) : Exception("this phone has no StrongBox", cause)

class KeyNeedsUserAuthException(cause: Throwable? = null) : Exception("the key needs user authentication", cause)

/** The Keystore invalidated the key, for example because the screen lock was removed. */
class KeyInvalidatedException(cause: Throwable) : Exception(cause.message ?: cause.toString(), cause)

/** The phone key could not be opened, read or created. It was left as it is. */
class KeyUnavailableException(message: String, cause: Throwable) : Exception(message, cause)
