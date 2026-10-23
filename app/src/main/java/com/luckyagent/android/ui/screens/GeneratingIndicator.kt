package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.api.ProviderMessage
import com.luckyagent.android.data.api.WsEnvelope

/**
 * Composer label for the current session.
 * 「生成中」 follows a live agent turn. 「执行中」 follows a slash command.
 * An empty task list is not an input: a chat reply can generate with no task,
 * and a running task has its own return card.
 */
internal enum class ComposerActivityLabel {
    Generating,
    Executing,
    Hidden,
}

internal fun composerActivityLabel(
    agentWorking: Boolean,
    commandExecuting: Boolean,
    streaming: Boolean,
): ComposerActivityLabel = when {
    commandExecuting && !agentWorking && !streaming -> ComposerActivityLabel.Executing
    agentWorking || streaming -> ComposerActivityLabel.Generating
    else -> ComposerActivityLabel.Hidden
}

/**
 * Whether a replayed frame may register a run the phone does not already own.
 * Historical status, reasoning, and tool frames update records only.
 * Registration requires an explicit still-executing mark on that same frame.
 */
internal fun replayRegistersLiveRun(envelope: WsEnvelope): Boolean {
    val requestId = envelope.parentId?.takeIf { it.isNotBlank() } ?: return false
    if (envelope.runId.isNullOrBlank()) return false
    if (isTerminalAgentEvent(envelope)) return false
    return explicitExecutingMark(envelope, requestId)
}

/**
 * Connection notices are not a turn. A status frame with no request id used to
 * match a missing foreground id and re-light 「生成中」 on session switch.
 */
internal fun connectionStatusLightsTurn(type: String, state: String?, requestId: String?): Boolean {
    if (!requestId.isNullOrBlank()) return false
    if (type != "status" && type != "info") return false
    return state.equals("connected", ignoreCase = true) || state.equals("reconnected", ignoreCase = true)
}

/** End, cancel, and error close the turn. A later status frame must not reopen it. */
internal fun isTerminalAgentEvent(envelope: WsEnvelope): Boolean {
    if (!envelope.error.isNullOrBlank() && envelope.type !in setOf("status", "info", "compact")) {
        return true
    }
    return when (envelope.type) {
        "stream_end", "assistant_message", "final", "done", "chat_done", "message",
        "cancel", "cancelled", "error",
        -> true
        "status", "info" -> envelope.data.stateField()?.lowercase() == "idle"
        else -> false
    }
}

/**
 * Server states that mean this request is still executing.
 * Thinking, tool progress, and a plain status line are not enough.
 */
internal fun explicitExecutingMark(envelope: WsEnvelope, requestId: String): Boolean {
    if (envelope.parentId != requestId && envelope.runId != requestId) return false
    val state = envelope.data.stateField()?.lowercase() ?: return false
    return state in setOf("executing", "running", "in_progress", "in-progress")
}

/**
 * History already holds this turn's final assistant reply, and the phone does
 * not hold the outbound lease. Streaming leftovers must not keep 「生成中」.
 */
internal fun historySettlesUnownedTurn(
    historyEndsWithFinalAssistant: Boolean,
    ownsOutboundLease: Boolean,
): Boolean = historyEndsWithFinalAssistant && !ownsOutboundLease

/** True when the stored page already ends on a final assistant reply. */
internal fun historyPageEndsWithFinalAssistant(messages: List<ProviderMessage>): Boolean {
    val last = messages.lastOrNull { message ->
        val role = message.role?.lowercase()
        role == "assistant" || role == "user"
    } ?: return false
    return last.role.equals("assistant", ignoreCase = true) &&
        last.toolCalls.isEmpty() &&
        !last.content.isNullOrBlank()
}

private fun kotlinx.serialization.json.JsonElement?.stateField(): String? {
    val obj = this as? kotlinx.serialization.json.JsonObject ?: return null
    val value = obj["state"] as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return value.content.takeIf { it.isNotBlank() }
}
