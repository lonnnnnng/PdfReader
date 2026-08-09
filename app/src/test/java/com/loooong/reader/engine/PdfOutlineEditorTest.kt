package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfOutlineEditorTest {

    @Test
    fun rootAndChildOutlineEntriesPersistWithTheirPageTargets() {
        val document = PDDocument().apply {
            repeat(3) { addPage(PDPage()) }
        }

        assertTrue(PdfOutlineEditor.add(document, "第一章", pageIndex = 0, parentId = null))
        val root = PdfOutlineEditor.list(document).single()
        assertTrue(PdfOutlineEditor.add(document, "第一节", pageIndex = 2, parentId = root.id))

        assertEquals(
            listOf(
                OutlineExpectation("第一章", 0, 0, null),
                OutlineExpectation("第一节", 2, 1, "第一章"),
            ),
            expectations(PdfOutlineEditor.list(document)),
        )

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            assertEquals(
                listOf(
                    OutlineExpectation("第一章", 0, 0, null),
                    OutlineExpectation("第一节", 2, 1, "第一章"),
                ),
                expectations(PdfOutlineEditor.list(reopened)),
            )
            assertNull(PdfOutlineEditor.list(reopened).first().parentId)
        }
    }

    @Test
    fun updateChangesTitleAndPageWithoutDroppingOutlineStyle() {
        val document = PDDocument().apply {
            repeat(2) { addPage(PDPage()) }
        }
        PdfOutlineEditor.add(document, "旧标题", pageIndex = 0, parentId = null)
        document.documentCatalog.documentOutline.firstChild.apply {
            isBold = true
            isItalic = true
        }
        val entry = PdfOutlineEditor.list(document).single()

        assertTrue(PdfOutlineEditor.update(document, entry.id, "新标题", pageIndex = 1))
        assertEquals("新标题", PdfOutlineEditor.list(document).single().title)
        assertEquals(1, PdfOutlineEditor.list(document).single().pageIndex)
        assertTrue(document.documentCatalog.documentOutline.firstChild.isBold)
        assertTrue(document.documentCatalog.documentOutline.firstChild.isItalic)

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            assertEquals("新标题", PdfOutlineEditor.list(reopened).single().title)
            assertEquals(1, PdfOutlineEditor.list(reopened).single().pageIndex)
            assertTrue(reopened.documentCatalog.documentOutline.firstChild.isBold)
            assertTrue(reopened.documentCatalog.documentOutline.firstChild.isItalic)
        }
    }

    @Test
    fun moveReordersOnlySiblingsAndKeepsTheirSubtrees() {
        val document = PDDocument().apply {
            repeat(2) { addPage(PDPage()) }
        }
        PdfOutlineEditor.add(document, "第一章", pageIndex = 0, parentId = null)
        PdfOutlineEditor.add(document, "第二章", pageIndex = 1, parentId = null)
        val second = PdfOutlineEditor.list(document).first { it.title == "第二章" }
        PdfOutlineEditor.add(document, "第二章第一节", pageIndex = 1, parentId = second.id)
        val refreshedSecond = PdfOutlineEditor.list(document).first { it.title == "第二章" }

        assertTrue(PdfOutlineEditor.move(document, refreshedSecond.id, PdfOutlineMove.UP))
        assertEquals(
            listOf("第二章", "第二章第一节", "第一章"),
            PdfOutlineEditor.list(document).map(PdfOutlineEntry::title),
        )
        assertEquals(
            "第二章",
            PdfOutlineEditor.list(document).let { entries ->
                val child = entries.first { it.title == "第二章第一节" }
                entries.first { it.id == child.parentId }.title
            },
        )

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            assertEquals(
                listOf("第二章", "第二章第一节", "第一章"),
                PdfOutlineEditor.list(reopened).map(PdfOutlineEntry::title),
            )
        }
    }

    @Test
    fun deleteRemovesTheWholeSubtreeAndClearsAnEmptyOutline() {
        val document = PDDocument().apply {
            repeat(2) { addPage(PDPage()) }
        }
        PdfOutlineEditor.add(document, "保留章节", pageIndex = 0, parentId = null)
        PdfOutlineEditor.add(document, "待删章节", pageIndex = 1, parentId = null)
        val removedRoot = PdfOutlineEditor.list(document).first { it.title == "待删章节" }
        PdfOutlineEditor.add(document, "待删子节", pageIndex = 1, parentId = removedRoot.id)

        val refreshedRemovedRoot = PdfOutlineEditor.list(document).first { it.title == "待删章节" }
        assertTrue(PdfOutlineEditor.delete(document, refreshedRemovedRoot.id))
        assertEquals(listOf("保留章节"), PdfOutlineEditor.list(document).map(PdfOutlineEntry::title))

        assertTrue(PdfOutlineEditor.delete(document, PdfOutlineEditor.list(document).single().id))
        assertTrue(PdfOutlineEditor.list(document).isEmpty())
        assertNull(document.documentCatalog.documentOutline)

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        document.close()
        PDDocument.load(saved).use { reopened ->
            assertTrue(PdfOutlineEditor.list(reopened).isEmpty())
            assertNull(reopened.documentCatalog.documentOutline)
        }
    }

    private fun expectations(entries: List<PdfOutlineEntry>): List<OutlineExpectation> {
        val titleById = entries.associate { it.id to it.title }
        return entries.map { entry ->
            OutlineExpectation(
                title = entry.title,
                pageIndex = entry.pageIndex,
                level = entry.level,
                parentTitle = entry.parentId?.let(titleById::get),
            )
        }
    }

    private data class OutlineExpectation(
        val title: String,
        val pageIndex: Int?,
        val level: Int,
        val parentTitle: String?,
    )
}
