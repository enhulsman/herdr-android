package dev.herdr.android.ui.changes

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.herdr_mobile.ChangesDiff
import uniffi.herdr_mobile.ChangesEntry
import uniffi.herdr_mobile.ChangesInterface
import uniffi.herdr_mobile.ChangesList
import uniffi.herdr_mobile.ChangesView

// Spec "The screen follows the agent while it shows", "A Changes button opens
// the agent's changes" (task 4.3): the loop the screen runs while it is shown
// and the phone's screen is on, and where back goes.
class ChangesLoopTest {
    private val listed = ChangesView(ChangesList.Listed(listOf(ChangesEntry("a.txt", "changed", 1uL, 0uL, true)), false), null, null)

    /** Scripted [waitChange] answers; when they run out the loop is cancelled, as on leaving or the screen going off. */
    private class Fake(val waits: List<Boolean>, val view: ChangesView) : ChangesInterface {
        val calls = mutableListOf<String>()
        var job: Job? = null
        private var at = 0
        override fun read(): ChangesView { calls += "read"; return view }
        override fun open(path: String?): ChangesView { calls += "open $path"; return view }
        override fun waitChange(timeoutMs: UInt): Boolean {
            calls += "wait"
            if (at == waits.size) { job?.cancel(); return false }
            return waits[at++]
        }
        override fun leave() { calls += "leave" }
    }

    private fun follow(fake: Fake): List<ChangesView> {
        val shown = mutableListOf<ChangesView>()
        runBlocking {
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { followChanges(fake, waitMs = 5_000u) { shown += it } }
            fake.job = job
            job.start()
            job.join()
        }
        return shown
    }

    @Test
    fun readsOnOpeningAndShowsIt() {
        val f = Fake(emptyList(), listed)
        val shown = follow(f)
        assertEquals(listOf(listed), shown)
        assertEquals("read", f.calls.first())
    }

    @Test
    fun readsAgainOnlyWhenTheHubAsks() {
        val f = Fake(listOf(false, false, true, false, true), listed)
        follow(f)
        assertEquals(
            listOf("read", "wait", "wait", "wait", "read", "wait", "wait", "read", "wait", "leave"),
            f.calls,
        )
    }

    @Test
    fun leavingTellsTheHubAndReadsNothingAfter() {
        val f = Fake(listOf(true), listed)
        follow(f)
        assertEquals("leave", f.calls.last())
        assertEquals(1, f.calls.count { it == "leave" })
        assertEquals(f.calls.indexOfLast { it == "read" } + 2, f.calls.lastIndex)
    }

    @Test
    fun theLoopNeverOpensOrSendsAnything() {
        val f = Fake(listOf(true, true, false), listed)
        follow(f)
        assertEquals(setOf("read", "wait", "leave"), f.calls.toSet())
    }

    /** A read the hub is still running when the screen goes off: the hub hears at once, not after it. */
    @Test
    fun leavingDoesNotWaitForARunningRead() {
        val inRead = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val slow = object : ChangesInterface {
            override fun read(): ChangesView {
                calls += "read"
                inRead.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                calls += "read done"
                return listed
            }
            override fun open(path: String?): ChangesView = listed
            override fun waitChange(timeoutMs: UInt): Boolean = false
            override fun leave() { calls += "leave" }
        }
        runBlocking {
            val job = launch(Dispatchers.Default) { followChanges(slow) {} }
            inRead.await()
            job.cancel()
            val deadline = System.currentTimeMillis() + 5_000
            while ("leave" !in calls && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(listOf("read", "leave"), calls.toList())
            release.countDown()
            job.join()
        }
        assertEquals(listOf("read", "leave", "read done"), calls.toList())
    }

    // --- Back and taps, drawn before the hub answers ---

    @Test
    fun backFromADiffGoesToTheListThenCloses() {
        val open = listed.copy(open = "a.txt", diff = ChangesDiff.Reading)
        assertEquals(ChangesBack.ToList, backFrom(open))
        assertEquals(ChangesBack.Close, backFrom(listed))
    }

    @Test
    fun aTapShowsTheFileReadingAtOnce() {
        val v = opening(listed, "a.txt")
        assertEquals("a.txt", v.open)
        assertEquals(ChangesDiff.Reading, v.diff)
        assertEquals(listed.list, v.list)
    }

    @Test
    fun backToTheListDropsTheDiff() {
        val v = toList(listed.copy(open = "a.txt", diff = ChangesDiff.NoLongerChanged))
        assertNull(v.open)
        assertNull(v.diff)
        assertEquals(listed.list, v.list)
    }
}
