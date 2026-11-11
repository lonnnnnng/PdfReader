package com.loooong.pdfreader.engine

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PdfCompressionOptions(
    val jpegQuality: Int,
    val maxImageDimension: Int,
    val minimumSavingsPercent: Int,
) {
    init {
        require(jpegQuality in 30..95) { "JPEG 质量必须在 30-95 之间" }
        require(maxImageDimension in 640..4096) { "图片最长边必须在 640-4096 像素之间" }
        require(minimumSavingsPercent in 1..50) { "最小压缩收益必须在 1%-50% 之间" }
    }
}

enum class PdfCompressionPreset(val options: PdfCompressionOptions) {
    LIGHT(PdfCompressionOptions(jpegQuality = 86, maxImageDimension = 2400, minimumSavingsPercent = 5)),
    BALANCED(PdfCompressionOptions(jpegQuality = 76, maxImageDimension = 1600, minimumSavingsPercent = 8)),
    STRONG(PdfCompressionOptions(jpegQuality = 64, maxImageDimension = 1200, minimumSavingsPercent = 10)),
}

data class PdfCompressionReport(
    val scannedImages: Int,
    val compressedImages: Int,
    val skippedProtectedImages: Int,
    val skippedUnprofitableImages: Int,
    val skippedUnreadableImages: Int,
    val originalEncodedBytes: Long,
    val compressedEncodedBytes: Long,
) {
    val changed: Boolean get() = compressedImages > 0
    val estimatedSavedBytes: Long
        get() = (originalEncodedBytes - compressedEncodedBytes).coerceAtLeast(0L)
}

data class PdfCompressionProgress(
    val processedImages: Int,
    val totalImages: Int,
    val compressedImages: Int,
)

/** long: PDF 压缩只修改明确可安全重编码的位图资源，文本、矢量内容和页面结构始终保持原对象。 */
object PdfCompressionEditor {

    fun compress(
        document: PDDocument,
        options: PdfCompressionOptions,
        onProgress: (PdfCompressionProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PdfCompressionReport {
        val candidates = collectImageCandidates(document)
        var processed = 0
        var compressed = 0
        var protected = 0
        var unprofitable = 0
        var unreadable = 0
        var originalBytes = 0L
        var compressedBytes = 0L

        candidates.forEach { candidate ->
            if (isCancelled()) throw CancellationException("PDF 压缩已取消")
            when (val outcome = transcode(document, candidate.image, options)) {
                CompressionOutcome.Protected -> protected++
                CompressionOutcome.Unprofitable -> unprofitable++
                CompressionOutcome.Unreadable -> unreadable++
                is CompressionOutcome.Compressed -> {
                    // long: 同一图像流可能被多个页面或 Form 资源引用；统一替换全部资源名，避免只压缩其中一个视觉实例。
                    candidate.references.forEach { reference ->
                        reference.resources.put(reference.name, outcome.replacement)
                        reference.resources.cosObject.isNeedToBeUpdated = true
                        (reference.resources.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)
                            ?.isNeedToBeUpdated = true
                    }
                    outcome.replacement.cosObject.isNeedToBeUpdated = true
                    compressed++
                    originalBytes += outcome.originalBytes
                    compressedBytes += outcome.compressedBytes
                }
            }
            processed++
            onProgress(PdfCompressionProgress(processed, candidates.size, compressed))
        }

        return PdfCompressionReport(
            scannedImages = processed,
            compressedImages = compressed,
            skippedProtectedImages = protected,
            skippedUnprofitableImages = unprofitable,
            skippedUnreadableImages = unreadable,
            originalEncodedBytes = originalBytes,
            compressedEncodedBytes = compressedBytes,
        )
    }

    private fun collectImageCandidates(document: PDDocument): List<ImageCandidate> {
        val visitedResources = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        val candidatesByStream = IdentityHashMap<COSStream, ImageCandidate>()
        val orderedCandidates = ArrayList<ImageCandidate>()

        fun visit(resources: PDResources?) {
            resources ?: return
            if (!visitedResources.add(resources.cosObject)) return
            resources.xObjectNames.forEach { name ->
                when (val xObject = runCatching { resources.getXObject(name) }.getOrNull()) {
                    is PDImageXObject -> {
                        val stream = xObject.cosObject
                        val candidate = candidatesByStream[stream] ?: ImageCandidate(xObject).also {
                            candidatesByStream[stream] = it
                            orderedCandidates += it
                        }
                        candidate.references += ImageReference(resources, name)
                    }
                    is PDFormXObject -> visit(xObject.resources)
                }
            }
        }

        repeat(document.numberOfPages) { pageIndex -> visit(document.getPage(pageIndex).resources) }
        return orderedCandidates
    }

    private fun transcode(
        document: PDDocument,
        image: PDImageXObject,
        options: PdfCompressionOptions,
    ): CompressionOutcome {
        if (isProtectedImage(image)) return CompressionOutcome.Protected
        val originalBytes = image.cosObject.length.takeIf { it > 0L }
            ?: return CompressionOutcome.Unreadable

        var decoded: Bitmap? = null
        var working: Bitmap? = null
        return try {
            val subsampling = decodeSubsampling(image, options.maxImageDimension)
            decoded = if (subsampling > 1) image.getImage(null, subsampling) else image.image
            val source = decoded ?: return CompressionOutcome.Unreadable
            val bitmap = scaleToLimit(source, options.maxImageDimension).also { working = it }

            val encoded = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, options.jpegQuality, output)) {
                    "Android 无法编码当前图片"
                }
                output.toByteArray()
            }
            val candidateBytes = encoded.size.toLong()
            val savedBytes = originalBytes - candidateBytes
            val savingsPercent = if (originalBytes == 0L) 0.0 else savedBytes * 100.0 / originalBytes
            if (savedBytes <= 0L || savingsPercent + 0.0001 < options.minimumSavingsPercent) {
                return CompressionOutcome.Unprofitable
            }

            val replacement = JPEGFactory.createFromByteArray(document, encoded).apply {
                interpolate = image.interpolate
                runCatching { metadata = image.metadata }
                runCatching { optionalContent = image.optionalContent }
                image.structParent.takeIf { it >= 0 }?.let { structParent = it }
            }
            CompressionOutcome.Compressed(
                replacement = replacement,
                originalBytes = originalBytes,
                compressedBytes = candidateBytes,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            CompressionOutcome.Unreadable
        } finally {
            val scaled = working
            val source = decoded
            if (scaled != null && scaled !== source && !scaled.isRecycled) scaled.recycle()
            if (source != null && !source.isRecycled) source.recycle()
        }
    }

