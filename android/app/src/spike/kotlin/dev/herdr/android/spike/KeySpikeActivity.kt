package dev.herdr.android.spike

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import uniffi.herdr_mobile.SignException
import uniffi.herdr_mobile.SshException
import uniffi.herdr_mobile.SshSigner
import uniffi.herdr_mobile.sshPublicKeyLine
import uniffi.herdr_mobile.sshRunSpike

// Task M0.6, Spike B: SSH sign-in with a non-exportable Keystore key that needs
// user authentication. Two keys: the design's 30-minute one, and a 20-second
// one so the check can watch the window run out. Started over adb with the
// server as extras (host, port, user); results also go to logcat under the tag
// herdr-spike-key, including the authorized_keys lines.
private const val TAG = "herdr-spike-key"
private const val PREFS = "key-spike"
private const val EXPIRY_WAIT_MS = 25_000L

private data class SpikeKey(val alias: String, val validitySeconds: Int)

private val LONG_KEY = SpikeKey("spike-ssh-30m", 30 * 60)
private val SHORT_KEY = SpikeKey("spike-ssh-20s", 20)

class KeySpikeActivity : ComponentActivity() {
    private var report by mutableStateOf("")
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(transparent), SystemBarStyle.dark(transparent))
        val host = intent.getStringExtra("host") ?: "127.0.0.1"
        val port = intent.getIntExtra("port", 0)
        val user = intent.getStringExtra("user").orEmpty()

        report = buildString {
            for (key in listOf(LONG_KEY, SHORT_KEY)) {
                val line = sshPublicKeyLine(publicKeyInfo(key), "herdr-spike-${key.validitySeconds}s")
                Log.i(TAG, "authorized_keys ${key.alias}: $line")
                val level = "${key.alias}: ${securityLevel(key)}"
                Log.i(TAG, level)
                appendLine(level)
            }
            if (port == 0 || user.isEmpty()) appendLine("\nStart with --ei port and --es user to run the check.")
        }

        setContent {
            val scope = rememberCoroutineScope()
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF17191D))
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicText(
                    "Run the check",
                    Modifier
                        .clickable {
                            if (running || port == 0 || user.isEmpty()) return@clickable
                            running = true
                            scope.launch {
                                check(host, port, user)
                                running = false
                            }
                        }
                        .padding(vertical = 12.dp),
                    style = TextStyle(color = Color(0xFF8FC1FF), fontSize = 18.sp),
                )
                BasicText(
                    report,
                    style = TextStyle(color = Color(0xFFEEEEEF), fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                )
            }
        }
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        report += line + "\n"
    }

    /**
     * The phone check: short key (prompt unless the phone was unlocked in the
     * last 20 s), long key right after (no prompt), wait out the short window,
     * short key again (prompt), long key again (no prompt).
     */
    private suspend fun check(host: String, port: Int, user: String) {
        say("\n— check started —")
        connect(host, port, user, SHORT_KEY, "1 short key")
        connect(host, port, user, LONG_KEY, "2 long key, right after")
        say("waiting ${EXPIRY_WAIT_MS / 1000} s for the short key's window to close…")
        delay(EXPIRY_WAIT_MS)
        connect(host, port, user, SHORT_KEY, "3 short key, window closed")
        connect(host, port, user, LONG_KEY, "4 long key, still inside 30 min")
        say("— check done —")
    }

    /** One sign-in; prompts once and tries again when the key asks for it. */
    private suspend fun connect(host: String, port: Int, user: String, key: SpikeKey, label: String) {
        val signer = KeystoreSigner(key.alias)
        var prompted = false
        while (true) {
            val started = System.nanoTime()
            try {
                val run = withContext(Dispatchers.IO) {
                    sshRunSpike(
                        host, port.toUShort(), user, publicKeyInfo(key), pinnedHostKey(),
                        "spike", signer, 15_000uL,
                    )
                }
                pinHostKey(run.hostKeyFingerprint)
                val totalMs = (System.nanoTime() - started) / 1_000_000
                say(
                    "$label: ok, prompted=$prompted, output=\"${run.output}\", exit=${run.exitStatus}, " +
                        "signatures=${run.signatures}, sign=${signer.lastSignMs} ms, total=$totalMs ms",
                )
                return
            } catch (e: SshException.NeedsUserAuth) {
                if (prompted) {
                    say("$label: FAILED, still needs authentication after the prompt")
                    return
                }
                prompted = true
                val outcome = try {
                    authenticate()
                } catch (e: SecurityException) {
                    e.toString()
                }
                if (outcome != null) {
                    say("$label: FAILED, prompt ended: $outcome")
                    return
                }
            } catch (e: SshException) {
                say("$label: FAILED, ${e::class.simpleName}: ${e.message} ${signer.lastError.orEmpty()}")
                return
            }
        }
    }

    /** Shows the system prompt; null on success, otherwise why it ended. */
    private suspend fun authenticate(): String? = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        BiometricPrompt.Builder(this)
            .setTitle("herdr spike")
            .setSubtitle("Unlock the SSH key")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(
                cancel,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        cont.resume(null)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        cont.resume("error $errorCode: $errString")
                    }
                },
            )
    }

    private fun pinnedHostKey(): String? =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("host-key", null)

    private fun pinHostKey(fingerprint: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("host-key", fingerprint).apply()
    }

    private fun publicKeyInfo(key: SpikeKey): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(key.alias)) generate(key)
        return store.getCertificate(key.alias).publicKey.encoded
    }

    /** StrongBox when the phone has it, else the TEE; never exportable. */
    private fun generate(key: SpikeKey) {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(key.alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                key.validitySeconds,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setIsStrongBoxBacked(strongBox)
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            generator.initialize(spec(strongBox = true))
            generator.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            generator.initialize(spec(strongBox = false))
            generator.generateKeyPair()
        }
    }

    private fun securityLevel(key: SpikeKey): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val private = store.getKey(key.alias, null) as PrivateKey
        val info = KeyFactory.getInstance(private.algorithm, "AndroidKeyStore")
            .getKeySpec(private, KeyInfo::class.java)
        val level = when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
            else -> "level ${info.securityLevel}"
        }
        return "$level, auth ${info.userAuthenticationValidityDurationSeconds} s, " +
            "types ${info.userAuthenticationType}"
    }
}

/** Signs with a Keystore key; reports an expired authentication window as NeedsUserAuth. */
private class KeystoreSigner(private val alias: String) : SshSigner {
    @Volatile var lastSignMs = -1L
    @Volatile var lastError: String? = null

    override fun sign(data: ByteArray): ByteArray {
        val started = System.nanoTime()
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = store.getKey(alias, null) as PrivateKey
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(key)
            signature.update(data)
            return signature.sign().also { lastSignMs = (System.nanoTime() - started) / 1_000_000 }
        } catch (e: UserNotAuthenticatedException) {
            throw SignException.NeedsUserAuth()
        } catch (e: Exception) {
            lastError = e.toString()
            throw SignException.Failed(e.toString())
        }
    }
}
