package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColorSpace
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDInlineImage
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.sqrt

object ContentStreamEngine {

    private const val MAX_FORM_DEPTH = 32

    fun parse(page: PDPage): ParsedPage {
        val tokens = readTokens(page)
        val pageStream = ParsedStream(StreamOwner.Page(page), tokens, page.resources)
        val context = ParseContext()
        val ancestors = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())
        val children = Builder(
            context = context,
            stream = pageStream,
            initialCtm = Affine.IDENTITY,
            ancestorForms = ancestors,
            depth = 0,
        ).build()
        val rootBounds = Bounds.empty()
        for (child in children) child.bounds?.let(rootBounds::includeBounds)
        val root = DrawNode(
            id = 0,
            kind = NodeKind.GROUP,
            label = "页面",
            detail = "${children.size} 个顶层元素",
            startIndex = 0,
            endIndex = tokens.lastIndex,
            bounds = if (rootBounds.isValid) rootBounds else null,
            colorArgb = null,
            raw = "",
            children = children,
            stream = pageStream,
        )
        return ParsedPage(pageStream, root, context.leaves, context.ocrLeaves)
    }

    private fun readTokens(page: PDPage): List<Any> {
        val parser = PDFStreamParser(page)
        parser.parse()
        return ArrayList<Any>(parser.tokens)
    }

    private fun readTokens(form: PDFormXObject): List<Any> {
        val parser = PDFStreamParser(form)
        parser.parse()
        return ArrayList<Any>(parser.tokens)
    }

    private class ParseContext {
        var nextId = 1
        val leaves = ArrayList<DrawNode>()
        val ocrLeaves = ArrayList<DrawNode>()
    }

    private class GraphicsStateSnapshot(
        val fillColor: Int?,
        val strokeColor: Int?,
        val fillModel: String?,
        val strokeModel: String?,
        val fillCs: PDColorSpace?,
        val strokeCs: PDColorSpace?,
        val fillCsLabel: String?,
        val strokeCsLabel: String?,
        val fontSize: Float,
        val fontName: String,
        val currentFont: PDFont?,
        val leading: Float,
        val characterSpacing: Float,
        val wordSpacing: Float,
        val horizontalScale: Float,
        val textRise: Float,
        val textRenderingMode: Int,
        val clipBounds: Bounds?,
        val strokingAlpha: Float,
        val nonStrokingAlpha: Float,
        val blendMode: String,
        val lineWidth: Float,
        val lineCap: Int,
        val lineJoin: Int,
        val miterLimit: Float,
        val dashPattern: List<Float>,
        val dashPhase: Float,
    )

    private class Builder(
        private val context: ParseContext,
        private val stream: ParsedStream,
        initialCtm: Affine,
        private val initialGraphicsState: GraphicsStateSnapshot? = null,
        private val ancestorForms: MutableSet<COSStream>,
        private val depth: Int,
        private val inheritedLocks: List<ElementLock> = emptyList(),
    ) {

        private val tokens = stream.tokens
        private val resources = stream.resources

        private val rootChildren = ArrayList<DrawNode>()

        private val groupStack = ArrayDeque<GroupFrame>()
        private val ctmStack = ArrayDeque<Affine>().apply { addLast(initialCtm) }
        private val graphicsStateStack = ArrayDeque<GraphicsStateSnapshot>()
        private val markedContentStack = ArrayDeque<MarkedContentFrame>()

        private val operands = ArrayList<COSBase>()
        private var runStart = -1

        private var pathBounds: Bounds? = null
        private var pathStart = -1
        private var currentPathPoint: PathPoint? = null
        private var subpathStartPoint: PathPoint? = null
        private var pendingClipRule: ClipRule? = null
        private var currentClipBounds: Bounds? = null
        private var strokingAlpha = 1f
        private var nonStrokingAlpha = 1f
        private var blendMode = "Normal"
        private var lineWidth = 1f
        private var lineCap = 0
        private var lineJoin = 0
        private var miterLimit = 10f
        private var dashPattern = emptyList<Float>()
        private var dashPhase = 0f
        private var previousPathDirection: PathDirection? = null
        private var firstSubpathDirection: PathDirection? = null
        private var pathMiterFactor = 1f
        private var pathClosed = false

        private var inText = false
        private var textStart = -1
        private var textBounds = Bounds.empty()
        private var textMatrix = Affine.IDENTITY
        private var textLineMatrix = Affine.IDENTITY
        private var fontSize = 0f
        private var fontName = ""
        private var currentFont: PDFont? = null
        private val fontCache = HashMap<String, PDFont?>()
        private var leading = 0f
        private var characterSpacing = 0f
        private var wordSpacing = 0f
        private var horizontalScale = 1f
        private var textRise = 0f
        private var textRenderingMode = 0
        private val textPreview = StringBuilder()
        private val textFull = StringBuilder()
        private var textRuns = ArrayList<DrawNode>()
        private var runBounds = Bounds.empty()
        private val runText = StringBuilder()

        private var fillColor: Int? = null
        private var strokeColor: Int? = null
        private var fillModel: String? = null
        private var strokeModel: String? = null
        private var fillCs: PDColorSpace? = null
        private var strokeCs: PDColorSpace? = null
        private var fillCsLabel: String? = null
        private var strokeCsLabel: String? = null
        private val csCache = HashMap<String, PDColorSpace?>()

        private class GroupFrame(val openIndex: Int, val children: MutableList<DrawNode> = ArrayList())
        private class MarkedContentFrame(
            val crop: ImageCrop?,
            val lock: ElementLock?,
            val generatedOcr: Boolean,
        )

        init {
            initialGraphicsState?.let(::restoreGraphicsState)
        }

        fun build(): List<DrawNode> {
            for (i in tokens.indices) {
                when (val tok = tokens[i]) {
                    is Operator -> {
                        val start = if (operands.isEmpty()) i else runStart
                        handle(tok, i, start)
                        operands.clear()
                        runStart = -1
                    }
                    is COSBase -> {
                        if (operands.isEmpty()) runStart = i
                        operands.add(tok)
                    }
                }
            }
            while (groupStack.isNotEmpty()) closeGroup(tokens.lastIndex)
            while (markedContentStack.isNotEmpty()) endMarkedContent(tokens.lastIndex)
            return rootChildren
        }

        private fun currentChildren(): MutableList<DrawNode> =
            if (groupStack.isEmpty()) rootChildren else groupStack.last().children

        private fun ctm(): Affine = ctmStack.last()

        private fun handle(operator: Operator, opIndex: Int, opStart: Int) {
            val op = operator.name
            when (op) {
                "q" -> {
                    groupStack.addLast(GroupFrame(opIndex))
                    // long: PDF 的 q/Q 会隔离完整图形状态；颜色或字体一旦泄漏，后续对象会被错误识别和编辑。
                    graphicsStateStack.addLast(snapshotGraphicsState())
                    ctmStack.addLast(ctm())
                }
                "Q" -> closeGroup(opIndex)
                "BMC", "BDC" -> beginMarkedContent(op, opStart, opIndex)
                "EMC" -> endMarkedContent(opIndex)
                "cm" -> if (operands.size >= 6) {
                    val old = ctmStack.removeLast()
                    ctmStack.addLast(affine(0).then(old))
                }
                "BT" -> {
                    inText = true
                    textStart = opIndex
                    textMatrix = Affine.IDENTITY
                    textLineMatrix = Affine.IDENTITY
                    textBounds = Bounds.empty()
                    textPreview.setLength(0)
                    textFull.setLength(0)
                    textRuns = ArrayList()
                }
                "ET" -> {
                    if (inText) emitText(opIndex)
                    inText = false
                }
                "Tf" -> if (operands.size >= 2) {
                    val fontCos = operands[0] as? COSName
                    fontName = fontCos?.name ?: fontName
                    fontSize = num(1)
                    currentFont = if (fontCos == null) null else resolveFont(fontCos)
                }
                "TL" -> if (operands.isNotEmpty()) leading = num(0)
                "Tc" -> if (operands.isNotEmpty()) characterSpacing = num(0)
                "Tw" -> if (operands.isNotEmpty()) wordSpacing = num(0)
                "Tz" -> if (operands.isNotEmpty()) horizontalScale = num(0) / 100f
                "Ts" -> if (operands.isNotEmpty()) textRise = num(0)
                "Tr" -> if (operands.isNotEmpty()) textRenderingMode = num(0).toInt()
                "Td" -> if (operands.size >= 2) moveTextLine(num(0), num(1))
                "TD" -> if (operands.size >= 2) {
                    leading = -num(1)
                    moveTextLine(num(0), num(1))
                }
                "Tm" -> if (operands.size >= 6) {
                    textLineMatrix = affine(0)
                    textMatrix = textLineMatrix
                }
                "T*" -> moveTextLine(0f, -leading)
                "Tj" -> {
                    beginRun()
                    showText(operands.lastOrNull() as? COSString)
                    emitRun(op, opStart, opIndex)
                }
                "TJ" -> {
                    beginRun()
                    showArray(operands.lastOrNull() as? COSArray)
                    emitRun(op, opStart, opIndex)
                }
                "'" -> {
                    moveTextLine(0f, -leading)
                    beginRun()
                    showText(operands.lastOrNull() as? COSString)
                    emitRun(op, opStart, opIndex)
                }
                "\"" -> if (operands.size >= 3) {
                    wordSpacing = num(0)
                    characterSpacing = num(1)
                    moveTextLine(0f, -leading)
                    beginRun()
                    showText(operands[2] as? COSString)
                    emitRun(op, opStart, opIndex)
                }
                "rg" -> { fillColor = rgb(num(0), num(1), num(2)); fillModel = "RGB" }
                "RG" -> { strokeColor = rgb(num(0), num(1), num(2)); strokeModel = "RGB" }
                "g" -> { fillColor = rgb(num(0), num(0), num(0)); fillModel = "Gray" }
                "G" -> { strokeColor = rgb(num(0), num(0), num(0)); strokeModel = "Gray" }
                "k" -> { fillColor = cmyk(num(0), num(1), num(2), num(3)); fillModel = "CMYK" }
                "K" -> { strokeColor = cmyk(num(0), num(1), num(2), num(3)); strokeModel = "CMYK" }
                "cs" -> {
                    val n = operands.lastOrNull() as? COSName
                    fillCs = colorSpace(n); fillCsLabel = describeColorSpace(n)
                }
                "CS" -> {
                    val n = operands.lastOrNull() as? COSName
                    strokeCs = colorSpace(n); strokeCsLabel = describeColorSpace(n)
                }
                "sc", "scn" -> {
                    resolveCsColor(fillCs)?.let { fillColor = it }
                    fillModel = fillCsLabel
                }
                "SC", "SCN" -> {
                    resolveCsColor(strokeCs)?.let { strokeColor = it }
                    strokeModel = strokeCsLabel
                }
                "gs" -> applyExtendedGraphicsState(operands.lastOrNull() as? COSName)
                "w" -> if (operands.isNotEmpty()) {
                    lineWidth = num(0).takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: lineWidth
                }
                "J" -> if (operands.isNotEmpty()) lineCap = num(0).toInt().coerceIn(0, 2)
                "j" -> if (operands.isNotEmpty()) lineJoin = num(0).toInt().coerceIn(0, 2)
                "M" -> if (operands.isNotEmpty()) {
                    miterLimit = num(0).takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: miterLimit
                }
                "d" -> if (operands.size >= 2) {
                    dashPattern = (operands[0] as? COSArray)
                        ?.mapNotNull { (it as? COSNumber)?.floatValue()?.takeIf(Float::isFinite) }
                        ?.map { it.coerceAtLeast(0f) }
                        ?: emptyList()
                    dashPhase = num(1).takeIf { it.isFinite() } ?: 0f
                }
                "m" -> if (operands.size >= 2) {
                    beginPath(opStart)
                    movePath(num(0), num(1))
                }
                "l" -> if (operands.size >= 2) {
                    beginPath(opStart)
                    linePath(num(0), num(1))
                }
                "c" -> if (operands.size >= 6) {
                    beginPath(opStart)
                    cubicPath(num(0), num(1), num(2), num(3), num(4), num(5))
                }
                "v" -> if (operands.size >= 4) {
                    beginPath(opStart)
                    val current = currentPathPoint
                    val control = mapPoint(num(0), num(1))
                    val end = mapPoint(num(2), num(3))
                    if (current == null) movePathPoint(end) else cubicPath(current, current, control, end)
                }
                "y" -> if (operands.size >= 4) {
                    beginPath(opStart)
                    val control = mapPoint(num(0), num(1))
                    val end = mapPoint(num(2), num(3))
                    val current = currentPathPoint
                    if (current == null) movePathPoint(end) else cubicPath(current, control, end, end)
                }
                "re" -> if (operands.size >= 4) {
                    beginPath(opStart)
                    val x = num(0); val y = num(1); val w = num(2); val h = num(3)
                    movePath(x, y)
                    linePath(x + w, y)
                    linePath(x + w, y + h)
                    linePath(x, y + h)
                    closePath()
                }
                "h" -> closePath()
                "W" -> if (pathBounds != null) pendingClipRule = ClipRule.NON_ZERO
                "W*" -> if (pathBounds != null) pendingClipRule = ClipRule.EVEN_ODD
                "s", "b", "b*" -> {
                    // long: 这些绘制操作隐含 h；显式闭合后才能正确计算连接样式与后续选择边界。
                    closePath()
                    endPath(op, opIndex)
                }
                "f", "F", "f*", "S", "B", "B*" -> endPath(op, opIndex)
                "n" -> endPath(op, opIndex)
                "BI" -> inlineImage(operator, opIndex)
                "Do" -> doXObject(operands.lastOrNull() as? COSName, opStart, opIndex)
            }
        }

        private fun closeGroup(closeIndex: Int) {
            if (groupStack.isEmpty()) return
            val frame = groupStack.removeLast()
            if (ctmStack.size > 1) ctmStack.removeLast()
            if (graphicsStateStack.isNotEmpty()) {
                restoreGraphicsState(graphicsStateStack.removeLast())
            }
            val bounds = Bounds.empty()
            for (c in frame.children) c.bounds?.let { bounds.includeBounds(it) }
            val node = DrawNode(
                id = context.nextId++,
                kind = NodeKind.GROUP,
                label = "分组",
                detail = "${frame.children.size} 个元素",
                startIndex = frame.openIndex,
                endIndex = closeIndex,
                bounds = if (bounds.isValid) bounds else null,
                colorArgb = null,
                raw = rawSlice(frame.openIndex, minOf(frame.openIndex + 1, closeIndex)),
                children = frame.children,
                locks = activeLocks(),
                stream = stream,
            )
            currentChildren().add(node)
        }

        private fun beginMarkedContent(op: String, startIndex: Int, operatorIndex: Int) {
            val tag = operands.firstOrNull() as? COSName
            val dictionary = if (op == "BDC") operands.lastOrNull() as? COSDictionary else null
            val crop = if (tag?.name == "ReaderCrop" && dictionary != null) {
                ImageCrop(
                    left = cropValue(dictionary, "L"),
                    top = cropValue(dictionary, "T"),
                    right = cropValue(dictionary, "R"),
                    bottom = cropValue(dictionary, "B"),
                    wrapperStart = startIndex,
                )
            } else {
                null
            }
            val lock = if (tag?.name == "ReaderLock") {
                ElementLock(stream, startIndex, operatorIndex)
            } else {
                null
            }
            markedContentStack.addLast(
                MarkedContentFrame(
                    crop = crop,
                    lock = lock,
                    generatedOcr = tag?.name == PdfOcrEditor.MARKER_NAME,
                ),
            )
        }

        private fun endMarkedContent(endIndex: Int) {
            if (markedContentStack.isEmpty()) return
            val frame = markedContentStack.removeLast()
            frame.crop?.wrapperEnd = endIndex
            frame.lock?.wrapperEnd = endIndex
        }

        private fun cropValue(dictionary: COSDictionary, key: String): Float =
            ((dictionary.getDictionaryObject(COSName.getPDFName(key)) as? COSNumber)?.floatValue() ?: 0f)
                .coerceIn(0f, 0.99f)

        private fun activeImageCrop(): ImageCrop? =
            markedContentStack.asReversed().firstNotNullOfOrNull { it.crop }

        private fun activeGeneratedOcr(): Boolean = markedContentStack.any { it.generatedOcr }

        private fun activeLocks(): List<ElementLock> = buildList {
            addAll(inheritedLocks)
            markedContentStack.mapNotNullTo(this) { it.lock }
        }

        private fun snapshotGraphicsState() = GraphicsStateSnapshot(
            fillColor = fillColor,
            strokeColor = strokeColor,
            fillModel = fillModel,
            strokeModel = strokeModel,
            fillCs = fillCs,
            strokeCs = strokeCs,
            fillCsLabel = fillCsLabel,
            strokeCsLabel = strokeCsLabel,
            fontSize = fontSize,
            fontName = fontName,
            currentFont = currentFont,
            leading = leading,
            characterSpacing = characterSpacing,
            wordSpacing = wordSpacing,
            horizontalScale = horizontalScale,
            textRise = textRise,
            textRenderingMode = textRenderingMode,
            clipBounds = currentClipBounds?.duplicate(),
            strokingAlpha = strokingAlpha,
            nonStrokingAlpha = nonStrokingAlpha,
            blendMode = blendMode,
            lineWidth = lineWidth,
            lineCap = lineCap,
            lineJoin = lineJoin,
            miterLimit = miterLimit,
            dashPattern = dashPattern.toList(),
            dashPhase = dashPhase,
        )

        private fun restoreGraphicsState(snapshot: GraphicsStateSnapshot) {
            fillColor = snapshot.fillColor
            strokeColor = snapshot.strokeColor
            fillModel = snapshot.fillModel
            strokeModel = snapshot.strokeModel
            fillCs = snapshot.fillCs
            strokeCs = snapshot.strokeCs
            fillCsLabel = snapshot.fillCsLabel
            strokeCsLabel = snapshot.strokeCsLabel
            fontSize = snapshot.fontSize
            fontName = snapshot.fontName
            currentFont = snapshot.currentFont
            leading = snapshot.leading
            characterSpacing = snapshot.characterSpacing
            wordSpacing = snapshot.wordSpacing
            horizontalScale = snapshot.horizontalScale
            textRise = snapshot.textRise
            textRenderingMode = snapshot.textRenderingMode
            currentClipBounds = snapshot.clipBounds?.duplicate()
            strokingAlpha = snapshot.strokingAlpha
            nonStrokingAlpha = snapshot.nonStrokingAlpha
            blendMode = snapshot.blendMode
            lineWidth = snapshot.lineWidth
            lineCap = snapshot.lineCap
            lineJoin = snapshot.lineJoin
            miterLimit = snapshot.miterLimit
            dashPattern = snapshot.dashPattern.toList()
            dashPhase = snapshot.dashPhase
        }

        private fun applyExtendedGraphicsState(name: COSName?) {
            if (name == null) return
            val state = try {
                resources?.getExtGState(name)
            } catch (_: Exception) {
                null
            } ?: return
            val dictionary = state.cosObject
            (dictionary.getDictionaryObject(COSName.getPDFName("CA")) as? COSNumber)?.let {
                strokingAlpha = it.floatValue().coerceIn(0f, 1f)
            }
            (dictionary.getDictionaryObject(COSName.getPDFName("ca")) as? COSNumber)?.let {
                nonStrokingAlpha = it.floatValue().coerceIn(0f, 1f)
            }
            describeBlendMode(dictionary.getDictionaryObject(COSName.getPDFName("BM")))?.let {
                blendMode = it
            }
        }

        private fun describeBlendMode(value: COSBase?): String? = when (value) {
            is COSName -> value.name
            is COSArray -> (value.getObject(0) as? COSName)?.name
            else -> null
        }

        private fun moveTextLine(tx: Float, ty: Float) {
            textLineMatrix = Affine.translate(tx, ty).then(textLineMatrix)
            textMatrix = textLineMatrix
        }

        private fun resolveFont(name: COSName): PDFont? =
            fontCache.getOrPut(name.name) {
                try {
                    resources?.getFont(name)
                } catch (_: Exception) {
                    null
                }
            }

        private fun colorSpace(name: COSName?): PDColorSpace? {
            if (name == null) return null
            return csCache.getOrPut(name.name) {
                try {
                    resources?.getColorSpace(name)
                } catch (_: Exception) {
                    null
                }
            }
        }

        // A human label for the color space the fill/stroke was set in, read from
        // the raw definition so spot colors keep their colorant name even though
        // pdfbox-android can't build them.
        private fun describeColorSpace(name: COSName?): String? {
            if (name == null) return null
            when (name.name) {
                "DeviceRGB" -> return "RGB"
                "DeviceGray" -> return "Gray"
                "DeviceCMYK" -> return "CMYK"
                "Pattern" -> return "Pattern"
            }
            val def = try {
                (resources?.cosObject?.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary)
                    ?.getDictionaryObject(name)
            } catch (_: Exception) {
                null
            }
            return when (def) {
                is COSArray -> {
                    val family = (def.getObject(0) as? COSName)?.name ?: "Color space"
                    if (family == "Separation" && def.size() > 1) {
                        (def.getObject(1) as? COSName)?.name?.let { "Separation ($it)" } ?: "Separation"
                    } else {
                        family
                    }
                }
                is COSName -> describeColorSpace(def) ?: def.name
                else -> name.name
            }
        }

        // Resolves an sc/scn color through its color space to RGB. The component
        // count must match: pdfbox-android falls back to DeviceRGB for color
        // spaces it cannot build (e.g. Separation), and feeding it the wrong
        // operand count yields garbage, so skip those and let bitmap sampling
        // supply the real color instead.
        private fun resolveCsColor(cs: PDColorSpace?): Int? {
            if (cs == null) return null
            val comps = ArrayList<Float>(operands.size)
            for (o in operands) if (o is COSNumber) comps.add(o.floatValue())
            if (comps.isEmpty() || comps.size != cs.numberOfComponents) return null
            return try {
                val c = cs.toRGB(comps.toFloatArray())
                rgb(c[0], c[1], c[2])
            } catch (_: Exception) {
                null
            }
        }

        private fun showText(s: COSString?) {
            if (s == null) return
            val bytes = s.bytes
            val font = currentFont
            val decoded = if (font != null) decodeWithFont(font, bytes) else null
            val baseAdvance: Float
            val glyphCount: Int
            val spaceCount: Int
            if (decoded != null) {
                appendText(decoded.text)
                baseAdvance = decoded.width
                glyphCount = decoded.glyphCount
                spaceCount = decoded.spaceCount
            } else {
                appendText(asciiFallback(bytes))
                baseAdvance = bytes.size * fontSize * 0.5f
                glyphCount = bytes.size
                spaceCount = bytes.count { (it.toInt() and 0xFF) == 32 }
            }
            // long: PDF 文本推进先叠加字形宽度、字距和词距，再应用水平缩放；后续 run 的起点依赖该结果。
            val advance = (
                baseAdvance + glyphCount * characterSpacing + spaceCount * wordSpacing
            ) * horizontalScale
            val ascent = fontSize * 0.75f
            val descent = -fontSize * 0.25f
            val m = Affine.translate(0f, textRise).then(textMatrix).then(ctm())
            includeQuad(textBounds, m, advance, ascent, descent)
            includeQuad(runBounds, m, advance, ascent, descent)
            textMatrix = Affine.translate(advance, 0f).then(textMatrix)
        }

        private class Decoded(
            val text: String,
            val width: Float,
            val glyphCount: Int,
            val spaceCount: Int,
        )

        private fun decodeWithFont(font: PDFont, bytes: ByteArray): Decoded? =
            try {
                val sb = StringBuilder()
                var width = 0f
                var glyphCount = 0
                var spaceCount = 0
                val input = ByteArrayInputStream(bytes)
                while (input.available() > 0) {
                    val code = font.readCode(input)
                    glyphCount++
                    if (code == 32) spaceCount++
                    val u = try {
                        font.toUnicode(code)
                    } catch (_: Exception) {
                        null
                    }
                    if (u != null) sb.append(u)
                    else if (code in 32..126) sb.append(code.toChar())
                    width += try {
                        font.getDisplacement(code).x * fontSize
                    } catch (_: Exception) {
                        fontSize * 0.5f
                    }
                }
                Decoded(sb.toString(), width, glyphCount, spaceCount)
            } catch (_: Exception) {
                null
            }

        private fun includeQuad(b: Bounds, m: Affine, w: Float, ascent: Float, descent: Float) {
            b.include(m.mapX(0f, descent), m.mapY(0f, descent))
            b.include(m.mapX(w, descent), m.mapY(w, descent))
            b.include(m.mapX(w, ascent), m.mapY(w, ascent))
            b.include(m.mapX(0f, ascent), m.mapY(0f, ascent))
        }

        private fun showArray(a: COSArray?) {
            if (a == null) return
            for (i in 0 until a.size()) {
                when (val el = a.getObject(i)) {
                    is COSString -> showText(el)
                    is COSNumber -> {
                        val rawTx = -el.floatValue() / 1000f * fontSize
                        if (rawTx > 0.2f * fontSize) appendText(" ")
                        val tx = rawTx * horizontalScale
                        textMatrix = Affine.translate(tx, 0f).then(textMatrix)
                    }
                }
            }
        }

        private fun beginRun() {
            runBounds = Bounds.empty()
            runText.setLength(0)
        }

        private fun emitRun(op: String, opStart: Int, opIndex: Int) {
            if (!runBounds.isValid) return
            val preview = runText.toString().trim().take(40)
            val label = if (preview.isEmpty()) "文本" else "文本“$preview”"
            val size = if (fontSize > 0f) "  ${fontSize.toInt()}pt" else ""
            textRuns.add(
                DrawNode(
                    id = context.nextId++,
                    kind = NodeKind.TEXT,
                    label = label,
                    detail = "$op  ${(fontName + size).trim()}".trim(),
                    startIndex = opStart,
                    endIndex = opIndex,
                    bounds = runBounds,
                    colorArgb = textPaintColor(),
                    raw = rawSlice(opStart, opIndex),
                    children = emptyList(),
                    text = runText.toString(),
                    font = currentFont,
                    fontResourceName = fontName,
                    fontSize = fontSize,
                    characterSpacing = characterSpacing,
                    lineLeading = leading,
                    textRenderingMode = textRenderingMode,
                    colorSpace = textPaintColorSpace(),
                    clipBounds = currentClipBounds?.duplicate(),
                    strokingAlpha = strokingAlpha,
                    nonStrokingAlpha = nonStrokingAlpha,
                    blendMode = blendMode,
                    generatedOcr = activeGeneratedOcr(),
                    locks = activeLocks(),
                    stream = stream,
                ),
            )
        }

        private fun emitText(endIndex: Int) {
            if (!textBounds.isValid) return
            val preview = textPreview.toString().trim()
            val label = if (preview.isEmpty()) "文本" else "文本“$preview”"
            val size = if (fontSize > 0f) "  ${fontSize.toInt()}pt" else ""
            addLeaf(
                DrawNode(
                    id = context.nextId++,
                    kind = NodeKind.TEXT,
                    label = label,
                    detail = (fontName + size).trim(),
                    startIndex = textStart,
                    endIndex = endIndex,
                    bounds = textBounds,
                    colorArgb = textPaintColor(),
                    raw = rawSlice(textStart, endIndex),
                    children = textRuns,
                    text = textFull.toString(),
                    ctm = ctm(),
                    fontSize = fontSize,
                    characterSpacing = characterSpacing,
                    lineLeading = leading,
                    textRenderingMode = textRenderingMode,
                    colorSpace = textPaintColorSpace(),
                    clipBounds = currentClipBounds?.duplicate(),
                    strokingAlpha = strokingAlpha,
                    nonStrokingAlpha = nonStrokingAlpha,
                    blendMode = blendMode,
                    generatedOcr = activeGeneratedOcr(),
                    locks = activeLocks(),
                    stream = stream,
                ),
            )
        }

        private fun textPaintColor(): Int? = when (textRenderingMode) {
            1, 5 -> strokeColor
            3, 7 -> null
            2, 6 -> fillColor ?: strokeColor
            else -> fillColor
        }

        private fun textPaintColorSpace(): String? = when (textRenderingMode) {
            1, 5 -> strokeModel
            3, 7 -> null
            2, 6 -> fillModel ?: strokeModel
            else -> fillModel
        }

        private fun beginPath(start: Int) {
            if (pathBounds == null) {
                pathBounds = Bounds.empty()
                pathStart = start
                currentPathPoint = null
                subpathStartPoint = null
                pendingClipRule = null
                previousPathDirection = null
                firstSubpathDirection = null
                pathMiterFactor = 1f
                pathClosed = false
            }
        }

        private class PathPoint(
            val userX: Float,
            val userY: Float,
            val x: Float,
            val y: Float,
        )

        private class PathDirection(val x: Float, val y: Float)

        private fun mapPoint(x: Float, y: Float): PathPoint =
            PathPoint(x, y, ctm().mapX(x, y), ctm().mapY(x, y))

        private fun movePath(x: Float, y: Float) = movePathPoint(mapPoint(x, y))

        private fun movePathPoint(point: PathPoint) {
            pathBounds?.include(point.x, point.y)
            currentPathPoint = point
            subpathStartPoint = point
            previousPathDirection = null
            firstSubpathDirection = null
        }

        private fun linePath(x: Float, y: Float) {
            val point = mapPoint(x, y)
            currentPathPoint?.let {
                pathBounds?.include(it.x, it.y)
                registerSegment(it, point)
            }
            pathBounds?.include(point.x, point.y)
            currentPathPoint = point
            if (subpathStartPoint == null) subpathStartPoint = point
        }

        private fun closePath() {
            val start = subpathStartPoint ?: return
            val current = currentPathPoint
            if (current != null && (current.userX != start.userX || current.userY != start.userY)) {
                registerSegment(current, start)
            }
            val closingDirection = previousPathDirection
            val firstDirection = firstSubpathDirection
            if (closingDirection != null && firstDirection != null) {
                registerJoin(closingDirection, firstDirection)
            }
            pathBounds?.include(start.x, start.y)
            currentPathPoint = start
            pathClosed = true
        }

        private fun cubicPath(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            x3: Float,
            y3: Float,
        ) {
            val end = mapPoint(x3, y3)
            val current = currentPathPoint
            if (current == null) {
                movePathPoint(end)
                return
            }
            cubicPath(current, mapPoint(x1, y1), mapPoint(x2, y2), end)
        }

        private fun cubicPath(p0: PathPoint, p1: PathPoint, p2: PathPoint, p3: PathPoint) {
            val bounds = pathBounds ?: return
            val startDirection = direction(p0, p1) ?: direction(p0, p2) ?: direction(p0, p3)
            val endDirection = direction(p2, p3) ?: direction(p1, p3) ?: direction(p0, p3)
            if (startDirection != null) {
                previousPathDirection?.let { registerJoin(it, startDirection) }
                if (firstSubpathDirection == null) firstSubpathDirection = startDirection
            }
            previousPathDirection = endDirection ?: startDirection ?: previousPathDirection
            bounds.include(p0.x, p0.y)
            bounds.include(p3.x, p3.y)

            // long: 控制点不一定落在曲线上；只有三次贝塞尔导数在 (0,1) 内的根才是真实极值。
            val candidates = LinkedHashSet<Float>()
            cubicExtrema(p0.x, p1.x, p2.x, p3.x, candidates)
            cubicExtrema(p0.y, p1.y, p2.y, p3.y, candidates)
            for (t in candidates) {
                bounds.include(
                    cubicValue(p0.x, p1.x, p2.x, p3.x, t),
                    cubicValue(p0.y, p1.y, p2.y, p3.y, t),
                )
            }
            currentPathPoint = p3
            if (subpathStartPoint == null) subpathStartPoint = p0
        }

        private fun registerSegment(start: PathPoint, end: PathPoint) {
            val direction = direction(start, end) ?: return
            previousPathDirection?.let { registerJoin(it, direction) }
            if (firstSubpathDirection == null) firstSubpathDirection = direction
            previousPathDirection = direction
        }

        private fun direction(start: PathPoint, end: PathPoint): PathDirection? {
            val dx = end.userX - start.userX
            val dy = end.userY - start.userY
            val length = sqrt(dx * dx + dy * dy)
            if (!length.isFinite() || length < 1e-6f) return null
            return PathDirection(dx / length, dy / length)
        }

        private fun registerJoin(incoming: PathDirection, outgoing: PathDirection) {
            val dot = (incoming.x * outgoing.x + incoming.y * outgoing.y).coerceIn(-1f, 1f)
            val sinHalfInterior = sqrt(((1f + dot) / 2f).coerceAtLeast(0f))
            val ratio = if (sinHalfInterior < 1e-4f) Float.MAX_VALUE else 1f / sinHalfInterior
            if (ratio.isFinite()) pathMiterFactor = maxOf(pathMiterFactor, ratio)
        }

        private fun cubicExtrema(
            p0: Float,
            p1: Float,
            p2: Float,
            p3: Float,
            into: MutableSet<Float>,
        ) {
            val a = -p0 + 3f * p1 - 3f * p2 + p3
            val b = 2f * (p0 - 2f * p1 + p2)
            val c = p1 - p0
            if (abs(a) < 1e-6f) {
                if (abs(b) >= 1e-6f) addExtremum(-c / b, into)
                return
            }
            val discriminant = b * b - 4f * a * c
            if (discriminant < 0f) return
            val root = sqrt(discriminant)
            addExtremum((-b + root) / (2f * a), into)
            addExtremum((-b - root) / (2f * a), into)
        }

        private fun addExtremum(t: Float, into: MutableSet<Float>) {
            if (t > 0f && t < 1f && t.isFinite()) into.add(t)
        }

        private fun cubicValue(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val u = 1f - t
            return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
        }

        private fun endPath(op: String, endIndex: Int) {
            val geometryBounds = pathBounds
            if (geometryBounds != null && geometryBounds.isValid) {
                val clipRule = pendingClipRule
                if (clipRule != null) {
                    // long: W/W* 到路径终结操作才生效，并与已有剪裁区域取交集；q/Q 会通过图形状态快照恢复它。
                    currentClipBounds = currentClipBounds?.intersected(geometryBounds) ?: geometryBounds.duplicate()
                }
                if (op == "n" && clipRule == null) {
                    resetPath()
                    return
                }
                val filled = op == "f" || op == "F" || op == "f*" ||
                    op == "B" || op == "B*" || op == "b" || op == "b*"
                val stroked = op == "S" || op == "s" ||
                    op == "B" || op == "B*" || op == "b" || op == "b*"
                // long: 剪裁仍采用原始路径几何；只有可见描边的选择框才扩展到笔画外缘。
                val paintedBounds = if (stroked) strokedBounds(geometryBounds) else geometryBounds.duplicate()
                val label = when {
                    op == "n" && clipRule != null -> "剪裁路径"
                    clipRule != null && (op.startsWith("B") || op.startsWith("b")) -> "路径填充、描边并剪裁"
                    clipRule != null && (op == "S" || op == "s") -> "路径描边并剪裁"
                    clipRule != null -> "路径填充并剪裁"
                    op == "S" || op == "s" -> "路径描边"
                    op.startsWith("B") || op.startsWith("b") -> "路径填充与描边"
                    else -> "路径填充"
                }
                val node = DrawNode(
                    id = context.nextId++,
                    kind = NodeKind.PATH,
                    label = label,
                    detail = buildString {
                        append("${paintedBounds.width.toInt()}×${paintedBounds.height.toInt()}")
                        when (clipRule) {
                            ClipRule.NON_ZERO -> append("  非零环绕剪裁")
                            ClipRule.EVEN_ODD -> append("  奇偶规则剪裁")
                            null -> Unit
                        }
                    },
                    startIndex = pathStart,
                    endIndex = endIndex,
                    bounds = paintedBounds,
                    colorArgb = when {
                        op == "n" -> null
                        filled -> fillColor ?: strokeColor
                        else -> strokeColor
                    },
                    raw = rawSlice(pathStart, endIndex),
                    children = emptyList(),
                    ctm = ctm(),
                    colorSpace = if (filled) fillModel ?: strokeModel else strokeModel,
                    clipRule = clipRule,
                    clipBounds = currentClipBounds?.duplicate(),
                    strokingAlpha = strokingAlpha,
                    nonStrokingAlpha = nonStrokingAlpha,
                    blendMode = blendMode,
                    fillArgb = fillColor.takeIf { filled },
                    strokeArgb = strokeColor.takeIf { stroked },
                    fillColorSpace = fillModel.takeIf { filled },
                    strokeColorSpace = strokeModel.takeIf { stroked },
                    lineWidth = lineWidth,
                    lineCap = lineCap,
                    lineJoin = lineJoin,
                    miterLimit = miterLimit,
                    dashPattern = dashPattern.toList(),
                    dashPhase = dashPhase,
                    pathClosed = pathClosed,
                    locks = activeLocks(),
                    stream = stream,
                )
                if (op == "n" && clipRule != null && activeImageCrop() != null) {
                    currentChildren().add(node)
                } else {
                    addLeaf(node)
                }
            }
            resetPath()
        }

        private fun strokedBounds(geometry: Bounds): Bounds {
            val halfWidth = lineWidth.coerceAtLeast(0f) / 2f
            if (halfWidth == 0f) return geometry.duplicate()
            val matrix = ctm()
            val trace = matrix.a * matrix.a + matrix.b * matrix.b +
                matrix.c * matrix.c + matrix.d * matrix.d
            val determinant = matrix.a * matrix.d - matrix.b * matrix.c
            val discriminant = (trace * trace - 4f * determinant * determinant).coerceAtLeast(0f)
            val sigmaMax = sqrt(((trace + sqrt(discriminant)) / 2f).coerceAtLeast(0f))
            val joinFactor = if (lineJoin == 0) {
                pathMiterFactor.coerceIn(1f, miterLimit.coerceAtLeast(1f))
            } else {
                1f
            }
            val expansion = halfWidth * sigmaMax * joinFactor
            if (!expansion.isFinite()) return geometry.duplicate()
            return Bounds(
                geometry.minX - expansion,
                geometry.minY - expansion,
                geometry.maxX + expansion,
                geometry.maxY + expansion,
            )
        }

        private fun resetPath() {
            pathBounds = null
            pathStart = -1
            currentPathPoint = null
            subpathStartPoint = null
            pendingClipRule = null
            previousPathDirection = null
            firstSubpathDirection = null
            pathMiterFactor = 1f
            pathClosed = false
        }

        private fun doXObject(name: COSName?, start: Int, endIndex: Int) {
            if (name == null) return
            val n = name.name
            try {
                when (val x = resources?.getXObject(name)) {
                    is PDImageXObject -> addLeaf(
                        DrawNode(
                            id = context.nextId++,
                            kind = NodeKind.IMAGE,
                            label = "图像",
                            detail = "$n  ${x.width}×${x.height}",
                            startIndex = start,
                            endIndex = endIndex,
                            bounds = unitSquare(),
                            colorArgb = null,
                            raw = rawSlice(start, endIndex),
                            children = emptyList(),
                            ctm = ctm(),
                            clipBounds = currentClipBounds?.duplicate(),
                            strokingAlpha = strokingAlpha,
                            nonStrokingAlpha = nonStrokingAlpha,
                            blendMode = blendMode,
                            xObjectResourceName = name,
                            imageCrop = activeImageCrop(),
                            locks = activeLocks(),
                            stream = stream,
                        ),
                    )
                    is PDFormXObject -> addFormNode(name, x, start, endIndex)
                    else -> {}
                }
            } catch (_: Exception) {
            }
        }

        private fun inlineImage(operator: Operator, index: Int) {
            val parameters = operator.imageParameters ?: return
            val data = operator.imageData ?: return
            val image = try {
                PDInlineImage(parameters, data, resources)
            } catch (_: Exception) {
                null
            }
            val dimensions = if (image == null) {
                "尺寸未知"
            } else {
                "${image.width}×${image.height}"
            }
            addLeaf(
                DrawNode(
                    id = context.nextId++,
                    kind = NodeKind.IMAGE,
                    label = "内嵌图像",
                    detail = dimensions,
                    startIndex = index,
                    endIndex = index,
                    bounds = unitSquare(),
                    colorArgb = null,
                    raw = "BI … ID (${data.size} bytes) EI",
                    children = emptyList(),
                    ctm = ctm(),
                    clipBounds = currentClipBounds?.duplicate(),
                    strokingAlpha = strokingAlpha,
                    nonStrokingAlpha = nonStrokingAlpha,
                    blendMode = blendMode,
                    inlineImage = true,
                    imageCrop = activeImageCrop(),
                    locks = activeLocks(),
                    stream = stream,
                ),
            )
        }

        private fun addFormNode(name: COSName, form: PDFormXObject, start: Int, endIndex: Int) {
            val formCtm = form.matrix.toAffine().then(ctm())
            val formCos = form.cosObject
            var stoppedReason: String? = null
            val children = when {
                depth >= MAX_FORM_DEPTH -> {
                    stoppedReason = "达到深度限制"
                    emptyList()
                }
                formCos in ancestorForms -> {
                    stoppedReason = "检测到递归引用"
                    emptyList()
                }
                else -> {
                    // Form 没有自己的 /Resources 时继承调用者；递归栈只保护当前路径，
                    // 同一个 Form 在页面上的其他合法调用仍会生成独立节点和坐标。
                    ancestorForms.add(formCos)
                    try {
                        val childTokens = readTokens(form)
                        val operandIndex = (endIndex - 1 downTo start).firstOrNull { index ->
                            (tokens.getOrNull(index) as? COSName)?.name == name.name
                        } ?: start
                        val invocation = FormInvocation(
                            parentStream = stream,
                            resourceName = name,
                            operandIndex = operandIndex,
                            operatorIndex = endIndex,
                        )
                        val childStream = ParsedStream(
                            owner = StreamOwner.Form(form),
                            tokens = childTokens,
                            resources = form.resources ?: resources,
                            formPath = stream.formPath + form,
                            invocationPath = stream.invocationPath + invocation,
                        )
                        Builder(
                            context = context,
                            stream = childStream,
                            initialCtm = formCtm,
                            // long: Form 从调用点继承图形状态，但子流内的修改必须停留在 Form 执行范围内。
                            initialGraphicsState = snapshotGraphicsState(),
                            ancestorForms = ancestorForms,
                            depth = depth + 1,
                            inheritedLocks = activeLocks(),
                        ).build()
                    } finally {
                        ancestorForms.remove(formCos)
                    }
                }
            }
            val childBounds = Bounds.empty()
            for (child in children) child.bounds?.let(childBounds::includeBounds)
            val bounds = formBounds(form, formCtm)
                ?: if (childBounds.isValid) childBounds else null
            val detail = buildString {
                append(name.name).append("  ").append(children.size).append(" 个元素")
                stoppedReason?.let { append("  (").append(it).append(')') }
            }
            currentChildren().add(
                DrawNode(
                    id = context.nextId++,
                    kind = NodeKind.GROUP,
                    label = "表单对象",
                    detail = detail,
                    startIndex = start,
                    endIndex = endIndex,
                    bounds = bounds,
                    colorArgb = null,
                    raw = rawSlice(start, endIndex),
                    children = children,
                    ctm = ctm(),
                    locks = activeLocks(),
                    stream = stream,
                ),
            )
        }

        private fun unitSquare(): Bounds {
            val b = Bounds.empty()
            val m = ctm()
            b.include(m.mapX(0f, 0f), m.mapY(0f, 0f))
            b.include(m.mapX(1f, 0f), m.mapY(1f, 0f))
            b.include(m.mapX(1f, 1f), m.mapY(1f, 1f))
            b.include(m.mapX(0f, 1f), m.mapY(0f, 1f))
            return b
        }

        private fun formBounds(form: PDFormXObject, matrix: Affine): Bounds? {
            val r = form.bBox ?: return null
            val b = Bounds.empty()
            val xs = floatArrayOf(r.lowerLeftX, r.upperRightX)
            val ys = floatArrayOf(r.lowerLeftY, r.upperRightY)
            for (x in xs) for (y in ys) b.include(matrix.mapX(x, y), matrix.mapY(x, y))
            return if (b.isValid) b else null
        }

        private fun addLeaf(node: DrawNode) {
            if (node.generatedOcr) {
                // long: OCR 文字层必须参与搜索与永久遮盖，但不属于用户可见对象，避免检查器被隐藏文字淹没。
                context.ocrLeaves.add(node)
            } else {
                currentChildren().add(node)
                context.leaves.add(node)
            }
        }

        private fun appendText(text: String) {
            if (text.isEmpty()) return
            runText.append(text)
            textFull.append(text)
            if (textPreview.length < 40) {
                val room = 40 - textPreview.length
                textPreview.append(if (text.length > room) text.substring(0, room) else text)
            }
        }

        private fun asciiFallback(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (byte in bytes) {
                val v = byte.toInt() and 0xFF
                if (v in 32..126) sb.append(v.toChar())
            }
            return sb.toString()
        }

        private fun num(i: Int): Float = (operands.getOrNull(i) as? COSNumber)?.floatValue() ?: 0f

        private fun affine(start: Int): Affine =
            Affine(num(start), num(start + 1), num(start + 2), num(start + 3), num(start + 4), num(start + 5))

        private fun rawSlice(start: Int, end: Int): String {
            val sb = StringBuilder()
            var i = start
            while (i <= end && i < tokens.size) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(cosToString(tokens[i]))
                if (sb.length > 220) {
                    sb.append(" …")
                    break
                }
                i++
            }
            return sb.toString()
        }
    }

    private fun cosToString(o: Any?): String = when (o) {
        is COSName -> "/" + o.name
        is COSString -> "(" + asciiOf(o.bytes) + ")"
        is COSArray -> "[" + (0 until o.size()).joinToString(" ") { cosToString(o.getObject(it)) } + "]"
        is COSNumber -> formatNumber(o.floatValue())
        is COSBoolean -> o.value.toString()
        is Operator -> o.name
        else -> o?.toString() ?: ""
    }

    private fun asciiOf(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            sb.append(if (v in 32..126) v.toChar() else '·')
            if (sb.length > 32) {
                sb.append('…')
                break
            }
        }
        return sb.toString()
    }

    private fun formatNumber(v: Float): String =
        if (v == v.toLong().toFloat()) {
            v.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", v)
        }

    private fun Matrix.toAffine(): Affine = Affine(
        scaleX, shearY, shearX, scaleY, translateX, translateY,
    )

    private fun rgb(r: Float, g: Float, b: Float): Int {
        val ri = (r * 255f).toInt().coerceIn(0, 255)
        val gi = (g * 255f).toInt().coerceIn(0, 255)
        val bi = (b * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun cmyk(c: Float, m: Float, y: Float, k: Float): Int =
        rgb((1f - c) * (1f - k), (1f - m) * (1f - k), (1f - y) * (1f - k))
}