    private fun isProtectedImage(image: PDImageXObject): Boolean {
        val dictionary = image.cosObject
        if (image.isStencil || image.bitsPerComponent <= 1) return true
        if (dictionary.containsKey(COSName.MASK) || dictionary.containsKey(COSName.SMASK)) return true
        val colorSpace = runCatching { image.colorSpace }.getOrNull() ?: return true
        // long: 仅把设备灰度/RGB/CMYK 渲染为 JPEG；专色、ICC、Pattern 等颜色空间需保留原始色彩语义，不能静默转成 RGB。
        return colorSpace.javaClass.name !in SAFE_COLOR_SPACES
    }

    private fun decodeSubsampling(image: PDImageXObject, maxDimension: Int): Int {
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val dimensionFactor = maxOf(width, height).toDouble() / maxDimension
        val pixelFactor = sqrt(width.toDouble() * height / MAX_DECODE_PIXELS)
        return ceil(maxOf(1.0, dimensionFactor, pixelFactor)).toInt().coerceAtLeast(1)
    }

    private fun scaleToLimit(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source
        val factor = maxDimension.toDouble() / longest
        val width = (source.width * factor).roundToInt().coerceAtLeast(1)
        val height = (source.height * factor).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private data class ImageReference(
        val resources: PDResources,
        val name: COSName,
    )

    private data class ImageCandidate(
        val image: PDImageXObject,
        val references: MutableList<ImageReference> = ArrayList(),
    )

    private sealed interface CompressionOutcome {
        data object Protected : CompressionOutcome
        data object Unprofitable : CompressionOutcome
        data object Unreadable : CompressionOutcome
        data class Compressed(
            val replacement: PDImageXObject,
            val originalBytes: Long,
            val compressedBytes: Long,
        ) : CompressionOutcome
    }

    private val SAFE_COLOR_SPACES = setOf(
        "com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray",
        "com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB",
        "com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceCMYK",
    )
    private const val MAX_DECODE_PIXELS = 8_000_000.0
}
