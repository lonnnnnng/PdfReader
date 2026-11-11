package com.loooong.pdfreader

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineUpdaterTest {
    @Test
    fun rejectsOtherAppsSignaturesAndDowngradesBeforeInstallation() {
        validateUpdateIdentity("new", "new", 30, 29, setOf("signer"), setOf("signer"))
        listOf(
            { validateUpdateIdentity("old", "new", 30, 29, setOf("signer"), setOf("signer")) },
            { validateUpdateIdentity("new", "new", 29, 29, setOf("signer"), setOf("signer")) },
            { validateUpdateIdentity("new", "new", 30, 29, setOf("other"), setOf("signer")) },
            { validateUpdateIdentity("new", "new", 30, 29, emptySet(), emptySet()) },
        ).forEach { invalid ->
            try { invalid(); throw AssertionError("不兼容的更新包必须被拒绝") }
            catch (_: IllegalArgumentException) { }
        }
    }


    @Test
    fun calculatesBoundedDownloadProgress() {
        assertEquals(25, updateProgressPercent(25L, 100L))
        assertEquals(100, updateProgressPercent(150L, 100L))
        assertNull(updateProgressPercent(0L, -1L))
    }

    @Test
    fun mapsActionableDownloadFailuresToChineseMessages() {
        assertEquals(
            "下载失败：设备存储空间不足",
            updateDownloadFailureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE),
        )
        assertEquals(
            "下载失败：服务器返回 HTTP 404",
            updateDownloadFailureMessage(404),
        )
    }

    @Test
    fun keepsApkAssetNameAndFallsBackToSafeVersionName() {
        assertEquals(
            "reader-0.6.0.apk",
            updateApkFileName(
                "https://github.com/example/releases/download/v0.6.0/reader-0.6.0.apk?download=1",
                "v0.6.0",
            ),
        )
        assertEquals(
            "PdfReader-v0.6.0-beta_1.apk",
            updateApkFileName("https://example.com/download/latest", "v0.6.0-beta+1"),
        )
    }
}
