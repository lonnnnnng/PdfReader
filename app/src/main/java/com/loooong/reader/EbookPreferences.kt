package com.loooong.reader

import android.content.Context
import androidx.core.content.edit
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

enum class EbookPageTheme {
    SYSTEM,
    LIGHT,
    DARK,
    SEPIA,
}

data class EbookReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineHeight: Float = 1.55f,
    val horizontalPaddingDp: Float = 20f,
    val pageTheme: EbookPageTheme = EbookPageTheme.SYSTEM,
)

enum class EbookSourceKind {
    LOCAL,
    ONLINE,
}

data class EbookHistoryEntry(
    val sourceId: String,
    val title: String,
    val format: EbookFormat,
    val sourceKind: EbookSourceKind,
    val updatedAt: Long,
    val cacheFileName: String? = null,
    val progress: Float? = null,
)

data class EbookBookmark(
    val sourceId: String,
    val format: EbookFormat,
    val paragraphIndex: Int = 0,
    val locatorJson: String? = null,
    val locationKey: String? = null,
    val title: String,
    val progress: Float? = null,
    val createdAt: Long,
)

internal fun ebookBookmarkKey(bookmark: EbookBookmark): String =
    when (bookmark.format) {
        EbookFormat.TXT -> "${bookmark.sourceId}:TXT:${bookmark.paragraphIndex}"
        EbookFormat.EPUB -> {
            val location = bookmark.locationKey
                ?: epubBookmarkLocationKey(bookmark.locatorJson)
                ?: bookmark.locatorJson.orEmpty()
            "${bookmark.sourceId}:EPUB:$location"
        }
    }

internal fun epubBookmarkLocationKey(locatorJson: String?): String? {
    val raw = locatorJson?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        val locator = JSONObject(raw)
        val href = locator.optString("href").takeIf(String::isNotBlank) ?: return@runCatching null
        val resourceHref = href.substringBefore('#')
        val hrefFragment = href.substringAfter('#', missingDelimiterValue = "").takeIf(String::isNotBlank)
        val locations = locator.optJSONObject("locations") ?: JSONObject()
        val fragments = locations.optJSONArray("fragments")
            ?.takeIf { it.length() > 0 }
            ?.toString()
        val position = locations.optInt("position", 0).takeIf { it > 0 }
        val progression = locations.optDouble("progression", Double.NaN).takeIf(Double::isFinite)
        val totalProgression = locations.optDouble("totalProgression", Double.NaN).takeIf(Double::isFinite)
        // long: EPUB 的标题、上下文和总进度会随排版变化；优先用 CFI/position 等稳定位置去重，避免同一书签重复添加。
        val anchor = when {
            fragments != null -> "fragments:$fragments"
            hrefFragment != null -> "fragment:$hrefFragment"
            position != null -> "position:$position"
            progression != null -> "progression:${progression.toBookmarkBucket()}"
            totalProgression != null -> "total:${totalProgression.toBookmarkBucket()}"
            else -> "resource"
        }
        "$resourceHref|$anchor"
    }.getOrNull()
}

internal fun toggledEbookBookmarks(
    existing: List<EbookBookmark>,
    bookmark: EbookBookmark,
    limit: Int = MAX_EBOOK_BOOKMARKS_PER_BOOK,
): List<EbookBookmark> {
    if (limit <= 0) return emptyList()
    val key = ebookBookmarkKey(bookmark)
    return if (existing.any { ebookBookmarkKey(it) == key }) {
        existing.filterNot { ebookBookmarkKey(it) == key }
    } else {
        buildList {
            add(bookmark)
            existing.asSequence()
                .filterNot { ebookBookmarkKey(it) == key }
                .take(limit - 1)
                .forEach(::add)
        }
    }
}

private fun Double.toBookmarkBucket(): Int =
    (coerceIn(0.0, 1.0) * EPUB_BOOKMARK_PROGRESSION_BUCKETS).roundToInt()

