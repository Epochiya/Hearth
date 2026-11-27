package com.clockedbit.hearth.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clockedbit.hearth.data.Book
import com.clockedbit.hearth.data.BookStructure
import com.clockedbit.hearth.data.Highlight
import com.clockedbit.hearth.data.ReaderPrefs
import com.clockedbit.hearth.data.Store
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset

object ReaderAssets {
    var js: String = ""
        private set

    fun load(ctx: Context) {
        if (js.isEmpty()) js = ctx.assets.open("reader.js").bufferedReader().use { it.readText() }
    }
}

fun cssColor(argb: Int): String {
    val a = (argb ushr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "rgba($r,$g,$b,${"%.3f".format(java.util.Locale.US, a / 255f)})"
}

object ReaderCss {
    const val TOP = 46
    const val BOTTOM = 54

    fun fontStack(f: String): String? = when {
        f == "publisher" -> null
        f == "serif" -> "'Noto Serif', 'Droid Serif', Georgia, serif"
        f == "sans" -> "'Roboto', 'Noto Sans', 'Helvetica Neue', sans-serif"
        f == "mono" -> "'Roboto Mono', 'Droid Sans Mono', monospace"
        f == "hand" -> "'Dancing Script', 'Coming Soon', cursive"
        f == "slab" -> "'Roboto Slab', 'Noto Serif', serif"
        f.startsWith("custom:") -> "'HearthCustom', serif"
        else -> null
    }

    fun build(p: ReaderPrefs): String {
        val bg = cssColor(p.bg); val fg = cssColor(p.fg); val ac = cssColor(p.accent)
        val acSoft = cssColor((p.accent and 0x00FFFFFF) or 0x55000000)
        val ff = fontStack(p.fontFamily)
        val sb = StringBuilder()
        if (p.fontFamily.startsWith("custom:")) {
            val name = Uri.encode(p.fontFamily.removePrefix("custom:"))
            sb.append("@font-face{font-family:'HearthCustom';src:url('https://book.local/__font/$name');}")
        }
        sb.append("html{background:$bg !important;-webkit-text-size-adjust:100%;}")
        sb.append("html.hp{height:100vh;overflow:hidden !important;}")
        sb.append("html.hl-loading body{opacity:0;}")
        sb.append("body{margin:0 !important;box-sizing:border-box !important;background:transparent !important;")
        sb.append("color:$fg !important;font-size:${p.fontSize}px !important;line-height:${p.lineHeight} !important;")
        sb.append("letter-spacing:${p.letterSpacing}em;font-weight:${p.fontWeight};overflow-wrap:break-word;")
        sb.append("transition:opacity .22s ease;max-width:none !important;")
        if (ff != null) sb.append("font-family:$ff !important;")
        sb.append("}")
        sb.append("html.hp body{overflow:visible !important;}")
        if (p.overrideStyles) {
            sb.append("body *:not(mark){color:inherit !important;background-color:transparent !important;")
            sb.append("line-height:inherit !important;")
            if (ff != null) sb.append("font-family:inherit !important;")
            sb.append("}")
            sb.append("body pre, body code, body pre *{font-family:monospace !important;}")
            sb.append("p{margin-top:0 !important;margin-bottom:${p.paraSpacing}em !important;}")
            sb.append("h1,h2,h3,h4{line-height:1.3 !important;margin:0.6em 0 0.5em 0 !important;}")
        }
        if (p.justify) sb.append("p,div,li,blockquote{text-align:justify !important;}")
        if (p.hyphenate) sb.append("body{-webkit-hyphens:auto;hyphens:auto;}")
        sb.append("h1,h2,h3,h4,h5,h6{break-after:avoid;-webkit-column-break-after:avoid;font-weight:600 !important;}")
        val maxH = "calc(100vh - ${TOP + BOTTOM + 8}px)"
        sb.append("img,svg,video,image{max-width:100% !important;height:auto !important;max-height:$maxH !important;")
        sb.append("object-fit:contain;break-inside:avoid;-webkit-column-break-inside:avoid;border-radius:6px;}")
        sb.append("a{color:$ac !important;text-decoration:none !important;}")
        sb.append("mark.hl{color:inherit !important;border-radius:3px;-webkit-box-decoration-break:clone;box-decoration-break:clone;}")
        sb.append("mark.hl.note{border-bottom:2px dotted $ac;}")
        sb.append("mark.hl-flash{transition:background-color 1s;}")
        sb.append("::selection{background:$acSoft;}")
        sb.append("table{max-width:100%;border-collapse:collapse;}td,th{border:1px solid rgba(128,128,128,.3);padding:4px;}")
        sb.append("pre{white-space:pre-wrap;}hr{border:none;border-top:1px solid rgba(128,128,128,.35);margin:1.4em 15%;}")
        sb.append("blockquote{margin-left:1em;padding-left:1em;border-left:3px solid $acSoft;}")
        sb.append(".hearth-next{margin:3em 0 2em 0;padding:18px;border-radius:16px;text-align:center !important;")
        sb.append("border:1px solid $acSoft;color:$ac !important;font-family:sans-serif !important;}")
        sb.append(".hearth-next::after{content:'Next chapter  \\2192';}")
        return sb.toString()
    }

    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "source", "track", "wbr", "param", "keygen")
    private val selfClosing = Regex("<([a-zA-Z][\\w:-]*)((?:\\s[^<>]*?)?)\\s*/>")

    fun inject(html: String, css: String, js: String): String {
        var h = html.replace(Regex("^\\s*<\\?xml[^>]*\\?>"), "")
        h = h.replace(Regex("<meta[^>]+name\\s*=\\s*[\"']viewport[\"'][^>]*>", RegexOption.IGNORE_CASE), "")
        // XHTML self-closing tags break the HTML parser (e.g. <title/> swallows the page)
        h = selfClosing.replace(h) { m ->
            val tag = m.groupValues[1]
            if (tag.lowercase() in voidTags) m.value else "<$tag${m.groupValues[2]}></$tag>"
        }
        val head = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">" +
            "<style id=\"hearth-style\">$css</style><script>$js</script>"
        val headTag = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(h)
        if (headTag != null) return h.substring(0, headTag.range.last + 1) + head + h.substring(headTag.range.last + 1)
        val body = Regex("<body", RegexOption.IGNORE_CASE).find(h)
        if (body != null) return h.substring(0, body.range.first) + "<head>$head</head>" + h.substring(body.range.first)
        return "<html><head>$head</head><body>$h</body></html>"
    }

    fun charsetOf(bytes: ByteArray): Charset {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val m = Regex("(?:encoding|charset)\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(head)
        return m?.let { runCatching { Charset.forName(it.groupValues[1]) }.getOrNull() } ?: Charsets.UTF_8
    }
}

/** WebView that adds Highlight / Note to the text selection menu. */
class ReaderWebView(context: Context) : WebView(context) {
    var onSelectionAction: ((String) -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        val cb = callback ?: return super.startActionMode(null, type)
        val wrapped = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                cb.onCreateActionMode(mode, menu)
                menu.add(Menu.NONE, ID_HL, 0, "Highlight")
                menu.add(Menu.NONE, ID_NOTE, 1, "Note")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = cb.onPrepareActionMode(mode, menu)
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    ID_HL -> { onSelectionAction?.invoke("highlight"); mode.finish(); return true }
                    ID_NOTE -> { onSelectionAction?.invoke("note"); mode.finish(); return true }
                }
                return cb.onActionItemClicked(mode, item)
            }
            override fun onDestroyActionMode(mode: ActionMode) = cb.onDestroyActionMode(mode)
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (cb is ActionMode.Callback2) cb.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
            }
        }
        return super.startActionMode(wrapped, type)
    }

    companion object {
        private const val ID_HL = 0x4801
        private const val ID_NOTE = 0x4802
    }
}

