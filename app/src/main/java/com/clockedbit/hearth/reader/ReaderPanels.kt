package com.clockedbit.hearth.reader

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clockedbit.hearth.data.*
import com.clockedbit.hearth.ui.ColorPickerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/* ============================== side panel: contents ============================== */

@Composable
fun ContentsPanel(
    book: Book, structure: BookStructure, ann: Annotations, engine: ReflowEngine?, source: PageSource?,
    currentChapter: Int, currentPage: Int, fg: Color, accent: Color,
    onGo: (Int, Int, Int) -> Unit,
    onAnchor: (Int, String?) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onEditHighlight: (Highlight) -> Unit,
    onEditNote: (PageNote) -> Unit,
) {
    val tabs = if (engine != null) listOf("Contents", "Bookmarks", "Highlights", "Notes") else listOf("Pages", "Bookmarks", "Notes")
    var tab by remember { mutableIntStateOf(0) }
    Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 8.dp))
    ScrollableTabRow(
        selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = fg, edgePadding = 14.dp,
        indicator = { pos ->
            if (tab < pos.size) with(TabRowDefaults) { SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = accent) }
        },
        divider = {}
    ) {
        tabs.forEachIndexed { i, t ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) },
                selectedContentColor = accent, unselectedContentColor = fg.copy(alpha = 0.6f))
        }
    }
    val dim = fg.copy(alpha = 0.55f)
    fun where(c: Int, p: Int): String =
        if (engine == null) "Page ${p + 1}" else structure.chapters.getOrNull(c)?.title ?: "Chapter ${c + 1}"

    when (tabs[tab]) {
        "Contents" -> {
            val entries = structure.toc.ifEmpty { structure.chapters.mapIndexed { i, c -> TocEntry(c.title, i, null, 0) } }
            val currentIdx = entries.indexOfLast { it.chapter <= currentChapter }.coerceAtLeast(0)
            val st = rememberLazyListState(maxOf(0, currentIdx - 3))
            LazyColumn(state = st, contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(entries) { i, e ->
                    val cur = i == currentIdx
                    Row(
                        Modifier.fillMaxWidth().clickable { onAnchor(e.chapter, e.anchor) }
                            .background(if (cur) accent.copy(alpha = 0.12f) else Color.Transparent)
                            .padding(start = (22 + e.depth * 16).dp, end = 18.dp, top = 13.dp, bottom = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (cur) Box(Modifier.size(6.dp).clip(CircleShape).background(accent)) else Spacer(Modifier.size(6.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(e.title, color = if (cur) accent else fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (e.depth == 0) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
        "Pages" -> {
            if (source != null) {
                LazyVerticalGrid(
                    GridCells.Adaptive(104.dp), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(source.count) { p ->
                        val bmp by produceState<Bitmap?>(null, p) { value = source.render(p, 220) }
                        val marked = ann.bookmarks.any { it.page == p }
                        val inked = ann.ink.any { it.page == p }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onGo(0, -1, p) }) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp))
                                    .background(fg.copy(alpha = 0.08f))
                                    .border(if (p == currentPage) 2.dp else 0.dp, if (p == currentPage) accent else Color.Transparent, RoundedCornerShape(8.dp))
                            ) {
                                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                                    if (inked) Icon(Icons.Rounded.Edit, null, tint = accent, modifier = Modifier.size(14.dp))
                                    if (marked) Icon(Icons.Rounded.Bookmark, null, tint = accent, modifier = Modifier.size(14.dp))
                                }
                            }
                            Text("${p + 1}", fontSize = 11.sp, color = if (p == currentPage) accent else dim)
                        }
                    }
                }
            }
        }
        "Bookmarks" -> {
            if (ann.bookmarks.isEmpty()) EmptyHint("No bookmarks yet.\nTap the ribbon in the top bar to save your place.", dim)
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(ann.bookmarks.sortedWith(compareBy({ it.chapter }, { it.offset }, { it.page })), key = { it.id }) { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onGo(b.chapter, b.offset, b.page) }.padding(start = 22.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Bookmark, null, tint = accent)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(where(b.chapter, b.page), style = MaterialTheme.typography.labelSmall, color = accent)
                            Text(b.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis, color = fg)
                        }
                        IconButton({ onDeleteBookmark(b.id) }) { Icon(Icons.Rounded.Close, "Delete", tint = dim) }
                    }
                }
            }
        }
        "Highlights" -> {
            if (ann.highlights.isEmpty()) EmptyHint("No highlights yet.\nLong-press text, then choose Highlight or Note.", dim)
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(ann.highlights.sortedWith(compareBy({ it.chapter }, { it.start })), key = { it.id }) { h ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onGo(h.chapter, h.start, -1) }.padding(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        Box(Modifier.width(4.dp).heightIn(min = 40.dp).clip(RoundedCornerShape(2.dp)).background(Color(h.color or (0xFF shl 24))))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(where(h.chapter, -1), style = MaterialTheme.typography.labelSmall, color = accent)
                            Text(h.text.trim(), maxLines = 4, overflow = TextOverflow.Ellipsis, color = fg, fontFamily = FontFamily.Serif)
                            if (h.note.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("✎ " + h.note, color = dim, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            }
                        }
                        IconButton({ onEditHighlight(h) }) { Icon(Icons.Rounded.Edit, "Edit", tint = dim) }
                    }
                }
            }
        }
        "Notes" -> {
            if (ann.notes.isEmpty()) EmptyHint("No page notes yet.\nUse ⋮ → Add note to this page.", dim)
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(ann.notes.sortedWith(compareBy({ it.chapter }, { it.offset }, { it.page })), key = { it.id }) { n ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onGo(n.chapter, n.offset, n.page) }.padding(start = 22.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.StickyNote2, null, tint = accent)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(where(n.chapter, n.page), style = MaterialTheme.typography.labelSmall, color = accent)
                            Text(n.text, maxLines = 3, overflow = TextOverflow.Ellipsis, color = fg)
                        }
                        IconButton({ onEditNote(n) }) { Icon(Icons.Rounded.Edit, "Edit", tint = dim) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String, color: Color) {
    Text(text, color = color, modifier = Modifier.padding(28.dp), lineHeight = 22.sp)
}

