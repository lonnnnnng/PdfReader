package com.loooong.pdfreader.engine

import com.loooong.pdfreader.fitPdfRenderSize
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer

enum class PdfPageExportMode {
    PRESERVE_STRUCTURE,
    FLATTENED_COPY,
}

internal enum class PdfPageExportStrategy {
    DIRECT_DOCUMENT,
    REBUILD_SELECTION,
    FLATTEN_SELECTION,
}

/** long: 全页保留直接写当前文档才能完整携带 Catalog 级结构；选页保留必须重建引用，展平则明确转换为静态页面。 */
internal fun planPdfPageExport(
    mode: PdfPageExportMode,
    pageCount: Int,
    pageIndices: Set<Int>,
): PdfPageExportStrategy {
    require(pageCount > 0) { "PDF 没有可导出的页面" }
    require(pageIndices.isNotEmpty()) { "请至少选择一页" }
    require(pageIndices.all { it in 0 until pageCount }) { "页面索引越界" }
    return when {
        mode == PdfPageExportMode.FLATTENED_COPY -> PdfPageExportStrategy.FLATTEN_SELECTION
        pageIndices.size == pageCount -> PdfPageExportStrategy.DIRECT_DOCUMENT
        else -> PdfPageExportStrategy.REBUILD_SELECTION
    }
}

object PdfFlattenedCopyExporter {
    fun create(
        source: PDDocument,
        pageIndices: Set<Int>,
        renderDpi: Float = DEFAULT_RENDER_DPI,
        maxPixelsPerPage: Long = DEFAULT_MAX_PIXELS_PER_PAGE,
        jpegQuality: Float = DEFAULT_JPEG_QUALITY,
    ): PDDocument {
        require(pageIndices.isNotEmpty()) { "请至少选择一页" }
        require(pageIndices.all { it in 0 until source.numberOfPages }) { "页面索引越界" }
        require(renderDpi.isFinite() && renderDpi > 0f) { "展平渲染 DPI 无效" }
        require(maxPixelsPerPage > 0L) { "展平页面像素预算无效" }
        require(jpegQuality in 0f..1f) { "展平 JPEG 质量无效" }

        val flattened = PDDocument()
        return try {
            copyDocumentInformation(source, flattened)
            val renderer = PDFRenderer(source)
            pageIndices.sorted().forEach { pageIndex ->
                val sourcePage = source.getPage(pageIndex)
                val crop = sourcePage.cropBox
                val rotation = ((sourcePage.rotation % 360) + 360) % 360
                val pageWidth = if (rotation == 90 || rotation == 270) crop.height else crop.width
                val pageHeight = if (rotation == 90 || rotation == 270) crop.width else crop.height
                val renderSize = fitPdfRenderSize(
                    widthPoints = crop.width,
                    heightPoints = crop.height,
                    rotation = rotation,
                    requestedScale = renderDpi / 72f,
                    maxPixels = maxPixelsPerPage,
                    maxDimension = DEFAULT_MAX_DIMENSION,
                )
                val bitmap = renderer.renderImage(pageIndex, renderSize.scale)
                try {
                    val targetPage = PDPage(PDRectangle(pageWidth, pageHeight))
                    flattened.addPage(targetPage)
                    val image = JPEGFactory.createFromImage(flattened, bitmap, jpegQuality)
                    PDPageContentStream(flattened, targetPage).use { content ->
                        content.drawImage(image, 0f, 0f, pageWidth, pageHeight)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            flattened
        } catch (failure: Throwable) {
            runCatching { flattened.close() }
            throw failure
        }
    }

    private fun copyDocumentInformation(source: PDDocument, destination: PDDocument) {
        val cloned = PDFCloneUtility(destination)
            .cloneForNewDocument(source.documentInformation.cosObject) as? com.tom_roush.pdfbox.cos.COSDictionary
            ?: return
        // long: 展平只保留不执行代码的 Info 元数据；Catalog 级表单、脚本、附件和导航结构不会复制到静态副本。
        destination.documentInformation = PDDocumentInformation(cloned)
    }

    private const val DEFAULT_RENDER_DPI = 144f
    private const val DEFAULT_MAX_PIXELS_PER_PAGE = 12_000_000L
    private const val DEFAULT_MAX_DIMENSION = 8_192
    private const val DEFAULT_JPEG_QUALITY = 0.9f
}
