package com.luckyagent.android.data.cache

import android.content.Context
import androidx.room.Room
import com.luckyagent.android.data.api.ProviderMessage
import com.luckyagent.android.data.api.RuntimeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class SessionCacheRepository internal constructor(
    private val dao: SessionCacheDao,
    private val json: Json,
) {
    constructor(
        context: Context,
        database: SessionCacheDatabase = Room.databaseBuilder(
            context.applicationContext,
            SessionCacheDatabase::class.java,
            "luckyagent_session_cache.db",
        ).fallbackToDestructiveMigration().build(),
        json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    ) : this(database.dao(), json)

    /**
     * Isolate cached data by both runtime URL and credential. The same runtime
     * can serve different accounts, so apiBase alone is not a safe namespace.
     */
    fun endpointKey(apiBase: String): String = apiBase.trim().trimEnd('/').lowercase()

    private fun cacheKey(apiBase: String, apiKey: String): String {
        val normalizedBase = endpointKey(apiBase)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$normalizedBase#key:$digest"
    }

    suspend fun listSessions(apiBase: String, apiKey: String = ""): List<RuntimeSession> =
        dao.listSessions(cacheKey(apiBase, apiKey)).map { row ->
            RuntimeSession(
                id = row.sessionId,
                title = row.title,
                pinned = row.pinned,
                project = row.project,
                messageCount = row.messageCount,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
            )
        }

    suspend fun sessionMeta(apiBase: String, sessionId: String, apiKey: String = ""): CachedSessionMeta? {
        val row = dao.session(cacheKey(apiBase, apiKey), sessionId) ?: return null
        if (row.startIndex < 0) return null
        return CachedSessionMeta(
            messageCount = row.messageCount,
            updatedAt = row.updatedAt,
            reachedOldest = row.reachedOldest,
        )
    }

    suspend fun loadPage(apiBase: String, sessionId: String, apiKey: String = ""): MessagePage? {
        val endpoint = cacheKey(apiBase, apiKey)
        return readPage(endpoint, dao.session(endpoint, sessionId))
    }

    /**
     * The stored page, or null when the cache is missing, undecodable, or no
     * longer a contiguous run from [CachedSessionEntity.startIndex]. Diffing
     * against a page with gaps would patch the wrong ordinals.
     */
    private suspend fun readPage(endpoint: String, session: CachedSessionEntity?): MessagePage? {
        if (session == null || session.startIndex < 0) return null
        val stored = dao.messages(endpoint, session.sessionId)
        if (stored.isEmpty() && session.messageCount > 0) return null
        val messages = withContext(Dispatchers.Default) {
            stored.map { entity -> messageFromCache(entity) ?: return@withContext null }
        } ?: return null
        if (stored.withIndex().any { (index, entity) -> entity.ordinal != session.startIndex + index }) return null
        return MessagePage(
            messages = messages,
            startIndex = session.startIndex,
            messageCount = session.messageCount,
            reachedOldest = session.reachedOldest,
        )
    }

    suspend fun touchOpened(apiBase: String, sessionId: String, openedAt: Long = System.currentTimeMillis(), apiKey: String = "") {
        val endpoint = cacheKey(apiBase, apiKey)
        val existing = dao.session(endpoint, sessionId) ?: return
        dao.upsertSession(existing.copy(lastOpenedAt = openedAt))
    }

    suspend fun saveSessionList(apiBase: String, sessions: List<RuntimeSession>, now: Long = System.currentTimeMillis(), apiKey: String = "") {
        val endpoint = cacheKey(apiBase, apiKey)
        val remoteIds = sessions.map { it.id }.toSet()
        sessions.forEach { remote ->
            val existing = dao.session(endpoint, remote.id)
            dao.upsertSession(
                CachedSessionEntity(
                    endpoint = endpoint,
                    sessionId = remote.id,
                    title = remote.title ?: existing?.title,
                    pinned = remote.pinned,
                    project = remote.project,
                    messageCount = remote.messageCount ?: existing?.messageCount ?: 0,
                    createdAt = remote.createdAt ?: existing?.createdAt,
                    updatedAt = remote.updatedAt ?: existing?.updatedAt,
                    cachedAt = now,
                    lastOpenedAt = existing?.lastOpenedAt ?: 0L,
                    reachedOldest = existing?.reachedOldest ?: false,
                    startIndex = existing?.startIndex ?: -1,
                ),
            )
        }
        dao.sessionIdsByRecentOpen(endpoint)
            .filter { it !in remoteIds }
            .forEach { stale ->
                dao.deleteMessages(endpoint, stale)
                dao.deleteSession(endpoint, stale)
            }
        evictClosedSessions(endpoint)
    }

    suspend fun savePage(
        apiBase: String,
        sessionId: String,
        title: String?,
        updatedAt: String?,
        createdAt: String?,
        page: MessagePage,
        opened: Boolean,
        now: Long = System.currentTimeMillis(),
        apiKey: String = "",
        /** A page the caller already read via [loadPage]; avoids decoding the cache twice. */
        stored: MessagePage? = null,
    ) {
        val endpoint = cacheKey(apiBase, apiKey)
        val capped = capLatestMessages(page)
        val existing = dao.session(endpoint, sessionId)
        val storedPage = stored ?: readPage(endpoint, existing)
        val session = sessionEntity(endpoint, sessionId, title, updatedAt, createdAt, capped, existing, opened, now)
        when (val write = historyCacheWrite(storedPage, capped)) {
            HistoryCacheWrite.Unchanged -> {
                if (existing == null || sessionRowChanged(existing, session)) dao.upsertSession(session)
            }
            is HistoryCacheWrite.Patch -> {
                val inserts = withContext(Dispatchers.Default) {
                    write.inserts.map { item ->
                        messageEntity(endpoint, sessionId, item.ordinal, item.message)
                    }
                }
                dao.patchMessages(
                    session = session,
                    inserts = inserts,
                    deleteBefore = write.deleteBefore,
                    deleteFrom = write.deleteFrom,
                )
            }
            HistoryCacheWrite.Replace -> {
                val messages = withContext(Dispatchers.Default) {
                    capped.messages.mapIndexed { index, message ->
                        messageEntity(endpoint, sessionId, capped.startIndex + index, message)
                    }
                }
                dao.replaceMessages(
                    session = session,
                    messages = messages,
                    minOrdinal = capped.startIndex,
                )
            }
        }
        evictClosedSessions(endpoint)
    }

    private fun sessionRowChanged(existing: CachedSessionEntity, next: CachedSessionEntity): Boolean =
        existing.title != next.title ||
            existing.pinned != next.pinned ||
            existing.project != next.project ||
            existing.messageCount != next.messageCount ||
            existing.createdAt != next.createdAt ||
            existing.updatedAt != next.updatedAt ||
            existing.lastOpenedAt != next.lastOpenedAt ||
            existing.reachedOldest != next.reachedOldest ||
            existing.startIndex != next.startIndex

    private fun sessionEntity(
        endpoint: String,
        sessionId: String,
        title: String?,
        updatedAt: String?,
        createdAt: String?,
        page: MessagePage,
        existing: CachedSessionEntity?,
        opened: Boolean,
        now: Long,
    ) = CachedSessionEntity(
        endpoint = endpoint,
        sessionId = sessionId,
        title = title ?: existing?.title,
        pinned = existing?.pinned ?: false,
        project = existing?.project,
        messageCount = page.messageCount,
        createdAt = createdAt ?: existing?.createdAt,
        updatedAt = updatedAt ?: existing?.updatedAt,
        cachedAt = now,
        lastOpenedAt = if (opened) now else existing?.lastOpenedAt ?: 0L,
        reachedOldest = page.reachedOldest,
        startIndex = page.startIndex,
    )

    private fun messageEntity(
        endpoint: String,
        sessionId: String,
        ordinal: Int,
        message: ProviderMessage,
    ) = CachedMessageEntity(
        endpoint = endpoint,
        sessionId = sessionId,
        ordinal = ordinal,
        role = message.role,
        content = message.content,
        createdAt = message.createdAt,
        toolCallId = message.toolCallId,
        // A preview is not the stored body. Leave rawJson empty so a later open
        // cannot decode the cap (including a trailing truncation marker) as complete.
        rawJson = if (message.contentTruncated) "" else json.encodeToString(message),
    )

    suspend fun removeSession(apiBase: String, sessionId: String, apiKey: String = "") {
        val endpoint = cacheKey(apiBase, apiKey)
        dao.deleteMessages(endpoint, sessionId)
        dao.deleteSession(endpoint, sessionId)
    }

    suspend fun invalidate(apiBase: String, sessionId: String, apiKey: String = "") {
        val endpoint = cacheKey(apiBase, apiKey)
        val existing = dao.session(endpoint, sessionId) ?: return
        dao.deleteMessages(endpoint, sessionId)
        dao.upsertSession(
            existing.copy(
                startIndex = -1,
                reachedOldest = false,
                cachedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun evictClosedSessions(endpoint: String) {
        val ids = dao.sessionIdsByRecentOpen(endpoint)
        if (ids.size <= SessionCachePolicy.MAX_SESSIONS) return
        ids.drop(SessionCachePolicy.MAX_SESSIONS).forEach { stale ->
            dao.deleteMessages(endpoint, stale)
            dao.deleteSession(endpoint, stale)
        }
    }

    /**
     * Plain rows rebuild from the structured columns. rawJson is decoded only
     * when the row carries fields those columns cannot hold, such as attachments
     * or tool calls. An empty rawJson is a truncated preview, not a full body.
     */
    private fun messageFromCache(entity: CachedMessageEntity): ProviderMessage? {
        if (entity.rawJson.isEmpty()) {
            return ProviderMessage(
                role = entity.role,
                content = entity.content,
                createdAt = entity.createdAt,
                toolCallId = entity.toolCallId,
                contentTruncated = true,
            )
        }
        if (!rawJsonNeeded(entity.rawJson)) {
            return ProviderMessage(
                role = entity.role,
                content = entity.content,
                createdAt = entity.createdAt,
                toolCallId = entity.toolCallId,
            )
        }
        return decodeMessage(entity.rawJson)
    }

    private fun rawJsonNeeded(raw: String): Boolean {
        // Empty lists and nulls are omitted (explicitNulls = false), so a hit
        // means the row actually carries that field.
        if (raw.contains("\"attachments\"")) return true
        if (raw.contains("\"tool_calls\"")) return true
        if (raw.contains("\"content_parts\"")) return true
        if (raw.contains("\"reasoning_content\"")) return true
        if (raw.contains("\"usage\"")) return true
        if (raw.contains("\"blob_hash\"")) return true
        if (raw.contains("\"name\"")) return true
        return false
    }

    private fun decodeMessage(raw: String): ProviderMessage? =
        runCatching { json.decodeFromString(ProviderMessage.serializer(), raw) }.getOrNull()
}
