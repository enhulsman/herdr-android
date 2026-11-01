package dev.herdr.android.key

import android.security.keystore.KeyProperties
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The key's properties are the spike's (M0.6) and the spec's: ECDSA P-256,
// signing only, SHA-256, user authentication (biometric or device credential)
// valid 30 minutes, StrongBox when the phone has it. The spec object is plain
// framework code, so Robolectric can build it.
@RunWith(RobolectricTestRunner::class)
class PhoneKeySpecTest {
    @Test
    fun thePhoneKeyHasTheSpikesProperties() {
        for (strongBox in listOf(true, false)) {
            val spec = PhoneKey.spec("alias", strongBox)
            assertEquals("alias", spec.keystoreAlias)
            assertEquals(KeyProperties.PURPOSE_SIGN, spec.purposes)
            assertEquals("secp256r1", (spec.algorithmParameterSpec as ECGenParameterSpec).name)
            assertArrayEquals(arrayOf(KeyProperties.DIGEST_SHA256), spec.digests)
            assertTrue(spec.isUserAuthenticationRequired)
            assertEquals(30 * 60, spec.userAuthenticationValidityDurationSeconds)
            assertEquals(
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                spec.userAuthenticationType,
            )
            assertEquals(strongBox, spec.isStrongBoxBacked)
        }
    }

    @Test
    fun thePhoneKeyIsAnEcKey() {
        assertEquals(KeyProperties.KEY_ALGORITHM_EC, PhoneKey.ALGORITHM)
    }
}
