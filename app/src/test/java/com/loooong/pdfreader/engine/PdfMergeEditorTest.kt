package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMergeEditorTest {

    @Test
    fun mergePreservesSourceOrderAndPageGeometry() {
        val first = documentWithPages(PDRectangle(100f, 200f), PDRectangle(101f, 201f))
        val second = documentWithPages(PDRectangle(300f, 400f))

        try {
            val (merged, report) = PdfMergeEditor.merge(listOf(first, second))
            merged.use {
                assertEquals(2, report.sourceCount)
                assertEquals(3, report.pageCount)
                assertEquals(3, it.numberOfPages)
                assertEquals(100f, it.getPage(0).mediaBox.width)
                assertEquals(101f, it.getPage(1).mediaBox.width)
                assertEquals(300f, it.getPage(2).mediaBox.width)
                assertNotSame(first.getPage(0).cosObject, it.getPage(0).cosObject)
                val bytes = ByteArrayOutputStream().also(it::save).toByteArray()
                assertTrue(bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray()))
            }
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun mergeRejectsFewerThanTwoDocuments() {
        val document = documentWithPages(PDRectangle(100f, 200f))
        try {
            runCatching { PdfMergeEditor.merge(listOf(document)) }
                .onSuccess { throw AssertionError("合并单个 PDF 应该失败") }
                .onFailure { assertTrue(it.message.orEmpty().contains("两个")) }
        } finally {
            document.close()
        }
    }

    private fun documentWithPages(vararg boxes: PDRectangle): PDDocument = PDDocument().also { document ->
        boxes.forEach { document.addPage(PDPage(it)) }
    }
}
