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
            chatCompletionsUrl("https://api.example.test/v1"),
        )
        assertEquals(
            "https://api.example.test/v1/chat/completions",
            chatCompletionsUrl("https://api.example.test/v1/chat/completions"),
        )
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
