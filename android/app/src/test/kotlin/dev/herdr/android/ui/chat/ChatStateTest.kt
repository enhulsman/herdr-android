package dev.herdr.android.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatRefusal
import uniffi.herdr_mobile.ChatRow

// The chat's own state: sheets follow their row by key, refusal and gauge
// texts (spec "The tool sheet shows each call", "Sending goes only to the
// agent whose log is shown", "A context gauge shows in the top bar", "Photos
// can be attached from the phone").
@RunWith(RobolectricTestRunner::class)
class ChatStateTest {
    private fun call(title: String, done: Boolean = true, out: String = "") = ChatCall("Bash", title, title, out, done, false, emptyList())
    private fun tools(key: String, vararg calls: ChatCall) = ChatRow(key, "tools", "", "", emptyList(), calls.toList())

    @Test
    fun oneToolOpensItsDetailAtOnce() {
        val s = ChatState()
        val one = tools("10:0", call("Run the tests"))
        s.openTools(one)
        assertEquals("10:0", s.sheet)
        assertEquals(0, s.detail)
        s.closeDetail(one.calls)
        assertNull("back from a lone call closes the sheet", s.sheet)
        val many = tools("20:0", call("a"), call("b"))
        s.openTools(many)
        assertNull(s.detail)
        s.detail = 1
        s.closeDetail(many.calls)
        assertEquals("back from one of several shows the list", "20:0", s.sheet)
        assertNull(s.detail)
    }

    @Test
    fun anOpenDetailFollowsItsRunningCallUntilItEnds() {
        // Spec scenario "A running command's detail": the sheet reads the row by key from each poll.
        val running = listOf(ChatRow("0:0", "said", "Testing.", "", emptyList(), emptyList()), tools("40:0", call("Run the tests", done = false)))
        assertEquals(false, sheetCalls(running, "40:0")!!.single().done)
        // Older rows read back in front and the call ended: the same key still finds it.
        val later = listOf(ChatRow("0:0", "user", "older", "", emptyList(), emptyList())) + running.dropLast(1) +
            tools("40:0", call("Run the tests", done = true, out = "ok"))
        assertEquals("ok", sheetCalls(later, "40:0")!!.single().output)
        assertNull("its row is gone (a new session)", sheetCalls(listOf(tools("0:0", call("x"))), "40:0"))
        assertNull(sheetCalls(later, null))
    }

    @Test
    fun aSentMessageIsShownAtOnceAndFollowsTheBottom() {
        val s = ChatState()
        s.stick = false
        s.refusal = Refusal("The agent has exited", false)
        val p = s.sent("Run the tests", 0, emptyList(), "idle", now = 5)
        assertEquals(listOf(p), s.pending)
        assertEquals(true, s.stick)
        assertNull(s.refusal)
        val q = s.sent("And push", 0, emptyList(), "idle", now = 6)
        assertEquals("each message its own id", 2, s.pending.map { it.id }.toSet().size)
        s.update(p.id) { it.typed(10) }
        assertEquals(10L, s.pending.first().typedAt)
        s.drop(p.id)
        assertEquals(listOf(q), s.pending)
    }

    @Test
    fun refusalsSayWhyAndOfferTheTerminalWhereItHelps() {
        assertEquals(Refusal("The agent is waiting for you in the terminal", true), refusalOf(ChatRefusal.Waiting))
        assertEquals(Refusal("The agent has exited", false), refusalOf(ChatRefusal.Exited))
        assertEquals(Refusal("This is a different session now", true), refusalOf(ChatRefusal.Changed))
        assertEquals(Refusal("Not connected", false), refusalOf(ChatRefusal.Failed("Not connected")))
    }

    @Test
    fun theGaugeShowsCodexsShareAndClaudeCodesTokens() {
        // Spec scenarios "A Codex session" and "A Claude Code session".
        assertEquals("47%", gaugeLabel(223_000u, 475_000u, 47u))
        assertEquals("223k of 475k tokens", gaugeDetail(223_000u, 475_000u))
        assertEquals("283k", gaugeLabel(283_000u, null, null))
        assertEquals("283k tokens used", gaugeDetail(283_000u, null))
        assertEquals("rounded to the nearest thousand", "284k tokens used", gaugeDetail(283_600u, null))
        assertEquals("900", gaugeLabel(900u, null, null))
        assertNull("no token count yet", gaugeLabel(null, null, null))
        assertNull(gaugeDetail(null, null))
    }

    @Test
    fun uploadsAreScaledOnlyWhenLarge() {
        assertEquals(null, uploadLongEdge(1080, 1920, 900_000))
        assertEquals(2000, uploadLongEdge(4000, 3000, 2_000_000))
        assertEquals("too many bytes: re-encoded at its own size", 1600, uploadLongEdge(1600, 1200, 5_000_000))
    }

    @Test
    fun thumbnailsKeepTheirShapeWithinLimits() {
        assertEquals(112.dp, thumbWidth(1000, 1000))
        assertEquals("a tall phone screenshot is clamped", 72.dp, thumbWidth(1080, 2340))
        assertEquals(168.dp, thumbWidth(1200, 800))
        assertEquals(240.dp, thumbWidth(4000, 500))
    }

    @Test
    fun cameraPhotosAreTurnedUprightBeforeSending() {
        assertEquals(0f, exifRotation(android.media.ExifInterface.ORIENTATION_NORMAL))
        assertEquals(0f, exifRotation(android.media.ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(90f, exifRotation(android.media.ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180f, exifRotation(android.media.ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270f, exifRotation(android.media.ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun attachedPhotosGetDistinctNames() {
        assertEquals(4, List(4) { photoName(stamp = 4242L, index = it, ext = "jpg") }.toSet().size)
    }
}
