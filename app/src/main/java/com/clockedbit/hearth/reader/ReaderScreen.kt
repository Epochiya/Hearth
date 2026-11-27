package com.clockedbit.hearth.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import android.os.BatteryManager
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.clockedbit.hearth.KeyBus
import com.clockedbit.hearth.data.*
import com.clockedbit.hearth.ui.exportNotes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ReaderSheet { TYPE, COLORS, SETTINGS }
enum class SidePanel { CONTENTS, SEARCH }

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun recolorFilter(bg: Int, fg: Int): ColorFilter {
    fun ch(c: Int, s: Int) = ((c shr s) and 0xFF).toFloat()
    val fr = ch(fg, 16); val fgG = ch(fg, 8); val fb = ch(fg, 0)
    val kr = (ch(bg, 16) - fr) / 255f; val kg = (ch(bg, 8) - fgG) / 255f; val kb = (ch(bg, 0) - fb) / 255f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                kr * 0.299f, kr * 0.587f, kr * 0.114f, 0f, fr,
                kg * 0.299f, kg * 0.587f, kg * 0.114f, 0f, fgG,
                kb * 0.299f, kb * 0.587f, kb * 0.114f, 0f, fb,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onClose: () -> Unit) {
    val book0 = remember(bookId) { Store.book(bookId) }
    if (book0 == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val scope = rememberCoroutineScope()
    val prefs by Store.prefs.collectAsState()
    // Comics always read as one long vertical scroll
    val scrollMode = prefs.scrollMode || book0.format == BookFormat.CBZ
    val dir = remember { Store.bookDir(bookId) }
    val structure = remember { Store.structure(bookId) }
    val reflow = book0.format.reflow

    var ann by remember { mutableStateOf(Store.annotations(bookId)) }
    fun editAnn(f: (Annotations) -> Annotations) {
        ann = f(ann); Store.saveAnnotations(bookId, ann)
    }

    var ui by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var side by remember { mutableStateOf<SidePanel?>(null) }
    var lastSide by remember { mutableStateOf(SidePanel.CONTENTS) }
    var pen by remember { mutableStateOf(false) }
    var penTool by remember { mutableIntStateOf(InkTool.PEN) }
    var lastActive by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var editHl by remember { mutableStateOf<Highlight?>(null) }
    var noteSel by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var pageNote by remember { mutableStateOf<PageNote?>(null) }
    var footnote by remember { mutableStateOf<String?>(null) }
    var imageFile by remember { mutableStateOf<File?>(null) }
    var speaking by remember { mutableStateOf(false) }
    var spokenKey by remember { mutableStateOf("") }
    var msPerPage by remember { mutableFloatStateOf(70_000f) }
    var lastTurn by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var zoom by remember { mutableFloatStateOf(book0.zoom.coerceIn(1f, 5f)) }
    var pan by remember { mutableStateOf(Offset(1e6f, 1e6f)) }
    var zoomLock by remember { mutableStateOf(book0.zoomLock) }
    var fitWidth by remember { mutableFloatStateOf(1f) }
    var lastImgPage by remember { mutableIntStateOf(-1) }
    var jumpBack by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var gotoOpen by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }
    val undo = remember { mutableStateListOf<InkStroke>() }
    val redo = remember { mutableStateListOf<InkStroke>() }

    val engine: ReflowEngine? = remember {
        if (reflow) ReflowEngine(book0, structure, dir).also { it.prefs = Store.prefs.value } else null
    }
    val source: PageSource? = remember {
        if (reflow) null
        else if (book0.format == BookFormat.PDF) runCatching { PdfSource(File(dir, "book.pdf")) }.getOrNull()
        else ImagePagesSource(structure.chapters.map { File(dir, it.href) })
    }
    DisposableEffect(Unit) { onDispose { source?.close() } }
    val pageCount = source?.count ?: 0
    val initialPage = remember { book0.position.toIntOrNull()?.coerceIn(0, max(0, pageCount - 1)) ?: 0 }
    val pagerState = rememberPagerState(initialPage) { pageCount }
    val listState = rememberLazyListState(initialPage)
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    // ---- where are we? ----
    val chapter = engine?.chapter ?: 0
    // Scrolled all the way down = on the last page (the top of the screen may still show an earlier one)
    val atEnd = scrollMode && engine == null && pageCount > 0 &&
        listState.layoutInfo.visibleItemsInfo.isNotEmpty() && !listState.canScrollForward
    val imgPage = if (scrollMode) (if (atEnd) pageCount - 1 else listState.firstVisibleItemIndex) else pagerState.currentPage
    val startOff = engine?.start ?: -1
    val endOff = engine?.end ?: -1
    val progress = engine?.progress ?: if (pageCount <= 1) 1f else imgPage / (pageCount - 1f)
    fun here(c: Int, off: Int, p: Int): Boolean =
        if (engine != null) c == chapter && off >= startOff && (off < endOff || off == startOff) else p == imgPage

    // keep the same page when switching between swipe and scroll
    var modeSeen by remember { mutableStateOf(scrollMode) }
    var stablePage by remember { mutableIntStateOf(initialPage) }
    SideEffect { if (modeSeen == scrollMode) stablePage = imgPage }
    LaunchedEffect(scrollMode) {
        if (modeSeen != scrollMode) {
            if (engine == null && pageCount > 0) {
                val target = stablePage.coerceIn(0, pageCount - 1)
                if (scrollMode) listState.scrollToItem(target) else pagerState.scrollToPage(target)
            }
            modeSeen = scrollMode
        }
    }

    // keep zoom across pages: land at the top-left going forward, bottom-right going back
    LaunchedEffect(imgPage) {
        val sx = if (prefs.rtlPages) -1e6f else 1e6f
        if (lastImgPage >= 0 && zoom > 1.01f) pan = if (imgPage >= lastImgPage) Offset(sx, 1e6f) else Offset(-sx, -1e6f)
        lastImgPage = imgPage
    }
    LaunchedEffect(zoom, zoomLock) {
        if (engine != null) return@LaunchedEffect
        delay(500)
        Store.updateBook(bookId) { it.copy(zoom = zoom, zoomLock = zoomLock) }
    }

    val pageBg = Color(prefs.bg)
    val pageFg = Color(prefs.fg)
    val accent = Color(prefs.accent)
    val darkPage = pageBg.luminance() < 0.4f

    // ---- navigation ----
    fun next() {
        if (engine != null) engine.next() else scope.launch {
            if (scrollMode) listState.animateScrollToItem(min(imgPage + 1, max(0, pageCount - 1)))
            else pagerState.animateScrollToPage(min(pagerState.currentPage + 1, max(0, pageCount - 1)))
        }
    }
    fun prev() {
        if (engine != null) engine.prev() else scope.launch {
            if (scrollMode) listState.animateScrollToItem(max(imgPage - 1, 0))
            else pagerState.animateScrollToPage(max(pagerState.currentPage - 1, 0))
        }
    }
    fun goPage(p: Int) {
        scope.launch {
            if (scrollMode) listState.scrollToItem(p.coerceIn(0, max(0, pageCount - 1)))
            else pagerState.scrollToPage(p.coerceIn(0, max(0, pageCount - 1)))
        }
    }
    fun markJump() { jumpBack = Triple(chapter, startOff, imgPage) }
    fun seek(f: Float) {
        markJump()
        if (engine != null) engine.seek(f) else goPage((f * (pageCount - 1)).roundToInt())
    }
    fun goLoc(c: Int, off: Int, p: Int, flash: Int = 0) {
        markJump()
        if (engine != null) engine.goTo(c, max(0, off), flash) else goPage(p)
        side = null
    }

    // ---- text to speech ----
    fun speakPage() {
        val e = engine ?: return
        val key = "${e.chapter}:${e.start}"
        if (key == spokenKey) return
        spokenKey = key
        e.pageText { t ->
            if (!speaking) return@pageText
            if (t.isBlank()) e.next()
            else if (!speaker.speak(t, prefs.ttsRate)) {
                speaking = false
                Toast.makeText(ctx, "Text-to-speech isn't available on this device", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun toggleSpeak() {
        if (speaking) { speaking = false; speaker.stop() }
        else { speaking = true; spokenKey = ""; speakPage() }
    }
    SideEffect {
        speaker.onDone = {
            val e = engine
            if (speaking && e != null) {
                if (e.chapter >= e.chapterCount - 1 && e.page >= e.pages - 1) speaking = false else e.next()
            }
        }
    }

    // ---- engine wiring ----
    SideEffect {
        engine?.highlightsFor = { c -> ann.highlights.filter { it.chapter == c } }
        engine?.listener = object : ReflowListener {
            override fun toggleUi() { if (!pen) { ui = !ui; if (!ui) sheet = null } }
            override fun singleTap() { if (ui) ui = false; side = null }
            override fun highlightTap(id: String) { editHl = ann.highlights.firstOrNull { it.id == id } }
            override fun selection(action: String, start: Int, end: Int, text: String) {
                val e = engine ?: return
                if (action == "note") { noteSel = Triple(start, end, text); return }
                val h = Highlight(newId(), e.chapter, start, end, text, prefs.hlColor, "", System.currentTimeMillis())
                editAnn { it.copy(highlights = it.highlights + h) }
                e.addHl(h)
            }
            override fun footnote(text: String) { footnote = text }
            override fun autoScrollEnd() {
                val e = engine ?: return
                if (e.chapter < e.chapterCount - 1) e.nextChapter() else autoScroll = false
            }
            override fun pinch(scale: Float) {
                val cur = Store.prefs.value.fontSize
                val n = (cur * scale).roundToInt().coerceIn(10, 48)
                if (n != cur) {
                    Store.updatePrefs { it.copy(fontSize = n) }
                    Toast.makeText(ctx, "Text size $n", Toast.LENGTH_SHORT).show()
                }
            }
            override fun image(file: File) { imageFile = file }
            override fun pageChanged() {
                lastActive = System.currentTimeMillis()
                if (speaking) speakPage()
            }
        }
    }

    // apply style changes (debounced) to the open chapter
    val styleKey = ReaderCss.build(prefs) + "|${scrollMode}|${prefs.margin}|${prefs.twoPage}|${prefs.tapZones}|${prefs.pageAnim}"
    var appliedKey by remember { mutableStateOf(styleKey) }
    SideEffect { if (engine != null && appliedKey == styleKey) engine.prefs = prefs }
    LaunchedEffect(styleKey) {
        if (engine == null || styleKey == appliedKey) return@LaunchedEffect
        delay(140)
        engine.applyPrefs(prefs)
        appliedKey = styleKey
    }

    // ---- reading pace + position saving ----
    val posKey = if (engine != null) "${engine.chapter}:${engine.start}:${engine.loading}" else "$imgPage"
    LaunchedEffect(posKey) {
        val now = System.currentTimeMillis()
        val dt = now - lastTurn
        if (dt in 3_000L..300_000L) msPerPage = msPerPage * 0.8f + dt * 0.2f
        lastTurn = now
        lastActive = now
        if (engine != null && engine.loading) return@LaunchedEffect
        if (modeSeen != scrollMode) return@LaunchedEffect
        delay(500)
        val pos = engine?.position() ?: imgPage.toString()
        val pr = progress
        Store.updateBook(bookId) {
            it.copy(position = pos, progress = pr, lastOpened = System.currentTimeMillis(), finished = it.finished || pr >= 0.995f)
        }
    }
    LaunchedEffect(Unit) {
        Store.updateBook(bookId) { it.copy(lastOpened = System.currentTimeMillis()) }
        while (true) {
            delay(30_000)
            if (System.currentTimeMillis() - lastActive < 180_000) Store.logReading(bookId, 30_000)
        }
    }

    // ---- auto-scroll ----
    val density = LocalDensity.current
    LaunchedEffect(autoScroll, prefs.autoScrollSpeed, engine?.loading, engine?.chapter, scrollMode) {
        if (autoScroll && !scrollMode) { Store.updatePrefs { it.copy(scrollMode = true) }; return@LaunchedEffect }
        if (engine != null) {
            val px = prefs.autoScrollSpeed
            engine.eval("window.hearth && hearth.autoScroll(${if (autoScroll && !engine.loading) px else 0f})")
            return@LaunchedEffect
        }
        if (!autoScroll) return@LaunchedEffect
        var last = 0L
        while (true) {
            val dt = withFrameNanos { t -> val d = if (last == 0L) 0f else (t - last) / 1e9f; last = t; d }
            listState.scrollBy(prefs.autoScrollSpeed * density.density * dt)
            if (!listState.canScrollForward) { autoScroll = false; break }
        }
    }

    // ---- window: brightness, keep-awake, rotation, immersive ----
    LaunchedEffect(prefs.brightness) {
        val w = activity?.window ?: return@LaunchedEffect
        val lp = w.attributes
        lp.screenBrightness = if (prefs.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else prefs.brightness.coerceIn(0.02f, 1f)
        w.attributes = lp
    }
    LaunchedEffect(prefs.keepScreenOn) {
        val w = activity?.window ?: return@LaunchedEffect
        if (prefs.keepScreenOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(prefs.orientation) {
        activity?.requestedOrientation = when (prefs.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(ui, darkPage) {
        val w = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(w, w.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.isAppearanceLightStatusBars = !darkPage
        c.isAppearanceLightNavigationBars = !darkPage
        if (ui) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { w ->
                val c = WindowCompat.getInsetsController(w, w.decorView)
                c.show(WindowInsetsCompat.Type.systemBars())
                c.isAppearanceLightStatusBars = false
                c.isAppearanceLightNavigationBars = false
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(prefs.volumeKeys) {
        KeyBus.enabled = prefs.volumeKeys
        onDispose { KeyBus.enabled = false }
    }
    LaunchedEffect(Unit) { KeyBus.events.collect { d -> if (d > 0) next() else prev() } }

    BackHandler {
        when {
            sheet != null -> sheet = null
            side != null -> side = null
            pen -> pen = false
            ui -> ui = false
            else -> onClose()
        }
    }

    // ---- annotations on this page ----
    val pageStrokes = remember(ann.ink, chapter, startOff, endOff, imgPage) {
        ann.ink.filter { here(it.chapter, it.offset, it.page) }
    }
    val bookmarkHere = ann.bookmarks.firstOrNull { here(it.chapter, it.offset, it.page) }
    val notesHere = ann.notes.filter { here(it.chapter, it.offset, it.page) }

    fun addStroke(tool: Int, color: Int, w: Float, pts: FloatArray, p: Int) {
        val s = InkStroke(newId(), if (engine != null) chapter else 0, if (engine != null) startOff else -1,
            if (engine != null) -1 else p, tool, color, w, pts)
        editAnn { it.copy(ink = it.ink + s) }
        undo.add(s); redo.clear()
    }
    fun eraseStroke(s: InkStroke) {
        editAnn { a -> a.copy(ink = a.ink.filter { it.id != s.id }) }
        undo.remove(s)
    }
    fun toggleBookmark() {
        if (bookmarkHere != null) editAnn { a -> a.copy(bookmarks = a.bookmarks.filter { it.id != bookmarkHere.id }) }
        else {
            val ex = engine?.excerpt?.ifBlank { null } ?: "Page ${imgPage + 1}"
            val b = Bookmark(newId(), chapter, if (engine != null) startOff else -1, if (engine != null) -1 else imgPage, ex, System.currentTimeMillis())
            editAnn { it.copy(bookmarks = it.bookmarks + b) }
        }
    }

    val penColor = if (penTool == InkTool.MARKER) prefs.markerColor else prefs.penColor
    val penWidth = if (penTool == InkTool.MARKER) prefs.markerWidth else prefs.penWidth
    val recolor = remember(prefs.bg, prefs.fg, prefs.recolorPages) {
        if (book0.format == BookFormat.PDF && prefs.recolorPages) recolorFilter(prefs.bg, prefs.fg) else null
    }

    val chapterTitle = if (engine != null) structure.chapters.getOrNull(chapter)?.title ?: "" else ""
    val leftInChapter = if (engine != null) max(0, engine.pages - engine.page - 1) else max(0, pageCount - imgPage - 1)
    val minsLeft = ceil(leftInChapter * msPerPage / 60_000f).toInt()

    Box(Modifier.fillMaxSize().background(pageBg)) {
        // ---------- page content ----------
        if (engine != null) {
            AndroidView(
                factory = { c ->
                    createReaderWebView(c, engine).also {
                        engine.web = it
                        engine.restore(book0.position)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { v ->
                    if (engine.web === v) engine.web = null
                    v.destroy()
                }
            )
            if (pen || pageStrokes.isNotEmpty()) {
                InkLayer(
                    strokes = pageStrokes, active = pen, tool = penTool, color = penColor, widthDp = penWidth,
                    palmMode = prefs.palmMode, passFingers = false,
                    onStroke = { t, col, w, pts -> addStroke(t, col, w, pts, -1) },
                    onErase = { eraseStroke(it) },
                    onSwipe = { if (it > 0) engine.next() else engine.prev() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (source != null) {
            PagesView(
                source = source, pagerState = pagerState, listState = listState, scrollMode = scrollMode,
                recolor = recolor, ink = ann.ink, pen = pen, penTool = penTool, penColor = penColor, penWidth = penWidth,
                palmMode = prefs.palmMode,
                onStroke = { t, col, w, pts, p -> addStroke(t, col, w, pts, p) },
                onErase = { eraseStroke(it) },
                onTapZone = { z0 ->
                    val z = if (prefs.rtlPages) -z0 else z0
                    if (autoScroll) { autoScroll = false; return@PagesView }
                    when {
                        z < 0 && prefs.tapZones && !ui -> prev()
                        z > 0 && prefs.tapZones && !ui -> next()
                        else -> { ui = false; side = null }
                    }
                },
                onDoubleTap = { if (!pen) ui = !ui },
                zoom = zoom, pan = pan, zoomLock = zoomLock,
                onZoomPan = { z, o -> zoom = z; pan = o },
                onEdgeTurn = { d0 -> val d = if (prefs.rtlPages) -d0 else d0; if (d > 0) next() else prev() },
                onFitWidth = { fitWidth = it },
                crop = prefs.cropMargins, gap = prefs.pageGap, rtl = prefs.rtlPages
            )
        } else {
            Text("Couldn't open this book.", color = pageFg, modifier = Modifier.align(Alignment.Center))
        }

        // ---------- night warmth ----------
        if (prefs.warmth > 0f) Box(Modifier.fillMaxSize().background(Color(0xFFFF8A1F).copy(alpha = prefs.warmth * 0.3f)))

        // ---------- bookmark ribbon ----------
        AnimatedVisibility(
            bookmarkHere != null, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 28.dp)
        ) {
            Canvas(Modifier.size(width = 20.dp, height = 34.dp)) {
                val p = Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
                    lineTo(size.width / 2f, size.height * 0.76f); lineTo(0f, size.height); close()
                }
                drawPath(p, accent)
            }
        }

        // ---------- page notes ----------
        if (notesHere.isNotEmpty() && !ui) {
            Surface(
                onClick = { pageNote = notesHere.first() },
                shape = RoundedCornerShape(50), color = accent.copy(alpha = 0.18f),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 10.dp)
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.StickyNote2, null, tint = accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (notesHere.size == 1) "Note" else "${notesHere.size} notes", color = accent, fontSize = 11.sp)
                }
            }
        }

        // ---------- quiet status line ----------
        if (!ui && !pen && prefs.showStatus) {
            val (clock, battery) = rememberClockBattery()
            val dim = pageFg.copy(alpha = 0.42f)
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (prefs.showClock) {
                    Text(clock, color = dim, fontSize = 11.sp)
                    if (battery >= 0) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (battery > 20) Icons.Rounded.BatteryStd else Icons.Rounded.BatteryAlert, null,
                            tint = dim, modifier = Modifier.size(12.dp)
                        )
                        Text("$battery%", color = dim, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                }
                Text(
                    if (engine != null) chapterTitle else "Page ${imgPage + 1} of $pageCount",
                    color = dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    (if (leftInChapter > 0 && engine != null) "$minsLeft min left · " else "") + "${(progress * 100).roundToInt()}%",
                    color = dim, fontSize = 11.sp
                )
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(accent.copy(alpha = 0.35f)))
        }

        // ---------- jump back ----------
        AnimatedVisibility(
            jumpBack != null && !pen, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                .padding(start = 18.dp, bottom = if (ui) 190.dp else 40.dp)
        ) {
            val j = jumpBack
            if (j != null) Surface(shape = RoundedCornerShape(50), color = pageBg, contentColor = pageFg, shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable {
                            val here = Triple(chapter, startOff, imgPage)
                            if (engine != null) engine.goTo(j.first, max(0, j.second)) else goPage(j.third)
                            jumpBack = here
                        }.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.KeyboardReturn, null, tint = accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Back to " + if (engine != null) (structure.chapters.getOrNull(j.first)?.title ?: "previous place") else "page ${j.third + 1}",
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)
                        )
                    }
                    IconButton({ jumpBack = null }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Dismiss", modifier = Modifier.size(14.dp)) }
                }
            }
        }

        // ---------- auto-scroll pill ----------
        AnimatedVisibility(
            autoScroll && !ui, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        ) {
            Surface(shape = RoundedCornerShape(50), color = accent, contentColor = pageBg) {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ Store.updatePrefs { it.copy(autoScrollSpeed = (it.autoScrollSpeed - 10f).coerceAtLeast(5f)) } }, Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Remove, "Slower", tint = pageBg)
                    }
                    Text("Auto-scroll ${prefs.autoScrollSpeed.roundToInt()}", fontSize = 12.sp, color = pageBg)
                    IconButton({ Store.updatePrefs { it.copy(autoScrollSpeed = (it.autoScrollSpeed + 10f).coerceAtMost(400f)) } }, Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Add, "Faster", tint = pageBg)
                    }
                    IconButton({ autoScroll = false }, Modifier.size(34.dp)) { Icon(Icons.Rounded.Stop, "Stop", tint = pageBg) }
                }
            }
        }

        // ---------- listening pill ----------
        AnimatedVisibility(
            speaking && !ui, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        ) {
            Surface(onClick = { toggleSpeak() }, shape = RoundedCornerShape(50), color = accent) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Pause, null, tint = pageBg, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reading aloud", color = pageBg, fontSize = 12.sp)
                }
            }
        }

        // ---------- top bar ----------
        AnimatedVisibility(
            ui, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            var more by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalContentColor provides pageFg) {
                Surface(color = pageBg.copy(alpha = 0.95f), contentColor = pageFg, shadowElevation = 6.dp) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(book0.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (engine != null) chapterTitle else "Page ${imgPage + 1} of $pageCount",
                                style = MaterialTheme.typography.bodySmall, color = pageFg.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (engine != null) IconButton({ side = SidePanel.SEARCH; lastSide = SidePanel.SEARCH }) { Icon(Icons.Rounded.Search, "Search") }
                        IconButton({ toggleBookmark() }) {
                            Icon(
                                if (bookmarkHere != null) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Bookmark",
                                tint = if (bookmarkHere != null) accent else pageFg
                            )
                        }
                        Box {
                            IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(more, { more = false }) {
                                DropdownMenuItem(
                                    text = { Text("Add note to this page") }, leadingIcon = { Icon(Icons.Rounded.StickyNote2, null) },
                                    onClick = {
                                        more = false
                                        pageNote = PageNote("", chapter, if (engine != null) startOff else -1, if (engine != null) -1 else imgPage, "", 0L)
                                    }
                                )
                                if (engine != null) DropdownMenuItem(
                                    text = { Text(if (speaking) "Stop reading aloud" else "Read aloud") },
                                    leadingIcon = { Icon(Icons.Rounded.Headphones, null) },
                                    onClick = { more = false; toggleSpeak() }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (autoScroll) "Stop auto-scroll" else "Auto-scroll") },
                                    leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                                    onClick = { more = false; autoScroll = !autoScroll; if (autoScroll) ui = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Go to page…") },
                                    leadingIcon = { Icon(Icons.Rounded.Tag, null) },
                                    onClick = { more = false; gotoOpen = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export highlights & notes") }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                    onClick = { more = false; Store.book(bookId)?.let { exportNotes(ctx, it) } }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (prefs.orientation == 0) "Lock rotation" else "Unlock rotation") },
                                    leadingIcon = { Icon(Icons.Rounded.ScreenRotation, null) },
                                    onClick = {
                                        more = false
                                        val land = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                                        Store.updatePrefs { it.copy(orientation = if (it.orientation != 0) 0 else if (land) 2 else 1) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- bottom bar ----------
        AnimatedVisibility(
            ui && !pen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            var drag by remember { mutableStateOf<Float?>(null) }
            CompositionLocalProvider(LocalContentColor provides pageFg) {
                Surface(color = pageBg.copy(alpha = 0.95f), contentColor = pageFg, shadowElevation = 6.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = if (LocalConfiguration.current.screenWidthDp < 600) 8.dp else 18.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val d = drag
                            val label = if (d != null) {
                                if (engine != null) {
                                    val (c, _) = engine.fractionToChapter(d)
                                    "→ " + (structure.chapters.getOrNull(c)?.title ?: "")
                                } else "→ Page ${(d * (pageCount - 1)).roundToInt() + 1}"
                            } else if (engine != null) "Page ${engine.page + 1} of ${engine.pages} in this chapter"
                            else "Page ${imgPage + 1} of $pageCount"
                            Text(label, style = MaterialTheme.typography.bodySmall, color = pageFg.copy(alpha = 0.7f),
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { gotoOpen = true }.padding(vertical = 4.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${((d ?: progress) * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall, color = accent)
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (book0.format != BookFormat.CBZ) {
                                ZoomChip("Swipe", !scrollMode, accent, pageFg, icon = Icons.Rounded.SwapHoriz) {
                                    autoScroll = false; Store.updatePrefs { it.copy(scrollMode = false) }
                                }
                                ZoomChip("Scroll", scrollMode, accent, pageFg, icon = Icons.Rounded.SwapVert) { Store.updatePrefs { it.copy(scrollMode = true) } }
                            }
                            if (engine == null && !scrollMode) {
                                Box(Modifier.width(1.dp).height(20.dp).background(pageFg.copy(alpha = 0.15f)))
                                ZoomChip("Fit page", zoom <= 1.01f, accent, pageFg) { zoom = 1f; pan = Offset.Zero }
                                ZoomChip("Fit width", zoom > 1.01f && kotlin.math.abs(zoom - fitWidth) < 0.02f, accent, pageFg) {
                                    zoom = fitWidth.coerceIn(1f, 5f); pan = Offset(1e6f, 1e6f)
                                }
                                ZoomChip(if (zoomLock) "Zoom locked" else "Lock zoom", zoomLock, accent, pageFg,
                                    icon = if (zoomLock) Icons.Rounded.Lock else Icons.Rounded.LockOpen) { zoomLock = !zoomLock }
                                Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = pageFg.copy(alpha = 0.6f))
                            }
                            if (engine == null) {
                                Box(Modifier.width(1.dp).height(20.dp).background(pageFg.copy(alpha = 0.15f)))
                                ZoomChip("Trim margins", prefs.cropMargins, accent, pageFg, icon = Icons.Rounded.Crop) {
                                    Store.updatePrefs { it.copy(cropMargins = !it.cropMargins) }
                                }
                                ZoomChip("Right-to-left", prefs.rtlPages, accent, pageFg, icon = Icons.Rounded.FormatTextdirectionRToL) {
                                    Store.updatePrefs { it.copy(rtlPages = !it.rtlPages) }
                                }
                            }
                        }
                        Slider(
                            value = drag ?: progress,
                            onValueChange = { drag = it },
                            onValueChangeFinished = { drag?.let { seek(it) }; drag = null },
                            colors = SliderDefaults.colors(
                                thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = pageFg.copy(alpha = 0.15f)
                            )
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BarButton(Icons.AutoMirrored.Rounded.List, "Contents", pageFg) { side = SidePanel.CONTENTS; lastSide = SidePanel.CONTENTS }
                            if (engine != null) BarButton(Icons.Rounded.TextFields, "Text", pageFg) { sheet = ReaderSheet.TYPE }
                            BarButton(Icons.Rounded.Palette, "Colors", pageFg) { sheet = ReaderSheet.COLORS }
                            BarButton(Icons.Rounded.Edit, "Pen", pageFg) { pen = true; ui = false }
                            if (engine != null) BarButton(if (speaking) Icons.Rounded.Pause else Icons.Rounded.Headphones, "Listen", if (speaking) accent else pageFg) { toggleSpeak() }
                            BarButton(Icons.Rounded.Tune, "Reading", pageFg) { sheet = ReaderSheet.SETTINGS }
                        }
                    }
                }
            }
        }

        // ---------- pen toolbar ----------
        AnimatedVisibility(
            pen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 8.dp, end = 8.dp, bottom = 18.dp)
        ) {
            PenToolbar(
                prefs = prefs, tool = penTool, onTool = { penTool = it },
                canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(),
                onUndo = {
                    undo.removeLastOrNull()?.let { s -> editAnn { a -> a.copy(ink = a.ink.filter { it.id != s.id }) }; redo.add(s) }
                },
                onRedo = {
                    redo.removeLastOrNull()?.let { s -> editAnn { it.copy(ink = it.ink + s) }; undo.add(s) }
                },
                onClearPage = {
                    val ids = if (engine != null) pageStrokes.map { it.id }.toSet() else ann.ink.filter { it.page == imgPage }.map { it.id }.toSet()
                    editAnn { a -> a.copy(ink = a.ink.filter { it.id !in ids }) }
                },
                onDone = { pen = false }
            )
        }

        // ---------- side panel ----------
        AnimatedVisibility(side != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { side = null }
            )
        }
        val panelW = min(LocalConfiguration.current.screenWidthDp * 0.9f, 430f).dp
        AnimatedVisibility(
            side != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                Modifier.fillMaxHeight().width(panelW), color = pageBg, contentColor = pageFg,
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp), shadowElevation = 16.dp
            ) {
                CompositionLocalProvider(LocalContentColor provides pageFg) {
                    Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                        when (side ?: lastSide) {
                            SidePanel.CONTENTS -> ContentsPanel(
                                book = book0, structure = structure, ann = ann, engine = engine, source = source,
                                currentChapter = chapter, currentPage = imgPage, fg = pageFg, accent = accent,
                                onGo = { c, off, p -> goLoc(c, off, p) },
                                onAnchor = { c, a -> markJump(); engine?.goAnchor(c, a); side = null },
                                onDeleteBookmark = { id -> editAnn { a -> a.copy(bookmarks = a.bookmarks.filter { it.id != id }) } },
                                onEditHighlight = { editHl = it },
                                onEditNote = { pageNote = it },
                            )
                            SidePanel.SEARCH -> if (engine != null) SearchPanel(
                                dir = dir, structure = structure, fg = pageFg, accent = accent,
                                onGo = { c, q, k -> markJump(); engine.find(c, q, k); side = null }
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- sheets ----------
    sheet?.let { s ->
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            containerColor = pageBg, contentColor = pageFg, scrimColor = Color.Black.copy(alpha = 0.12f),
        ) {
            CompositionLocalProvider(LocalContentColor provides pageFg) {
                when (s) {
                    ReaderSheet.TYPE -> TypographySheet(prefs)
                    ReaderSheet.COLORS -> ColorsSheet(prefs, isPdf = book0.format == BookFormat.PDF)
                    ReaderSheet.SETTINGS -> ReadingSettingsSheet(prefs, reflow = engine != null, comic = book0.format == BookFormat.CBZ)
                }
            }
        }
    }

    editHl?.let { h ->
        HighlightSheet(
            h = h, bg = pageBg, fg = pageFg, accent = accent,
            onDismiss = { editHl = null },
            onSave = { nh ->
                editAnn { a -> a.copy(highlights = a.highlights.map { if (it.id == nh.id) nh else it }) }
                if (nh.chapter == engine?.chapter) engine?.updateHl(nh)
                editHl = null
            },
            onDelete = {
                editAnn { a -> a.copy(highlights = a.highlights.filter { it.id != h.id }) }
                engine?.removeHl(h.id)
                editHl = null
            },
            onCopy = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("quote", h.text))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            },
            onShare = {
                val t = "“${h.text.trim()}”\n— ${book0.title}" + if (h.note.isNotBlank()) "\n\n${h.note}" else ""
                com.clockedbit.hearth.AppLock.skipNextLock = true
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share quote"))
            }
        )
    }

    noteSel?.let { (s, e, text) ->
        NoteDialog(
            title = "Add a note", quote = text, initial = "",
            onDismiss = { noteSel = null },
            onSave = { note ->
                val eng = engine
                if (eng != null) {
                    val h = Highlight(newId(), eng.chapter, s, e, text, prefs.hlColor, note, System.currentTimeMillis())
                    editAnn { it.copy(highlights = it.highlights + h) }
                    eng.addHl(h)
                }
                noteSel = null
            },
            onDelete = null
        )
    }

    pageNote?.let { n ->
        NoteDialog(
            title = if (n.id.isEmpty()) "Note on this page" else "Page note", quote = null, initial = n.text,
            onDismiss = { pageNote = null },
            onSave = { t ->
                if (t.isNotBlank()) {
                    if (n.id.isEmpty()) editAnn { it.copy(notes = it.notes + n.copy(id = newId(), text = t, created = System.currentTimeMillis())) }
                    else editAnn { a -> a.copy(notes = a.notes.map { if (it.id == n.id) it.copy(text = t) else it }) }
                }
                pageNote = null
            },
            onDelete = if (n.id.isEmpty()) null else ({
                editAnn { a -> a.copy(notes = a.notes.filter { it.id != n.id }) }
                pageNote = null
            })
        )
    }

    footnote?.let { t ->
        AlertDialog(
            onDismissRequest = { footnote = null },
            containerColor = pageBg, textContentColor = pageFg, titleContentColor = accent,
            title = { Text("Note") },
            text = { Text(t, modifier = Modifier.heightIn(max = 360.dp).verticalScrollCompat()) },
            confirmButton = { TextButton({ footnote = null }) { Text("Close", color = accent) } }
        )
    }

    imageFile?.let { f -> ImageViewer(f) { imageFile = null } }

    if (gotoOpen) {
        var v by remember { mutableStateOf("") }
        val isPages = engine == null
        AlertDialog(
            onDismissRequest = { gotoOpen = false },
            containerColor = pageBg, titleContentColor = pageFg, textContentColor = pageFg,
            title = { Text(if (isPages) "Go to page" else "Go to position") },
            text = {
                OutlinedTextField(
                    v, { v = it.filter { ch -> ch.isDigit() }.take(5) }, singleLine = true,
                    label = { Text(if (isPages) "Page (1–$pageCount)" else "Percent (0–100)") },
                    suffix = { if (!isPages) Text("%") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton({
                    val n = v.toIntOrNull()
                    if (n != null) {
                        if (isPages) { markJump(); goPage(n - 1) } else seek((n.coerceIn(0, 100)) / 100f)
                    }
                    gotoOpen = false
                }) { Text("Go", color = accent) }
            },
            dismissButton = { TextButton({ gotoOpen = false }) { Text("Cancel", color = pageFg) } }
        )
    }
}

@Composable
private fun RowScope.BarButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    // Equal share of the bar, so six buttons fit on a phone as well as a tablet.
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = tint.copy(alpha = 0.8f), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

@Composable
fun Modifier.verticalScrollCompat(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

/* ---------------- PDF / comic pages ---------------- */

@Composable
private fun ZoomChip(label: String, selected: Boolean, accent: Color, fg: Color, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.2f) else fg.copy(alpha = 0.07f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) accent else fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 12.sp, color = if (selected) accent else fg)
    }
}

@Composable
private fun PagesView(
    source: PageSource,
    pagerState: PagerState,
    listState: LazyListState,
    scrollMode: Boolean,
    recolor: ColorFilter?,
    ink: List<InkStroke>,
    pen: Boolean,
    penTool: Int,
    penColor: Int,
    penWidth: Float,
    palmMode: Int,
    onStroke: (Int, Int, Float, FloatArray, Int) -> Unit,
    onErase: (InkStroke) -> Unit,
    onTapZone: (Int) -> Unit,
    onDoubleTap: () -> Unit,
    zoom: Float,
    pan: Offset,
    zoomLock: Boolean,
    onZoomPan: (Float, Offset) -> Unit,
    onEdgeTurn: (Int) -> Unit,
    onFitWidth: (Float) -> Unit,
    crop: Boolean,
    gap: Int,
    rtl: Boolean,
) {
    if (scrollMode) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wPx = constraints.maxWidth
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }, onTap = { onTapZone(0) }) },
                verticalArrangement = Arrangement.spacedBy(gap.dp)
            ) {
                items(source.count) { p ->
                    val bmp by produceState<Bitmap?>(null, p, wPx, crop) { value = source.render(p, wPx, crop) }
                    val b = bmp
                    val aspect = if (b != null) b.width.toFloat() / b.height else 0.707f
                    Box(Modifier.fillMaxWidth().aspectRatio(aspect)) {
                        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = recolor)
                        val strokes = remember(ink, p) { ink.filter { it.page == p } }
                        if (pen || strokes.isNotEmpty()) InkLayer(
                            strokes, pen, penTool, penColor, penWidth, palmMode, passFingers = true,
                            onStroke = { t, c, w, pts -> onStroke(t, c, w, pts, p) }, onErase = onErase, onSwipe = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    } else {
        HorizontalPager(
            state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = zoom <= 1.01f, key = { it },
            pageSpacing = gap.dp, reverseLayout = rtl
        ) { p ->
            ZoomPage(
                source, p, recolor, ink, pen, penTool, penColor, penWidth, palmMode, onStroke, onErase, onTapZone, onDoubleTap,
                zoom = zoom, pan = pan, zoomLock = zoomLock, isCurrent = p == pagerState.currentPage,
                onZoomPan = onZoomPan, onEdgeTurn = onEdgeTurn, onFitWidth = onFitWidth, crop = crop
            )
        }
    }
}

@Composable
private fun ZoomPage(
    source: PageSource, p: Int, recolor: ColorFilter?, ink: List<InkStroke>, pen: Boolean, penTool: Int, penColor: Int,
    penWidth: Float, palmMode: Int,
    onStroke: (Int, Int, Float, FloatArray, Int) -> Unit, onErase: (InkStroke) -> Unit,
    onTapZone: (Int) -> Unit, onDoubleTap: () -> Unit,
    zoom: Float, pan: Offset, zoomLock: Boolean, isCurrent: Boolean,
    onZoomPan: (Float, Offset) -> Unit, onEdgeTurn: (Int) -> Unit, onFitWidth: (Float) -> Unit,
    crop: Boolean,
) {
    val density = LocalDensity.current
    val edgePx = with(density) { 90.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val bmp by produceState<Bitmap?>(null, p, wPx, crop) { value = source.render(p, (wPx * 1.6f).toInt(), crop) }
        val b = bmp
        val aspect = if (b != null) b.width.toFloat() / b.height else 0.707f
        val fw = if (aspect > wPx / hPx) wPx else hPx * aspect
        val fh = if (aspect > wPx / hPx) wPx / aspect else hPx
        LaunchedEffect(fw, isCurrent, b != null) { if (isCurrent && b != null) onFitWidth(wPx / fw) }
        fun maxX(z: Float) = max(0f, (fw * z - wPx) / 2f)
        fun maxY(z: Float) = max(0f, (fh * z - hPx) / 2f)
        val ox = pan.x.coerceIn(-maxX(zoom), maxX(zoom))
        val oy = pan.y.coerceIn(-maxY(zoom), maxY(zoom))

        val zoomS = rememberUpdatedState(zoom)
        val panS = rememberUpdatedState(pan)
        val lockS = rememberUpdatedState(zoomLock)
        val onZoomPanS = rememberUpdatedState(onZoomPan)
        val onEdgeS = rememberUpdatedState(onEdgeTurn)

        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onDoubleTap() },
                        onTap = { pos ->
                            val f = pos.x / size.width
                            onTapZone(if (f < 0.25f) -1 else if (f > 0.75f) 1 else 0)
                        }
                    )
                }
                .pointerInput(fw, fh, wPx, hPx) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var over = 0f
                        var pinched = false
                        do {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            val z = zoomS.value
                            if (pressed >= 2 && !lockS.value) {
                                pinched = true
                                val nz = (z * ev.calculateZoom()).coerceIn(1f, 5f)
                                val pn = ev.calculatePan()
                                val cx = panS.value.x.coerceIn(-maxX(nz), maxX(nz)) + pn.x
                                val cy = panS.value.y.coerceIn(-maxY(nz), maxY(nz)) + pn.y
                                onZoomPanS.value(if (nz < 1.03f) 1f else nz, Offset(cx.coerceIn(-maxX(nz), maxX(nz)), cy.coerceIn(-maxY(nz), maxY(nz))))
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (z > 1.01f && pressed >= 1) {
                                val pn = ev.calculatePan()
                                val mx = maxX(z); val my = maxY(z)
                                val nx = panS.value.x.coerceIn(-mx, mx) + pn.x
                                val ny = panS.value.y.coerceIn(-my, my) + pn.y
                                if (nx > mx) over += nx - mx else if (nx < -mx) over += nx + mx
                                onZoomPanS.value(z, Offset(nx.coerceIn(-mx, mx), ny.coerceIn(-my, my)))
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        // drag past the edge of a zoomed page = turn the page, keeping the zoom
                        if (!pinched && over < -edgePx) onEdgeS.value(1)
                        else if (!pinched && over > edgePx) onEdgeS.value(-1)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (b == null) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = LocalContentColor.current.copy(alpha = 0.4f))
            } else {
                Box(
                    Modifier.size(with(density) { fw.toDp() }, with(density) { fh.toDp() })
                        .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = ox; translationY = oy }
                ) {
                    Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = recolor)
                    val strokes = remember(ink, p) { ink.filter { it.page == p } }
                    if (pen || strokes.isNotEmpty()) InkLayer(
                        strokes, pen, penTool, penColor, penWidth, palmMode, passFingers = true,
                        onStroke = { t, c, w, pts -> onStroke(t, c, w, pts, p) }, onErase = onErase, onSwipe = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/* ---------------- image viewer ---------------- */

@Composable
private fun ImageViewer(file: File, onDismiss: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, file) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }, onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            bmp?.let {
                Image(
                    it.asImageBitmap(), null,
                    Modifier.fillMaxSize().padding(16.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                    contentScale = ContentScale.Fit
                )
            }
            IconButton(onDismiss, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
                Icon(Icons.Rounded.Close, "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun animatedAlpha(visible: Boolean): Float = animateFloatAsState(if (visible) 1f else 0f, label = "a").value

@Composable
private fun rememberClockBattery(): Pair<String, Int> {
    val ctx = LocalContext.current
    val fmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    var clock by remember { mutableStateOf(fmt.format(java.util.Date())) }
    var battery by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = fmt.format(java.util.Date())
            battery = runCatching {
                (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }.getOrDefault(-1)
            delay(20_000)
        }
    }
    return clock to battery
}