/* ============================== side panel: search ============================== */

data class SearchHit(val chapter: Int, val k: Int, val snippet: String, val at: Int)

private val entityRe = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
private fun plainText(html: String): String {
    var s = html
    val bodyStart = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(s)
    if (bodyStart != null) s = s.substring(bodyStart.range.last + 1)
    s = s.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    s = s.replace(Regex("<[^>]*>"), "")
    return entityRe.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> when (e) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                "mdash" -> "—"; "ndash" -> "–"; "hellip" -> "…"; "rsquo" -> "’"; "lsquo" -> "‘"; "rdquo" -> "”"; "ldquo" -> "“"
                else -> m.value
            }
        }
    }
}

suspend fun searchBook(dir: File, s: BookStructure, q: String): List<SearchHit> = withContext(Dispatchers.IO) {
    val out = ArrayList<SearchHit>()
    val ql = q.lowercase()
    for ((ci, ch) in s.chapters.withIndex()) {
        if (out.size >= 400) break
        val raw = runCatching { File(dir, ch.href).readBytes() }.getOrNull() ?: continue
        val text = plainText(String(raw, ReaderCss.charsetOf(raw)))
        val tl = text.lowercase()
        var idx = tl.indexOf(ql)
        var k = 0
        while (idx >= 0 && out.size < 400) {
            val a = maxOf(0, idx - 50); val b = minOf(text.length, idx + ql.length + 70)
            val snip = (if (a > 0) "…" else "") + text.substring(a, b).replace(Regex("\\s+"), " ").trim() + (if (b < text.length) "…" else "")
            out.add(SearchHit(ci, k, snip, idx))
            k++
            idx = tl.indexOf(ql, idx + 1)
        }
    }
    out
}

