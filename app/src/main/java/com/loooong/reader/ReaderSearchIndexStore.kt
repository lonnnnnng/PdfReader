package com.loooong.reader

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal class ReaderSearchIndexStore(
    private val directory: File,
) {
    fun save(fingerprint: PdfFileFingerprint, pages: List<ReaderPageTextIndex>): Boolean {
        if (pages.isEmpty() || pages.map(ReaderPageTextIndex::pageIndex) != pages.indices.toList()) return false
        val encodedPages = pages.map { it.text.toByteArray(StandardCharsets.UTF_8) }
        if (encodedPages.any { it.size > MAX_PAGE_TEXT_BYTES }) return false
        if (encodedPages.sumOf(ByteArray::size).toLong() > MAX_TOTAL_TEXT_BYTES) return false
        if (!directory.exists() && !directory.mkdirs()) return false
        if (!directory.isDirectory) return false

        val temporary = runCatching {
            File.createTempFile(".reader-index-", ".tmp", directory)
        }.getOrNull() ?: return false
        return try {
            DataOutputStream(
                BufferedOutputStream(GZIPOutputStream(temporary.outputStream())),
            ).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeLong(fingerprint.length)
                output.write(fingerprint.sha256.toByteArray(StandardCharsets.US_ASCII))
                output.writeInt(encodedPages.size)
                encodedPages.forEachIndexed { pageIndex, bytes ->
                    output.writeInt(pageIndex)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
            if (temporary.length() > MAX_COMPRESSED_FILE_BYTES) return false
            val target = indexFile(fingerprint)
            // long: 优先使用文件系统原子替换，避免进程中断留下半份索引；不支持时再退化为同目录覆盖。
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            target.setLastModified(System.currentTimeMillis())
            prune()
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { temporary.delete() }
        }
    }

    fun load(fingerprint: PdfFileFingerprint, pageCount: Int): List<ReaderPageTextIndex>? {
        if (pageCount <= 0) return null
        val source = indexFile(fingerprint)
        if (!source.isFile || source.length() !in 1..MAX_COMPRESSED_FILE_BYTES) return null
        val restored = runCatching {
            DataInputStream(
                BufferedInputStream(GZIPInputStream(source.inputStream())),
            ).use { input ->
                require(input.readInt() == MAGIC) { "搜索索引格式无效" }
                require(input.readInt() == VERSION) { "搜索索引版本不兼容" }
                require(input.readLong() == fingerprint.length) { "搜索索引文件长度指纹不匹配" }
                val hashBytes = ByteArray(SHA256_TEXT_LENGTH).also(input::readFully)
                require(String(hashBytes, StandardCharsets.US_ASCII) == fingerprint.sha256) {
                    "搜索索引 SHA-256 不匹配"
                }
                require(input.readInt() == pageCount) { "搜索索引页数不匹配" }
                var totalBytes = 0L
                val pages = List(pageCount) { expectedPageIndex ->
                    require(input.readInt() == expectedPageIndex) { "搜索索引页序无效" }
                    val byteCount = input.readInt()
                    require(byteCount in 0..MAX_PAGE_TEXT_BYTES) { "搜索索引单页文本过大" }
                    totalBytes += byteCount
                    require(totalBytes <= MAX_TOTAL_TEXT_BYTES) { "搜索索引文本总量过大" }
                    val bytes = ByteArray(byteCount).also(input::readFully)
                    ReaderPageTextIndex(
                        pageIndex = expectedPageIndex,
                        text = String(bytes, StandardCharsets.UTF_8),
                    )
                }
                require(input.read() == -1) { "搜索索引包含多余数据" }
                pages
            }
        }.getOrElse {
            // long: 损坏缓存不能阻塞 PDF 打开；删除后本次会话会自动重新建立，不影响用户源文件。
            runCatching { source.delete() }
            return null
        }
        source.setLastModified(System.currentTimeMillis())
        return restored
    }

    private fun indexFile(fingerprint: PdfFileFingerprint): File =
        File(directory, "${fingerprint.sha256}-${fingerprint.length}.idx.gz")

    private fun prune() {
        directory.listFiles { file -> file.isFile && file.name.endsWith(".idx.gz") }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_INDEX_FILES)
            .forEach { stale -> runCatching { stale.delete() } }
    }

    private companion object {
        const val MAGIC = 0x52534958
        const val VERSION = 1
        const val SHA256_TEXT_LENGTH = 64
        const val MAX_PAGE_TEXT_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_TEXT_BYTES = 64L * 1024 * 1024
        const val MAX_COMPRESSED_FILE_BYTES = 32L * 1024 * 1024
        const val MAX_INDEX_FILES = 6
    }
}
