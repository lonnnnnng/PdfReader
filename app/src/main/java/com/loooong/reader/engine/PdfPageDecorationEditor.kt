package com.loooong.reader.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.cos
import kotlin.math.sin

enum class PageDecorationPosition {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
}

data class PdfWatermarkRequest(
    val text: String,
    val fontSize: Float = 48f,
    val colorArgb: Int = 0xFF64748B.toInt(),
    val opacity: Float = 0.22f,
    val rotationDegrees: Float = -35f,
    val position: PageDecorationPosition = PageDecorationPosition.CENTER,
    val margin: Float = 36f,
)

data class PdfPageNumberRequest(
    val template: String = "{page}",
    val startNumber: Int = 1,
    val fontSize: Float = 11f,
    val colorArgb: Int = 0xFF475569.toInt(),
    val opacity: Float = 1f,
    val position: PageDecorationPosition = PageDecorationPosition.BOTTOM_CENTER,
    val margin: Float = 24f,
)

data class PdfPageDecorationRequest(
    val pageIndices: Set<Int>,
    val watermark: PdfWatermarkRequest? = null,
    val pageNumber: PdfPageNumberRequest? = null,
)

data class PdfPageDecorationResult(
    val decoratedPageCount: Int,
    val watermarkCount: Int,
    val pageNumberCount: Int,
)

/** long: 页面装饰统一在页面视觉坐标中布局，再逆变换回 PDF 用户空间，旋转页与裁剪页使用同一套位置语义。 */
object PdfPageDecorationEditor {

    fun apply(
        document: PDDocument,
        request: PdfPageDecorationRequest,
        resolveFont: (String) -> PDFont?,
    ): PdfPageDecorationResult {
        val pages = request.pageIndices.sorted()
        require(pages.isNotEmpty()) { "请至少选择一页" }
        require(pages.all { it in 0 until document.numberOfPages }) { "页面索引越界" }
        require(request.watermark != null || request.pageNumber != null) { "请至少启用水印或页码" }

        val watermark = request.watermark?.normalized()
        val numberTexts = request.pageNumber?.normalized()?.let { number ->
            pages.mapIndexed { order, _ ->
                number.template
                    .replace("{page}", (number.startNumber + order).toString())
                    .replace("{total}", pages.size.toString())
            }
        }.orEmpty()
        val watermarkFont = watermark?.let { config ->
            requireNotNull(resolveFont(config.text)) { "没有可编码水印文本的字体" }
        }
        val pageNumber = request.pageNumber?.normalized()
        val pageNumberFont = pageNumber?.let {
            requireNotNull(resolveFont(numberTexts.joinToString("\n"))) { "没有可编码页码文本的字体" }
        }

        // long: 所有文本和字体在触碰页面前完成校验，避免字体解析失败时只装饰前半部分页面。
        watermarkFont?.encode(watermark!!.text)
        pageNumberFont?.let { font -> numberTexts.forEach(font::encode) }

        pages.forEachIndexed { order, pageIndex ->
            val page = document.getPage(pageIndex)
            PDPageContentStream(
                document,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true,
            ).use { content ->
                content.saveGraphicsState()
                content.transform(visualToPageMatrix(page))
                watermark?.let { config ->
                    drawText(content, page, config.toTextStyle(requireNotNull(watermarkFont)), config.text)
                }
                pageNumber?.let { config ->
                    drawText(
                        content,
                        page,
                        config.toTextStyle(requireNotNull(pageNumberFont)),
                        numberTexts[order],
                    )
                }
                content.restoreGraphicsState()
            }
            ElementEditor.markPageUpdated(document, page)
        }

        return PdfPageDecorationResult(
            decoratedPageCount = pages.size,
            watermarkCount = if (watermark == null) 0 else pages.size,
            pageNumberCount = if (pageNumber == null) 0 else pages.size,
        )
    }

