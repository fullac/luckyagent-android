package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.api.HistoryToolCall
import com.luckyagent.android.data.api.ProviderMessage
import com.luckyagent.android.data.api.WsEnvelope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratingIndicatorTest {
    @Test
    fun ownedLiveRunShowsGenerating() {
        assertEquals(
            ComposerActivityLabel.Generating,
            composerActivityLabel(agentWorking = true, commandExecuting = false, streaming = false),
        )
        assertEquals(
            ComposerActivityLabel.Generating,
            composerActivityLabel(agentWorking = false, commandExecuting = false, streaming = true),
        )
    }

    @Test
    fun commandWithoutAgentShowsExecuting() {
        assertEquals(
            ComposerActivityLabel.Executing,
            composerActivityLabel(agentWorking = false, commandExecuting = true, streaming = false),
        )
    }

    @Test
    fun idleComposerStaysHidden() {
        assertEquals(
            ComposerActivityLabel.Hidden,
            composerActivityLabel(agentWorking = false, commandExecuting = false, streaming = false),
        )
    }

    @Test
    fun emptyTaskListDoesNotDecideTheLabel() {
        val hidden = composerActivityLabel(agentWorking = false, commandExecuting = false, streaming = false)
        val generating = composerActivityLabel(agentWorking = true, commandExecuting = false, streaming = false)
        assertEquals(ComposerActivityLabel.Hidden, hidden)
        assertEquals(ComposerActivityLabel.Generating, generating)
    }

    @Test
    fun connectedStatusWithoutRequestDoesNotLightTurn() {
        assertTrue(connectionStatusLightsTurn("status", "connected", null))
        assertTrue(connectionStatusLightsTurn("status", "reconnected", ""))
        assertFalse(connectionStatusLightsTurn("status", "connected", "req-1"))
        assertFalse(connectionStatusLightsTurn("status", "executing", null))
    }

    @Test
    fun replayWithoutExecutingMarkDoesNotRegister() {
        val status = envelope(type = "status", state = "thinking")
        val reasoning = envelope(type = "reasoning")
        val tool = envelope(type = "tool_call")
        assertFalse(replayRegistersLiveRun(status))
        assertFalse(replayRegistersLiveRun(reasoning))
        assertFalse(replayRegistersLiveRun(tool))
    }

    @Test
    fun replayWithExecutingMarkRegisters() {
        val executing = envelope(type = "status", state = "executing")
        assertTrue(replayRegistersLiveRun(executing))
        assertTrue(explicitExecutingMark(executing, "req-1"))
    }

    @Test
    fun journalGapErrorClearsTheTurn() {
        val gap = WsEnvelope(
            type = "error",
            parentId = "req-1",
            runId = "req-1",
            error = "websocket journal sequence gap at byte 0",
        )
        assertTrue(isTerminalAgentEvent(gap))
        assertFalse(replayRegistersLiveRun(gap))
    }

    @Test
    fun terminalEventsDoNotReopenFromALaterStatus() {
        assertTrue(isTerminalAgentEvent(envelope(type = "stream_end")))
        assertTrue(isTerminalAgentEvent(envelope(type = "cancel")))
        assertTrue(isTerminalAgentEvent(envelope(type = "status", state = "idle")))
        assertFalse(replayRegistersLiveRun(envelope(type = "status", state = "idle")))
        assertFalse(replayRegistersLiveRun(envelope(type = "error", error = "request failed")))
    }

    @Test
    fun historyFinalReplyWithoutLeaseClearsIndicator() {
        val page = listOf(
            ProviderMessage(role = "user", content = "你好"),
            ProviderMessage(role = "assistant", content = "已回复"),
        )
        val settled = historyPageEndsWithFinalAssistant(page)
        assertTrue(settled)
        assertTrue(historySettlesUnownedTurn(settled, ownsOutboundLease = false))
        assertFalse(historySettlesUnownedTurn(settled, ownsOutboundLease = true))
    }

    @Test
    fun storedPageEndingOnAssistantSettles() {
        val page = listOf(
            ProviderMessage(role = "user", content = "你好"),
            ProviderMessage(role = "assistant", content = "已回复"),
        )
        assertTrue(historyPageEndsWithFinalAssistant(page))
        assertFalse(
            historyPageEndsWithFinalAssistant(
                listOf(ProviderMessage(role = "user", content = "还没回")),
            ),
        )
    }

    @Test
    fun openUserTurnDoesNotSettleFromHistory() {
        val page = listOf(ProviderMessage(role = "user", content = "还没回"))
        val settled = historyPageEndsWithFinalAssistant(page)
        assertFalse(settled)
        assertFalse(historySettlesUnownedTurn(settled, ownsOutboundLease = false))
    }

    @Test
    fun assistantTurnWaitingOnToolsDoesNotSettle() {
        // A reply that still owes a tool result is not final, so the replayed
        // run must survive history refresh.
        val page = listOf(
            ProviderMessage(role = "user", content = "查一下"),
            ProviderMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(HistoryToolCall(id = "call-1", name = "search")),
            ),
        )
        assertFalse(historyPageEndsWithFinalAssistant(page))
    }

    private fun envelope(
        type: String,
        state: String? = null,
        error: String? = null,
    ): WsEnvelope = WsEnvelope(
        type = type,
        parentId = "req-1",
        runId = "req-1",
        error = error,
        data = state?.let { buildJsonObject { put("state", it) } },
    )
}
