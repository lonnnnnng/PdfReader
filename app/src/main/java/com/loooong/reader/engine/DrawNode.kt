package com.loooong.reader.engine

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject

enum class NodeKind { GROUP, TEXT, PATH, IMAGE }

enum class ClipRule { NON_ZERO, EVEN_ODD }

class ImageCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val wrapperStart: Int,
    var wrapperEnd: Int = -1,
)

/** long: 锁包装保留所属内容流和成对标记索引，Form 子对象继承锁后仍能回到调用点安全解锁。 */
class ElementLock(
    val stream: ParsedStream,
    val wrapperStart: Int,
    val beginOperatorIndex: Int,
    var wrapperEnd: Int = -1,
)

// token 索引只能解释为所属内容流中的位置；Form 路径同时保留增量保存时
// 从页面资源走到目标共享流所需的精确遍历链。
sealed class StreamOwner {
    class Page(val page: PDPage) : StreamOwner()
    class Form(val form: PDFormXObject) : StreamOwner()
}

class ParsedStream(
    val owner: StreamOwner,
    val tokens: List<Any>,
    var resources: PDResources?,
    val formPath: List<PDFormXObject> = emptyList(),
    val invocationPath: List<FormInvocation> = emptyList(),
)

/** long: 记录 Form 从页面到目标子流的每个精确调用点，写回时只替换被编辑的那一次 /Do。 */
class FormInvocation(
    val parentStream: ParsedStream,
    val resourceName: COSName,
    val operandIndex: Int,
    val operatorIndex: Int,
)

// One node in the inspector tree, mapped to a contiguous run of content-stream
// tokens [startIndex, endIndex] so it can be highlighted, deleted, or rewritten.
// ctm is the CTM active at the node, used to map page-space edits into the local
// matrix of a q/cm/Q wrapper; font is the run's font, used to re-encode text.
class DrawNode(
    val id: Int,
    val kind: NodeKind,
    val label: String,
    val detail: String,
    val startIndex: Int,
    val endIndex: Int,
    val bounds: Bounds?,
    val colorArgb: Int?,
    val raw: String,
    val children: List<DrawNode>,
    val text: String? = null,
    val ctm: Affine? = null,
    val font: PDFont? = null,
    val fontResourceName: String? = null,
    val fontSize: Float = 0f,
    val characterSpacing: Float = 0f,
    val lineLeading: Float = 0f,
    val textRenderingMode: Int? = null,
    val colorSpace: String? = null,
    val clipRule: ClipRule? = null,
    val clipBounds: Bounds? = null,
    val strokingAlpha: Float = 1f,
    val nonStrokingAlpha: Float = 1f,
    val blendMode: String = "Normal",
    val fillArgb: Int? = null,
    val strokeArgb: Int? = null,
    val fillColorSpace: String? = null,
    val strokeColorSpace: String? = null,
    val lineWidth: Float = 1f,
    val lineCap: Int = 0,
    val lineJoin: Int = 0,
    val miterLimit: Float = 10f,
    val dashPattern: List<Float> = emptyList(),
    val dashPhase: Float = 0f,
    val pathClosed: Boolean = false,
    val inlineImage: Boolean = false,
    val xObjectResourceName: COSName? = null,
    val imageCrop: ImageCrop? = null,
    val generatedOcr: Boolean = false,
    val locks: List<ElementLock> = emptyList(),
    val stream: ParsedStream? = null,
) {
    val locked: Boolean get() = locks.isNotEmpty()
}

class ParsedPage(
    val pageStream: ParsedStream,
    val root: DrawNode,
    val leaves: List<DrawNode>,
    val ocrLeaves: List<DrawNode> = emptyList(),
) {
    val tokens: List<Any> get() = pageStream.tokens
}

fun findNode(node: DrawNode, id: Int?): DrawNode? {
    if (id == null) return null
    if (node.id == id) return node
    for (c in node.children) findNode(c, id)?.let { return it }
    return null
}

fun collectGroupIds(node: DrawNode, into: MutableSet<Int> = HashSet()): Set<Int> {
    if (node.kind == NodeKind.GROUP) into.add(node.id)
    for (c in node.children) collectGroupIds(c, into)
    return into
}
