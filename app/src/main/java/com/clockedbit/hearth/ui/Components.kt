package com.clockedbit.hearth.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clockedbit.hearth.data.Book
import com.clockedbit.hearth.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Slowly drifting warm light behind the library. */
@Composable
fun CozyBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "bg")
    val phase by t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart), label = "phase"
    )
    Canvas(modifier.fillMaxSize().background(Hearth.bg)) {
        val w = size.width; val h = size.height
        val r = maxOf(w, h)
        fun blob(cx: Float, cy: Float, radius: Float, c: Color) {
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), Offset(cx, cy), radius), radius, Offset(cx, cy))
        }
        blob(w * (0.15f + 0.08f * cos(phase)), h * (0.12f + 0.05f * sin(phase)), r * 0.55f, Hearth.ember.copy(alpha = 0.20f))
        blob(w * (0.90f + 0.06f * sin(phase * 2)), h * (0.35f + 0.08f * cos(phase)), r * 0.45f, Hearth.plum.copy(alpha = 0.14f))
        blob(w * (0.40f + 0.10f * sin(phase)), h * (0.95f + 0.04f * cos(phase * 2)), r * 0.50f, Hearth.amber.copy(alpha = 0.10f))
        blob(w * (0.75f + 0.05f * cos(phase * 3)), h * (0.80f), r * 0.30f, Hearth.teal.copy(alpha = 0.06f))
    }
}

private val coverCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
fun rememberCover(book: Book): Bitmap? {
    val path = book.cover?.let { File(Store.bookDir(book.id), it).path }
    val bmp by produceState<Bitmap?>(path?.let { coverCache.get(it) }, path) {
        if (path == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 360) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.also { coverCache.put(path, it) }
            }.getOrNull()
        }
    }
    return bmp
}

private val coverPalettes = listOf(
    listOf(Color(0xFF6B2F1A), Color(0xFFD9733A)),
    listOf(Color(0xFF2B2350), Color(0xFF8E6BD9)),
    listOf(Color(0xFF12383A), Color(0xFF3FB2A6)),
    listOf(Color(0xFF4A1E2E), Color(0xFFE07A8F)),
    listOf(Color(0xFF2E3A1C), Color(0xFF9CC25B)),
    listOf(Color(0xFF1C2B44), Color(0xFF5B8DEF)),
    listOf(Color(0xFF3D2A12), Color(0xFFE6B450)),
)

@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val bmp = rememberCover(book)
    Box(modifier) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val pal = coverPalettes[(book.title.hashCode() and 0x7fffffff) % coverPalettes.size]
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(pal, Offset.Zero, Offset(400f, 700f))),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.width(26.dp).height(3.dp).background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                    Text(
                        book.title, color = Color.White, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp, lineHeight = 21.sp, maxLines = 5, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        book.author.ifEmpty { book.format.label }, color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // spine shading for a "real book" feel
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.28f), 0.05f to Color.White.copy(alpha = 0.08f),
                    0.09f to Color.Transparent, 1f to Color.Transparent
                )
            )
        )
    }
}

fun hsv(c: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }

/** Compact HSV colour picker with swatch + hex entry. */
@Composable
fun ColorPickerRow(label: String, color: Int, onChange: (Int) -> Unit, keepAlpha: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val alpha = if (keepAlpha) (color ushr 24) else 0xFF
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(color or (0xFF shl 24)))
                    .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            )
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("#%06X".format(color and 0xFFFFFF), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            val h = hsv(color)
            var hex by remember(color) { mutableStateOf("%06X".format(color and 0xFFFFFF)) }
            fun emit(hh: Float, ss: Float, vv: Float) {
                val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hh, ss, vv))
                onChange((rgb and 0xFFFFFF) or (alpha shl 24))
            }
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                GradientSlider("Hue", h[0], 0f..360f, Brush.horizontalGradient(
                    listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it, 1f, 1f) })) { emit(it, h[1], h[2]) }
                GradientSlider("Saturation", h[1], 0f..1f, Brush.horizontalGradient(
                    listOf(Color.hsv(h[0], 0f, h[2]), Color.hsv(h[0], 1f, h[2])))) { emit(h[0], it, h[2]) }
                GradientSlider("Brightness", h[2], 0f..1f, Brush.horizontalGradient(
                    listOf(Color.Black, Color.hsv(h[0], h[1], 1f)))) { emit(h[0], h[1], it) }
                OutlinedTextField(
                    hex, { v ->
                        hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        if (hex.length == 6) onChange((hex.toLong(16).toInt() and 0xFFFFFF) or (alpha shl 24))
                    },
                    label = { Text("Hex") }, singleLine = true, prefix = { Text("#") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.width(160.dp)
                )
            }
        }
    }
}

@Composable
private fun GradientSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, brush: Brush, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(brush))
            Slider(
                value, onChange, valueRange = range,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(110.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Hearth.ember.copy(alpha = 0.45f), Color.Transparent))),
            contentAlignment = Alignment.Center
        ) { Text("📚", fontSize = 46.sp) }
        Spacer(Modifier.height(14.dp))
        Text("Your shelf is empty", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Add EPUB, PDF, TXT, FB2, DOCX, Markdown, HTML or CBZ comics.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        androidx.compose.material3.FilledTonalButton(onClick = onAdd) { Text("Add your first book") }
    }
}

@Composable
fun LaunchOnce(block: suspend () -> Unit) { LaunchedEffect(Unit) { block() } }
