package dev.herdr.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Spike for issue 17 (never merged): the working line, the running tool line's
// shimmer, and inline code whose box must not hide a text selection.
class ChatWorkingTest {
    private fun sent(text: String) = Pending(text, 0, at = 0, sentAt = 0)

    @Test
    fun theAgentWorkingShowsTheLine() {
        assertTrue(busy("working", emptyList()))
    }

    @Test
    fun aMessageOnItsWayShowsTheLineBeforeTheStatusCatchesUp() {
        assertTrue(busy("done", listOf(sent("Fix the retry"))))
    }

    @Test
    fun aCommandOnItsWayDoesNot() {
        // A slash command may only open a dialog; a ! command runs in the shell, not the agent.
        assertFalse(busy("done", listOf(sent("/usage"))))
        assertFalse(busy("idle", listOf(sent("  !git status"))))
    }

    @Test
    fun doneIdleAndBlockedDoNot() {
        // Blocked waits for the person, not on the agent.
        for (s in listOf("done", "idle", "blocked", "unknown", "")) assertFalse(s, busy(s, emptyList()))
    }

    @Test
    fun theVerbChangesEveryFewSecondsAndCycles() {
        assertEquals(workingVerb(0), workingVerb(VERB_MS - 1))
        assertNotEquals(workingVerb(0), workingVerb(VERB_MS))
        assertEquals(workingVerb(0), workingVerb(VERB_MS * WORKING_VERBS.size))
        assertTrue(WORKING_VERBS.all { it.isNotBlank() && !it.endsWith("…") })
        assertEquals(WORKING_VERBS.size, WORKING_VERBS.toSet().size)
    }

    @Test
    fun onlyALiveRunningCallShimmers() {
        val running = listOf(uniffi.herdr_mobile.ChatCall("Bash", "Run the tests", "", "", false, false, emptyList()))
        val finished = listOf(running[0].copy(done = true))
        assertTrue(shimmers(running, busy = true))
        assertFalse("a call left open in an old log", shimmers(running, busy = false))
        assertFalse(shimmers(finished, busy = true))
    }

    @Test
    fun inlineCodeIsMarkedNotPaintedAsASpanBackground() {
        val s = inline("Run `cargo test` and `ls`")
        assertEquals("Run cargo test and ls", s.text)
        assertTrue("no span background: it would paint over the selection", s.spanStyles.none { it.item.background != androidx.compose.ui.graphics.Color.Unspecified })
        val code = s.getStringAnnotations(CODE_TAG, 0, s.length).map { s.text.substring(it.start, it.end) }
        assertEquals(listOf("cargo test", "ls"), code)
    }

    @Test
    fun onlyThePersonsOwnScrollChangesFollowing() {
        // The app's scroll to the end passes a moment with more below: following stays on.
        assertTrue(nextStick(stick = true, byPerson = false, more = true))
        // The person scrolls up: off; back at the end: on again.
        assertFalse(nextStick(stick = true, byPerson = true, more = true))
        assertTrue(nextStick(stick = false, byPerson = true, more = false))
        // Off stays off while the app moves things.
        assertFalse(nextStick(stick = false, byPerson = false, more = false))
    }
}
