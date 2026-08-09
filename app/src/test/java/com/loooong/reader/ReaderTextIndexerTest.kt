package com.loooong.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextIndexerTest {

    @Test
    fun indexPageKeepsSearchableTextAlignedWithCharacterBounds() {
        PDDocument().use { document ->
            val firstPage = PDPage(PDRectangle.A4)
            document.addPage(firstPage)
            PDPageContentStream(document, firstPage).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 18f)
                content.newLineAtOffset(72f, 720f)
                content.showText("FIRST")
                content.endText()
            }
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 18f)
                content.newLineAtOffset(72f, 720f)
                content.showText("PDF PDF")
                content.endText()
            }

            val index = ReaderTextIndexer.indexPage(document, 1)
            val results = searchReaderIndex(listOf(index), "PDF")

            assertTrue(index.text.contains("PDF PDF"))
            assertFalse(index.text.contains("FIRST"))
            assertEquals(index.text.length, index.characterBounds.size)
            assertEquals(2, results.size)
            assertFalse(results.first().bounds.isEmpty())
            assertTrue(results.first().bounds.single().right > results.first().bounds.single().left)
        }
    }
}
