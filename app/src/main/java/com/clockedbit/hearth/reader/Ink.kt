package com.clockedbit.hearth.reader

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.clockedbit.hearth.data.InkStroke
import kotlin.math.abs
import kotlin.math.hypot

object InkTool {
    const val PEN = 0
    const val MARKER = 1
    const val ERASER = 2
}

/** Shared across pages so the pen is "remembered" once seen (auto palm rejection). */
object StylusMemory { var seen = false }

private class TouchState {
    var activeId = -1
    var erasing = false
    var swipeId = -1
    var sx = 0f
    var sy = 0f
    val erased = HashSet<String>()
}

fun DrawScope.drawInk(pts: FloatArray, color: Int, widthFrac: Float, tool: Int) {
    val w = size.width; val h = size.height
    if (w <= 0f || pts.size < 2) return
    val sw = (widthFrac * w).coerceAtLeast(1f)
    val c = if (tool == InkTool.MARKER) Color(color).copy(alpha = 0.38f) else Color(color)
    if (pts.size < 6) {
        drawCircle(c, sw / 2f, Offset(pts[0] * w, pts[1] * h))
        return
    }
    val path = Path()
    path.moveTo(pts[0] * w, pts[1] * h)
    var i = 2
    while (i < pts.size - 2) {
        val x1 = pts[i] * w; val y1 = pts[i + 1] * h
        val x2 = pts[i + 2] * w; val y2 = pts[i + 3] * h
        path.quadraticBezierTo(x1, y1, (x1 + x2) / 2f, (y1 + y2) / 2f)
        i += 2
    }
    path.lineTo(pts[pts.size - 2] * w, pts[pts.size - 1] * h)
    drawPath(
        path, c,
        style = Stroke(width = sw, cap = if (tool == InkTool.MARKER) StrokeCap.Square else StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/**
 * Drawing surface with palm rejection.
 * palmMode: 0 = auto (fingers stop drawing once a stylus is seen), 1 = stylus only, 2 = fingers draw too.
 * Non-drawing finger touches are passed through (return false) when [passFingers] is true,
 * otherwise a horizontal finger swipe calls [onSwipe] (+1 next, -1 previous).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InkLayer(
    strokes: List<InkStroke>,
    active: Boolean,
    tool: Int,
    color: Int,
    widthDp: Float,
    palmMode: Int,
    passFingers: Boolean,
    onStroke: (tool: Int, color: Int, widthFrac: Float, points: FloatArray) -> Unit,
    onErase: (InkStroke) -> Unit,
    onSwipe: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val palmPx = with(density) { 46.dp.toPx() }
    val swipePx = with(density) { 70.dp.toPx() }
    val eraseR = with(density) { 18.dp.toPx() }
    val widthPx = with(density) { widthDp.dp.toPx() }
    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    val current = remember { ArrayList<Float>() }
    var tick by remember { mutableIntStateOf(0) }
    val st = remember { TouchState() }
    val curStrokes by rememberUpdatedState(strokes)
    val curTool by rememberUpdatedState(tool)
    val curColor by rememberUpdatedState(color)
    val curOnStroke by rememberUpdatedState(onStroke)
    val curOnErase by rememberUpdatedState(onErase)
    val curOnSwipe by rememberUpdatedState(onSwipe)

    fun addPoint(x: Float, y: Float) {
        if (layerSize.width == 0 || layerSize.height == 0) return
        current.add((x / layerSize.width).coerceIn(0f, 1f))
        current.add((y / layerSize.height).coerceIn(0f, 1f))
    }

    fun eraseAt(x: Float, y: Float) {
        val w = layerSize.width.toFloat(); val h = layerSize.height.toFloat()
        for (s in curStrokes) {
            if (s.id in st.erased) continue
            val r = eraseR + s.width * w / 2f
            var i = 0
            while (i < s.points.size - 1) {
                if (hypot(s.points[i] * w - x, s.points[i + 1] * h - y) < r) {
                    st.erased.add(s.id); curOnErase(s); break
                }
                i += 2
            }
        }
    }

    val input = if (!active) Modifier else Modifier.pointerInteropFilter { ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val tt = ev.getToolType(i)
                val isPen = tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
                if (isPen) StylusMemory.seen = true
                val fingerDraws = when (palmMode) { 0 -> !StylusMemory.seen; 1 -> false; else -> true }
                val palm = ev.getTouchMajor(i) > palmPx
                if (st.activeId == -1 && (isPen || (fingerDraws && !palm))) {
                    st.activeId = ev.getPointerId(i)
                    st.erasing = curTool == InkTool.ERASER || tt == MotionEvent.TOOL_TYPE_ERASER ||
                        (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                    st.erased.clear()
                    current.clear()
                    if (st.erasing) eraseAt(ev.getX(i), ev.getY(i)) else addPoint(ev.getX(i), ev.getY(i))
                    tick++
                    true
                } else if (st.activeId == -1 && !isPen) {
                    if (passFingers) false
                    else {
                        st.swipeId = ev.getPointerId(i); st.sx = ev.getX(i); st.sy = ev.getY(i); true
                    }
                } else true
            }
            MotionEvent.ACTION_MOVE -> {
                if (st.activeId != -1) {
                    val idx = ev.findPointerIndex(st.activeId)
                    if (idx >= 0) {
                        for (hIdx in 0 until ev.historySize) {
                            val hx = ev.getHistoricalX(idx, hIdx); val hy = ev.getHistoricalY(idx, hIdx)
                            if (st.erasing) eraseAt(hx, hy) else addPoint(hx, hy)
                        }
                        if (st.erasing) eraseAt(ev.getX(idx), ev.getY(idx)) else addPoint(ev.getX(idx), ev.getY(idx))
                        tick++
                    }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                val pid = ev.getPointerId(i)
                if (pid == st.activeId) {
                    if (!st.erasing && current.size >= 2) {
                        val pts = current.toFloatArray()
                        val w = layerSize.width.toFloat().coerceAtLeast(1f)
                        curOnStroke(curTool, curColor, widthPx / w, pts)
                    }
                    current.clear(); st.activeId = -1; tick++
                } else if (pid == st.swipeId) {
                    val dx = ev.getX(i) - st.sx; val dy = ev.getY(i) - st.sy
                    if (abs(dx) > swipePx && abs(dx) > abs(dy)) curOnSwipe(if (dx < 0) 1 else -1)
                    st.swipeId = -1
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                current.clear(); st.activeId = -1; st.swipeId = -1; tick++; true
            }
            else -> true
        }
    }

    Canvas(modifier.onSizeChanged { layerSize = it }.then(input)) {
        @Suppress("UNUSED_VARIABLE") val t = tick
        for (s in strokes) drawInk(s.points, s.color, s.width, s.tool)
        if (current.size >= 2 && !st.erasing) {
            drawInk(current.toFloatArray(), color, widthPx / size.width.coerceAtLeast(1f), tool)
        }
    }
}
