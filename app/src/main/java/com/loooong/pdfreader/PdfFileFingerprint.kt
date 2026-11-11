package com.loooong.pdfreader

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * long: 用实际字节内容识别 PDF 来源，而不是依赖 SAF Provider 不一定提供的修改时间。
 * 这样覆盖保存前可以发现其他应用已经替换了用户正在编辑的原文件。
 */
data class PdfFileFingerprint(
    val length: Long,
    val sha256: String,
) {
    init {
        require(length >= 0L) { "文件长度不能为负数" }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "SHA-256 指纹格式无效"
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        fun fromFile(file: File): PdfFileFingerprint {
            if (!file.isFile) throw IOException("文件不存在，无法计算指纹")
            file.inputStream().buffered().use { input ->
                return fromInputStream(input)
            }
        }

        /** long: 不关闭调用方持有的流，便于来源复制、备份和 SAF 回读在同一事务中管理资源。 */
        fun fromInputStream(input: InputStream): PdfFileFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            var length = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                length += read
            }
            val hash = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
            return PdfFileFingerprint(length, hash)
        }
    }
}

/** long: 覆盖写入过程中再次发现来源变化时，必须让上层展示冲突，而不是把它当成普通 I/O 失败。 */
class PdfSourceConflictException(message: String) : IOException(message)

enum class PdfSaveOutcome {
    SAVED,
    FAILED,
    CONFLICT,
}
