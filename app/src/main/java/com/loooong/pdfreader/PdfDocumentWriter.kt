package com.loooong.pdfreader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs

internal object PdfDocumentWriter {
    /** long: 将文档写入一个新目标。新建目标可能是只写 SAF Provider，因此不能先读取目标内容，
     * 也不能把新建目标误当作需要恢复的原文件；临时文件校验通过后才打开目标输出流。 */
    fun writeNewTarget(
        document: PDDocument,
        openOutput: () -> OutputStream?,
        stagingFile: File,
        validationPassword: String? = null,
        openReadback: (() -> InputStream?)? = null,
    ): PdfWriteResult {
        try {
            writeValidated(document, stagingFile, validationPassword)
            val expectedSummary = summarize(document)
            val expectedFingerprint = PdfFileFingerprint.fromFile(stagingFile)
            copyFileToDestination(stagingFile, openOutput)
            val targetFingerprint = openReadback?.let {
                verifyDestination(
                    expectedFile = stagingFile,
                    openReadback = it,
                    validationPassword = validationPassword,
                    expectedSummary = expectedSummary,
                    requireReadback = false,
                )
            }
            return PdfWriteResult(
                fingerprint = targetFingerprint ?: expectedFingerprint,
                // long: 只有回读并重新解析过 SAF 目标，后续才允许把它当作可安全覆盖的来源。
                targetVerified = targetFingerprint != null,
            )
        } finally {
            runCatching { stagingFile.delete() }
        }
    }

    /**
     * long: 数字签名文件已经通过增量写入生成，不能再调用 PDDocument.save()；这里只负责校验、复制和回读，
     * 保证 SAF 目标与已验证的签名临时文件逐字节一致。
     */
    fun writePreparedNewTarget(
        stagingFile: File,
        openOutput: () -> OutputStream?,
        openReadback: (() -> InputStream?)? = null,
        validate: (File) -> Unit,
    ): PdfWriteResult {
        try {
            require(stagingFile.isFile) { "待保存的 PDF 临时文件不存在" }
            validate(stagingFile)
            val expectedFingerprint = PdfFileFingerprint.fromFile(stagingFile)
            copyFileToDestination(stagingFile, openOutput)
            val targetFingerprint = openReadback?.let { readback ->
                verifyPreparedDestination(stagingFile, readback, validate)
            }
            return PdfWriteResult(
                fingerprint = targetFingerprint ?: expectedFingerprint,
                targetVerified = targetFingerprint != null,
            )
        } finally {
            runCatching { stagingFile.delete() }
        }
    }

    fun overwrite(
        document: PDDocument,
        openCurrent: () -> InputStream?,
        openOutput: () -> OutputStream?,
        stagingFile: File,
        backupFile: File,
        validationPassword: String? = null,
        openReadback: () -> InputStream?,
        expectedCurrent: PdfFileFingerprint? = null,
    ): PdfWriteResult = writeWithBackup(
            document = document,
            openCurrent = openCurrent,
            openOutput = openOutput,
            stagingFile = stagingFile,
            backupFile = backupFile,
            validationPassword = validationPassword,
            openReadback = openReadback,
            expectedCurrent = expectedCurrent,
            unreadableMessage = "无法读取原始 PDF，已取消覆盖保存",
            restoredMessage = "覆盖保存失败，原文件已恢复",
            restoreFailedMessage = "覆盖保存失败，且无法恢复原文件",
        )

    private fun writeWithBackup(
        document: PDDocument,
        openCurrent: () -> InputStream?,
        openOutput: () -> OutputStream?,
        stagingFile: File,
        backupFile: File,
        validationPassword: String?,
        openReadback: () -> InputStream?,
        expectedCurrent: PdfFileFingerprint?,
        unreadableMessage: String,
        restoredMessage: String,
        restoreFailedMessage: String,
    ): PdfWriteResult {
        var keepBackup = false
        try {
            writeValidated(document, stagingFile, validationPassword)
            val expectedSummary = summarize(document)
            backupFile.parentFile?.mkdirs()
            val current = requireNotNull(openCurrent()) { unreadableMessage }
            current.buffered().use { source ->
                backupFile.outputStream().buffered().use(source::copyTo)
            }
            val backupFingerprint = PdfFileFingerprint.fromFile(backupFile)
            if (expectedCurrent != null && backupFingerprint != expectedCurrent) {
                // long: 在真正打开 "wt" 之前再次核对备份内容，消除预检查与写入之间的竞态窗口。
                throw PdfSourceConflictException("原 PDF 已被其他应用修改")
            }

            try {
                copyFileToDestination(stagingFile, openOutput)
                val targetFingerprint = verifyDestination(
                    expectedFile = stagingFile,
                    openReadback = openReadback,
                    validationPassword = validationPassword,
                    expectedSummary = expectedSummary,
                    requireReadback = true,
                )
                return PdfWriteResult(
                    fingerprint = requireNotNull(targetFingerprint),
                    targetVerified = true,
                )
            } catch (writeFailure: Throwable) {
                // 来源冲突发生在目标尚未打开之前，不能为了恢复而再次触碰用户文件。
                if (writeFailure is PdfSourceConflictException) throw writeFailure
                // long: SAF 输出流可能在打开时就截断原文件。覆盖失败后立即回写备份，尽量保证用户仍能打开原文档。
                val restoreFailure = runCatching {
                    copyFileToDestination(backupFile, openOutput)
                    verifyDestination(
                        expectedFile = backupFile,
                        openReadback = openReadback,
                        validationPassword = validationPassword,
                        expectedSummary = summarizeFile(backupFile, validationPassword),
                        requireReadback = true,
                    )
                }.exceptionOrNull()
                if (restoreFailure != null) {
                    // long: Provider 可能已经截断原件，双重失败后这份备份是唯一原始数据，必须交给用户导出。
                    keepBackup = true
                    writeFailure.addSuppressed(restoreFailure)
                    throw PdfBackupRecoveryException(backupFile, restoreFailedMessage, writeFailure)
                }
                throw IOException(
                    restoredMessage,
                    writeFailure,
                )
            }
        } finally {
            runCatching { stagingFile.delete() }
            if (!keepBackup) runCatching { backupFile.delete() }
        }
    }