    private fun drawText(
        content: PDPageContentStream,
        page: PDPage,
        style: TextStyle,
        text: String,
    ) {
        val box = page.cropBox
        val rotation = page.normalizedRotation()
        val visualWidth = if (rotation == 90 || rotation == 270) box.height else box.width
        val visualHeight = if (rotation == 90 || rotation == 270) box.width else box.height
        val width = style.font.getStringWidth(text) / 1_000f * style.fontSize
        val descriptor = style.font.fontDescriptor
        val ascent = descriptor?.ascent?.takeIf(Float::isFinite)?.div(1_000f)?.times(style.fontSize)
            ?: style.fontSize * 0.8f
        val descent = descriptor?.descent?.takeIf(Float::isFinite)?.div(1_000f)?.times(style.fontSize)
            ?: -style.fontSize * 0.2f
        val x = when (style.position) {
            PageDecorationPosition.TOP_LEFT,
            PageDecorationPosition.CENTER_LEFT,
            PageDecorationPosition.BOTTOM_LEFT,
            -> style.margin
            PageDecorationPosition.TOP_CENTER,
            PageDecorationPosition.CENTER,
            PageDecorationPosition.BOTTOM_CENTER,
            -> (visualWidth - width) / 2f
            PageDecorationPosition.TOP_RIGHT,
            PageDecorationPosition.CENTER_RIGHT,
            PageDecorationPosition.BOTTOM_RIGHT,
            -> visualWidth - style.margin - width
        }
        val baseline = when (style.position) {
            PageDecorationPosition.BOTTOM_LEFT,
            PageDecorationPosition.BOTTOM_CENTER,
            PageDecorationPosition.BOTTOM_RIGHT,
            -> style.margin - descent
            PageDecorationPosition.CENTER_LEFT,
            PageDecorationPosition.CENTER,
            PageDecorationPosition.CENTER_RIGHT,
            -> (visualHeight - (ascent - descent)) / 2f - descent
            PageDecorationPosition.TOP_LEFT,
            PageDecorationPosition.TOP_CENTER,
            PageDecorationPosition.TOP_RIGHT,
            -> visualHeight - style.margin - ascent
        }
        val radians = Math.toRadians(style.rotationDegrees.toDouble()).toFloat()
        val cosValue = cos(radians)
        val sinValue = sin(radians)
        val centerX = x + width / 2f
        val centerY = baseline + (ascent + descent) / 2f
        val translateX = centerX + cosValue * (x - centerX) - sinValue * (baseline - centerY)
        val translateY = centerY + sinValue * (x - centerX) + cosValue * (baseline - centerY)

        content.saveGraphicsState()
        content.setGraphicsStateParameters(
            PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = style.opacity
                strokingAlphaConstant = style.opacity
            },
        )
        content.setNonStrokingColor(
            ((style.colorArgb shr 16) and 0xFF) / 255f,
            ((style.colorArgb shr 8) and 0xFF) / 255f,
            (style.colorArgb and 0xFF) / 255f,
        )
        content.beginText()
        content.setFont(style.font, style.fontSize)
        content.setTextMatrix(
            Matrix(cosValue, sinValue, -sinValue, cosValue, translateX, translateY),
        )
        content.showText(text)
        content.endText()
        content.restoreGraphicsState()
    }

    private fun visualToPageMatrix(page: PDPage): Matrix {
        val box = page.cropBox
        return when (page.normalizedRotation()) {
            90 -> Matrix(0f, 1f, -1f, 0f, box.lowerLeftX + box.width, box.lowerLeftY)
            180 -> Matrix(-1f, 0f, 0f, -1f, box.lowerLeftX + box.width, box.lowerLeftY + box.height)
            270 -> Matrix(0f, -1f, 1f, 0f, box.lowerLeftX, box.lowerLeftY + box.height)
            else -> Matrix(1f, 0f, 0f, 1f, box.lowerLeftX, box.lowerLeftY)
        }
    }

    private fun PdfWatermarkRequest.normalized(): PdfWatermarkRequest = copy(text = text.trim()).also {
        require(it.text.isNotEmpty()) { "水印文本不能为空" }
        require(it.text.length <= 500) { "水印文本不能超过 500 个字符" }
        validateStyle(it.fontSize, it.opacity, it.rotationDegrees, it.margin)
    }

    private fun PdfPageNumberRequest.normalized(): PdfPageNumberRequest = copy(template = template.trim()).also {
        require(it.template.isNotEmpty()) { "页码模板不能为空" }
        require(it.template.length <= 200) { "页码模板不能超过 200 个字符" }
        require(it.template.contains("{page}") || it.template.contains("{total}")) {
            "页码模板需要包含 {page} 或 {total}"
        }
        val remaining = it.template.replace("{page}", "").replace("{total}", "")
        require('{' !in remaining && '}' !in remaining) { "页码模板包含未知占位符" }
        validateStyle(it.fontSize, it.opacity, rotationDegrees = 0f, it.margin)
    }

    private fun validateStyle(fontSize: Float, opacity: Float, rotationDegrees: Float, margin: Float) {
        require(fontSize.isFinite() && fontSize in 4f..400f) { "字号必须在 4-400 pt" }
        require(opacity.isFinite() && opacity in 0f..1f) { "不透明度必须在 0-1 之间" }
        require(rotationDegrees.isFinite()) { "旋转角度无效" }
        require(margin.isFinite() && margin >= 0f) { "边距不能小于 0" }
    }

    private fun PdfWatermarkRequest.toTextStyle(font: PDFont): TextStyle = TextStyle(
        font = font,
        fontSize = fontSize,
        colorArgb = colorArgb,
        opacity = opacity * ((colorArgb ushr 24) and 0xFF) / 255f,
        rotationDegrees = rotationDegrees,
        position = position,
        margin = margin,
    )

    private fun PdfPageNumberRequest.toTextStyle(font: PDFont): TextStyle = TextStyle(
        font = font,
        fontSize = fontSize,
        colorArgb = colorArgb,
        opacity = opacity * ((colorArgb ushr 24) and 0xFF) / 255f,
        rotationDegrees = 0f,
        position = position,
        margin = margin,
    )

    private data class TextStyle(
        val font: PDFont,
        val fontSize: Float,
        val colorArgb: Int,
        val opacity: Float,
        val rotationDegrees: Float,
        val position: PageDecorationPosition,
        val margin: Float,
    )

    private fun PDPage.normalizedRotation(): Int = ((rotation % 360) + 360) % 360
}