interface ReflowListener {
    fun toggleUi()
    fun singleTap()
    fun highlightTap(id: String)
    fun selection(action: String, start: Int, end: Int, text: String)
    fun footnote(text: String)
    fun pinch(scale: Float)
    fun autoScrollEnd()
    fun image(file: File)
    fun pageChanged()
}

/** Drives a book chapter-by-chapter inside a WebView. All state is Compose-observable. */
class ReflowEngine(val book: Book, val structure: BookStructure, val dir: File) {
    var chapter by mutableIntStateOf(0)
    var page by mutableIntStateOf(0)
    var pages by mutableIntStateOf(1)
    var start by mutableIntStateOf(0)
    var end by mutableIntStateOf(0)
    var excerpt by mutableStateOf("")
    var loading by mutableStateOf(true)

    var web: ReaderWebView? = null
    var prefs: ReaderPrefs = ReaderPrefs()
    var listener: ReflowListener? = null
    var highlightsFor: (Int) -> List<Highlight> = { emptyList() }

    private var pendingTarget = JSONObject()
    private val weights: List<Long> = structure.chapters.map { it.weight.coerceAtLeast(1) }
    private val totalWeight: Long = weights.sum().coerceAtLeast(1)

    val chapterCount get() = structure.chapters.size

    fun cumulative(c: Int): Long = weights.take(c.coerceIn(0, weights.size)).sum()

