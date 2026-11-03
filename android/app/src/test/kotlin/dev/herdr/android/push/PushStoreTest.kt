package dev.herdr.android.push

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.PushRegistration

// What the phone keeps about alerts outlives the process: the push service
// runs with the app closed, and the silence check starts the process from a
// job (alerts spec, "Silence is noticed without claiming a cause", "Mute per
// machine", "A test alert proves the path").
@RunWith(RobolectricTestRunner::class)
class PushStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private fun open() = PushStore(context.getSharedPreferences("push-store-test", Context.MODE_PRIVATE))

    private val reg = PushRegistration("https://ntfy.example.org/upABC?up=1", "BKEY", "AUTH")

    @Test
    fun everythingSurvivesANewInstance() {
        open().apply {
            registration = reg
            setup = AlertSetup.Waiting
            permissionAsked = true
            setMuted("workstation", true)
            recordPush("workstation", 10)
            markHealthShown("workstation", 10)
            testAsked("workstation", 20)
            testReceived("workstation", 25)
        }
        val again = open()
        assertEquals(reg, again.registration)
        assertEquals(AlertSetup.Waiting, again.setup)
        assertTrue(again.permissionAsked)
        assertEquals(MachineAlerts(muted = true, lastPushMs = 10, healthShownForMs = 10, testAskedMs = 20, testReceivedMs = 25), again.machine("workstation"))
    }

    @Test
    fun aFailureKeepsItsReason() {
        open().setup = AlertSetup.Failed("network")
        assertEquals(AlertSetup.Failed("network"), open().setup)
    }

    @Test
    fun anUnknownMachineHasNothingRecorded() {
        assertEquals(MachineAlerts(), open().machine("nowhere"))
    }

    @Test
    fun enrollingSetsABaselineOnlyOnce() {
        val s = open()
        s.enroll("workstation", 100)
        s.enroll("workstation", 200)
        assertEquals(100L, s.machine("workstation").lastPushMs)
        s.recordPush("workstation", 300)
        s.enroll("workstation", 400)
        assertEquals(300L, s.machine("workstation").lastPushMs)
        assertEquals(listOf("workstation"), s.enrolled())
    }

    @Test
    fun aRemovedMachineTakesItsRecordWith() {
        val s = open()
        s.setMuted("workstation", true)
        s.recordPush("workstation", 1)
        s.recordPush("server", 2)
        s.keepMachines(setOf("server"))
        assertEquals(MachineAlerts(), s.machine("workstation"))
        assertEquals(2L, s.machine("server").lastPushMs)
        assertEquals(listOf("server"), s.enrolled())
    }

    @Test
    fun namesWithDotsAndColonsDoNotCollide() {
        val s = open()
        s.recordPush("a.b", 1)
        s.recordPush("a", 2)
        s.setMuted("a:b", true)
        assertEquals(1L, s.machine("a.b").lastPushMs)
        assertEquals(2L, s.machine("a").lastPushMs)
        assertFalse(s.machine("a").muted)
        assertTrue(s.machine("a:b").muted)
    }

    @Test
    fun writesBumpTheChangeCounter() {
        val s = open()
        val before = s.changes.value
        s.setMuted("workstation", true)
        assertTrue(s.changes.value > before)
    }

    @Test
    fun clearingTheRegistrationForgetsIt() {
        val s = open()
        s.registration = reg
        s.registration = null
        assertNull(open().registration)
    }
}
