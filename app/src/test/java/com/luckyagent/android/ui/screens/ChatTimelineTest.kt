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
import com.luckyagent.android.ui.replaceBubbleAt
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun streamingReplacementReusesEarlierTimelineItems() {
        val initial = listOf(
            ChatBubble(id = "question", role = "user", content = "Question"),
            ChatBubble(id = "answer", role = "assistant", content = "one", streaming = true),
        )
        val cache = ChatTimelineCache()
        val first = cache.update(initial)
        val next = initial.replaceBubbleAt(1, initial[1].copy(content = "one two"))
        val second = cache.update(next)
        val third = next.replaceBubbleAt(1, next[1].copy(content = "one two three"))
        val final = cache.update(third)

        assertSame(first[0], second[0])
        assertSame(second[0], final[0])
        assertEquals("one two three", (final.last() as ChatTimelineItem.Message).bubble.content)
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
            ChatBubble("u-live", "user", "what is in the file"),
            ChatBubble("reasoning-$turn-1", "reasoning", "reading the file"),
            ChatBubble("tool-$turn:1", "tool", "file_read", stepId = "$turn:1"),
            ChatBubble("a-live", "assistant", "partial", streaming = true),
        )
        assertEquals(
            listOf("u-live", "reasoning-$turn-1", "tool-$turn:1", "a-live"),
            liveTurnBubbles(bubbles, turn, "a-live").map { it.id },
        )
        assertEquals(emptyList<String>(), liveTurnBubbles(bubbles, "", null).map { it.id })
    }

    @Test
    fun historyRefreshReplacesTheLiveTurnByServerOrdinal() {
        val turn = "turn-7"
        val live = listOf(
            ChatBubble("u-live", "user", "what is in the file", serverOrdinal = 1),
            ChatBubble("reasoning-$turn-1", "reasoning", "reading the file", serverOrdinal = 2),
            ChatBubble("a-live", "assistant", "partial", streaming = true, serverOrdinal = 3),
        )
        val historyUserOnly = listOf(
            ChatBubble("ord:0-user", "user", "old", serverOrdinal = 0),
            ChatBubble("ord:1-user", "user", "what is in the file", serverOrdinal = 1),
        )
        assertEquals(listOf("reasoning-$turn-1", "a-live"), dedupeLiveTurn(historyUserOnly, live).map { it.id })

        val notStoredYet = listOf(ChatBubble("ord:0-user", "user", "old", serverOrdinal = 0))
        assertEquals(live.map { it.id }, dedupeLiveTurn(notStoredYet, live).map { it.id })

        val olderCopy = listOf(
            ChatBubble("ord:0-user", "user", "what is in the file", serverOrdinal = 0),
            ChatBubble("ord:1-assistant", "assistant", "an earlier answer", serverOrdinal = 1),
        )
        val secondSend = listOf(
            ChatBubble("u-again", "user", "what is in the file", serverOrdinal = 2),
            ChatBubble("a-again", "assistant", "second", streaming = true, serverOrdinal = 3),
        )
        assertEquals(secondSend.map { it.id }, dedupeLiveTurn(olderCopy, secondSend).map { it.id })

        val stored = olderCopy + listOf(
            ChatBubble("ord:2-user", "user", "what is in the file", serverOrdinal = 2),
            ChatBubble("ord:3-reasoning", "reasoning", "another reading", serverOrdinal = 3),
            ChatBubble("ord:4-assistant", "assistant", "the stored answer is longer", serverOrdinal = 4),
        )
        val partial = listOf(
            ChatBubble("u-again", "user", "what is in the file", serverOrdinal = 2),
            ChatBubble("reasoning-$turn-1", "reasoning", "reading", serverOrdinal = 3),
            ChatBubble("a-again", "assistant", "part", streaming = true, serverOrdinal = 4),
        )
        assertEquals(emptyList<String>(), dedupeLiveTurn(stored, partial).map { it.id })

        val windowMoved = listOf(
            ChatBubble("ord:8-user", "user", "what is in the file", serverOrdinal = 8),
            ChatBubble("ord:9-reasoning", "reasoning", "the stored reasoning", serverOrdinal = 9),
            ChatBubble("ord:9-tool", "tool", "file_read", serverOrdinal = 9),
        )
        val liveWithTool = listOf(
            ChatBubble("u-live", "user", "what is in the file", serverOrdinal = 8),
            ChatBubble("reasoning-$turn-1", "reasoning", "reading", serverOrdinal = 9),
            ChatBubble("tool-$turn:1", "tool", "file_read", stepId = "$turn:1", serverOrdinal = 9),
            ChatBubble("a-live", "assistant", "still streaming", streaming = true, serverOrdinal = 10),
        )
        assertEquals(listOf("a-live"), dedupeLiveTurn(windowMoved, liveWithTool).map { it.id })

        val placeholder = listOf(
            ChatBubble("u-live", "user", "what is in the file", serverOrdinal = 1),
            ChatBubble("reasoning-$turn-0", "reasoning", "", reasoningHasContent = false),
        )
        assertEquals(emptyList<String>(), dedupeLiveTurn(historyUserOnly, placeholder).map { it.id })
        val waiting = listOf(
            ChatBubble("u-live", "user", "what is in the file", serverOrdinal = 4),
            ChatBubble("reasoning-$turn-0", "reasoning", "", reasoningHasContent = false),
        )
        assertEquals(waiting.map { it.id }, dedupeLiveTurn(historyUserOnly, waiting).map { it.id })
    }

    @Test
    fun anchorSharesOneOrdinalPerServerMessage() {
        val turn = "turn-9"
        val live = listOf(
            ChatBubble("u-live", "user", "what is in the file"),
            ChatBubble("reasoning-$turn-0", "reasoning", "reading", reasoningHasContent = true),
            ChatBubble("tool-$turn:1", "tool", "file_read", toolName = "file_read", toolArgs = "{}", stepId = "$turn:1"),
            ChatBubble("a-round", "assistant", "looking", isIntermediate = true),
            ChatBubble("tool-$turn:1-result", "tool", "file contents", toolName = "file_read", toolOutput = "file contents", toolDone = true, stepId = "$turn:1"),
            ChatBubble("a-live", "assistant", "still streaming", streaming = true),
        )
        assertEquals(listOf(40, 41, 41, 41, 42, 43), anchorLiveTurnOrdinals(live, 40))

        val start = 40
        val history = listOf(
            ProviderMessage(role = "user", content = "what is in the file").toHistoryBubbles(start),
            ProviderMessage(
                role = "assistant",
                content = "looking",
                reasoningContent = "reading",
                toolCalls = listOf(HistoryToolCall(id = "$turn:1", name = "file_read", arguments = "{}")),
            ).toHistoryBubbles(start + 1),
            ProviderMessage(role = "tool", name = "file_read", content = "file contents", toolCallId = "$turn:1")
                .toHistoryBubbles(start + 2),
            ProviderMessage(role = "assistant", content = "the stored answer").toHistoryBubbles(start + 3),
        ).flatten().mapIndexed { index, bubble ->
            val ordinal = when (index) {
                0 -> start
                in 1..3 -> start + 1
                4 -> start + 2
                else -> start + 3
            }
            bubble.copy(serverOrdinal = ordinal)
        }
        val stamped = live.zip(anchorLiveTurnOrdinals(live, start)) { bubble, ordinal ->
            bubble.copy(serverOrdinal = ordinal)
        }
        assertEquals(emptyList<String>(), dedupeLiveTurn(history, stamped).map { it.id })
    }

    @Test
    fun aHistoryOnlyFoldDoesNotOwnTheNewTurn() {
        val history = listOf(
            ChatBubble("u-old", "user", "earlier"),
            ChatBubble("reasoning-old", "reasoning", "old thought", reasoningHasContent = true),
            ChatBubble("a-old", "assistant", "earlier answer"),
        )
        val items = buildTimeline(history)
        val fold = items.filterIsInstance<ChatTimelineItem.Process>().single()
        assertFalse(processOwnsTurn(fold.steps, items))
        val sameText = history + listOf(
            ChatBubble("u-new", "user", "earlier"),
            ChatBubble("reasoning-new", "reasoning", "old thought", reasoningHasContent = true),
        )
        val both = buildTimeline(sameText)
        val folds = both.filterIsInstance<ChatTimelineItem.Process>()
        assertEquals(2, folds.size)
        assertFalse(processOwnsTurn(folds[0].steps, both))
        assertTrue(processOwnsTurn(folds[1].steps, both))
    }

    @Test
    fun newTurnStatusStartsInItsOwnFold() {
        val previous = listOf(
            ChatBubble("u-old", "user", "earlier"),
            ChatBubble("reasoning-turn-1-1", "reasoning", "old thought", reasoningHasContent = true),
            ChatBubble("a-old", "assistant", "earlier answer"),
        )
        val opened = previous + listOf(
            ChatBubble("u-new", "user", "next"),
            ChatBubble("reasoning-turn-2-0", "reasoning", "", reasoningHasContent = false),
        )
        val items = buildTimeline(opened)
        val folds = items.filterIsInstance<ChatTimelineItem.Process>()
        assertEquals(2, folds.size)
        assertFalse(processOwnsTurn(folds[0].steps, items))
        assertTrue(processOwnsTurn(folds[1].steps, items))
        assertEquals(
            "reading the file",
            outerProcessLabel("reading the file", null, isResponding = true, isLatestProcess = true, ownsCurrentTurn = true),
        )
        assertEquals(
            null,
            outerProcessLabel("reading the file", null, isResponding = true, isLatestProcess = true, ownsCurrentTurn = false),
        )
        assertTrue(folds[1].steps.all(::isTurnStatusPlaceholder))
    }

    @Test
    fun openingWithoutAnAnchorLandsOnTheLatestRow() {
        assertEquals(12 to 0, openingScrollIndex(null, 12))
        assertEquals(12 to 0, openingScrollIndex(0 to 40, 12))
        assertEquals(4 to 18, openingScrollIndex(4 to 18, 12))
        assertEquals(12 to 9, openingScrollIndex(40 to 9, 12))
    }

    @Test
    fun followLatestOnlyWhileTheViewportStayedAtTheBottom() {
        assertTrue(shouldFollowLatest(atBottom = true, stuckToLastAutoScroll = false))
        assertTrue(shouldFollowLatest(atBottom = false, stuckToLastAutoScroll = true))
        assertFalse(shouldFollowLatest(atBottom = false, stuckToLastAutoScroll = false))
    }

    @Test
    fun outerFoldShowsOnlyTheLatestSummaryUntilTheAnswerArrives() {
        assertEquals("reading the file", outerProcessLabel("reading the file", null, isResponding = true, isLatestProcess = true))
        assertEquals("calling file_read", outerProcessLabel("calling file_read", null, isResponding = true, isLatestProcess = true))
        assertEquals("calling file_read", outerProcessLabel("calling file_read\nolder detail", null, isResponding = true, isLatestProcess = true))
        assertEquals(null, outerProcessLabel("calling file_read", null, isResponding = false, isLatestProcess = true))
        assertEquals(null, outerProcessLabel("   ", null, isResponding = true, isLatestProcess = true))
        assertEquals(null, outerProcessLabel("calling file_read", null, isResponding = true, isLatestProcess = false))
        assertEquals(null, outerProcessLabel("calling file_read", null, isResponding = true, isLatestProcess = true, ownsCurrentTurn = false))
    }

    @Test
    fun outerFoldReplacesTheSummaryWhenTheTurnEndsWithoutAnAnswer() {
        assertEquals("已取消", outerProcessLabel("reading the file", "已取消", isResponding = false, isLatestProcess = true))
        assertEquals("请求失败", outerProcessLabel(null, "请求失败", isResponding = false, isLatestProcess = true))
        assertEquals(null, outerProcessLabel("reading the file", "已取消", isResponding = false, isLatestProcess = false))
        assertEquals("reading the file", outerProcessLabel("reading the file", "已取消", isResponding = true, isLatestProcess = true))
    }
}