    val progress: Float
        get() {
            if (weights.isEmpty()) return 0f
            val frac = if (pages <= 1) 0f else page.toFloat() / pages
            return ((cumulative(chapter) + weights[chapter.coerceIn(0, weights.size - 1)] * frac) / totalWeight).coerceIn(0f, 1f)
        }

    fun fractionToChapter(f: Float): Pair<Int, Float> {
        val target = (f.coerceIn(0f, 1f) * totalWeight).toLong()
        var acc = 0L
        weights.forEachIndexed { i, w ->
            if (target < acc + w || i == weights.size - 1) return i to ((target - acc).toFloat() / w).coerceIn(0f, 1f)
            acc += w
        }
        return 0 to 0f
    }

    fun position(): String = JSONObject().put("c", chapter).put("o", start).toString()

    fun restore(pos: String) {
        val o = runCatching { JSONObject(pos) }.getOrNull()
        if (o != null) openChapter(o.optInt("c"), JSONObject().put("offset", o.optInt("o")))
        else openChapter(0, JSONObject())
    }

    private fun urlFor(c: Int) = "https://book.local/" +
        structure.chapters[c].href.split('/').joinToString("/") { Uri.encode(it) }

    fun openChapter(i: Int, target: JSONObject) {
        if (structure.chapters.isEmpty()) return
        val c = i.coerceIn(0, structure.chapters.size - 1)
        chapter = c
        loading = true
        pendingTarget = target
        web?.loadUrl(urlFor(c))
    }

    fun cfg(): JSONObject = JSONObject()
        .put("paged", !prefs.scrollMode).put("margin", prefs.margin)
        .put("top", ReaderCss.TOP).put("bottom", ReaderCss.BOTTOM)
        .put("twoPage", prefs.twoPage).put("tapZones", prefs.tapZones).put("anim", prefs.pageAnim)

    private fun hlJson(h: Highlight) = JSONObject().put("id", h.id).put("start", h.start).put("end", h.end)
        .put("color", cssColor(h.color)).put("note", h.note.isNotBlank())

    fun onPageFinished() {
        val hl = JSONArray()
        highlightsFor(chapter).forEach { hl.put(hlJson(it)) }
        val o = JSONObject().put("cfg", cfg()).put("target", pendingTarget).put("hl", hl)
        eval("window.hearth && hearth.init($o)")
    }

    fun applyPrefs(p: ReaderPrefs) {
        prefs = p
        web?.setBackgroundColor(p.bg)
        eval("window.hearth && hearth.setStyle(${JSONObject.quote(ReaderCss.build(p))}, ${cfg()})")
    }

    fun next() = eval("window.hearth && hearth.next()")
    fun prev() = eval("window.hearth && hearth.prev()")

    fun nextChapter() { if (chapter < chapterCount - 1) openChapter(chapter + 1, JSONObject()) }
    fun prevChapter() { if (chapter > 0) openChapter(chapter - 1, JSONObject().put("end", true)) }

    fun seek(f: Float) {
        val (c, frac) = fractionToChapter(f)
        if (c == chapter && !loading) eval("hearth.goFraction($frac)")
        else openChapter(c, JSONObject().put("fraction", frac.toDouble()))
    }

    fun goTo(c: Int, offset: Int, flash: Int = 0) {
        if (c == chapter && !loading) eval("hearth.goOffset($offset,$flash)")
        else openChapter(c, JSONObject().put("offset", offset).put("flash", flash))
    }

    fun goAnchor(c: Int, anchor: String?) {
        if (anchor == null) openChapter(c, JSONObject())
        else if (c == chapter && !loading) eval("hearth.goAnchor(${JSONObject.quote(anchor)})")
        else openChapter(c, JSONObject().put("anchor", anchor))
    }

    fun find(c: Int, q: String, k: Int) {
        if (c == chapter && !loading) eval("hearth.find(${JSONObject.quote(q)},$k)")
        else openChapter(c, JSONObject().put("find", q).put("k", k))
    }

