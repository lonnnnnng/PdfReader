package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Calendar

class PdfMetadataEditorTest {

    @Test
    fun metadataChangesPersistWithoutReplacingUnmanagedInformation() {
        val document = PDDocument().apply { addPage(PDPage()) }
        val creationDate = Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 2, 3, 4, 5) }
        document.documentInformation.apply {
            title = "旧标题"
            producer = "原始生成器"
            setCreationDate(creationDate)
            setCustomMetadataValue("Department", "研发部")
        }

        assertTrue(
            PdfMetadataEditor.update(
                document,
                PdfMetadata(
                    title = "新标题",
                    author = "long",
                    subject = "产品说明",
                    keywords = "阅读,PDF",
                    creator = "阅读",
                ),
            ),
        )
        assertFalse(PdfMetadataEditor.update(document, PdfMetadataEditor.read(document)))

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()

        PDDocument.load(saved).use { reopened ->
            assertEquals(
                PdfMetadata("新标题", "long", "产品说明", "阅读,PDF", "阅读"),
                PdfMetadataEditor.read(reopened),
            )
            assertEquals("原始生成器", reopened.documentInformation.producer)
            assertEquals("研发部", reopened.documentInformation.getCustomMetadataValue("Department"))
            assertEquals(
                creationDate.timeInMillis / 1_000L,
                reopened.documentInformation.creationDate.timeInMillis / 1_000L,
            )
            assertTrue(reopened.documentInformation.modificationDate != null)
        }
    }
}