    private fun writeValidated(document: PDDocument, stagingFile: File, validationPassword: String?) {
        stagingFile.parentFile?.mkdirs()
        stagingFile.outputStream().buffered().use { PdfSerialization.write(document, it) }

        // long: 重新解析完整临时文件并核对关键文档结构，只有序列化结果可用时才允许触碰 SAF 目标。
        validateSavedFile(
            file = stagingFile,
            validationPassword = validationPassword,
            expected = summarize(document),
        )
    }

    private fun validateSavedFile(
        file: File,
        validationPassword: String?,
        expected: DocumentSummary,
    ) {
        PDDocument.load(file, validationPassword.orEmpty()).use { saved ->
            val actual = summarize(saved)
            check(actual.pageCount == expected.pageCount) { "导出的 PDF 页数校验失败" }
            check(actual.encrypted == expected.encrypted) { "导出的 PDF 加密状态校验失败" }
            check(actual.pages.size == expected.pages.size) { "导出的 PDF 页面结构校验失败" }
            expected.pages.zip(actual.pages).forEachIndexed { index, (before, after) ->
                check(before.matches(after)) { "导出的 PDF 第 ${index + 1} 页尺寸或旋转校验失败" }
            }
            check(actual.annotationSubtypes == expected.annotationSubtypes) {
                "导出的 PDF 批注结构校验失败"
            }
            check(actual.formFieldNames == expected.formFieldNames) {
                "导出的 PDF 表单字段校验失败"
            }
            check(actual.outlineTitles == expected.outlineTitles) {
                "导出的 PDF 目录结构校验失败"
            }
        }
    }

    private fun verifyDestination(
        expectedFile: File,
        openReadback: () -> InputStream?,
        validationPassword: String?,
        expectedSummary: DocumentSummary,
        requireReadback: Boolean,
    ): PdfFileFingerprint? {
        val input = try {
            openReadback()
        } catch (failure: Throwable) {
            if (requireReadback) throw failure
            return null
        }
        if (input == null) {
            if (requireReadback) throw IOException("无法回读保存目标，已取消确认")
            return null
        }

        // long: 校验文件必须放在 expectedFile 同目录，Android 沙盒的默认临时目录不保证可写，
        // 而同目录还能避免跨文件系统复制造成的额外失败。
        val verificationDirectory = expectedFile.parentFile?.takeIf { it.isDirectory }
            ?: throw IOException("无法创建保存目标校验文件")
        val verificationFile = File.createTempFile(".pdf-target-verify-", ".pdf", verificationDirectory)
        return try {
            try {
                input.buffered().use { source ->
                    verificationFile.outputStream().buffered().use(source::copyTo)
                }
            } catch (readFailure: IOException) {
                if (!requireReadback) {
                    // long: 新建副本的 Provider 可能允许写入却在回读中途断流；保留副本但禁止后续覆盖，
                    // 让用户仍能使用已生成文件，同时不把未验证目标当成可靠来源。
                    return null
                }
                throw readFailure
            }
            val expectedFingerprint = PdfFileFingerprint.fromFile(expectedFile)
            val actualFingerprint = PdfFileFingerprint.fromFile(verificationFile)
            check(actualFingerprint == expectedFingerprint) { "保存目标内容校验失败，文件可能被截断" }
            validateSavedFile(verificationFile, validationPassword, expectedSummary)
            actualFingerprint
        } finally {
            runCatching { verificationFile.delete() }
        }
    }