@Composable
fun SearchPanel(dir: File, structure: BookStructure, fg: Color, accent: Color, onGo: (Int, String, Int) -> Unit) {
    var q by remember { mutableStateOf(SearchMemory.query) }
    var hits by remember { mutableStateOf(SearchMemory.hits) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run() {
        if (q.trim().length < 2) return
        busy = true
        scope.launch {
            val r = searchBook(dir, structure, q.trim())
            hits = r; busy = false
            SearchMemory.query = q; SearchMemory.hits = r
        }
    }
    Text("Search", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 10.dp))
    OutlinedTextField(
        q, { q = it }, singleLine = true, placeholder = { Text("Word or phrase") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { run() }),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, cursorColor = accent, focusedTextColor = fg, unfocusedTextColor = fg),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
    )
    if (hits.isNotEmpty()) Text(
        "${hits.size}${if (hits.size >= 400) "+" else ""} results", style = MaterialTheme.typography.labelSmall,
        color = fg.copy(alpha = 0.6f), modifier = Modifier.padding(start = 22.dp, top = 10.dp)
    )
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(hits) { h ->
            Column(
                Modifier.fillMaxWidth().clickable { onGo(h.chapter, SearchMemory.query.trim(), h.k) }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text(structure.chapters.getOrNull(h.chapter)?.title ?: "", style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1)
                Text(h.snippet, color = fg, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            }
        }
    }
}

object SearchMemory {
    var query = ""
    var hits: List<SearchHit> = emptyList()
}

/* ============================== pen toolbar ============================== */

@Composable
fun PenToolbar(
    prefs: ReaderPrefs, tool: Int, onTool: (Int) -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit,
    onClearPage: () -> Unit, onDone: () -> Unit,
) {
    var showWidth by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val surface = Color(0xFF221B18)
    val on = Color(0xFFF2E6D6)
    val accent = Color(0xFFFFA45C)
    Surface(shape = RoundedCornerShape(28.dp), color = surface.copy(alpha = 0.97f), shadowElevation = 14.dp, contentColor = on) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (showWidth && tool != InkTool.ERASER) {
                val isMarker = tool == InkTool.MARKER
                val v = if (isMarker) prefs.markerWidth else prefs.penWidth
                Row(Modifier.width(minOf(320, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 56).dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Size", fontSize = 12.sp, color = on.copy(alpha = 0.7f))
                    Slider(
                        v, { nv -> Store.updatePrefs { if (isMarker) it.copy(markerWidth = nv) else it.copy(penWidth = nv) } },
                        valueRange = if (isMarker) 8f..44f else 1f..14f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                    )
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size((v.coerceAtMost(26f)).dp).clip(CircleShape).background(
                            Color(if (isMarker) prefs.markerColor else prefs.penColor).copy(alpha = if (isMarker) 0.5f else 1f)))
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Rounded.Edit, "Pen", tool == InkTool.PEN, accent) { if (tool == InkTool.PEN) showWidth = !showWidth else onTool(InkTool.PEN) }
                ToolButton(Icons.Rounded.BorderColor, "Marker", tool == InkTool.MARKER, accent) { if (tool == InkTool.MARKER) showWidth = !showWidth else onTool(InkTool.MARKER) }
                ToolButton(Icons.Rounded.CleaningServices, "Eraser", tool == InkTool.ERASER, accent) { onTool(InkTool.ERASER) }
                VerticalDivider(Modifier.height(30.dp).padding(horizontal = 6.dp), color = on.copy(alpha = 0.15f))
                if (tool != InkTool.ERASER) {
                    val cur = if (tool == InkTool.MARKER) prefs.markerColor else prefs.penColor
                    Palettes.pen.forEach { c ->
                        val sel = (c and 0xFFFFFF) == (cur and 0xFFFFFF)
                        Box(
                            Modifier.padding(3.dp).size(if (sel) 30.dp else 24.dp).clip(CircleShape).background(Color(c))
                                .border(if (sel) 2.5.dp else 1.dp, if (sel) Color.White else Color.White.copy(alpha = 0.2f), CircleShape)
                                .clickable {
                                    Store.updatePrefs { if (tool == InkTool.MARKER) it.copy(markerColor = c) else it.copy(penColor = c) }
                                }
                        )
                    }
                    IconButton({ showWidth = !showWidth }) { Icon(Icons.Rounded.LineWeight, "Size") }
                    VerticalDivider(Modifier.height(30.dp).padding(horizontal = 6.dp), color = on.copy(alpha = 0.15f))
                }
                IconButton(onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
                IconButton(onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
                IconButton({ confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear page") }
                TextButton({ Store.updatePrefs { it.copy(palmMode = (it.palmMode + 1) % 3) } }) {
                    Icon(Icons.Rounded.PanTool, null, modifier = Modifier.size(16.dp), tint = on.copy(alpha = 0.8f))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (prefs.palmMode) { 0 -> "Palm: Auto"; 1 -> "Stylus only"; else -> "Fingers draw" },
                        color = on.copy(alpha = 0.8f), fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(onDone, colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Color(0xFF2A1607))) {
                    Icon(Icons.Rounded.Check, "Done")
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear drawings on this page?") },
        confirmButton = { TextButton({ onClearPage(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } }
    )
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) accent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (selected) accent else LocalContentColor.current)
        Text(label, fontSize = 10.sp, color = if (selected) accent else LocalContentColor.current.copy(alpha = 0.7f))
    }
}

/* ============================== typography ============================== */

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current.copy(alpha = 0.55f), modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, accent: Color, steps: Int = 0, onChange: (Float) -> Unit) {
    Label(label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value, onChange, valueRange = range, steps = steps, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = LocalContentColor.current.copy(alpha = 0.15f))
        )
        Text(display, modifier = Modifier.width(64.dp).padding(start = 12.dp), fontSize = 13.sp)
    }
}

@Composable
private fun SwitchRow(label: String, sub: String?, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = 0.55f))
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White))
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, accent: Color, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LocalContentColor.current.copy(alpha = 0.07f)).padding(4.dp)
    ) {
        options.forEachIndexed { i, s ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (i == selected) accent else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(s, fontSize = 13.sp, color = if (i == selected) Color(0xFF1E1410) else LocalContentColor.current)
            }
        }
    }
}