internal fun mergeEbookHistory(
    existing: List<EbookHistoryEntry>,
    latest: EbookHistoryEntry,
    limit: Int = 20,
): List<EbookHistoryEntry> = if (limit <= 0) {
    emptyList()
} else {
    val previous = existing.firstOrNull { it.sourceId == latest.sourceId }
    val mergedLatest = if (latest.progress == null && previous?.progress != null) {
        latest.copy(progress = previous.progress)
    } else {
        latest
    }
    buildList {
        add(mergedLatest)
        existing.asSequence()
            .filterNot { it.sourceId == latest.sourceId }
            .take(limit - 1)
            .forEach(::add)
    }
}

class EbookPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadHistory(): List<EbookHistoryEntry> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        EbookHistoryEntry(
                            sourceId = item.getString("sourceId"),
                            title = item.optString("title", "电子书"),
                            format = EbookFormat.valueOf(item.getString("format")),
                            sourceKind = EbookSourceKind.valueOf(item.getString("sourceKind")),
                            updatedAt = item.optLong("updatedAt", 0L),
                            cacheFileName = item.optString("cacheFileName").takeIf { it.isNotBlank() },
                            progress = item.optDouble("progress", Double.NaN)
                                .takeUnless(Double::isNaN)
                                ?.toFloat()
                                ?.coerceIn(0f, 1f),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun recordHistory(entry: EbookHistoryEntry) {
        val merged = mergeEbookHistory(loadHistory(), entry)
        saveHistory(merged)
    }

    fun removeHistory(sourceId: String) {
        saveHistory(loadHistory().filterNot { it.sourceId == sourceId })
        removeBookmarks(sourceId)
    }

    fun removePosition(sourceId: String) {
        val root = runCatching {
            JSONObject(preferences.getString(KEY_POSITIONS, null) ?: "{}")
        }.getOrElse { JSONObject() }
        root.remove(sourceId)
        preferences.edit { putString(KEY_POSITIONS, root.toString()) }
    }

    fun updateHistoryProgress(sourceId: String, progress: Float) {
        saveHistory(
            loadHistory().map { entry ->
                if (entry.sourceId == sourceId) entry.copy(progress = progress.coerceIn(0f, 1f)) else entry
            },
        )
    }

    fun loadBookmarks(sourceId: String): List<EbookBookmark> {
        val raw = runCatching {
            JSONObject(preferences.getString(KEY_BOOKMARKS, null) ?: "{}").optJSONArray(sourceId)
        }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until raw.length().coerceAtMost(MAX_EBOOK_BOOKMARKS_PER_BOOK)) {
                val bookmark = runCatching {
                    val item = raw.getJSONObject(index)
                    val format = EbookFormat.valueOf(item.getString("format"))
                    val locatorJson = item.optString("locatorJson")
                        .takeIf { it.isNotBlank() && it != "null" }
                    EbookBookmark(
                        sourceId = sourceId,
                        format = format,
                        paragraphIndex = item.optInt("paragraphIndex", 0).coerceAtLeast(0),
                        locatorJson = locatorJson,
                        locationKey = if (format == EbookFormat.EPUB) {
                            epubBookmarkLocationKey(locatorJson)
                        } else {
                            null
                        },
                        title = item.optString("title", "阅读位置"),
                        progress = normalizedEbookProgress(
                            item.optDouble("progress", Double.NaN),
                        ),
                        createdAt = item.optLong("createdAt", 0L),
                    )
                }.getOrNull() ?: continue
                // long: 单条旧数据损坏不应拖垮整本书的书签；无法恢复定位的 EPUB 记录直接跳过。
                if (bookmark.format == EbookFormat.EPUB && bookmark.locationKey == null) {
                    continue
                }
                add(bookmark)
            }
        }.distinctBy(::ebookBookmarkKey)
    }

    fun saveBookmarks(sourceId: String, bookmarks: List<EbookBookmark>) {
        val root = runCatching {
            JSONObject(preferences.getString(KEY_BOOKMARKS, null) ?: "{}")
        }.getOrElse { JSONObject() }
        val normalized = bookmarks.asSequence()
            .filter { it.sourceId == sourceId }
            .distinctBy(::ebookBookmarkKey)
            .take(MAX_EBOOK_BOOKMARKS_PER_BOOK)
            .toList()
        if (normalized.isEmpty()) {
            root.remove(sourceId)
            preferences.edit { putString(KEY_BOOKMARKS, root.toString()) }
            return
        }
        val array = JSONArray()
        normalized.forEach { bookmark ->
            array.put(
                JSONObject()
                    .put("format", bookmark.format.name)
                    .put("paragraphIndex", bookmark.paragraphIndex)
                    .put("locatorJson", bookmark.locatorJson)
                    .put("title", bookmark.title)
                    .put("progress", bookmark.progress)
                    .put("createdAt", bookmark.createdAt),
            )
        }
        root.put(sourceId, array)
        preferences.edit { putString(KEY_BOOKMARKS, root.toString()) }
    }

    fun removeBookmarks(sourceId: String) {
        val root = runCatching {
            JSONObject(preferences.getString(KEY_BOOKMARKS, null) ?: "{}")
        }.getOrElse { JSONObject() }
        root.remove(sourceId)
        preferences.edit { putString(KEY_BOOKMARKS, root.toString()) }
    }

    private fun saveHistory(entries: List<EbookHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(
                JSONObject()
                    .put("sourceId", item.sourceId)
                    .put("title", item.title)
                    .put("format", item.format.name)
                    .put("sourceKind", item.sourceKind.name)
                    .put("updatedAt", item.updatedAt)
                    .put("cacheFileName", item.cacheFileName)
                    .put("progress", item.progress),
            )
        }
        preferences.edit { putString(KEY_HISTORY, array.toString()) }
    }

    fun loadPosition(sourceId: String): EbookReadingPosition? {
        val raw = runCatching {
            JSONObject(preferences.getString(KEY_POSITIONS, null) ?: "{}")
                .optString(sourceId)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return ebookReadingPositionFromJson(raw)
    }

    fun savePosition(position: EbookReadingPosition) {
        val root = runCatching {
            JSONObject(preferences.getString(KEY_POSITIONS, null) ?: "{}")
        }.getOrElse { JSONObject() }
        root.put(position.sourceId, position.toJson())
        preferences.edit { putString(KEY_POSITIONS, root.toString()) }
    }

    fun loadSettings(): EbookReaderSettings {
        val json = runCatching {
            JSONObject(preferences.getString(KEY_SETTINGS, null) ?: "{}")
        }.getOrElse { JSONObject() }
        return EbookReaderSettings(
            fontSizeSp = json.optDouble("fontSizeSp", 18.0).toFloat().coerceIn(14f, 32f),
            lineHeight = json.optDouble("lineHeight", 1.55).toFloat().coerceIn(1.2f, 2.2f),
            horizontalPaddingDp = json.optDouble("horizontalPaddingDp", 20.0).toFloat().coerceIn(8f, 40f),
            pageTheme = runCatching {
                EbookPageTheme.valueOf(json.optString("pageTheme", EbookPageTheme.SYSTEM.name))
            }.getOrDefault(EbookPageTheme.SYSTEM),
        )
    }

    fun saveSettings(settings: EbookReaderSettings) {
        val json = JSONObject()
            .put("fontSizeSp", settings.fontSizeSp)
            .put("lineHeight", settings.lineHeight)
            .put("horizontalPaddingDp", settings.horizontalPaddingDp)
            .put("pageTheme", settings.pageTheme.name)
        preferences.edit { putString(KEY_SETTINGS, json.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "ebook_reader"
        const val KEY_HISTORY = "history"
        const val KEY_POSITIONS = "positions"
        const val KEY_SETTINGS = "settings"
        const val KEY_BOOKMARKS = "bookmarks"
    }
}

internal const val MAX_EBOOK_BOOKMARKS_PER_BOOK = 100
private const val EPUB_BOOKMARK_PROGRESSION_BUCKETS = 10_000
