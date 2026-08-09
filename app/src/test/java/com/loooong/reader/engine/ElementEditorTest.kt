package com.loooong.reader.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

// These stay path-only / font-free so they run on a plain JVM. The text
// re-encode success path needs the font AFMs that pdfbox-android ships as
// Android assets, so it is exercised on-device, not here.
class ElementEditorTest {

    @Test
    fun lockingAndUnlockingElementSurvivesDocumentReload() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }

        assertTrue(ElementEditor.setLocked(doc, page, path, locked = true) is EditResult.Applied)
        val saved = ByteArrayOutputStream().also(doc::save).toByteArray()
        doc.close()

        PDDocument.load(saved).use { reopened ->
            val reopenedPage = reopened.getPage(0)
            var reopenedPath = ContentStreamEngine.parse(reopenedPage).leaves.single { it.kind == NodeKind.PATH }
            assertTrue(reopenedPath.locked)

            assertTrue(
                ElementEditor.setLocked(reopened, reopenedPage, reopenedPath, locked = false) is EditResult.Applied,
            )
            reopenedPath = ContentStreamEngine.parse(reopenedPage).leaves.single { it.kind == NodeKind.PATH }
            assertTrue(!reopenedPath.locked)
        }
    }

    @Test
    fun lockedElementRejectsEditingAndDeletion() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        var path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        ElementEditor.setLocked(doc, page, path, locked = true)
        path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }

        assertTrue(!ElementEditor.capabilities(path.stream!!.tokens, path).canGeom)
        assertTrue(ElementEditor.editElement(doc, page, path, EditRequest(dx = 5f)) is EditResult.NoChange)
        assertTrue(ElementEditor.deleteNodes(doc, page, listOf(path)) is EditResult.NoChange)
        var singleDeleteRejected = false
        try {
            ElementEditor.deleteNode(doc, page, path)
        } catch (_: IllegalArgumentException) {
            singleDeleteRejected = true
        }
        assertTrue(singleDeleteRejected)

        val lockedStream = path.stream!!
        assertTrue(
            "图层重排不能绕过 ReaderLock",
            ElementEditor.reorderRange(
                doc,
                page,
                lockedStream,
                path.startIndex,
                path.endIndex,
                insertAt = 0,
            ) is EditResult.NoChange,
        )
        doc.close()
    }

    @Test
    fun translatingPathShiftsBoundsAndWrapsTokens() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }
        val before = path.bounds!!.minX

        val result = ElementEditor.editElement(
            doc, page, parsed.tokens, path, EditRequest(dx = 10f),
        )
        assertTrue("edit should apply", result is EditResult.Applied)
        val ops = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue("q present", ops.contains("q"))
        assertTrue("cm present", ops.contains("cm"))
        assertTrue("Q present", ops.contains("Q"))

        val moved = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(before + 10f, moved.bounds!!.minX, 0.5f)
        doc.close()
    }

    @Test
    fun pastingMultipleRawNodesUsesOneTranslationWrapper() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val sourceTokens = listOf<Any>(
            COSFloat(10f), COSFloat(20f), COSFloat(30f), COSFloat(40f),
            Operator.getOperator("re"), Operator.getOperator("f"),
        )

        val result = ElementEditor.pasteNodes(
            document = doc,
            page = page,
            tokens = emptyList(),
            copiedNodes = listOf(
                RawPasteNode(sourceTokens, null),
                RawPasteNode(sourceTokens, null),
            ),
            dx = 24f,
            dy = 24f,
        )

        assertTrue(result is EditResult.Applied)
        val operators = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertEquals(listOf("q", "cm", "re", "f", "re", "f", "Q"), operators)
        doc.close()
    }

    @Test
    fun rotatingPathAroundCenterUpdatesBounds() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }

        val result = ElementEditor.editElement(
            doc,
            page,
            parsed.tokens,
            path,
            EditRequest(rotationDegrees = 90f, pivotX = 150f, pivotY = 100f),
        )

        assertTrue(result is EditResult.Applied)
        val rotated = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(100f, rotated.bounds!!.minX, 0.5f)
        assertEquals(0f, rotated.bounds!!.minY, 0.5f)
        assertEquals(200f, rotated.bounds!!.maxX, 0.5f)
        assertEquals(200f, rotated.bounds!!.maxY, 0.5f)
        doc.close()
    }

    @Test
    fun rotatingPageUpdatesPdfRotationAndRestoreReturnsPreviousValue() {
        val doc = PDDocument()
        val page = PDPage()
        page.rotation = 90
        doc.addPage(page)
        val before = ElementEditor.snapshot(page)

        ElementEditor.rotatePage(doc, page, 90)
        assertEquals(180, page.rotation)

        ElementEditor.restore(doc, page, before)
        assertEquals(90, page.rotation)
        doc.close()
    }

    @Test
    fun insertingTextAddsFontResourceAndTextOperators() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)

        val result = ElementEditor.insertText(
            doc,
            page,
            parsed.tokens,
            TextInsertRequest("Hello", 80f, 120f, 18f, PDType1Font.HELVETICA, 0xFF202124.toInt()),
        )

        assertTrue(result is EditResult.Applied)
        val operators = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue(operators.contains("BT"))
        assertTrue(operators.contains("Tj"))
        assertTrue(page.resources.fontNames.any())
        doc.close()
    }

    @Test
    @Ignore("PDFBox Android image factory requires a real Android Bitmap runtime")
    fun insertingImageAddsXObjectAndDoOperator() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )

        val result = ElementEditor.insertImage(
            doc,
            page,
            parsed.tokens,
            ImageInsertRequest(png, 80f, 120f, 48f, 48f),
        )

        assertTrue(result is EditResult.Applied)
        val operators = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue(operators.contains("Do"))
        assertTrue(page.resources.xObjectNames.any())
        doc.close()
    }

    @Test
    fun emptyImageInsertionIsRejectedBeforeBitmapDecode() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.insertImage(
            doc,
            page,
            emptyList(),
            ImageInsertRequest(ByteArray(0), 0f, 0f, 48f, 48f),
        )

        assertTrue(result is EditResult.NoChange)
        doc.close()
    }

    @Test
    fun insertingRectangleCreatesEditableFillAndStrokePath() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)

        val result = ElementEditor.insertShape(
            doc,
            page,
            parsed.tokens,
            ShapeInsertRequest(
                kind = ShapeKind.RECTANGLE,
                x = 80f,
                y = 120f,
                width = 90f,
                height = 50f,
                fillArgb = 0xFFE8F0FE.toInt(),
                strokeArgb = 0xFF2563EB.toInt(),
                lineWidth = 3f,
                lineJoin = 1,
            ),
        )

        assertTrue(result is EditResult.Applied)
        val operators = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue(operators.contains("re"))
        assertTrue(operators.contains("B"))
        val rectangle = ContentStreamEngine.parse(page).leaves.last { it.kind == NodeKind.PATH }
        assertEquals(0xFFE8F0FE.toInt(), rectangle.fillArgb)
        assertEquals(0xFF2563EB.toInt(), rectangle.strokeArgb)
        assertEquals(3f, rectangle.lineWidth, 1e-4f)
        assertEquals(1, rectangle.lineJoin)
        doc.close()
    }

    @Test
    fun insertingLineThenEditingStrokeStylePersistsAfterReparse() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        var parsed = ContentStreamEngine.parse(page)

        assertTrue(
            ElementEditor.insertShape(
                doc,
                page,
                parsed.tokens,
                ShapeInsertRequest(
                    kind = ShapeKind.LINE,
                    x = 40f,
                    y = 60f,
                    width = 120f,
                    height = 0f,
                    strokeArgb = 0xFF202124.toInt(),
                    lineWidth = 2f,
                    lineCap = 1,
                ),
            ) is EditResult.Applied,
        )
        parsed = ContentStreamEngine.parse(page)
        val line = parsed.leaves.last { it.kind == NodeKind.PATH }

        assertTrue(
            ElementEditor.editElement(
                doc,
                page,
                line,
                EditRequest(lineWidth = 6f, lineCap = 2, lineJoin = 2, miterLimit = 4f),
            ) is EditResult.Applied,
        )

        val edited = ContentStreamEngine.parse(page).leaves.last { it.kind == NodeKind.PATH }
        assertEquals(6f, edited.lineWidth, 1e-4f)
        assertEquals(2, edited.lineCap)
        assertEquals(2, edited.lineJoin)
        assertEquals(4f, edited.miterLimit, 1e-4f)
        assertEquals(37f, edited.bounds!!.minX, 0.01f)
        assertEquals(57f, edited.bounds!!.minY, 0.01f)
        assertEquals(163f, edited.bounds!!.maxX, 0.01f)
        assertEquals(63f, edited.bounds!!.maxY, 0.01f)
        doc.close()
    }

    @Test
    fun pastingPathDuplicatesItWithVisibleOffset() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }
        val copied = path.stream!!.tokens.subList(path.startIndex, path.endIndex + 1).toList()

        val result = ElementEditor.pasteNode(
            doc,
            page,
            parsed.tokens,
            copied,
            path.stream.resources,
            24f,
            24f,
        )

        assertTrue(result is EditResult.Applied)
        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertEquals(2, paths.size)
        assertEquals(50f, paths[0].bounds!!.minX, 0.5f)
        assertEquals(74f, paths[1].bounds!!.minX, 0.5f)
        assertEquals(74f, paths[1].bounds!!.minY, 0.5f)
        doc.close()
    }

    @Test
    fun batchDeleteWritesOnePageStream() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val tokens = listOf<Any>(
            COSFloat(0f), COSFloat(0f), COSFloat(10f), COSFloat(10f), Operator.getOperator("re"),
            Operator.getOperator("f"),
            COSFloat(20f), COSFloat(0f), COSFloat(10f), COSFloat(10f), Operator.getOperator("re"),
            Operator.getOperator("f"),
        )
        val stream = ParsedStream(StreamOwner.Page(page), tokens, page.resources)
        val first = DrawNode(1, NodeKind.PATH, "a", "", 0, 5, Bounds(0f, 0f, 10f, 10f), null, "", emptyList(), ctm = Affine.IDENTITY, stream = stream)
        val second = DrawNode(2, NodeKind.PATH, "b", "", 6, 11, Bounds(20f, 0f, 30f, 10f), null, "", emptyList(), ctm = Affine.IDENTITY, stream = stream)

        val result = ElementEditor.deleteNodes(doc, page, listOf(first, second))
        assertTrue(result is EditResult.Applied)
        assertTrue(ContentStreamEngine.parse(page).leaves.none { it.kind == NodeKind.PATH })
        doc.close()
    }

    @Test
    fun batchTransformMovesMultipleObjectsInOneRewrite() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val tokens = listOf<Any>(
            COSFloat(0f), COSFloat(0f), COSFloat(10f), COSFloat(10f), Operator.getOperator("re"),
            Operator.getOperator("f"),
            COSFloat(20f), COSFloat(0f), COSFloat(10f), COSFloat(10f), Operator.getOperator("re"),
            Operator.getOperator("f"),
        )
        val stream = ParsedStream(StreamOwner.Page(page), tokens, page.resources)
        val first = DrawNode(1, NodeKind.PATH, "a", "", 0, 5, Bounds(0f, 0f, 10f, 10f), null, "", emptyList(), ctm = Affine.IDENTITY, stream = stream)
        val second = DrawNode(2, NodeKind.PATH, "b", "", 6, 11, Bounds(20f, 0f, 30f, 10f), null, "", emptyList(), ctm = Affine.IDENTITY, stream = stream)

        val result = ElementEditor.editElements(
            doc,
            page,
            listOf(
                first to EditRequest(dx = 10f),
                second to EditRequest(dx = -10f),
            ),
        )
        assertTrue(result is EditResult.Applied)
        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertEquals(10f, paths[0].bounds!!.minX, 0.5f)
        assertEquals(10f, paths[1].bounds!!.minX, 0.5f)
        doc.close()
    }

    @Test
    fun reorderRangeMovesWholeQqBlock() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val tokens = listOf<Any>(
            Operator.getOperator("q"), COSFloat(1f), Operator.getOperator("Q"),
            Operator.getOperator("q"), COSFloat(2f), Operator.getOperator("Q"),
        )
        val stream = ParsedStream(StreamOwner.Page(page), tokens, page.resources)
        val result = ElementEditor.reorderRange(doc, page, stream, 0, 2, 6)
        assertTrue(result is EditResult.Applied)
        val floats = (result as EditResult.Applied).tokens.filterIsInstance<COSFloat>().map { it.floatValue() }
        assertEquals(listOf(2f, 1f), floats)
        doc.close()
    }

    @Test
    fun recoloringPathSetsNewFill() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }

        val blue = (0xFF shl 24) or 0x0000FF
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(fillArgb = blue))

        val recolored = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(blue, recolored.colorArgb)
        doc.close()
    }

    @Test
    fun colorOnlyEditWrapsWithoutCm() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }

        val green = (0xFF shl 24) or 0x00FF00
        val result = ElementEditor.editElement(
            doc, page, parsed.tokens, path, EditRequest(fillArgb = green),
        )
        assertTrue(result is EditResult.Applied)
        val ops = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue("q present", ops.contains("q"))
        assertTrue("Q present", ops.contains("Q"))
        assertTrue("no cm for color-only", !ops.contains("cm"))
        doc.close()
    }

    @Test
    fun incrementalSaveReflectsEditOnReload() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(dx = 10f))

        val out = ByteArrayOutputStream()
        doc.saveIncremental(out)
        doc.close()

        val reloaded = PDDocument.load(out.toByteArray())
        val moved = ContentStreamEngine.parse(reloaded.getPage(0))
            .leaves.first { it.kind == NodeKind.PATH }
        assertEquals(60f, moved.bounds!!.minX, 0.5f)
        reloaded.close()
    }

    @Test
    fun twoIncrementalEditsBothSurvive() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        var parsed = ContentStreamEngine.parse(page)
        var path = parsed.leaves.first { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(dx = 10f))
        parsed = ContentStreamEngine.parse(page)
        path = parsed.leaves.first { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(dy = 5f))

        val out = ByteArrayOutputStream()
        doc.saveIncremental(out)
        doc.close()

        val reloaded = PDDocument.load(out.toByteArray())
        val p = ContentStreamEngine.parse(reloaded.getPage(0)).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(60f, p.bounds!!.minX, 0.5f)
        assertEquals(55f, p.bounds!!.minY, 0.5f)
        reloaded.close()
    }

    @Test
    fun blockColorRestatesFillBeforeEachRun() {
        // BT /F1 12 Tf 0 0 0 rg (Hi) Tj ET   (black text)
        val tokens = listOf<Any>(
            Operator.getOperator("BT"),
            COSName.getPDFName("F1"), COSFloat(12f), Operator.getOperator("Tf"),
            COSFloat(0f), COSFloat(0f), COSFloat(0f), Operator.getOperator("rg"),
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)), Operator.getOperator("Tj"),
            Operator.getOperator("ET"),
        )
        val run = DrawNode(
            id = 2, kind = NodeKind.TEXT, label = "run", detail = "",
            startIndex = 8, endIndex = 9, bounds = null, colorArgb = (0xFF shl 24),
            raw = "", children = emptyList(),
        )
        val block = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "block", detail = "",
            startIndex = 0, endIndex = 10, bounds = null, colorArgb = (0xFF shl 24),
            raw = "", children = listOf(run),
        )
        val doc = PDDocument()
        doc.addPage(PDPage())
        val red = (0xFF shl 24) or 0xFF0000
        val result = ElementEditor.editElement(
            doc, doc.getPage(0), tokens, block, EditRequest(fillArgb = red),
        )
        doc.close()

        assertTrue(result is EditResult.Applied)
        val out = (result as EditResult.Applied).tokens
        val si = out.indexOfFirst { it is COSString }            // the (Hi) string
        assertEquals("rg", (out[si - 1] as Operator).name)        // restated fill
        assertEquals(1f, (out[si - 4] as COSFloat).floatValue(), 1e-4f)  // red
        assertEquals(0f, (out[si - 3] as COSFloat).floatValue(), 1e-4f)
        assertEquals(0f, (out[si - 2] as COSFloat).floatValue(), 1e-4f)
        assertEquals("q", (out.first { it is Operator } as Operator).name)
        assertEquals("Q", (out.last { it is Operator } as Operator).name)
    }

    @Test
    fun textEditWithoutFontIsRejected() {
        val tokens = listOf<Any>(
            COSString("hi".toByteArray(Charsets.ISO_8859_1)),
            Operator.getOperator("Tj"),
        )
        val node = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "hi", font = null,
        )
        val doc = PDDocument()
        val result = ElementEditor.editElement(doc, PDPage(), tokens, node, EditRequest(newText = "x"))
        assertTrue("missing font should be rejected", result is EditResult.TextEncodeFailed)
        doc.close()
    }

    @Test
    fun textEditSubstitutesFontAndRestoresOriginal() {
        val tokens = listOf<Any>(
            COSName.getPDFName("F1"), COSFloat(12f), Operator.getOperator("Tf"),
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)), Operator.getOperator("Tj"),
        )
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 3, endIndex = 4, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "Hi",
            fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(newText = "Hello"), PDType1Font.HELVETICA,
        )
        assertTrue("substitution should apply", result is EditResult.Applied)
        val out = (result as EditResult.Applied).tokens
        val tfAt = out.indices.filter { (out[it] as? Operator)?.name == "Tf" }
        assertEquals("original Tf plus switch and restore", 3, tfAt.size)
        assertEquals("original font restored after run", "F1", (out[tfAt[2] - 2] as COSName).name)
        val added = out[tfAt[1] - 2] as COSName
        assertTrue("substitute font added to resources", page.resources.getFont(added) != null)
        assertTrue("run still shows text", out.any { (it as? Operator)?.name == "Tj" })
        doc.close()
    }

    @Test
    fun textLayoutEditWritesAndRestoresFontSpacingAndLeading() {
        val tokens = listOf<Any>(
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)), Operator.getOperator("Tj"),
        )
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "Hi",
            fontResourceName = "F1", fontSize = 12f, characterSpacing = 1f, lineLeading = 14f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc,
            page,
            tokens,
            run,
            EditRequest(fontSize = 18f, characterSpacing = 2f, lineLeading = 22f),
        ) as EditResult.Applied

        val operators = result.tokens.filterIsInstance<Operator>().map { it.name }
        assertEquals(2, operators.count { it == "Tf" })
        assertEquals(2, operators.count { it == "Tc" })
        assertEquals(2, operators.count { it == "TL" })
        val tfIndexes = result.tokens.indices.filter { (result.tokens[it] as? Operator)?.name == "Tf" }
        assertEquals(18f, (result.tokens[tfIndexes[0] - 1] as COSFloat).floatValue(), 0f)
        assertEquals(12f, (result.tokens[tfIndexes[1] - 1] as COSFloat).floatValue(), 0f)
        doc.close()
    }

    @Test
    fun multilineTextEditCreatesRealLineAdvances() {
        val tokens = listOf<Any>(
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)), Operator.getOperator("Tj"),
        )
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "Hi",
            font = PDType1Font.HELVETICA, fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc,
            page,
            tokens,
            run,
            EditRequest(newText = "A\nB", lineLeading = 16f),
        ) as EditResult.Applied

        val operators = result.tokens.filterIsInstance<Operator>().map { it.name }
        assertEquals(2, operators.count { it == "Tj" })
        assertEquals(1, operators.count { it == "T*" })
        assertEquals(listOf("A", "B"), result.tokens.filterIsInstance<COSString>().map {
            String(it.bytes, Charsets.ISO_8859_1)
        })
        doc.close()
    }

    @Test
    fun doubleQuoteTextEditRewritesItsOwnCharacterSpacingOperand() {
        val tokens = listOf<Any>(
            COSFloat(3f), COSFloat(1f), COSString("Hi".toByteArray(Charsets.ISO_8859_1)),
            Operator.getOperator("\""),
        )
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 3, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "Hi",
            characterSpacing = 1f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(characterSpacing = 4f),
        ) as EditResult.Applied

        val quoteIndex = result.tokens.indexOfFirst { (it as? Operator)?.name == "\"" }
        assertEquals(4f, (result.tokens[quoteIndex - 2] as COSFloat).floatValue(), 0f)
        doc.close()
    }

    @Test
    fun opacityEditCreatesExtGStateAndSurvivesReparse() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val path = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }

        val result = ElementEditor.editElement(doc, page, path, EditRequest(opacity = 0.35f))

        assertTrue(result is EditResult.Applied)
        assertTrue(page.resources.extGStateNames.any())
        val reparsed = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(0.35f, reparsed.nonStrokingAlpha, 1e-4f)
        assertEquals(0.35f, reparsed.strokingAlpha, 1e-4f)
        doc.close()
    }

    @Test
    fun textEditPreservesTjArraySpacing() {
        val textArray = com.tom_roush.pdfbox.cos.COSArray().apply {
            add(COSString("A".toByteArray(Charsets.ISO_8859_1)))
            add(COSFloat(-120f))
            add(COSString("B".toByteArray(Charsets.ISO_8859_1)))
        }
        val tokens = listOf<Any>(textArray, Operator.getOperator("TJ"))
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "AB",
            font = PDType1Font.HELVETICA, fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(newText = "CD"),
        )

        assertTrue(result is EditResult.Applied)
        val out = (result as EditResult.Applied).tokens
        val rebuilt = out[0] as com.tom_roush.pdfbox.cos.COSArray
        assertEquals("TJ", (out[1] as Operator).name)
        assertEquals("C", String((rebuilt.getObject(0) as COSString).bytes, Charsets.ISO_8859_1))
        assertEquals(-120f, (rebuilt.getObject(1) as COSFloat).floatValue(), 0f)
        assertEquals("D", String((rebuilt.getObject(2) as COSString).bytes, Charsets.ISO_8859_1))
        doc.close()
    }

    @Test
    fun emptyTextRemovesTjSpacingAdjustments() {
        val textArray = com.tom_roush.pdfbox.cos.COSArray().apply {
            add(COSString("A".toByteArray(Charsets.ISO_8859_1)))
            add(COSFloat(-120f))
            add(COSString("B".toByteArray(Charsets.ISO_8859_1)))
        }
        val tokens = listOf<Any>(textArray, Operator.getOperator("TJ"))
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "AB",
            font = PDType1Font.HELVETICA, fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(newText = ""),
        ) as EditResult.Applied

        val rebuilt = result.tokens[0] as com.tom_roush.pdfbox.cos.COSArray
        assertEquals(1, rebuilt.size())
        assertEquals(0, (rebuilt.getObject(0) as COSString).bytes.size)
        doc.close()
    }

    @Test
    fun undoFontSubstitutionRestoresPageResources() {
        val tokens = listOf<Any>(
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)),
            Operator.getOperator("Tj"),
        )
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "Hi",
            fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val before = ElementEditor.snapshot(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(newText = "Hello"), PDType1Font.HELVETICA,
        )
        assertTrue(result is EditResult.Applied)
        assertTrue(page.cosObject.containsKey(COSName.RESOURCES))

        ElementEditor.restore(doc, page, before)

        assertTrue(!page.cosObject.containsKey(COSName.RESOURCES))
        doc.close()
    }

    @Test
    fun permanentRedactionRemovesSelectedTextAndLeavesAnOpaqueCoverAfterReload() {
        val doc = PDDocument.load(redactionPdf())
        val page = doc.getPage(0)
        val secret = ContentStreamEngine.parse(page).leaves.single { node ->
            node.kind == NodeKind.TEXT && node.text?.contains("SECRET-123456") == true
        }

        val result = ElementEditor.redactNodes(doc, page, listOf(secret), fillArgb = 0xFF000000.toInt())
        assertTrue(result is EditResult.Applied)

        val saved = ByteArrayOutputStream().also(doc::save).toByteArray()
        doc.close()
        val rawPdf = saved.toString(Charsets.ISO_8859_1)
        assertFalse("最终 PDF 中不能残留被遮盖的明文", rawPdf.contains("SECRET-123456"))

        PDDocument.load(saved).use { reopened ->
            val parsed = ContentStreamEngine.parse(reopened.getPage(0))
            assertFalse(parsed.leaves.any { it.text?.contains("SECRET-123456") == true })
            assertTrue(parsed.leaves.any { it.text?.contains("KEEP-654321") == true })
            assertTrue(
                "遮盖区域应作为不透明黑色路径保留",
                parsed.leaves.any { node ->
                    node.kind == NodeKind.PATH && node.colorArgb == 0xFF000000.toInt() &&
                        node.bounds?.let { bounds ->
                            bounds.minX <= secret.bounds!!.minX && bounds.maxX >= secret.bounds!!.maxX &&
                                bounds.minY <= secret.bounds!!.minY && bounds.maxY >= secret.bounds!!.maxY
                        } == true
                },
            )
        }
    }

    @Test
    fun permanentRedactionRemovesExternalImagePayloadAndResourceAfterReload() {
        val secretPayload = "SECRET-IMAGE-PAYLOAD-8675309"
        val doc = PDDocument.load(redactionImagePdf(secretPayload))
        val page = doc.getPage(0)
        val image = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.IMAGE }

        val result = ElementEditor.redactNodes(doc, page, listOf(image), fillArgb = 0xFF000000.toInt())
        assertTrue(result is EditResult.Applied)

        val saved = ByteArrayOutputStream().also(doc::save).toByteArray()
        doc.close()
        assertFalse(
            "最终 PDF 中不能保留已无引用的外部图片原始数据",
            saved.toString(Charsets.ISO_8859_1).contains(secretPayload),
        )

        PDDocument.load(saved).use { reopened ->
            val reopenedPage = reopened.getPage(0)
            assertFalse(reopenedPage.resources.xObjectNames.any { it.name == "ImSecret" })
            val parsed = ContentStreamEngine.parse(reopenedPage)
            assertFalse(parsed.leaves.any { it.kind == NodeKind.IMAGE })
            assertTrue(
                "图片删除后必须留下不透明黑色遮盖区域",
                parsed.leaves.any { node ->
                    node.kind == NodeKind.PATH && node.colorArgb == 0xFF000000.toInt() &&
                        node.nonStrokingAlpha == 1f && node.blendMode == "Normal"
                },
            )
        }
    }

    @Test
    fun permanentRedactionKeepsImageResourceStillUsedByAnotherVisibleInstance() {
        val secretPayload = "SHARED-IMAGE-PAYLOAD-24680"
        val doc = PDDocument.load(reusedRedactionImagePdf(secretPayload))
        val page = doc.getPage(0)
        val images = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.IMAGE }
        val first = images.minBy { it.bounds!!.minX }

        assertTrue(ElementEditor.redactNodes(doc, page, listOf(first)) is EditResult.Applied)
        val saved = ByteArrayOutputStream().also(doc::save).toByteArray()
        doc.close()

        assertTrue(saved.toString(Charsets.ISO_8859_1).contains(secretPayload))
        PDDocument.load(saved).use { reopened ->
            val reopenedPage = reopened.getPage(0)
            assertTrue(reopenedPage.resources.xObjectNames.any { it.name == "ImSecret" })
            assertEquals(1, ContentStreamEngine.parse(reopenedPage).leaves.count { it.kind == NodeKind.IMAGE })
        }
    }

    @Test
    fun permanentRedactionRemovesMarkedContentReplacementTextAfterReload() {
        val doc = PDDocument.load(markedContentRedactionPdf())
        val page = doc.getPage(0)
        val secret = ContentStreamEngine.parse(page).leaves.single { node ->
            node.kind == NodeKind.TEXT && node.text?.contains("VISIBLE-SECRET-2026") == true
        }

        assertTrue(ElementEditor.redactNodes(doc, page, listOf(secret)) is EditResult.Applied)
        val saved = ByteArrayOutputStream().also(doc::save).toByteArray()
        doc.close()

        val rawPdf = saved.toString(Charsets.ISO_8859_1)
        assertFalse(rawPdf.contains("VISIBLE-SECRET-2026"))
        assertFalse(rawPdf.contains("SECRET-ACTUALTEXT-2026"))
        assertFalse(rawPdf.contains("SECRET-ALT-2026"))
        PDDocument.load(saved).use { reopened ->
            val reopenedPage = reopened.getPage(0)
            assertFalse(ContentStreamEngine.parse(reopenedPage).leaves.any { it.kind == NodeKind.TEXT })
            val parser = PDFStreamParser(reopenedPage)
            parser.parse()
            val replacementTexts = parser.tokens.filterIsInstance<COSDictionary>().flatMap { dictionary ->
                listOf(COSName.ACTUAL_TEXT, COSName.ALT).mapNotNull { key ->
                    (dictionary.getDictionaryObject(key) as? COSString)?.string
                }
            }
            assertFalse(replacementTexts.any { it.contains("SECRET-") })
        }
    }

    @Test
    fun permanentRedactionFailsClosedWhenTaggedStructureTextCannotBeSanitized() {
        val doc = PDDocument.load(taggedRedactionPdf())
        val page = doc.getPage(0)
        val secret = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.TEXT }

        assertFalse(ElementEditor.canRedactNodes(doc, page, listOf(secret)))
        assertTrue(ElementEditor.redactNodes(doc, page, listOf(secret)) is EditResult.NoChange)
        doc.close()
    }

    private fun pathPdf(): ByteArray {
        val content = "1 0 0 rg\n50 50 200 100 re f\n"
        val length = content.toByteArray(Charsets.ISO_8859_1).size
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << >> /Contents 4 0 R >>",
            "<< /Length $length >>\nstream\n${content}endstream",
        )
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        val offsets = IntArray(objects.size + 1)
        for ((i, obj) in objects.withIndex()) {
            offsets[i + 1] = sb.toString().toByteArray(Charsets.ISO_8859_1).size
            sb.append("${i + 1} 0 obj\n").append(obj).append("\nendobj\n")
        }
        val xrefOffset = sb.toString().toByteArray(Charsets.ISO_8859_1).size
        sb.append("xref\n0 ${objects.size + 1}\n")
        sb.append("0000000000 65535 f \n")
        for (i in 1..objects.size) sb.append(String.format("%010d 00000 n \n", offsets[i]))
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
        sb.append("startxref\n$xrefOffset\n%%EOF")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun redactionPdf(): ByteArray = pdfWithContent(
        "BT /F1 20 Tf 72 700 Td (SECRET-123456) Tj ET\n" +
            "BT /F1 20 Tf 72 650 Td (KEEP-654321) Tj ET\n",
    )

    private fun markedContentRedactionPdf(): ByteArray = pdfWithContent(
        "/Span << /ActualText (SECRET-ACTUALTEXT-2026) /Alt (SECRET-ALT-2026) >> BDC\n" +
            "BT /F1 20 Tf 72 700 Td (VISIBLE-SECRET-2026) Tj ET\n" +
            "EMC\n",
    )

    private fun taggedRedactionPdf(): ByteArray {
        val content = "/P << /MCID 0 >> BDC BT /F1 20 Tf 72 700 Td (VISIBLE-TAGGED-SECRET) Tj ET EMC\n"
        return pdfFromObjects(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 6 0 R /MarkInfo << /Marked true >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /StructParents 0 /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                "<< /Length ${content.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n${content}endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Type /StructTreeRoot /K 7 0 R >>",
                "<< /Type /StructElem /S /P /P 6 0 R /Pg 3 0 R /K 0 /Alt (SECRET-STRUCTURE-TEXT) >>",
            ),
        )
    }

    private fun redactionImagePdf(secretPayload: String): ByteArray {
        val content = "q 120 0 0 80 72 620 cm /ImSecret Do Q\n"
        return imageRedactionPdf(secretPayload, content)
    }

    private fun reusedRedactionImagePdf(secretPayload: String): ByteArray {
        val content = "q 120 0 0 80 72 620 cm /ImSecret Do Q\n" +
            "q 120 0 0 80 300 620 cm /ImSecret Do Q\n"
        return imageRedactionPdf(secretPayload, content)
    }

    private fun imageRedactionPdf(secretPayload: String, content: String): ByteArray {
        return pdfFromObjects(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /XObject << /ImSecret 5 0 R >> >> /Contents 4 0 R >>",
                "<< /Length ${content.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n${content}endstream",
                "<< /Type /XObject /Subtype /Image /Width 1 /Height 1 " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                    "/Length ${secretPayload.toByteArray(Charsets.ISO_8859_1).size} >>\n" +
                    "stream\n$secretPayload\nendstream",
            ),
        )
    }

    private fun pdfWithContent(content: String): ByteArray {
        val length = content.toByteArray(Charsets.ISO_8859_1).size
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length $length >>\nstream\n${content}endstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        return pdfFromObjects(objects)
    }

    private fun pdfFromObjects(objects: List<String>): ByteArray {
        val sb = StringBuilder().append("%PDF-1.4\n")
        val offsets = IntArray(objects.size + 1)
        objects.forEachIndexed { index, objectBody ->
            offsets[index + 1] = sb.toString().toByteArray(Charsets.ISO_8859_1).size
            sb.append("${index + 1} 0 obj\n").append(objectBody).append("\nendobj\n")
        }
        val xrefOffset = sb.toString().toByteArray(Charsets.ISO_8859_1).size
        sb.append("xref\n0 ${objects.size + 1}\n")
        sb.append("0000000000 65535 f \n")
        for (index in 1..objects.size) sb.append(String.format("%010d 00000 n \n", offsets[index]))
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
        sb.append("startxref\n$xrefOffset\n%%EOF")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