@Composable
fun TypographySheet(prefs: ReaderPrefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = Color(prefs.accent)
    var fonts by remember { mutableStateOf(Store.customFonts()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val f = Importer.importFont(ctx, uri)
                fonts = Store.customFonts()
                Store.updatePrefs { it.copy(fontFamily = "custom:${f.name}") }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, e.message ?: "Couldn't add font", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        Text("Text", style = MaterialTheme.typography.titleLarge)
        Label("Font")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val builtIn = listOf(
                "publisher" to ("Original" to FontFamily.Default), "serif" to ("Serif" to FontFamily.Serif),
                "sans" to ("Sans" to FontFamily.SansSerif), "slab" to ("Slab" to FontFamily.Serif),
                "mono" to ("Mono" to FontFamily.Monospace), "hand" to ("Hand" to FontFamily.Cursive),
            )
            builtIn.forEach { (key, v) -> FontChip(v.first, v.second, prefs.fontFamily == key, accent) { Store.updatePrefs { it.copy(fontFamily = key) } } }
            fonts.forEach { f ->
                val fam = remember(f.path) { runCatching { FontFamily(Typeface.createFromFile(f)) }.getOrDefault(FontFamily.Default) }
                val key = "custom:${f.name}"
                FontChip(f.nameWithoutExtension.take(14), fam, prefs.fontFamily == key, accent) { Store.updatePrefs { it.copy(fontFamily = key) } }
            }
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, LocalContentColor.current.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                    .clickable { picker.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.Add, null)
                Text("Add font", fontSize = 11.sp)
            }
        }
        Label("Size")
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton({ Store.updatePrefs { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(10)) } }) { Text("A", fontSize = 13.sp) }
            Slider(
                prefs.fontSize.toFloat(), { v -> Store.updatePrefs { it.copy(fontSize = v.roundToInt()) } }, valueRange = 10f..48f,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = LocalContentColor.current.copy(alpha = 0.15f))
            )
            FilledTonalIconButton({ Store.updatePrefs { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(48)) } }) { Text("A", fontSize = 21.sp) }
            Text("${prefs.fontSize}", modifier = Modifier.width(36.dp).padding(start = 10.dp))
        }
        SliderRow("Line spacing", prefs.lineHeight, 1.0f..2.6f, "%.1f×".format(prefs.lineHeight), accent) { v -> Store.updatePrefs { it.copy(lineHeight = (v * 10).roundToInt() / 10f) } }
        SliderRow("Margins", prefs.margin.toFloat(), 0f..120f, "${prefs.margin}", accent) { v -> Store.updatePrefs { it.copy(margin = v.roundToInt()) } }
        SliderRow("Paragraph spacing", prefs.paraSpacing, 0f..2f, "%.1f".format(prefs.paraSpacing), accent) { v -> Store.updatePrefs { it.copy(paraSpacing = (v * 10).roundToInt() / 10f) } }
        SliderRow("Letter spacing", prefs.letterSpacing, -0.03f..0.15f, "%.2f".format(prefs.letterSpacing), accent) { v -> Store.updatePrefs { it.copy(letterSpacing = (v * 100).roundToInt() / 100f) } }
        SliderRow("Weight", prefs.fontWeight.toFloat(), 300f..700f, "${prefs.fontWeight}", accent, steps = 3) { v -> Store.updatePrefs { it.copy(fontWeight = ((v / 100).roundToInt() * 100)) } }
        Spacer(Modifier.height(8.dp))
        SwitchRow("Justify text", "Straight edges on both sides", prefs.justify, accent) { v -> Store.updatePrefs { it.copy(justify = v) } }
        SwitchRow("Hyphenation", "Break long words at line ends", prefs.hyphenate, accent) { v -> Store.updatePrefs { it.copy(hyphenate = v) } }
        SwitchRow("Use my style everywhere", "Override the book's own fonts, colors and spacing", prefs.overrideStyles, accent) { v -> Store.updatePrefs { it.copy(overrideStyles = v) } }
        TextButton({
            val d = ReaderPrefs()
            Store.updatePrefs { it.copy(fontFamily = d.fontFamily, fontSize = d.fontSize, lineHeight = d.lineHeight, margin = d.margin,
                paraSpacing = d.paraSpacing, letterSpacing = d.letterSpacing, fontWeight = d.fontWeight, justify = d.justify,
                hyphenate = d.hyphenate, overrideStyles = d.overrideStyles) }
        }) { Text("Reset text settings", color = accent) }
    }
}

