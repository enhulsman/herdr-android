package dev.kudde.android.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.kudde_mobile.ChatFound
import uniffi.kudde_mobile.ChatHealth
import uniffi.kudde_mobile.ChatPoll

// The switch and the view shown (spec "The switch shows only while the chat
// can follow", "The chosen view stays when switching agents", "After the
// agent exits, the chat stays read-only", the health guard's "the switch
// stays"; design D12: a held chat shows at once).
class ChatSwitchTest {
    private fun poll(
        found: ChatFound = ChatFound.FOUND,
        health: ChatHealth = ChatHealth.Ok,
        exited: Boolean = false,
        answered: Boolean = true,
    ) = ChatPoll(
        found = found, answered = answered, agent = "claude", title = "", version = "", status = "idle", health = health, problem = "",
        exited = exited, canSend = !exited, cut = false, rows = emptyList(), gaugeUsed = null, gaugeWindow = null,
        gaugePercent = null, queued = emptyList(), timings = "",
    )

    private val broken = ChatHealth.Broken("message records renamed")

    @Test
    fun theSwitchShowsForFoundOrEmptyWhileOkOrDegraded() {
        assertTrue(switchShown(poll(), before = false))
        assertTrue(switchShown(poll(ChatFound.EMPTY), before = false))
        assertTrue(switchShown(poll(health = ChatHealth.Degraded), before = false))
        assertFalse("a plain shell", switchShown(poll(ChatFound.NONE), before = false))
        assertFalse(switchShown(poll(ChatFound.MISSED), before = false))
        assertFalse("Broken when the terminal is opened", switchShown(poll(health = broken), before = false))
    }

    @Test
    fun aLogTurningBrokenKeepsTheSwitchItAlreadyShowed() {
        assertTrue(switchShown(poll(health = broken), before = true))
        assertFalse("the agent went away", switchShown(poll(ChatFound.MISSED), before = true))
    }

    @Test
    fun theTerminalIsTheFirstView() {
        val s = ChatSwitch(chosen = false, held = null)
        assertFalse(s.chat)
        s.onPoll(poll())
        assertFalse("the switch shows, the terminal stays", s.chat)
        assertTrue(s.offered)
    }

    @Test
    fun aTapOnChatShowsItAndChoosesIt() {
        val s = ChatSwitch(chosen = false, held = null)
        s.onPoll(poll())
        s.toChat()
        assertTrue(s.chat)
        assertTrue(s.chosen)
        s.toTerminal()
        assertFalse(s.chat)
        assertFalse(s.chosen)
        assertTrue("the switch stays on the terminal", s.offered)
    }

    @Test
    fun chatChosenOpensTheNextAgentOnItsChat() {
        val s = ChatSwitch(chosen = true, held = null)
        assertTrue("looking for the conversation meanwhile", s.chat)
        s.onPoll(poll())
        assertTrue(s.chat)
    }

    @Test
    fun chatChosenOpensAPlainShellOnItsTerminalAndKeepsTheChoice() {
        val s = ChatSwitch(chosen = true, held = null)
        s.onPoll(poll(ChatFound.NONE))
        assertFalse(s.chat)
        assertFalse(s.offered)
        assertTrue("the next agent with a log opens on its chat", s.chosen)
    }

    @Test
    fun anAgentStartedInAWatchedShellLeavesTheTerminalOnScreen() {
        val s = ChatSwitch(chosen = true, held = null)
        s.onPoll(poll(ChatFound.NONE))
        s.onPoll(poll(ChatFound.EMPTY))
        assertFalse("no switch away while the person types there", s.chat)
        assertTrue(s.offered)
    }

    @Test
    fun aHeldChatShowsAtOnce() {
        val s = ChatSwitch(chosen = true, held = poll())
        assertTrue(s.chat)
        assertTrue(s.offered)
    }

    @Test
    fun aHeldChatWhoseAgentLeftMeanwhileFallsBackOnTheFirstPoll() {
        val s = ChatSwitch(chosen = true, held = poll())
        s.onPoll(poll(ChatFound.NONE, exited = true))
        assertFalse(s.chat)
    }

    @Test
    fun aChatOnScreenStaysWhenItsAgentExits() {
        val s = ChatSwitch(chosen = true, held = null)
        s.onPoll(poll())
        s.onPoll(poll(ChatFound.NONE, exited = true))
        assertTrue("read-only, The agent has exited", s.chat)
    }

    @Test
    fun aChatOpenedByHandSaysWhyInsteadOfSwitchingBack() {
        val s = ChatSwitch(chosen = false, held = null)
        s.onPoll(poll(ChatFound.EMPTY))
        s.toChat()
        s.onPoll(poll(ChatFound.MISSED))
        assertTrue("No conversation found, shown in the chat", s.chat)
    }

    @Test
    fun aChatOnScreenStaysWhenItsLogTurnsBroken() {
        val s = ChatSwitch(chosen = true, held = null)
        s.onPoll(poll())
        s.onPoll(poll(health = broken))
        assertTrue(s.chat)
        assertTrue(s.offered)
        s.toTerminal()
        s.onPoll(poll(health = broken))
        assertTrue("the switch stays", s.offered)
    }

    @Test
    fun aBrokenHeldChatOpensOnTheTerminalWithoutTheSwitch() {
        val s = ChatSwitch(chosen = true, held = poll(health = broken))
        assertFalse(s.chat)
        assertFalse(s.offered)
    }

    @Test
    fun anExitedAgentOpensOnTheTerminalWithoutTheSwitch() {
        val s = ChatSwitch(chosen = true, held = null)
        s.onPoll(poll(ChatFound.NONE, exited = true))
        assertFalse(s.chat)
        assertFalse(s.offered)
    }

    // Issue 58: back from another app after Android ended the app, the open
    // terminal comes back with a new chat whose first polls run before the
    // machine has reconnected. Those polls decide nothing.

    private val notConnected get() = poll(ChatFound.NONE, answered = false)

    @Test
    fun aChatOpenedAgainBeforeTheMachineReconnectsStaysTheChat() {
        val s = ChatSwitch(chosen = true, held = null)
        s.onPoll(notConnected)
        assertTrue("still looking for the conversation", s.chat)
        s.onPoll(notConnected)
        s.onPoll(poll())
        assertTrue(s.chat)
        assertTrue(s.offered)
    }

    @Test
    fun theFirstAnsweredPollStillOpensAPlainShellOnItsTerminal() {
        val s = ChatSwitch(chosen = true, held = null)
        s.onPoll(notConnected)
        s.onPoll(poll(ChatFound.NONE))
        assertFalse(s.chat)
        assertFalse(s.offered)
    }

    @Test
    fun anUnansweredPollKeepsTheSwitchItShowed() {
        val s = ChatSwitch(chosen = false, held = poll())
        s.onPoll(notConnected)
        assertTrue(s.offered)
        assertFalse(s.chat)
    }

    @Test
    fun anUnansweredHeldChatShowsAsLooking() {
        val s = ChatSwitch(chosen = true, held = poll(ChatFound.MISSED, answered = false))
        assertTrue(s.chat)
        assertFalse(s.offered)
        s.onPoll(poll())
        assertTrue(s.chat)
        assertTrue(s.offered)
    }

    @Test
    fun theChatSaysLookingUntilTheDeskAnswers() {
        assertTrue(chatLooking(null))
        assertTrue("not \"No conversation found\"", chatLooking(notConnected))
        assertFalse(chatLooking(poll()))
        assertFalse(chatLooking(poll(ChatFound.MISSED)))
    }
}
