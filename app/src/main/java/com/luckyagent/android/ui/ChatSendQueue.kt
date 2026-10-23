package com.luckyagent.android.ui

import com.luckyagent.android.data.api.MediaAttachment
import com.luckyagent.android.data.api.ProviderMessage

/**
 * The runtime serializes turns per session. This class keeps the client-side
 * FIFO state while the ViewModel dispatcher hands one item to the runtime at a
 * time.
 */
enum class OutboundQueueStatus {
    Queued,
    Sending,
    Accepted,
    ServerQueued,
    Running,
    Failed,
}

data class OutboundQueueItem(
    val id: String,
    val sessionId: String,
    val preview: String,
    val message: String,
    val attachments: List<MediaAttachment> = emptyList(),
    val requestId: String? = null,
    val status: OutboundQueueStatus = OutboundQueueStatus.Queued,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Small synchronized store so ViewModel event callbacks can update queue state safely. */
class OutboundChatQueue {
    private val items = LinkedHashMap<String, OutboundQueueItem>()

    @Synchronized
    fun add(item: OutboundQueueItem) {
        items[item.id] = item
    }

    @Synchronized
    fun replace(nextItems: List<OutboundQueueItem>) {
        items.clear()
        nextItems.forEach { items[it.id] = it }
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    @Synchronized
    fun update(
        id: String,
        status: OutboundQueueStatus? = null,
        requestId: String? = null,
        error: String? = null,
        clearError: Boolean = false,
    ): OutboundQueueItem? {
        val current = items[id] ?: return null
        val next = current.copy(
            status = status ?: current.status,
            requestId = requestId ?: current.requestId,
            error = if (clearError) null else error ?: current.error,
        )
        items[id] = next
        return next
    }

    @Synchronized
    fun findByRequest(sessionId: String, requestId: String): OutboundQueueItem? =
        items.values.firstOrNull { it.sessionId == sessionId && it.requestId == requestId }

    /**
     * Next frame that has not reached the WebSocket yet. Multiple frames may be
     * in flight: the runtime owns the server-side turn queue and reports whether
     * each request is accepted or waiting behind another run.
     */
    @Synchronized
    fun firstQueued(sessionId: String): OutboundQueueItem? {
        val sessionItems = items.values.filter { it.sessionId == sessionId }
        return sessionItems.firstOrNull { it.status == OutboundQueueStatus.Queued }
    }

    @Synchronized
    fun sessionIds(): Set<String> = items.values.map { it.sessionId }.toSet()

    @Synchronized
    fun remove(id: String): OutboundQueueItem? = items.remove(id)

    @Synchronized
    fun removeSession(sessionId: String) {
        items.entries.removeIf { it.value.sessionId == sessionId }
    }

    @Synchronized
    fun items(sessionId: String): List<OutboundQueueItem> =
        items.values.filter { it.sessionId == sessionId }
}

/**
 * Outbox rows the server already stored.
 *
 * A request id is authoritative when the server echoes it back as
 * `client_message_id`. Until then a stored user message with identical text
 * consumes one outstanding row, oldest first, so a repeated phrase still leaves
 * the later copy pending.
 */
internal fun confirmedOutboundIds(
    items: List<OutboundQueueItem>,
    messages: List<ProviderMessage>,
): Set<String> {
    if (items.isEmpty()) return emptySet()
    val storedIds = messages.mapNotNull { it.clientMessageId?.trim()?.takeIf(String::isNotBlank) }.toSet()
    val storedText = mutableMapOf<String, Int>()
    messages.forEach { message ->
        if (!message.role.equals("user", ignoreCase = true)) return@forEach
        val text = message.content?.trim().orEmpty()
        if (text.isEmpty()) return@forEach
        storedText[text] = (storedText[text] ?: 0) + 1
    }
    val confirmed = mutableSetOf<String>()
    items.sortedBy(OutboundQueueItem::createdAt).forEach { item ->
        if (item.id in storedIds) {
            confirmed += item.id
            return@forEach
        }
        val text = item.message.trim()
        val remaining = storedText[text] ?: 0
        if (remaining > 0) {
            storedText[text] = remaining - 1
            confirmed += item.id
        }
    }
    return confirmed
}
