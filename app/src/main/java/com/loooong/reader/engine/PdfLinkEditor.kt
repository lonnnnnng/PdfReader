package com.loooong.reader.engine

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import java.net.URI
import java.util.Locale

data class PdfLinkId(
    val pageIndex: Int,
    val annotationIndex: Int,
)

enum class PdfLinkKind {
    URI,
    PAGE,
    UNSUPPORTED,
    BROKEN,
}

sealed interface PdfLinkTarget {
    data class Uri(val value: String) : PdfLinkTarget

    data class Page(
        val pageIndex: Int,
        val left: Int? = null,
        val top: Int? = null,
        val zoom: Float? = null,
    ) : PdfLinkTarget
}

data class PdfLinkCreateRequest(
    val pageIndex: Int,
    val bounds: Bounds,
    val target: PdfLinkTarget,
)

data class PdfLinkUpdateRequest(
    val bounds: Bounds,
    val target: PdfLinkTarget,
)

data class PdfLinkInfo(
    val id: PdfLinkId,
    val bounds: Bounds?,
    val kind: PdfLinkKind,
    val uri: String? = null,
    val targetPageIndex: Int? = null,
    val targetLeft: Int? = null,
    val targetTop: Int? = null,
    val targetZoom: Float? = null,
    val description: String,
    val editable: Boolean,
)

data class PdfLinkUriValidation(
    val normalized: String? = null,
    val scheme: String? = null,
    val error: String? = null,
) {
    val allowed: Boolean get() = normalized != null && error == null
}

/** long: PDF 中的 URI 动作来自不受信任文档，编辑写入和阅读点击必须共用同一协议白名单。 */
object PdfLinkUriPolicy {

    fun validate(raw: String): PdfLinkUriValidation {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return PdfLinkUriValidation(error = "链接地址不能为空")
        if (normalized.length > MAX_URI_LENGTH) {
            return PdfLinkUriValidation(error = "链接地址不能超过 $MAX_URI_LENGTH 个字符")
        }
        if (normalized.any { it.code < 0x20 || it.code == 0x7F }) {
            return PdfLinkUriValidation(error = "链接地址包含不允许的控制字符")
        }
        val parsed = runCatching { URI(normalized) }.getOrElse {
            return PdfLinkUriValidation(error = "链接地址格式无效")
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            ?: return PdfLinkUriValidation(error = "链接地址需要包含协议")
        if (scheme !in ALLOWED_SCHEMES) {
            return PdfLinkUriValidation(
                scheme = scheme,
                error = "出于安全考虑，不允许打开 $scheme 协议链接",
            )
        }
        when (scheme) {
            "http", "https" -> if (parsed.host.isNullOrBlank() && parsed.rawAuthority.isNullOrBlank()) {
                return PdfLinkUriValidation(scheme = scheme, error = "网页链接缺少有效域名")
            }
            "mailto" -> if (parsed.rawSchemeSpecificPart?.substringBefore('?').isNullOrBlank()) {
                return PdfLinkUriValidation(scheme = scheme, error = "邮件链接缺少收件地址")
            }
        }
        return PdfLinkUriValidation(normalized = normalized, scheme = scheme)
    }

    fun requireAllowed(raw: String): String {
        val validation = validate(raw)
        require(validation.allowed) { validation.error ?: "链接地址不可用" }
        return requireNotNull(validation.normalized)
    }

    private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")
    private const val MAX_URI_LENGTH = 2_048
}

/** long: 页面链接的动作字典和批注区域统一在此维护，避免 UI 直接拼装容易产生 /A 与 /Dest 冲突的底层对象。 */
object PdfLinkEditor {

    fun list(document: PDDocument): List<PdfLinkInfo> = buildList {
        repeat(document.numberOfPages) { pageIndex ->
            document.getPage(pageIndex).annotations.forEachIndexed { annotationIndex, annotation ->
                val link = annotation as? PDAnnotationLink ?: return@forEachIndexed
                add(readLink(document, pageIndex, annotationIndex, link))
            }
        }
    }

    fun create(document: PDDocument, request: PdfLinkCreateRequest): PdfLinkId {
        require(request.pageIndex in 0 until document.numberOfPages) { "链接页码超出范围" }
        require(request.bounds.isValid && request.bounds.width >= 1f && request.bounds.height >= 1f) {
            "链接区域无效"
        }
        val page = document.getPage(request.pageIndex)
        val link = PDAnnotationLink().apply {
            rectangle = request.bounds.toRectangle()
            borderStyle = PDBorderStyleDictionary().apply { width = 0f }
            highlightMode = PDAnnotationLink.HIGHLIGHT_MODE_INVERT
            action = when (val target = request.target) {
                is PdfLinkTarget.Uri -> PDActionURI().apply { uri = PdfLinkUriPolicy.requireAllowed(target.value) }
                is PdfLinkTarget.Page -> PDActionGoTo().apply {
                    destination = pageDestination(document, target)
                }
            }
            this.page = page
        }
        val annotations = page.annotations.toMutableList().apply { add(link) }
        page.annotations = annotations
        ElementEditor.markPageUpdated(document, page)
        return PdfLinkId(request.pageIndex, annotations.lastIndex)
    }

