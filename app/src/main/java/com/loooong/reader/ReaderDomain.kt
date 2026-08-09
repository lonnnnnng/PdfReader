package com.loooong.reader

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class AppMode {
    EDIT,
    READ,
}

data class ReaderHistoryEntry(
    val uri: String,
    val title: String,
    val pageIndex: Int,
    val updatedAt: Long,
)

data class ReaderSearchResult(
    val matchIndex: Int,
    val pageIndex: Int,
    val snippet: String,
    val bounds: List<ReaderTextBounds> = emptyList(),
)

data class ReaderTextBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class ReaderPageTextIndex(
    val pageIndex: Int,
    val text: String,
    val characterBounds: List<ReaderTextBounds?> = emptyList(),
)

data class ReaderOutlineEntry(
    val title: String,
    val pageIndex: Int,
    val level: Int,
)

data class ReaderPageInfo(
    val pageIndex: Int,
    val widthPoints: Float,
    val heightPoints: Float,
    val cropX: Float,
    val cropY: Float,
    val cropWidthPoints: Float,
    val cropHeightPoints: Float,
    val rotation: Int,
)

internal data class PdfRenderSize(
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
)

internal data class PdfRenderRegionRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** long: 阅读器固定以基准 DPI 传递 tile 范围，回退渲染按实际整页位图比例映射，避免预算缩放后裁剪偏移。 */
internal fun mapPdfRenderRegionToBitmap(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cropWidth: Float,
    cropHeight: Float,
    rotation: Int,
    baseScale: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
): PdfRenderRegionRect? {
    require(cropWidth > 0f && cropHeight > 0f && baseScale > 0f) { "页面范围无效" }
    require(bitmapWidth > 0 && bitmapHeight > 0) { "位图尺寸无效" }
    val normalizedRotation = ((rotation % 360) + 360) % 360
    val pageWidthAtBase =
        (if (normalizedRotation == 90 || normalizedRotation == 270) cropHeight else cropWidth) * baseScale
    val pageHeightAtBase =
        (if (normalizedRotation == 90 || normalizedRotation == 270) cropWidth else cropHeight) * baseScale
    val clippedLeft = (left / pageWidthAtBase * bitmapWidth).roundToInt().coerceIn(0, bitmapWidth)
    val clippedTop = (top / pageHeightAtBase * bitmapHeight).roundToInt().coerceIn(0, bitmapHeight)
    val clippedRight = (right / pageWidthAtBase * bitmapWidth).roundToInt().coerceIn(0, bitmapWidth)
    val clippedBottom = (bottom / pageHeightAtBase * bitmapHeight).roundToInt().coerceIn(0, bitmapHeight)
    if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
    return PdfRenderRegionRect(clippedLeft, clippedTop, clippedRight, clippedBottom)
}

/** long: 每个逗号分段对应一个输出 PDF，严格禁止重叠可避免同一页被无意重复写入多个分片。 */
internal fun parsePdfPageRanges(input: String, pageCount: Int): List<IntRange> {
    require(pageCount > 0) { "PDF 没有可拆分页面" }
    val segments = input.replace('，', ',').split(',').map(String::trim)
    require(segments.isNotEmpty() && segments.none(String::isEmpty)) { "请输入有效页码范围" }
    val ranges = segments.map { segment ->
        val dashCount = segment.count { it == '-' }
        if (dashCount == 0) {
            val page = segment.toIntOrNull() ?: throw IllegalArgumentException("页码格式无效")
            require(page in 1..pageCount) { "页码超出文档范围" }
            (page - 1)..(page - 1)
        } else {
            require(dashCount == 1) { "页码范围格式无效" }
            val parts = segment.split('-', limit = 2)
            val start = parts[0].trim().takeIf(String::isNotEmpty)?.toIntOrNull() ?: 1
            val end = parts[1].trim().takeIf(String::isNotEmpty)?.toIntOrNull() ?: pageCount
            require(start in 1..pageCount && end in 1..pageCount) { "页码超出文档范围" }
            require(start <= end) { "页码范围起点不能大于终点" }
            (start - 1)..(end - 1)
        }
    }
    val overlaps = ranges.indices.any { leftIndex ->
        ((leftIndex + 1) until ranges.size).any { rightIndex ->
            val left = ranges[leftIndex]
            val right = ranges[rightIndex]
            maxOf(left.first, right.first) <= minOf(left.last, right.last)
        }
    }
    require(!overlaps) { "拆分页码范围不能重叠" }
    return ranges
}

