package com.clockedbit.hearth.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A book made of page images (PDF or comic). */
interface PageSource {
    val count: Int
    suspend fun render(index: Int, width: Int, crop: Boolean = false): Bitmap?
    fun close()
}

private fun bitmapCache(): LruCache<String, Bitmap> {
    val max = (Runtime.getRuntime().maxMemory() / 6).toInt()
    return object : LruCache<String, Bitmap>(max) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

class PdfSource(file: File) : PageSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()
    private val cache = bitmapCache()
    private var closed = false
    override val count: Int = renderer.pageCount

    override suspend fun render(index: Int, width: Int, crop: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val w = width.coerceIn(200, 2400)
        val key = "$index:$w:$crop"
        cache.get(key)?.let { return@withContext it }
        synchronized(lock) {
            if (closed || index !in 0 until count) return@withContext null
            runCatching {
                renderer.openPage(index).use { page ->
                    val h = (page.height * (w.toFloat() / page.width)).toInt().coerceIn(1, 6000)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = if (crop) autoCrop(bmp) else bmp
                    cache.put(key, out)
                    out
                }
            }.getOrNull()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}

class ImagePagesSource(private val files: List<File>) : PageSource {
    private val cache = bitmapCache()
    override val count: Int = files.size

    override suspend fun render(index: Int, width: Int, crop: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val f = files.getOrNull(index) ?: return@withContext null
        val w = width.coerceIn(200, 2400)
        val key = "$index:$w:$crop"
        cache.get(key)?.let { return@withContext it }
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= w) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { if (crop) autoCrop(it) else it }
                ?.also { cache.put(key, it) }
        }.getOrNull()
    }

    override fun close() {}
}

/** Trims empty (near-white) borders so the text fills more of the screen. */
fun autoCrop(src: Bitmap): Bitmap {
    val w = src.width; val h = src.height
    if (w < 50 || h < 50) return src
    val step = maxOf(1, w / 400)
    val row = IntArray(w)
    val col = IntArray(h)
    fun inkPx(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return r + g + b < 660
    }
    fun rowHasInk(y: Int): Boolean {
        src.getPixels(row, 0, w, 0, y, w, 1)
        var x = 0
        while (x < w) { if (inkPx(row[x])) return true; x += step }
        return false
    }
    fun colHasInk(x: Int): Boolean {
        src.getPixels(col, 0, 1, x, 0, 1, h)
        var y = 0
        while (y < h) { if (inkPx(col[y])) return true; y += step }
        return false
    }
    var top = 0; while (top < h && !rowHasInk(top)) top += step
    if (top >= h) return src
    var bottom = h - 1; while (bottom > top && !rowHasInk(bottom)) bottom -= step
    var left = 0; while (left < w && !colHasInk(left)) left += step
    var right = w - 1; while (right > left && !colHasInk(right)) right -= step
    val pad = (w * 0.02f).toInt()
    left = maxOf(0, left - pad); top = maxOf(0, top - pad)
    right = minOf(w - 1, right + pad); bottom = minOf(h - 1, bottom + pad)
    val cw = right - left + 1; val ch = bottom - top + 1
    if (cw < w * 0.3f || ch < h * 0.3f) return src
    if (cw * ch > w * h * 0.95f) return src
    return Bitmap.createBitmap(src, left, top, cw, ch)
}
