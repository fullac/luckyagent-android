package com.luckyagent.android.data.cache

import com.luckyagent.android.data.api.ProviderMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SessionCacheWriteTest {
    @Test
    fun secondSaveOfTheSamePageDoesNotRewriteMessages() = kotlinx.coroutines.runBlocking {
        val dao = FakeDao()
        val cache = cache(dao)
        val page = page(start = 3, count = 5, messages = listOf(msg("a"), msg("b")))

        cache.savePage("http://127.0.0.1:9090", "s", "title", "t1", null, page, opened = true, now = 10)
        val stored = dao.messages.single { it.ordinal == 3 }.rawJson
        dao.replaceCalls = 0
        dao.patchCalls = 0
        dao.upsertMessageCalls = 0

        cache.savePage("http://127.0.0.1:9090", "s", "title", "t1", null, page, opened = true, now = 20)

        assertEquals(0, dao.replaceCalls)
        assertEquals(0, dao.patchCalls)
        assertEquals(0, dao.upsertMessageCalls)
        assertSame(stored, dao.messages.single { it.ordinal == 3 }.rawJson)
        assertEquals(20, dao.sessions.single().lastOpenedAt)
    }

    @Test
    fun newTailInsertsOnlyTheNewRow() = kotlinx.coroutines.runBlocking {
        val dao = FakeDao()
        val cache = cache(dao)
        cache.savePage(
            "http://127.0.0.1:9090",
            "s",
            "title",
            "t1",
            null,
            page(start = 3, count = 5, messages = listOf(msg("a"), msg("b"))),
            opened = true,
            now = 10,
        )
        val kept = dao.messages.single { it.ordinal == 3 }
        dao.replaceCalls = 0
        dao.patchCalls = 0

        cache.savePage(
            "http://127.0.0.1:9090",
            "s",
            "title",
            "t2",
            null,
            page(start = 3, count = 6, messages = listOf(msg("a"), msg("b"), msg("c"))),
            opened = true,
            now = 20,
        )

        assertEquals(0, dao.replaceCalls)
        assertEquals(1, dao.patchCalls)
        assertSame(kept, dao.messages.single { it.ordinal == 3 })
        assertEquals("c", dao.messages.single { it.ordinal == 5 }.content)
        assertEquals(listOf(3, 4, 5), dao.messages.map { it.ordinal })
    }

    @Test
    fun disjointPageStillReplaces() = kotlinx.coroutines.runBlocking {
        val dao = FakeDao()
        val cache = cache(dao)
        cache.savePage(
            "http://127.0.0.1:9090",
            "s",
            null,
            null,
            null,
            page(start = 0, count = 2, messages = listOf(msg("a"), msg("b"))),
            opened = false,
            now = 10,
        )
        dao.replaceCalls = 0

        cache.savePage(
            "http://127.0.0.1:9090",
            "s",
            null,
            null,
            null,
            page(start = 8, count = 10, messages = listOf(msg("x"), msg("y"))),
            opened = false,
            now = 20,
        )

        assertEquals(1, dao.replaceCalls)
        assertEquals(listOf(8, 9), dao.messages.map { it.ordinal })
    }

    private fun cache(dao: FakeDao) = SessionCacheRepository(
        dao,
        Json { ignoreUnknownKeys = true; explicitNulls = false },
    )

    private fun msg(content: String) = ProviderMessage(role = "user", content = content, createdAt = "c-$content")

    private fun page(start: Int, count: Int, messages: List<ProviderMessage>) = MessagePage(
        messages = messages,
        startIndex = start,
        messageCount = count,
        reachedOldest = start == 0,
    )
}

private class FakeDao : SessionCacheDao {
    val sessions = mutableListOf<CachedSessionEntity>()
    val messages = mutableListOf<CachedMessageEntity>()
    var replaceCalls = 0
    var patchCalls = 0
    var upsertMessageCalls = 0

    override suspend fun listSessions(endpoint: String): List<CachedSessionRow> = emptyList()

    override suspend fun session(endpoint: String, sessionId: String): CachedSessionEntity? =
        sessions.firstOrNull { it.endpoint == endpoint && it.sessionId == sessionId }

    override suspend fun messages(endpoint: String, sessionId: String): List<CachedMessageEntity> =
        messages.filter { it.endpoint == endpoint && it.sessionId == sessionId }.sortedBy { it.ordinal }

    override suspend fun upsertSession(entity: CachedSessionEntity) {
        sessions.removeAll { it.endpoint == entity.endpoint && it.sessionId == entity.sessionId }
        sessions += entity
    }

    override suspend fun upsertMessages(entities: List<CachedMessageEntity>) {
        upsertMessageCalls += 1
        entities.forEach { entity ->
            messages.removeAll {
                it.endpoint == entity.endpoint && it.sessionId == entity.sessionId && it.ordinal == entity.ordinal
            }
            messages += entity
        }
    }

    override suspend fun deleteMessages(endpoint: String, sessionId: String) {
        messages.removeAll { it.endpoint == endpoint && it.sessionId == sessionId }
    }

    override suspend fun deleteSession(endpoint: String, sessionId: String) {
        sessions.removeAll { it.endpoint == endpoint && it.sessionId == sessionId }
    }

    override suspend fun deleteMessagesBefore(endpoint: String, sessionId: String, minOrdinal: Int) {
        messages.removeAll { it.endpoint == endpoint && it.sessionId == sessionId && it.ordinal < minOrdinal }
    }

    override suspend fun deleteMessagesFrom(endpoint: String, sessionId: String, fromOrdinal: Int) {
        messages.removeAll { it.endpoint == endpoint && it.sessionId == sessionId && it.ordinal >= fromOrdinal }
    }

    override suspend fun sessionIdsByRecentOpen(endpoint: String): List<String> =
        sessions.filter { it.endpoint == endpoint }.map { it.sessionId }

    override suspend fun replaceMessages(
        session: CachedSessionEntity,
        messages: List<CachedMessageEntity>,
        minOrdinal: Int,
    ) {
        replaceCalls += 1
        upsertSession(session)
        deleteMessages(session.endpoint, session.sessionId)
        if (messages.isNotEmpty()) upsertMessages(messages)
        deleteMessagesBefore(session.endpoint, session.sessionId, minOrdinal)
    }

    override suspend fun patchMessages(
        session: CachedSessionEntity,
        inserts: List<CachedMessageEntity>,
        deleteBefore: Int,
        deleteFrom: Int,
    ) {
        patchCalls += 1
        upsertSession(session)
        deleteMessagesFrom(session.endpoint, session.sessionId, deleteFrom)
        deleteMessagesBefore(session.endpoint, session.sessionId, deleteBefore)
        if (inserts.isNotEmpty()) upsertMessages(inserts)
    }
}