/** long: 固定页数分片覆盖全部页面，最后不足一组时保留剩余页而不生成空文件。 */
internal fun buildPdfSplitRanges(pageCount: Int, pagesPerFile: Int): List<IntRange> {
    require(pageCount > 0) { "PDF 没有可拆分页面" }
    require(pagesPerFile > 0) { "每个分片页数必须大于 0" }
    return (0 until pageCount step pagesPerFile).map { start ->
        start..minOf(start + pagesPerFile - 1, pageCount - 1)
    }
}

/** long: 编辑画布限制单页位图预算，异常或超大页面不会按声明尺寸耗尽应用堆内存。 */
internal fun fitPdfRenderSize(
    widthPoints: Float,
    heightPoints: Float,
    rotation: Int,
    requestedScale: Float,
    maxPixels: Long,
    maxDimension: Int,
): PdfRenderSize {
    require(widthPoints.isFinite() && widthPoints > 0f) { "页面宽度无效" }
    require(heightPoints.isFinite() && heightPoints > 0f) { "页面高度无效" }
    require(requestedScale.isFinite() && requestedScale > 0f) { "渲染缩放无效" }
    require(maxPixels > 0L && maxDimension > 0) { "渲染预算无效" }

    val normalizedRotation = ((rotation % 360) + 360) % 360
    val orientedWidth = if (normalizedRotation == 90 || normalizedRotation == 270) {
        heightPoints.toDouble()
    } else {
        widthPoints.toDouble()
    }
    val orientedHeight = if (normalizedRotation == 90 || normalizedRotation == 270) {
        widthPoints.toDouble()
    } else {
        heightPoints.toDouble()
    }
    val desiredWidth = orientedWidth * requestedScale
    val desiredHeight = orientedHeight * requestedScale
    val dimensionFactor = maxDimension / maxOf(desiredWidth, desiredHeight)
    val pixelFactor = sqrt(maxPixels.toDouble() / (desiredWidth * desiredHeight))
    val factor = minOf(1.0, dimensionFactor, pixelFactor)
    var widthPx = (desiredWidth * factor).roundToInt().coerceIn(1, maxDimension)
    var heightPx = (desiredHeight * factor).roundToInt().coerceIn(1, maxDimension)
    val roundedPixels = widthPx.toLong() * heightPx
    if (roundedPixels > maxPixels) {
        val tightening = sqrt(maxPixels.toDouble() / roundedPixels)
        widthPx = floor(widthPx * tightening).toInt().coerceAtLeast(1)
        heightPx = floor(heightPx * tightening).toInt().coerceAtLeast(1)
    }
    val effectiveScale = minOf(widthPx / orientedWidth, heightPx / orientedHeight).toFloat()
    return PdfRenderSize(widthPx, heightPx, effectiveScale)
}

internal fun mergeReadingHistory(
    existing: List<ReaderHistoryEntry>,
    latest: ReaderHistoryEntry,
    limit: Int = 20,
): List<ReaderHistoryEntry> = if (limit <= 0) {
    emptyList()
} else {
    buildList {
        add(latest)
        existing.asSequence()
            .filterNot { it.uri == latest.uri }
            .take(limit - 1)
            .forEach(::add)
    }
}

internal fun removeReadingHistory(
    existing: List<ReaderHistoryEntry>,
    uri: String,
): List<ReaderHistoryEntry> = existing.filterNot { it.uri == uri }

internal fun togglePageBookmark(existing: Set<Int>, pageIndex: Int): Set<Int> =
    if (pageIndex in existing) existing - pageIndex else existing + pageIndex

internal fun searchReaderIndex(
    pages: List<ReaderPageTextIndex>,
    rawQuery: String,
): List<ReaderSearchResult> {
    val query = rawQuery.trim()
    if (query.isEmpty()) return emptyList()

    var matchIndex = 0
    return buildList {
        pages.forEach { page ->
            var searchFrom = 0
            while (searchFrom <= page.text.length - query.length) {
                val match = page.text.indexOf(query, startIndex = searchFrom, ignoreCase = true)
                if (match < 0) break
                add(
                    ReaderSearchResult(
                        matchIndex = matchIndex++,
                        pageIndex = page.pageIndex,
                        snippet = searchSnippet(page.text, match, query.length),
                        bounds = mergeReaderTextBounds(
                            page.characterBounds
                                .subList(
                                    match.coerceAtMost(page.characterBounds.size),
                                    (match + query.length).coerceAtMost(page.characterBounds.size),
                                )
                                .filterNotNull(),
                        ),
                    ),
                )
                searchFrom = match + query.length.coerceAtLeast(1)
            }
        }
    }
}

