package com.loooong.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementOperationsTest {
    @Test
    fun snapsNearestEdgeWithoutChangingOtherAxis() {
        val result = ElementSnapper.snap(
            moving = Bounds(10f, 10f, 30f, 30f),
            candidates = listOf(Bounds(50f, 60f, 70f, 80f)),
            page = Bounds(0f, 0f, 100f, 100f),
            dx = 18f,
            dy = 7f,
            threshold = 3f,
        )

        assertEquals(20f, result.dx, 0.001f)
        assertEquals(7f, result.dy, 0.001f)
        assertEquals(listOf(SnapGuide(SnapAxis.VERTICAL, 50f)), result.guides)
    }

    @Test
    fun snapsBothCentersToPageCenter() {
        val result = ElementSnapper.snap(
            moving = Bounds(10f, 10f, 30f, 30f),
            candidates = emptyList(),
            page = Bounds(0f, 0f, 100f, 100f),
            dx = 29f,
            dy = 28f,
            threshold = 2f,
        )

        assertEquals(30f, result.dx, 0.001f)
        assertEquals(30f, result.dy, 0.001f)
        assertEquals(
            listOf(
                SnapGuide(SnapAxis.VERTICAL, 50f),
                SnapGuide(SnapAxis.HORIZONTAL, 50f),
            ),
            result.guides,
        )
    }

    @Test
    fun leavesTranslationUntouchedOutsideThreshold() {
        val result = ElementSnapper.snap(
            moving = Bounds(10f, 10f, 30f, 30f),
            candidates = emptyList(),
            page = Bounds(0f, 0f, 100f, 100f),
            dx = 15f,
            dy = 7f,
            threshold = 3f,
        )

        assertEquals(15f, result.dx, 0.001f)
        assertEquals(7f, result.dy, 0.001f)
        assertTrue(result.guides.isEmpty())
    }

    @Test
    fun alignsVisualBoundsInPageSpace() {
        val a = node(1, 0f, 0f, 20f, 10f)
        val b = node(2, 40f, 30f, 60f, 50f)

        val left = ElementAlignment.compute(listOf(a, b), AlignmentAction.LEFT)
        assertEquals(1, left.size)
        assertEquals(-40f, left.single { it.id == 2 }.dx, 0.001f)

        val center = ElementAlignment.compute(listOf(a, b), AlignmentAction.VERTICAL_CENTER)
        assertEquals(-15f, center.single { it.id == 2 }.dy, 0.001f)
    }

    @Test
    fun distributesUnequalElementsKeepingOuterEdges() {
        val a = node(1, 0f, 0f, 10f, 10f)
        val b = node(2, 30f, 0f, 50f, 10f)
        val c = node(3, 80f, 0f, 90f, 10f)

        val result = ElementAlignment.compute(
            listOf(a, b, c),
            AlignmentAction.DISTRIBUTE_HORIZONTAL,
        )
        assertEquals(1, result.size)
        assertEquals(5f, result.single { it.id == 2 }.dx, 0.001f)
    }

    @Test
    fun distributionNeedsThreeElements() {
        val result = ElementAlignment.compute(
            listOf(node(1, 0f, 0f, 10f, 10f), node(2, 20f, 0f, 30f, 10f)),
            AlignmentAction.DISTRIBUTE_HORIZONTAL,
        )
        assertTrue(result.isEmpty())
    }

    private fun node(id: Int, minX: Float, minY: Float, maxX: Float, maxY: Float) =
        DrawNode(
            id = id,
            kind = NodeKind.PATH,
            label = "path",
            detail = "",
            startIndex = 0,
            endIndex = 0,
            bounds = Bounds(minX, minY, maxX, maxY),
            colorArgb = null,
            raw = "",
            children = emptyList(),
        )
}
