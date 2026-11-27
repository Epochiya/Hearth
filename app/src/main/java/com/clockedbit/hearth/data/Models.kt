package com.clockedbit.hearth.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

private fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>()
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        runCatching { f(o) }.getOrNull()?.let { out.add(it) }
    }
    return out
}

enum class BookFormat(val reflow: Boolean, val label: String) {
    EPUB(true, "EPUB"), TXT(true, "TXT"), HTML(true, "HTML"), MD(true, "MD"),
    FB2(true, "FB2"), DOCX(true, "DOCX"), PDF(false, "PDF"), CBZ(false, "COMIC")
}

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val cover: String?,          // path relative to the book folder
    val addedAt: Long,
    val lastOpened: Long = 0L,
    val progress: Float = 0f,
    val position: String = "",
    val pageCount: Int = 0,
    val favorite: Boolean = false,
    val finished: Boolean = false,
    val readingMs: Long = 0L,
    val zoom: Float = 1f,
    val zoomLock: Boolean = false,
    val pageSort: Int = 0,       // comics: 0 = name (smart A–Z), 1 = date modified
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("author", author); put("format", format.name)
        put("cover", cover ?: ""); put("addedAt", addedAt); put("lastOpened", lastOpened)
        put("progress", progress.toDouble()); put("position", position); put("pageCount", pageCount)
        put("favorite", favorite); put("finished", finished); put("readingMs", readingMs)
        put("zoom", zoom.toDouble()); put("zoomLock", zoomLock); put("pageSort", pageSort)
    }

    companion object {
        fun fromJson(o: JSONObject) = Book(
            id = o.getString("id"),
            title = o.optString("title", "Untitled"),
            author = o.optString("author", ""),
            format = runCatching { BookFormat.valueOf(o.optString("format", "EPUB")) }.getOrDefault(BookFormat.EPUB),
            cover = o.optString("cover", "").ifEmpty { null },
            addedAt = o.optLong("addedAt"),
            lastOpened = o.optLong("lastOpened"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            position = o.optString("position", ""),
            pageCount = o.optInt("pageCount"),
            favorite = o.optBoolean("favorite"),
            finished = o.optBoolean("finished"),
            readingMs = o.optLong("readingMs"),
            zoom = o.optDouble("zoom", 1.0).toFloat(),
            zoomLock = o.optBoolean("zoomLock"),
            pageSort = o.optInt("pageSort", 0),
        )
    }
}

data class Chapter(val href: String, val title: String, val weight: Long)
data class TocEntry(val title: String, val chapter: Int, val anchor: String?, val depth: Int)

data class BookStructure(val chapters: List<Chapter>, val toc: List<TocEntry>) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("chapters", JSONArray().apply {
            chapters.forEach { put(JSONObject().put("href", it.href).put("title", it.title).put("weight", it.weight)) }
        })
        put("toc", JSONArray().apply {
            toc.forEach {
                put(JSONObject().put("title", it.title).put("chapter", it.chapter)
                    .put("anchor", it.anchor ?: "").put("depth", it.depth))
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject) = BookStructure(
            o.optJSONArray("chapters").mapObj { Chapter(it.getString("href"), it.optString("title"), it.optLong("weight", 1)) },
            o.optJSONArray("toc").mapObj {
                TocEntry(it.optString("title"), it.optInt("chapter"), it.optString("anchor").ifEmpty { null }, it.optInt("depth"))
            }
        )
    }
}

/* ---------------- annotations ---------------- */

data class Bookmark(val id: String, val chapter: Int, val offset: Int, val page: Int, val excerpt: String, val created: Long)
data class Highlight(
    val id: String, val chapter: Int, val start: Int, val end: Int,
    val text: String, val color: Int, val note: String, val created: Long
)
data class PageNote(val id: String, val chapter: Int, val offset: Int, val page: Int, val text: String, val created: Long)

class InkStroke(
    val id: String, val chapter: Int, val offset: Int, val page: Int,
    val tool: Int, val color: Int, val width: Float, val points: FloatArray
)

