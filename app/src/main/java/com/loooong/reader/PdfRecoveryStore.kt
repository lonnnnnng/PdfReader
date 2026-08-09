package com.loooong.reader

import android.content.Context
import java.io.File

data class PdfRecoveryInfo(
    val file: File,
    val title: String,
    val sourceUri: String?,
    val sourceFingerprint: PdfFileFingerprint?,
    val pageIndex: Int,
    val canOverwrite: Boolean,
    val updatedAt: Long,
)

/** long: 恢复记录只指向已经完成 PDFBox 写入的工作文件，不保存半写入流或用户原文件。 */
internal object PdfRecoveryStore {
    private const val PREFS = "pdf_edit_recovery"
    private const val KEY_PATH = "path"
    private const val KEY_TITLE = "title"
    private const val KEY_SOURCE_URI = "source_uri"
    private const val KEY_SOURCE_LENGTH = "source_length"
    private const val KEY_SOURCE_SHA256 = "source_sha256"
    private const val KEY_PAGE_INDEX = "page_index"
    private const val KEY_CAN_OVERWRITE = "can_overwrite"
    private const val KEY_UPDATED_AT = "updated_at"

    fun load(context: Context): PdfRecoveryInfo? {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = preferences.getString(KEY_PATH, null) ?: return null
        val file = File(path)
        if (!file.isFile || file.length() == 0L) {
            clear(context, deleteFile = false)
            return null
        }
        val sourceFingerprint = runCatching {
            val length = preferences.getLong(KEY_SOURCE_LENGTH, -1L)
            val sha256 = preferences.getString(KEY_SOURCE_SHA256, null)
            if (length < 0L || sha256.isNullOrBlank()) null else PdfFileFingerprint(length, sha256)
        }.getOrNull()
        return PdfRecoveryInfo(
            file = file,
            title = preferences.getString(KEY_TITLE, null)?.takeIf(String::isNotBlank) ?: "未保存的 PDF",
            sourceUri = preferences.getString(KEY_SOURCE_URI, null)?.takeIf(String::isNotBlank),
            sourceFingerprint = sourceFingerprint,
            pageIndex = preferences.getInt(KEY_PAGE_INDEX, 0).coerceAtLeast(0),
            // long: 旧版本恢复记录没有来源指纹，不能证明原文件仍是同一份内容，因此恢复后必须降级为只读另存。
            canOverwrite = preferences.getBoolean(KEY_CAN_OVERWRITE, false) && sourceFingerprint != null,
            updatedAt = preferences.getLong(KEY_UPDATED_AT, file.lastModified()),
        )
    }

    fun remember(
        context: Context,
        file: File,
        title: String,
        sourceUri: String?,
        pageIndex: Int,
        canOverwrite: Boolean,
        sourceFingerprint: PdfFileFingerprint? = null,
    ) {
        require(file.isFile && file.length() > 0L) { "恢复文件不可用" }
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousPath = preferences.getString(KEY_PATH, null)
        val committed = preferences.edit()
            .putString(KEY_PATH, file.absolutePath)
            .putString(KEY_TITLE, title)
            .putString(KEY_SOURCE_URI, sourceUri)
            .putInt(KEY_PAGE_INDEX, pageIndex.coerceAtLeast(0))
            // long: 写入恢复记录时也做一次防御性降级，避免调用方把没有来源指纹的草稿标成可覆盖。
            .putBoolean(KEY_CAN_OVERWRITE, canOverwrite && sourceFingerprint != null)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply {
                if (sourceFingerprint == null) {
                    remove(KEY_SOURCE_LENGTH)
                    remove(KEY_SOURCE_SHA256)
                } else {
                    putLong(KEY_SOURCE_LENGTH, sourceFingerprint.length)
                    putString(KEY_SOURCE_SHA256, sourceFingerprint.sha256)
                }
            }
            .commit()
        check(committed) { "无法记录 PDF 恢复状态" }
        if (previousPath != null && previousPath != file.absolutePath) {
            runCatching { File(previousPath).delete() }
        }
    }

    fun clear(context: Context, deleteFile: Boolean) {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = preferences.getString(KEY_PATH, null)?.let(::File)
        preferences.edit().clear().commit()
        if (deleteFile) runCatching { file?.delete() }
    }
}
