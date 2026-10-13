package com.luckyagent.android.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphTopologyTest {
    private val graph = GraphTopology(
        listOf("root", "incoming", "outgoing", "second", "orphan", "unresolved"),
        listOf(GraphLink("incoming", "root"), GraphLink("root", "outgoing"),
            GraphLink("outgoing", "second"), GraphLink("root", "unresolved")),
    )

    @Test fun removesDuplicateNodesSelfLinksAndMissingEndpoints() {
        val graph = GraphTopology(listOf("a", "a", "b", ""), listOf(
            GraphLink("a", "a"), GraphLink("a", "b", 2), GraphLink("a", "b", 3), GraphLink("b", "missing")))
        assertEquals(listOf("a", "b"), graph.nodeIds)
        assertEquals(listOf(GraphLink("a", "b", 2)), graph.links)
        assertEquals(setOf("a"), graph.neighbors.getValue("b"))
    }

    @Test fun globalFilterKeepsOnlyRealEdgesBetweenRemainingNodes() {
        val view = graph.view(setOf("root", "second", "unresolved"))
        assertEquals(listOf("root", "second", "unresolved"), view.nodeIds)
        assertEquals(listOf(GraphLink("root", "unresolved")), view.links)
    }

    @Test fun localGraphTraversesBothDirectionsToRequestedDepth() {
        assertEquals(setOf("root", "incoming", "outgoing", "unresolved"), graph.view(root = "root").nodeIds.toSet())
        assertEquals(setOf("root", "incoming", "outgoing", "second", "unresolved"), graph.view(root = "root", depth = 2).nodeIds.toSet())
    }

    @Test fun directionFiltersControlNeighborhoodTraversal() {
        assertEquals(setOf("root", "outgoing", "unresolved"), graph.view(root = "root", incoming = false).nodeIds.toSet())
        assertEquals(setOf("root", "incoming"), graph.view(root = "root", outgoing = false).nodeIds.toSet())
        assertEquals(listOf("root"), graph.view(root = "root", incoming = false, outgoing = false).nodeIds)
    }

    @Test fun localSearchRetainsRootButDoesNotTraverseHiddenNodes() {
        val view = graph.view(setOf("second"), root = "root", depth = 3)
        assertEquals(listOf("root"), view.nodeIds)
        assertTrue(view.links.isEmpty())
    }

    @Test fun unresolvedFilterDropsIncidentLinksWithoutInventingAReplacement() {
        val view = graph.view(graph.nodeIds.toSet() - "unresolved")
        assertTrue(view.links.none { it.target == "unresolved" })
        assertEquals(3, view.links.size)
    }

    @Test fun isolatesAreRemovedAfterFilteringButLocalRootRemains() {
        assertTrue("orphan" !in graph.view(includeIsolated = false).nodeIds)
        assertEquals(listOf("root"), graph.view(emptySet(), root = "root", includeIsolated = false).nodeIds)
    }

    @Test fun absentLocalRootDoesNotFallBackToTheEntireVault() {
        assertTrue(graph.view(root = "missing").nodeIds.isEmpty())
    }
}
