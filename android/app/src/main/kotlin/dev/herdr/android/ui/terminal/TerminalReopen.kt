package dev.herdr.android.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * An open terminal across background idle (stay-connected design D5).
 * [TerminalSession] and [TerminalOpener] are single-use, so a reopen is a new
 * [generation]: an idle that lands while the screen is stopped closes the
 * session and keeps its last frame in memory; the next start bumps
 * [generation], and the new session is seeded from that frame ([takeSeed])
 * rather than the disk cache, so no Keystore decrypt is needed. An idle
 * while started is ignored: the person is back, as the tracker's catch-up
 * rule has it. Main thread only.
 */
class TerminalReopen {
    /** Keys the session, the opener and the cache load; bumps on each reopen. */
    var generation by mutableIntStateOf(0)
        private set

    /** The detached session's last live screen, until the new session takes it. */
    var lastFrame: ByteArray? = null
        private set

    private var stopped = false
    private var detached = false

    fun onStop() {
        stopped = true
    }

    /** True when the terminal was detached, so a new generation opens it again. */
    fun onStart(): Boolean {
        stopped = false
        if (!detached) return false
        detached = false
        generation++
        return true
    }

    /** Background idle; [close] detaches the session and returns its last frame. True when it closed. */
    fun onIdle(close: () -> ByteArray?): Boolean {
        if (!stopped || detached) return false
        lastFrame = close()
        detached = true
        return true
    }

    /** The frame to show until the live first paint, once; null: load from the disk cache as usual. */
    fun takeSeed(): ByteArray? = lastFrame.also { lastFrame = null }
}
