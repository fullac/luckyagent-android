package com.luckyagent.android.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphCameraTest {
    private fun assertPoint(expected: GraphPoint, actual: GraphPoint) {
        assertEquals(expected.x, actual.x, .001f)
        assertEquals(expected.y, actual.y, .001f)
    }

    @Test fun screenWorldRoundTripWithNonzeroCentre() {
        val camera = GraphCamera(GraphPoint(130f, -84f), .75f)
        val point = GraphPoint(312f, 47f)
        assertPoint(point, camera.toWorld(camera.toScreen(point, 360f, 400f), 360f, 400f))
    }

    @Test fun pinchKeepsWorldPointAtMovedCentroidRatherThanPanelOrigin() {
        val camera = GraphCamera(GraphPoint(130f, -84f), .75f)
        val anchor = GraphPoint(72f, 95f)
        val world = camera.toWorld(anchor, 360f, 400f)
        val next = camera.transform(anchor, GraphPoint(18f, -9f), 1.8f, 360f, 400f)
        assertPoint(GraphPoint(90f, 86f), next.toScreen(world, 360f, 400f))
    }

    @Test fun pinchAnchorAlsoHoldsWhenScaleIsClamped() {
        val camera = GraphCamera(scale = 7f)
        val anchor = GraphPoint(32f, 280f)
        val world = camera.toWorld(anchor, 360f, 400f)
        val next = camera.transform(anchor, GraphPoint(0f, 0f), 10f, 360f, 400f)
        assertEquals(GraphCamera.MAX_SCALE, next.scale, 0f)
        assertPoint(anchor, next.toScreen(world, 360f, 400f))
    }

    @Test fun fitUsesActualBoundsNotAFixedWorldRectangle() {
        val points = listOf(GraphPoint(-2400f, 300f), GraphPoint(3400f, -600f))
        val camera = GraphCamera.fit(points, 360f, 400f, 24f)
        points.forEach { point ->
            val screen = camera.toScreen(point, 360f, 400f)
            assertTrue(screen.x in 24f..336f && screen.y in 24f..376f)
        }
        assertPoint(GraphPoint(500f, -150f), camera.center)
    }

    @Test fun fitHandlesEmptySingleAndInvalidPositions() {
        assertEquals(GraphCamera(), GraphCamera.fit(emptyList(), 360f, 400f, 24f))
        val point = GraphPoint(800f, -300f)
        val camera = GraphCamera.fit(listOf(point, GraphPoint(Float.NaN, 0f)), 360f, 400f, 24f)
        assertPoint(point, camera.center)
        assertTrue(camera.scale.isFinite())
    }
}