data class Annotations(
    val bookmarks: List<Bookmark> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val notes: List<PageNote> = emptyList(),
    val ink: List<InkStroke> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bookmarks", JSONArray().apply {
            bookmarks.forEach {
                put(JSONObject().put("id", it.id).put("c", it.chapter).put("o", it.offset).put("p", it.page)
                    .put("x", it.excerpt).put("t", it.created))
            }
        })
        put("highlights", JSONArray().apply {
            highlights.forEach {
                put(JSONObject().put("id", it.id).put("c", it.chapter).put("s", it.start).put("e", it.end)
                    .put("x", it.text).put("col", it.color).put("n", it.note).put("t", it.created))
            }
        })
        put("notes", JSONArray().apply {
            notes.forEach {
                put(JSONObject().put("id", it.id).put("c", it.chapter).put("o", it.offset).put("p", it.page)
                    .put("x", it.text).put("t", it.created))
            }
        })
        put("ink", JSONArray().apply {
            ink.forEach { s ->
                val pts = JSONArray()
                s.points.forEach { v -> pts.put((v * 10000f).toInt()) }
                put(JSONObject().put("id", s.id).put("c", s.chapter).put("o", s.offset).put("p", s.page)
                    .put("tool", s.tool).put("col", s.color).put("w", s.width.toDouble()).put("pts", pts))
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject) = Annotations(
            o.optJSONArray("bookmarks").mapObj {
                Bookmark(it.getString("id"), it.optInt("c"), it.optInt("o", -1), it.optInt("p", -1), it.optString("x"), it.optLong("t"))
            },
            o.optJSONArray("highlights").mapObj {
                Highlight(it.getString("id"), it.optInt("c"), it.optInt("s"), it.optInt("e"), it.optString("x"),
                    it.optInt("col"), it.optString("n"), it.optLong("t"))
            },
            o.optJSONArray("notes").mapObj {
                PageNote(it.getString("id"), it.optInt("c"), it.optInt("o", -1), it.optInt("p", -1), it.optString("x"), it.optLong("t"))
            },
            o.optJSONArray("ink").mapObj {
                val arr = it.optJSONArray("pts") ?: JSONArray()
                val pts = FloatArray(arr.length()) { i -> arr.optInt(i) / 10000f }
                InkStroke(it.getString("id"), it.optInt("c"), it.optInt("o", -1), it.optInt("p", -1),
                    it.optInt("tool"), it.optInt("col"), it.optDouble("w", 0.004).toFloat(), pts)
            },
        )
    }
}

/* ---------------- themes & prefs ---------------- */

data class ThemePreset(val id: String, val name: String, val bg: Int, val fg: Int, val accent: Int)

object Themes {
    val presets = listOf(
        ThemePreset("ember", "Ember", 0xFF1C1714.toInt(), 0xFFE9DCC9.toInt(), 0xFFFFA45C.toInt()),
        ThemePreset("midnight", "Midnight", 0xFF0F131A.toInt(), 0xFFCDD6E4.toInt(), 0xFF7AA2FF.toInt()),
        ThemePreset("void", "Void", 0xFF000000.toInt(), 0xFFB9B4AC.toInt(), 0xFFFF8A65.toInt()),
        ThemePreset("forest", "Forest", 0xFF121C17.toInt(), 0xFFCFE2D3.toInt(), 0xFF7BD389.toInt()),
        ThemePreset("plum", "Plum", 0xFF1D1520.toInt(), 0xFFE7D6EA.toInt(), 0xFFD08CFF.toInt()),
        ThemePreset("cocoa", "Cocoa", 0xFF2A211C.toInt(), 0xFFEFE0CC.toInt(), 0xFFE8B86D.toInt()),
        ThemePreset("sepia", "Sepia", 0xFFF3E9D2.toInt(), 0xFF4E3B2A.toInt(), 0xFFB5651D.toInt()),
        ThemePreset("paper", "Paper", 0xFFFAF7F0.toInt(), 0xFF2A2724.toInt(), 0xFFD9480F.toInt()),
        ThemePreset("mist", "Mist", 0xFFE6EBEF.toInt(), 0xFF26323C.toInt(), 0xFF2F80ED.toInt()),
    )
}

object Palettes {
    val highlight: List<Int> = listOf(0x70FFD54F, 0x6066D98A, 0x6064B5F6, 0x60F48FB1, 0x60B388FF, 0x60FF8A50)
    val pen: List<Int> = listOf(
        0xFFF5EFE6, 0xFFFFA45C, 0xFFFF6B6B, 0xFF4FC3F7, 0xFF81C784, 0xFFBA68C8, 0xFFFFD54F, 0xFF1A1A1A
    ).map { it.toInt() }
}

