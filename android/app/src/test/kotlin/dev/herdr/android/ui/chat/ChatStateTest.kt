package dev.herdr.android.ui.chat

import androidx.compose.ui.unit.dp
import dev.herdr.android.testing.HerdrTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.herdr_mobile.ChatCall
import uniffi.herdr_mobile.ChatRefusal
import uniffi.herdr_mobile.ChatRow

// The chat's own state: sheets follow their row by key, refusal and gauge
// texts (spec "The tool sheet shows each call", "Sending goes only to the
// agent whose log is shown", "A context gauge shows in the top bar", "Photos
// can be attached from the phone").
@RunWith(HerdrTestRunner::class)
class ChatStateTest {
    private fun call(title: String, done: Boolean = true, out: String = "") = ChatCall("Bash", title, title, out, done, false, emptyList())
    private fun tools(key: String, vararg calls: ChatCall) = ChatRow(key, "tools", "", "", emptyList(), calls.toList())

    @Test
    fun theDialogRefusalGoesWhenTheWaitingLineEnds() {
        // Seen on the test phone: after Dismiss the line went, but
        // "Answer the dialog in the terminal first" stayed under it.
        val s = ChatState()
        s.draft = "/usage"
        val out = s.send(emptyList(), "idle", now = 0)!!
        s.typed(out.pending.id, now = 5_000)
        s.draft = "hi"
        assertNull(s.send(emptyList(), "idle", now = 5_500))
        assertEquals(DIALOG_FIRST, s.refusal?.text)
        s.update(out.pending.id) { it.dismissWait() }
        assertNull("dismissed", s.refusal)
        assertEquals("the draft stays", "hi", s.draft)

        val r = ChatState()
        r.draft = "/usage"
        r.send(emptyList(), "idle", now = 0)!!
        r.draft = "hi"
        r.send(emptyList(), "idle", now = 3_500)
        assertEquals(DIALOG_FIRST, r.refusal?.text)
        r.pending = emptyList()
        assertNull("its row arrived", r.refusal)

        val other = ChatState()
        other.refusal = refusalOf(ChatRefusal.Exited)
        other.pending = emptyList()
        assertEquals("other refusals stay", refusalOf(ChatRefusal.Exited), other.refusal)
    }

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
        // Mirror first (left to right), then turn clockwise.
        assertEquals(ExifTurn(0f, false), exifTurn(android.media.ExifInterface.ORIENTATION_NORMAL))
        assertEquals(ExifTurn(0f, false), exifTurn(android.media.ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(ExifTurn(90f, false), exifTurn(android.media.ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(ExifTurn(180f, false), exifTurn(android.media.ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(ExifTurn(270f, false), exifTurn(android.media.ExifInterface.ORIENTATION_ROTATE_270))
        assertTrue(exifTurn(android.media.ExifInterface.ORIENTATION_NORMAL).none)
    }

    @Test
    fun mirroredPhotosAreTurnedUprightToo() {
        assertEquals(ExifTurn(0f, true), exifTurn(android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(ExifTurn(180f, true), exifTurn(android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL))
        // Transpose (5): mirrored, then a quarter turn back, so (x, y) lands on (y, x).
        assertEquals(ExifTurn(270f, true), exifTurn(android.media.ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(ExifTurn(90f, true), exifTurn(android.media.ExifInterface.ORIENTATION_TRANSVERSE))
        assertFalse(exifTurn(android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL).none)
    }

    @Test
    fun attachedPhotosGetDistinctNames() {
        assertEquals(4, List(4) { photoName(stamp = 4242L, index = it, ext = "jpg") }.toSet().size)
    }

    // Issue #22: a picked file is read only up to a limit, so a huge one (or a
    // provider that never stops) cannot fill the app's memory.
    @Test
    fun aPickedFileIsReadUpToALimit() {
        val small = ByteArray(10) { it.toByte() }
        assertTrue(small.contentEquals(readAtMost(java.io.ByteArrayInputStream(small), 10)))
        assertNull(readAtMost(java.io.ByteArrayInputStream(ByteArray(11)), 10))
        val endless = object : java.io.InputStream() {
            var read = 0L
            override fun read(): Int { read++; return 0 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        assertNull(readAtMost(endless, 1000))
        assertTrue("read ${endless.read} bytes", endless.read <= 1000 + 8192)
    }
}
