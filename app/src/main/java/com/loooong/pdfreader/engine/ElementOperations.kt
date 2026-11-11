package com.loooong.pdfreader.engine

import kotlin.math.abs

enum class AlignmentAction {
    LEFT,
    HORIZONTAL_CENTER,
    RIGHT,
    TOP,
    VERTICAL_CENTER,
    BOTTOM,
    DISTRIBUTE_HORIZONTAL,
    DISTRIBUTE_VERTICAL,
}

enum class LayerAction { FORWARD, BACKWARD, TO_FRONT, TO_BACK }

data class ElementTranslation(val id: Int, val dx: Float, val dy: Float)

enum class SnapAxis { VERTICAL, HORIZONTAL }

data class SnapGuide(val axis: SnapAxis, val position: Float)

data class SnapResult(
    val dx: Float,
    val dy: Float,
    val guides: List<SnapGuide> = emptyList(),
)

object ElementSnapper {
    /** long: 阈值与输入坐标使用同一单位，调用方可按画布缩放换算，保证不同缩放级别的吸附手感一致。 */
    fun snap(
        moving: Bounds,
        candidates: List<Bounds>,
        page: Bounds?,
        dx: Float,
        dy: Float,
        threshold: Float,
    ): SnapResult {
        if (!moving.isValid || !dx.isFinite() || !dy.isFinite() ||
            !threshold.isFinite() || threshold < 0f
        ) {
            return SnapResult(dx, dy)
        }
        val xTargets = buildTargets(candidates, page, horizontal = true)
        val yTargets = buildTargets(candidates, page, horizontal = false)
        val xMatch = nearestMatch(axisValues(moving, horizontal = true).map { it + dx }, xTargets, threshold)
        val yMatch = nearestMatch(axisValues(moving, horizontal = false).map { it + dy }, yTargets, threshold)
        return SnapResult(
            dx = dx + (xMatch?.adjustment ?: 0f),
            dy = dy + (yMatch?.adjustment ?: 0f),
            guides = buildList {
                xMatch?.let { add(SnapGuide(SnapAxis.VERTICAL, it.target)) }
                yMatch?.let { add(SnapGuide(SnapAxis.HORIZONTAL, it.target)) }
            },
        )
    }

    private fun buildTargets(candidates: List<Bounds>, page: Bounds?, horizontal: Boolean): List<Float> =
        buildList {
            candidates.filter(Bounds::isValid).forEach { addAll(axisValues(it, horizontal)) }
            page?.takeIf(Bounds::isValid)?.let { addAll(axisValues(it, horizontal)) }
        }.distinct()

    private fun axisValues(bounds: Bounds, horizontal: Boolean): List<Float> = if (horizontal) {
        listOf(bounds.minX, (bounds.minX + bounds.maxX) / 2f, bounds.maxX)
    } else {
        listOf(bounds.minY, (bounds.minY + bounds.maxY) / 2f, bounds.maxY)
    }

    private fun nearestMatch(sources: List<Float>, targets: List<Float>, threshold: Float): Match? =
        sources.flatMap { source -> targets.map { target -> Match(target - source, target) } }
            .filter { abs(it.adjustment) <= threshold }
            .minByOrNull { abs(it.adjustment) }

    private data class Match(val adjustment: Float, val target: Float)
}

object ElementAlignment {
    /** long: 所有对齐均使用 PDF 页面坐标的视觉外接框，页面旋转只影响显示，不参与这里的几何计算。 */
    fun compute(nodes: List<DrawNode>, action: AlignmentAction): List<ElementTranslation> {
        val items = nodes.mapNotNull { node -> node.bounds?.let { node to it } }
        if (items.size < 2) return emptyList()
        val minX = items.minOf { it.second.minX }
        val maxX = items.maxOf { it.second.maxX }
        val minY = items.minOf { it.second.minY }
        val maxY = items.maxOf { it.second.maxY }
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f

        val translations = when (action) {
            AlignmentAction.LEFT -> items.map { (node, b) -> ElementTranslation(node.id, minX - b.minX, 0f) }
            AlignmentAction.HORIZONTAL_CENTER -> items.map { (node, b) ->
                ElementTranslation(node.id, centerX - (b.minX + b.maxX) / 2f, 0f)
            }
            AlignmentAction.RIGHT -> items.map { (node, b) -> ElementTranslation(node.id, maxX - b.maxX, 0f) }
            AlignmentAction.TOP -> items.map { (node, b) -> ElementTranslation(node.id, 0f, maxY - b.maxY) }
            AlignmentAction.VERTICAL_CENTER -> items.map { (node, b) ->
                ElementTranslation(node.id, 0f, centerY - (b.minY + b.maxY) / 2f)
            }
            AlignmentAction.BOTTOM -> items.map { (node, b) -> ElementTranslation(node.id, 0f, minY - b.minY) }
            AlignmentAction.DISTRIBUTE_HORIZONTAL -> distribute(items, horizontal = true)
            AlignmentAction.DISTRIBUTE_VERTICAL -> distribute(items, horizontal = false)
        }
        return translations.filter { kotlin.math.abs(it.dx) > EPSILON || kotlin.math.abs(it.dy) > EPSILON }
    }

    private fun distribute(
        items: List<Pair<DrawNode, Bounds>>,
        horizontal: Boolean,
    ): List<ElementTranslation> {
        if (items.size < 3) return emptyList()
        val sorted = if (horizontal) items.sortedBy { it.second.minX } else items.sortedBy { it.second.minY }
        val first = sorted.first().second
        val last = sorted.last().second
        val span = if (horizontal) last.maxX - first.minX else last.maxY - first.minY
        val occupied = sorted.sumOf {
            (if (horizontal) it.second.width else it.second.height).toDouble()
        }.toFloat()
        val gap = (span - occupied) / (sorted.size - 1)
        var cursor = if (horizontal) first.minX else first.minY
        return sorted.mapIndexed { index, (node, bounds) ->
            val delta = if (index == 0 || index == sorted.lastIndex) {
                0f
            } else if (horizontal) {
                cursor - bounds.minX
            } else {
                cursor - bounds.minY
            }
            cursor += (if (horizontal) bounds.width else bounds.height) + gap
            if (horizontal) ElementTranslation(node.id, delta, 0f)
            else ElementTranslation(node.id, 0f, delta)
        }
    }

    private const val EPSILON = 0.001f
}
