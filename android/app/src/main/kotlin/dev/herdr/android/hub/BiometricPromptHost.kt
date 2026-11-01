package dev.herdr.android.hub

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Log
import dev.herdr.android.R

/**
 * The platform prompt (no androidx.biometric), allowing a strong biometric or
 * the device credential, the same authenticators the key accepts.
 */
class BiometricPromptHost(private val activity: Activity) : PromptHost {
    override fun showPrompt(onResult: (Boolean) -> Unit) {
        try {
            BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.unlock_key_title))
                .setSubtitle(activity.getString(R.string.unlock_key_subtitle))
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
                .authenticate(
                    CancellationSignal(),
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            onResult(true)
                        }

                        // A finger that does not match keeps the prompt open
                        // (onAuthenticationFailed); only an error ends it.
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            Log.i(TAG, "prompt ended: $errorCode $errString")
                            onResult(false)
                        }
                    },
                )
        } catch (e: Exception) {
            Log.w(TAG, "prompt could not be shown", e)
            onResult(false)
        }
    }

    private companion object {
        const val TAG = "herdr-auth"
    }
}
