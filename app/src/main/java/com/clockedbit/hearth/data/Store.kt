package com.clockedbit.hearth.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Everything lives as small JSON files inside the app's private folder. */
object Store {
    lateinit var root: File
        private set

    val books = MutableStateFlow<List<Book>>(emptyList())
    val prefs = MutableStateFlow(ReaderPrefs())
    /** Collection names, in the order the user created them. */
    val collections = MutableStateFlow<List<String>>(emptyList())
    /** "yyyy-MM-dd" -> milliseconds read that day */
    val stats = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val writer = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    val booksDir: File get() = File(root, "books")
    val fontsDir: File get() = File(root, "fonts")

    fun init(context: Context) {
        if (::root.isInitialized) return
        root = context.applicationContext.filesDir
        booksDir.mkdirs(); fontsDir.mkdirs()
        loadBooks()
        val saved = runCatching {
            val a = JSONArray(File(root, "collections.json").readText())
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
        collections.value = (saved + books.value.map { it.collection }.filter { it.isNotEmpty() }).distinct()
        runCatching { prefs.value = ReaderPrefs.fromJson(JSONObject(File(root, "prefs.json").readText())) }
        runCatching {
            val o = JSONObject(File(root, "stats.json").readText())
            val m = HashMap<String, Long>()
            o.keys().forEach { k -> m[k] = o.optLong(k) }
            stats.value = m
        }
    }

    fun bookDir(id: String) = File(booksDir, id)

    /* ---------------- collections ---------------- */

    private fun saveCollections(l: List<String>) {
        collections.value = l
        val json = JSONArray(l).toString()
        writer.launch { writeAtomic(File(root, "collections.json"), json) }
    }

    /** Returns the name actually used (trimmed), or null if empty. */
    fun createCollection(name: String): String? {
        val n = name.trim().take(60)
        if (n.isEmpty()) return null
        if (collections.value.none { it.equals(n, true) }) saveCollections(collections.value + n)
        return collections.value.first { it.equals(n, true) }
    }

    fun renameCollection(old: String, new: String) {
        val n = new.trim().take(60)
        if (n.isEmpty() || n == old) return
        val merged = collections.value.firstOrNull { it.equals(n, true) && it != old }
        val target = merged ?: n
        saveCollections(collections.value.mapNotNull { if (it == old) (if (merged != null) null else target) else it })
        books.value.filter { it.collection == old }.forEach { b -> updateBook(b.id) { it.copy(collection = target) } }
    }

    /** Deletes the collection only; its books stay in the library. */
    fun deleteCollection(name: String) {
        saveCollections(collections.value - name)
        books.value.filter { it.collection == name }.forEach { b -> updateBook(b.id) { it.copy(collection = "") } }
    }

    fun setCollection(bookIds: Collection<String>, name: String) {
        bookIds.forEach { id -> updateBook(id) { it.copy(collection = name) } }
    }

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete(); tmp.renameTo(f)
        }
    }

    private fun loadBooks() {
        val list = booksDir.listFiles()?.mapNotNull { dir ->
            val f = File(dir, "book.json")
            if (!f.exists()) null else runCatching { Book.fromJson(JSONObject(f.readText())) }.getOrNull()
        } ?: emptyList()
        books.value = list
    }

    fun book(id: String): Book? = books.value.firstOrNull { it.id == id }

    fun saveBook(b: Book) {
        books.value = books.value.filter { it.id != b.id } + b
        val json = b.toJson().toString()
        writer.launch { writeAtomic(File(bookDir(b.id), "book.json"), json) }
    }

    fun updateBook(id: String, f: (Book) -> Book) {
        val b = book(id) ?: return
        saveBook(f(b))
    }

    fun deleteBook(id: String) {
        books.value = books.value.filter { it.id != id }
        writer.launch { bookDir(id).deleteRecursively() }
    }

    fun saveStructure(id: String, s: BookStructure) =
        writeAtomic(File(bookDir(id), "structure.json"), s.toJson().toString())

    fun structure(id: String): BookStructure = runCatching {
        BookStructure.fromJson(JSONObject(File(bookDir(id), "structure.json").readText()))
    }.getOrDefault(BookStructure(emptyList(), emptyList()))

    fun annotations(id: String): Annotations = runCatching {
        Annotations.fromJson(JSONObject(File(bookDir(id), "annotations.json").readText()))
    }.getOrDefault(Annotations())

    fun saveAnnotations(id: String, a: Annotations) {
        val json = a.toJson().toString()
        writer.launch { writeAtomic(File(bookDir(id), "annotations.json"), json) }
    }

    fun setPrefs(p: ReaderPrefs) {
        prefs.value = p
        val json = p.toJson().toString()
        writer.launch { writeAtomic(File(root, "prefs.json"), json) }
    }

    fun updatePrefs(f: (ReaderPrefs) -> ReaderPrefs) = setPrefs(f(prefs.value))

    fun logReading(bookId: String, ms: Long) {
        val key = dayFmt.format(Date())
        val m = HashMap(stats.value)
        m[key] = (m[key] ?: 0L) + ms
        stats.value = m
        val json = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()
        writer.launch { writeAtomic(File(root, "stats.json"), json) }
        updateBook(bookId) { it.copy(readingMs = it.readingMs + ms) }
    }

    fun todayMs(s: Map<String, Long> = stats.value): Long = s[dayFmt.format(Date())] ?: 0L

    fun streak(s: Map<String, Long> = stats.value): Int {
        val cal = Calendar.getInstance()
        var n = 0
        // today counts if read; otherwise start counting from yesterday
        if ((s[dayFmt.format(cal.time)] ?: 0L) < 60_000L) cal.add(Calendar.DAY_OF_YEAR, -1)
        while ((s[dayFmt.format(cal.time)] ?: 0L) >= 60_000L) {
            n++; cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return n
    }

    fun customFonts(): List<File> =
        fontsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()
}
