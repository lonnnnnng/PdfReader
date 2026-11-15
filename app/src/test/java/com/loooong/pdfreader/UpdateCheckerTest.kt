package com.loooong.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun comparesSemanticVersionsNumerically() {
        assertTrue(isVersionNewer("v0.4.0", "0.3.2"))
        assertTrue(isVersionNewer("v0.10.0", "0.9.9"))
        assertFalse(isVersionNewer("v0.3.2", "0.3.2"))
        assertFalse(isVersionNewer("v0.3.1", "0.3.2"))
    }

    @Test
    fun ignoresVersionPrefixAndSuffix() {
        assertTrue(isVersionNewer("v1.2.4", "1.2.3-debug"))
        assertFalse(isVersionNewer("1.2.3", "v1.2.3"))
    }

    @Test
    fun parsesReleaseMetadataWithoutDownloadAssets() {
        val release = UpdateChecker.parseRelease(
            """
            {
              "tag_name": "v0.4.0",
              "name": "PdfReader 0.4.0",
              "body": "Form editing and settings",
              "html_url": "https://github.com/example/releases/tag/v0.4.0",
              "assets": []
            }
            """.trimIndent(),
        )

        assertEquals("v0.4.0", release.tagName)
        assertEquals("PdfReader 0.4.0", release.name)
        assertEquals("Form editing and settings", release.notes)
        assertEquals("https://github.com/example/releases/tag/v0.4.0", release.releaseUrl)
    }
}
