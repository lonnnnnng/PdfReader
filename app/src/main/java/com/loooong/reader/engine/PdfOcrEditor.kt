package com.loooong.reader.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.hypot

data class OcrImagePoint(
    val x: Float,
    val y: Float,
)

data class OcrLine(
    val text: String,
    val topLeft: OcrImagePoint,
    val topRight: OcrImagePoint,
    val bottomRight: OcrImagePoint,
    val bottomLeft: OcrImagePoint,
)

data class OcrPageRecognition(
    val imageWidth: Int,
    val imageHeight: Int,
    val lines: List<OcrLine>,
)

internal data class OcrPlacedLine(
    val text: String,
    val matrix: Matrix,
    val fontSize: Float,
    val horizontalScalePercent: Float,
)

/** long: OCR 文字层始终使用专用 marked-content 标签，便于重新识别、永久遮盖和检查器隐藏统一识别。 */
object PdfOcrEditor {
    const val MARKER_NAME = "ReaderOCR"
    val MARKER: COSName = COSName.getPDFName(MARKER_NAME)

    fun hasExtractableText(document: PDDocument, pageIndex: Int): Boolean {
        require(pageIndex in 0 until document.numberOfPages) { "OCR 页码超出文档范围" }
        return PDFTextStripper().apply {
            startPage = pageIndex + 1
            endPage = pageIndex + 1
        }.getText(document).any { !it.isWhitespace() }
    }

    fun hasGeneratedLayer(page: PDPage): Boolean = generatedLayerRanges(readTokens(page))
        ?.isNotEmpty() == true

