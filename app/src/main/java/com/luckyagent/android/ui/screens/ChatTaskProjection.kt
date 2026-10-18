package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.api.TaskSummary
import com.luckyagent.android.data.api.executionSession
import com.luckyagent.android.ui.ChatBubble
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Ownership is separate from the execution-chat deep link. */
internal fun TaskSummary.belongsToChatSession(session: String): Boolean = session.isNotBlank() && sequenceOf(
    parentSessionId,
    metadata["parent_session_id"],
    metadata["owner_session_id"],
    sessionId,
    executionSession(),
).any { it?.trim() == session.trim() }

private val subagentTools = setOf("subagent", "delegate_task", "delegate_status", "wait_for_tasks", "delegate_cancel")

private fun String?.taskObject(): JsonObject? = this?.let {
    runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
}

private fun JsonObject.taskText(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

/** Only explicit task references from this process fold participate in its graph. */
internal fun collabTasksForTurn(
    steps: List<ChatBubble>,
    tasks: List<TaskSummary>,
    session: String,
): List<TaskSummary> {
    val argumentsByCall = steps.mapNotNull { step ->
        step.stepId?.let { id -> step.toolArgs.taskObject()?.let { id to it } }
    }.toMap()
    val references = linkedMapOf<String, JsonObject>()
    fun rememberTask(id: String, value: JsonObject) {
        references[id] = JsonObject(references[id].orEmpty() + value)
    }
    fun collect(value: JsonObject, includeTaskList: Boolean = true) {
        (value.taskText("task_id") ?: value.taskText("id"))?.let { rememberTask(it, value) }
        (value["task_ids"] as? JsonArray)?.forEach { raw ->
            (raw as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let {
                references.putIfAbsent(it, JsonObject(emptyMap()))
            }
        }
        (value["tasks"] as? JsonArray)?.takeIf { includeTaskList }?.forEach { raw ->
            (raw as? JsonObject)?.let { task ->
                (task.taskText("task_id") ?: task.taskText("id"))?.let { rememberTask(it, task) }
            }
        }
    }
    steps.filter { it.role == "tool" && it.toolName?.lowercase() in subagentTools }.forEach { step ->
        val args = step.toolArgs.taskObject() ?: argumentsByCall[step.stepId]
        // Listing tasks is a global inspection, not a new collaboration group.
        if (args?.taskText("action")?.lowercase() == "list") return@forEach
        args?.let { collect(it) }
        (step.toolOutput ?: step.content).taskObject()?.let {
            // A page can begin with an unpaired list/wait result. Its global
            // task array is ambiguous until the matching call is available.
            collect(it, includeTaskList = args != null || step.toolName == "wait_for_tasks")
        }
    }
    val byId = tasks.associateBy { it.id }
    val scoped = references.mapNotNull { (id, wire) ->
        val metadata = (wire["metadata"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.toMap()
        val task = byId[id] ?: TaskSummary(
            id = id,
            parentId = wire.taskText("parent_id"),
            sessionId = wire.taskText("session_id"),
            parentSessionId = wire.taskText("parent_session_id"),
            executionSessionId = wire.taskText("execution_session_id"),
            role = wire.taskText("role"),
            source = wire.taskText("source") ?: "tool",
            mode = wire.taskText("mode") ?: "single",
            status = wire.taskText("status") ?: "pending",
            description = wire.taskText("description") ?: id,
            metadata = metadata,
        )
        val hasOwner = sequenceOf(task.parentSessionId, task.metadata["parent_session_id"], task.metadata["owner_session_id"])
            .any { !it.isNullOrBlank() }
        // Old servers may only expose the task ID in this turn's tool response.
        task.takeIf { session.isNotBlank() && (it.belongsToChatSession(session) || !hasOwner) }
    }
    val ids = scoped.mapTo(mutableSetOf()) { it.id }
    // An explicitly referenced group already represents its children.
    return scoped.filter { it.parentId !in ids }
}
