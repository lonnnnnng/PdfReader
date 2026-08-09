package com.loooong.reader.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.roundToInt

// long: 单个元素的页面空间编辑请求；默认以左下角缩放，画布直接操作可显式传入中心锚点并旋转。
data class EditRequest(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    val fillArgb: Int? = null,
    val strokeArgb: Int? = null,
    val newText: String? = null,
    val fontEntryId: String? = null,
    val fontSize: Float? = null,
    val characterSpacing: Float? = null,
    val lineLeading: Float? = null,
    val opacity: Float? = null,
    val replacementImageBytes: ByteArray? = null,
    val imageCrop: ImageCropRequest? = null,
    val lineWidth: Float? = null,
    val lineCap: Int? = null,
    val lineJoin: Int? = null,
    val miterLimit: Float? = null,
)

data class ImageCropRequest(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isValid: Boolean
        get() = left >= 0f && top >= 0f && right >= 0f && bottom >= 0f &&
            left + right < 1f && top + bottom < 1f

    val hasCrop: Boolean get() = left > 0f || top > 0f || right > 0f || bottom > 0f
}

class TextInsertRequest(
    val text: String,
    val x: Float,
    val y: Float,
    val fontSize: Float,
    val font: PDFont,
    val fillArgb: Int,
)

class ImageInsertRequest(
    val bytes: ByteArray,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

enum class ShapeKind { RECTANGLE, LINE }

data class ShapeInsertRequest(
    val kind: ShapeKind,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fillArgb: Int? = null,
    val strokeArgb: Int? = null,
    val lineWidth: Float = 2f,
    val lineCap: Int = 0,
    val lineJoin: Int = 0,
    val miterLimit: Float = 10f,
)

/** long: 多选复制只传递同一内容流中的原始 token 和资源字典，粘贴时由目标页面统一重绑定资源。 */
data class RawPasteNode(
    val tokens: List<Any>,
    val sourceResources: PDResources?,
)

// What the edit form may offer for a given node.
class EditCaps(
    val canGeom: Boolean,
    val canFill: Boolean,
    val canStroke: Boolean,
    val canText: Boolean,
    val canOpacity: Boolean,
    val canReplaceImage: Boolean,
    val canCropImage: Boolean,
    val canStrokeStyle: Boolean,
)

sealed class EditResult {
    class Applied(val tokens: List<Any>) : EditResult()
    object NoChange : EditResult()
    object Degenerate : EditResult()
    class TextEncodeFailed(val chars: String? = null) : EditResult()
}

class StreamEditSnapshot internal constructor(
    val owner: StreamOwner,
    val content: ByteArray,
    internal val explicitResources: COSDictionary?,
    internal val formPath: List<PDFormXObject>,
    internal val pageRotation: Int? = null,
    internal val annotations: COSArray? = null,
)

typealias PageEditSnapshot = StreamEditSnapshot

object ElementEditor {

    private data class RedactionImageResource(
        val name: COSName,
        val image: PDImageXObject,
    )

    private data class RedactionPlan(
        val stream: ParsedStream,
        val ranges: List<IntRange>,
        val imageResources: List<RedactionImageResource>,
        val markedContentPropertyIndexes: Set<Int>,
    )

    private data class MarkedContentFrame(
        val beginOperatorIndex: Int,
        val propertyIndex: Int?,
        val usesNamedProperties: Boolean,
        val hasProperties: Boolean,
        var endOperatorIndex: Int = -1,
    )

    /** long: 文档级功能重写页面 token 时统一走现有提交链，保证页面树、资源和增量写入遍历标记完整更新。 */
    internal fun replacePageContent(document: PDDocument, page: PDPage, tokens: List<Any>) {
        writeStream(
            document = document,
            page = page,
            stream = ParsedStream(StreamOwner.Page(page), tokens, page.resources),
            tokens = tokens,
        )
    }

    // Drops tokens [start, end] and rewrites the page content stream. Everything
    // outside the range is preserved byte-equivalent through ContentStreamWriter.
    fun deleteRange(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        start: Int,
        end: Int,
    ): List<Any> {
        val kept = ArrayList<Any>(tokens.size)
        for (i in tokens.indices) {
            if (i < start || i > end) kept.add(tokens[i])
        }
        writeStream(
            document,
            page,
            ParsedStream(StreamOwner.Page(page), kept, page.resources),
            kept,
        )
        return kept
    }

    fun deleteNode(document: PDDocument, page: PDPage, node: DrawNode): List<Any> {
        require(!node.locked) { "元素已锁定" }
        val parsedStream = requireNotNull(node.stream) { "Element has no content-stream owner" }
        val kept = ArrayList<Any>(parsedStream.tokens.size)
        for (i in parsedStream.tokens.indices) {
            if (i < node.startIndex || i > node.endIndex) kept.add(parsedStream.tokens[i])
        }
        writeStream(document, page, parsedStream, kept)
        return kept
    }

    /** long: 多选删除一次性重写页面流，避免多个节点删除时旧 token 索引逐次失效。 */
    fun deleteNodes(document: PDDocument, page: PDPage, nodes: List<DrawNode>): EditResult {
        if (nodes.isEmpty() || nodes.any { it.locked }) return EditResult.NoChange
        val stream = nodes.first().stream ?: return EditResult.NoChange
        if (stream.owner !is StreamOwner.Page || nodes.any { it.stream !== stream }) return EditResult.NoChange
        val ranges = nodes.map { it.startIndex..it.endIndex }.sortedBy { it.first }
        if (ranges.any { it.first < 0 || it.last >= stream.tokens.size } ||
            ranges.zipWithNext().any { (left, right) -> left.last >= right.first }
        ) {
            return EditResult.NoChange
        }
        val kept = ArrayList<Any>(stream.tokens.size)
        var rangeIndex = 0
        for (index in stream.tokens.indices) {
            while (rangeIndex < ranges.size && index > ranges[rangeIndex].last) rangeIndex++
            if (rangeIndex >= ranges.size || index !in ranges[rangeIndex]) kept.add(stream.tokens[index])
        }
        writeStream(document, page, stream, kept)
        return EditResult.Applied(kept)
    }

    /**
     * long: 永久遮盖在同一次内容流重写中删除目标操作符并追加不透明矩形；删除与覆盖必须原子完成，
     * 避免只画黑框却仍能复制、搜索或恢复下面的敏感内容。
     */
    fun redactNodes(
        document: PDDocument,
        page: PDPage,
        nodes: List<DrawNode>,
        fillArgb: Int = 0xFF000000.toInt(),
        padding: Float = 1f,
    ): EditResult {
        if ((fillArgb ushr 24) != 0xFF || !padding.isFinite() || padding < 0f) return EditResult.NoChange
        val plan = buildRedactionPlan(document, page, nodes) ?: return EditResult.NoChange
        val stream = plan.stream

        val kept = ArrayList<Any>(stream.tokens.size)
        var rangeIndex = 0
        for (index in stream.tokens.indices) {
            while (rangeIndex < plan.ranges.size && index > plan.ranges[rangeIndex].last) rangeIndex++
            if (rangeIndex >= plan.ranges.size || index !in plan.ranges[rangeIndex]) {
                val token = stream.tokens[index]
                kept.add(
                    if (index in plan.markedContentPropertyIndexes) {
                        sanitizeMarkedContentProperties(token as COSDictionary)
                    } else {
                        token
                    },
                )
            }
        }
        if (!removeUnreferencedRedactionImages(stream, kept, plan.imageResources)) return EditResult.NoChange

        val out = ArrayList<Any>(kept.size + nodes.size * 8 + 8)
        // long: 原内容统一包进 q/Q，确保其末尾遗留的透明度、混合模式和裁剪路径不会污染遮盖矩形。
        out.add(Operator.getOperator("q"))
        out.addAll(kept)
        out.add(Operator.getOperator("Q"))
        out.add(Operator.getOperator("q"))
        appendOpaqueRedactionState(out, stream)
        appendColor(out, fillArgb, stroke = false)
        // long: OCR 节点只提供待删除的隐藏文本范围，遮盖外观仍以用户实际选择的可见对象为准，避免重复叠加黑框。
        nodes.filterNot(DrawNode::generatedOcr).mapNotNull(DrawNode::bounds)
            .sortedWith(compareBy<Bounds>({ it.minY }, { it.minX }))
            .forEach { bounds ->
                val left = bounds.minX - padding
                val bottom = bounds.minY - padding
                val width = bounds.width + padding * 2f
                val height = bounds.height + padding * 2f
                if (!left.isFinite() || !bottom.isFinite() || width <= 0f || height <= 0f) {
                    return EditResult.NoChange
                }
                out.add(COSFloat(left)); out.add(COSFloat(bottom))
                out.add(COSFloat(width)); out.add(COSFloat(height))
                out.add(Operator.getOperator("re"))
                out.add(Operator.getOperator("f"))
            }
        out.add(Operator.getOperator("Q"))
        writeStream(document, page, stream, out)
        return EditResult.Applied(out)
    }

    fun canRedactNodes(document: PDDocument, page: PDPage, nodes: List<DrawNode>): Boolean =
        buildRedactionPlan(document, page, nodes) != null

    private fun buildRedactionPlan(document: PDDocument, page: PDPage, nodes: List<DrawNode>): RedactionPlan? {
        if (nodes.isEmpty() || nodes.any { it.locked || it.kind == NodeKind.GROUP }) return null
        // long: Tagged PDF 的结构树位于页面内容流之外；尚未能精确回写对应结构节点时必须拒绝，避免 /Alt 泄露。
        if (document.documentCatalog.structureTreeRoot != null) return null
        val stream = nodes.first().stream ?: return null
        val owner = stream.owner as? StreamOwner.Page ?: return null
        if (owner.page.cosObject !== page.cosObject || nodes.any { node ->
                node.stream !== stream || node.clipRule != null || node.clipBounds != null ||
                    node.bounds?.isValid != true
            }
        ) {
            return null
        }
        if (!hasBalancedGraphicsState(stream.tokens)) return null
        val ranges = nodes.map { it.startIndex..it.endIndex }.sortedBy { it.first }
        if (ranges.any { it.first < 0 || it.last >= stream.tokens.size } ||
            ranges.zipWithNext().any { (left, right) -> left.last >= right.first }
        ) {
            return null
        }
        val markedContentPropertyIndexes = markedContentPropertiesForRedaction(stream.tokens, ranges) ?: return null
        val imageResources = nodes.mapNotNull { node ->
            if (node.kind != NodeKind.IMAGE || node.inlineImage) return@mapNotNull null
            val name = node.xObjectResourceName ?: return null
            val image = runCatching { stream.resources?.getXObject(name) }.getOrNull() as? PDImageXObject
                ?: return null
            RedactionImageResource(name, image)
        }
        return RedactionPlan(stream, ranges, imageResources, markedContentPropertyIndexes)
    }

    private fun markedContentPropertiesForRedaction(
        tokens: List<Any>,
        ranges: List<IntRange>,
    ): Set<Int>? {
        val open = ArrayDeque<MarkedContentFrame>()
        val closed = ArrayList<MarkedContentFrame>()
        tokens.forEachIndexed { index, token ->
            val operator = token as? Operator ?: return@forEachIndexed
            when (operator.name) {
                "BMC" -> open.addLast(
                    MarkedContentFrame(
                        beginOperatorIndex = index,
                        propertyIndex = null,
                        usesNamedProperties = false,
                        hasProperties = false,
                    ),
                )
                "BDC" -> {
                    val property = tokens.getOrNull(index - 1)
                    open.addLast(
                        MarkedContentFrame(
                            beginOperatorIndex = index,
                            propertyIndex = (index - 1).takeIf { property is COSDictionary },
                            usesNamedProperties = property is COSName,
                            hasProperties = true,
                        ),
                    )
                }
                "EMC" -> {
                    if (open.isEmpty()) return null
                    closed += open.removeLast().also { it.endOperatorIndex = index }
                }
            }
        }
        if (open.isNotEmpty()) return null

        val properties = LinkedHashSet<Int>()
        for (frame in closed) {
            val overlapping = ranges.filter { range ->
                range.first <= frame.endOperatorIndex && range.last >= frame.beginOperatorIndex
            }
            if (overlapping.isEmpty()) continue
            if (overlapping.any { range ->
                    range.first <= frame.beginOperatorIndex || range.last >= frame.endOperatorIndex
                }
            ) {
                return null
            }
            // long: 命名属性来自共享 /Properties 资源；尚未做引用隔离时必须失败关闭，不能清空其他内容共用的替代文本。
            if (!frame.hasProperties) continue
            if (frame.usesNamedProperties || frame.propertyIndex == null) return null
            properties += frame.propertyIndex
        }
        return properties
    }

    private fun sanitizeMarkedContentProperties(token: COSDictionary): COSDictionary =
        COSDictionary(token).apply {
            // long: 可见文字删除后同步移除辅助技术使用的替代文字，避免搜索、复制或无障碍读取仍泄露敏感内容。
            listOf(COSName.ACTUAL_TEXT, COSName.ALT, COSName.getPDFName("E")).forEach(::removeItem)
        }

    private fun hasBalancedGraphicsState(tokens: List<Any>): Boolean {
        var depth = 0
        tokens.filterIsInstance<Operator>().forEach { operator ->
            when (operator.name) {
                "q" -> depth++
                "Q" -> {
                    if (depth == 0) return false
                    depth--
                }
            }
        }
        return depth == 0
    }

    private fun removeUnreferencedRedactionImages(
        stream: ParsedStream,
        keptTokens: List<Any>,
        candidates: List<RedactionImageResource>,
    ): Boolean {
        if (candidates.isEmpty()) return true
        val resources = stream.resources ?: return false
        val removable = ArrayList<COSName>()
        candidates.distinctBy { it.name.name }.forEach { candidate ->
            val referenced = runCatching {
                isXObjectReferenced(
                    tokens = keptTokens,
                    resources = resources,
                    target = candidate.image.cosObject,
                    visitedForms = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>()),
                    depth = 0,
                )
            }.getOrElse { return false }
            if (!referenced) removable += candidate.name
        }
        if (removable.isEmpty()) return true
        val copied = copyResourcesForEdit(stream)
        val xObjects = copied.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: return false
        removable.forEach(xObjects::removeItem)
        xObjects.isNeedToBeUpdated = true
        return true
    }

    private fun isXObjectReferenced(
        tokens: List<Any>,
        resources: PDResources,
        target: COSStream,
        visitedForms: MutableSet<COSStream>,
        depth: Int,
    ): Boolean {
        check(depth <= 32) { "Form XObject 引用层级过深" }
        for (index in 0 until tokens.lastIndex) {
            val name = tokens[index] as? COSName ?: continue
            if ((tokens[index + 1] as? Operator)?.name != "Do") continue
            val xObject = requireNotNull(resources.getXObject(name)) { "XObject 资源无法解析" }
            when (xObject) {
                is PDImageXObject -> if (xObject.cosObject === target) return true
                is PDFormXObject -> {
                    val formStream = xObject.cosObject
                    if (!visitedForms.add(formStream)) continue
                    try {
                        val parser = PDFStreamParser(xObject)
                        parser.parse()
                        val childTokens = parser.tokens
                        val childResources = xObject.resources ?: resources
                        if (isXObjectReferenced(childTokens, childResources, target, visitedForms, depth + 1)) {
                            return true
                        }
                    } finally {
                        // long: 递归集合只保护当前调用路径，同一 Form 在不同资源上下文中复用时仍需分别核查。
                        visitedForms.remove(formStream)
                    }
                }
            }
        }
        return false
    }

    private fun appendOpaqueRedactionState(out: MutableList<Any>, stream: ParsedStream) {
        val resources = copyResourcesForEdit(stream)
        val state = PDExtendedGraphicsState().apply {
            strokingAlphaConstant = 1f
            nonStrokingAlphaConstant = 1f
            blendMode = BlendMode.NORMAL
            strokingOverprintControl = false
            nonStrokingOverprintControl = false
            overprintMode = 0f
            alphaSourceFlag = false
            // long: /SMask /None 显式清除之前 gs 遗留的软遮罩，否则黑框仍可能被透明蒙版削弱。
            cosObject.setItem(COSName.SMASK, COSName.NONE)
        }
        out.add(resources.add(state))
        out.add(Operator.getOperator("gs"))
    }

    /**
     * long: 锁定写入 PDF 自有的 marked-content 包装，重新打开文档后仍可识别；
     * 解锁只删除成对的 ReaderLock 标记，元素原始操作符和图形状态保持不变。
     */
    fun setLocked(
        document: PDDocument,
        page: PDPage,
        node: DrawNode,
        locked: Boolean,
    ): EditResult {
        if (locked == node.locked) return EditResult.NoChange
        val targetStream = (if (locked) node.stream else node.locks.lastOrNull()?.stream)
            ?: return EditResult.NoChange
        val tokens = targetStream.tokens
        val out = if (locked) {
            if (node.stream !== targetStream || node.startIndex !in tokens.indices ||
                node.endIndex !in node.startIndex until tokens.size
            ) {
                return EditResult.NoChange
            }
            ArrayList<Any>(tokens.size + 3).apply {
                addAll(tokens.subList(0, node.startIndex))
                add(COSName.getPDFName("ReaderLock"))
                add(Operator.getOperator("BMC"))
                addAll(tokens.subList(node.startIndex, node.endIndex + 1))
                add(Operator.getOperator("EMC"))
                addAll(tokens.subList(node.endIndex + 1, tokens.size))
            }
        } else {
            val lock = node.locks.lastOrNull() ?: return EditResult.NoChange
            if (lock.stream !== targetStream || lock.wrapperStart !in tokens.indices ||
                lock.beginOperatorIndex !in lock.wrapperStart until tokens.size ||
                lock.wrapperEnd !in (lock.beginOperatorIndex + 1) until tokens.size ||
                !isOp(tokens[lock.beginOperatorIndex], "BMC") &&
                !isOp(tokens[lock.beginOperatorIndex], "BDC") ||
                !isOp(tokens[lock.wrapperEnd], "EMC")
            ) {
                return EditResult.NoChange
            }
            ArrayList<Any>(tokens.size - (lock.beginOperatorIndex - lock.wrapperStart + 2)).apply {
                for (index in tokens.indices) {
                    if (index !in lock.wrapperStart..lock.beginOperatorIndex && index != lock.wrapperEnd) {
                        add(tokens[index])
                    }
                }
            }
        }
        writeStream(document, page, targetStream, out)
        return EditResult.Applied(out)
    }

    fun insertText(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        request: TextInsertRequest,
    ): EditResult {
        if (request.text.isEmpty() || request.fontSize <= 0f) return EditResult.NoChange
        val lines = request.text.split('\n')
        val encodedLines = try {
            lines.map { request.font.encode(it) }
        } catch (_: Exception) {
            return EditResult.TextEncodeFailed(unsupportedChars(request.font, request.text))
        }
        val resources = copyPageResources(page)
        val fontName = resources.add(request.font)
        val out = ArrayList<Any>(tokens.size + 18)
        out.addAll(tokens)
        out.add(Operator.getOperator("q"))
        out.add(Operator.getOperator("BT"))
        out.add(fontName)
        out.add(COSFloat(request.fontSize))
        out.add(Operator.getOperator("Tf"))
        out.add(COSFloat(request.fontSize))
        out.add(Operator.getOperator("TL"))
        appendColor(out, request.fillArgb, false)
        out.add(COSFloat(1f)); out.add(COSFloat(0f));
        out.add(COSFloat(0f)); out.add(COSFloat(1f))
        out.add(COSFloat(request.x)); out.add(COSFloat(request.y))
        out.add(Operator.getOperator("Tm"))
        encodedLines.forEachIndexed { index, bytes ->
            out.add(COSString(bytes))
            out.add(Operator.getOperator("Tj"))
            if (index != encodedLines.lastIndex) out.add(Operator.getOperator("T*"))
        }
        out.add(Operator.getOperator("ET"))
        out.add(Operator.getOperator("Q"))
        writeStream(document, page, ParsedStream(StreamOwner.Page(page), tokens, resources), out)
        return EditResult.Applied(out)
    }

    fun insertImage(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        request: ImageInsertRequest,
    ): EditResult {
        if (request.bytes.isEmpty() || request.width <= 0f || request.height <= 0f) {
            return EditResult.NoChange
        }
        val image = PDImageXObject.createFromByteArray(document, request.bytes, "inserted-image")
        val resources = copyPageResources(page)
        val imageName = resources.add(image)
        val out = ArrayList<Any>(tokens.size + 12)
        out.addAll(tokens)
        out.add(Operator.getOperator("q"))
        out.add(COSFloat(request.width)); out.add(COSFloat(0f))
        out.add(COSFloat(0f)); out.add(COSFloat(request.height))
        out.add(COSFloat(request.x)); out.add(COSFloat(request.y))
        out.add(Operator.getOperator("cm"))
        out.add(imageName)
        out.add(Operator.getOperator("Do"))
        out.add(Operator.getOperator("Q"))
        writeStream(document, page, ParsedStream(StreamOwner.Page(page), tokens, resources), out)
        return EditResult.Applied(out)
    }

    /** long: 基础图形以独立 q/Q 块追加，后续可安全选择、复制、调层级并作为一次操作撤销。 */
    fun insertShape(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        request: ShapeInsertRequest,
    ): EditResult {
        val finiteValues = listOf(request.x, request.y, request.width, request.height, request.lineWidth, request.miterLimit)
        if (finiteValues.any { !it.isFinite() } || request.lineWidth <= 0f || request.miterLimit < 1f) {
            return EditResult.NoChange
        }
        val validGeometry = when (request.kind) {
            ShapeKind.RECTANGLE -> request.width > 0f && request.height > 0f &&
                (request.fillArgb != null || request.strokeArgb != null)
            ShapeKind.LINE -> (request.width != 0f || request.height != 0f) && request.strokeArgb != null
        }
        if (!validGeometry) return EditResult.NoChange

        val resources = copyPageResources(page)
        val out = ArrayList<Any>(tokens.size + 28)
        out.addAll(tokens)
        out.add(Operator.getOperator("q"))
        request.fillArgb?.let { appendColor(out, it, false) }
        request.strokeArgb?.let {
            appendColor(out, it, true)
            appendStrokeStyle(out, request.lineWidth, request.lineCap, request.lineJoin, request.miterLimit)
        }
        when (request.kind) {
            ShapeKind.RECTANGLE -> {
                out.add(COSFloat(request.x)); out.add(COSFloat(request.y))
                out.add(COSFloat(request.width)); out.add(COSFloat(request.height))
                out.add(Operator.getOperator("re"))
                val paint = when {
                    request.fillArgb != null && request.strokeArgb != null -> "B"
                    request.fillArgb != null -> "f"
                    else -> "S"
                }
                out.add(Operator.getOperator(paint))
            }
            ShapeKind.LINE -> {
                out.add(COSFloat(request.x)); out.add(COSFloat(request.y))
                out.add(Operator.getOperator("m"))
                out.add(COSFloat(request.x + request.width)); out.add(COSFloat(request.y + request.height))
                out.add(Operator.getOperator("l"))
                out.add(Operator.getOperator("S"))
            }
        }
        out.add(Operator.getOperator("Q"))
        writeStream(document, page, ParsedStream(StreamOwner.Page(page), tokens, resources), out)
        return EditResult.Applied(out)
    }

    /**
     * long: 复制页面级路径、图片或完整文本对象时，重新绑定资源并以平移矩阵追加到内容流末尾。
     * 文本运行（BT/ET 内的单个 Tj/TJ）不应被单独包在 q/Q 中，调用方需改走 insertText。
     */
    fun pasteNode(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        copiedTokens: List<Any>,
        sourceResources: PDResources?,
        dx: Float,
        dy: Float,
    ): EditResult {
        if (copiedTokens.isEmpty() || !dx.isFinite() || !dy.isFinite()) return EditResult.NoChange
        val resources = copyPageResources(page)
        val remapped = remapResourceNames(copiedTokens, sourceResources, resources)
        val out = ArrayList<Any>(tokens.size + remapped.size + 12)
        out.addAll(tokens)
        out.add(Operator.getOperator("q"))
        out.add(COSFloat(1f)); out.add(COSFloat(0f))
        out.add(COSFloat(0f)); out.add(COSFloat(1f))
        out.add(COSFloat(dx)); out.add(COSFloat(dy))
        out.add(Operator.getOperator("cm"))
        out.addAll(remapped)
        out.add(Operator.getOperator("Q"))
        writeStream(document, page, ParsedStream(StreamOwner.Page(page), tokens, resources), out)
        return EditResult.Applied(out)
    }

    /** long: 多个节点共用一个平移包装和一次内容流写入，组内相对位置及单步撤销边界不会被逐项粘贴打散。 */
    fun pasteNodes(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        copiedNodes: List<RawPasteNode>,
        dx: Float,
        dy: Float,
    ): EditResult {
        if (copiedNodes.isEmpty() || !dx.isFinite() || !dy.isFinite()) return EditResult.NoChange
        val resources = copyPageResources(page)
        val out = ArrayList<Any>(tokens.size + copiedNodes.sumOf { it.tokens.size } + 12)
        out.addAll(tokens)
        out.add(Operator.getOperator("q"))
        out.add(COSFloat(1f)); out.add(COSFloat(0f))
        out.add(COSFloat(0f)); out.add(COSFloat(1f))
        out.add(COSFloat(dx)); out.add(COSFloat(dy))
        out.add(Operator.getOperator("cm"))
        copiedNodes.forEach { copied ->
            out.addAll(remapResourceNames(copied.tokens, copied.sourceResources, resources))
        }
        out.add(Operator.getOperator("Q"))
        writeStream(document, page, ParsedStream(StreamOwner.Page(page), tokens, resources), out)
        return EditResult.Applied(out)
    }

    private fun writeStream(
        document: PDDocument,
        page: PDPage,
        stream: ParsedStream,
        tokens: List<Any>,
    ) {
        when (val owner = stream.owner) {
            is StreamOwner.Page -> {
                val stream = PDStream(document)
                stream.createOutputStream(COSName.FLATE_DECODE).use { out ->
                    ContentStreamWriter(out).writeTokens(tokens)
                }
                commitPage(document, owner.page, stream)
            }
            is StreamOwner.Form -> {
                if (stream.invocationPath.isEmpty()) {
                    owner.form.cosObject.createOutputStream().use { out ->
                        ContentStreamWriter(out).writeTokens(tokens)
                    }
                    commitForm(document, page, stream.formPath.ifEmpty { listOf(owner.form) })
                } else {
                    writeIsolatedFormEdit(document, stream, tokens)
                }
            }
        }
    }

    /**
     * long: Form 可能在同页或跨页复用。编辑目标实例时逐层复制 Form 调用链，
     * 只改写页面上命中的那个 /Do 操作数，其他调用点继续引用原对象。
     */
    private fun writeIsolatedFormEdit(
        document: PDDocument,
        stream: ParsedStream,
        tokens: List<Any>,
    ) {
        val owner = stream.owner as? StreamOwner.Form ?: return
        var child = cloneForm(document, owner.form, stream.resources, tokens)

        for (invocation in stream.invocationPath.asReversed()) {
            val parent = invocation.parentStream
            val resources = copyResourcesForIsolation(parent.resources)
            val childName = resources.add(child)
            val rewritten = parent.tokens.toMutableList()
            check(invocation.operandIndex in rewritten.indices) { "Form 调用位置已经失效" }
            check(invocation.operatorIndex in rewritten.indices &&
                isOp(rewritten[invocation.operatorIndex], "Do")
            ) { "Form 调用操作符已经失效" }
            val originalName = rewritten[invocation.operandIndex] as? COSName
            check(originalName?.name == invocation.resourceName.name) { "Form 资源引用已经变化" }
            rewritten[invocation.operandIndex] = childName

            when (val parentOwner = parent.owner) {
                is StreamOwner.Form -> {
                    child = cloneForm(document, parentOwner.form, resources, rewritten)
                }
                is StreamOwner.Page -> {
                    parentOwner.page.resources = resources
                    val pageStream = PDStream(document)
                    pageStream.createOutputStream(COSName.FLATE_DECODE).use { out ->
                        ContentStreamWriter(out).writeTokens(rewritten)
                    }
                    commitPage(document, parentOwner.page, pageStream)
                    return
                }
            }
        }
        error("Form 调用链没有回到页面")
    }

    private fun cloneForm(
        document: PDDocument,
        source: PDFormXObject,
        resources: PDResources?,
        tokens: List<Any>,
    ): PDFormXObject {
        val clone = PDFormXObject(document)
        val excluded = setOf(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.RESOURCES)
        for (key in source.cosObject.keySet()) {
            if (key !in excluded) clone.cosObject.setItem(key, source.cosObject.getItem(key))
        }
        clone.resources = copyResourcesForIsolation(resources)
        clone.cosObject.createOutputStream(COSName.FLATE_DECODE).use { out ->
            ContentStreamWriter(out).writeTokens(tokens)
        }
        clone.cosObject.setNeedToBeUpdated(true)
        markResources(clone.resources)
        return clone
    }

    private fun copyResourcesForIsolation(resources: PDResources?): PDResources {
        val dictionary = resources?.cosObject?.let(::COSDictionary) ?: COSDictionary()
        (resources?.cosObject?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)?.let {
            dictionary.setItem(COSName.XOBJECT, COSDictionary(it))
        }
        return PDResources(dictionary)
    }

    // 同时记录实际内容流及其显式资源，Form 内字体替换也必须能够完整撤销。
    fun snapshot(page: PDPage): PageEditSnapshot = snapshot(
        ParsedStream(StreamOwner.Page(page), emptyList(), page.resources),
    )

    fun snapshot(stream: ParsedStream): StreamEditSnapshot = StreamEditSnapshot(
        owner = stream.owner,
        content = readContent(stream.owner),
        explicitResources = ownerDictionary(stream.owner)
            .getDictionaryObject(COSName.RESOURCES) as? COSDictionary,
        formPath = stream.formPath,
        pageRotation = (stream.owner as? StreamOwner.Page)?.page?.rotation,
        annotations = (stream.owner as? StreamOwner.Page)?.page?.let(::copyAnnotations),
    )

    fun snapshot(owner: StreamOwner): StreamEditSnapshot = StreamEditSnapshot(
        owner = owner,
        content = readContent(owner),
        explicitResources = ownerDictionary(owner)
            .getDictionaryObject(COSName.RESOURCES) as? COSDictionary,
        formPath = (owner as? StreamOwner.Form)?.let { listOf(it.form) } ?: emptyList(),
        pageRotation = (owner as? StreamOwner.Page)?.page?.rotation,
        annotations = (owner as? StreamOwner.Page)?.page?.let(::copyAnnotations),
    )

    fun restore(document: PDDocument, page: PDPage, snapshot: StreamEditSnapshot) {
        // 必须通过 setter 同步 PDFBox 包装对象的资源缓存，直接改 COS 字典会继续返回旧资源。
        setResources(snapshot.owner, snapshot.explicitResources?.let(::PDResources))
        if (snapshot.owner is StreamOwner.Page && snapshot.pageRotation != null) {
            snapshot.owner.page.rotation = snapshot.pageRotation
            val pageDictionary = snapshot.owner.page.cosObject
            if (snapshot.annotations == null) {
                pageDictionary.removeItem(COSName.ANNOTS)
            } else {
                pageDictionary.setItem(COSName.ANNOTS, snapshot.annotations)
            }
        }
        when (val owner = snapshot.owner) {
            is StreamOwner.Page -> {
                val stream = PDStream(document)
                stream.createOutputStream(COSName.FLATE_DECODE).use { out -> out.write(snapshot.content) }
                commitPage(document, owner.page, stream)
            }
            is StreamOwner.Form -> {
                owner.form.cosObject.createOutputStream().use { out -> out.write(snapshot.content) }
                commitForm(
                    document,
                    page,
                    snapshot.formPath.ifEmpty { listOf(owner.form) },
                )
            }
        }
    }

    /** long: 页面旋转写入 /Rotate 属性，保持 PDF 内容坐标不变，便于阅读器和其他 PDF 软件一致解释。 */
    fun rotatePage(document: PDDocument, page: PDPage, degrees: Int) {
        val normalized = ((page.rotation + degrees) % 360 + 360) % 360
        page.rotation = normalized
        markPageChain(document, page)
    }

    internal fun markPageUpdated(document: PDDocument, page: PDPage) {
        markPageChain(document, page)
    }

    // Geometry needs a q/cm/Q wrapper, illegal inside a text object, so it is
    // offered on paths, images and whole text objects (BT..ET) but never runs.
    fun capabilities(tokens: List<Any>, node: DrawNode): EditCaps {
        if (node.locked) {
            return EditCaps(
                canGeom = false,
                canFill = false,
                canStroke = false,
                canText = false,
                canOpacity = false,
                canReplaceImage = false,
                canCropImage = false,
                canStrokeStyle = false,
            )
        }
        val textObject = node.kind == NodeKind.TEXT && isOp(tokens.getOrNull(node.startIndex), "BT")
        val textRun = node.kind == NodeKind.TEXT && !textObject
        val path = node.kind == NodeKind.PATH
        val image = node.kind == NodeKind.IMAGE
        val wrappable = path || image || textObject
        val canGeom = wrappable && node.bounds != null && node.ctm?.inverse() != null
        val paint = (tokens.getOrNull(node.endIndex) as? Operator)?.name ?: ""
        val pathFill = path && (paint == "f" || paint == "F" || paint == "f*" ||
            paint.startsWith("B") || paint.startsWith("b"))
        val pathStroke = path && (paint == "S" || paint == "s" ||
            paint.startsWith("B") || paint.startsWith("b"))
        val clips = node.clipRule != null
        // A filled/stroked path always has a paint color (sampled from the page
        // when its color space is unresolved). Text only exposes a color when one
        // was set, so gate text color on that.
        val hasColor = node.colorArgb != null
        val textUsesFill = node.textRenderingMode == null ||
            node.textRenderingMode in setOf(0, 2, 4, 6)
        return EditCaps(
            canGeom = canGeom,
            canFill = !clips && (pathFill || ((textObject || textRun) && hasColor && textUsesFill)),
            canStroke = !clips && pathStroke,
            canText = textRun,
            canOpacity = !clips && (path || image || textObject),
            canReplaceImage = image,
            canCropImage = image,
            canStrokeStyle = !clips && pathStroke,
        )
    }

    fun editElement(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
        substituteFont: PDFont? = null,
        subScale: Float = 1f,
    ): EditResult {
        if (node.locked) return EditResult.NoChange
        val editStream = ParsedStream(StreamOwner.Page(page), tokens, page.resources)
        val result = rebuild(document, tokens, node, request, editStream, substituteFont, subScale)
        if (result is EditResult.Applied) {
            writeStream(document, page, editStream, result.tokens)
        }
        return result
    }

    /** long: 对齐操作按 token 起点倒序改写，所有节点完成后只写流一次，形成单一撤销事务。 */
    fun editElements(
        document: PDDocument,
        page: PDPage,
        edits: List<Pair<DrawNode, EditRequest>>,
    ): EditResult {
        if (edits.isEmpty() || edits.any { it.first.locked }) return EditResult.NoChange
        val source = edits.first().first.stream ?: return EditResult.NoChange
        if (source.owner !is StreamOwner.Page || edits.any { it.first.stream !== source }) {
            return EditResult.NoChange
        }
        var tokens = source.tokens
        var changed = false
        for ((node, request) in edits.sortedByDescending { it.first.startIndex }) {
            val currentStream = ParsedStream(source.owner, tokens, source.resources, source.formPath)
            when (val result = rebuild(document, tokens, node, request, currentStream, null, 1f)) {
                is EditResult.Applied -> {
                    tokens = result.tokens
                    changed = true
                }
                EditResult.NoChange -> Unit
                else -> return result
            }
        }
        if (!changed) return EditResult.NoChange
        writeStream(document, page, source, tokens)
        return EditResult.Applied(tokens)
    }

    /** long: 只移动已经验证为自包含的 q/Q 区间，调用方负责给出同层目标插入位置。 */
    fun reorderRange(
        document: PDDocument,
        page: PDPage,
        stream: ParsedStream,
        start: Int,
        end: Int,
        insertAt: Int,
    ): EditResult {
        if (stream.owner !is StreamOwner.Page || start !in stream.tokens.indices ||
            end !in stream.tokens.indices || start > end || insertAt !in 0..stream.tokens.size
        ) {
            return EditResult.NoChange
        }
        if (containsReaderLock(stream.tokens, start, end)) return EditResult.NoChange
        if (insertAt in (start + 1)..(end + 1)) return EditResult.NoChange
        val block = stream.tokens.subList(start, end + 1).toList()
        val out = ArrayList<Any>(stream.tokens.size)
        out.addAll(stream.tokens.subList(0, start))
        out.addAll(stream.tokens.subList(end + 1, stream.tokens.size))
        val adjusted = if (insertAt > end) insertAt - block.size else insertAt
        if (adjusted !in 0..out.size || adjusted == start) return EditResult.NoChange
        out.addAll(adjusted, block)
        writeStream(document, page, stream, out)
        return EditResult.Applied(out)
    }

    fun editElement(
        document: PDDocument,
        page: PDPage,
        node: DrawNode,
        request: EditRequest,
        substituteFont: PDFont? = null,
        subScale: Float = 1f,
    ): EditResult {
        if (node.locked) return EditResult.NoChange
        val editStream = requireNotNull(node.stream) { "Element has no content-stream owner" }
        val result = rebuild(document, editStream.tokens, node, request, editStream, substituteFont, subScale)
        if (result is EditResult.Applied) {
            writeStream(document, page, editStream, result.tokens)
        }
        return result
    }

    private fun rebuild(
        document: PDDocument,
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
        editStream: ParsedStream,
        substituteFont: PDFont?,
        subScale: Float,
    ): EditResult {
        val textObject = node.kind == NodeKind.TEXT && isOp(tokens.getOrNull(node.startIndex), "BT")
        val textRun = node.kind == NodeKind.TEXT && !textObject
        if (textRun) {
            return rebuildTextRun(tokens, node, request, editStream, substituteFont, subScale)
        }

        val wrappable = node.kind == NodeKind.PATH || node.kind == NodeKind.IMAGE || textObject
        val wantsGeom = request.dx != 0f || request.dy != 0f ||
            request.scaleX != 1f || request.scaleY != 1f || request.rotationDegrees != 0f
        val wantsColor = request.fillArgb != null || request.strokeArgb != null
        val wantsOpacity = request.opacity != null
        val wantsImageReplacement = request.replacementImageBytes != null
        val wantsImageCrop = request.imageCrop != null
        val wantsStrokeStyle = request.lineWidth != null || request.lineCap != null ||
            request.lineJoin != null || request.miterLimit != null
        if (!wrappable ||
            (!wantsGeom && !wantsColor && !wantsOpacity && !wantsImageReplacement &&
                !wantsImageCrop && !wantsStrokeStyle)
        ) {
            return EditResult.NoChange
        }

        // long: 剪裁属于图形状态。若用 q/Q 包裹，Q 会连同刚建立的剪裁一起恢复；
        // long: 因此只在路径前后拼接互逆 cm，让后续内容沿用剪裁但不继承临时 CTM。
        if (node.clipRule != null) {
            if (!wantsGeom || wantsColor || wantsOpacity || wantsImageReplacement ||
                wantsImageCrop || wantsStrokeStyle
            ) {
                return EditResult.NoChange
            }
            val m = node.ctm ?: return EditResult.Degenerate
            val b = node.bounds ?: return EditResult.Degenerate
            val inv = m.inverse() ?: return EditResult.Degenerate
            val pivotX = request.pivotX ?: b.minX
            val pivotY = request.pivotY ?: b.minY
            val pageTransform = Affine.scaleAbout(request.scaleX, request.scaleY, pivotX, pivotY)
                .then(Affine.rotateAbout(request.rotationDegrees, pivotX, pivotY))
                .then(Affine.translate(request.dx, request.dy))
            val localTransform = m.then(pageTransform).then(inv)
            val restoreTransform = localTransform.inverse() ?: return EditResult.Degenerate
            val out = ArrayList<Any>(tokens.size + 16)
            out.addAll(tokens.subList(0, node.startIndex))
            appendMatrix(out, localTransform)
            out.addAll(tokens.subList(node.startIndex, node.endIndex + 1))
            appendMatrix(out, restoreTransform)
            out.addAll(tokens.subList(node.endIndex + 1, tokens.size))
            return EditResult.Applied(out)
        }

        if (node.kind == NodeKind.IMAGE && (node.imageCrop != null || wantsImageCrop)) {
            return rebuildImageCrop(
                document = document,
                tokens = tokens,
                node = node,
                request = request,
                editStream = editStream,
                wantsGeom = wantsGeom,
                wantsOpacity = wantsOpacity,
            )
        }

        val prefix = ArrayList<Any>()
        prefix.add(Operator.getOperator("q"))
        if (wantsGeom) {
            val m = node.ctm ?: return EditResult.Degenerate
            val b = node.bounds ?: return EditResult.Degenerate
            val inv = m.inverse() ?: return EditResult.Degenerate
            val pivotX = request.pivotX ?: b.minX
            val pivotY = request.pivotY ?: b.minY
            val t = Affine.scaleAbout(request.scaleX, request.scaleY, pivotX, pivotY)
                .then(Affine.rotateAbout(request.rotationDegrees, pivotX, pivotY))
                .then(Affine.translate(request.dx, request.dy))
            val c = m.then(t).then(inv)
            prefix.add(COSFloat(c.a)); prefix.add(COSFloat(c.b)); prefix.add(COSFloat(c.c))
            prefix.add(COSFloat(c.d)); prefix.add(COSFloat(c.e)); prefix.add(COSFloat(c.f))
            prefix.add(Operator.getOperator("cm"))
        }
        // Paths/images carry no color operator inside the wrapped range, so a new
        // color sits in the prefix. A text object DOES set colors inside BT/ET,
        // so its fill must be restated before each run to win; done in the body.
        if (!textObject) {
            request.fillArgb?.let { appendColor(prefix, it, false) }
            request.strokeArgb?.let { appendColor(prefix, it, true) }
            if (wantsStrokeStyle && node.kind == NodeKind.PATH) {
                appendStrokeStyle(
                    prefix,
                    request.lineWidth ?: node.lineWidth,
                    request.lineCap ?: node.lineCap,
                    request.lineJoin ?: node.lineJoin,
                    request.miterLimit ?: node.miterLimit,
                )
            }
        }
        request.opacity?.let { appendOpacity(prefix, editStream, it) }

        val replacementBlock = request.replacementImageBytes?.takeIf { node.kind == NodeKind.IMAGE }?.let { bytes ->
            val resources = copyResourcesForEdit(editStream)
            val image = PDImageXObject.createFromByteArray(document, bytes, "replacement-image")
            val name = resources.add(image)
            listOf(name, Operator.getOperator("Do"))
        }

        val out = ArrayList<Any>(tokens.size + prefix.size + 8)
        out.addAll(tokens.subList(0, node.startIndex))
        out.addAll(prefix)
        val blockFill = if (textObject) request.fillArgb else null
        if (blockFill != null) {
            val runStarts = node.children.mapTo(HashSet()) { it.startIndex }
            for (i in node.startIndex..node.endIndex) {
                if (i in runStarts) appendColor(out, blockFill, false)
                out.add(tokens[i])
            }
        } else {
            out.addAll(replacementBlock ?: tokens.subList(node.startIndex, node.endIndex + 1))
        }
        out.add(Operator.getOperator("Q"))
        out.addAll(tokens.subList(node.endIndex + 1, tokens.size))
        return EditResult.Applied(out)
    }

    private fun rebuildImageCrop(
        document: PDDocument,
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
        editStream: ParsedStream,
        wantsGeom: Boolean,
        wantsOpacity: Boolean,
    ): EditResult {
        val existing = node.imageCrop
        val crop = request.imageCrop ?: ImageCropRequest(
            left = existing?.left ?: 0f,
            top = existing?.top ?: 0f,
            right = existing?.right ?: 0f,
            bottom = existing?.bottom ?: 0f,
        )
        if (!crop.isValid) return EditResult.Degenerate

        val replacementBlock = request.replacementImageBytes?.let { bytes ->
            val resources = copyResourcesForEdit(editStream)
            val image = PDImageXObject.createFromByteArray(document, bytes, "replacement-image")
            listOf(resources.add(image), Operator.getOperator("Do"))
        }
        val imageBlock = replacementBlock ?: tokens.subList(node.startIndex, node.endIndex + 1)
        val cropBlock = ArrayList<Any>(imageBlock.size + 24)
        if (crop.hasCrop) {
            val properties = COSDictionary().apply {
                setItem(COSName.getPDFName("L"), COSFloat(crop.left))
                setItem(COSName.getPDFName("T"), COSFloat(crop.top))
                setItem(COSName.getPDFName("R"), COSFloat(crop.right))
                setItem(COSName.getPDFName("B"), COSFloat(crop.bottom))
            }
            cropBlock.add(COSName.getPDFName("ReaderCrop"))
            cropBlock.add(properties)
            cropBlock.add(Operator.getOperator("BDC"))
            cropBlock.add(Operator.getOperator("q"))
            cropBlock.add(COSFloat(crop.left))
            cropBlock.add(COSFloat(crop.bottom))
            cropBlock.add(COSFloat(1f - crop.left - crop.right))
            cropBlock.add(COSFloat(1f - crop.top - crop.bottom))
            cropBlock.add(Operator.getOperator("re"))
            cropBlock.add(Operator.getOperator("W"))
            cropBlock.add(Operator.getOperator("n"))
            cropBlock.addAll(imageBlock)
            cropBlock.add(Operator.getOperator("Q"))
            cropBlock.add(Operator.getOperator("EMC"))
        } else {
            cropBlock.addAll(imageBlock)
        }

        val editStart = existing?.wrapperStart?.takeIf { it in tokens.indices } ?: node.startIndex
        val editEnd = existing?.wrapperEnd?.takeIf { it in editStart until tokens.size } ?: node.endIndex
        val prefix = ArrayList<Any>()
        val suffix = ArrayList<Any>()
        if (wantsGeom || wantsOpacity) {
            prefix.add(Operator.getOperator("q"))
            if (wantsGeom) {
                val m = node.ctm ?: return EditResult.Degenerate
                val b = node.bounds ?: return EditResult.Degenerate
                val inv = m.inverse() ?: return EditResult.Degenerate
                val pivotX = request.pivotX ?: b.minX
                val pivotY = request.pivotY ?: b.minY
                val transform = Affine.scaleAbout(request.scaleX, request.scaleY, pivotX, pivotY)
                    .then(Affine.rotateAbout(request.rotationDegrees, pivotX, pivotY))
                    .then(Affine.translate(request.dx, request.dy))
                appendMatrix(prefix, m.then(transform).then(inv))
            }
            request.opacity?.let { appendOpacity(prefix, editStream, it) }
            suffix.add(Operator.getOperator("Q"))
        }

        // long: 只替换应用自身标记的裁剪包装，外部 PDF 原有剪裁保持不动；四边归零会完整移除包装并恢复原图。
        val out = ArrayList<Any>(tokens.size + cropBlock.size + prefix.size + suffix.size)
        out.addAll(tokens.subList(0, editStart))
        out.addAll(prefix)
        out.addAll(cropBlock)
        out.addAll(suffix)
        out.addAll(tokens.subList(editEnd + 1, tokens.size))
        return EditResult.Applied(out)
    }

    private fun appendMatrix(out: MutableList<Any>, matrix: Affine) {
        out.add(COSFloat(matrix.a)); out.add(COSFloat(matrix.b)); out.add(COSFloat(matrix.c))
        out.add(COSFloat(matrix.d)); out.add(COSFloat(matrix.e)); out.add(COSFloat(matrix.f))
        out.add(Operator.getOperator("cm"))
    }

    // Rewrites one show-text run. When substituteFont is given the run is
    // re-encoded with it: a Tf selecting the new resource is emitted before the
    // run and the original Tf restated after, since q/Q cannot wrap text.
    private fun rebuildTextRun(
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
        editStream: ParsedStream,
        substituteFont: PDFont?,
        subScale: Float,
    ): EditResult {
        val wantsColor = request.fillArgb != null
        val newText = request.newText
        val wantsFontSize = request.fontSize != null
        val wantsCharacterSpacing = request.characterSpacing != null
        val wantsLeading = request.lineLeading != null
        if (!wantsColor && newText == null && !wantsFontSize && !wantsCharacterSpacing && !wantsLeading) {
            return EditResult.NoChange
        }

        val out = ArrayList<Any>(tokens.size + 8)
        out.addAll(tokens.subList(0, node.startIndex))
        request.fillArgb?.let { appendColor(out, it, false) }
        val requestedFontName = if (substituteFont != null) {
            val res = copyResourcesForEdit(editStream)
            ensureFontResource(res, substituteFont)
        } else {
            node.fontResourceName?.takeIf { it.isNotBlank() }?.let(COSName::getPDFName)
        }
        val requestedFontSize = request.fontSize ?: node.fontSize
        if ((substituteFont != null || wantsFontSize) && requestedFontName != null) {
            out.add(requestedFontName)
            out.add(COSFloat(requestedFontSize * if (substituteFont != null) subScale else 1f))
            out.add(Operator.getOperator("Tf"))
        }
        request.characterSpacing?.let {
            out.add(COSFloat(it)); out.add(Operator.getOperator("Tc"))
        }
        request.lineLeading?.let {
            out.add(COSFloat(it)); out.add(Operator.getOperator("TL"))
        }
        if (newText != null) {
            val font = substituteFont ?: node.font ?: return EditResult.TextEncodeFailed()
            val encodedLines = try {
                newText.split('\n').map(font::encode)
            } catch (_: Exception) {
                return EditResult.TextEncodeFailed(unsupportedChars(font, newText))
            }
            val opName = (tokens[node.endIndex] as? Operator)?.name
            if (opName == "TJ" && encodedLines.size == 1) {
                val original = tokens.getOrNull(node.endIndex - 1) as? COSArray
                val rebuilt = original?.let { rebuildTextArray(it, newText, font) }
                    ?: return EditResult.TextEncodeFailed()
                for (i in node.startIndex until node.endIndex - 1) out.add(tokens[i])
                out.add(rebuilt)
                out.add(tokens[node.endIndex])
            } else {
                appendMultilineText(
                    out,
                    tokens,
                    node,
                    opName,
                    encodedLines,
                    request.characterSpacing,
                )
            }
        } else {
            val opName = (tokens[node.endIndex] as? Operator)?.name
            if (opName == "\"" && request.characterSpacing != null) {
                for (i in node.startIndex..node.endIndex) {
                    if (i == node.endIndex - 2) out.add(COSFloat(request.characterSpacing)) else out.add(tokens[i])
                }
            } else {
                out.addAll(tokens.subList(node.startIndex, node.endIndex + 1))
            }
        }
        if ((substituteFont != null || wantsFontSize) && !node.fontResourceName.isNullOrBlank()) {
            out.add(COSName.getPDFName(node.fontResourceName))
            out.add(COSFloat(node.fontSize))
            out.add(Operator.getOperator("Tf"))
        }
        if (wantsCharacterSpacing) {
            out.add(COSFloat(node.characterSpacing)); out.add(Operator.getOperator("Tc"))
        }
        if (wantsLeading) {
            out.add(COSFloat(node.lineLeading)); out.add(Operator.getOperator("TL"))
        }
        out.addAll(tokens.subList(node.endIndex + 1, tokens.size))
        return EditResult.Applied(out)
    }

    private fun appendMultilineText(
        out: MutableList<Any>,
        tokens: List<Any>,
        node: DrawNode,
        originalOperator: String?,
        lines: List<ByteArray>,
        requestedCharacterSpacing: Float?,
    ) {
        val first = lines.firstOrNull() ?: ByteArray(0)
        when (originalOperator) {
            "'" -> {
                out.add(COSString(first)); out.add(Operator.getOperator("'"))
            }
            "\"" -> {
                for (i in node.startIndex until (node.endIndex - 1)) {
                    if (i == node.endIndex - 2 && requestedCharacterSpacing != null) {
                        out.add(COSFloat(requestedCharacterSpacing))
                    } else {
                        out.add(tokens[i])
                    }
                }
                out.add(COSString(first)); out.add(Operator.getOperator("\""))
            }
            else -> {
                out.add(COSString(first)); out.add(Operator.getOperator("Tj"))
            }
        }
        for (line in lines.drop(1)) {
            out.add(Operator.getOperator("T*"))
            out.add(COSString(line))
            out.add(Operator.getOperator("Tj"))
        }
    }

    private fun rebuildTextArray(original: COSArray, newText: String, font: PDFont): COSArray? {
        val stringIndexes = (0 until original.size())
            .filter { original.getObject(it) is COSString }
        if (stringIndexes.isEmpty()) return null

        val weights = stringIndexes.map {
            (original.getObject(it) as COSString).bytes.size.coerceAtLeast(1)
        }
        val totalWeight = weights.sum()
        val codePoints = newText.codePoints().toArray()
        var stringCursor = 0
        var codePointStart = 0
        var cumulativeWeight = 0
        val segments = ArrayList<ByteArray>(stringIndexes.size)

        for (ignored in stringIndexes) {
            cumulativeWeight += weights[stringCursor]
            // 按原字符串片段的占比分配新文字，让 TJ 中的字距调整仍落在相近位置。
            val codePointEnd = if (stringCursor == stringIndexes.lastIndex) {
                codePoints.size
            } else {
                (codePoints.size * cumulativeWeight.toFloat() / totalWeight)
                    .roundToInt()
                    .coerceIn(codePointStart, codePoints.size)
            }
            val segment = String(codePoints, codePointStart, codePointEnd - codePointStart)
            val encoded = try {
                font.encode(segment)
            } catch (_: Exception) {
                return null
            }
            segments.add(encoded)
            codePointStart = codePointEnd
            stringCursor++
        }

        if (segments.all { it.isEmpty() }) {
            return COSArray().apply { add(COSString(ByteArray(0))) }
        }

        val rebuilt = COSArray()
        stringCursor = 0
        for (i in 0 until original.size()) {
            val item = original.getObject(i)
            if (item is COSString) {
                val encoded = segments[stringCursor++]
                if (encoded.isNotEmpty()) rebuilt.add(COSString(encoded))
            } else {
                // 只保留两个有效文本片段之间的位移，空片段不能推动后续文字位置。
                val hasTextBefore = segments.take(stringCursor).any { it.isNotEmpty() }
                val hasTextAfter = segments.drop(stringCursor).any { it.isNotEmpty() }
                if (hasTextBefore && hasTextAfter) rebuilt.add(original.get(i))
            }
        }
        return rebuilt
    }

    private fun copyResourcesForEdit(stream: ParsedStream): PDResources {
        val inherited = stream.resources
        val copiedDictionary = if (inherited == null) {
            COSDictionary()
        } else {
            COSDictionary(inherited.cosObject).apply {
                // long: 新字体、图片和透明度资源都写入独立子字典，避免污染继承或共享的页面/Form 资源。
                listOf(COSName.FONT, COSName.XOBJECT, COSName.getPDFName("ExtGState")).forEach { key ->
                    val child = inherited.cosObject.getDictionaryObject(key) as? COSDictionary
                    if (child != null) setItem(key, COSDictionary(child))
                }
            }
        }
        return PDResources(copiedDictionary).also { copied ->
            // long: Form 的资源先留在待写回流上，避免 copy-on-write 完成前污染原共享 Form。
            stream.resources = copied
            if (stream.owner is StreamOwner.Page) setResources(stream.owner, copied)
        }
    }

    private fun appendOpacity(out: MutableList<Any>, stream: ParsedStream, opacity: Float) {
        val resources = copyResourcesForEdit(stream)
        val state = PDExtendedGraphicsState().apply {
            val alpha = opacity.coerceIn(0f, 1f)
            strokingAlphaConstant = alpha
            nonStrokingAlphaConstant = alpha
        }
        out.add(resources.add(state))
        out.add(Operator.getOperator("gs"))
    }

    private fun copyPageResources(page: PDPage): PDResources {
        val inherited = page.resources
        val copiedDictionary = if (inherited == null) {
            COSDictionary()
        } else {
            COSDictionary(inherited.cosObject).apply {
                listOf(COSName.FONT, COSName.XOBJECT).forEach { key ->
                    (inherited.cosObject.getDictionaryObject(key) as? COSDictionary)
                        ?.let { setItem(key, COSDictionary(it)) }
                }
            }
        }
        return PDResources(copiedDictionary).also { page.resources = it }
    }

    private fun remapResourceNames(
        copiedTokens: List<Any>,
        sourceResources: PDResources?,
        targetResources: PDResources,
    ): List<Any> {
        if (sourceResources == null) return copiedTokens
        val names = HashMap<String, COSName>()
        val out = ArrayList<Any>(copiedTokens.size)
        for (i in copiedTokens.indices) {
            val token = copiedTokens[i]
            if (token is COSName && i + 1 < copiedTokens.size) {
                val op = copiedTokens[i + 1] as? Operator
                val mapped = when (op?.name) {
                    "Tf" -> runCatching { sourceResources.getFont(token) }
                        .getOrNull()?.let { sourceFont ->
                            names.getOrPut(token.name) { targetResources.add(sourceFont) }
                        }
                    "Do" -> runCatching { sourceResources.getXObject(token) }
                        .getOrNull()?.let { xObject ->
                            names.getOrPut(token.name) {
                                when (xObject) {
                                    is PDImageXObject -> targetResources.add(xObject)
                                    is PDFormXObject -> targetResources.add(xObject)
                                    else -> token
                                }
                            }
                        }
                    else -> null
                }
                out.add(mapped ?: token)
            } else {
                out.add(token)
            }
        }
        return out
    }

    // Reuses an already-added resource when the same font is applied to several
    // runs, so repeated edits don't pile up duplicate /Font entries.
    private fun ensureFontResource(resources: PDResources, font: PDFont): COSName {
        for (name in resources.fontNames) {
            val existing = try {
                resources.getFont(name)
            } catch (_: Exception) {
                null
            }
            if (existing != null && existing.cosObject === font.cosObject) return name
        }
        return resources.add(font)
    }

    // The distinct characters the font cannot encode, for a useful message.
    private fun unsupportedChars(font: PDFont, text: String): String {
        val bad = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            try {
                font.encode(s)
            } catch (_: Exception) {
                if (s.isNotBlank()) bad.add(s)
            }
            i += Character.charCount(cp)
        }
        return bad.joinToString(" ")
    }

    private fun appendColor(out: MutableList<Any>, argb: Int, stroke: Boolean) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        out.add(COSFloat(r)); out.add(COSFloat(g)); out.add(COSFloat(b))
        out.add(Operator.getOperator(if (stroke) "RG" else "rg"))
    }

    private fun appendStrokeStyle(
        out: MutableList<Any>,
        lineWidth: Float,
        lineCap: Int,
        lineJoin: Int,
        miterLimit: Float,
    ) {
        out.add(COSFloat(lineWidth.coerceAtLeast(0f)))
        out.add(Operator.getOperator("w"))
        out.add(COSFloat(lineCap.coerceIn(0, 2).toFloat()))
        out.add(Operator.getOperator("J"))
        out.add(COSFloat(lineJoin.coerceIn(0, 2).toFloat()))
        out.add(Operator.getOperator("j"))
        out.add(COSFloat(miterLimit.coerceAtLeast(1f)))
        out.add(Operator.getOperator("M"))
    }

    // Swap the page's content stream and flag what changed so saveIncremental
    // appends just those objects instead of rewriting the whole file.
    //
    // setNeedToBeUpdated is a TRAVERSAL GATE, not only a write filter: the
    // incremental writer walks the object graph from the trailer and refuses to
    // descend into an unflagged object. So flagging only the page + new stream
    // leaves them unreachable and the increment comes out empty. The entire
    // chain catalog -> pages -> page -> stream must be flagged.
    //
    // Flags are never cleared by the writer and saveIncremental always re-reads
    // the original bytes, so flags left on across edits make changes accumulate
    // correctly. clearContentsFlag drops the superseded stream so orphaned
    // streams don't pile up in the cache.
    private fun commitPage(document: PDDocument, page: PDPage, stream: PDStream) {
        clearContentsFlag(page.cosObject.getDictionaryObject(COSName.CONTENTS))
        page.setContents(stream)
        stream.cosObject.setNeedToBeUpdated(true)
        markResources(page.resources)
        markPageChain(document, page)
    }

    private fun commitForm(
        document: PDDocument,
        page: PDPage,
        formPath: List<PDFormXObject>,
    ) {
        // 增量 writer 把更新标记当作遍历门；页资源、每层 XObject 字典和外层
        // Form 都必须打开，才能沿调用路径走到真正修改的共享 Form 流。
        markResources(page.resources)
        for (form in formPath) {
            form.cosObject.setNeedToBeUpdated(true)
            markResources(form.resources)
        }
        markPageChain(document, page)
    }

    private fun markResources(resources: PDResources?) {
        val dictionary = resources?.cosObject ?: return
        dictionary.setNeedToBeUpdated(true)
        (dictionary.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)
            ?.setNeedToBeUpdated(true)
    }

    private fun markPageChain(document: PDDocument, page: PDPage) {
        var dict: COSDictionary? = page.cosObject
        while (dict != null) {
            dict.setNeedToBeUpdated(true)
            dict = dict.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        document.documentCatalog.cosObject.setNeedToBeUpdated(true)
    }

    private fun readContent(owner: StreamOwner): ByteArray = when (owner) {
        is StreamOwner.Page -> owner.page.contents?.use { it.readBytes() } ?: ByteArray(0)
        is StreamOwner.Form -> owner.form.contents?.use { it.readBytes() } ?: ByteArray(0)
    }

    private fun ownerDictionary(owner: StreamOwner): COSDictionary = when (owner) {
        is StreamOwner.Page -> owner.page.cosObject
        is StreamOwner.Form -> owner.form.cosObject
    }

    private fun copyAnnotations(page: PDPage): COSArray? {
        val source = page.cosObject.getCOSArray(COSName.ANNOTS) ?: return null
        return COSArray().also { target ->
            for (item in source) {
                target.add((item as? COSDictionary)?.let(::COSDictionary) ?: item)
            }
        }
    }

    private fun setResources(owner: StreamOwner, resources: PDResources?) {
        when (owner) {
            is StreamOwner.Page -> owner.page.resources = resources
            is StreamOwner.Form -> owner.form.resources = resources
        }
    }

    private fun clearContentsFlag(contents: COSBase?) {
        when (contents) {
            is COSStream -> contents.setNeedToBeUpdated(false)
            is COSArray -> for (i in 0 until contents.size()) {
                (contents.getObject(i) as? COSStream)?.setNeedToBeUpdated(false)
            }
            else -> {}
        }
    }

    /** long: 图层重排必须在内容流底层再次检查 ReaderLock，避免绕过 ViewModel 入口后移动受保护对象。 */
    private fun containsReaderLock(tokens: List<Any>, start: Int, end: Int): Boolean {
        val markedContent = ArrayDeque<Boolean>()
        for (index in tokens.indices) {
            if (index in start..end && markedContent.any { it }) return true
            val operator = tokens[index] as? Operator ?: continue
            when (operator.name) {
                "BMC", "BDC" -> {
                    val tag = tokens.subList(0, index)
                        .asReversed()
                        .takeWhile { it !is Operator }
                        .filterIsInstance<COSName>()
                        .firstOrNull()
                    val isLock = tag?.name == "ReaderLock"
                    markedContent.addLast(isLock)
                    if (isLock && index in start..end) return true
                }
                "EMC" -> if (markedContent.isNotEmpty()) markedContent.removeLast()
            }
        }
        return false
    }

    private fun isOp(token: Any?, name: String): Boolean =
        token is Operator && token.name == name
}
