package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.api.TaskSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatNavigationTest {
    @Test fun sessionCardKeepsOnlyTasksThatBelongToTheOpenChat() {
        val owner = task("owner", parentSessionId = "chat")
        val child = task("child", sessionId = "chat")
        val other = task("other", parentSessionId = "elsewhere")

        val visible = tasksForSession(listOf(other, owner, child), "chat")

        assertEquals(listOf("owner", "child"), visible.map { it.id })
    }

    @Test fun sessionCardPrefersAttentionThenTheRunningTask() {
        val done = task("done", status = "completed", lastActivityAt = "9")
        val running = task("running", status = "running", lastActivityAt = "2")
        val blocked = task("blocked", status = "blocked", lastActivityAt = "1")

        assertEquals("blocked", sessionNavigationTask(listOf(done, running, blocked))?.id)
        assertEquals("running", sessionNavigationTask(listOf(done, running))?.id)
        assertEquals("done", sessionNavigationTask(listOf(done))?.id)
        assertNull(sessionNavigationTask(emptyList()))
    }

    private fun task(
        id: String,
        status: String = "running",
        parentSessionId: String? = null,
        sessionId: String? = null,
        lastActivityAt: String? = null,
    ) = TaskSummary(
        id = id,
        parentSessionId = parentSessionId,
        sessionId = sessionId,
        source = "tool",
        mode = "single",
        status = status,
        description = id,
        lastActivityAt = lastActivityAt,
    )
}
