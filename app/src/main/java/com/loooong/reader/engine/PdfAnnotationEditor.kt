package com.loooong.reader.engine

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDAppearanceContentStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.util.Matrix

typealias SignatureStroke = List<Pair<Float, Float>>

enum class TextMarkupKind(
    val subtype: String,
    val defaultColorArgb: Int,
    val defaultOpacity: Float,
) {
    HIGHLIGHT(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT, 0xFFFFD54F.toInt(), 0.35f),
    UNDERLINE(PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE, 0xFF1565C0.toInt(), 1f),
    STRIKEOUT(PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT, 0xFFD32F2F.toInt(), 1f),
}

enum class StampKind(
    val pdfName: String,
    val appearanceText: String,
    val displayName: String,
    val colorArgb: Int,
) {
    APPROVED(PDAnnotationRubberStamp.NAME_APPROVED, "APPROVED", "已批准", 0xFF2E7D32.toInt()),
    DRAFT(PDAnnotationRubberStamp.NAME_DRAFT, "DRAFT", "草稿", 0xFFEF6C00.toInt()),
    CONFIDENTIAL(PDAnnotationRubberStamp.NAME_CONFIDENTIAL, "CONFIDENTIAL", "机密", 0xFFC62828.toInt()),
    FINAL(PDAnnotationRubberStamp.NAME_FINAL, "FINAL", "最终版", 0xFF1565C0.toInt()),
    ;

    companion object {
        fun fromPdfName(name: String?): StampKind = entries.firstOrNull { it.pdfName == name } ?: APPROVED
    }
}

data class AnnotationEditRequest(
    val colorArgb: Int,
    val opacity: Float,
    val contents: String,
    val font: PDFont? = null,
    val fontSize: Float = DEFAULT_FREE_TEXT_FONT_SIZE,
)

data class PdfAnnotationInfo(
    val index: Int,
    val subtype: String,
    val bounds: Bounds,
    val contents: String?,
    val colorArgb: Int,
    val opacity: Float,
    val editable: Boolean,
    val fontSize: Float?,
)

/** long: 页面批注写入器，覆盖不依赖外部资源的高亮、便签和手写签名。 */
object PdfAnnotationEditor {

    fun list(page: PDPage): List<PdfAnnotationInfo> = page.getAnnotations().mapIndexedNotNull { index, annotation ->
        val rectangle = annotation.rectangle ?: return@mapIndexedNotNull null
        PdfAnnotationInfo(
            index = index,
            subtype = annotation.subtype ?: "批注",
            bounds = Bounds(
                rectangle.lowerLeftX,
                rectangle.lowerLeftY,
                rectangle.upperRightX,
                rectangle.upperRightY,
            ),
            contents = annotation.contents?.takeIf { it.isNotBlank() },
            colorArgb = annotation.color.toArgbOrNull() ?: defaultColorFor(annotation.subtype),
            opacity = (annotation as? PDAnnotationMarkup)?.constantOpacity?.coerceIn(0f, 1f) ?: 1f,
            editable = annotation.isEditable(),
            fontSize = annotation.freeTextFontSize(),
        )
    }

