package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PageTreeEditorTest {

    @Test
    fun deleteMiddlePageKeepsRemainingOrderAndReloads() {
        val document = documentWithPages(3)

        assertEquals(1, PageTreeEditor.delete(document, 1))
        assertEquals(2, document.numberOfPages)
        assertEquals(listOf("page-0", "page-2"), markers(document))

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            assertEquals(2, reopened.numberOfPages)
            assertEquals(listOf("page-0", "page-2"), markers(reopened))
        }
        document.close()
    }

    @Test
    fun cannotDeleteTheLastRemainingPage() {
        val document = documentWithPages(1)

        try {
            PageTreeEditor.delete(document, 0)
            throw AssertionError("expected delete to reject the last page")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("至少需要保留一页"))
        } finally {
            document.close()
        }
    }

    @Test
    fun deleteSelectedPagesKeepsRemainingOrderAndChoosesNextSurvivingPage() {
        val document = documentWithPages(5)

        assertEquals(2, PageTreeEditor.deleteSelected(document, setOf(3, 1), currentIndex = 3))
        assertEquals(listOf("page-0", "page-2", "page-4"), markers(document))

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            assertEquals(listOf("page-0", "page-2", "page-4"), markers(reopened))
        }
        document.close()
    }

    @Test
    fun deleteSelectedPagesKeepsUnselectedCurrentPageVisible() {
        val document = documentWithPages(5)

        assertEquals(1, PageTreeEditor.deleteSelected(document, setOf(3, 1), currentIndex = 2))
        assertEquals("page-2", markers(document)[1])
        document.close()
    }

    @Test
    fun deleteSelectedPagesRejectsInvalidSelectionsWithoutChangingDocument() {
        listOf(
            emptySet(),
            setOf(-1),
            setOf(0, 3),
            setOf(0, 1, 2),
        ).forEach { indices ->
            val document = documentWithPages(3)
            try {
                PageTreeEditor.deleteSelected(document, indices, currentIndex = 1)
                throw AssertionError("expected batch delete to reject invalid pages")
            } catch (expected: IllegalArgumentException) {
                assertEquals(listOf("page-0", "page-1", "page-2"), markers(document))
            } finally {
                document.close()
            }
        }
    }

    @Test
    fun rotateSelectedPagesChangesOnlySelectionAndReloads() {
        val document = documentWithPages(3)
        document.getPage(0).rotation = 270
        document.getPage(1).rotation = 180

        PageTreeEditor.rotateSelected(document, setOf(2, 0), degrees = 90)

        assertEquals(listOf(0, 180, 90), (0 until 3).map { document.getPage(it).rotation })
        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            assertEquals(listOf(0, 180, 90), (0 until 3).map { reopened.getPage(it).rotation })
        }
        document.close()
    }

    @Test
    fun rotateSelectedPagesRejectsInvalidRequestsBeforeChangingPages() {
        listOf(
            emptySet<Int>() to 90,
            setOf(-1) to 90,
            setOf(0, 3) to 90,
            setOf(0, 2) to 45,
        ).forEach { (indices, degrees) ->
            val document = documentWithPages(3)
            try {
                PageTreeEditor.rotateSelected(document, indices, degrees)
                throw AssertionError("expected batch rotation to reject invalid input")
            } catch (expected: IllegalArgumentException) {
                assertEquals(listOf(0, 0, 0), (0 until 3).map { document.getPage(it).rotation })
            } finally {
                document.close()
            }
        }
    }

    @Test
    fun importAllPagesInsertsAfterTargetAndPreservesPageProperties() {
        val target = documentWithPages(2, prefix = "target")
        val source = documentWithPages(2, prefix = "source")
        source.getPage(0).apply {
            rotation = 90
            cropBox = PDRectangle(5f, 10f, 80f, 160f)
            annotations = listOf(PDAnnotationText().apply { contents = "导入批注" })
        }

        assertEquals(1, PageTreeEditor.importAll(target, source, afterIndex = 0))
        assertEquals(listOf("target-0", "source-0", "source-1", "target-1"), markers(target))
        assertEquals(90, target.getPage(1).rotation)
        assertEquals(80f, target.getPage(1).cropBox.width, 0.001f)
        assertEquals("导入批注", target.getPage(1).annotations.single().contents)
        assertEquals(listOf("source-0", "source-1"), markers(source))
        source.close()

        val saved = ByteArrayOutputStream().also(target::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            assertEquals(listOf("target-0", "source-0", "source-1", "target-1"), markers(reopened))
            assertEquals(90, reopened.getPage(1).rotation)
            assertEquals("导入批注", reopened.getPage(1).annotations.single().contents)
        }
        target.close()
    }

    @Test
    fun importAllPagesCopiesInheritedResourcesIntoDestinationDocument() {
        val target = documentWithPages(1, prefix = "target")
        val source = PDDocument()
        val sourcePage = PDPage(PDRectangle(100f, 200f))
        source.addPage(sourcePage)
        val fontName = COSName.getPDFName("InheritedFont")
        val inheritedResources = PDResources().apply {
            put(fontName, PDType1Font.HELVETICA)
        }
        source.documentCatalog.pages.cosObject.setItem(COSName.RESOURCES, inheritedResources)
        sourcePage.cosObject.removeItem(COSName.RESOURCES)
        sourcePage.setContents(PDStream(source).also { stream ->
            stream.createOutputStream().use { output ->
                output.write("BT /InheritedFont 12 Tf 10 20 Td (Hello) Tj ET".toByteArray())
            }
        })

        PageTreeEditor.importAll(target, source, afterIndex = 0)
        source.close()

        val imported = target.getPage(1)
        assertTrue(imported.cosObject.containsKey(COSName.RESOURCES))
        assertEquals("Helvetica", imported.resources.getFont(fontName).name)
        val saved = ByteArrayOutputStream().also(target::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            val reopenedPage = reopened.getPage(1)
            assertTrue(reopenedPage.cosObject.containsKey(COSName.RESOURCES))
            assertEquals("Helvetica", reopenedPage.resources.getFont(fontName).name)
        }
        target.close()
    }

    @Test
    fun importAllPagesMaterializesInheritedGeometryAndRotation() {
        val target = documentWithPages(1, prefix = "target")
        val source = PDDocument()
        val sourcePage = PDPage()
        source.addPage(sourcePage)
        val pageTree = source.documentCatalog.pages.cosObject
        pageTree.setItem(COSName.MEDIA_BOX, PDRectangle(11f, 22f, 311f, 422f).cosObject)
        pageTree.setItem(COSName.CROP_BOX, PDRectangle(17f, 29f, 271f, 389f).cosObject)
        pageTree.setInt(COSName.ROTATE, 270)
        // long: 让页面只依赖父节点，验证跨文档后不再依赖已关闭的源页树。
        sourcePage.cosObject.removeItem(COSName.MEDIA_BOX)
        sourcePage.cosObject.removeItem(COSName.CROP_BOX)
        sourcePage.cosObject.removeItem(COSName.ROTATE)

        PageTreeEditor.importAll(target, source, afterIndex = 0)
        source.close()

        val imported = target.getPage(1)
        assertEquals(311f, imported.mediaBox.width, 0.001f)
        assertEquals(422f, imported.mediaBox.height, 0.001f)
        assertEquals(271f, imported.cropBox.width, 0.001f)
        assertEquals(389f, imported.cropBox.height, 0.001f)
        assertEquals(270, imported.rotation)
        val saved = ByteArrayOutputStream().also(target::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            val page = reopened.getPage(1)
            assertEquals(311f, page.mediaBox.width, 0.001f)
            assertEquals(271f, page.cropBox.width, 0.001f)
            assertEquals(270, page.rotation)
        }
        target.close()
    }

    @Test
    fun duplicatePreservesPagePropertiesAndInsertsAfterSource() {
        val document = documentWithPages(2)
        val source = document.getPage(0)
        source.rotation = 90
        source.annotations = listOf(PDAnnotationText().apply { contents = "复制批注" })

        assertEquals(1, PageTreeEditor.duplicate(document, 0))
        assertEquals(3, document.numberOfPages)
        assertEquals(listOf("page-0", "page-0", "page-1"), markers(document))

        val duplicate = document.getPage(1)
        assertEquals(90, duplicate.rotation)
        assertEquals(source.mediaBox.width, duplicate.mediaBox.width, 0.001f)
        assertEquals(1, duplicate.annotations.size)
        assertEquals("复制批注", duplicate.annotations.single().contents)
        document.close()
    }

    @Test
    fun duplicateSelectedPagesKeepsSelectionOrderAndCurrentPageIdentity() {
        val document = documentWithPages(4)

        assertEquals(2, PageTreeEditor.duplicateSelected(document, setOf(0, 2), currentIndex = 2))
        assertEquals(
            listOf("page-0", "page-1", "page-2", "page-0", "page-2", "page-3"),
            markers(document),
        )
        document.close()
    }

    @Test
    fun moveSelectedPagesPreservesRelativeOrderAndMovesCurrentPage() {
        val document = documentWithPages(4)

        assertEquals(3, PageTreeEditor.moveSelected(document, setOf(1, 2), delta = 1, currentIndex = 2))
        assertEquals(listOf("page-0", "page-3", "page-1", "page-2"), markers(document))

        assertEquals(2, PageTreeEditor.moveSelected(document, setOf(1, 3), delta = -1, currentIndex = 3))
        assertEquals(listOf("page-3", "page-0", "page-2", "page-1"), markers(document))
        document.close()
    }

    @Test
    fun batchPageOperationsRejectInvalidSelectionWithoutChangingOrder() {
        listOf(emptySet<Int>(), setOf(-1), setOf(0, 4)).forEach { indices ->
            val document = documentWithPages(3)
            try {
                PageTreeEditor.duplicateSelected(document, indices, currentIndex = 0)
                fail("批量复制应拒绝无效页面选择")
            } catch (expected: IllegalArgumentException) {
                assertEquals(listOf("page-0", "page-1", "page-2"), markers(document))
            } finally {
                document.close()
            }
        }
    }

    @Test
    fun moveChangesOrderAndReturnsNewIndex() {
        val document = documentWithPages(3)

        assertEquals(0, PageTreeEditor.move(document, 1, -1))
        assertEquals(listOf("page-1", "page-0", "page-2"), markers(document))
        assertEquals(2, PageTreeEditor.move(document, 1, 1))
        assertEquals(listOf("page-1", "page-2", "page-0"), markers(document))
        document.close()
    }

    @Test
    fun insertBlankPageSupportsDocumentStartAndKeepsNeighborPageSize() {
        val document = documentWithPages(2)
        document.getPage(0).rotation = 90

        assertEquals(0, PageTreeEditor.insertBlank(document, -1))
        assertEquals(3, document.numberOfPages)
        assertEquals(listOf("", "page-0", "page-1"), markers(document))
        assertEquals(100f, document.getPage(0).mediaBox.width, 0.001f)
        assertEquals(200f, document.getPage(0).mediaBox.height, 0.001f)
        assertEquals(90, document.getPage(0).rotation)

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            assertEquals(listOf("", "page-0", "page-1"), markers(reopened))
            assertEquals(90, reopened.getPage(0).rotation)
        }
        document.close()
    }

    @Test
    fun insertBlankPageCreatesTheFirstPageForAnEmptyDocument() {
        val document = PDDocument()

        assertEquals(0, PageTreeEditor.insertBlank(document, -1))
        assertEquals(1, document.numberOfPages)
        assertEquals(listOf(""), markers(document))
        document.close()
    }

    @Test
    fun insertBlankPageAfterMiddlePageUsesThatPageAsTemplate() {
        val document = documentWithPages(3)

        assertEquals(2, PageTreeEditor.insertBlank(document, 1))
        assertEquals(listOf("page-0", "page-1", "", "page-2"), markers(document))
        assertEquals(101f, document.getPage(2).mediaBox.width, 0.001f)
        assertEquals(201f, document.getPage(2).mediaBox.height, 0.001f)
        document.close()
    }

    @Test
    fun moveToSupportsArbitraryTargetsAndReloads() {
        val document = documentWithPages(4)

        assertEquals(3, PageTreeEditor.moveTo(document, 0, 3))
        assertEquals(listOf("page-1", "page-2", "page-3", "page-0"), markers(document))
        assertEquals(0, PageTreeEditor.moveTo(document, 2, 0))
        assertEquals(listOf("page-3", "page-1", "page-2", "page-0"), markers(document))

        val saved = ByteArrayOutputStream().also(document::save).toByteArray()
        PDDocument.load(saved).use { reopened ->
            assertEquals(listOf("page-3", "page-1", "page-2", "page-0"), markers(reopened))
        }
        document.close()
    }

    @Test
    fun moveToSameIndexDoesNotChangePageOrder() {
        val document = documentWithPages(3)

        assertEquals(1, PageTreeEditor.moveTo(document, 1, 1))
        assertEquals(listOf("page-0", "page-1", "page-2"), markers(document))
        document.close()
    }

    @Test
    fun insertAndMoveToRejectOutOfRangeIndexes() {
        val document = documentWithPages(2)

        listOf(
            { PageTreeEditor.insertBlank(document, -2) },
            { PageTreeEditor.insertBlank(document, 2) },
            { PageTreeEditor.moveTo(document, -1, 0) },
            { PageTreeEditor.moveTo(document, 0, 2) },
        ).forEach { operation ->
            try {
                operation()
                throw AssertionError("expected page operation to reject an invalid index")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("页面索引越界"))
            }
        }
        document.close()
    }

    @Test
    fun extractSelectedPagesPreservesDocumentOrderAndPageProperties() {
        val document = documentWithPages(3)
        document.getPage(0).apply {
            rotation = 90
            cropBox = PDRectangle(5f, 10f, 80f, 160f)
        }
        document.getPage(2).annotations = listOf(PDAnnotationText().apply { contents = "导出批注" })

        PageTreeEditor.extract(document, setOf(2, 0)).use { extracted ->
            assertEquals(2, extracted.numberOfPages)
            assertEquals(listOf("page-0", "page-2"), markers(extracted))
            assertEquals(90, extracted.getPage(0).rotation)
            assertEquals(80f, extracted.getPage(0).cropBox.width, 0.001f)
            assertEquals("导出批注", extracted.getPage(1).annotations.single().contents)

            val saved = ByteArrayOutputStream().also(extracted::save).toByteArray()
            PDDocument.load(saved).use { reopened ->
                assertEquals(listOf("page-0", "page-2"), markers(reopened))
                assertEquals(90, reopened.getPage(0).rotation)
                assertEquals("导出批注", reopened.getPage(1).annotations.single().contents)
            }
        }

        assertEquals(3, document.numberOfPages)
        assertEquals(listOf("page-0", "page-1", "page-2"), markers(document))
        document.close()
    }

    @Test
    fun extractedAnnotationsRemainIndependentAfterSourceChangesAndClose() {
        val document = documentWithPages(1)
        val sourceAnnotation = PDAnnotationText().apply { contents = "原始批注" }
        document.getPage(0).annotations = listOf(sourceAnnotation)

        val extracted = PageTreeEditor.extract(document, setOf(0))
        sourceAnnotation.contents = "源文档已修改"
        document.close()

        extracted.use { result ->
            assertEquals("原始批注", result.getPage(0).annotations.single().contents)
            val saved = ByteArrayOutputStream().also(result::save).toByteArray()
            PDDocument.load(saved).use { reopened ->
                assertEquals("原始批注", reopened.getPage(0).annotations.single().contents)
            }
        }
    }

    @Test
    fun extractedPagesPreserveXmpMetadataAfterSourceClose() {
        val document = documentWithPages(2)
        val xmp = """<?xpacket begin='﻿'?>
            <x:xmpmeta xmlns:x='adobe:ns:meta/'>
              <rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'/>
            </x:xmpmeta>
            <?xpacket end='w'?>
        """.trimIndent().toByteArray()
        document.documentCatalog.metadata = PDMetadata(document).apply {
            importXMPMetadata(xmp)
        }

        val extracted = PageTreeEditor.extract(document, setOf(1))
        document.close()

        extracted.use { result ->
            assertArrayEquals(xmp, result.documentCatalog.metadata.exportXMPMetadata().use { it.readBytes() })
            val saved = ByteArrayOutputStream().also(result::save).toByteArray()
            PDDocument.load(saved).use { reopened ->
                assertArrayEquals(xmp, reopened.documentCatalog.metadata.exportXMPMetadata().use { it.readBytes() })
            }
        }
    }

    @Test
    fun extractedPagesPreserveDocumentAttachments() {
        val source = documentWithPages(2)
        val attachmentBytes = "导出后仍保留".toByteArray()
        PdfAttachmentEditor.add(
            document = source,
            fileName = "证据.txt",
            description = "页面子集附件",
            mimeType = "text/plain",
            source = ByteArrayInputStream(attachmentBytes),
            sourceSize = attachmentBytes.size.toLong(),
        )

        val extracted = PageTreeEditor.extract(source, setOf(1))
        source.close()
        extracted.use { document ->
            val attachment = PdfAttachmentEditor.list(document).single()
            assertEquals("证据.txt", attachment.fileName)
            assertEquals("页面子集附件", attachment.description)
            val exported = ByteArrayOutputStream()
            PdfAttachmentEditor.writeTo(document, attachment.key, exported)
            assertArrayEquals(attachmentBytes, exported.toByteArray())
        }
    }

    @Test
    fun extractedPagesPreserveVisiblePageLabelsForNonContiguousSelection() {
        val document = documentWithPages(4)
        document.documentCatalog.pageLabels = PDPageLabels(document).apply {
            setLabelItem(0, PDPageLabelRange().apply {
                style = PDPageLabelRange.STYLE_ROMAN_LOWER
                prefix = "前言-"
                start = 1
            })
            setLabelItem(2, PDPageLabelRange().apply {
                style = PDPageLabelRange.STYLE_DECIMAL
                prefix = "正文-"
                start = 1
            })
        }

        val extracted = PageTreeEditor.extract(document, setOf(2, 0))
        document.close()

        extracted.use { result ->
            assertEquals(
                listOf("前言-i", "正文-1"),
                result.documentCatalog.pageLabels.labelsByPageIndices.toList(),
            )
            val saved = ByteArrayOutputStream().also(result::save).toByteArray()
            PDDocument.load(saved).use { reopened ->
                assertEquals(
                    listOf("前言-i", "正文-1"),
                    reopened.documentCatalog.pageLabels.labelsByPageIndices.toList(),
                )
            }
        }
    }

    @Test
    fun extractedInternalLinksRemapToTheCopiedTargetPage() {
        val document = documentWithPages(3)
        val destination = PDPageXYZDestination().apply {
            page = document.getPage(2)
            left = 17
            top = 33
            zoom = 1.25f
        }
        document.getPage(0).annotations = listOf(
            PDAnnotationLink().apply {
                rectangle = PDRectangle(5f, 6f, 40f, 20f)
                action = PDActionGoTo().apply { this.destination = destination }
                page = document.getPage(0)
            },
        )

        val extracted = PageTreeEditor.extract(document, setOf(2, 0))
        document.close()

        extracted.use { result ->
            assertInternalLinkTarget(result, expectedPageIndex = 1)
            val saved = ByteArrayOutputStream().also(result::save).toByteArray()
            PDDocument.load(saved).use { reopened ->
                assertInternalLinkTarget(reopened, expectedPageIndex = 1)
            }
        }
    }

    @Test
    fun extractionRejectsInternalLinksWhoseTargetPageWasNotSelected() {
        val document = documentWithPages(2)
        val destination = PDPageXYZDestination().apply { page = document.getPage(1) }
        document.getPage(0).annotations = listOf(
            PDAnnotationLink().apply {
                rectangle = PDRectangle(5f, 6f, 40f, 20f)
                action = PDActionGoTo().apply { this.destination = destination }
                page = document.getPage(0)
            },
        )

        try {
            PageTreeEditor.extract(document, setOf(0)).close()
            fail("指向未选页面的链接不应变成失效链接")
        } catch (expected: PdfPageTransferException) {
            assertTrue(expected.message.orEmpty().contains("未导出页面"))
        } finally {
            document.close()
        }
    }

    @Test
    fun extractionRejectsAcroFormInsteadOfCreatingOrphanWidgets() {
        val document = documentWithPages(1)
        PdfFormEditor.create(
            document,
            PdfFormFieldCreateRequest(
                kind = PdfFormFieldCreateKind.TEXT,
                name = "customer-name",
                label = "客户名称",
                pageIndex = 0,
                bounds = Bounds(10f, 20f, 120f, 48f),
            ),
        )

        try {
            PageTreeEditor.extract(document, setOf(0)).close()
            fail("包含表单字段的页面不应被静默导出")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("表单"))
        } finally {
            document.close()
        }
    }

    @Test
    fun extractionRejectsUnsupportedDocumentLevelStructures() {
        val cases = listOf(
            Triple(
                "Names",
                COSDictionary().apply {
                    setItem(COSName.getPDFName("JavaScript"), COSDictionary())
                },
                "名称树",
            ),
            Triple("Dests", COSDictionary(), "命名目标"),
            Triple("StructTreeRoot", COSDictionary(), "标签结构"),
            Triple("OCProperties", COSDictionary(), "可选图层"),
            Triple("OpenAction", COSDictionary(), "打开动作"),
            Triple("AA", COSDictionary(), "附加动作"),
            Triple("Threads", COSArray(), "文章线程"),
        )

        cases.forEach { (keyName, value, expectedLabel) ->
            val document = documentWithPages(1)
            document.documentCatalog.cosObject.setItem(COSName.getPDFName(keyName), value)
            try {
                PageTreeEditor.extract(document, setOf(0)).close()
                fail("包含 $expectedLabel 的文档不应被静默导出")
            } catch (expected: PdfPageTransferException) {
                assertTrue(expected.message.orEmpty().contains(expectedLabel))
            } finally {
                document.close()
            }
        }
    }

    @Test
    fun extractSelectedPagesKeepsMetadataAndRemapsResolvableOutlineTargets() {
        val document = documentWithPages(3)
        document.documentInformation.title = "产品手册"
        PdfOutlineEditor.add(document, "第一章", pageIndex = 0, parentId = null)
        PdfOutlineEditor.add(document, "移除章节", pageIndex = 1, parentId = null)
        PdfOutlineEditor.add(document, "第三章", pageIndex = 2, parentId = null)

        PageTreeEditor.extract(document, setOf(2, 0)).use { extracted ->
            assertEquals("产品手册", extracted.documentInformation.title)
            assertEquals(
                listOf("第一章" to 0, "第三章" to 1),
                PdfOutlineEditor.list(extracted).map { it.title to it.pageIndex },
            )

            val saved = ByteArrayOutputStream().also(extracted::save).toByteArray()
            PDDocument.load(saved).use { reopened ->
                assertEquals("产品手册", reopened.documentInformation.title)
                assertEquals(
                    listOf("第一章" to 0, "第三章" to 1),
                    PdfOutlineEditor.list(reopened).map { it.title to it.pageIndex },
                )
            }
        }

        document.close()
    }

    @Test
    fun extractRejectsEmptyAndOutOfRangeSelections() {
        val document = documentWithPages(2)

        listOf(
            emptySet(),
            setOf(-1),
            setOf(0, 2),
        ).forEach { indices ->
            try {
                PageTreeEditor.extract(document, indices).close()
                throw AssertionError("expected extract to reject invalid pages")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("选择") || expected.message!!.contains("索引越界"))
            }
        }
        document.close()
    }

    private fun documentWithPages(count: Int, prefix: String = "page"): PDDocument = PDDocument().also { document ->
        repeat(count) { index ->
            val page = PDPage(PDRectangle(100f + index, 200f + index))
            page.setContents(PDStream(document).also { stream ->
                stream.createOutputStream().use { it.write("$prefix-$index".toByteArray()) }
            })
            document.addPage(page)
        }
    }

    private fun markers(document: PDDocument): List<String> = (0 until document.numberOfPages).map { index ->
        document.getPage(index).contents?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    private fun assertInternalLinkTarget(document: PDDocument, expectedPageIndex: Int) {
        val link = document.getPage(0).annotations.single() as PDAnnotationLink
        val action = link.action as PDActionGoTo
        val destination = action.destination as PDPageXYZDestination
        assertEquals(expectedPageIndex, document.pages.indexOf(destination.page))
        assertEquals(17, destination.left)
        assertEquals(33, destination.top)
        assertEquals(1.25f, destination.zoom, 0.001f)
    }
}
