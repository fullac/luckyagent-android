package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.api.HistoryToolCall
import com.luckyagent.android.data.api.ProviderMessage
import com.luckyagent.android.data.api.TaskListResponse
import com.luckyagent.android.data.api.TaskSummary
import com.luckyagent.android.data.api.executionSession
import com.luckyagent.android.data.api.toTaskSummary
import com.luckyagent.android.ui.ChatBubble
import com.luckyagent.android.ui.toHistoryBubbles
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatTaskProjectionTest {
    private val owner = "owner-session"

    private fun task(id: String, status: String = "completed", parent: String? = null) = TaskSummary(
        id = id, parentId = parent, sessionId = "delegate-$id", executionSessionId = "delegate-$id",
        parentSessionId = owner, source = "tool", mode = "single", status = status,
        description = "Work $id", metadata = mapOf("owner_session_id" to owner),
    )

    private fun output(id: String, status: String = "running") = """
        {"task_id":"$id","execution_session_id":"delegate-$id","parent_session_id":"$owner",
         "role":"subagent","source":"tool","mode":"single","status":"$status","description":"Work $id"}
    """.trimIndent()

    private fun history(vararg ids: String): List<ChatBubble> {
        val messages = listOf(ProviderMessage(
            role = "assistant", content = "Inspect the project",
            toolCalls = ids.map { HistoryToolCall(id = "call-$it", name = "subagent", arguments = """{"action":"delegate","description":"Work $it"}""") },
        )) + ids.map {
            ProviderMessage(role = "tool", name = "subagent", toolCallId = "call-$it", content = output(it))
        }
        return messages.flatMapIndexed { index, message -> message.toHistoryBubbles(index) }
    }

    @Test
    fun fourIndependentDelegatesFromRealWireShapeBelongToTheirOwnerAndFormOneTurnGraph() {
        val wire = (1..4).joinToString(",") { index -> """
            {"id":"task-$index","parent_session_id":"$owner","execution_session_id":"delegate-task-$index",
             "role":"subagent","source":"tool","mode":"single","status":"completed",
             "metadata":{"owner_session_id":"$owner","execution_session_id":"delegate-task-$index"}}
        """.trimIndent() }
        val records = Json.decodeFromString(TaskListResponse.serializer(), """{"tasks":[$wire]}""").tasks
        val summaries = records.map { it.toTaskSummary() }
        val projected = collabTasksForTurn(history("task-1", "task-2", "task-3", "task-4"), summaries, owner)

        assertEquals((1..4).map { "task-$it" }, projected.map { it.id })
        assertEquals("子代理 · 4/4 · 完成", collabFoldLabel(projected, false))
        projected.forEach { summary ->
            assertTrue(summary.belongsToChatSession(owner))
            assertTrue(summary.belongsToChatSession("delegate-${summary.id}"))
            assertEquals("delegate-${summary.id}", summary.executionSession())
        }
    }

    @Test
    fun independentDelegatesHaveProgressWithoutGroupedRuntimeMode() {
        val delegates = (1..4).map { task("task-$it") }
        assertEquals("子代理 · 4/4 · 完成", collabFoldLabel(delegates, false))
        assertTrue(delegates.all { it.mode == "single" && it.parentId == null })
    }

    @Test
    fun eachFoldKeepsOnlyItsOwnExplicitTaskIds() {
        val tasks = listOf(task("first"), task("second"), task("old"))
        assertEquals(listOf("first"), collabTasksForTurn(history("first"), tasks, owner).map { it.id })
        assertEquals(listOf("second"), collabTasksForTurn(history("second"), tasks, owner).map { it.id })
        assertTrue(collabTasksForTurn(listOf(ChatBubble("reason", "reasoning", "Thinking")), tasks, owner).isEmpty())
        assertTrue(collabTasksForTurn(history("first"), tasks, "another-owner").isEmpty())
    }

    @Test
    fun liveToolOutputWorksBeforePollingAndThenUsesAuthoritativeTaskStatus() {
        val live = ChatBubble("tool-call", "tool", "subagent · done", toolName = "subagent",
            toolArgs = """{"action":"delegate"}""", toolOutput = output("live"), toolDone = true)
        val preview = collabTasksForTurn(listOf(live), emptyList(), owner).single()
        assertEquals("live", preview.id)
        assertEquals("delegate-live", preview.executionSession())
        assertEquals("running", preview.status)
        assertEquals("子代理 · 0/1", collabFoldLabel(listOf(preview), true))
        val polled = collabTasksForTurn(listOf(live), listOf(task("live")), owner)
        assertEquals("子代理 · 1/1 · 完成", collabFoldLabel(polled, false))
    }

    @Test
    fun waitingForKnownIdsSurvivesTruncatedToolOutputAndDoesNotDuplicateTasks() {
        val wait = ChatBubble("wait", "tool", "truncated", toolName = "subagent",
            toolArgs = """{"action":"wait","task_ids":["a","b","a"]}""", toolOutput = "{truncated")
        val steps = history("a", "b") + wait
        val projected = collabTasksForTurn(steps, listOf(task("a"), task("b", "running")), owner)
        assertEquals(listOf("a", "b"), projected.map { it.id })
        assertEquals("子代理 · 1/2", collabFoldLabel(projected, true))
        val previews = collabTasksForTurn(steps, emptyList(), owner)
        assertEquals(listOf("Work a", "Work b"), previews.map { it.description })
    }

    @Test
    fun globalTaskListCannotPolluteThisTurnEvenWhenItsHistoryIsOnAnotherPage() {
        val call = ProviderMessage(role = "assistant", toolCalls = listOf(HistoryToolCall(
            id = "list-call", name = "subagent", arguments = """{"action":"list"}""")))
        val result = ProviderMessage(role = "tool", name = "subagent", toolCallId = "list-call",
            content = """{"tasks":[{"task_id":"old"}]}""")
        val steps = listOf(call, result).flatMapIndexed { i, message -> message.toHistoryBubbles(i) }
        assertEquals("list-call", steps.last().stepId)
        assertTrue(collabTasksForTurn(steps, listOf(task("old")), owner).isEmpty())
        assertTrue(collabTasksForTurn(listOf(steps.last()), listOf(task("old")), owner).isEmpty())
    }

    @Test
    fun failedAndCancelledDelegatesAreNotLabelledAsAllCompleted() {
        assertEquals("子代理 · 1/3 · 1 失败", collabFoldLabel(listOf(task("ok"), task("bad", "failed"), task("stop", "cancelled")), false))
        assertEquals("子代理 · 0/1 · 已结束", collabFoldLabel(listOf(task("stop", "cancelled")), false))
    }

    @Test
    fun clientMessageIdBecomesTheStableHistoryBubbleId() {
        val bubbles = ProviderMessage(
            role = "user",
            content = "queued",
            clientMessageId = "outbound-123",
        ).toHistoryBubbles(7)

        assertEquals("outbound-123", bubbles.single().id)
        assertEquals("outbound-123", bubbles.single().outboundId)
    }

    @Test
    fun existingGroupRepresentsItsChildrenAndKeepsItsExecutionMode() {
        for (mode in listOf("parallel", "pipeline", "debate")) {
            val root = task("root").copy(mode = mode, childCount = 2, completedChildren = 2)
            val tasks = listOf(root, task("child", parent = "root"))
            val projected = collabTasksForTurn(history("root", "child"), tasks, owner)
            assertEquals(listOf("root"), projected.map { it.id })
            assertEquals(mode, projected.single().mode)
            assertEquals(collabFoldLabel(listOf(root), false), collabFoldLabel(projected, false))
        }
    }

    @Test
    fun metadataOwnerAndLegacyExplicitReferencesRemainUsable() {
        val metadata = task("metadata").copy(parentSessionId = null)
        assertTrue(metadata.belongsToChatSession(owner))
        val legacy = task("legacy").copy(parentSessionId = null, metadata = emptyMap())
        assertEquals(listOf("legacy"), collabTasksForTurn(history("legacy"), listOf(legacy), owner).map { it.id })
        assertTrue(collabTasksForTurn(history("legacy"), listOf(legacy), "").isEmpty())
        val foreignPreview = ChatBubble("foreign", "tool", "", toolName = "subagent",
            toolOutput = """{"task_id":"foreign","metadata":{"owner_session_id":"another-owner"}}""")
        assertTrue(collabTasksForTurn(listOf(foreignPreview), emptyList(), owner).isEmpty())
    }

    @Test
    fun unrelatedToolsAndTaskIdsInsideDelegateContextAreIgnored() {
        val steps = listOf(
            ChatBubble("file", "tool", output("unrelated"), toolName = "file_read"),
            ChatBubble("delegate", "tool", "subagent · running", toolName = "subagent",
                toolArgs = """{"action":"delegate","context":{"task_id":"unrelated"}}"""),
            ChatBubble("invalid", "tool", """{"tasks":[null,1],"task_id":{}}""", toolName = "subagent"),
        )
        assertTrue(collabTasksForTurn(steps, listOf(task("unrelated")), owner).isEmpty())
    }
}
