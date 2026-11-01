package dev.herdr.android.key

import uniffi.herdr_mobile.SignException
import uniffi.herdr_mobile.SshSigner
import uniffi.herdr_mobile.sshPublicKeyLine
import uniffi.herdr_mobile.authorizedKeysLine as restrictedLine

/**
 * The app's one SSH key (machine-connection spec, "The phone key is
 * hardware-bound and needs the owner"; design.md §2; spike M0.6): ECDSA P-256
 * in the Android Keystore, non-exportable, StrongBox when the phone has it,
 * usable for 30 minutes after a fingerprint or device credential.
 *
 * The key is created the first time it is needed and then reused for the life
 * of the install, across restarts and updates. Errors are reported, never
 * repaired: a lost or replaced key would lock the phone out of every machine,
 * so this class never deletes or re-creates a key that exists.
 */
class KeyManager(private val backend: KeyBackend, private val alias: String = ALIAS) {
    private var cached: ByteArray? = null
    private val signer = KeystoreSigner(backend, alias)

    /** The public key (SubjectPublicKeyInfo DER); creates the key if there is none yet. */
    @Synchronized
    fun publicKey(): ByteArray {
        cached?.let { return it.copyOf() }
        val exists = try {
            backend.contains(alias)
        } catch (e: Exception) {
            throw KeyUnavailableException("The phone's key store could not be opened: ${e.describe()}", e)
        }
        if (!exists) create()
        val spki = try {
            backend.publicKey(alias)
        } catch (e: Exception) {
            throw KeyUnavailableException("The phone key could not be read: ${e.describe()}", e)
        }
        cached = spki
        return spki.copyOf()
    }

    /** `ecdsa-sha2-nistp256 AAAA... herdr-android` */
    fun publicKeyLine(comment: String = COMMENT): String = sshPublicKeyLine(publicKey(), comment)

    /** The machine's `authorized_keys` line: the key, restricted to herdr-core's command. */
    fun authorizedKeysLine(comment: String = COMMENT): String = restrictedLine(publicKey(), comment)

    /** Signs for the hub; the key must exist ([publicKey] creates it). */
    fun signer(): SshSigner = signer

    private fun create() {
        try {
            try {
                backend.generate(alias, strongBox = true)
            } catch (e: StrongBoxMissingException) {
                backend.generate(alias, strongBox = false)
            }
        } catch (e: Exception) {
            throw KeyUnavailableException("The phone key could not be created: ${e.describe()}", e)
        }
    }

    companion object {
        const val ALIAS = "herdr-ssh"
        const val COMMENT = "herdr-android"
    }
}

/** Reports an expired authentication window as NeedsUserAuth, as the hub expects. */
private class KeystoreSigner(private val backend: KeyBackend, private val alias: String) : SshSigner {
    override fun sign(data: ByteArray): ByteArray = try {
        backend.sign(alias, data)
    } catch (e: KeyNeedsUserAuthException) {
        throw SignException.NeedsUserAuth()
    } catch (e: KeyInvalidatedException) {
        throw SignException.Failed("the phone key can no longer be used: ${e.describe()}")
    } catch (e: Exception) {
        throw SignException.Failed(e.describe())
    }
}

private fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: toString()
