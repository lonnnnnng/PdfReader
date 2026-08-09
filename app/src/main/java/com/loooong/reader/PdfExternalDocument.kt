package com.loooong.reader

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream

/** long: 分享和打印共用独立缓存目录；临时 PDF 不写回用户来源文件，且过期文件会在下一次操作前清理。 */
object PdfExternalFileStore {
    private const val DIRECTORY = "external-pdf"
    private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

    fun create(context: Context, prefix: String): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
        return File.createTempFile(prefix, ".pdf", directory)
    }
}

/** long: 打印框架只接收打印适配器输出的 PDF 字节；直接复制已校验的临时文件，避免在打印线程重解析内容流。 */
class PdfFilePrintAdapter(
    private val source: File,
    private val title: String,
    private val pageCount: Int,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder(title)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pageCount.coerceAtLeast(1))
                .build(),
            oldAttributes != newAttributes,
        )
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        try {
            if (cancellationSignal.isCanceled) {
                callback.onWriteCancelled()
                return
            }
            FileInputStream(source).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (cancellationSignal.isCanceled) {
                            callback.onWriteCancelled()
                            return
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (cancelled: Throwable) {
            if (cancellationSignal.isCanceled) callback.onWriteCancelled()
            else callback.onWriteFailed(cancelled.message ?: "打印文件准备失败")
        }
    }

    override fun onFinish() {
        runCatching { source.delete() }
    }
}