@Composable
private fun FontChip(label: String, family: FontFamily, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else LocalContentColor.current.copy(alpha = 0.06f))
            .border(if (selected) 1.5.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Aa", fontFamily = family, fontSize = 22.sp, color = if (selected) accent else LocalContentColor.current)
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

/* ============================== colors ============================== */

@Composable
fun ColorsSheet(prefs: ReaderPrefs, isPdf: Boolean) {
    val accent = Color(prefs.accent)
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        Text("Colors & light", style = MaterialTheme.typography.titleLarge)
        Label("Themes")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Themes.presets.forEach { t ->
                val sel = prefs.themeId == t.id
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                    Store.updatePrefs { it.copy(themeId = t.id, bg = t.bg, fg = t.fg, accent = t.accent) }
                }) {
                    Box(
                        Modifier.size(width = 64.dp, height = 84.dp).clip(RoundedCornerShape(12.dp)).background(Color(t.bg))
                            .border(if (sel) 2.5.dp else 1.dp, if (sel) Color(t.accent) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            Text("Aa", color = Color(t.fg), fontFamily = FontFamily.Serif, fontSize = 18.sp)
                            Spacer(Modifier.height(6.dp))
                            repeat(3) { i ->
                                Box(Modifier.padding(vertical = 2.dp).fillMaxWidth(if (i == 2) 0.6f else 1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(t.fg).copy(alpha = 0.4f)))
                            }
                        }
                        Box(Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape).background(Color(t.accent)))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(t.name, fontSize = 11.sp, color = if (sel) Color(t.accent) else LocalContentColor.current)
                }
            }
        }
        Label("Make it yours")
        ColorPickerRow("Page", prefs.bg, { c -> Store.updatePrefs { it.copy(bg = c, themeId = "custom") } })
        ColorPickerRow("Text", prefs.fg, { c -> Store.updatePrefs { it.copy(fg = c, themeId = "custom") } })
        ColorPickerRow("Accent & links", prefs.accent, { c -> Store.updatePrefs { it.copy(accent = c, themeId = "custom") } })
        Label("Highlight color")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Palettes.highlight.forEach { c ->
                val sel = c == prefs.hlColor
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c or (0xFF shl 24)).copy(alpha = 0.85f))
                        .border(if (sel) 3.dp else 0.dp, if (sel) LocalContentColor.current else Color.Transparent, CircleShape)
                        .clickable { Store.updatePrefs { it.copy(hlColor = c) } }
                )
            }
        }
        Label("Light")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LightMode, null, modifier = Modifier.size(20.dp))
            Slider(
                if (prefs.brightness < 0f) 0.5f else prefs.brightness,
                { v -> Store.updatePrefs { it.copy(brightness = v.coerceAtLeast(0.02f)) } },
                enabled = prefs.brightness >= 0f, valueRange = 0.02f..1f, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
            )
            FilterChip(prefs.brightness < 0f, { Store.updatePrefs { it.copy(brightness = if (it.brightness < 0f) 0.5f else -1f) } }, label = { Text("System") })
        }
        SliderRow("Night warmth", prefs.warmth, 0f..1f, "${(prefs.warmth * 100).roundToInt()}%", accent) { v -> Store.updatePrefs { it.copy(warmth = v) } }
        if (isPdf) SwitchRow("Recolor PDF pages", "Paint pages with your theme colors (turn off for photos)", prefs.recolorPages, accent) { v ->
            Store.updatePrefs { it.copy(recolorPages = v) }
        }
    }
}

