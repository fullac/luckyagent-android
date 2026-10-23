package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.api.TaskSummary
import com.luckyagent.android.data.api.executionSession
import com.luckyagent.android.data.api.isTerminalTaskStatus

/**
 * Tasks that belong to the open chat, so the session card does not become a
 * second copy of the global task list.
 */
internal fun tasksForSession(tasks: List<TaskSummary>, sessionId: String): List<TaskSummary> {
    val owner = sessionId.trim()
    if (owner.isEmpty()) return emptyList()
    return tasks.filter { task ->
        task.parentSessionId?.trim() == owner ||
            task.metadata["parent_session_id"]?.trim() == owner ||
            task.metadata["owner_session_id"]?.trim() == owner ||
            task.sessionId?.trim() == owner ||
            task.executionSession()?.trim() == owner
    }
}

internal fun sessionNavigationTask(tasks: List<TaskSummary>): TaskSummary? =
    tasks.firstOrNull { it.status.taskNeedsAttention() }
        ?: tasks.firstOrNull { it.status.taskIsActive() && !it.status.isTerminalTaskStatus() }
        ?: tasks.maxByOrNull { it.lastActivityAt ?: it.completedAt ?: it.createdAt ?: "" }