    fun addHl(h: Highlight) = eval("hearth.addHl(${hlJson(h)})")
    fun removeHl(id: String) = eval("hearth.removeHl(${JSONObject.quote(id)})")
    fun updateHl(h: Highlight) = eval("hearth.updateHl(${hlJson(h)})")
    fun useSelection(action: String) = eval("hearth.useSelection('$action')")

    fun pageText(cb: (String) -> Unit) {
        val w = web ?: return cb("")
        w.evaluateJavascript("window.hearth ? hearth.pageText() : ''") { v ->
            cb(runCatching { JSONTokener(v).nextValue() as? String }.getOrNull() ?: "")
        }
    }

    fun eval(js: String) { web?.evaluateJavascript(js, null) }

    fun onPage(p: Int, n: Int, s: Int, e: Int, ex: String) {
        page = p; pages = n; start = s; end = e; excerpt = ex; loading = false
        listener?.pageChanged()
    }

    fun fileForUrl(url: Uri): File? {
        if (url.host != "book.local") return null
        val path = url.path?.removePrefix("/") ?: return null
        val f = File(dir, path)
        return if (f.canonicalPath.startsWith(dir.canonicalPath) && f.isFile) f else null
    }
}

class ReflowBridge(private val engine: ReflowEngine) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun onPage(p: Int, n: Int, s: Int, e: Int, ex: String) = post { engine.onPage(p, n, s, e, ex) }
    @JavascriptInterface fun toggleUi() = post { engine.listener?.toggleUi() }
    @JavascriptInterface fun singleTap() = post { engine.listener?.singleTap() }
    @JavascriptInterface fun nextChapter() = post { engine.nextChapter() }
    @JavascriptInterface fun prevChapter() = post { engine.prevChapter() }
    @JavascriptInterface fun onHighlightTap(id: String) = post { engine.listener?.highlightTap(id) }
    @JavascriptInterface fun onSelection(action: String, s: Int, e: Int, text: String) =
        post { engine.listener?.selection(action, s, e, text) }
    @JavascriptInterface fun footnote(text: String) = post { engine.listener?.footnote(text) }
    @JavascriptInterface fun pinch(scale: Float) = post { engine.listener?.pinch(scale) }
    @JavascriptInterface fun autoScrollEnd() = post { engine.listener?.autoScrollEnd() }
    @JavascriptInterface fun openImage(src: String) = post {
        engine.fileForUrl(Uri.parse(src))?.let { engine.listener?.image(it) }
    }

    private fun post(f: () -> Unit) { main.post { f() } }
}

class BookClient(private val engine: ReflowEngine, private val ctx: Context) : WebViewClient() {

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, ByteArrayInputStream(ByteArray(0)))

    private fun mime(ext: String): String = when (ext) {
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "js" -> "application/javascript"
        "ttf" -> "font/ttf"; "otf" -> "font/otf"; "woff" -> "font/woff"; "woff2" -> "font/woff2"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != "book.local") return null
        return try {
            val path = url.path ?: return notFound()
            if (path.startsWith("/__font/")) {
                val f = File(Store.fontsDir, path.removePrefix("/__font/"))
                return if (f.isFile) WebResourceResponse(mime(f.extension.lowercase()), null, FileInputStream(f)) else notFound()
            }
            val f = engine.fileForUrl(url) ?: return notFound()
            val ext = f.extension.lowercase()
            if (request.isForMainFrame && ext in setOf("html", "htm", "xhtml", "xml")) {
                val raw = f.readBytes()
                val html = String(raw, ReaderCss.charsetOf(raw))
                val out = ReaderCss.inject(html, ReaderCss.build(engine.prefs), ReaderAssets.js)
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)))
            } else {
                WebResourceResponse(mime(ext), null, FileInputStream(f))
            }
        } catch (e: Exception) {
            notFound()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val u = request.url
        if (u.host == "book.local") {
            val p = u.path?.removePrefix("/") ?: return true
            val idx = engine.structure.chapters.indexOfFirst { it.href == p }
            if (idx >= 0) {
                val frag = u.fragment
                engine.openChapter(idx, if (frag != null) JSONObject().put("anchor", frag) else JSONObject())
            }
            return true
        }
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (url != null && url.contains("book.local")) engine.onPageFinished()
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun createReaderWebView(ctx: Context, engine: ReflowEngine): ReaderWebView = ReaderWebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = false
    settings.textZoom = 100
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    setBackgroundColor(engine.prefs.bg)
    addJavascriptInterface(ReflowBridge(engine), "HearthBridge")
    webViewClient = BookClient(engine, ctx)
    onSelectionAction = { engine.useSelection(it) }
}
