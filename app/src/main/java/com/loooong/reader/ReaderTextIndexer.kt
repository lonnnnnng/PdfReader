package com.loooong.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter

object ReaderTextIndexer {
    fun indexPage(document: PDDocument, pageIndex: Int): ReaderPageTextIndex {
        require(pageIndex in 0 until document.numberOfPages) { "页面索引越界" }
        val writer = StringWriter()
        val stripper = PositionAwareTextStripper(writer).apply {
            startPage = pageIndex + 1
            endPage = pageIndex + 1
            sortByPosition = true
        }
        stripper.writeSinglePage(document, document.getPage(pageIndex))
        return stripper.toIndex(pageIndex)
    }
}

private class PositionAwareTextStripper(
    private val textWriter: StringWriter,
) : PDFTextStripper() {
    private val characterBounds = ArrayList<ReaderTextBounds?>()

    fun writeSinglePage(document: PDDocument, page: PDPage) {
        this.document = document
        output = textWriter
        // long: writeText() 遍历页树时会先递增页号，直接 processPage() 则保留内部初始页号 0；范围必须显式包含 0。
        startPage = 0
        endPage = 0
        startDocument(document)
        try {
            // long: 直接处理目标页而不是每页调用 writeText() 遍历整棵页树，大文档后台索引保持线性页数复杂度。
            processPage(page)
        } finally {
            endDocument(document)
        }
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        val start = textWriter.buffer.length
        super.writeString(text, textPositions)
        while (characterBounds.size < start) characterBounds += null
        characterBounds += mapTextPositions(text, textPositions)
    }

    fun toIndex(pageIndex: Int): ReaderPageTextIndex {
        val text = textWriter.toString()
        while (characterBounds.size < text.length) characterBounds += null
        if (characterBounds.size > text.length) {
            characterBounds.subList(text.length, characterBounds.size).clear()
        }
        return ReaderPageTextIndex(pageIndex, text, characterBounds.toList())
    }

    private fun mapTextPositions(
        text: String,
        positions: List<TextPosition>,
    ): List<ReaderTextBounds?> {
        if (text.isEmpty()) return emptyList()
        val mapped = ArrayList<ReaderTextBounds?>(text.length)
        positions.forEach { position ->
            val unicode = position.unicode.orEmpty()
            if (unicode.isEmpty()) return@forEach
            val characterWidth = position.widthDirAdj.coerceAtLeast(0f) / unicode.length
            val height = position.heightDir.coerceAtLeast(1f)
            repeat(unicode.length) { characterIndex ->
                val left = position.xDirAdj + characterWidth * characterIndex
                mapped += ReaderTextBounds(
                    left = left,
                    top = (position.yDirAdj - height).coerceAtLeast(0f),
                    right = left + characterWidth,
                    bottom = position.yDirAdj,
                )
            }
        }
        if (mapped.size == text.length) return mapped

        // long: 字体 ToUnicode 映射可能让一个 glyph 产生多个 UTF-16 单元；数量异常时按文本长度重采样，
        // 保证索引偏移始终与最终 PDFTextStripper 文本一致，高亮最多降低精度而不会错位到其他命中。
        return List(text.length) { index ->
            mapped.getOrNull((index.toLong() * mapped.size / text.length).toInt())
        }
    }
}
