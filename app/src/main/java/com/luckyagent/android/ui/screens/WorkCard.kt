package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.api.AutonomyTaskSummary
import com.luckyagent.android.data.api.TaskSummary
import com.luckyagent.android.data.api.executionSession
import com.luckyagent.android.data.api.isTerminalTaskStatus

internal enum class WorkCardKind { Task, Background }

/**
 * One thing the user asked the computer to do. The chat card, the return
 * target, and the notification share this choice so a tap does not land on a
 * module list.
 */
internal data class WorkCard(
    val id: String,
    val kind: WorkCardKind,
    val title: String,
    val status: String,
    val sessionId: String?,
    val active: Boolean,
    val terminal: Boolean,
    val loading: Boolean = false,
)

internal fun workCardsForSession(
    tasks: List<TaskSummary>,
    background: List<AutonomyTaskSummary>,
    sessionId: String,
): List<WorkCard> {
    val owner = sessionId.trim()
    if (owner.isEmpty()) return emptyList()
    val taskCards = tasks
        .filter { taskBelongsToSession(owner, it.parentSessionId, it.sessionId, it.executionSession(), it.metadata) }
        .map { it.toWorkCard() }
    val boardCards = background
        .filter { taskBelongsToSession(owner, it.parentSessionId, it.sessionId, it.executionSession(), it.metadata) }
        .map { it.toWorkCard() }
    return (taskCards + boardCards).sortedWith(
        compareByDescending<WorkCard> { it.active }
            .thenByDescending { it.terminal }
            .thenBy { it.title },
    )
}

internal fun featuredWorkCard(cards: List<WorkCard>): WorkCard? =
    cards.firstOrNull { it.active } ?: cards.firstOrNull { it.terminal }

/**
 * The card the user should see. A notification focus wins even when it belongs
 * to another session. Otherwise the open chat shows its own running or just
 * finished item.
 */
internal fun visibleWorkCard(
    focusedId: String?,
    focusedBackground: Boolean,
    tasks: List<TaskSummary>,
    background: List<AutonomyTaskSummary>,
    sessionId: String,
): WorkCard? {
    val focus = focusedId?.trim().orEmpty()
    if (focus.isNotEmpty()) {
        val source = if (focusedBackground) {
            background.filter { it.id == focus }.map { it.toWorkCard() }
        } else {
            tasks.filter { it.id == focus }.map { it.toWorkCard() }
        }
        return source.firstOrNull() ?: WorkCard(
            id = focus,
            kind = if (focusedBackground) WorkCardKind.Background else WorkCardKind.Task,
            title = "正在读取这件事",
            status = "",
            sessionId = null,
            active = true,
            terminal = false,
            loading = true,
        )
    }
    return featuredWorkCard(workCardsForSession(tasks, background, sessionId))
}

/**
 * Where a notification should land. A linked session opens that chat. A task
 * without one stays on its card. A bare module name is not a landing target.
 */
internal fun notificationLanding(sessionId: String?, taskId: String?, destination: String): String {
    val session = sessionId?.trim().orEmpty()
    if (session.isNotEmpty()) return "session:$session"
    val task = taskId?.trim().orEmpty()
    if (task.isNotEmpty()) {
        return if (destination == "background") "background-card:$task" else "task-card:$task"
    }
    return "none"
}

internal fun workCardStatusLabel(status: String): String = when (status.lowercase()) {
    "pending", "queued", "ready" -> "排队中"
    "running", "in_progress", "in-progress" -> "进行中"
    "blocked", "waiting" -> "需要处理"
    "completed", "done", "succeeded" -> "已完成"
    "failed", "error" -> "失败"
    "timeout" -> "超时"
    "cancelled", "canceled" -> "已取消"
    else -> if (status.isBlank()) "进行中" else status
}

/** Unified and legacy tasks have a cancel endpoint. The autonomy queue does not. */
internal fun workCardCanCancel(card: WorkCard): Boolean =
    card.kind == WorkCardKind.Task && card.active && !card.terminal

private fun TaskSummary.toWorkCard(): WorkCard {
    val terminal = status.isTerminalTaskStatus() || status.equals("done", true)
    return WorkCard(
        id = id,
        kind = WorkCardKind.Task,
        title = description.ifBlank { id },
        status = status,
        sessionId = executionSession(),
        active = !terminal,
        terminal = terminal,
    )
}

private fun AutonomyTaskSummary.toWorkCard(): WorkCard {
    val terminal = state.isTerminalTaskStatus() ||
        state.equals("done", true) ||
        state.equals("succeeded", true)
    return WorkCard(
        id = id,
        kind = WorkCardKind.Background,
        title = title.ifBlank { description.ifBlank { id } },
        status = state,
        sessionId = executionSession(),
        active = !terminal,
        terminal = terminal,
    )
}

private fun taskBelongsToSession(
    owner: String,
    parentSessionId: String?,
    sessionId: String?,
    executionSession: String?,
    metadata: Map<String, String>,
): Boolean =
    parentSessionId?.trim() == owner ||
        metadata["parent_session_id"]?.trim() == owner ||
        metadata["owner_session_id"]?.trim() == owner ||
        sessionId?.trim() == owner ||
        executionSession?.trim() == owner
