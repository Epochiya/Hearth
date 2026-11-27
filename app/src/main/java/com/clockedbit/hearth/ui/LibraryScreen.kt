package com.clockedbit.hearth.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clockedbit.hearth.data.Book
import com.clockedbit.hearth.data.BookFormat
import com.clockedbit.hearth.data.Importer
import com.clockedbit.hearth.data.Store
import kotlinx.coroutines.launch
import java.util.Calendar

fun exportNotes(ctx: Context, book: Book) {
    val a = Store.annotations(book.id)
    val s = Store.structure(book.id)
    val sb = StringBuilder()
    sb.append("# ").append(book.title).append('\n')
    if (book.author.isNotEmpty()) sb.append("_").append(book.author).append("_\n")
    sb.append('\n')
    fun chap(c: Int) = s.chapters.getOrNull(c)?.title ?: if (book.format.reflow) "Chapter ${c + 1}" else ""
    if (a.highlights.isNotEmpty()) {
        sb.append("## Highlights\n\n")
        a.highlights.sortedWith(compareBy({ it.chapter }, { it.start })).forEach {
            sb.append("> ").append(it.text.trim().replace("\n", "\n> ")).append("\n")
            if (it.note.isNotBlank()) sb.append("\n**Note:** ").append(it.note.trim()).append('\n')
            sb.append("\n— ").append(chap(it.chapter)).append("\n\n")
        }
    }
    if (a.notes.isNotEmpty()) {
        sb.append("## Notes\n\n")
        a.notes.sortedWith(compareBy({ it.chapter }, { it.offset }, { it.page })).forEach {
            val where = if (it.page >= 0) "Page ${it.page + 1}" else chap(it.chapter)
            sb.append("- ").append(it.text.trim()).append("  _(").append(where).append(")_\n")
        }
        sb.append('\n')
    }
    if (a.bookmarks.isNotEmpty()) {
        sb.append("## Bookmarks\n\n")
        a.bookmarks.forEach {
            val where = if (it.page >= 0) "Page ${it.page + 1}" else chap(it.chapter)
            sb.append("- ").append(where).append(": ").append(it.excerpt.take(90)).append("…\n")
        }
    }
    if (a.highlights.isEmpty() && a.notes.isEmpty() && a.bookmarks.isEmpty()) sb.append("(No highlights or notes yet.)\n")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Notes — ${book.title}")
        putExtra(Intent.EXTRA_TEXT, sb.toString())
    }
    ctx.startActivity(Intent.createChooser(send, "Export notes"))
}

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Late-night reading?"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by Store.books.collectAsState()
    val stats by Store.stats.collectAsState()
    val prefs by Store.prefs.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var menuBook by remember { mutableStateOf<Book?>(null) }
    var editBook by remember { mutableStateOf<Book?>(null) }
    var deleteBook by remember { mutableStateOf<Book?>(null) }
    var importing by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = uris.size
            var ok = 0
            var last: Book? = null
            for (u in uris) {
                try { last = Importer.import(ctx, u); ok++ } catch (e: Exception) {
                    snackbar.showSnackbar(e.message ?: "Import failed")
                }
                importing--
            }
            if (ok > 0) {
                val r = snackbar.showSnackbar(
                    if (ok == 1) "Added “${last?.title}”" else "Added $ok books",
                    actionLabel = if (ok == 1) "Open" else null, duration = SnackbarDuration.Short
                )
                if (r == SnackbarResult.ActionPerformed && last != null) onOpen(last.id)
            }
        }
    }
    val addBooks = { launcher.launch(arrayOf("*/*")) }

    val shown = remember(books, query, filter, sort) {
        var l = books.asSequence()
        if (query.isNotBlank()) l = l.filter { it.title.contains(query, true) || it.author.contains(query, true) }
        l = when (filter) {
            1 -> l.filter { it.lastOpened > 0 && !it.finished }
            2 -> l.filter { it.favorite }
            3 -> l.filter { it.finished }
            4 -> l.filter { it.lastOpened == 0L }
            else -> l
        }
        when (sort) {
            1 -> l.sortedBy { it.title.lowercase() }
            2 -> l.sortedBy { it.author.lowercase() }
            3 -> l.sortedByDescending { it.progress }
            4 -> l.sortedByDescending { it.addedAt }
            else -> l.sortedByDescending { maxOf(it.lastOpened, it.addedAt) }
        }.toList()
    }
    val continueBook = books.filter { it.lastOpened > 0 && !it.finished }.maxByOrNull { it.lastOpened }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize()) {
        CozyBackground()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(prefs.coverSize.dp),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = top + 18.dp, bottom = 130.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(stats, prefs.dailyGoalMin, books.size, onSettings = { showSettings = true })
            }
            if (continueBook != null && query.isBlank() && filter == 0) {
                item(span = { GridItemSpan(maxLineSpan) }) { ContinueCard(continueBook) { onOpen(continueBook.id) } }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    OutlinedTextField(
                        query, { query = it }, singleLine = true,
                        placeholder = { Text("Search your shelf") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = Hearth.surface.copy(alpha = 0.6f),
                            focusedContainerColor = Hearth.surface.copy(alpha = 0.8f),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.06f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("All", "Reading", "Favorites", "Finished", "New").forEachIndexed { i, label ->
                                FilterChip(
                                    selected = filter == i, onClick = { filter = i }, label = { Text(label) },
                                    shape = RoundedCornerShape(50),
                                )
                            }
                        }
                        Box {
                            IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                listOf("Recent", "Title", "Author", "Progress", "Date added").forEachIndexed { i, s ->
                                    DropdownMenuItem(
                                        text = { Text(s) }, onClick = { sort = i; sortMenu = false },
                                        trailingIcon = { if (sort == i) Icon(Icons.Rounded.Check, null) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (books.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(addBooks) }
            } else if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
            }
            items(shown, key = { it.id }) { b ->
                BookTile(b, Modifier.animateItem(), onClick = { onOpen(b.id) }, onLongClick = { menuBook = b })
            }
        }

        ExtendedFloatingActionButton(
            onClick = addBooks,
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Add books") },
            containerColor = Hearth.amber, contentColor = Color(0xFF2A1607),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp)
        )

        AnimatedVisibility(
            importing > 0,
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = top + 8.dp)
        ) {
            Surface(shape = RoundedCornerShape(50), color = Hearth.surfaceHigh, shadowElevation = 8.dp) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Adding $importing book${if (importing > 1) "s" else ""}…")
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp))
    }

    menuBook?.let { b ->
        ModalBottomSheet(onDismissRequest = { menuBook = null }, containerColor = Hearth.surface) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BookCover(b, Modifier.width(56.dp).height(82.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(b.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(b.author, b.format.label, "${(b.progress * 100).toInt()}% read",
                                if (b.readingMs > 60_000) "${b.readingMs / 60_000} min" else "").filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SheetAction(Icons.Rounded.AutoStories, "Open") { menuBook = null; onOpen(b.id) }
                SheetAction(if (b.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (b.favorite) "Remove from favorites" else "Add to favorites") {
                    Store.updateBook(b.id) { it.copy(favorite = !it.favorite) }; menuBook = null
                }
                SheetAction(Icons.Rounded.CheckCircle, if (b.finished) "Mark as unread" else "Mark as finished") {
                    Store.updateBook(b.id) { if (it.finished) it.copy(finished = false) else it.copy(finished = true, progress = 1f) }
                    menuBook = null
                }
                SheetAction(Icons.Rounded.Edit, "Edit title & author") { editBook = b; menuBook = null }
                SheetAction(Icons.Rounded.Share, "Export highlights & notes") { exportNotes(ctx, b); menuBook = null }
                SheetAction(Icons.Rounded.Delete, "Remove from library", danger = true) { deleteBook = b; menuBook = null }
            }
        }
    }

    editBook?.let { b ->
        var t by remember(b.id) { mutableStateOf(b.title) }
        var a by remember(b.id) { mutableStateOf(b.author) }
        AlertDialog(
            onDismissRequest = { editBook = null },
            title = { Text("Edit details") },
            text = {
                Column {
                    OutlinedTextField(t, { t = it }, label = { Text("Title") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(a, { a = it }, label = { Text("Author") })
                }
            },
            confirmButton = {
                TextButton({ Store.updateBook(b.id) { it.copy(title = t.trim().ifEmpty { it.title }, author = a.trim()) }; editBook = null }) { Text("Save") }
            },
            dismissButton = { TextButton({ editBook = null }) { Text("Cancel") } }
        )
    }

    deleteBook?.let { b ->
        AlertDialog(
            onDismissRequest = { deleteBook = null },
            title = { Text("Remove “${b.title}”?") },
            text = { Text("Its highlights, notes and drawings will be deleted too.") },
            confirmButton = { TextButton({ Store.deleteBook(b.id); deleteBook = null }) { Text("Remove", color = Color(0xFFFF7B7B)) } },
            dismissButton = { TextButton({ deleteBook = null }) { Text("Keep") } }
        )
    }

    if (showSettings) AppSettingsSheet(onDismiss = { showSettings = false }, snackbar = snackbar)
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) Color(0xFFFF7B7B) else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = c)
        Spacer(Modifier.width(18.dp))
        Text(label, color = c, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Header(stats: Map<String, Long>, goalMin: Int, count: Int, onSettings: () -> Unit) {
    val today = Store.todayMs(stats) / 60_000
    val streak = Store.streak(stats)
    val goalFrac by animateFloatAsState((today.toFloat() / goalMin.coerceAtLeast(1)).coerceIn(0f, 1f), tween(900), label = "goal")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(), style = MaterialTheme.typography.labelSmall, color = Hearth.amber)
                Spacer(Modifier.height(4.dp))
                Text("Your Library", style = MaterialTheme.typography.displaySmall)
            }
            IconButton(onSettings, Modifier.clip(CircleShape).background(Hearth.surface.copy(alpha = 0.6f))) {
                Icon(Icons.Rounded.Tune, "Settings")
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatChip(Modifier.weight(1f)) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { goalFrac }, modifier = Modifier.size(34.dp), strokeWidth = 4.dp,
                        color = Hearth.amber, trackColor = Color.White.copy(alpha = 0.08f), strokeCap = StrokeCap.Round
                    )
                    Text("${today}", fontSize = 11.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$today / $goalMin min", style = MaterialTheme.typography.titleSmall)
                }
            }
            StatChip(Modifier.weight(1f)) {
                Text("🔥", fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Streak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$streak day${if (streak == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall)
                }
            }
            StatChip(Modifier.weight(1f)) {
                Text("📖", fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Books", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$count", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun StatChip(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(Hearth.surface.copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, content = content
    )
}

@Composable
private fun ContinueCard(b: Book, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A2416), Color(0xFF1F1714), Color(0xFF2A1E2E))))
            .border(1.dp, Hearth.amber.copy(alpha = 0.18f), RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Box(Modifier.matchParentSize().padding(6.dp).shadow(26.dp, RoundedCornerShape(10.dp), ambientColor = Hearth.ember, spotColor = Hearth.ember))
                BookCover(b, Modifier.width(92.dp).height(136.dp).clip(RoundedCornerShape(10.dp)))
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("CONTINUE READING", style = MaterialTheme.typography.labelSmall, color = Hearth.amber)
                Spacer(Modifier.height(6.dp))
                Text(b.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (b.author.isNotEmpty()) Text(b.author, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { b.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Hearth.amber, trackColor = Color.White.copy(alpha = 0.08f), strokeCap = StrokeCap.Round,
                    drawStopIndicator = {}
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(b.progress * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = Hearth.amber)
                    Spacer(Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(50), color = Hearth.amber) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF2A1607), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resume", color = Color(0xFF2A1607), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(b: Book, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.68f)
                .shadow(14.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(14.dp))
        ) {
            BookCover(b, Modifier.fillMaxSize())
            if (b.progress > 0f && !b.finished) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(b.progress.coerceIn(0.02f, 1f)).background(Hearth.amber))
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (b.finished) Badge("✓ DONE", Color(0xFF7BD389))
                else if (b.lastOpened == 0L) Badge("NEW", Hearth.amber)
                if (b.favorite) Badge("♥", Color(0xFFFF8FA3))
            }
            if (b.format == BookFormat.PDF || b.format == BookFormat.CBZ) {
                Text(
                    b.format.label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
        if (b.author.isNotEmpty())
            Text(b.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, fontSize = 9.sp, color = Color(0xFF1A1310),
        modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSettingsSheet(onDismiss: () -> Unit, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by Store.prefs.collectAsState()
    var fonts by remember { mutableStateOf(Store.customFonts()) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { Importer.importFont(ctx, uri); fonts = Store.customFonts() } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Font import failed") }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Hearth.surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            SectionLabel("Daily reading goal")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(prefs.dailyGoalMin.toFloat(), { v -> Store.updatePrefs { it.copy(dailyGoalMin = v.toInt()) } },
                    valueRange = 5f..180f, steps = 34, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text("${prefs.dailyGoalMin} min")
            }
            SectionLabel("Cover size")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(prefs.coverSize.toFloat(), { v -> Store.updatePrefs { it.copy(coverSize = v.toInt()) } },
                    valueRange = 96f..260f, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(when { prefs.coverSize < 125 -> "Small"; prefs.coverSize < 185 -> "Medium"; else -> "Large" })
            }
            SectionLabel("Your fonts")
            if (fonts.isEmpty()) Text("Import .ttf / .otf files to use them in books.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            fonts.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.nameWithoutExtension, Modifier.weight(1f))
                    IconButton({ f.delete(); fonts = Store.customFonts() }) { Icon(Icons.Rounded.Delete, "Delete font") }
                }
            }
            TextButton({ fontPicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Import a font")
            }
            SectionLabel("Pen")
            Text("Palm rejection", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Auto", "Stylus only", "Off").forEachIndexed { i, s ->
                    SegmentedButton(prefs.palmMode == i, { Store.updatePrefs { it.copy(palmMode = i) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(s) }
                }
            }
            Text(
                "Auto: fingers draw until your stylus touches the screen, then only the stylus draws.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            SectionLabel("About")
            Text("Hearth — a cozy reader. Supports EPUB, PDF, TXT, FB2, DOCX, Markdown, HTML and CBZ.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
