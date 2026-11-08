package dev.herdr.android.push

import android.content.Context
import android.content.res.Resources
import dev.herdr.android.ui.agents.AgentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.herdr_mobile.PushRegistration

// alerts spec: "Three notification channels" (Needs you, Finished, Alert
// health; tapping an agent alert opens its terminal), "Alerts clear when
// handled anywhere" (cleared removes it), "Silence is noticed without claiming
// a cause" (one notice after 13 h, cleared by the next push), "Mute per
// machine" (dropped, still counts for health), "A test alert proves the path".
// design-tokens.json notification: title "<agent> needs you" / "<agent>
// finished", body "<kind> · <place> · <machine>" with absent parts left out,
// never screen text or msg.
@RunWith(RobolectricTestRunner::class)
class AlertsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val res: Resources = context.resources
    private val store = PushStore(context.getSharedPreferences("alerts-test", Context.MODE_PRIVATE)).apply {
        registration = PushRegistration("https://ntfy.example.org/upABC?up=1", "BKEY", "AUTH")
    }
    private val sink = FakeSink()
    private val alerts = Alerts(store, sink, res) { ms -> "at$ms" }

    private fun push(json: String, decrypted: Boolean = true, now: Long = NOW) =
        alerts.onMessage(json.toByteArray(), decrypted, now)

    private fun alert(
        state: String,
        kind: String? = null,
        name: String = "docs nav",
        machine: String = "workstation",
        terminal: String = "t3",
        place: String? = null,
    ) =
        """{"v":1,"type":"alert","machine":"$machine","terminal":"$terminal","name":"$name","state":"$state"""" +
            (kind?.let { ""","kind":"$it"""" } ?: "") + (place?.let { ""","place":"$it"""" } ?: "") + ""","at":5}"""

    @Test
    fun blockedPostsNeedsYouWithKindAndMachineAndOpensTheTerminal() {
        push(alert("blocked", kind = "permission"))
        val note = sink.posted.single()
        assertEquals(AlertChannel.NeedsYou, note.channel)
        assertEquals("agent:workstation/t3", note.tag)
        assertEquals("docs nav needs you", note.title)
        assertEquals("Permission · workstation", note.text)
        assertEquals(AgentKey("workstation", "t3"), note.open)
    }

    @Test
    fun blockedWithoutAKindNamesOnlyTheMachine() {
        push(alert("blocked"))
        assertEquals("workstation", sink.posted.single().text)
    }

    @Test
    fun anErrorNeedsYouToo() {
        push(alert("error"))
        val note = sink.posted.single()
        assertEquals(AlertChannel.NeedsYou, note.channel)
        assertEquals("docs nav needs you", note.title)
        assertEquals("Error · workstation", note.text)
    }

    @Test
    fun doneGoesToFinishedWithTheMachineOnTheSecondLine() {
        push(alert("done", name = "og tags"))
        val note = sink.posted.single()
        assertEquals(AlertChannel.Finished, note.channel)
        assertEquals("agent:workstation/t3", note.tag)
        assertEquals("og tags finished", note.title)
        assertEquals("workstation", note.text)
        assertEquals(AgentKey("workstation", "t3"), note.open)
    }

    // notification-place: the desk's "space / tab" on the second line, before
    // the machine; left out when the push has none (an older herdr-push, or a
    // terminal not placed yet).
    @Test
    fun needsYouSaysWhereTheSessionSits() {
        push(alert("blocked", kind = "permission", name = "Pricing page copy", place = "website / 2"))
        val note = sink.posted.single()
        assertEquals("Pricing page copy needs you", note.title)
        assertEquals("Permission · website / 2 · workstation", note.text)
    }

    @Test
    fun needsYouWithoutAKindStillSaysWhere() {
        push(alert("blocked", place = "website / 2"))
        assertEquals("website / 2 · workstation", sink.posted.single().text)
    }

    @Test
    fun anErrorSaysWhereToo() {
        push(alert("error", place = "api / 1"))
        assertEquals("Error · api / 1 · workstation", sink.posted.single().text)
    }

    @Test
    fun finishedSaysWhereTheSessionSits() {
        push(alert("done", name = "Pricing page copy", place = "website / 2"))
        val note = sink.posted.single()
        assertEquals("Pricing page copy finished", note.title)
        assertEquals("website / 2 · workstation", note.text)
    }

    @Test
    fun aBlankPlaceIsLeftOut() {
        push(alert("blocked", kind = "permission", place = "  "))
        assertEquals("Permission · workstation", sink.posted.single().text)
    }

    @Test
    fun thePublicVersionDoesNotSayWhere() {
        push(alert("blocked", kind = "permission", place = "website / 2"))
        push(alert("done", terminal = "t4", place = "website / 2"))
        assertEquals(
            listOf("An agent needs you · workstation", "An agent finished · workstation"),
            sink.posted.map { it.publicTitle },
        )
    }

    // session-names: a session title in the name, hidden on a private lock
    // screen behind a fixed public version.
    @Test
    fun aSessionTitleNamesTheAlert() {
        push(alert("done", name = "Pricing page copy"))
        assertEquals("Pricing page copy finished", sink.posted.single().title)
    }

    @Test
    fun agentAlertsHaveAPublicVersionWithoutTheName() {
        push(alert("blocked", kind = "permission", name = "Pricing page copy"))
        push(alert("error", name = "Pricing page copy", terminal = "t4"))
        push(alert("done", name = "Pricing page copy", terminal = "t5"))
        assertEquals(
            listOf("An agent needs you · workstation", "An agent needs you · workstation", "An agent finished · workstation"),
            sink.posted.map { it.publicTitle },
        )
        assertTrue(sink.posted.none { it.publicTitle!!.contains("Pricing") })
    }

    @Test
    fun theTestAlertHasAPublicVersion() {
        push("""{"v":1,"type":"test","machine":"workstation","at":5}""")
        assertEquals("Test alert from workstation", sink.posted.single().publicTitle)
    }

    @Test
    fun anUnnamedAgentGoesByItsTerminal() {
        push(alert("blocked", name = ""))
        assertEquals("t3 needs you", sink.posted.single().title)
    }

    @Test
    fun clearedRemovesThatAgentsNotification() {
        push(alert("blocked"))
        push("""{"v":1,"type":"cleared","machine":"workstation","terminal":"t3","at":6}""")
        assertEquals(listOf("agent:workstation/t3"), sink.cancelled.filter { it.startsWith("agent:") })
        assertEquals(1, sink.posted.size)
    }

    @Test
    fun aCanaryShowsNothingButCountsAsWord() {
        push("""{"v":1,"type":"canary","machine":"workstation","at":5}""", now = NOW + 7)
        assertTrue(sink.posted.isEmpty())
        assertEquals(NOW + 7, store.machine("workstation").lastPushMs)
    }

    @Test
    fun aTestArrivesAsANotificationAndIsRecorded() {
        store.testAsked("workstation", NOW - 3_000)
        push("""{"v":1,"type":"test","machine":"workstation","at":5}""")
        val note = sink.posted.single()
        assertEquals(AlertChannel.NeedsYou, note.channel)
        assertEquals("test:workstation", note.tag)
        assertEquals("Test alert from workstation", note.title)
        assertEquals(NOW, store.machine("workstation").testReceivedMs)
    }

    @Test
    fun aMutedMachineShowsNothingButStillCountsForHealth() {
        store.setMuted("workstation", true)
        push(alert("blocked"), now = NOW + 1)
        push("""{"v":1,"type":"test","machine":"workstation","at":5}""", now = NOW + 2)
        assertTrue(sink.posted.isEmpty())
        assertEquals(NOW + 2, store.machine("workstation").lastPushMs)
        assertEquals(NOW + 2, store.machine("workstation").testReceivedMs)
        // Another machine is not muted.
        push(alert("blocked", machine = "server"))
        assertEquals(1, sink.posted.size)
    }

    @Test
    fun undecryptedOrMalformedPushesAreIgnoredEntirely() {
        push(alert("blocked"), decrypted = false)
        push("not json")
        push("""{"v":1,"type":"alert","terminal":"t3","state":"blocked"}""")
        push("""[1,2]""")
        assertTrue(sink.posted.isEmpty())
        assertTrue(sink.cancelled.isEmpty())
        assertNull(store.machine("workstation").lastPushMs)
    }

    @Test
    fun anUnknownTypeOnlyCountsAsWord() {
        push("""{"v":2,"type":"something-new","machine":"workstation","at":5}""")
        assertTrue(sink.posted.isEmpty())
        assertEquals(NOW, store.machine("workstation").lastPushMs)
    }

    @Test
    fun anAlertWithoutATerminalShowsNothing() {
        push("""{"v":1,"type":"alert","machine":"workstation","name":"x","state":"blocked","at":5}""")
        assertTrue(sink.posted.isEmpty())
    }

    @Test
    fun silenceOfThirteenHoursPostsOneQuietNotice() {
        push("""{"v":1,"type":"canary","machine":"workstation","at":5}""", now = NOW)
        alerts.checkSilence(NOW + 13 * HOUR - 1)
        assertTrue(sink.posted.isEmpty())
        alerts.checkSilence(NOW + 13 * HOUR)
        val note = sink.posted.single()
        assertEquals(AlertChannel.Health, note.channel)
        assertEquals("health:workstation", note.tag)
        assertEquals("No word from workstation since at$NOW", note.title)
        assertEquals("The machine is off or alerts are broken.", note.text)
        assertNull(note.open)
        // Once per silence, however often the check runs.
        alerts.checkSilence(NOW + 20 * HOUR)
        assertEquals(1, sink.posted.size)
    }

    @Test
    fun theNextPushClearsTheNoticeAndArmsItAgain() {
        push("""{"v":1,"type":"canary","machine":"workstation","at":5}""", now = NOW)
        alerts.checkSilence(NOW + 14 * HOUR)
        push("""{"v":1,"type":"canary","machine":"workstation","at":6}""", now = NOW + 20 * HOUR)
        assertTrue("health:workstation" in sink.cancelled)
        alerts.checkSilence(NOW + 33 * HOUR)
        assertEquals(2, sink.posted.count { it.channel == AlertChannel.Health })
    }

    @Test
    fun anEnrolledMachineThatNeverSpokeIsNoticedToo() {
        // Registered at a connection, then nothing at all: a dead topic.
        store.enroll("workstation", NOW)
        alerts.checkSilence(NOW + 13 * HOUR)
        assertEquals("No word from workstation since at$NOW", sink.posted.single().title)
    }

    @Test
    fun aMutedMachineStillGetsTheHealthNotice() {
        store.setMuted("workstation", true)
        store.enroll("workstation", NOW)
        alerts.checkSilence(NOW + 13 * HOUR)
        assertEquals(1, sink.posted.size)
    }

    @Test
    fun noHealthNoticesWhileAlertsAreOffOnThePhone() {
        store.enroll("workstation", NOW)
        store.registration = null
        alerts.checkSilence(NOW + 20 * HOUR)
        assertTrue(sink.posted.isEmpty())
    }

    private companion object {
        const val NOW = 1_730_650_000_000L
        const val HOUR = 3_600_000L
    }
}

class FakeSink : AlertSink {
    val posted = mutableListOf<AlertNote>()
    val cancelled = mutableListOf<String>()
    override fun post(note: AlertNote) { posted += note }
    override fun cancel(tag: String) { cancelled += tag }
}
