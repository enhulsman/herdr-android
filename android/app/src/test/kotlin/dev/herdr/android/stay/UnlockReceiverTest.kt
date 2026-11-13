package dev.herdr.android.stay

import android.content.Context
import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// stay-connected spec, "An unlock retries" (design D6): the service hears each
// unlock of the phone. ACTION_USER_PRESENT is sent by SystemUI, an app uid,
// not the system uid, so a receiver registered not-exported never gets it
// (phone check 4.2, Android 17: the broadcast skipped the receiver). It is a
// protected broadcast, so exported lets no other app send it.
@RunWith(RobolectricTestRunner::class)
class UnlockReceiverTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun registersExportedForTheUnlockBroadcast() {
        UnlockReceiver {}.register(app)
        val wrapper = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) }
        assertTrue("exported", wrapper.flags and Context.RECEIVER_EXPORTED != 0)
        assertEquals("not not-exported", 0, wrapper.flags and Context.RECEIVER_NOT_EXPORTED)
    }

    @Test
    fun anUnlockCallsBackOnce() {
        var unlocks = 0
        UnlockReceiver { unlocks++ }.register(app)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, unlocks)
    }

    @Test
    fun unregisterStopsIt() {
        var unlocks = 0
        val receiver = UnlockReceiver { unlocks++ }
        receiver.register(app)
        receiver.unregister(app)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, unlocks)
    }
}
