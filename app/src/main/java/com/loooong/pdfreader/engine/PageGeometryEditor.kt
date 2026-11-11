package com.loooong.pdfreader.engine

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

data class PageCropMargins(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class PageGeometryInfo(
    val mediaWidth: Float,
    val mediaHeight: Float,
    val margins: PageCropMargins,
) {
    val isCropped: Boolean
        get() = listOf(margins.left, margins.top, margins.right, margins.bottom).any {
            it > CROP_EPSILON
        }

    private companion object {
        const val CROP_EPSILON = 0.01f
    }
}

object PageGeometryEditor {

    fun inspect(page: PDPage): PageGeometryInfo {
        val media = page.mediaBox
        val crop = page.cropBox
        val rotation = page.normalizedRotation()
        val pdfMargins = PageCropMargins(
            left = (crop.lowerLeftX - media.lowerLeftX).coerceAtLeast(0f),
            top = (media.upperRightY - crop.upperRightY).coerceAtLeast(0f),
            right = (media.upperRightX - crop.upperRightX).coerceAtLeast(0f),
            bottom = (crop.lowerLeftY - media.lowerLeftY).coerceAtLeast(0f),
        )
        return PageGeometryInfo(
            mediaWidth = if (rotation == 90 || rotation == 270) media.height else media.width,
            mediaHeight = if (rotation == 90 || rotation == 270) media.width else media.height,
            margins = pdfMargins.toVisualMargins(rotation),
        )
    }

    fun crop(page: PDPage, margins: PageCropMargins) {
        val media = page.mediaBox
        val rotation = page.normalizedRotation()
        val values = listOf(margins.left, margins.top, margins.right, margins.bottom)
        require(values.all { it.isFinite() && it >= 0f }) { "裁剪边距必须是非负有限数值" }
        val visualWidth = if (rotation == 90 || rotation == 270) media.height else media.width
        val visualHeight = if (rotation == 90 || rotation == 270) media.width else media.height
        val visibleWidth = visualWidth - margins.left - margins.right
        val visibleHeight = visualHeight - margins.top - margins.bottom
        // long: 至少保留一个可渲染区域，零尺寸 CropBox 会导致不同 PDF 引擎出现白屏或打开失败。
        require(visibleWidth >= MIN_VISIBLE_POINTS && visibleHeight >= MIN_VISIBLE_POINTS) {
            "裁剪后页面尺寸过小"
        }
        // long: UI 边距按旋转后的视觉方向表达，写入前换回 PDF 坐标，旋转页面的左边不会误裁成原始左边。
        val pdfMargins = margins.toPdfMargins(rotation)
        page.cropBox = PDRectangle(
            media.lowerLeftX + pdfMargins.left,
            media.lowerLeftY + pdfMargins.bottom,
            media.width - pdfMargins.left - pdfMargins.right,
            media.height - pdfMargins.top - pdfMargins.bottom,
        )
        page.cosObject.setNeedToBeUpdated(true)
    }

    fun resetCrop(page: PDPage) {
        page.cosObject.removeItem(COSName.CROP_BOX)
        page.cosObject.setNeedToBeUpdated(true)
    }

    /** long: 页面尺寸调整只改变画布边界并保持原中心，内容和批注的物理尺寸不会因换纸张而被隐式缩放。 */
    fun resize(page: PDPage, visualWidth: Float, visualHeight: Float) {
        require(visualWidth.isFinite() && visualWidth in MIN_PAGE_POINTS..MAX_PAGE_POINTS) {
            "页面宽度必须在 $MIN_PAGE_POINTS-$MAX_PAGE_POINTS pt"
        }
        require(visualHeight.isFinite() && visualHeight in MIN_PAGE_POINTS..MAX_PAGE_POINTS) {
            "页面高度必须在 $MIN_PAGE_POINTS-$MAX_PAGE_POINTS pt"
        }
        val media = page.mediaBox
        val rotation = page.normalizedRotation()
        val pdfWidth = if (rotation == 90 || rotation == 270) visualHeight else visualWidth
        val pdfHeight = if (rotation == 90 || rotation == 270) visualWidth else visualHeight
        val centerX = (media.lowerLeftX + media.upperRightX) / 2f
        val centerY = (media.lowerLeftY + media.upperRightY) / 2f
        page.mediaBox = PDRectangle(
            centerX - pdfWidth / 2f,
            centerY - pdfHeight / 2f,
            pdfWidth,
            pdfHeight,
        )
        // long: 依赖框若继续保留旧坐标可能超出新 MediaBox，移除后按 PDF 规则继承新的完整页面范围。
        listOf(COSName.CROP_BOX, COSName.BLEED_BOX, COSName.TRIM_BOX, COSName.ART_BOX).forEach {
            page.cosObject.removeItem(it)
        }
        page.cosObject.setNeedToBeUpdated(true)
    }

    private const val MIN_VISIBLE_POINTS = 1f
    private const val MIN_PAGE_POINTS = 36f
    private const val MAX_PAGE_POINTS = 20_000f
}

private fun PDPage.normalizedRotation(): Int = ((rotation % 360) + 360) % 360

private fun PageCropMargins.toPdfMargins(rotation: Int): PageCropMargins = when (rotation) {
    90 -> PageCropMargins(left = top, top = right, right = bottom, bottom = left)
    180 -> PageCropMargins(left = right, top = bottom, right = left, bottom = top)
    270 -> PageCropMargins(left = bottom, top = left, right = top, bottom = right)
    else -> this
}

private fun PageCropMargins.toVisualMargins(rotation: Int): PageCropMargins = when (rotation) {
    90 -> PageCropMargins(left = bottom, top = left, right = top, bottom = right)
    180 -> PageCropMargins(left = right, top = bottom, right = left, bottom = top)
    270 -> PageCropMargins(left = top, top = right, right = bottom, bottom = left)
    else -> this
}
