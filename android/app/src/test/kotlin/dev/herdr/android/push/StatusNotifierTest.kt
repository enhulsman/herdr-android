package dev.herdr.android.push

import org.junit.Assert.assertEquals
import org.junit.Test

// stay-connected spec, "The status notification shows counts only": reposted
// only when its text changes (progress ticks post nothing), and nothing after
// the option is turned off (design D1: the service's collection may deliver
// one more snapshot after stop). The status notification has its own sink, so
// it cannot touch an agent alert ("Alerts stay with ntfy").
class StatusNotifierTest {
    private val sink = FakeStatusSink()
    private val notifier = StatusNotifier(sink)

    @Test
    fun theFirstLineIsPosted() {
        notifier.update(StatusLine("1 needs you", "Connected"))
        assertEquals(listOf(StatusLine("1 needs you", "Connected")), sink.posted)
    }

    @Test
    fun anEqualLineIsNotReposted() {
        repeat(5) { notifier.update(StatusLine("3 working", "Connected")) }
        assertEquals(1, sink.posted.size)
    }

    @Test
    fun aChangedLineIsPostedAndChangingBackPostsAgain() {
        notifier.update(StatusLine("3 working", "Connected"))
        notifier.update(StatusLine("Reconnecting", null))
        notifier.update(StatusLine("3 working", "Connected"))
        assertEquals(3, sink.posted.size)
    }

    @Test
    fun stopCancelsAndLaterUpdatesPostNothing() {
        notifier.update(StatusLine("3 working", "Connected"))
        notifier.stop()
        notifier.update(StatusLine("1 needs you", "Connected"))
        assertEquals(1, sink.posted.size)
        assertEquals(1, sink.cancels)
    }
}

class FakeStatusSink : StatusSink {
    val posted = mutableListOf<StatusLine>()
    var cancels = 0

    override fun post(line: StatusLine) {
        posted += line
    }

    override fun cancel() {
        cancels++
    }
}