/* ============================== reading settings ============================== */

@Composable
fun ReadingSettingsSheet(prefs: ReaderPrefs, reflow: Boolean, comic: Boolean = false) {
    val accent = Color(prefs.accent)
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        Text("Reading", style = MaterialTheme.typography.titleLarge)
        if (!comic) Label("Layout")
        if (!comic) Segmented(listOf("Pages", "Scroll"), if (prefs.scrollMode) 1 else 0, accent) { i -> Store.updatePrefs { it.copy(scrollMode = i == 1) } }
        Spacer(Modifier.height(6.dp))
        if (reflow) SwitchRow("Two pages in landscape", "Book-style spread on wide screens (tablets held sideways)", prefs.twoPage, accent) { v -> Store.updatePrefs { it.copy(twoPage = v) } }
        SwitchRow("Page-turn animation", null, prefs.pageAnim, accent) { v -> Store.updatePrefs { it.copy(pageAnim = v) } }
        SwitchRow("Tap edges to turn pages", "Left / right side of the screen", prefs.tapZones, accent) { v -> Store.updatePrefs { it.copy(tapZones = v) } }
        SwitchRow("Volume keys turn pages", null, prefs.volumeKeys, accent) { v -> Store.updatePrefs { it.copy(volumeKeys = v) } }
        SwitchRow("Keep screen awake", null, prefs.keepScreenOn, accent) { v -> Store.updatePrefs { it.copy(keepScreenOn = v) } }
        if (!reflow) {
            Label("Pages")
            SliderRow("Gap between pages", prefs.pageGap.toFloat(), 0f..48f, "${prefs.pageGap} dp", accent) { v ->
                Store.updatePrefs { it.copy(pageGap = v.roundToInt()) }
            }
            SwitchRow("Trim page margins", "Cut empty white borders so text looks bigger", prefs.cropMargins, accent) { v ->
                Store.updatePrefs { it.copy(cropMargins = v) }
            }
            SwitchRow("Right-to-left pages", "For Arabic books and manga", prefs.rtlPages, accent) { v ->
                Store.updatePrefs { it.copy(rtlPages = v) }
            }
        }
        SliderRow("Auto-scroll speed", prefs.autoScrollSpeed, 5f..300f, "${prefs.autoScrollSpeed.roundToInt()}", accent) { v ->
            Store.updatePrefs { it.copy(autoScrollSpeed = v.roundToInt().toFloat()) }
        }
        Label("Screen")
        SwitchRow("Status line", "Page, progress and time at the bottom", prefs.showStatus, accent) { v -> Store.updatePrefs { it.copy(showStatus = v) } }
        if (prefs.showStatus) SwitchRow("Clock & battery", null, prefs.showClock, accent) { v -> Store.updatePrefs { it.copy(showClock = v) } }
        Label("Rotation")
        Segmented(listOf("Auto", "Portrait", "Landscape"), prefs.orientation, accent) { i -> Store.updatePrefs { it.copy(orientation = i) } }
        Label("Pen palm rejection")
        Segmented(listOf("Auto", "Stylus only", "Off"), prefs.palmMode, accent) { i -> Store.updatePrefs { it.copy(palmMode = i) } }
        if (reflow) {
            SliderRow("Read-aloud speed", prefs.ttsRate, 0.5f..2f, "%.2f×".format(prefs.ttsRate), accent) { v -> Store.updatePrefs { it.copy(ttsRate = (v * 20).roundToInt() / 20f) } }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Tip: double-tap anywhere to show or hide the bars. Long-press text to highlight or add a note.",
            fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = 0.55f)
        )
    }
}

