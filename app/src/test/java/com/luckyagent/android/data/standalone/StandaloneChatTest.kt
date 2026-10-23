package com.luckyagent.android.data.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneChatTest {
    @Test
    fun chatUrlAppendsCompletionsOnce() {
        assertEquals(
            "https://api.example.test/v1/chat/completions",
            chatCompletionsUrl("https://api.example.test/v1").url,
        )
        assertEquals(
            "https://api.example.test/v1/chat/completions",
            chatCompletionsUrl("https://api.example.test/v1/chat/completions").url,
        )
    }

    @Test
    fun chatUrlKeepsQueryAndAddsMissingV1() {
        assertEquals(
            "https://api.example.test/v1/chat/completions?foo=1",
            chatCompletionsUrl("https://api.example.test/v1/chat/completions?foo=1").url,
        )
        assertEquals(
            "https://api.example.test/v1/chat/completions?key=1",
            chatCompletionsUrl("https://api.example.test/v1?key=1").url,
        )
        assertEquals(
            "https://api.example.test/v1/chat/completions",
            chatCompletionsUrl("https://api.example.test").url,
        )
        assertTrue(chatCompletionsUrl("https://api.example.test").addedV1)
        assertEquals(
            "https://api.example.test/v1/models",
            modelsUrl("https://api.example.test").url,
        )
        val rejected = runCatching { chatCompletionsUrl("api.example.test/v1") }
        assertTrue(rejected.exceptionOrNull()?.message.orEmpty().contains("http"))
    }

    @Test
    fun modelListReadsIdsWithoutInventingOne() {
        val body = """{"data":[{"id":"alpha"},{"id":" beta "},{"id":""}]}"""
        assertEquals(listOf("alpha", "beta"), extractModelIds(body))
    }

    @Test
    fun storedUserTurnIsNotAppendedAgain() {
        val session = StandaloneSession(
            id = "s",
            messages = listOf(StandaloneMessage("user", "同一句")),
        )
        val prompt = buildStandaloneMessages(session, "同一句")
        assertEquals(1, prompt.count { it["role"] == "user" && it["content"] == "同一句" })
    }

    @Test
    fun completionReadsMessageContent() {
        val body = """{"choices":[{"message":{"role":"assistant","content":" hello "}}]}"""
        assertEquals("hello", extractCompletion(body))
    }

    @Test
    fun compactKeepsRecentMessagesAndFoldsOlderOnes() {
        val session = StandaloneSession(
            id = "s",
            messages = listOf(
                StandaloneMessage("user", "记住口令是 blue-fox"),
                StandaloneMessage("assistant", "已记住"),
                StandaloneMessage("user", "最近的问题"),
                StandaloneMessage("assistant", "最近的回答"),
            ),
        )
        val compacted = compactStandaloneSession(session, keepRecentTokens = 8)
        assertTrue(compacted != null)
        assertTrue(compacted!!.summary.contains("blue-fox"))
        assertTrue(compacted.messages.none { it.content.contains("blue-fox") })
        assertTrue(compacted.messages.last().content.contains("最近的回答"))
    }

    @Test
    fun shortSessionIsNotCompacted() {
        val session = StandaloneSession(
            id = "s",
            messages = listOf(StandaloneMessage("user", "hi"), StandaloneMessage("assistant", "hello")),
        )
        assertNull(compactStandaloneSession(session))
    }

    @Test
    fun promptCarriesSummaryWithoutWritingTheKey() {
        val session = StandaloneSession(id = "s", summary = "口令是 blue-fox", messages = emptyList())
        val prompt = buildStandaloneMessages(session, "口令是什么")
        assertTrue(prompt.first()["content"]!!.contains("blue-fox"))
        assertEquals("user", prompt.last()["role"])
        assertTrue(prompt.none { it["content"]!!.contains("sk-") })
    }

    @Test
    fun laterTurnsStillCarryTheSearchResult() {
        val session = StandaloneSession(
            id = "s",
            messages = listOf(
                StandaloneMessage("user", "/search 口令"),
                StandaloneMessage("assistant", "搜索「口令」\nblue-fox", kind = "search"),
                StandaloneMessage("assistant", "查到了"),
            ),
        )
        val prompt = buildStandaloneMessages(session, "再重复一次")
        assertTrue(prompt.any { it["content"]!!.contains("blue-fox") })
        assertEquals("再重复一次", prompt.last()["content"])
    }

    @Test
    fun searchFormatsAbstractAndRelatedTopics() {
        val body = """
            {"AbstractText":"A fact","AbstractURL":"https://example.test/fact",
             "RelatedTopics":[{"Text":"More","FirstURL":"https://example.test/more"}]}
        """.trimIndent()
        val formatted = formatSearch("fact", body)
        assertTrue(formatted.contains("A fact"))
        assertTrue(formatted.contains("https://example.test/more"))
    }
}
