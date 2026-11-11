package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageTransferInspectorTest {

    @Test
    fun inspectAllReportsEverySplitRiskOnceBeforeChoosingOutputFolder() {
        PDDocument().use { document ->
            repeat(2) { document.addPage(PDPage(PDRectangle.A4)) }
            document.documentCatalog.cosObject.setItem(
                COSName.getPDFName("Names"),
                COSDictionary().apply {
                    setItem(COSName.getPDFName("JavaScript"), COSDictionary())
                },
            )
            document.getPage(0).annotations = listOf(
                PDAnnotationLink().apply {
                    rectangle = PDRectangle(10f, 10f, 80f, 24f)
                    action = PDActionGoTo().apply {
                        destination = PDPageXYZDestination().apply { page = document.getPage(1) }
                    }
                    page = document.getPage(0)
                },
            )

            val issues = PdfPageTransferInspector.inspectAll(
                document,
                listOf(setOf(0), setOf(1)),
            )

            assertEquals(
                setOf(
                    PdfPageTransferIssueKind.NAME_TREE,
                    PdfPageTransferIssueKind.LINK_TARGET_OUTSIDE_SELECTION,
                ),
                issues.map(PdfPageTransferIssue::kind).toSet(),
            )
            assertEquals(issues.map(PdfPageTransferIssue::kind).distinct().size, issues.size)
        }
    }
}
