package com.luckyagent.android.ui.graph

import kotlin.math.max
import kotlin.math.min

data class GraphPoint(val x: Float, val y: Float) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

/** Center is in world coordinates; scale is screen pixels per world unit. */
data class GraphCamera(val center: GraphPoint = GraphPoint(0f, 0f), val scale: Float = 1f) {
    fun toScreen(point: GraphPoint, width: Float, height: Float) = GraphPoint(
        (point.x - center.x) * scale + width / 2f,
        (point.y - center.y) * scale + height / 2f,
    )

    fun toWorld(point: GraphPoint, width: Float, height: Float) = GraphPoint(
        (point.x - width / 2f) / scale + center.x,
        (point.y - height / 2f) / scale + center.y,
    )

    /** Keep the world point under the previous pinch centroid under the moved centroid. */
    fun transform(anchor: GraphPoint, pan: GraphPoint, zoom: Float, width: Float, height: Float): GraphCamera {
        val world = toWorld(anchor, width, height)
        val nextScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        return GraphCamera(
            GraphPoint(
                world.x - (anchor.x + pan.x - width / 2f) / nextScale,
                world.y - (anchor.y + pan.y - height / 2f) / nextScale,
            ),
            nextScale,
        )
    }

    companion object {
        const val MIN_SCALE = .03f
        const val MAX_SCALE = 8f

        fun fit(points: Collection<GraphPoint>, width: Float, height: Float, padding: Float): GraphCamera {
            val finite = points.filter { it.isFinite }
            if (finite.isEmpty() || width <= 0f || height <= 0f) return GraphCamera()
            // Reserve world-space room for node radii as well as screen-space label padding.
            val left = finite.minOf { it.x } - 30f
            val right = finite.maxOf { it.x } + 30f
            val top = finite.minOf { it.y } - 30f
            val bottom = finite.maxOf { it.y } + 30f
            val scale = min(max(1f, width - padding * 2) / (right - left),
                max(1f, height - padding * 2) / (bottom - top)).coerceIn(MIN_SCALE, 2f)
            return GraphCamera(GraphPoint((left + right) / 2, (top + bottom) / 2), scale)
        }
    }
}
