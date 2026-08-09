package com.loooong.reader.engine

import com.tom_roush.harmony.awt.geom.AffineTransform
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ContentStreamEngineTest {
    @Test
    fun readerLockMarksContainedElementAsLocked() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(doc, page, "/ReaderLock BMC 10 20 30 40 re f EMC\n")

        val path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }

        assertTrue(path.locked)
        assertEquals(0, path.locks.single().wrapperStart)
        assertEquals(1, path.locks.single().beginOperatorIndex)
        assertEquals(8, path.locks.single().wrapperEnd)
        doc.close()
    }

    @Test
    fun nestedReaderLocksPreserveEveryActiveLayer() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "/ReaderLock BMC 0 0 10 10 re f /ReaderLock BMC 20 0 10 10 re f EMC EMC\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }

        assertEquals(1, paths[0].locks.size)
        assertEquals(2, paths[1].locks.size)
        doc.close()
    }


    @Test
    fun parsesTextImageAndPath() {
        val doc = PDDocument.load(samplePdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)

        val kinds = parsed.leaves.map { it.kind }
        assertTrue("expected a text node", kinds.contains(NodeKind.TEXT))
        assertTrue("expected a path node", kinds.contains(NodeKind.PATH))

        val text = parsed.leaves.first { it.kind == NodeKind.TEXT }
        assertTrue("text label should carry preview", text.label.contains("Hello World"))
        assertTrue("token range should be ordered", text.startIndex <= text.endIndex)
        assertTrue("text should have bounds", text.bounds != null)

        doc.close()
    }

    @Test
    fun deletingTextRewritesTheStream() {
        val doc = PDDocument.load(samplePdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val text = parsed.leaves.first { it.kind == NodeKind.TEXT }

        val kept = ElementEditor.deleteRange(
            doc, page, parsed.tokens, text.startIndex, text.endIndex,
        )
        assertTrue("tokens should shrink", kept.size < parsed.tokens.size)

        val reparsed = ContentStreamEngine.parse(page)
        val stillThere = reparsed.leaves.any {
            it.kind == NodeKind.TEXT && it.label.contains("Hello World")
        }
        assertTrue("deleted text should be gone after re-parse", !stillThere)
        assertTrue("the path should survive", reparsed.leaves.any { it.kind == NodeKind.PATH })

        doc.close()
    }

    @Test
    fun textRunCarriesFontResourceAndSize() {
        val doc = PDDocument.load(samplePdf())
        val parsed = ContentStreamEngine.parse(doc.getPage(0))
        val textObj = parsed.leaves.first { it.kind == NodeKind.TEXT }
        val run = textObj.children.first { it.kind == NodeKind.TEXT }
        assertEquals("F1", run.fontResourceName)
        assertEquals(24f, run.fontSize, 1e-4f)
        doc.close()
    }

    @Test
    fun textStateOperatorsAffectSpacingScaleAndRise() {
        val content = buildString {
            append("BT /F1 10 Tf 10 20 Td 0 Tc 0 Tw 100 Tz 0 Ts (A A) Tj ET\n")
            append("BT /F1 10 Tf 10 50 Td 2 Tc 4 Tw 100 Tz 0 Ts (A A) Tj ET\n")
            append("BT /F1 10 Tf 10 80 Td 0 Tc 0 Tw 50 Tz 0 Ts (A A) Tj ET\n")
            append("BT /F1 10 Tf 10 110 Td 0 Tc 0 Tw 100 Tz 5 Ts (A A) Tj ET\n")
        }
        val doc = PDDocument.load(pdfWithContent(content))

        val texts = ContentStreamEngine.parse(doc.getPage(0)).leaves
            .filter { it.kind == NodeKind.TEXT }
        val baseline = texts[0].bounds!!
        val spaced = texts[1].bounds!!
        val scaled = texts[2].bounds!!
        val raised = texts[3].bounds!!

        assertEquals(4, texts.size)
        assertEquals(baseline.width + 10f, spaced.width, 0.01f)
        assertEquals(baseline.width * 0.5f, scaled.width, 0.01f)
        assertEquals(baseline.width, raised.width, 0.01f)
        assertEquals(95f, raised.minY - baseline.minY, 0.01f)
        doc.close()
    }

    @Test
    fun doubleQuoteSetsSpacingAndMovesToNextLine() {
        val doc = PDDocument.load(
            pdfWithContent("BT /F1 10 Tf 10 100 Td 12 TL (A A) Tj 4 2 (A A) \" ET\n"),
        )

        val text = ContentStreamEngine.parse(doc.getPage(0)).leaves
            .single { it.kind == NodeKind.TEXT }
        val first = text.children[0].bounds!!
        val second = text.children[1].bounds!!

        assertEquals(2, text.children.size)
        assertEquals(first.width + 10f, second.width, 0.01f)
        assertEquals(-12f, second.minY - first.minY, 0.01f)
        doc.close()
    }

    @Test
    fun textRenderingModeUsesStrokeColorAndHidesInvisibleText() {
        val content = "BT /F1 10 Tf 1 0 0 rg 0 0 1 RG 10 20 Td " +
            "1 Tr (Stroke) Tj 20 0 Td 3 Tr (Hidden) Tj ET\n"
        val doc = PDDocument.load(pdfWithContent(content))

        val text = ContentStreamEngine.parse(doc.getPage(0)).leaves
            .single { it.kind == NodeKind.TEXT }
        val strokeRun = text.children[0]
        val hiddenRun = text.children[1]

        assertEquals(1, strokeRun.textRenderingMode)
        assertEquals(0xFF0000FF.toInt(), strokeRun.colorArgb)
        assertFalse(ElementEditor.capabilities(strokeRun.stream!!.tokens, strokeRun).canFill)
        assertEquals(3, hiddenRun.textRenderingMode)
        assertEquals(null, hiddenRun.colorArgb)
        doc.close()
    }

    @Test
    fun nestedGraphicsStateRestoresFillColorAfterQ() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "0 0 1 rg q 1 0 0 rg q 0 1 0 rg 0 0 10 10 re f Q " +
                "20 0 10 10 re f Q 40 0 10 10 re f\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }

        assertEquals(3, paths.size)
        assertEquals(0xFF00FF00.toInt(), paths[0].colorArgb)
        assertEquals(0xFFFF0000.toInt(), paths[1].colorArgb)
        assertEquals(0xFF0000FF.toInt(), paths[2].colorArgb)
        doc.close()
    }

    @Test
    fun graphicsStateRestoresStrokeColorAfterQ() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "0 0 0 RG q 1 0 1 RG 0 0 m 10 10 l S Q 20 0 m 30 10 l S\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }

        assertEquals(2, paths.size)
        assertEquals(0xFFFF00FF.toInt(), paths[0].colorArgb)
        assertEquals(0xFF000000.toInt(), paths[1].colorArgb)
        doc.close()
    }

    @Test
    fun extendedGraphicsStateTracksAlphaBlendAndRestoresAfterQ() {
        val doc = PDDocument()
        val page = addPage(doc)
        val state = PDExtendedGraphicsState().apply {
            strokingAlphaConstant = 0.7f
            nonStrokingAlphaConstant = 0.4f
            blendMode = BlendMode.MULTIPLY
        }
        page.resources.put(COSName.getPDFName("GS1"), state)
        setPageContent(
            doc,
            page,
            "q /GS1 gs 0 0 10 10 re B Q 20 0 10 10 re f\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }

        assertEquals(2, paths.size)
        assertEquals(0.7f, paths[0].strokingAlpha, 1e-4f)
        assertEquals(0.4f, paths[0].nonStrokingAlpha, 1e-4f)
        assertEquals("Multiply", paths[0].blendMode)
        assertEquals(1f, paths[1].strokingAlpha, 1e-4f)
        assertEquals(1f, paths[1].nonStrokingAlpha, 1e-4f)
        assertEquals("Normal", paths[1].blendMode)
        doc.close()
    }

    @Test
    fun clippingPathsTrackRuleIntersectionAndGraphicsStateRestore() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "0 0 100 100 re W n 10 10 20 20 re f " +
                "q 20 20 30 30 re W* n 25 25 10 10 re f Q " +
                "60 60 10 10 re f\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        val clips = paths.filter { it.clipRule != null }

        assertEquals(5, paths.size)
        assertEquals(ClipRule.NON_ZERO, clips[0].clipRule)
        assertBounds(clips[0].clipBounds, 0f, 0f, 100f, 100f)
        assertEquals(ClipRule.EVEN_ODD, clips[1].clipRule)
        assertBounds(clips[1].clipBounds, 20f, 20f, 50f, 50f)
        assertBounds(paths[3].clipBounds, 20f, 20f, 50f, 50f)
        assertBounds(paths[4].clipBounds, 0f, 0f, 100f, 100f)

        val caps = ElementEditor.capabilities(clips[0].stream!!.tokens, clips[0])
        assertTrue(caps.canGeom)
        assertFalse(caps.canFill)
        assertFalse(caps.canStroke)
        doc.close()
    }

    @Test
    fun movingClipPathKeepsClipActiveForFollowingContent() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(doc, page, "0 0 100 100 re W n 0 0 200 200 re f\n")
        val clip = ContentStreamEngine.parse(page).leaves.single { it.clipRule != null }

        assertTrue(ElementEditor.editElement(doc, page, clip, EditRequest(dx = 10f)) is EditResult.Applied)

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertBounds(paths[0].bounds, 10f, 0f, 110f, 100f)
        assertBounds(paths[0].clipBounds, 10f, 0f, 110f, 100f)
        assertBounds(paths[1].clipBounds, 10f, 0f, 110f, 100f)
        doc.close()
    }

    @Test
    fun cubicBoundsUseRealInteriorExtrema() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(doc, page, "0 0 m 100 200 200 -200 300 0 c S\n")

        val bounds = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }.bounds

        assertBounds(bounds, -0.5f, -58.235f, 300.5f, 58.235f, 0.01f)
        doc.close()
    }

    @Test
    fun curveShortcutsUseTheCurrentPointAsImplicitControl() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "10 20 m 30 40 50 60 v S 10 20 m 30 40 50 60 y S\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }

        assertEquals(2, paths.size)
        assertBounds(paths[0].bounds, 9.5f, 19.5f, 50.5f, 60.5f)
        assertBounds(paths[1].bounds, 9.5f, 19.5f, 50.5f, 60.5f)
        doc.close()
    }

    @Test
    fun pathPaintingTracksIndependentFillStrokeAndLineStyle() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "1 0 0 rg 0 0 1 RG 10 w 1 J 2 j 5 M [3 2] 1 d 20 20 40 30 re B\n",
        )

        val path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }

        assertEquals(0xFFFF0000.toInt(), path.fillArgb)
        assertEquals(0xFF0000FF.toInt(), path.strokeArgb)
        assertEquals(10f, path.lineWidth, 1e-4f)
        assertEquals(1, path.lineCap)
        assertEquals(2, path.lineJoin)
        assertEquals(5f, path.miterLimit, 1e-4f)
        assertEquals(listOf(3f, 2f), path.dashPattern)
        assertEquals(1f, path.dashPhase, 1e-4f)
        assertTrue(path.pathClosed)
        assertBounds(path.bounds, 15f, 15f, 65f, 55f)
        doc.close()
    }

    @Test
    fun implicitCloseOperatorsClosePathsAndNoPaintDropsGeometry() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "0 0 m 20 0 l 20 20 l s 40 0 m 60 0 l 60 20 l b* 80 0 10 10 re n\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }

        assertEquals(2, paths.size)
        assertTrue(paths[0].pathClosed)
        assertTrue(paths[1].pathClosed)
        assertTrue(paths[0].label.contains("描边"))
        assertTrue(paths[1].label.contains("填充与描边"))
        doc.close()
    }

    @Test
    fun graphicsStateRestoresLineStyleAfterQ() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "2 w 0 J 0 j 10 M q 8 w 2 J 1 j 3 M 0 0 m 20 0 l S Q 40 0 m 60 0 l S\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }

        assertEquals(2, paths.size)
        assertEquals(8f, paths[0].lineWidth, 1e-4f)
        assertEquals(2, paths[0].lineCap)
        assertEquals(1, paths[0].lineJoin)
        assertEquals(3f, paths[0].miterLimit, 1e-4f)
        assertBounds(paths[0].bounds, -4f, -4f, 24f, 4f)
        assertEquals(2f, paths[1].lineWidth, 1e-4f)
        assertEquals(0, paths[1].lineCap)
        assertEquals(0, paths[1].lineJoin)
        assertEquals(10f, paths[1].miterLimit, 1e-4f)
        assertBounds(paths[1].bounds, 39f, -1f, 61f, 1f)
        doc.close()
    }

    @Test
    fun parsesMovesAndDeletesInlineImageWithoutLosingBinaryData() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "q 20 0 0 30 5 7 cm BI /W 1 /H 1 /CS /G /BPC 8 ID \u007f EI Q\n",
        )

        var image = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.IMAGE }
        assertTrue(image.inlineImage)
        assertEquals("内嵌图像", image.label)
        assertTrue(image.detail.contains("1×1"))
        assertBounds(image.bounds, 5f, 7f, 25f, 37f)

        assertTrue(ElementEditor.editElement(doc, page, image, EditRequest(dx = 10f)) is EditResult.Applied)
        image = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.IMAGE }
        assertBounds(image.bounds, 15f, 7f, 35f, 37f)

        ElementEditor.deleteNode(doc, page, image)
        assertFalse(ContentStreamEngine.parse(page).leaves.any { it.kind == NodeKind.IMAGE })
        doc.close()
    }

    @Test
    fun imageCropCanBeUpdatedAndResetAfterReparse() {
        val doc = PDDocument()
        val page = addPage(doc)
        setPageContent(
            doc,
            page,
            "q 100 0 0 80 10 20 cm BI /W 1 /H 1 /CS /G /BPC 8 ID \u007f EI Q\n",
        )
        var image = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.IMAGE }

        assertTrue(
            ElementEditor.editElement(
                doc,
                page,
                image,
                EditRequest(imageCrop = ImageCropRequest(0.1f, 0.2f, 0.15f, 0.05f)),
            ) is EditResult.Applied,
        )
        var parsed = ContentStreamEngine.parse(page)
        image = parsed.leaves.single { it.kind == NodeKind.IMAGE }
        assertEquals(0.1f, image.imageCrop!!.left, 1e-4f)
        assertEquals(0.2f, image.imageCrop!!.top, 1e-4f)
        assertEquals(1, flatten(parsed.root).count { it.clipRule != null })
        assertFalse(parsed.leaves.any { it.clipRule != null })

        assertTrue(
            ElementEditor.editElement(
                doc,
                page,
                image,
                EditRequest(imageCrop = ImageCropRequest(0.2f, 0.1f, 0.1f, 0.1f)),
            ) is EditResult.Applied,
        )
        parsed = ContentStreamEngine.parse(page)
        image = parsed.leaves.single { it.kind == NodeKind.IMAGE }
        assertEquals(0.2f, image.imageCrop!!.left, 1e-4f)
        assertEquals(0.1f, image.imageCrop!!.bottom, 1e-4f)
        assertEquals(1, flatten(parsed.root).count { it.clipRule != null })
        assertFalse(parsed.leaves.any { it.clipRule != null })

        assertTrue(
            ElementEditor.editElement(
                doc,
                page,
                image,
                EditRequest(imageCrop = ImageCropRequest(0f, 0f, 0f, 0f)),
            ) is EditResult.Applied,
        )
        parsed = ContentStreamEngine.parse(page)
        image = parsed.leaves.single { it.kind == NodeKind.IMAGE }
        assertEquals(null, image.imageCrop)
        assertFalse(parsed.leaves.any { it.clipRule != null })
        doc.close()
    }

    @Test
    fun parsesFormChildrenWithFormMatrixAndCallerCtm() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "0 0 10 20 re f\n").apply {
            setMatrix(AffineTransform(1f, 0f, 0f, 1f, 3f, 4f))
        }
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(doc, page, "q 2 0 0 3 100 200 cm /Fm Do Q\n")

        val parsed = ContentStreamEngine.parse(page)
        val formNode = parsed.root.children.single().children.single()
        val path = formNode.children.single { it.kind == NodeKind.PATH }

        assertEquals(NodeKind.GROUP, formNode.kind)
        assertTrue(formNode.stream?.owner is StreamOwner.Page)
        assertTrue(path.stream?.owner is StreamOwner.Form)
        assertEquals(106f, path.bounds!!.minX, 1e-4f)
        assertEquals(212f, path.bounds!!.minY, 1e-4f)
        assertEquals(126f, path.bounds!!.maxX, 1e-4f)
        assertEquals(272f, path.bounds!!.maxY, 1e-4f)
        doc.close()
    }

    @Test
    fun formInheritsCallerColorWithoutLeakingItsOwnChanges() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "1 0 0 rg 0 0 10 10 re f\n")
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(doc, page, "0 0 1 rg /Fm Do 20 0 10 10 re f\n")

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }

        assertEquals(2, paths.size)
        assertEquals(0xFFFF0000.toInt(), paths[0].colorArgb)
        assertEquals(0xFF0000FF.toInt(), paths[1].colorArgb)
        doc.close()
    }

    @Test
    fun formUsesCallerColorWhenItDoesNotSetOne() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "0 0 10 10 re f\n")
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(doc, page, "0 1 0 rg /Fm Do\n")

        val path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }

        assertEquals(0xFF00FF00.toInt(), path.colorArgb)
        doc.close()
    }

    @Test
    fun expandsTheSameFormAtEachCallSite() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "0 0 10 10 re f\n")
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(
            doc,
            page,
            "q 1 0 0 1 0 0 cm /Fm Do Q q 1 0 0 1 50 0 cm /Fm Do Q\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertEquals(2, paths.size)
        assertEquals(0f, paths[0].bounds!!.minX, 1e-4f)
        assertEquals(50f, paths[1].bounds!!.minX, 1e-4f)
        assertTrue(paths[0].id != paths[1].id)
        val firstOwner = paths[0].stream!!.owner as StreamOwner.Form
        val secondOwner = paths[1].stream!!.owner as StreamOwner.Form
        assertTrue(firstOwner.form.cosObject === secondOwner.form.cosObject)
        doc.close()
    }

    @Test
    fun stopsRecursiveFormCycleAndKeepsReachablePaths() {
        val doc = PDDocument()
        val page = addPage(doc)
        val formA = addForm(doc, "0 0 10 10 re f /B Do\n")
        val formB = addForm(doc, "20 0 10 10 re f /A Do\n")
        formA.resources = PDResources().apply { put(COSName.getPDFName("B"), formB) }
        formB.resources = PDResources().apply { put(COSName.getPDFName("A"), formA) }
        page.resources.put(COSName.getPDFName("A"), formA)
        setPageContent(doc, page, "/A Do\n")

        val parsed = ContentStreamEngine.parse(page)
        assertEquals(2, parsed.leaves.count { it.kind == NodeKind.PATH })
        val forms = flatten(parsed.root).filter { it.label == "表单对象" }
        assertEquals(3, forms.size)
        assertTrue(forms.any { it.detail.contains("递归引用") && it.children.isEmpty() })
        doc.close()
    }

    @Test
    fun formWithoutResourcesInheritsCallerXObjects() {
        val doc = PDDocument()
        val page = addPage(doc)
        val inner = addForm(doc, "0 0 10 10 re f\n")
        val outer = addForm(doc, "/Inner Do\n").apply { resources = null }
        page.resources.put(COSName.getPDFName("Outer"), outer)
        page.resources.put(COSName.getPDFName("Inner"), inner)
        setPageContent(doc, page, "/Outer Do\n")

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertEquals(1, paths.size)
        assertTrue(paths.single().stream?.owner is StreamOwner.Form)
        doc.close()
    }

    @Test
    fun editsAndRestoresAPathInsideFormStream() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "0 0 10 10 re f\n")
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(doc, page, "/Fm Do\n")
        var path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        val before = ElementEditor.snapshot(page)

        val result = ElementEditor.editElement(doc, page, path, EditRequest(dx = 5f))
        assertTrue(result is EditResult.Applied)
        path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        assertEquals(5f, path.bounds!!.minX, 1e-4f)

        ElementEditor.restore(doc, page, before)
        path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        assertEquals(0f, path.bounds!!.minX, 1e-4f)
        doc.close()
    }

    @Test
    fun editingOneRepeatedFormInstanceDoesNotChangeOtherCallSite() {
        val seed = PDDocument()
        val seedPage = addPage(seed)
        val form = addForm(seed, "0 0 10 10 re f\n")
        seedPage.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(
            seed,
            seedPage,
            "q 1 0 0 1 0 0 cm /Fm Do Q q 1 0 0 1 50 0 cm /Fm Do Q\n",
        )
        val original = ByteArrayOutputStream().also(seed::save).toByteArray()
        seed.close()

        val doc = PDDocument.load(original)
        val page = doc.getPage(0)
        val first = ContentStreamEngine.parse(page).leaves
            .filter { it.kind == NodeKind.PATH }
            .first()

        assertTrue(
            ElementEditor.editElement(doc, page, first, EditRequest(dx = 5f)) is EditResult.Applied,
        )
        var paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertEquals(5f, paths[0].bounds!!.minX, 1e-4f)
        assertEquals(50f, paths[1].bounds!!.minX, 1e-4f)

        val saved = ByteArrayOutputStream()
        doc.saveIncremental(saved)
        doc.close()
        PDDocument.load(saved.toByteArray()).use { reopened ->
            paths = ContentStreamEngine.parse(reopened.getPage(0)).leaves
                .filter { it.kind == NodeKind.PATH }
            assertEquals(5f, paths[0].bounds!!.minX, 1e-4f)
            assertEquals(50f, paths[1].bounds!!.minX, 1e-4f)
        }
    }

    @Test
    fun lockingOneRepeatedFormInstanceDoesNotLockOtherCallSite() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "0 0 10 10 re f\n")
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(
            doc,
            page,
            "q 1 0 0 1 0 0 cm /Fm Do Q q 1 0 0 1 50 0 cm /Fm Do Q\n",
        )
        val first = ContentStreamEngine.parse(page).leaves
            .filter { it.kind == NodeKind.PATH }
            .first()

        assertTrue(ElementEditor.setLocked(doc, page, first, locked = true) is EditResult.Applied)

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertTrue(paths[0].locked)
        assertTrue(!paths[1].locked)
        doc.close()
    }

    @Test
    fun editingSharedFormOnOnePageDoesNotChangeAnotherPage() {
        val doc = PDDocument()
        val form = addForm(doc, "0 0 10 10 re f\n")
        val firstPage = addPage(doc).apply {
            resources.put(COSName.getPDFName("Fm"), form)
        }
        val secondPage = addPage(doc).apply {
            resources.put(COSName.getPDFName("Fm"), form)
        }
        setPageContent(doc, firstPage, "/Fm Do\n")
        setPageContent(doc, secondPage, "/Fm Do\n")
        val firstPath = ContentStreamEngine.parse(firstPage).leaves.single { it.kind == NodeKind.PATH }

        assertTrue(
            ElementEditor.editElement(doc, firstPage, firstPath, EditRequest(dx = 7f)) is EditResult.Applied,
        )

        val moved = ContentStreamEngine.parse(firstPage).leaves.single { it.kind == NodeKind.PATH }
        val untouched = ContentStreamEngine.parse(secondPage).leaves.single { it.kind == NodeKind.PATH }
        assertEquals(7f, moved.bounds!!.minX, 1e-4f)
        assertEquals(0f, untouched.bounds!!.minX, 1e-4f)
        doc.close()
    }

    @Test
    fun deletingOneRepeatedFormInstanceKeepsOtherCallSite() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "0 0 10 10 re f\n")
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(
            doc,
            page,
            "q 1 0 0 1 0 0 cm /Fm Do Q q 1 0 0 1 50 0 cm /Fm Do Q\n",
        )
        val first = ContentStreamEngine.parse(page).leaves
            .filter { it.kind == NodeKind.PATH }
            .first()

        ElementEditor.deleteNode(doc, page, first)

        val remaining = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        assertEquals(50f, remaining.bounds!!.minX, 1e-4f)
        doc.close()
    }

    @Test
    fun incrementalSavePersistsFormStreamEdit() {
        val seed = PDDocument()
        val seedPage = addPage(seed)
        val innerForm = addForm(seed, "0 0 10 10 re f\n")
        val outerForm = addForm(seed, "/Inner Do\n")
        outerForm.resources.put(COSName.getPDFName("Inner"), innerForm)
        seedPage.resources.put(COSName.getPDFName("Outer"), outerForm)
        setPageContent(seed, seedPage, "/Outer Do\n")
        val original = ByteArrayOutputStream().also(seed::save).toByteArray()
        seed.close()

        val doc = PDDocument.load(original)
        val page = doc.getPage(0)
        val path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, path, EditRequest(dx = 7f))
        val saved = ByteArrayOutputStream()
        doc.saveIncremental(saved)
        doc.close()

        val reloaded = PDDocument.load(saved.toByteArray())
        val moved = ContentStreamEngine.parse(reloaded.getPage(0))
            .leaves.single { it.kind == NodeKind.PATH }
        assertEquals(7f, moved.bounds!!.minX, 1e-4f)
        reloaded.close()
    }

    private fun addPage(doc: PDDocument): PDPage = PDPage(PDRectangle(300f, 300f)).also {
        it.resources = PDResources()
        doc.addPage(it)
    }

    private fun addForm(doc: PDDocument, content: String): PDFormXObject = PDFormXObject(doc).also {
        it.bBox = PDRectangle(0f, 0f, 100f, 100f)
        it.resources = PDResources()
        writeContent(it.contentStream, content)
    }

    private fun setPageContent(doc: PDDocument, page: PDPage, content: String) {
        val stream = PDStream(doc)
        writeContent(stream, content)
        page.setContents(stream)
    }

    private fun writeContent(stream: PDStream, content: String) {
        stream.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
    }

    private fun flatten(root: DrawNode): List<DrawNode> {
        val nodes = ArrayList<DrawNode>()
        fun visit(node: DrawNode) {
            for (child in node.children) {
                nodes.add(child)
                visit(child)
            }
        }
        visit(root)
        return nodes
    }

    private fun assertBounds(
        actual: Bounds?,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        delta: Float = 1e-4f,
    ) {
        requireNotNull(actual)
        assertEquals(minX, actual.minX, delta)
        assertEquals(minY, actual.minY, delta)
        assertEquals(maxX, actual.maxX, delta)
        assertEquals(maxY, actual.maxY, delta)
    }

    private fun samplePdf(): ByteArray = pdfWithContent(
        "BT /F1 24 Tf 100 700 Td (Hello World) Tj ET\n1 0 0 rg\n50 50 200 100 re f\n",
    )

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
}
