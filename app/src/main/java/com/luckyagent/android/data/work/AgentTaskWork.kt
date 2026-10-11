package com.luckyagent.android.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/** Schedules a phone-side watcher for a remote LuckyAgent task. */
object AgentTaskWork {
    private const val NAME_PREFIX = "luckyagent-task-"

    /**
     * Enqueue a phone-side watcher for either a unified task or an autonomy
     * queue task. The origin is part of the unique-work name so two APIs that
     * happen to reuse an ID cannot cancel each other's watcher.
     */
    fun enqueue(
        context: Context,
        taskId: String,
        executionSessionId: String? = null,
        origin: String = ORIGIN_UNIFIED,
    ) {
        val id = taskId.trim()
        if (id.isBlank()) return
        val normalizedOrigin = normalizeOrigin(origin)
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInputData(
                workDataOf(
                    AgentTaskWorker.KEY_TASK_ID to id,
                    AgentTaskWorker.KEY_SESSION_ID to executionSessionId?.trim().orEmpty(),
                    AgentTaskWorker.KEY_ORIGIN to normalizedOrigin,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(id, normalizedOrigin),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context, taskId: String, origin: String = ORIGIN_UNIFIED) {
        val id = taskId.trim()
        if (id.isNotBlank()) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(workName(id, normalizeOrigin(origin)))
        }
    }

    private fun workName(taskId: String, origin: String): String =
        if (origin == ORIGIN_UNIFIED) NAME_PREFIX + taskId else NAME_PREFIX + origin + "-" + taskId

    private fun normalizeOrigin(origin: String): String =
        if (origin.trim().equals(ORIGIN_AUTONOMY, ignoreCase = true)) ORIGIN_AUTONOMY else ORIGIN_UNIFIED

    const val ORIGIN_UNIFIED = "unified"
    const val ORIGIN_AUTONOMY = "autonomy"
}