    fun update(
        document: PDDocument,
        page: PDPage,
        index: Int,
        request: AnnotationEditRequest,
    ): Boolean {
        require(request.opacity.isFinite() && request.opacity in 0f..1f) { "批注透明度必须在 0-1 之间" }
        require(request.fontSize.isFinite() && request.fontSize in 6f..96f) { "批注字号必须在 6-96 pt" }
        val annotation = page.getAnnotations().getOrNull(index) ?: return false
        require(annotation.isEditable()) {
            "当前批注已锁定或只读"
        }
        val normalizedContents = request.contents.trim()
        if (annotation.subtype == PDAnnotationMarkup.SUB_TYPE_FREETEXT) {
            require(normalizedContents.isNotBlank()) { "FreeText 内容不能为空" }
        }
        val currentColor = annotation.color.toArgbOrNull() ?: defaultColorFor(annotation.subtype)
        val currentOpacity = (annotation as? PDAnnotationMarkup)?.constantOpacity?.coerceIn(0f, 1f) ?: 1f
        val currentFontSize = annotation.freeTextFontSize()
        if (
            currentColor == request.colorArgb &&
            currentOpacity == request.opacity &&
            annotation.contents.orEmpty() == normalizedContents &&
            (currentFontSize == null || currentFontSize == request.fontSize)
        ) return false

        annotation.setColor(toColor(request.colorArgb))
        annotation.setContents(normalizedContents)
        if (annotation is PDAnnotationMarkup) annotation.setConstantOpacity(request.opacity)
        if (annotation.subtype == PDAnnotationMarkup.SUB_TYPE_FREETEXT) {
            val font = requireNotNull(request.font) { "FreeText 批注缺少可编码当前文本的字体" }
            (annotation as PDAnnotationMarkup).apply {
                setDefaultAppearance(request.colorArgb.toDefaultAppearance(request.fontSize))
                setDefaultStyleString("font:${request.fontSize}pt sans-serif;color:${request.colorArgb.toCssColor()}")
            }
            rebuildFreeTextAppearance(
                document = document,
                annotation = annotation,
                font = font,
                fontSize = request.fontSize,
                colorArgb = request.colorArgb,
                opacity = request.opacity,
            )
        } else if (annotation is PDAnnotationRubberStamp) {
            val font = requireNotNull(request.font) { "图章缺少可用字体" }
            rebuildStampAppearance(
                document = document,
                annotation = annotation,
                kind = StampKind.fromPdfName(annotation.name),
                font = font,
                colorArgb = request.colorArgb,
                opacity = request.opacity,
            )
        } else if (annotation.subtype == PDAnnotationMarkup.SUB_TYPE_INK) {
            // long: Ink 的可见笔画完全依赖外观流；TextMarkup 则保留原矩形和 QuadPoints，避免 PDFBox 扩框后移动坐标漂移。
            runCatching { annotation.constructAppearances(document) }
        }
        ElementEditor.markPageUpdated(document, page)
        return true
    }

    fun delete(document: PDDocument, page: PDPage, index: Int): Boolean {
        val annotations = page.getAnnotations().toMutableList()
        if (index !in annotations.indices) return false
        if (!annotations[index].isEditable()) return false
        annotations.removeAt(index)
        page.setAnnotations(annotations)
        ElementEditor.markPageUpdated(document, page)
        return true
    }

