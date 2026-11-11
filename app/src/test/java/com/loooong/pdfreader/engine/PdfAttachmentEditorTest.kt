package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PdfAttachmentEditorTest {

    @Test
    fun attachmentCanBeAddedRenamedExportedAndDeletedAfterReload() {
        val firstBytes = "附件正文".toByteArray()
        val secondBytes = byteArrayOf(1, 2, 3, 4, 5)
        val saved = PDDocument().use { document ->
            document.addPage(PDPage())
            val firstKey = PdfAttachmentEditor.add(
                document = document,
                fileName = "说明.txt",
                description = "第一份说明",
                mimeType = "text/plain",
                source = ByteArrayInputStream(firstBytes),
                sourceSize = firstBytes.size.toLong(),
            )
            val secondKey = PdfAttachmentEditor.add(
                document = document,
                fileName = "说明.txt",
                description = null,
                mimeType = "application/octet-stream",
                source = ByteArrayInputStream(secondBytes),
                sourceSize = secondBytes.size.toLong(),
            )

            assertEquals("说明.txt", firstKey)
            assertEquals("说明 (2).txt", secondKey)
            assertEquals(2, PdfAttachmentEditor.list(document).size)
            ByteArrayOutputStream().also(document::save).toByteArray()
        }

        PDDocument.load(saved).use { reopened ->
            val attachments = PdfAttachmentEditor.list(reopened)
            assertEquals(listOf("说明 (2).txt", "说明.txt"), attachments.map(PdfAttachmentInfo::key))
            assertTrue(attachments.all(PdfAttachmentInfo::readable))

            val exported = ByteArrayOutputStream()
            assertEquals(firstBytes.size.toLong(), PdfAttachmentEditor.writeTo(reopened, "说明.txt", exported))
            assertArrayEquals(firstBytes, exported.toByteArray())

            val renamed = PdfAttachmentEditor.rename(
                reopened,
                key = "说明.txt",
                fileName = "使用指南.txt",
                description = "更新后的说明",
            )
            assertEquals("使用指南.txt", renamed)
            assertEquals("更新后的说明", PdfAttachmentEditor.list(reopened).single {
                it.key == "使用指南.txt"
            }.description)

            assertTrue(PdfAttachmentEditor.delete(reopened, "说明 (2).txt"))
            assertTrue(PdfAttachmentEditor.delete(reopened, "使用指南.txt"))
            assertFalse(PdfAttachmentEditor.delete(reopened, "使用指南.txt"))
            assertTrue(PdfAttachmentEditor.list(reopened).isEmpty())
            assertNull(reopened.documentCatalog.names?.embeddedFiles)
        }
    }
}