data class ReaderPrefs(
    val themeId: String = "ember",
    val bg: Int = 0xFF1C1714.toInt(),
    val fg: Int = 0xFFE9DCC9.toInt(),
    val accent: Int = 0xFFFFA45C.toInt(),
    val fontFamily: String = "serif",
    val fontSize: Int = 21,
    val lineHeight: Float = 1.6f,
    val margin: Int = 32,
    val paraSpacing: Float = 0.7f,
    val letterSpacing: Float = 0f,
    val fontWeight: Int = 400,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    val overrideStyles: Boolean = true,
    val scrollMode: Boolean = false,
    val twoPage: Boolean = true,
    val tapZones: Boolean = true,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    val pageAnim: Boolean = true,
    val recolorPages: Boolean = true,
    val warmth: Float = 0f,
    val brightness: Float = -1f,
    val orientation: Int = 0,      // 0 auto, 1 portrait, 2 landscape
    val palmMode: Int = 0,         // 0 auto, 1 stylus only, 2 off
    val penColor: Int = 0xFFFFA45C.toInt(),
    val penWidth: Float = 3f,
    val markerColor: Int = 0xFFFFD54F.toInt(),
    val markerWidth: Float = 18f,
    val hlColor: Int = 0x70FFD54F,
    val ttsRate: Float = 1f,
    val dailyGoalMin: Int = 30,
    val pageGap: Int = 10,
    val cropMargins: Boolean = false,
    val rtlPages: Boolean = false,
    val showStatus: Boolean = true,
    val showClock: Boolean = true,
    val autoScrollSpeed: Float = 40f,
    val coverSize: Int = 148,
    val appLock: Boolean = false,       // ask for fingerprint / PIN when opening Hearth
    val privacyScreen: Boolean = false, // block screenshots and hide Hearth in recent apps
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("themeId", themeId); put("bg", bg); put("fg", fg); put("accent", accent)
        put("fontFamily", fontFamily); put("fontSize", fontSize); put("lineHeight", lineHeight.toDouble())
        put("margin", margin); put("paraSpacing", paraSpacing.toDouble()); put("letterSpacing", letterSpacing.toDouble())
        put("fontWeight", fontWeight); put("justify", justify); put("hyphenate", hyphenate)
        put("overrideStyles", overrideStyles); put("scrollMode", scrollMode); put("twoPage", twoPage)
        put("tapZones", tapZones); put("volumeKeys", volumeKeys); put("keepScreenOn", keepScreenOn)
        put("pageAnim", pageAnim); put("recolorPages", recolorPages); put("warmth", warmth.toDouble())
        put("brightness", brightness.toDouble()); put("orientation", orientation); put("palmMode", palmMode)
        put("penColor", penColor); put("penWidth", penWidth.toDouble()); put("markerColor", markerColor)
        put("markerWidth", markerWidth.toDouble()); put("hlColor", hlColor); put("ttsRate", ttsRate.toDouble())
        put("dailyGoalMin", dailyGoalMin); put("pageGap", pageGap); put("cropMargins", cropMargins)
        put("rtlPages", rtlPages); put("showStatus", showStatus); put("showClock", showClock)
        put("autoScrollSpeed", autoScrollSpeed.toDouble()); put("coverSize", coverSize)
        put("appLock", appLock); put("privacyScreen", privacyScreen)
    }

    companion object {
        fun fromJson(o: JSONObject): ReaderPrefs {
            val d = ReaderPrefs()
            return ReaderPrefs(
                themeId = o.optString("themeId", d.themeId),
                bg = o.optInt("bg", d.bg), fg = o.optInt("fg", d.fg), accent = o.optInt("accent", d.accent),
                fontFamily = o.optString("fontFamily", d.fontFamily),
                fontSize = o.optInt("fontSize", d.fontSize),
                lineHeight = o.optDouble("lineHeight", d.lineHeight.toDouble()).toFloat(),
                margin = o.optInt("margin", d.margin),
                paraSpacing = o.optDouble("paraSpacing", d.paraSpacing.toDouble()).toFloat(),
                letterSpacing = o.optDouble("letterSpacing", d.letterSpacing.toDouble()).toFloat(),
                fontWeight = o.optInt("fontWeight", d.fontWeight),
                justify = o.optBoolean("justify", d.justify),
                hyphenate = o.optBoolean("hyphenate", d.hyphenate),
                overrideStyles = o.optBoolean("overrideStyles", d.overrideStyles),
                scrollMode = o.optBoolean("scrollMode", d.scrollMode),
                twoPage = o.optBoolean("twoPage", d.twoPage),
                tapZones = o.optBoolean("tapZones", d.tapZones),
                volumeKeys = o.optBoolean("volumeKeys", d.volumeKeys),
                keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
                pageAnim = o.optBoolean("pageAnim", d.pageAnim),
                recolorPages = o.optBoolean("recolorPages", d.recolorPages),
                warmth = o.optDouble("warmth", 0.0).toFloat(),
                brightness = o.optDouble("brightness", -1.0).toFloat(),
                orientation = o.optInt("orientation", 0),
                palmMode = o.optInt("palmMode", 0),
                penColor = o.optInt("penColor", d.penColor),
                penWidth = o.optDouble("penWidth", d.penWidth.toDouble()).toFloat(),
                markerColor = o.optInt("markerColor", d.markerColor),
                markerWidth = o.optDouble("markerWidth", d.markerWidth.toDouble()).toFloat(),
                hlColor = o.optInt("hlColor", d.hlColor),
                ttsRate = o.optDouble("ttsRate", 1.0).toFloat(),
                dailyGoalMin = o.optInt("dailyGoalMin", 30),
                pageGap = o.optInt("pageGap", d.pageGap),
                cropMargins = o.optBoolean("cropMargins", d.cropMargins),
                rtlPages = o.optBoolean("rtlPages", d.rtlPages),
                showStatus = o.optBoolean("showStatus", d.showStatus),
                showClock = o.optBoolean("showClock", d.showClock),
                autoScrollSpeed = o.optDouble("autoScrollSpeed", d.autoScrollSpeed.toDouble()).toFloat(),
                coverSize = o.optInt("coverSize", d.coverSize),
                appLock = o.optBoolean("appLock", d.appLock),
                privacyScreen = o.optBoolean("privacyScreen", d.privacyScreen),
            )
        }
    }
}
