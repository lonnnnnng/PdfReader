package com.loooong.pdfreader

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.loooong.pdfreader.engine.OcrImagePoint
import com.loooong.pdfreader.engine.OcrLine
import com.loooong.pdfreader.engine.OcrPageRecognition
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** long: ML Kit 只负责位图识别并返回中立坐标模型，PDF 写入规则留在引擎层，避免 ViewModel 绑定 SDK 细节。 */
internal class AndroidOcrRecognizer : AutoCloseable {
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build(),
    )

    suspend fun recognize(bitmap: Bitmap): OcrPageRecognition = suspendCancellableCoroutine { continuation ->
        // long: ML Kit 的 Task 不提供可靠的协程取消，Bitmap 必须由 Task 完成回调统一回收，
        // 否则页面退出时协程先结束、底层识别仍读已 recycle 的像素，可能触发 native 崩溃。
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        var recycled = false
        fun recycleOnce() {
            if (!recycled) {
                recycled = true
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) {
                        continuation.resume(
                            OcrPageRecognition(
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
                                lines = result.textBlocks.flatMap(Text.TextBlock::getLines)
                                    .mapNotNull(::toOcrLine),
                            ),
                        )
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                .addOnCompleteListener { recycleOnce() }
        } catch (error: Throwable) {
            recycleOnce()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    override fun close() {
        recognizer.close()
    }

    private fun toOcrLine(line: Text.Line): OcrLine? {
        val text = line.text.trim()
        if (text.isEmpty()) return null
        val corners = line.cornerPoints?.takeIf { it.size >= 4 }
            ?: line.boundingBox?.toCorners()
            ?: return null
        return OcrLine(
            text = text,
            topLeft = corners[0].toOcrPoint(),
            topRight = corners[1].toOcrPoint(),
            bottomRight = corners[2].toOcrPoint(),
            bottomLeft = corners[3].toOcrPoint(),
        )
    }

    private fun Rect.toCorners(): Array<Point> = arrayOf(
        Point(left, top),
        Point(right, top),
        Point(right, bottom),
        Point(left, bottom),
    )

    private fun Point.toOcrPoint(): OcrImagePoint = OcrImagePoint(x.toFloat(), y.toFloat())
}
