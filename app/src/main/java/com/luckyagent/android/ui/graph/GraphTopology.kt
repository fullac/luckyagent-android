package com.luckyagent.android.ui.graph

data class GraphLink(val source: String, val target: String, val weight: Int = 1)

/** A view of actual links, never relationships inferred from labels or layout proximity. */
class GraphTopology(nodeIds: List<String>, links: List<GraphLink>) {
    val nodeIds = nodeIds.filter { it.isNotBlank() }.distinct()
    private val ids = this.nodeIds.toSet()
    val links = links.filter { it.source in ids && it.target in ids && it.source != it.target }
        .distinctBy { it.source to it.target }
    val neighbors: Map<String, Set<String>> = this.nodeIds.associateWith { linkedSetOf<String>() }.also { adjacency ->
        this.links.forEach { link ->
            adjacency.getValue(link.source).add(link.target)
            adjacency.getValue(link.target).add(link.source)
        }
    }

    /**
     * Search/hide filters produce an induced subgraph. A local root stays visible while
     * searching its neighborhood. Incoming/outgoing controls traversal, not link creation.
     */
    fun view(
        allowedIds: Set<String> = ids,
        root: String? = null,
        depth: Int = 1,
        incoming: Boolean = true,
        outgoing: Boolean = true,
        includeIsolated: Boolean = true,
    ): GraphTopology {
        val allowed = ids.intersect(allowedIds).toMutableSet()
        val visible = if (root == null) allowed else {
            if (root !in ids) return GraphTopology(emptyList(), emptyList())
            allowed.add(root)
            val reached = linkedSetOf(root)
            var frontier = setOf(root)
            repeat(depth.coerceIn(0, 5)) {
                val next = links.flatMap { link ->
                    buildList {
                        if (outgoing && link.source in frontier && link.target in allowed) add(link.target)
                        if (incoming && link.target in frontier && link.source in allowed) add(link.source)
                    }
                }.toSet() - reached
                reached.addAll(next)
                frontier = next
            }
            reached
        }
        val visibleLinks = links.filter { it.source in visible && it.target in visible }
        val connected = visibleLinks.flatMap { listOf(it.source, it.target) }.toSet()
        return GraphTopology(
            nodeIds.filter { it in visible && (includeIsolated || it in connected || it == root) },
            visibleLinks,
        )
    }
}
