package com.loooong.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDomainTest {

    @Test
    fun oversizedPdfPageIsReducedToRenderBudget() {
        val size = fitPdfRenderSize(
            widthPoints = 20_000f,
            heightPoints = 10_000f,
            rotation = 0,
            requestedScale = 2f,
            maxPixels = 12_000_000L,
            maxDimension = 8_192,
        )

        assertTrue(size.widthPx <= 8_192)
        assertTrue(size.heightPx <= 8_192)
        assertTrue(size.widthPx.toLong() * size.heightPx <= 12_000_000L)
        assertEquals(2f, size.widthPx.toFloat() / size.heightPx, 0.01f)
        assertTrue(size.scale < 2f)
    }

    @Test
    fun regularRotatedPdfPageKeepsRequestedRenderScale() {
        val size = fitPdfRenderSize(
            widthPoints = 612f,
            heightPoints = 792f,
            rotation = 90,
            requestedScale = 2f,
            maxPixels = 12_000_000L,
            maxDimension = 8_192,
        )

        assertEquals(1_584, size.widthPx)
        assertEquals(1_224, size.heightPx)
        assertEquals(2f, size.scale, 0.001f)
    }

    @Test
    fun renderRegionMappingUsesOrientedDimensionsAndClipsBounds() {
        val mapped = mapPdfRenderRegionToBitmap(
            left = 396f,
            top = 306f,
            right = 1188f,
            bottom = 918f,
            cropWidth = 612f,
            cropHeight = 792f,
            rotation = 90,
            baseScale = 2f,
            bitmapWidth = 792,
            bitmapHeight = 612,
        )

        assertEquals(PdfRenderRegionRect(198, 153, 594, 459), mapped)
        assertEquals(
            PdfRenderRegionRect(0, 0, 792, 612),
            mapPdfRenderRegionToBitmap(
                left = -20f,
                top = -20f,
                right = 2_000f,
                bottom = 2_000f,
                cropWidth = 612f,
                cropHeight = 792f,
                rotation = 90,
                baseScale = 2f,
                bitmapWidth = 792,
                bitmapHeight = 612,
            ),
        )
    }

    @Test
    fun splitRangesSupportSinglePagesClosedRangesAndOpenEnd() {
        assertEquals(
            listOf(0..2, 4..4, 6..7),
            parsePdfPageRanges("1-3, 5, 7-", pageCount = 8),
        )
        assertEquals(
            listOf(0..1, 2..3, 4..5, 6..6),
            buildPdfSplitRanges(pageCount = 7, pagesPerFile = 2),
        )
    }

    @Test
    fun splitRangesRejectInvalidOrOverlappingPages() {
        listOf("", "0-2", "3-2", "1-9", "1-3,3-5", "1--3").forEach { input ->
            try {
                parsePdfPageRanges(input, pageCount = 8)
                throw AssertionError("expected invalid split ranges to be rejected: $input")
            } catch (_: IllegalArgumentException) {
                // long: 非法或重叠页码必须被拒绝，避免拆分时重复输出或访问越界页面。
            }
        }
    }

    @Test
    fun readingHistoryMovesReopenedDocumentToFrontAndKeepsLatestProgress() {
        val existing = listOf(
            ReaderHistoryEntry("content://docs/a", "A.pdf", 1, 100L),
            ReaderHistoryEntry("content://docs/b", "B.pdf", 4, 90L),
        )

        val merged = mergeReadingHistory(
            existing,
            ReaderHistoryEntry("content://docs/a", "A.pdf", 7, 120L),
            limit = 10,
        )

        assertEquals(listOf("content://docs/a", "content://docs/b"), merged.map { it.uri })
        assertEquals(7, merged.first().pageIndex)
        assertEquals(120L, merged.first().updatedAt)
    }

    @Test
    fun readingHistoryIsBounded() {
        val existing = (0 until 20).map {
            ReaderHistoryEntry("content://docs/$it", "$it.pdf", it, 100L - it)
        }

        val merged = mergeReadingHistory(
            existing,
            ReaderHistoryEntry("content://docs/new", "新文档.pdf", 0, 200L),
            limit = 20,
        )

        assertEquals(20, merged.size)
        assertEquals("content://docs/new", merged.first().uri)
        assertFalse(merged.any { it.uri == "content://docs/19" })
        assertTrue(mergeReadingHistory(existing, existing.first(), limit = 0).isEmpty())
    }

    @Test
    fun readingHistoryRemovesOnlyTargetDocument() {
        val existing = listOf(
            ReaderHistoryEntry("content://docs/a", "A.pdf", 1, 100L),
            ReaderHistoryEntry("content://docs/b", "B.pdf", 4, 90L),
        )

        val remaining = removeReadingHistory(existing, "content://docs/a")

        assertEquals(listOf("content://docs/b"), remaining.map { it.uri })
    }

    @Test
    fun bookmarkToggleAddsThenRemovesPage() {
        val added = togglePageBookmark(setOf(1, 3), 2)
        assertEquals(setOf(1, 2, 3), added)

        val removed = togglePageBookmark(added, 2)
        assertEquals(setOf(1, 3), removed)
    }

    @Test
    fun fullTextSearchReturnsPageNumberAndCompactSnippet() {
        val searchableText = "Android PDF 阅读模式支持全文搜索，PDF 搜索结果会高亮。"
        val characterBounds = searchableText.mapIndexed { index, _ ->
            ReaderTextBounds(index * 10f, 20f, index * 10f + 10f, 36f)
        }
        val pages = listOf(
            ReaderPageTextIndex(0, "第一章 简介"),
            ReaderPageTextIndex(
                1,
                searchableText,
                characterBounds,
            ),
            ReaderPageTextIndex(2, "附录"),
        )

        val results = searchReaderIndex(pages, "pdf")

        assertEquals(2, results.size)
        assertEquals(listOf(1, 1), results.map { it.pageIndex })
        assertEquals(listOf(0, 1), results.map { it.matchIndex })
        assertTrue(results.first().snippet.contains("PDF 阅读模式", ignoreCase = true))
        assertEquals(listOf(ReaderTextBounds(80f, 20f, 110f, 36f)), results.first().bounds)
        assertTrue(searchReaderIndex(pages, "   ").isEmpty())
    }

    @Test
    fun readerPrefetchKeepsImmediateNeighborsInsideDocumentBounds() {
        assertEquals(listOf(0, 1), readerPrefetchPages(currentPage = 0, pageCount = 5))
        assertEquals(listOf(1, 2, 3), readerPrefetchPages(currentPage = 2, pageCount = 5))
        assertEquals(listOf(3, 4), readerPrefetchPages(currentPage = 4, pageCount = 5))
        assertTrue(readerPrefetchPages(currentPage = 0, pageCount = 0).isEmpty())
    }

    @Test
    fun repeatedSearchMatchesKeepTheirOwnSnippetContext() {
        val text = "PDF 第一处 " + "中间内容".repeat(24) + " PDF 第二处"

        val results = searchReaderIndex(listOf(ReaderPageTextIndex(0, text)), "PDF")

        assertEquals(2, results.size)
        assertTrue(results.first().snippet.contains("第一处"))
        assertTrue(results.last().snippet.contains("第二处"))
        assertFalse(results.last().snippet.contains("第一处"))
    }

    @Test
    fun searchResultNavigationStartsAtFirstAndWrapsAcrossDocumentEnds() {
        val results = listOf(
            ReaderSearchResult(0, 0, "第一页"),
            ReaderSearchResult(1, 2, "第三页第一处"),
            ReaderSearchResult(2, 2, "第三页第二处"),
        )

        assertEquals(0, moveReaderSearchSelection(results, currentMatchIndex = null, delta = 1)?.matchIndex)
        assertEquals(2, moveReaderSearchSelection(results, currentMatchIndex = null, delta = -1)?.matchIndex)
        assertEquals(2, moveReaderSearchSelection(results, currentMatchIndex = 0, delta = -1)?.matchIndex)
        assertEquals(0, moveReaderSearchSelection(results, currentMatchIndex = 2, delta = 1)?.matchIndex)
        assertEquals(1, moveReaderSearchSelection(results, currentMatchIndex = 0, delta = 1)?.matchIndex)
        assertEquals(null, moveReaderSearchSelection(emptyList(), currentMatchIndex = null, delta = 1))
    }
}