    fun update(document: PDDocument, id: PdfLinkId, request: PdfLinkUpdateRequest): Boolean {
        require(id.pageIndex in 0 until document.numberOfPages) { "链接页码超出范围" }
        require(request.bounds.isValid && request.bounds.width >= 1f && request.bounds.height >= 1f) {
            "链接区域无效"
        }
        val page = document.getPage(id.pageIndex)
        val link = page.annotations.getOrNull(id.annotationIndex) as? PDAnnotationLink ?: return false
        if (!list(document).firstOrNull { it.id == id }?.editable.orFalse()) return false
        link.cosObject.removeItem(COSName.A)
        link.cosObject.removeItem(COSName.getPDFName("Dest"))
        link.rectangle = request.bounds.toRectangle()
        link.action = when (val target = request.target) {
            is PdfLinkTarget.Uri -> PDActionURI().apply { uri = PdfLinkUriPolicy.requireAllowed(target.value) }
            is PdfLinkTarget.Page -> PDActionGoTo().apply {
                destination = pageDestination(document, target)
            }
        }
        link.page = page
        ElementEditor.markPageUpdated(document, page)
        return true
    }

    fun delete(document: PDDocument, id: PdfLinkId): Boolean {
        require(id.pageIndex in 0 until document.numberOfPages) { "链接页码超出范围" }
        val page = document.getPage(id.pageIndex)
        val link = page.annotations.getOrNull(id.annotationIndex) as? PDAnnotationLink ?: return false
        if (!list(document).firstOrNull { it.id == id }?.editable.orFalse()) return false
        page.annotations = page.annotations.toMutableList().apply { removeAt(id.annotationIndex) }
        ElementEditor.markPageUpdated(document, page)
        return true
    }

    private fun readLink(
        document: PDDocument,
        pageIndex: Int,
        annotationIndex: Int,
        link: PDAnnotationLink,
    ): PdfLinkInfo {
        val id = PdfLinkId(pageIndex, annotationIndex)
        val bounds = link.rectangle?.toBounds()
        return runCatching {
            when (val action = link.action) {
                is PDActionURI -> PdfLinkInfo(
                    id = id,
                    bounds = bounds,
                    kind = PdfLinkKind.URI,
                    uri = action.uri,
                    description = action.uri?.takeIf(String::isNotBlank) ?: "空 URI 链接",
                    editable = bounds != null && !action.uri.isNullOrBlank(),
                )
                is PDActionGoTo -> readPageLink(id, bounds, document, action.destination)
                null -> {
                    val destination = link.destination
                    if (destination == null) {
                        PdfLinkInfo(
                            id = id,
                            bounds = bounds,
                            kind = PdfLinkKind.BROKEN,
                            description = "链接没有目标",
                            editable = false,
                        )
                    } else {
                        readPageLink(id, bounds, document, destination)
                    }
                }
                else -> PdfLinkInfo(
                    id = id,
                    bounds = bounds,
                    kind = PdfLinkKind.UNSUPPORTED,
                    description = action?.javaClass?.simpleName ?: "未识别的链接目标",
                    editable = false,
                )
            }
        }.getOrElse { error ->
            PdfLinkInfo(
                id = id,
                bounds = bounds,
                kind = PdfLinkKind.BROKEN,
                description = error.message ?: "链接结构损坏",
                editable = false,
            )
        }
    }

    private fun readPageLink(
        id: PdfLinkId,
        bounds: Bounds?,
        document: PDDocument,
        destination: PDDestination?,
    ): PdfLinkInfo {
        val pageDestination = destination as? PDPageDestination
            ?: return PdfLinkInfo(
                id = id,
                bounds = bounds,
                kind = PdfLinkKind.UNSUPPORTED,
                description = "命名链接目标",
                editable = false,
            )
        val pageIndex = PdfPageTransferInspector.resolvePageIndex(document, pageDestination)
        if (pageIndex == null) {
            return PdfLinkInfo(
                id = id,
                bounds = bounds,
                kind = PdfLinkKind.BROKEN,
                description = "无法解析页内链接目标",
                editable = false,
            )
        }
        val xyz = pageDestination as? PDPageXYZDestination
        return PdfLinkInfo(
            id = id,
            bounds = bounds,
            kind = PdfLinkKind.PAGE,
            targetPageIndex = pageIndex,
            targetLeft = xyz?.left?.takeIf { it >= 0 },
            targetTop = xyz?.top?.takeIf { it >= 0 },
            targetZoom = xyz?.zoom?.takeIf { it.isFinite() && it > 0f },
            description = "跳转到第 ${pageIndex + 1} 页",
            editable = bounds != null,
        )
    }

    private fun pageDestination(document: PDDocument, target: PdfLinkTarget.Page): PDPageXYZDestination {
        require(target.pageIndex in 0 until document.numberOfPages) { "链接目标页超出范围" }
        require(target.zoom == null || target.zoom.isFinite() && target.zoom in 0f..100f) { "链接缩放比例无效" }
        return PDPageXYZDestination().apply {
            page = document.getPage(target.pageIndex)
            target.left?.let { left = it }
            target.top?.let { top = it }
            target.zoom?.let { zoom = it }
        }
    }

    private fun Bounds.toRectangle(): PDRectangle = PDRectangle(minX, minY, width, height)

    private fun PDRectangle.toBounds(): Bounds = Bounds(lowerLeftX, lowerLeftY, upperRightX, upperRightY)

    private fun Boolean?.orFalse(): Boolean = this == true

}
