package com.loooong.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class PdfFileFingerprintTest {

    @Test
    fun sameBytesProduceStableFingerprint() {
        val bytes = "same-pdf-content".toByteArray()

        val first = PdfFileFingerprint.fromInputStream(ByteArrayInputStream(bytes))
        val second = PdfFileFingerprint.fromInputStream(ByteArrayInputStream(bytes))

        assertEquals(first, second)
        assertEquals(bytes.size.toLong(), first.length)
        assertEquals(64, first.sha256.length)
    }

    @Test
    fun differentBytesDoNotShareFingerprint() {
        val first = PdfFileFingerprint.fromInputStream(ByteArrayInputStream("one".toByteArray()))
        val second = PdfFileFingerprint.fromInputStream(ByteArrayInputStream("two".toByteArray()))

        assertNotEquals(first, second)
    }
}