/* ============================== highlight editor ============================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightSheet(
    h: Highlight, bg: Color, fg: Color, accent: Color,
    onDismiss: () -> Unit, onSave: (Highlight) -> Unit, onDelete: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit,
) {
    var color by remember(h.id) { mutableIntStateOf(h.color) }
    var note by remember(h.id) { mutableStateOf(h.note) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = bg, contentColor = fg) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Row {
                    Box(Modifier.width(4.dp).height(60.dp).clip(RoundedCornerShape(2.dp)).background(Color(color or (0xFF shl 24))))
                    Spacer(Modifier.width(14.dp))
                    Text(h.text.trim(), fontFamily = FontFamily.Serif, maxLines = 6, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, lineHeight = 23.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Palettes.highlight.forEach { c ->
                        val sel = c == color
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(c or (0xFF shl 24)).copy(alpha = 0.85f))
                                .border(if (sel) 3.dp else 0.dp, if (sel) fg else Color.Transparent, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    note, { note = it }, label = { Text("Note") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, cursorColor = accent, focusedLabelColor = accent,
                        focusedTextColor = fg, unfocusedTextColor = fg)
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
                    IconButton(onCopy) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                    IconButton(onShare) { Icon(Icons.Rounded.Share, "Share") }
                    Spacer(Modifier.weight(1f))
                    Button({ onSave(h.copy(color = color, note = note.trim())) }, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = bg)) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
fun NoteDialog(title: String, quote: String?, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: (() -> Unit)?) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (quote != null) {
                    Text("“${quote.trim()}”", fontFamily = FontFamily.Serif, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        color = LocalContentColor.current.copy(alpha = 0.7f))
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(text, { text = it }, placeholder = { Text("Write something…") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ onSave(text.trim()) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onDelete) { Text("Delete", color = Color(0xFFFF7B7B)) }
                TextButton(onDismiss) { Text("Cancel") }
            }
        }
    )
}
