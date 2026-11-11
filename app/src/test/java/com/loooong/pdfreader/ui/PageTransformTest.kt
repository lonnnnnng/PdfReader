package com.loooong.pdfreader.ui

import androidx.compose.ui.geometry.Offset
import com.loooong.pdfreader.ReaderPageInfo
import com.loooong.pdfreader.engine.Bounds
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransformTest {

    @Test
    fun unrotatedBitmapDeltaMapsBackToPdfAxes() {
        val transform = PageTransform(0f, 0f, 300f, 400f, 0, 2f)

        val delta = transform.toUserDelta(Offset(20f, -10f))

        assertEquals(10f, delta.x, 1e-4f)
        assertEquals(5f, delta.y, 1e-4f)
    }

    @Test
    fun rotatedBitmapDeltaMapsBackToPdfAxes() {
        val transform = PageTransform(0f, 0f, 300f, 400f, 90, 2f)

        val delta = transform.toUserDelta(Offset(20f, 10f))

        assertEquals(5f, delta.x, 1e-4f)
        assertEquals(10f, delta.y, 1e-4f)
        assertEquals(-30f, transform.toUserRotation(30f), 1e-4f)
    }

    @Test
    fun readerLinkBoundsRespectCropOriginAndPageRotation() {
        val source = Bounds(20f, 30f, 40f, 50f)
        val expectedByRotation = mapOf(
            0 to floatArrayOf(20f, 340f, 60f, 380f),
            90 to floatArrayOf(20f, 20f, 60f, 60f),
            180 to floatArrayOf(140f, 20f, 180f, 60f),
            270 to floatArrayOf(340f, 140f, 380f, 180f),
        )

        expectedByRotation.forEach { (rotation, expected) ->
            val visualWidth = if (rotation == 90 || rotation == 270) 200f else 100f
            val visualHeight = if (rotation == 90 || rotation == 270) 100f else 200f
            val rect = requireNotNull(
                mapReaderLinkBounds(
                    info = ReaderPageInfo(
                        pageIndex = 0,
                        widthPoints = visualWidth,
                        heightPoints = visualHeight,
                        cropX = 10f,
                        cropY = 20f,
                        cropWidthPoints = 100f,
                        cropHeightPoints = 200f,
                        rotation = rotation,
                    ),
                    bounds = source,
                    pageWidthPx = visualWidth * 2f,
                    pageHeightPx = visualHeight * 2f,
                ),
            )

            assertEquals(expected[0], rect.left, 1e-4f)
            assertEquals(expected[1], rect.top, 1e-4f)
            assertEquals(expected[2], rect.right, 1e-4f)
            assertEquals(expected[3], rect.bottom, 1e-4f)
        }
    }
}
