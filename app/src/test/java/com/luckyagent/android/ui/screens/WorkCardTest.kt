package com.luckyagent.android.ui.screens

import com.luckyagent.android.data.api.AutonomyTaskSummary
import com.luckyagent.android.data.api.TaskSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkCardTest {
    @Test fun chatCardKeepsOnlyWorkThatBelongsToTheOpenSession() {
        val mine = task("mine", parentSessionId = "chat", status = "running")
        val other = task("other", parentSessionId = "elsewhere", status = "running")
        val board = AutonomyTaskSummary(id = "board", title = "后台", parentSessionId = "chat", state = "in_progress")

        val cards = workCardsForSession(listOf(other, mine), listOf(board), "chat")

        assertEquals(listOf("mine", "board"), cards.map { it.id })
        assertTrue(workCardsForSession(listOf(mine), emptyList(), "").isEmpty())
    }

    @Test fun featuredCardPrefersTheRunningItemThenTheFinishedOne() {
        val done = task("done", status = "completed")
        val running = task("running", status = "running")

        assertEquals("running", featuredWorkCard(listOf(done.toCard(), running.toCard()))?.id)
        assertEquals("done", featuredWorkCard(listOf(done.toCard()))?.id)
        assertNull(featuredWorkCard(emptyList()))
        assertTrue(workCardCanCancel(running.toCard()))
        assertFalse(workCardCanCancel(done.toCard()))
    }

    @Test fun focusedCardStaysVisibleEvenWhenItBelongsToAnotherSession() {
        val running = task("running", parentSessionId = "chat", status = "running")
        val focused = task("elsewhere", parentSessionId = "other", status = "completed")

        val card = visibleWorkCard("elsewhere", false, listOf(running, focused), emptyList(), "chat")

        assertEquals("elsewhere", card?.id)
        assertEquals("已完成", card?.status?.let(::workCardStatusLabel))
    }

    @Test fun missingFocusStillShowsAReturnCard() {
        val card = visibleWorkCard("missing", true, emptyList(), emptyList(), "chat")

        assertEquals("missing", card?.id)
        assertEquals(WorkCardKind.Background, card?.kind)
        assertTrue(card?.loading == true)
        assertFalse(workCardCanCancel(card!!))
    }

    @Test fun closingAFinishedCardKeepsTheNextRunningOne() {
        val done = task("done", status = "completed")
        val running = task("running", status = "running")
        val cards = listOf(done.toCard(), running.toCard())

        assertEquals("running", featuredWorkCard(cards, setOf("done"))?.id)
        assertNull(featuredWorkCard(cards, setOf("done", "running")))
        assertEquals(setOf("kept", "done"), dismissedWorkCardIds(setOf("kept"), "done"))
    }

    @Test fun aDismissedCardStaysHiddenUntilANotificationFocusesIt() {
        val done = task("done", status = "completed")

        assertNull(visibleWorkCard(null, false, listOf(done), emptyList(), "chat", setOf("done")))
        assertEquals(
            "done",
            visibleWorkCard("done", false, listOf(done), emptyList(), "chat", setOf("done"))?.id,
        )
    }

    @Test fun notificationLandsOnTheCardWhenTheExecutionSessionIsMissing() {
        assertEquals("session:chat", notificationLanding("chat", "task", "tasks"))
        assertEquals("task-card:task", notificationLanding("", "task", "tasks"))
        assertEquals("background-card:board", notificationLanding(null, "board", "background"))
        assertEquals("none", notificationLanding("", "", "tasks"))
    }

    private fun task(id: String, parentSessionId: String? = "chat", status: String = "running") = TaskSummary(
        id = id,
        parentSessionId = parentSessionId,
        source = "tool",
        mode = "single",
        status = status,
        description = id,
    )

    private fun TaskSummary.toCard() = workCardsForSession(listOf(this), emptyList(), parentSessionId.orEmpty()).single()
}
