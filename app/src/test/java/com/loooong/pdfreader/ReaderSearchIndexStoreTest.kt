package com.loooong.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReaderSearchIndexStoreTest {

    @Test
    fun completedIndexCanBeRestoredByFingerprintAfterProcessRestart() {
        val directory = Files.createTempDirectory("reader-search-index-test").toFile()
        try {
            val fingerprint = PdfFileFingerprint(2048L, "a".repeat(64))
            val pages = listOf(
                ReaderPageTextIndex(0, "第一页 PDF"),
                ReaderPageTextIndex(1, "第二页搜索内容"),
            )

            assertTrue(ReaderSearchIndexStore(directory).save(fingerprint, pages))
            val restored = ReaderSearchIndexStore(directory).load(fingerprint, pageCount = 2)

            assertEquals(listOf("第一页 PDF", "第二页搜索内容"), restored?.map { it.text })
            assertEquals(listOf(0, 1), restored?.map { it.pageIndex })
            assertTrue(restored.orEmpty().none(ReaderPageTextIndex::coordinatesAvailable))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun indexCannotBeLoadedWithAnotherFingerprint() {
        withStore { store, _ ->
            val savedFingerprint = PdfFileFingerprint(2048L, "a".repeat(64))
            val otherFingerprint = PdfFileFingerprint(2048L, "b".repeat(64))

            assertTrue(store.save(savedFingerprint, listOf(ReaderPageTextIndex(0, "正文"))))

            assertNull(store.load(otherFingerprint, pageCount = 1))
        }
    }

    @Test
    fun indexWithDifferentPageCountIsRejected() {
        withStore { store, _ ->
            val fingerprint = PdfFileFingerprint(2048L, "c".repeat(64))
            assertTrue(store.save(fingerprint, listOf(ReaderPageTextIndex(0, "正文"))))

            assertNull(store.load(fingerprint, pageCount = 2))
        }
    }

    @Test
    fun corruptedIndexIsDeletedAndDoesNotThrow() {
        withStore { store, directory ->
            val fingerprint = PdfFileFingerprint(2048L, "d".repeat(64))
            assertTrue(store.save(fingerprint, listOf(ReaderPageTextIndex(0, "正文"))))
            val cacheFile = directory.listFiles().orEmpty().single { it.name.endsWith(".idx.gz") }
            cacheFile.writeBytes(byteArrayOf(1, 2, 3, 4))

            assertNull(store.load(fingerprint, pageCount = 1))
            assertFalse(cacheFile.exists())
        }
    }

    @Test
    fun oversizedPageIsNotPersisted() {
        withStore { store, directory ->
            val fingerprint = PdfFileFingerprint(2048L, "e".repeat(64))
            val oversizedText = "a".repeat(8 * 1024 * 1024 + 1)

            assertFalse(store.save(fingerprint, listOf(ReaderPageTextIndex(0, oversizedText))))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    private fun withStore(block: (ReaderSearchIndexStore, java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("reader-search-index-test").toFile()
        try {
            block(ReaderSearchIndexStore(directory), directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