    /**
     * long: 同一页重新 OCR 时先删除旧的 ReaderOCR 区段，再追加新的隐藏文字层；旧层和新层不会同时存在，
     * 搜索、复制和永久遮盖也就不会命中重复文本。
     */
    fun replaceLayer(
        document: PDDocument,
        page: PDPage,
        recognition: OcrPageRecognition,
        font: PDFont,
    ): Int {
        require(recognition.imageWidth > 0 && recognition.imageHeight > 0) { "OCR 图像尺寸无效" }
        removeGeneratedLayer(document, page)

        val placed = recognition.lines.mapNotNull { line ->
            placeLine(page, recognition.imageWidth, recognition.imageHeight, line, font)
        }
        if (placed.isEmpty()) return 0

        PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true,
        ).use { content ->
            content.beginMarkedContent(MARKER)
            content.saveGraphicsState()
            content.transform(visualToPageMatrix(page))
            placed.forEach { line ->
                content.beginText()
                content.setFont(font, line.fontSize)
                content.setRenderingMode(RenderingMode.NEITHER)
                content.setHorizontalScaling(line.horizontalScalePercent)
                content.setTextMatrix(line.matrix)
                content.showText(line.text)
                content.endText()
            }
            content.restoreGraphicsState()
            content.endMarkedContent()
        }
        ElementEditor.markPageUpdated(document, page)
        return placed.size
    }

    internal fun placeLine(
        page: PDPage,
        imageWidth: Int,
        imageHeight: Int,
        line: OcrLine,
        font: PDFont,
    ): OcrPlacedLine? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val text = line.text.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null

        val topLeft = imageToVisual(page, imageWidth, imageHeight, line.topLeft)
        val topRight = imageToVisual(page, imageWidth, imageHeight, line.topRight)
        val bottomRight = imageToVisual(page, imageWidth, imageHeight, line.bottomRight)
        val bottomLeft = imageToVisual(page, imageWidth, imageHeight, line.bottomLeft)
        val xVectorX = ((topRight.first - topLeft.first) + (bottomRight.first - bottomLeft.first)) / 2f
        val xVectorY = ((topRight.second - topLeft.second) + (bottomRight.second - bottomLeft.second)) / 2f
        val yVectorX = ((topLeft.first - bottomLeft.first) + (topRight.first - bottomRight.first)) / 2f
        val yVectorY = ((topLeft.second - bottomLeft.second) + (topRight.second - bottomRight.second)) / 2f
        val targetWidth = hypot(xVectorX, xVectorY)
        val targetHeight = hypot(yVectorX, yVectorY)
        if (!targetWidth.isFinite() || !targetHeight.isFinite() || targetWidth < 0.25f || targetHeight < 0.25f) {
            return null
        }

        val xUnitX = xVectorX / targetWidth
        val xUnitY = xVectorY / targetWidth
        val yUnitX = yVectorX / targetHeight
        val yUnitY = yVectorY / targetHeight
        // long: 解析器与遮盖使用 0.75/-0.25 的文字高度模型；按框高设字号并上移四分之一，
        // 生成节点的页面边界会与 OCR 框重合，后续区域遮盖才能同步删除隐藏文字。
        val fontSize = targetHeight.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        val baselineX = bottomLeft.first + yUnitX * fontSize * 0.25f
        val baselineY = bottomLeft.second + yUnitY * fontSize * 0.25f
        val naturalWidth = runCatching { font.getStringWidth(text) / 1_000f * fontSize }.getOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: return null
        val horizontalScale = (targetWidth / naturalWidth * 100f)
            .coerceIn(MIN_HORIZONTAL_SCALE, MAX_HORIZONTAL_SCALE)
        runCatching { font.encode(text) }.getOrElse { return null }

        return OcrPlacedLine(
            text = text,
            matrix = Matrix(xUnitX, xUnitY, yUnitX, yUnitY, baselineX, baselineY),
            fontSize = fontSize,
            horizontalScalePercent = horizontalScale,
        )
    }

    internal fun imageToVisual(
        page: PDPage,
        imageWidth: Int,
        imageHeight: Int,
        point: OcrImagePoint,
    ): Pair<Float, Float> {
        require(imageWidth > 0 && imageHeight > 0) { "OCR 图像尺寸无效" }
        val crop = page.cropBox
        val rotation = page.normalizedRotation()
        val visualWidth = if (rotation == 90 || rotation == 270) crop.height else crop.width
        val visualHeight = if (rotation == 90 || rotation == 270) crop.width else crop.height
        return point.x / imageWidth * visualWidth to
            visualHeight - point.y / imageHeight * visualHeight
    }

    private fun removeGeneratedLayer(document: PDDocument, page: PDPage) {
        val tokens = readTokens(page)
        val ranges = generatedLayerRanges(tokens)
            ?: error("旧 OCR 文字层结构损坏，已停止重新识别")
        if (ranges.isEmpty()) return
        val kept = tokens.filterIndexed { index, _ -> ranges.none { index in it } }
        ElementEditor.replacePageContent(document, page, kept)
    }

    private fun generatedLayerRanges(tokens: List<Any>): List<IntRange>? {
        data class Frame(val startIndex: Int, val generated: Boolean)

        val stack = ArrayDeque<Frame>()
        val ranges = ArrayList<IntRange>()
        tokens.forEachIndexed { index, token ->
            val operator = token as? Operator ?: return@forEachIndexed
            when (operator.name) {
                "BMC", "BDC" -> {
                    val start = operandStart(tokens, index)
                    val tag = tokens.getOrNull(start) as? COSName
                    stack.addLast(Frame(start, tag?.name == MARKER_NAME))
                }
                "EMC" -> {
                    if (stack.isEmpty()) return null
                    val frame = stack.removeLast()
                    if (frame.generated) ranges += frame.startIndex..index
                }
            }
        }
        if (stack.any { it.generated }) return null
        return ranges.sortedBy { it.first }
    }

    private fun operandStart(tokens: List<Any>, operatorIndex: Int): Int {
        var start = operatorIndex
        while (start > 0 && tokens[start - 1] !is Operator) start--
        return start
    }

    private fun readTokens(page: PDPage): List<Any> = PDFStreamParser(page).run {
        parse()
        ArrayList(tokens)
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

    private fun PDPage.normalizedRotation(): Int = ((rotation % 360) + 360) % 360

    private const val MIN_FONT_SIZE = 1f
    private const val MAX_FONT_SIZE = 512f
    private const val MIN_HORIZONTAL_SCALE = 5f
    private const val MAX_HORIZONTAL_SCALE = 2_000f
}
