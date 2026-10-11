package com.luckyagent.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BlackboardEntry(
    @SerialName("task_id") val taskId: String = "",
    val key: String = "",
    val value: String = "",
    val author: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class BlackboardResponse(
    @SerialName("task_id") val taskId: String? = null,
    val items: List<BlackboardEntry> = emptyList(),
)

/** A2A mailbox item emitted by collaborating agents for a task. */
@Serializable
data class AgentMessage(
    val id: String = "",
    val type: String = "",
    val from: String = "",
    val to: String = "",
    val priority: Int = 0,
    val payload: Map<String, JsonElement> = emptyMap(),
    val timestamp: String? = null,
    val correlation: String = "",
    val ttl: Int = 0,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class AgentMessagesResponse(
    val messages: List<AgentMessage> = emptyList(),
    val count: Int = 0,
)