/** long: 只预取当前页的直接邻页，缓存命中能覆盖正常连续阅读，同时不会让大文档在后台持续占用渲染资源。 */
internal fun readerPrefetchPages(currentPage: Int, pageCount: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val safePage = currentPage.coerceIn(0, pageCount - 1)
    return ((safePage - 1).coerceAtLeast(0)..(safePage + 1).coerceAtMost(pageCount - 1)).toList()
}

private fun mergeReaderTextBounds(bounds: List<ReaderTextBounds>): List<ReaderTextBounds> {
    if (bounds.isEmpty()) return emptyList()
    return buildList {
        bounds.forEach { next ->
            val previous = lastOrNull()
            val sameLine = previous != null &&
                kotlin.math.abs(previous.top - next.top) <= maxOf(previous.bottom - previous.top, 1f) * 0.6f &&
                next.left - previous.right <= maxOf(previous.bottom - previous.top, 1f)
            if (sameLine) {
                this[lastIndex] = ReaderTextBounds(
                    left = minOf(previous!!.left, next.left),
                    top = minOf(previous.top, next.top),
                    right = maxOf(previous.right, next.right),
                    bottom = maxOf(previous.bottom, next.bottom),
                )
            } else {
                add(next)
            }
        }
    }
}

private fun searchSnippet(text: String, matchStart: Int, matchLength: Int): String {
    val compact = text.replace(Regex("\\s+"), " ").trim()
    val compactMatch = text.substring(0, matchStart)
        .replace(Regex("\\s+"), " ")
        .trimStart()
        .length
        .coerceIn(0, compact.length)
    val compactMatchLength = text.substring(matchStart, (matchStart + matchLength).coerceAtMost(text.length))
        .replace(Regex("\\s+"), " ")
        .length
    val start = (compactMatch - 28).coerceAtLeast(0)
    val end = (compactMatch + compactMatchLength + 44).coerceAtMost(compact.length)
    return buildString {
        if (start > 0) append("…")
        append(compact.substring(start, end))
        if (end < compact.length) append("…")
    }
}

class ReaderPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadHistory(): List<ReaderHistoryEntry> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ReaderHistoryEntry(
                            uri = item.getString("uri"),
                            title = item.optString("title", "文档.pdf"),
                            pageIndex = item.optInt("pageIndex", 0).coerceAtLeast(0),
                            updatedAt = item.optLong("updatedAt", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(entry: ReaderHistoryEntry) {
        val merged = mergeReadingHistory(loadHistory(), entry)
        saveHistory(merged)
    }

    fun removeHistory(uri: String) {
        saveHistory(removeReadingHistory(loadHistory(), uri))
        val root = bookmarkRoot().apply { remove(uri) }
        preferences.edit { putString(KEY_BOOKMARKS, root.toString()) }
    }

    private fun saveHistory(entries: List<ReaderHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("title", item.title)
                    .put("pageIndex", item.pageIndex)
                    .put("updatedAt", item.updatedAt),
            )
        }
        preferences.edit { putString(KEY_HISTORY, array.toString()) }
    }

    fun loadBookmarks(documentUri: String): Set<Int> {
        val root = bookmarkRoot()
        val array = root.optJSONArray(documentUri) ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) add(array.optInt(index, -1))
        }.filterTo(sortedSetOf()) { it >= 0 }
    }

    fun saveBookmarks(documentUri: String, pages: Set<Int>) {
        val root = bookmarkRoot()
        val array = JSONArray()
        pages.sorted().forEach(array::put)
        root.put(documentUri, array)
        preferences.edit { putString(KEY_BOOKMARKS, root.toString()) }
    }

    private fun bookmarkRoot(): JSONObject = runCatching {
        JSONObject(preferences.getString(KEY_BOOKMARKS, null) ?: "{}")
    }.getOrElse { JSONObject() }

    private companion object {
        const val PREFS_NAME = "reader_library"
        const val KEY_HISTORY = "history"
        const val KEY_BOOKMARKS = "bookmarks"
    }
}
