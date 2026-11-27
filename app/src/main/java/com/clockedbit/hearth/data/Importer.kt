package com.clockedbit.hearth.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.zip.ZipFile

class ImportException(msg: String) : Exception(msg)

object Importer {

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    suspend fun import(context: Context, uri: Uri): Book = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val name = displayName(cr, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
        val id = newId()
        val dir = Store.bookDir(id).apply { mkdirs() }
        try {
            val raw = File(dir, "source.bin")
            val input = cr.openInputStream(uri) ?: throw ImportException("Couldn't open the file")
            input.use { inp -> raw.outputStream().use { inp.copyTo(it) } }
            val ext = name.substringAfterLast('.', "").lowercase()
            val format = detect(ext, raw)
            val baseTitle = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Untitled" }
            val book = when (format) {
                BookFormat.EPUB -> importEpub(id, dir, raw, baseTitle)
                BookFormat.PDF -> importPdf(id, dir, raw, baseTitle)
                BookFormat.CBZ -> importCbz(id, dir, raw, baseTitle)
                BookFormat.TXT -> importTxt(id, dir, raw, baseTitle)
                BookFormat.HTML -> importHtml(id, dir, raw, baseTitle)
                BookFormat.MD -> importMd(id, dir, raw, baseTitle)
                BookFormat.FB2 -> importFb2(id, dir, raw, baseTitle)
                BookFormat.DOCX -> importDocx(id, dir, raw, baseTitle)
            }
            Store.saveBook(book)
            book
        } catch (e: Throwable) {
            dir.deleteRecursively()
            if (e is ImportException) throw e
            throw ImportException("Couldn't read \"$name\": ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun displayName(cr: ContentResolver, uri: Uri): String? = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun detect(ext: String, f: File): BookFormat {
        when (ext) {
            "epub" -> return BookFormat.EPUB
            "pdf" -> return BookFormat.PDF
            "txt", "text", "log", "csv" -> return BookFormat.TXT
            "html", "htm", "xhtml" -> return BookFormat.HTML
            "md", "markdown" -> return BookFormat.MD
            "fb2" -> return BookFormat.FB2
            "docx" -> return BookFormat.DOCX
            "cbz" -> return BookFormat.CBZ
            "mobi", "azw", "azw3", "kfx", "prc" ->
                throw ImportException("Kindle files (.$ext) aren't supported. Convert it to EPUB with the free app Calibre, then add it.")
            "cbr", "rar", "7z", "cb7" ->
                throw ImportException("RAR/7z comics aren't supported. Re-pack them as .cbz (zip).")
            "djvu", "doc", "rtf", "odt" ->
                throw ImportException(".$ext isn't supported yet. Try PDF, EPUB or DOCX.")
        }
        // sniff the header
        val head = ByteArray(8)
        FileInputStream(f).use { it.read(head) }
        val s = String(head, Charsets.ISO_8859_1)
        if (s.startsWith("%PDF")) return BookFormat.PDF
        if (s.startsWith("PK")) {
            ZipFile(f).use { z ->
                if (z.getEntry("META-INF/container.xml") != null || z.getEntry("mimetype") != null) return BookFormat.EPUB
                if (z.getEntry("word/document.xml") != null) return BookFormat.DOCX
                val names = Collections.list(z.entries()).map { it.name.lowercase() }
                if (names.any { n -> imageExt.any { n.endsWith(".$it") } }) return BookFormat.CBZ
            }
            throw ImportException("This zip file doesn't contain a book.")
        }
        val text = String(f.readBytes().copyOf(minOf(4096, f.length().toInt())), Charsets.UTF_8)
        if (text.contains("<FictionBook")) return BookFormat.FB2
        if (text.contains("<html", ignoreCase = true)) return BookFormat.HTML
        if (ext.isEmpty() || text.none { it == '\u0000' }) return BookFormat.TXT
        throw ImportException("Unknown file type (.$ext)")
    }

    /* ---------------- helpers ---------------- */

    private fun unzip(zip: File, dest: File, filter: (String) -> Boolean = { true }) {
        val canon = dest.canonicalPath + File.separator
        ZipFile(zip).use { z ->
            for (e in Collections.list(z.entries())) {
                if (e.isDirectory || !filter(e.name)) continue
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(canon)) continue
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
                if (e.time > 0) out.setLastModified(e.time) // keep the date from the zip, for "Date modified" order
            }
        }
    }

    private fun normalizePath(p: String): String {
        val out = ArrayList<String>()
        for (seg in p.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }

    private fun decodeHref(h: String): String =
        runCatching { URLDecoder.decode(h.replace("+", "%2B"), "UTF-8") }.getOrDefault(h)

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // Legacy encodings: guess Arabic vs Western by the high-byte range
            var arabic = 0; var high = 0
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v >= 0x80) { high++; if (v in 0xC1..0xED) arabic++ }
            }
            val cs = if (high > 0 && arabic * 10 > high * 6) "windows-1256" else "windows-1252"
            String(bytes, runCatching { Charset.forName(cs) }.getOrDefault(Charsets.ISO_8859_1))
        }
    }

    private fun htmlPage(title: String, body: String) =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>${escape(title)}</title></head>" +
            "<body dir=\"auto\">$body</body></html>"

    private fun saveScaledCover(src: File, dest: File): Boolean = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 480) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        true
    }.getOrDefault(false)

    /** Split long plain HTML bodies into chapter files. */
    private fun writeChapters(id: String, dir: File, parts: List<Pair<String, String>>): BookStructure {
        val htmlDir = File(dir, "html").apply { mkdirs() }
        val chapters = parts.mapIndexed { i, (title, body) ->
            val f = File(htmlDir, "ch%04d.html".format(i))
            f.writeText(htmlPage(title, body))
            Chapter("html/${f.name}", title, f.length())
        }
        val toc = chapters.mapIndexed { i, c -> TocEntry(c.title, i, null, 0) }
        val s = BookStructure(chapters, toc)
        Store.saveStructure(id, s)
        return s
    }

    /* ---------------- EPUB ---------------- */

    private fun newParser(): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
    }

    private fun local(name: String?): String = name?.substringAfter(':')?.lowercase() ?: ""

    private fun importEpub(id: String, dir: File, raw: File, fallbackTitle: String): Book {
        val content = File(dir, "content")
        unzip(raw, content)
        raw.delete()

        var opfPath: String? = null
        val container = File(content, "META-INF/container.xml")
        if (container.exists()) {
            runCatching {
                val p = newParser(); p.setInput(container.inputStream(), null)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "rootfile") {
                        opfPath = p.getAttributeValue(null, "full-path"); break
                    }
                }
            }
        }
        if (opfPath == null || !File(content, opfPath!!).exists()) {
            opfPath = content.walkTopDown().firstOrNull { it.extension.equals("opf", true) }?.relativeTo(content)?.path
        }
        val opf = File(content, opfPath ?: throw ImportException("This EPUB is damaged (no package file)."))
        val opfDir = opfPath!!.replace('\\', '/').substringBeforeLast('/', "")
        fun resolve(base: String, href: String) = normalizePath(if (base.isEmpty()) decodeHref(href) else "$base/${decodeHref(href)}")

        var title = ""; var author = ""; var coverId: String? = null; var tocId: String? = null
        data class Item(val href: String, val type: String, val props: String)
        val manifest = LinkedHashMap<String, Item>()
        val spine = ArrayList<String>()

        val p = newParser(); p.setInput(opf.inputStream(), null)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (local(p.name)) {
                "title" -> if (title.isEmpty()) title = runCatching { p.nextText() }.getOrDefault("").trim()
                "creator" -> if (author.isEmpty()) author = runCatching { p.nextText() }.getOrDefault("").trim()
                "meta" -> if (p.getAttributeValue(null, "name") == "cover") coverId = p.getAttributeValue(null, "content")
                "item" -> {
                    val iid = p.getAttributeValue(null, "id") ?: continue
                    val href = p.getAttributeValue(null, "href") ?: continue
                    manifest[iid] = Item(resolve(opfDir, href), p.getAttributeValue(null, "media-type") ?: "",
                        p.getAttributeValue(null, "properties") ?: "")
                }
                "spine" -> tocId = p.getAttributeValue(null, "toc")
                "itemref" -> p.getAttributeValue(null, "idref")?.let { spine.add(it) }
            }
        }

        val chapterItems = spine.mapNotNull { manifest[it] }
            .filter { it.type.contains("html") || it.type.contains("xml") || it.href.endsWith("htm", true) || it.href.endsWith("html", true) }
            .filter { File(content, it.href).exists() }
        if (chapterItems.isEmpty()) throw ImportException("This EPUB has no readable chapters.")
        val pathToIndex = HashMap<String, Int>()
        chapterItems.forEachIndexed { i, it -> pathToIndex[it.href] = i }

        // table of contents
        val toc = ArrayList<TocEntry>()
        val navItem = manifest.values.firstOrNull { it.props.split(' ').contains("nav") }
        if (navItem != null) runCatching {
            val navFile = File(content, navItem.href)
            val navBase = navItem.href.substringBeforeLast('/', "")
            var html = navFile.readText()
            val tocStart = Regex("epub:type\\s*=\\s*[\"'][^\"']*toc", RegexOption.IGNORE_CASE).find(html)?.range?.first ?: 0
            html = html.substring(tocStart)
            val end = html.indexOf("</nav", ignoreCase = true)
            if (end > 0) html = html.substring(0, end)
            val aRe = Regex("<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (m in aRe.findAll(html)) {
                val before = html.substring(0, m.range.first)
                val depth = (Regex("<ol", RegexOption.IGNORE_CASE).findAll(before).count() -
                    Regex("</ol", RegexOption.IGNORE_CASE).findAll(before).count() - 1).coerceAtLeast(0)
                val href = m.groupValues[1]
                val label = android.text.Html.fromHtml(m.groupValues[2], android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                val path = resolve(navBase, href.substringBefore('#'))
                val idx = pathToIndex[path] ?: continue
                toc.add(TocEntry(label.ifEmpty { "Section" }, idx, href.substringAfter('#', "").ifEmpty { null }, depth))
            }
        }
        if (toc.isEmpty()) runCatching {
            val ncx = (tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.type.contains("ncx") }) ?: return@runCatching
            val ncxBase = ncx.href.substringBeforeLast('/', "")
            val np = newParser(); np.setInput(File(content, ncx.href).inputStream(), null)
            var depth = -1; var label = ""
            while (np.next() != XmlPullParser.END_DOCUMENT) {
                when (np.eventType) {
                    XmlPullParser.START_TAG -> when (local(np.name)) {
                        "navpoint" -> { depth++; label = "" }
                        "text" -> if (label.isEmpty()) label = runCatching { np.nextText() }.getOrDefault("").trim()
                        "content" -> {
                            val src = np.getAttributeValue(null, "src") ?: ""
                            val idx = pathToIndex[resolve(ncxBase, src.substringBefore('#'))]
                            if (idx != null) toc.add(TocEntry(label.ifEmpty { "Section" }, idx,
                                src.substringAfter('#', "").ifEmpty { null }, depth.coerceAtLeast(0)))
                        }
                    }
                    XmlPullParser.END_TAG -> if (local(np.name) == "navpoint") depth--
                }
            }
        }

        // chapter titles: inherit from the last toc entry at or before the chapter
        val chapters = chapterItems.mapIndexed { i, it ->
            val t = toc.lastOrNull { e -> e.chapter <= i }?.title ?: if (i == 0) "Beginning" else "Part ${i + 1}"
            Chapter("content/${it.href}", t, File(content, it.href).length().coerceAtLeast(1))
        }
        Store.saveStructure(id, BookStructure(chapters, toc))

        // cover
        val coverItem = manifest.values.firstOrNull { it.props.split(' ').contains("cover-image") }
            ?: coverId?.let { manifest[it] }
            ?: manifest.entries.firstOrNull { (k, v) -> v.type.startsWith("image") && (k.contains("cover", true) || v.href.contains("cover", true)) }?.value
            ?: manifest.values.firstOrNull { it.type.startsWith("image") }
        var cover: String? = null
        if (coverItem != null && coverItem.type.startsWith("image")) {
            if (saveScaledCover(File(content, coverItem.href), File(dir, "cover.jpg"))) cover = "cover.jpg"
        }
        return Book(id, title.ifEmpty { fallbackTitle }, author, BookFormat.EPUB, cover, System.currentTimeMillis(), pageCount = chapters.size)
    }

    /* ---------------- PDF ---------------- */

    private fun importPdf(id: String, dir: File, raw: File, title: String): Book {
        val pdf = File(dir, "book.pdf")
        raw.renameTo(pdf)
        var count = 0
        var cover: String? = null
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    count = r.pageCount
                    if (count == 0) throw ImportException("This PDF has no pages.")
                    r.openPage(0).use { page ->
                        val w = 480
                        val h = (page.height * (w.toFloat() / page.width)).toInt().coerceIn(1, 2000)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(dir, "cover.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        bmp.recycle()
                        cover = "cover.jpg"
                    }
                }
            }
        } catch (e: SecurityException) {
            throw ImportException("This PDF is password-protected.")
        }
        return Book(id, title, "", BookFormat.PDF, cover, System.currentTimeMillis(), pageCount = count)
    }

    /* ---------------- CBZ ---------------- */

    private val naturalOrder = Comparator<String> { a, b ->
        val ra = Regex("(\\d+)|(\\D+)").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("(\\d+)|(\\D+)").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())) else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }

    /** A comic's pages in the chosen order: 0 = smart A–Z (page2 before page10), 1 = date modified (oldest first). */
    private fun comicPages(dir: File, sort: Int): List<String> {
        val files = File(dir, "pages").walkTopDown().filter { it.isFile }.toList()
        val rel = { f: File -> f.relativeTo(dir).path.replace('\\', '/') }
        val byName = Comparator<File> { a, b -> naturalOrder.compare(rel(a), rel(b)) }
        val sorted = if (sort == 1) files.sortedWith(compareBy<File> { it.lastModified() }.then(byName)) else files.sortedWith(byName)
        return sorted.map(rel)
    }

    /** Re-order an imported comic's pages. Keeps highlights/ink tied to page numbers, so the page you're on stays the same number. */
    fun resortComic(bookId: String, sort: Int) {
        val dir = Store.bookDir(bookId)
        val files = comicPages(dir, sort)
        if (files.isEmpty()) return
        Store.saveStructure(bookId, BookStructure(files.map { Chapter(it, "", 1) }, emptyList()))
        Store.updateBook(bookId) { it.copy(pageSort = sort) }
    }

    private fun importCbz(id: String, dir: File, raw: File, title: String): Book {
        val pagesDir = File(dir, "pages")
        unzip(raw, pagesDir) { n -> imageExt.any { n.lowercase().endsWith(".$it") } && !n.contains("__MACOSX") }
        raw.delete()
        val files = comicPages(dir, 0)
        if (files.isEmpty()) throw ImportException("No images found in this comic.")
        Store.saveStructure(id, BookStructure(files.map { Chapter(it, "", 1) }, emptyList()))
        val cover = if (saveScaledCover(File(dir, files[0]), File(dir, "cover.jpg"))) "cover.jpg" else null
        return Book(id, title, "", BookFormat.CBZ, cover, System.currentTimeMillis(), pageCount = files.size)
    }

    /* ---------------- plain text ---------------- */

    private fun paragraphsToHtml(text: String): String {
        val norm = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = if (norm.contains("\n\n")) norm.split(Regex("\n\\s*\n")) else norm.split('\n')
        val sb = StringBuilder()
        for (b in blocks) {
            val t = b.trim()
            if (t.isEmpty()) continue
            sb.append("<p>").append(escape(t).replace("\n", "<br>")).append("</p>\n")
        }
        return sb.toString()
    }

    private fun importTxt(id: String, dir: File, raw: File, title: String): Book {
        val text = decodeText(raw.readBytes())
        raw.delete()
        val headRe = Regex("^[ \\t]*((chapter|part|book|prologue|epilogue|الفصل|الباب|الجزء|فصل)\\b[^\\n]{0,80})$",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        val heads = headRe.findAll(text).toList()
        val parts = ArrayList<Pair<String, String>>()
        if (heads.size >= 2) {
            if (heads[0].range.first > 200) parts.add("Opening" to paragraphsToHtml(text.substring(0, heads[0].range.first)))
            heads.forEachIndexed { i, m ->
                val endIdx = if (i + 1 < heads.size) heads[i + 1].range.first else text.length
                val head = m.value.trim()
                val body = text.substring(m.range.last + 1, endIdx)
                parts.add(head to "<h2>${escape(head)}</h2>\n" + paragraphsToHtml(body))
            }
        } else {
            var i = 0; var n = 1
            while (i < text.length) {
                var end = minOf(text.length, i + 40_000)
                if (end < text.length) {
                    val br = text.lastIndexOf("\n\n", end)
                    if (br > i + 10_000) end = br else {
                        val br2 = text.lastIndexOf('\n', end)
                        if (br2 > i + 10_000) end = br2
                    }
                }
                parts.add("Part $n" to paragraphsToHtml(text.substring(i, end)))
                i = end; n++
            }
            if (parts.isEmpty()) parts.add("Text" to "<p></p>")
        }
        val s = writeChapters(id, dir, parts)
        return Book(id, title, "", BookFormat.TXT, null, System.currentTimeMillis(), pageCount = s.chapters.size)
    }

    private fun importHtml(id: String, dir: File, raw: File, fallback: String): Book {
        val html = decodeText(raw.readBytes())
        raw.delete()
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: fallback
        val htmlDir = File(dir, "html").apply { mkdirs() }
        val f = File(htmlDir, "index.html")
        f.writeText(html)
        Store.saveStructure(id, BookStructure(listOf(Chapter("html/index.html", title, f.length())), listOf(TocEntry(title, 0, null, 0))))
        return Book(id, title, "", BookFormat.HTML, null, System.currentTimeMillis(), pageCount = 1)
    }

    private fun mdInline(s: String): String {
        var t = escape(s)
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        t = t.replace(Regex("__(.+?)__"), "<strong>$1</strong>")
        t = t.replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)\\*(?!\\w)"), "<em>$1</em>")
        t = t.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        t = t.replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        return t
    }

    private fun importMd(id: String, dir: File, raw: File, fallback: String): Book {
        val text = decodeText(raw.readBytes()).replace("\r\n", "\n")
        raw.delete()
        val parts = ArrayList<Pair<String, StringBuilder>>()
        var cur = "Beginning" to StringBuilder()
        var para = StringBuilder()
        var inList = false; var inCode = false
        fun flushPara() {
            if (para.isNotBlank()) cur.second.append("<p>").append(mdInline(para.toString().trim())).append("</p>\n")
            para = StringBuilder()
        }
        fun closeList() { if (inList) { cur.second.append("</ul>\n"); inList = false } }
        var title: String? = null
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("```")) {
                flushPara(); closeList()
                cur.second.append(if (inCode) "</pre>\n" else "<pre>")
                inCode = !inCode; continue
            }
            if (inCode) { cur.second.append(escape(line)).append('\n'); continue }
            val h = Regex("^(#{1,6})\\s+(.*)$").find(line)
            when {
                h != null -> {
                    flushPara(); closeList()
                    val level = h.groupValues[1].length
                    val ht = h.groupValues[2].trim()
                    if (title == null && level == 1) title = ht
                    if (level <= 2 && cur.second.isNotBlank()) {
                        parts.add(cur); cur = ht to StringBuilder()
                    } else if (level <= 2) cur = ht to cur.second
                    cur.second.append("<h$level>${mdInline(ht)}</h$level>\n")
                }
                Regex("^\\s*[-*+]\\s+").containsMatchIn(line) -> {
                    flushPara()
                    if (!inList) { cur.second.append("<ul>\n"); inList = true }
                    cur.second.append("<li>").append(mdInline(line.replaceFirst(Regex("^\\s*[-*+]\\s+"), ""))).append("</li>\n")
                }
                line.startsWith(">") -> { flushPara(); closeList(); cur.second.append("<blockquote>").append(mdInline(line.removePrefix(">").trim())).append("</blockquote>\n") }
                line.isBlank() -> { flushPara(); closeList() }
                Regex("^\\s*(-{3,}|\\*{3,})\\s*$").matches(line) -> { flushPara(); closeList(); cur.second.append("<hr>\n") }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(line.trim()) }
            }
        }
        flushPara(); closeList()
        if (inCode) cur.second.append("</pre>")
        parts.add(cur)
        val s = writeChapters(id, dir, parts.filter { it.second.isNotBlank() }.map { it.first to it.second.toString() }
            .ifEmpty { listOf("Text" to "<p></p>") })
        return Book(id, title ?: fallback, "", BookFormat.MD, null, System.currentTimeMillis(), pageCount = s.chapters.size)
    }

    /* ---------------- FB2 ---------------- */

    private fun importFb2(id: String, dir: File, raw: File, fallback: String): Book {
        val htmlDir = File(dir, "html").apply { mkdirs() }
        val imgDir = File(htmlDir, "img").apply { mkdirs() }
        val p = newParser()
        p.setInput(raw.inputStream(), null)
        var title = ""; var first = ""; var last = ""
        var inTitleInfo = false; var inAuthor = false; var coverHref: String? = null
        val parts = ArrayList<Pair<String, String>>()
        var bodyDepthSection = 0
        var sb = StringBuilder(); var partTitle = ""
        var inBody = false; var bodyIsNotes = false
        var inTitle = false; val titleText = StringBuilder()
        var binaryId: String? = null; var binaryType = ""
        val tagMap = mapOf("p" to "p", "emphasis" to "em", "strong" to "strong", "subtitle" to "h3", "epigraph" to "blockquote",
            "cite" to "blockquote", "poem" to "div", "stanza" to "div", "v" to "p", "text-author" to "p", "strikethrough" to "s",
            "sub" to "sub", "sup" to "sup", "code" to "code", "table" to "table", "tr" to "tr", "td" to "td", "th" to "th")
        fun flush() {
            if (sb.isNotBlank()) parts.add(partTitle.ifEmpty { if (bodyIsNotes) "Notes" else "Part ${parts.size + 1}" } to sb.toString())
            sb = StringBuilder(); partTitle = ""
        }
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            val name = local(p.name)
            when (p.eventType) {
                XmlPullParser.START_TAG -> when {
                    name == "title-info" -> inTitleInfo = true
                    inTitleInfo && name == "book-title" -> title = runCatching { p.nextText() }.getOrDefault("").trim()
                    inTitleInfo && name == "author" -> inAuthor = true
                    inTitleInfo && inAuthor && name == "first-name" -> if (first.isEmpty()) first = runCatching { p.nextText() }.getOrDefault("").trim()
                    inTitleInfo && inAuthor && name == "last-name" -> if (last.isEmpty()) last = runCatching { p.nextText() }.getOrDefault("").trim()
                    inTitleInfo && name == "image" -> {
                        for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == "href") coverHref = p.getAttributeValue(i).removePrefix("#")
                    }
                    name == "body" -> {
                        flush(); inBody = true
                        bodyIsNotes = p.getAttributeValue(null, "name") == "notes"
                    }
                    inBody && name == "section" -> {
                        bodyDepthSection++
                        if (bodyDepthSection == 1 && !bodyIsNotes) flush()
                        val sid = p.getAttributeValue(null, "id")
                        sb.append(if (sid != null) "<section id=\"${escape(sid)}\">" else "<section>")
                    }
                    inBody && name == "title" -> { inTitle = true; titleText.setLength(0); sb.append("<h2>") }
                    inBody && name == "empty-line" -> sb.append("<br>")
                    inBody && name == "image" -> {
                        var href = ""
                        for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == "href") href = p.getAttributeValue(i).removePrefix("#")
                        if (href.isNotEmpty()) sb.append("<img src=\"img/${escape(href)}\">")
                    }
                    inBody && name == "a" -> {
                        var href = ""
                        for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == "href") href = p.getAttributeValue(i)
                        sb.append("<a href=\"${escape(href)}\" epub:type=\"noteref\">")
                    }
                    inBody && tagMap.containsKey(name) -> {
                        val sid = p.getAttributeValue(null, "id")
                        sb.append("<${tagMap[name]}").append(if (sid != null) " id=\"${escape(sid)}\">" else ">")
                    }
                    name == "binary" -> { binaryId = p.getAttributeValue(null, "id"); binaryType = p.getAttributeValue(null, "content-type") ?: "" }
                }
                XmlPullParser.TEXT -> {
                    if (binaryId != null) {
                        runCatching {
                            val bytes = Base64.decode(p.text.trim(), Base64.DEFAULT)
                            File(imgDir, binaryId!!).writeBytes(bytes)
                        }
                    } else if (inBody) {
                        sb.append(escape(p.text))
                        if (inTitle) titleText.append(p.text).append(' ')
                    }
                }
                XmlPullParser.END_TAG -> when {
                    name == "title-info" -> inTitleInfo = false
                    name == "author" -> inAuthor = false
                    name == "body" -> { flush(); inBody = false }
                    inBody && name == "section" -> { sb.append("</section>"); bodyDepthSection-- }
                    inBody && name == "title" -> {
                        inTitle = false; sb.append("</h2>")
                        if (partTitle.isEmpty()) partTitle = titleText.toString().replace(Regex("\\s+"), " ").trim()
                    }
                    inBody && name == "a" -> sb.append("</a>")
                    inBody && tagMap.containsKey(name) -> sb.append("</${tagMap[name]}>")
                    name == "binary" -> binaryId = null
                }
            }
        }
        flush()
        raw.delete()
        if (parts.isEmpty()) throw ImportException("This FB2 book is empty.")
        val s = writeChapters(id, dir, parts)
        var cover: String? = null
        coverHref?.let { if (saveScaledCover(File(imgDir, it), File(dir, "cover.jpg"))) cover = "cover.jpg" }
        val author = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        return Book(id, title.ifEmpty { fallback }, author, BookFormat.FB2, cover, System.currentTimeMillis(), pageCount = s.chapters.size)
    }

    /* ---------------- DOCX ---------------- */

    private fun importDocx(id: String, dir: File, raw: File, fallback: String): Book {
        val parts = ArrayList<Pair<String, String>>()
        var title = fallback
        ZipFile(raw).use { z ->
            z.getEntry("docProps/core.xml")?.let { e ->
                runCatching {
                    val p = newParser(); p.setInput(z.getInputStream(e), null)
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "title") {
                            val t = p.nextText().trim(); if (t.isNotEmpty()) title = t; break
                        }
                    }
                }
            }
            val doc = z.getEntry("word/document.xml") ?: throw ImportException("This Word file is empty.")
            val p = newParser(); p.setInput(z.getInputStream(doc), null)
            var sb = StringBuilder(); var partTitle = ""
            val para = StringBuilder(); var style = ""; var bold = false; var italic = false
            var inRun = false; val run = StringBuilder()
            fun endRun() {
                if (run.isNotEmpty()) {
                    var t = escape(run.toString())
                    if (bold) t = "<strong>$t</strong>"
                    if (italic) t = "<em>$t</em>"
                    para.append(t)
                }
                run.setLength(0); bold = false; italic = false
            }
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                val n = local(p.name)
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (n) {
                        "p" -> { para.setLength(0); style = "" }
                        "pstyle" -> style = p.getAttributeValue(null, "w:val") ?: ""
                        "r" -> { inRun = true; run.setLength(0) }
                        "b" -> if (inRun) bold = p.getAttributeValue(null, "w:val") != "0"
                        "i" -> if (inRun) italic = p.getAttributeValue(null, "w:val") != "0"
                        "t" -> run.append(runCatching { p.nextText() }.getOrDefault(""))
                        "tab" -> run.append('\t')
                        "br" -> { endRun(); para.append("<br>") }
                    }
                    XmlPullParser.END_TAG -> when (n) {
                        "r" -> { endRun(); inRun = false }
                        "p" -> {
                            val content = para.toString()
                            val plain = content.replace(Regex("<[^>]+>"), "").trim()
                            val s = style.lowercase()
                            val heading = s.startsWith("heading") || s == "title"
                            val level = if (s == "title") 1 else s.filter { it.isDigit() }.toIntOrNull() ?: 2
                            if (heading && plain.isNotEmpty()) {
                                if (level <= 1 && sb.length > 2000) { parts.add(partTitle.ifEmpty { "Part ${parts.size + 1}" } to sb.toString()); sb = StringBuilder(); partTitle = "" }
                                if (partTitle.isEmpty()) partTitle = plain
                                val hl = level.coerceIn(1, 4) + 1
                                sb.append("<h$hl>$content</h$hl>\n")
                            } else if (plain.isNotEmpty()) sb.append("<p>$content</p>\n")
                            if (sb.length > 60_000) { parts.add(partTitle.ifEmpty { "Part ${parts.size + 1}" } to sb.toString()); sb = StringBuilder(); partTitle = "" }
                        }
                    }
                }
            }
            if (sb.isNotBlank()) parts.add(partTitle.ifEmpty { "Part ${parts.size + 1}" } to sb.toString())
        }
        raw.delete()
        if (parts.isEmpty()) throw ImportException("This Word file has no text.")
        val s = writeChapters(id, dir, parts)
        return Book(id, title, "", BookFormat.DOCX, null, System.currentTimeMillis(), pageCount = s.chapters.size)
    }

    /* ---------------- fonts ---------------- */

    suspend fun importFont(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val name = (displayName(context.contentResolver, uri) ?: "font.ttf").replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("ttf", "otf", "woff", "woff2")) throw ImportException("Pick a .ttf or .otf font file.")
        val out = File(Store.fontsDir, name)
        context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
            ?: throw ImportException("Couldn't open the font.")
        out
    }
}
