package com.loooong.pdfreader.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageExportTest {

    @Test
    fun preserveAllPagesWritesTheOriginalDocumentStructure() {
        assertEquals(
            PdfPageExportStrategy.DIRECT_DOCUMENT,
            planPdfPageExport(
                mode = PdfPageExportMode.PRESERVE_STRUCTURE,
                pageCount = 3,
                pageIndices = setOf(0, 1, 2),
            ),
        )
    }

    @Test
    fun preserveSelectionRebuildsWhileFlattenAlwaysRendersStaticPages() {
        assertEquals(
            PdfPageExportStrategy.REBUILD_SELECTION,
            planPdfPageExport(
                mode = PdfPageExportMode.PRESERVE_STRUCTURE,
                pageCount = 3,
                pageIndices = setOf(0, 2),
            ),
        )
        assertEquals(
            PdfPageExportStrategy.FLATTEN_SELECTION,
            planPdfPageExport(
                mode = PdfPageExportMode.FLATTENED_COPY,
                pageCount = 3,
                pageIndices = setOf(0, 1, 2),
            ),
        )
    }
}