    private fun verifyPreparedDestination(
        expectedFile: File,
        openReadback: () -> InputStream?,
        validate: (File) -> Unit,
    ): PdfFileFingerprint? {
        val input = try {
            openReadback()
        } catch (_: Throwable) {
            return null
        } ?: return null
        val verificationDirectory = expectedFile.parentFile?.takeIf { it.isDirectory }
            ?: throw IOException("无法创建签名目标校验文件")
        val verificationFile = File.createTempFile(".pdf-signature-verify-", ".pdf", verificationDirectory)
        return try {
            try {
                input.buffered().use { source ->
                    verificationFile.outputStream().buffered().use(source::copyTo)
                }
            } catch (_: IOException) {
                return null
            }
            val expectedFingerprint = PdfFileFingerprint.fromFile(expectedFile)
            val actualFingerprint = PdfFileFingerprint.fromFile(verificationFile)
            check(actualFingerprint == expectedFingerprint) { "签名保存目标内容校验失败，文件可能被截断" }
            validate(verificationFile)
            actualFingerprint
        } finally {
            runCatching { verificationFile.delete() }
        }
    }

    private fun summarizeFile(file: File, password: String?): DocumentSummary =
        PDDocument.load(file, password.orEmpty()).use(::summarize)

    private data class PageSummary(
        val mediaLeft: Float,
        val mediaBottom: Float,
        val mediaWidth: Float,
        val mediaHeight: Float,
        val cropLeft: Float,
        val cropBottom: Float,
        val cropWidth: Float,
        val cropHeight: Float,
        val rotation: Int,
    ) {
        fun matches(other: PageSummary): Boolean =
            rotation == other.rotation &&
                listOf(
                    mediaLeft to other.mediaLeft,
                    mediaBottom to other.mediaBottom,
                    mediaWidth to other.mediaWidth,
                    mediaHeight to other.mediaHeight,
                    cropLeft to other.cropLeft,
                    cropBottom to other.cropBottom,
                    cropWidth to other.cropWidth,
                    cropHeight to other.cropHeight,
                ).all { (a, b) -> abs(a - b) <= 0.01f }
    }

    private data class DocumentSummary(
        val pageCount: Int,
        val pages: List<PageSummary>,
        val annotationSubtypes: List<List<String>>,
        val formFieldNames: List<String>?,
        val outlineTitles: List<String>?,
        val encrypted: Boolean,
    )

    private fun summarize(document: PDDocument): DocumentSummary {
        val pages = (0 until document.numberOfPages).map { index ->
            val page = document.getPage(index)
            val media = page.mediaBox
            val crop = page.cropBox
            PageSummary(
                mediaLeft = media.lowerLeftX,
                mediaBottom = media.lowerLeftY,
                mediaWidth = media.width,
                mediaHeight = media.height,
                cropLeft = crop.lowerLeftX,
                cropBottom = crop.lowerLeftY,
                cropWidth = crop.width,
                cropHeight = crop.height,
                rotation = ((page.rotation % 360) + 360) % 360,
            )
        }
        val annotationSubtypes = pages.indices.map { index ->
            document.getPage(index).getAnnotations().map { it.subtype.orEmpty() }
        }
        val formFieldNames = runCatching {
            document.documentCatalog.acroForm?.fieldTree?.map { field ->
                "${field.fullyQualifiedName.orEmpty()}|${field.javaClass.name}"
            }?.sorted()
        }.getOrNull()
        val outlineTitles = runCatching {
            val root = document.documentCatalog.documentOutline ?: return@runCatching null
            val result = ArrayList<String>()
            collectOutlineTitles(root, result)
            result
        }.getOrNull()
        return DocumentSummary(
            pageCount = document.numberOfPages,
            pages = pages,
            annotationSubtypes = annotationSubtypes,
            formFieldNames = formFieldNames,
            outlineTitles = outlineTitles,
            // long: 刚设置或移除保护时 isEncrypted 仍反映旧 trailer，按待写入的保护状态校验。
            encrypted = document.encryption != null && !document.isAllSecurityToBeRemoved,
        )
    }

    private fun collectOutlineTitles(
        node: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode,
        into: MutableList<String>,
    ) {
        node.children().forEach { item ->
            into += item.title.orEmpty()
            if (item.hasChildren()) collectOutlineTitles(item, into)
        }
    }

    private fun copyFileToDestination(file: File, openOutput: () -> OutputStream?) {
        // long: "wt" 可能立即截断目标，因此必须在临时 PDF 校验和覆盖备份完成之后调用。
        val destination = openOutput() ?: throw IOException("无法打开所选保存位置")
        destination.buffered().use { target ->
            file.inputStream().buffered().use { source -> source.copyTo(target) }
            target.flush()
        }
    }
}

internal class PdfBackupRecoveryException(
    val backupFile: File,
    message: String,
    cause: Throwable,
) : IOException(message, cause)

internal data class PdfWriteResult(
    val fingerprint: PdfFileFingerprint,
    val targetVerified: Boolean,
)
