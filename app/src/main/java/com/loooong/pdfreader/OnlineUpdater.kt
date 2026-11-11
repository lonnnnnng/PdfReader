package com.loooong.pdfreader

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import java.net.URI

data class PendingUpdateDownload(
    val downloadId: Long,
    val tagName: String,
    val sha256: String,
)

sealed interface UpdateDownloadSnapshot {
    data class Waiting(
        val progressPercent: Int?,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val message: String,
    ) : UpdateDownloadSnapshot

    data object Ready : UpdateDownloadSnapshot
    data class Failed(val message: String) : UpdateDownloadSnapshot
    data object Missing : UpdateDownloadSnapshot
}

sealed interface UpdateInstallResult {
    data object Started : UpdateInstallResult
    data class Failed(val message: String) : UpdateInstallResult
}

class OnlineUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun restorePending(currentVersion: String): PendingUpdateDownload? {
        val pending = storedPending() ?: return null
        if (!isVersionNewer(pending.tagName, currentVersion)) {
            // long: 应用已经升级到目标版本后清理旧 APK，避免设置页继续提示安装已完成的版本。
            downloadManager.remove(pending.downloadId)
            clearPending()
            return null
        }
        return pending
    }

    fun startDownload(release: ReleaseInfo): PendingUpdateDownload {
        val downloadUrl = requireNotNull(release.downloadUrl) { "该版本没有可下载的 APK" }
        require(URI(downloadUrl).scheme.equals("https", ignoreCase = true)) { "更新下载地址不安全" }
        val expectedHash = requireNotNull(release.sha256) { "发布包缺少 SHA256 校验信息，请前往发布页下载" }
        storedPending()?.let { downloadManager.remove(it.downloadId) }

        val fileName = updateApkFileName(downloadUrl, release.tagName)
        val relativePath = "updates/${System.currentTimeMillis()}-$fileName"
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("PdfReader ${release.tagName}")
            .setDescription("正在下载应用更新")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, relativePath)

        val pending = PendingUpdateDownload(
            downloadId = downloadManager.enqueue(request),
            tagName = release.tagName,
            sha256 = expectedHash,
        )
        preferences.edit {
            putLong(KEY_DOWNLOAD_ID, pending.downloadId)
            putString(KEY_TAG_NAME, pending.tagName)
            putString(KEY_SHA256, pending.sha256)
        }
        return pending
    }

    fun query(pending: PendingUpdateDownload): UpdateDownloadSnapshot {
        val query = DownloadManager.Query().setFilterById(pending.downloadId)
        return downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use UpdateDownloadSnapshot.Missing
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadSnapshot.Ready
                DownloadManager.STATUS_FAILED -> UpdateDownloadSnapshot.Failed(
                    updateDownloadFailureMessage(reason),
                )
                DownloadManager.STATUS_PAUSED -> UpdateDownloadSnapshot.Waiting(
                    progressPercent = updateProgressPercent(downloaded, total),
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    message = "下载已暂停，等待网络恢复",
                )
                DownloadManager.STATUS_RUNNING -> UpdateDownloadSnapshot.Waiting(
                    progressPercent = updateProgressPercent(downloaded, total),
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    message = "正在下载更新",
                )
                else -> UpdateDownloadSnapshot.Waiting(
                    progressPercent = updateProgressPercent(downloaded, total),
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    message = "等待开始下载",
                )
            }
        } ?: UpdateDownloadSnapshot.Missing
    }

    fun canRequestPackageInstalls(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }

    fun install(pending: PendingUpdateDownload): UpdateInstallResult {
        if (query(pending) !is UpdateDownloadSnapshot.Ready) {
            return UpdateInstallResult.Failed("APK 尚未下载完成")
        }
        if (!canRequestPackageInstalls()) {
            return UpdateInstallResult.Failed("请先允许PdfReader安装未知来源应用")
        }

        val apkUri = downloadManager.getUriForDownloadedFile(pending.downloadId)
            ?: return UpdateInstallResult.Failed("无法读取已下载的 APK")
        val packageInstaller = appContext.packageManager.packageInstaller
        var sessionId: Int? = null
        var validatedApk: File? = null
        return try {
            val apk = File.createTempFile("verified-update-", ".apk", appContext.cacheDir)
            validatedApk = apk
            appContext.contentResolver.openInputStream(apkUri).use { input ->
                requireNotNull(input) { "无法读取更新包" }
                apk.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            validateArchive(apk, pending)
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(appContext.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                }
            }
            sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    requireNotNull(input) { "无法打开已下载的 APK" }
                    session.openWrite("base.apk", 0, -1).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                // long: PackageInstaller 通过可变 PendingIntent 回填安装状态，接收器再负责拉起系统确认页。
                val statusIntent = Intent(appContext, UpdateInstallReceiver::class.java).apply {
                    action = ACTION_UPDATE_INSTALL_STATUS
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val statusSender = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    statusIntent,
                    flags,
                ).intentSender
                session.commit(statusSender)
            }
            UpdateInstallResult.Started
        } catch (t: Throwable) {
            sessionId?.let { runCatching { packageInstaller.abandonSession(it) } }
            UpdateInstallResult.Failed(t.message?.takeIf { it.isNotBlank() } ?: "无法启动系统安装器")
        } finally {
            validatedApk?.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun validateArchive(apk: File, pending: PendingUpdateDownload) {
        // long: 校验的是私有副本，安装会话也读取同一文件，公共下载目录的后续变化不能替换已校验字节。
        require(PdfFileFingerprint.fromFile(apk).sha256.equals(pending.sha256, ignoreCase = true)) {
            "更新包 SHA256 不匹配，请重新下载"
        }
        val manager = appContext.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = requireNotNull(manager.getPackageArchiveInfo(apk.absolutePath, flags)) { "更新包无法解析" }
        val installed = manager.getPackageInfo(appContext.packageName, flags)
        validateUpdateIdentity(
            candidate.packageName, installed.packageName,
            if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong(),
            if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong(),
            archiveSigners(candidate), archiveSigners(installed),
        )
        require(candidate.versionName == pending.tagName.removePrefix("v")) { "更新包版本与发布信息不一致" }
    }

    @Suppress("DEPRECATION")
    private fun archiveSigners(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toSet()
    }

    fun clearFailedDownload(pending: PendingUpdateDownload) {
        downloadManager.remove(pending.downloadId)
        clearPending()
    }

    private fun storedPending(): PendingUpdateDownload? {
        val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        val tagName = preferences.getString(KEY_TAG_NAME, null).orEmpty()
        val sha256 = preferences.getString(KEY_SHA256, null).orEmpty()
        return if (downloadId >= 0L && tagName.isNotBlank() && sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            PendingUpdateDownload(downloadId, tagName, sha256)
        } else {
            null
        }
    }

    private fun clearPending() {
        preferences.edit {
            remove(KEY_DOWNLOAD_ID)
            remove(KEY_TAG_NAME)
            remove(KEY_SHA256)
        }
    }

    private companion object {
        const val PREFS_NAME = "online_update"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_TAG_NAME = "tag_name"
        const val KEY_SHA256 = "sha256"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_INSTALL_STATUS) return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmation == null) {
                    Toast.makeText(context, "无法打开系统安装确认页", Toast.LENGTH_LONG).show()
                } else {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "更新安装完成", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Toast.makeText(context, "已取消安装更新", Toast.LENGTH_LONG).show()
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "系统安装器返回错误 $status"
                Toast.makeText(context, "安装失败：$message", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun updateProgressPercent(downloadedBytes: Long, totalBytes: Long): Int? =
    if (downloadedBytes >= 0L && totalBytes > 0L) {
        ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
    } else {
        null
    }

internal fun updateDownloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "下载失败：设备存储空间不足"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载失败：存储设备不可用"
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "下载失败：更新文件已存在，请重试"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载失败：服务器重定向次数过多"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
    DownloadManager.ERROR_HTTP_DATA_ERROR,
    -> "下载失败：服务器连接异常"
    in 400..599 -> "下载失败：服务器返回 HTTP $reason"
    else -> "下载失败，请检查网络和存储空间后重试"
}

internal fun updateApkFileName(downloadUrl: String, tagName: String): String {
    val candidate = runCatching { URI(downloadUrl).path.substringAfterLast('/') }.getOrNull()
    val safeCandidate = candidate
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
    if (safeCandidate != null) return safeCandidate
    val safeTag = tagName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
    return "PdfReader-$safeTag.apk"
}

internal const val ACTION_UPDATE_INSTALL_STATUS =
    "com.loooong.pdfreader.action.UPDATE_INSTALL_STATUS"

/** long: 新旧应用共用仓库历史，安装前必须同时满足包名、递增版本码和固定发布证书。 */
internal fun validateUpdateIdentity(
    candidatePackage: String,
    installedPackage: String,
    candidateVersion: Long,
    installedVersion: Long,
    candidateSigners: Set<String>,
    installedSigners: Set<String>,
) {
    require(candidatePackage == installedPackage) { "更新包属于其他应用" }
    require(candidateVersion > installedVersion) { "更新包版本不高于当前版本" }
    require(candidateSigners.isNotEmpty() && candidateSigners == installedSigners) { "更新包签名不匹配" }
}
