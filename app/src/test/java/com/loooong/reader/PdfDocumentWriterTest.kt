package com.loooong.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class PdfDocumentWriterTest {

    @Test(expected = IOException::class)
    fun nullDestinationIsRejected() {
        val staging = File.createTempFile("pdf-writer-test-", ".pdf")
        PDDocument().use { document ->
            PdfDocumentWriter.writeNewTarget(
                document = document,
                openOutput = { null },
                stagingFile = staging,
            )
        }
    }

    @Test
    fun documentIsWrittenToDestination() {
        val output = ByteArrayOutputStream()
        val staging = File.createTempFile("pdf-writer-test-", ".pdf")

        PDDocument().use { document ->
            document.addPage(PDPage())
            val result = PdfDocumentWriter.writeNewTarget(
                document = document,
                openOutput = { output },
                stagingFile = staging,
                openReadback = { ByteArrayInputStream(output.toByteArray()) },
            )
            assertTrue(result.targetVerified)
        }

        val bytes = output.toByteArray()
        assertTrue(bytes.copyOfRange(0, 4).contentEquals("%PDF".toByteArray()))
        PDDocument.load(bytes).use { saved -> assertEquals(1, saved.numberOfPages) }
        assertTrue(!staging.exists())
    }

    @Test
    fun newTargetDoesNotReadExistingBytesAndCleansTemporaryFilesOnFailure() {
        val staging = File.createTempFile("pdf-copy-failure-", ".pdf")
        var outputOpened = false

        PDDocument().use { document ->
            document.addPage(PDPage())
            try {
                PdfDocumentWriter.writeNewTarget(
                    document = document,
                    openOutput = {
                        outputOpened = true
                        FailingOutputStream()
                    },
                    stagingFile = staging,
                )
                fail("副本写入失败时应抛出异常")
            } catch (expected: IOException) {
                assertEquals("模拟目标写入失败", expected.message)
            }
        }

        assertTrue(outputOpened)
        assertFalse(staging.exists())
    }

    @Test
    fun newTargetCanBeWrittenWhenProviderHasNoReadableCurrentStream() {
        val output = ByteArrayOutputStream()
        val staging = File.createTempFile("pdf-copy-write-only-", ".pdf")

        PDDocument().use { document ->
            document.addPage(PDPage())
            val result = PdfDocumentWriter.writeNewTarget(
                document = document,
                openOutput = { output },
                stagingFile = staging,
            )
            assertFalse(result.targetVerified)
        }

        assertTrue(output.toByteArray().copyOfRange(0, 4).contentEquals("%PDF".toByteArray()))
        assertFalse(staging.exists())
    }

    @Test
    fun newTargetReadbackMismatchIsRejectedAndStagingFileIsRemoved() {
        val output = ByteArrayOutputStream()
        val staging = File.createTempFile("pdf-copy-mismatch-", ".pdf")
        val providerBytes = createPdf(pageCount = 2)

        PDDocument().use { document ->
            document.addPage(PDPage())
            try {
                PdfDocumentWriter.writeNewTarget(
                    document = document,
                    openOutput = { output },
                    stagingFile = staging,
                    openReadback = { ByteArrayInputStream(providerBytes) },
                )
                fail("目标回读内容不一致时应拒绝确认保存")
            } catch (expected: IllegalStateException) {
                assertEquals("保存目标内容校验失败，文件可能被截断", expected.message)
            }
        }

        assertFalse(staging.exists())
    }

    @Test
    fun newTargetReadbackIoFailureKeepsCopyButMarksItUnverified() {
        val output = ByteArrayOutputStream()
        val staging = File.createTempFile("pdf-copy-readback-io-", ".pdf")

        PDDocument().use { document ->
            document.addPage(PDPage())
            val result = PdfDocumentWriter.writeNewTarget(
                document = document,
                openOutput = { output },
                stagingFile = staging,
                openReadback = { FailingInputStream() },
            )
            assertFalse(result.targetVerified)
        }

        assertTrue(output.toByteArray().copyOfRange(0, 4).contentEquals("%PDF".toByteArray()))
        assertFalse(staging.exists())
    }

    @Test
    fun preparedSignedFileIsCopiedWithoutReserializingAndValidatedAfterReadback() {
        val preparedBytes = createPdf(pageCount = 2)
        val staging = File.createTempFile("pdf-prepared-signature-", ".pdf").apply {
            writeBytes(preparedBytes)
        }
        val output = ByteArrayOutputStream()
        var validationCount = 0

        val result = PdfDocumentWriter.writePreparedNewTarget(
            stagingFile = staging,
            openOutput = { output },
            openReadback = { ByteArrayInputStream(output.toByteArray()) },
            validate = { file ->
                validationCount++
                PDDocument.load(file).use { document -> assertEquals(2, document.numberOfPages) }
            },
        )

        assertTrue(result.targetVerified)
        assertEquals(2, validationCount)
        assertTrue(preparedBytes.contentEquals(output.toByteArray()))
        assertFalse(staging.exists())
    }

    @Test
    fun preparedSignedFileRejectsReadbackThatDiffersFromValidatedBytes() {
        val staging = File.createTempFile("pdf-prepared-signature-mismatch-", ".pdf").apply {
            writeBytes(createPdf(pageCount = 1))
        }
        val output = ByteArrayOutputStream()
        try {
            PdfDocumentWriter.writePreparedNewTarget(
                stagingFile = staging,
                openOutput = { output },
                openReadback = { ByteArrayInputStream(createPdf(pageCount = 2)) },
                validate = { file -> PDDocument.load(file).use { } },
            )
            fail("签名目标回读字节不一致时应拒绝确认")
        } catch (expected: IllegalStateException) {
            assertEquals("签名保存目标内容校验失败，文件可能被截断", expected.message)
        }
        assertFalse(staging.exists())
    }

    @Test
    fun encryptedDocumentCanBeValidatedEditedAndCopiedAgainWithSessionPassword() {
        val output = ByteArrayOutputStream()
        val secondOutput = ByteArrayOutputStream()
        val staging = File.createTempFile("pdf-encrypted-copy-", ".pdf")
        val secondStaging = File.createTempFile("pdf-encrypted-copy-again-", ".pdf")
        val password = "reader-password"

        PDDocument().use { document ->
            document.addPage(PDPage())
            document.protect(StandardProtectionPolicy("owner-password", password, AccessPermission()))
            PdfDocumentWriter.writeNewTarget(
                document = document,
                openOutput = { output },
                stagingFile = staging,
                validationPassword = password,
            )
            document.addPage(PDPage())
            PdfDocumentWriter.writeNewTarget(
                document = document,
                openOutput = { secondOutput },
                stagingFile = secondStaging,
                validationPassword = password,
            )
        }

        PDDocument.load(secondOutput.toByteArray(), password).use { saved ->
            assertTrue(saved.isEncrypted)
            assertEquals(2, saved.numberOfPages)
        }
        assertFalse(staging.exists())
        assertFalse(secondStaging.exists())
    }

    @Test
    fun overwriteCreatesValidatedDocumentAndCleansTemporaryFiles() {
        val original = createPdf(pageCount = 1)
        val output = ByteArrayOutputStream()
        val staging = File.createTempFile("pdf-overwrite-test-", ".pdf")
        val backup = File.createTempFile("pdf-backup-test-", ".pdf")

        PDDocument().use { document ->
            repeat(2) { document.addPage(PDPage()) }
            PdfDocumentWriter.overwrite(
                document = document,
                openCurrent = { ByteArrayInputStream(original) },
                openOutput = { output },
                stagingFile = staging,
                backupFile = backup,
                openReadback = { ByteArrayInputStream(output.toByteArray()) },
            )
        }

        PDDocument.load(output.toByteArray()).use { saved ->
            assertEquals(2, saved.numberOfPages)
        }
        assertFalse(staging.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun failedOverwriteRestoresOriginalBytesAndCleansTemporaryFiles() {
        val original = createPdf(pageCount = 1)
        val restored = ByteArrayOutputStream()
        val staging = File.createTempFile("pdf-overwrite-failure-", ".pdf")
        val backup = File.createTempFile("pdf-backup-failure-", ".pdf")
        var outputAttempt = 0

        PDDocument().use { document ->
            document.addPage(PDPage())
            document.addPage(PDPage())
            try {
                PdfDocumentWriter.overwrite(
                    document = document,
                    openCurrent = { ByteArrayInputStream(original) },
                    openOutput = {
                        outputAttempt++
                        if (outputAttempt == 1) FailingOutputStream() else restored
                    },
                    stagingFile = staging,
                    backupFile = backup,
                    openReadback = { ByteArrayInputStream(restored.toByteArray()) },
                )
                fail("覆盖失败时应抛出异常")
            } catch (expected: IOException) {
                assertEquals("覆盖保存失败，原文件已恢复", expected.message)
            }
        }

        assertTrue(original.contentEquals(restored.toByteArray()))
        assertEquals(2, outputAttempt)
        assertFalse(staging.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun overwriteDetectsExternalModificationBeforeOpeningDestination() {
        val expected = createPdf(pageCount = 1)
        val changed = createPdf(pageCount = 2)
        val staging = File.createTempFile("pdf-overwrite-conflict-", ".pdf")
        val backup = File.createTempFile("pdf-backup-conflict-", ".pdf")
        var outputOpened = false

        PDDocument().use { document ->
            document.addPage(PDPage())
            try {
                PdfDocumentWriter.overwrite(
                    document = document,
                    openCurrent = { ByteArrayInputStream(changed) },
                    openOutput = {
                        outputOpened = true
                        ByteArrayOutputStream()
                    },
                    stagingFile = staging,
                    backupFile = backup,
                    openReadback = { ByteArrayInputStream(changed) },
                    expectedCurrent = PdfFileFingerprint.fromInputStream(ByteArrayInputStream(expected)),
                )
                fail("来源发生变化时应报告冲突")
            } catch (_: PdfSourceConflictException) {
                // expected
            }
        }

        assertFalse(outputOpened)
        assertFalse(staging.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun truncatedReadbackRestoresOriginalBytes() {
        val original = createPdf(pageCount = 1)
        val destination = TruncatingDestination()
        val staging = File.createTempFile("pdf-overwrite-short-", ".pdf")
        val backup = File.createTempFile("pdf-backup-short-", ".pdf")

        PDDocument().use { document ->
            document.addPage(PDPage())
            try {
                PdfDocumentWriter.overwrite(
                    document = document,
                    openCurrent = { ByteArrayInputStream(original) },
                    openOutput = destination::openOutput,
                    stagingFile = staging,
                    backupFile = backup,
                    openReadback = destination::openInput,
                )
                fail("目标短写时应恢复原文件并抛出异常")
            } catch (expected: IOException) {
                assertEquals("覆盖保存失败，原文件已恢复", expected.message)
            }
        }

        assertTrue(original.contentEquals(destination.bytes))
        assertEquals(2, destination.openCount)
        assertFalse(staging.exists())
        assertFalse(backup.exists())
    }

    private fun createPdf(pageCount: Int): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            repeat(pageCount) { document.addPage(PDPage()) }
            document.save(output)
        }
        return output.toByteArray()
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(value: Int) {
            throw IOException("模拟目标写入失败")
        }
    }

    private class FailingInputStream : java.io.InputStream() {
        override fun read(): Int = throw IOException("模拟回读失败")
    }

    private class TruncatingDestination {
        var bytes: ByteArray = ByteArray(0)
            private set
        var openCount: Int = 0
            private set

        fun openOutput(): OutputStream {
            val truncate = openCount++ == 0
            val sink = ByteArrayOutputStream()
            val limit = if (truncate) 128 else Int.MAX_VALUE
            return object : OutputStream() {
                override fun write(value: Int) {
                    if (sink.size() < limit) sink.write(value)
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    val remaining = (limit - sink.size()).coerceAtLeast(0)
                    if (remaining > 0) sink.write(buffer, offset, minOf(length, remaining))
                }

                override fun close() {
                    bytes = sink.toByteArray()
                }
            }
        }

        fun openInput() = ByteArrayInputStream(bytes)
    }
}
