package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.api.TaskSummary
import com.luckyagent.android.ui.ChatBubble
import com.luckyagent.android.ui.ChatMedia
import com.luckyagent.android.ui.toHistoryBubbles
import com.luckyagent.android.data.api.HistoryToolCall
import com.luckyagent.android.data.api.MediaAttachment
import com.luckyagent.android.data.api.ProviderMessage
import com.luckyagent.android.data.api.SessionHistory
import com.luckyagent.android.data.api.TokenUsage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {
    private fun timeline(messages: List<ProviderMessage>, startIndex: Int = 0) =
        buildTimeline(messages.flatMapIndexed { index, message -> message.toHistoryBubbles(startIndex + index) })

    private fun round(index: Int) = listOf(
        ProviderMessage(
            role = "assistant", content = "Progress $index", reasoningContent = "Reasoning $index",
            toolCalls = listOf(HistoryToolCall(id = "call-$index", name = "file_read", arguments = "{\"path\":\"$index\"}")),
        ),
        ProviderMessage(role = "tool", name = "file_read", content = "Result $index", toolCallId = "call-$index"),
    )

    @Test
    fun longQueryHasOneFoldAndOnlyTheFinalAnswerOnTheMainTimeline() {
        val messages = listOf(ProviderMessage(role = "user", content = "Long task")) +
            (1..20).flatMap(::round) + ProviderMessage(role = "assistant", content = "Final answer")

        val items = timeline(messages)

        assertEquals(3, items.size)
        assertEquals("Long task", (items[0] as ChatTimelineItem.Message).bubble.content)
        val folded = (items[1] as ChatTimelineItem.Process).steps
        assertEquals((1..20).map { "Progress $it" }, folded.filter { it.isIntermediate }.map { it.content })
        assertEquals(20, folded.count { it.role == "reasoning" })
        assertEquals(40, folded.count { it.role == "tool" })
        assertEquals("Final answer", (items[2] as ChatTimelineItem.Message).bubble.content)
        assertFalse((items[2] as ChatTimelineItem.Message).bubble.isIntermediate)
    }

    @Test
    fun eachUserQueryKeepsItsOwnFoldAndAnswer() {
        val items = timeline(
            listOf(ProviderMessage(role = "user", content = "First")) + round(1) +
                ProviderMessage(role = "assistant", content = "First answer") +
                ProviderMessage(role = "user", content = "Second") + round(2) +
                ProviderMessage(role = "assistant", content = "Second answer"),
        )

        assertEquals(6, items.size)
        assertEquals(listOf("Progress 1"), (items[1] as ChatTimelineItem.Process).steps.filter { it.isIntermediate }.map { it.content })
        assertEquals(listOf("Progress 2"), (items[4] as ChatTimelineItem.Process).steps.filter { it.isIntermediate }.map { it.content })
        assertEquals("Second answer", (items[5] as ChatTimelineItem.Message).bubble.content)
    }

    @Test
    fun unfinishedQueryKeepsCommentaryInTheFoldWithoutInventingAnAnswer() {
        val items = timeline(listOf(ProviderMessage(role = "user", content = "Continue working")) + round(1) + round(2))

        assertEquals(2, items.size)
        assertEquals(2, (items.last() as ChatTimelineItem.Process).steps.count { it.isIntermediate })
    }

    @Test
    fun toolCommentaryFoldsEvenWhenTheModelDoesNotReturnReasoning() {
        val input = round(1).map { it.copy(reasoningContent = null) } +
            ProviderMessage(role = "assistant", content = "Done")
        val items = timeline(input)

        assertEquals(2, items.size)
        assertTrue((items.first() as ChatTimelineItem.Process).steps.any { it.content == "Progress 1" })
        assertEquals("Done", (items.last() as ChatTimelineItem.Message).bubble.content)
    }

    @Test
    fun finalAnswerWithReasoningMediaAndUsageRemainsVisible() {
        val usage = TokenUsage(totalTokens = 42)
        val media = ChatMedia(MediaAttachment(type = "image", fileUrl = "https://example.test/result.png"))
        val message = ProviderMessage(role = "assistant", content = "Result", reasoningContent = "Final reasoning", usage = usage)
        val items = buildTimeline(message.toHistoryBubbles(20, messageAttachments = listOf(media)))

        assertEquals(2, items.size)
        val answer = (items.last() as ChatTimelineItem.Message).bubble
        assertEquals("Result", answer.content)
        assertEquals(listOf(media), answer.attachments)
        assertEquals(usage, answer.usage)
        assertFalse(answer.isIntermediate)
    }

    @Test
    fun loadingAHistoryPageInTheMiddleOfAToolQueryStillProducesOneFold() {
        val items = timeline(round(3) + round(4) + ProviderMessage(role = "assistant", content = "Done"), startIndex = 200)

        assertEquals(2, items.size)
        assertEquals(2, (items.first() as ChatTimelineItem.Process).steps.count { it.isIntermediate })
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun realHistoryWireShapePreservesTheIntermediateFlagThroughMapping() {
        val history = Json.decodeFromString(SessionHistory.serializer(), """
            {"messages":[
              {"role":"user","content":"Implement channel"},
              {"role":"assistant","content":"Read the entrypoint first","reasoning_content":"Check the boundary",
               "tool_calls":[{"id":"r1","name":"file_read","arguments":"{}"}]},
              {"role":"tool","tool_call_id":"r1","name":"file_read","content":"source"},
              {"role":"assistant","content":"Implemented"}
            ]}
        """.trimIndent())
        val items = timeline(history.messages)

        assertEquals(3, items.size)
        assertEquals("Read the entrypoint first", (items[1] as ChatTimelineItem.Process).steps.single { it.isIntermediate }.content)
        assertEquals("Implemented", (items[2] as ChatTimelineItem.Message).bubble.content)
    }

    @Test
    fun aConfirmedLiveToolRoundJoinsTheExistingFoldAndTheNextAnswerStaysVisible() {
        val reasoning = ChatBubble("r", "reasoning", "Think")
        val streaming = ChatBubble("a", "assistant", "Checking files", streaming = true)
        val initial = buildTimeline(listOf(reasoning, streaming))
        val tool = ChatBubble("t", "tool", "Result", toolDone = true)
        val folded = streaming.copy(streaming = false, isIntermediate = true)
        val updated = buildTimeline(listOf(reasoning, folded, tool, ChatBubble("final", "assistant", "Done")))

        assertEquals(initial.first().key, updated.first().key)
        assertEquals(listOf(reasoning, folded, tool), (updated.first() as ChatTimelineItem.Process).steps)
        assertEquals("Done", (updated.last() as ChatTimelineItem.Message).bubble.content)
    }

    @Test
    fun duplicateAndBlankIdsGetUniqueKeysWithoutDroppingMessages() {
        val bubbles = listOf("same", "same", "same-1", "", "", "timeline", "chat-tail", "pending-approvals")
            .map { ChatBubble(id = it, role = "assistant", content = it) }

        val timeline = buildTimeline(bubbles)

        assertEquals(bubbles, timeline.map { (it as ChatTimelineItem.Message).bubble })
        assertEquals(timeline.size, timeline.map { it.key }.toSet().size)
        assertTrue(timeline.all { it.key.isNotBlank() })
        assertTrue(timeline.none { it.key == "chat-tail" || it.key == "pending-approvals" })
        assertEquals(timeline.map { it.key }, buildTimeline(bubbles).map { it.key })
    }

    @Test
    fun processAndMessageIdsCannotCollide() {
        val steps = listOf(
            ChatBubble(id = "replayed", role = "reasoning", content = "reason"),
            ChatBubble(id = "replayed", role = "tool", content = "result"),
        )
        val answer = ChatBubble(id = "replayed", role = "assistant", content = "answer")
        val timeline = buildTimeline(steps + answer + steps)

        assertEquals(3, timeline.size)
        assertEquals(steps, (timeline[0] as ChatTimelineItem.Process).steps)
        assertEquals(answer, (timeline[1] as ChatTimelineItem.Message).bubble)
        assertEquals(steps, (timeline[2] as ChatTimelineItem.Process).steps)
        assertEquals(3, timeline.map { it.key }.toSet().size)
    }
    @Test
    fun collabFoldLabelUsesModeAndFinishedState() {
        val running = TaskSummary(id = "p", source = "tool", mode = "parallel", status = "running", description = "split", childCount = 3, completedChildren = 2)
        assertEquals("并行 · 2/3", collabFoldLabel(listOf(running), responding = true))
        val pipeline = TaskSummary(id = "s", source = "tool", mode = "pipeline", status = "running", description = "chain", childCount = 4, completedChildren = 1)
        assertEquals("流水线 · 第 1 步", collabFoldLabel(listOf(pipeline), responding = true))
        val debate = TaskSummary(id = "d", source = "tool", mode = "debate", status = "running", description = "argue", childCount = 3, completedChildren = 1, progress = 0.66)
        assertEquals("辩论 · 第 2 轮", collabFoldLabel(listOf(debate), responding = true))
        val board = TaskSummary(id = "b", source = "autonomy", mode = "autonomy_queue", status = "running", description = "board", childCount = 3)
        assertEquals("黑板 · 3 人", collabFoldLabel(listOf(board), responding = true))
        val done = running.copy(status = "completed", completedChildren = 3)
        assertEquals("并行 · 3/3 · 完成", collabFoldLabel(listOf(done), responding = false))
        assertEquals(null, collabFoldLabel(emptyList(), responding = true))
    }

    @Test
    fun liveTurnKeepsOnlyTheCurrentInMemoryBubbles() {
        val turn = "turn-7"
        val bubbles = listOf(
            ChatBubble("hist-1", "user", "old"),
            ChatBubble("reasoning-$turn-1", "reasoning", "reading the file"),
            ChatBubble("tool-$turn:1", "tool", "file_read", stepId = "$turn:1"),
            ChatBubble("a-live", "assistant", "partial", streaming = true),
            ChatBubble("u-older", "user", "previous local"),
        )
        assertEquals(
            listOf("reasoning-$turn-1", "tool-$turn:1", "a-live"),
            liveTurnBubbles(bubbles, turn, "a-live").map { it.id },
        )
        assertEquals(emptyList<String>(), liveTurnBubbles(bubbles, "", null).map { it.id })
    }

    @Test
    fun outerFoldShowsOnlyTheLatestSummaryUntilTheAnswerArrives() {
        assertEquals("reading the file", outerProcessLabel("reading the file", null, isResponding = true, isLatestProcess = true))
        assertEquals("calling file_read", outerProcessLabel("calling file_read", null, isResponding = true, isLatestProcess = true))
        assertEquals("calling file_read", outerProcessLabel("calling file_read\nolder detail", null, isResponding = true, isLatestProcess = true))
        assertEquals(null, outerProcessLabel("calling file_read", null, isResponding = false, isLatestProcess = true))
        assertEquals(null, outerProcessLabel("   ", null, isResponding = true, isLatestProcess = true))
        assertEquals(null, outerProcessLabel("calling file_read", null, isResponding = true, isLatestProcess = false))
    }

    @Test
    fun outerFoldReplacesTheSummaryWhenTheTurnEndsWithoutAnAnswer() {
        assertEquals("已取消", outerProcessLabel("reading the file", "已取消", isResponding = false, isLatestProcess = true))
        assertEquals("请求失败", outerProcessLabel(null, "请求失败", isResponding = false, isLatestProcess = true))
        assertEquals(null, outerProcessLabel("reading the file", "已取消", isResponding = false, isLatestProcess = false))
        assertEquals("reading the file", outerProcessLabel("reading the file", "已取消", isResponding = true, isLatestProcess = true))
    }
}
