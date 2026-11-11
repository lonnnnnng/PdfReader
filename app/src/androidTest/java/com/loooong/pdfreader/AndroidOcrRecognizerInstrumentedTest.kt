package com.loooong.pdfreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 架构裁剪后必须调用真实 OCR，只有成功识别才能证明原生库和离线模型仍然齐备。 */
@RunWith(AndroidJUnit4::class)
class AndroidOcrRecognizerInstrumentedTest {
    @Test
    fun arm64PackageLoadsOfflineOcrAndRecognizesRasterText() = runBlocking {
        val bitmap = Bitmap.createBitmap(1400, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 110f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("PDF READER", 70f, 160f, paint)
        canvas.drawText("2026", 70f, 320f, paint)
        AndroidOcrRecognizer().use { recognizer ->
            // long: recognize 接管位图并在 ML Kit Task 完成时回收，测试不能抢先释放像素。
            val result = withTimeout(30_000) { recognizer.recognize(bitmap) }
            val text = result.lines.joinToString(" ") { it.text }.uppercase()
            assertTrue("识别结果：$text", text.contains("PDF") && text.contains("2026"))
        }
    }
}