    fun move(document: PDDocument, page: PDPage, index: Int, dx: Float, dy: Float): Boolean {
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0f && dy == 0f)) return false
        val annotation = page.getAnnotations().getOrNull(index) ?: return false
        if (!annotation.isEditable()) return false
        val rectangle = annotation.rectangle ?: return false
        annotation.setRectangle(
            PDRectangle(
                rectangle.lowerLeftX + dx,
                rectangle.lowerLeftY + dy,
                rectangle.width,
                rectangle.height,
            ),
        )
        when (annotation) {
            is PDAnnotationTextMarkup -> {
                annotation.setQuadPoints(annotation.quadPoints.shiftPairs(dx, dy))
            }
            is PDAnnotationMarkup -> if (annotation.subtype == PDAnnotationMarkup.SUB_TYPE_INK) {
                annotation.setInkList(annotation.inkList.map { it.shiftPairs(dx, dy) }.toTypedArray())
                runCatching { annotation.constructAppearances(document) }
            }
        }
        ElementEditor.markPageUpdated(document, page)
        return true
    }

    fun addHighlight(
        document: PDDocument,
        page: PDPage,
        bounds: Bounds,
        colorArgb: Int = 0xFFFFD54F.toInt(),
        contents: String? = null,
    ) {
        addTextMarkup(
            document = document,
            page = page,
            bounds = bounds,
            kind = TextMarkupKind.HIGHLIGHT,
            colorArgb = colorArgb,
            opacity = 0.35f,
            contents = contents,
        )
    }

    fun addTextMarkup(
        document: PDDocument,
        page: PDPage,
        bounds: Bounds,
        kind: TextMarkupKind,
        colorArgb: Int = kind.defaultColorArgb,
        opacity: Float = kind.defaultOpacity,
        contents: String? = null,
    ) {
        require(opacity.isFinite() && opacity in 0f..1f) { "批注透明度必须在 0-1 之间" }
        val rect = annotationRect(bounds)
        val annotation = PDAnnotationTextMarkup(kind.subtype)
        annotation.setRectangle(rect)
        annotation.setQuadPoints(
            floatArrayOf(
                rect.lowerLeftX, rect.upperRightY,
                rect.upperRightX, rect.upperRightY,
                rect.lowerLeftX, rect.lowerLeftY,
                rect.upperRightX, rect.lowerLeftY,
            ),
        )
        annotation.setColor(toColor(colorArgb))
        annotation.setConstantOpacity(opacity)
        contents?.takeIf { it.isNotBlank() }?.let(annotation::setContents)
        annotation.setPage(page)
        append(page, annotation)
        ElementEditor.markPageUpdated(document, page)
    }

    fun addFreeText(
        document: PDDocument,
        page: PDPage,
        bounds: Bounds,
        contents: String,
        font: PDFont,
        colorArgb: Int = 0xFF202124.toInt(),
        opacity: Float = 1f,
        fontSize: Float = DEFAULT_FREE_TEXT_FONT_SIZE,
    ) {
        require(contents.isNotBlank()) { "FreeText 内容不能为空" }
        require(opacity.isFinite() && opacity in 0f..1f) { "批注透明度必须在 0-1 之间" }
        require(fontSize.isFinite() && fontSize in 6f..96f) { "批注字号必须在 6-96 pt" }
        val rect = freeTextRect(page, bounds)
        val annotation = PDAnnotationMarkup().apply {
            cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_FREETEXT)
            setRectangle(rect)
            setContents(contents.trim())
            setColor(toColor(colorArgb))
            setConstantOpacity(opacity)
            setDefaultAppearance(colorArgb.toDefaultAppearance(fontSize))
            setDefaultStyleString("font:${fontSize}pt sans-serif;color:${colorArgb.toCssColor()}")
            setBorderStyle(PDBorderStyleDictionary().apply { width = 0f })
            setPage(page)
        }
        rebuildFreeTextAppearance(document, annotation, font, fontSize, colorArgb, opacity)
        append(page, annotation)
        ElementEditor.markPageUpdated(document, page)
    }

    fun addStamp(
        document: PDDocument,
        page: PDPage,
        bounds: Bounds,
        kind: StampKind,
        font: PDFont,
        opacity: Float = 1f,
    ) {
        require(opacity.isFinite() && opacity in 0f..1f) { "图章透明度必须在 0-1 之间" }
        val annotation = PDAnnotationRubberStamp().apply {
            setName(kind.pdfName)
            setRectangle(stampRect(page, bounds))
            setContents(kind.displayName)
            setColor(toColor(kind.colorArgb))
            setConstantOpacity(opacity)
            setPage(page)
        }
        rebuildStampAppearance(document, annotation, kind, font, kind.colorArgb, opacity)
        append(page, annotation)
        ElementEditor.markPageUpdated(document, page)
    }

    fun addNote(
        document: PDDocument,
        page: PDPage,
        bounds: Bounds,
        contents: String,
        colorArgb: Int = 0xFFFFD54F.toInt(),
    ) {
        require(contents.isNotBlank()) { "便签内容不能为空" }
        val rect = noteRect(bounds)
        val annotation = PDAnnotationText()
        annotation.setRectangle(rect)
        annotation.setName(PDAnnotationText.NAME_NOTE)
        annotation.setColor(toColor(colorArgb))
        annotation.setContents(contents.trim())
        annotation.setPage(page)
        append(page, annotation)
        ElementEditor.markPageUpdated(document, page)
    }

    fun addSignature(
        document: PDDocument,
        page: PDPage,
        strokes: List<SignatureStroke>,
        colorArgb: Int = 0xFF202124.toInt(),
    ): Boolean {
        val usable = strokes.filter { stroke ->
            stroke.size >= 2 && stroke.all { (x, y) -> x.isFinite() && y.isFinite() }
        }
        if (usable.isEmpty()) return false

        val crop = page.cropBox
        val left = crop.lowerLeftX + crop.width * 0.22f
        val bottom = crop.lowerLeftY + crop.height * 0.12f
        val width = (crop.width * 0.56f).coerceAtLeast(48f)
        val height = (crop.height * 0.20f).coerceAtLeast(32f)
        val mapped = usable.map { stroke ->
            stroke.flatMap { (x, y) ->
                listOf(
                    left + x.coerceIn(0f, 1f) * width,
                    bottom + (1f - y.coerceIn(0f, 1f)) * height,
                )
            }.toFloatArray()
        }.toTypedArray()
        val allPoints = mapped.flatMap { it.asList().chunked(2) }
        val minX = allPoints.minOf { it[0] }
        val maxX = allPoints.maxOf { it[0] }
        val minY = allPoints.minOf { it[1] }
        val maxY = allPoints.maxOf { it[1] }
        val padding = 4f
        val annotation = PDAnnotationMarkup()
        annotation.cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
        annotation.setRectangle(
            PDRectangle(
                minX - padding,
                minY - padding,
                (maxX - minX + padding * 2f).coerceAtLeast(1f),
                (maxY - minY + padding * 2f).coerceAtLeast(1f),
            ),
        )
        annotation.setInkList(mapped)
        annotation.setColor(toColor(colorArgb))
        annotation.setConstantOpacity(1f)
        annotation.setContents("手写签名")
        annotation.setPage(page)
        runCatching { annotation.constructAppearances(document) }
        append(page, annotation)
        ElementEditor.markPageUpdated(document, page)
        return true
    }

    private fun append(page: PDPage, annotation: com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation) {
        val annotations = page.getAnnotations().toMutableList()
        annotations += annotation
        page.setAnnotations(annotations)
    }

    private fun annotationRect(bounds: Bounds): PDRectangle {
        val left = bounds.minX.coerceAtMost(bounds.maxX)
        val right = bounds.maxX.coerceAtLeast(bounds.minX)
        val bottom = bounds.minY.coerceAtMost(bounds.maxY)
        val top = bounds.maxY.coerceAtLeast(bounds.minY)
        return PDRectangle(left, bottom, (right - left).coerceAtLeast(1f), (top - bottom).coerceAtLeast(1f))
    }

    private fun noteRect(bounds: Bounds): PDRectangle {
        val size = (bounds.height.coerceAtLeast(12f) * 0.28f).coerceIn(12f, 24f)
        return PDRectangle(bounds.maxX + 4f, bounds.maxY - size, size, size)
    }

    private fun freeTextRect(page: PDPage, bounds: Bounds): PDRectangle {
        val crop = page.cropBox
        val width = bounds.width.coerceAtLeast(120f).coerceAtMost(crop.width)
        val height = bounds.height.coerceAtLeast(42f).coerceAtMost(crop.height)
        val left = bounds.minX.coerceIn(crop.lowerLeftX, crop.upperRightX - width)
        val bottom = bounds.minY.coerceIn(crop.lowerLeftY, crop.upperRightY - height)
        return PDRectangle(left, bottom, width, height)
    }

    private fun stampRect(page: PDPage, bounds: Bounds): PDRectangle {
        val crop = page.cropBox
        val width = bounds.width.coerceAtLeast(128f).coerceAtMost(crop.width)
        val height = bounds.height.coerceAtLeast(46f).coerceAtMost(crop.height)
        val left = bounds.minX.coerceIn(crop.lowerLeftX, crop.upperRightX - width)
        val bottom = bounds.minY.coerceIn(crop.lowerLeftY, crop.upperRightY - height)
        return PDRectangle(left, bottom, width, height)
    }

    private fun rebuildFreeTextAppearance(
        document: PDDocument,
        annotation: PDAnnotationMarkup,
        font: PDFont,
        fontSize: Float,
        colorArgb: Int,
        opacity: Float,
    ) {
        val rect = requireNotNull(annotation.rectangle) { "FreeText 批注缺少页面范围" }
        val appearance = PDAppearanceStream(document).apply {
            setResources(PDResources())
            setBBox(PDRectangle(rect.width, rect.height))
        }
        PDAppearanceContentStream(appearance).use { stream ->
            val alpha = PDExtendedGraphicsState().apply {
                strokingAlphaConstant = opacity
                nonStrokingAlphaConstant = opacity
            }
            stream.setGraphicsStateParameters(alpha)
            stream.setNonStrokingColor(toColor(colorArgb))
            stream.beginText()
            stream.setFont(font, fontSize)
            val padding = 4f
            val lineHeight = fontSize * 1.25f
            val lines = wrapFreeText(font, annotation.contents.orEmpty(), fontSize, rect.width - padding * 2f)
            val maxLines = ((rect.height - padding * 2f) / lineHeight).toInt().coerceAtLeast(1)
            lines.take(maxLines).forEachIndexed { index, line ->
                stream.setTextMatrix(Matrix.getTranslateInstance(padding, rect.height - padding - fontSize - index * lineHeight))
                stream.showText(line)
            }
            stream.endText()
        }
        annotation.setAppearance(PDAppearanceDictionary().apply { setNormalAppearance(appearance) })
    }

    private fun rebuildStampAppearance(
        document: PDDocument,
        annotation: PDAnnotationRubberStamp,
        kind: StampKind,
        font: PDFont,
        colorArgb: Int,
        opacity: Float,
    ) {
        val rect = requireNotNull(annotation.rectangle) { "图章缺少页面范围" }
        val appearance = PDAppearanceStream(document).apply {
            setResources(PDResources())
            setBBox(PDRectangle(rect.width, rect.height))
        }
        PDAppearanceContentStream(appearance).use { stream ->
            val alpha = PDExtendedGraphicsState().apply {
                strokingAlphaConstant = opacity
                nonStrokingAlphaConstant = opacity
            }
            stream.setGraphicsStateParameters(alpha)
            stream.setStrokingColor(toColor(colorArgb))
            stream.setLineWidth(2f)
            stream.addRect(1f, 1f, (rect.width - 2f).coerceAtLeast(1f), (rect.height - 2f).coerceAtLeast(1f))
            stream.stroke()
            val fontSize = (rect.height * 0.32f).coerceIn(10f, 24f)
            val textWidth = runCatching { font.getStringWidth(kind.appearanceText) / 1000f * fontSize }.getOrDefault(0f)
            stream.setNonStrokingColor(toColor(colorArgb))
            stream.beginText()
            stream.setFont(font, fontSize)
            stream.setTextMatrix(
                Matrix.getTranslateInstance(
                    ((rect.width - textWidth) / 2f).coerceAtLeast(4f),
                    (rect.height - fontSize) / 2f,
                ),
            )
            stream.showText(kind.appearanceText)
            stream.endText()
        }
        annotation.setAppearance(PDAppearanceDictionary().apply { setNormalAppearance(appearance) })
    }

    private fun wrapFreeText(font: PDFont, text: String, fontSize: Float, maxWidth: Float): List<String> {
        if (maxWidth <= 1f) return listOf(text)
        val result = mutableListOf<String>()
        text.lines().forEach { paragraph ->
            if (paragraph.isEmpty()) {
                result += ""
                return@forEach
            }
            val line = StringBuilder()
            paragraph.forEach { character ->
                val candidate = line.toString() + character
                val width = runCatching { font.getStringWidth(candidate) / 1000f * fontSize }
                    .getOrElse { Float.MAX_VALUE }
                if (line.isNotEmpty() && width > maxWidth) {
                    result += line.toString()
                    line.clear()
                }
                line.append(character)
            }
            if (line.isNotEmpty()) result += line.toString()
        }
        return result.ifEmpty { listOf("") }
    }

    private fun toColor(argb: Int): PDColor {
        val components = floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        )
        return PDColor(components, PDDeviceRGB.INSTANCE)
    }

    private fun PDColor?.toArgbOrNull(): Int? = this?.let { color ->
        runCatching { 0xFF000000.toInt() or color.toRGB() }.getOrNull()
    }

    private fun defaultColorFor(subtype: String?): Int = when (subtype) {
        PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT, PDAnnotationText.SUB_TYPE -> 0xFFFFD54F.toInt()
        PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE -> 0xFF1565C0.toInt()
        PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT -> 0xFFD32F2F.toInt()
        else -> 0xFF202124.toInt()
    }

    private fun Int.toCssColor(): String = String.format("#%06X", this and 0xFFFFFF)

    private fun Int.toDefaultAppearance(fontSize: Float): String {
        val red = ((this shr 16) and 0xFF) / 255f
        val green = ((this shr 8) and 0xFF) / 255f
        val blue = (this and 0xFF) / 255f
        return "/Helv $fontSize Tf $red $green $blue rg"
    }

    private fun PDAnnotation.isEditable(): Boolean = isManagedAnnotation() && !isReadOnly && !isLocked && !isLockedContents

    /** long: Widget、Link 和附件等批注还关联表单树或动作字典，必须交给专用编辑器维护，不能只改页面批注数组。 */
    private fun PDAnnotation.isManagedAnnotation(): Boolean = when (this) {
        is PDAnnotationText,
        is PDAnnotationTextMarkup,
        is PDAnnotationRubberStamp,
        -> true
        is PDAnnotationMarkup -> subtype == PDAnnotationMarkup.SUB_TYPE_FREETEXT ||
            subtype == PDAnnotationMarkup.SUB_TYPE_INK
        else -> false
    }

    private fun PDAnnotation.freeTextFontSize(): Float? {
        if (subtype != PDAnnotationMarkup.SUB_TYPE_FREETEXT) return null
        val appearance = (this as? PDAnnotationMarkup)?.defaultAppearance ?: return DEFAULT_FREE_TEXT_FONT_SIZE
        return FREE_TEXT_FONT_SIZE_REGEX.find(appearance)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it in 6f..96f }
            ?: DEFAULT_FREE_TEXT_FONT_SIZE
    }

    private fun FloatArray.shiftPairs(dx: Float, dy: Float): FloatArray = copyOf().also { shifted ->
        var index = 0
        while (index + 1 < shifted.size) {
            shifted[index] += dx
            shifted[index + 1] += dy
            index += 2
        }
    }
}

private const val DEFAULT_FREE_TEXT_FONT_SIZE = 12f
private val FREE_TEXT_FONT_SIZE_REGEX = Regex("""/[^\s]+\s+([0-9]+(?:\.[0-9]+)?)\s+Tf""")
