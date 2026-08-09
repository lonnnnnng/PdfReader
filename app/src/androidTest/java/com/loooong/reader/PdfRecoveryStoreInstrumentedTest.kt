package com.loooong.reader

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfRecoveryStoreInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PdfRecoveryStore.clear(context, deleteFile = true)
    }

    @After
    fun tearDown() {
        PdfRecoveryStore.clear(context, deleteFile = true)
    }

    @Test
    fun rememberReplaceAndClearManageRecoveryFileLifecycle() {
        val first = File.createTempFile("pdf-recovery-first-", ".pdf", context.cacheDir)
            .apply { writeText("first") }
        PdfRecoveryStore.remember(
            context = context,
            file = first,
            title = "草稿.pdf",
            sourceUri = "content://reader/source.pdf",
            pageIndex = 4,
            canOverwrite = true,
            sourceFingerprint = PdfFileFingerprint.fromFile(first),
        )

        val loaded = PdfRecoveryStore.load(context)
        assertNotNull(loaded)
        requireNotNull(loaded)
        assertEquals(first.absolutePath, loaded.file.absolutePath)
        assertEquals("草稿.pdf", loaded.title)
        assertEquals(4, loaded.pageIndex)
        assertTrue(loaded.canOverwrite)
        assertEquals(PdfFileFingerprint.fromFile(first), loaded.sourceFingerprint)

        val second = File.createTempFile("pdf-recovery-second-", ".pdf", context.cacheDir)
            .apply { writeText("second") }
        PdfRecoveryStore.remember(context, second, "新草稿.pdf", null, 1, false)

        assertFalse(first.exists())
        assertEquals(second.absolutePath, PdfRecoveryStore.load(context)?.file?.absolutePath)
        PdfRecoveryStore.clear(context, deleteFile = true)
        assertFalse(second.exists())
        assertEquals(null, PdfRecoveryStore.load(context))
    }

    @Test
    fun legacyRecoveryWithoutFingerprintLoadsButCannotClaimSafeOverwrite() {
        val file = File.createTempFile("pdf-recovery-legacy-", ".pdf", context.cacheDir)
            .apply { writeText("legacy") }
        val preferences = context.getSharedPreferences("pdf_edit_recovery", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("path", file.absolutePath)
            .putString("title", "旧草稿.pdf")
            .putString("source_uri", "content://reader/legacy.pdf")
            .putInt("page_index", 2)
            .putBoolean("can_overwrite", true)
            .commit()

        val loaded = requireNotNull(PdfRecoveryStore.load(context))
        assertEquals(null, loaded.sourceFingerprint)
        // long: 旧版本没有来源指纹，恢复层只能保留记录，不能把它当作可安全覆盖的来源。
        assertFalse(loaded.canOverwrite)
    }
}
