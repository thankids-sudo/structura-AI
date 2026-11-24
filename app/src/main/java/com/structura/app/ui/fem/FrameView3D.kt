package com.structura.app.ui.fem

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameResults
import com.structura.core.fem.LoadDir
import com.structura.core.fem.Station
import com.structura.core.fem.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class ResultView(val label: String) {
    MODEL("Model"), DEFORMED("Deformed"), N("N"), VZ("Vz"), MY("My"), VY("Vy"), MZ("Mz")
}

private fun value(st: Station, v: ResultView): Double = when (v) {
    ResultView.N -> st.n
    ResultView.VZ -> st.vz
    ResultView.MY -> st.my
    ResultView.VY -> st.vy
    ResultView.MZ -> st.mz
    else -> 0.0
}

/** Orthographic orbit projection shared by drawing and hit-testing. */
class Projector(model: FrameModel, yawDeg: Float, pitchDeg: Float, zoom: Float, private val w: Float, private val h: Float) {
    private val cy = cos(Math.toRadians(yawDeg.toDouble()))
    private val sy = sin(Math.toRadians(yawDeg.toDouble()))
    private val cp = cos(Math.toRadians(pitchDeg.toDouble()))
    private val sp = sin(Math.toRadians(pitchDeg.toDouble()))
    private val cx: Double
    private val cyy: Double
    private val scale: Double
    val modelSize: Double

    fun proj(p: Vec3): Pair<Double, Double> {
        val sx = p.x * cy + p.y * sy
        val depth = -p.x * sy + p.y * cy
        return sx to (p.z * cp + depth * sp)
    }

    init {
        val pts = model.nodes.map { proj(it.pos) }
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1.0)
        scale = min(w, h) * 0.62 / span * zoom
        cx = (minX + maxX) / 2; cyy = (minY + maxY) / 2
        val xs = model.nodes.map { it.x }; val ys = model.nodes.map { it.y }; val zs = model.nodes.map { it.z }
        modelSize = maxOf(xs.max() - xs.min(), ys.max() - ys.min(), zs.max() - zs.min()).coerceAtLeast(1.0)
    }

    fun screen(p: Vec3): Offset {
        val (x, y) = proj(p)
        return Offset((w / 2 + (x - cx) * scale).toFloat(), (h / 2 - (y - cyy) * scale).toFloat())
    }

    /** Screen-space direction of a world-space vector. */
    fun pixelDir(v: Vec3): Offset {
        val (x, y) = proj(v)
        return Offset((x * scale).toFloat(), (-y * scale).toFloat())
    }
}

/** Blue → cyan → green → yellow → red, as RFEM's deformation colour scale. */
fun heat(t: Float): Color {
    val stops = listOf(Color(0xFF1565C0), Color(0xFF00ACC1), Color(0xFF43A047), Color(0xFFFDD835), Color(0xFFE53935))
    val x = t.coerceIn(0f, 1f) * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], x - i)
}

/**
 * Orbit view of a frame model: drag to rotate, pinch to zoom, tap a node to select it.
 * DEFORMED view: the shape grows in whenever results change and can keep oscillating,
 * coloured by total displacement. [deformScale] > 0 fixes the magnification so that
 * live load changes show the real growth of deflection.
 */
@Composable
fun FrameView3D(
    model: FrameModel,
    results: FrameResults?,
    view: ResultView,
    modifier: Modifier = Modifier,
    deformScale: Double = 0.0,
    animate: Boolean = true,
    selectedNode: Int? = null,
    onNodeTap: ((Int?) -> Unit)? = null,
) {
    var yaw by remember { mutableFloatStateOf(-35f) }
    var pitch by remember { mutableFloatStateOf(25f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var canvasSize by remember { mutableStateOf(Size(1f, 1f)) }
    val tapCallback by rememberUpdatedState(onNodeTap)

    val grow = remember { Animatable(1f) }
    LaunchedEffect(results) {
        if (results != null) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    }
    val osc by rememberInfiniteTransition(label = "osc").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "phase",
    )
    val phase = grow.value * (if (animate && view == ResultView.DEFORMED) osc else 1f)

    val ink = Color(0xFF1B2A41)
    val pos = Color(0xFFC62828)
    val neg = Color(0xFF1565C0)
    val loadColor = Color(0xFFEF6C00)

    Box(
        modifier
            .background(Color.White)
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    yaw += pan.x * 0.4f
                    pitch = (pitch + pan.y * 0.4f).coerceIn(-89f, 89f)
                    zoom = (zoom * z).coerceIn(0.3f, 8f)
                }
            }
            .pointerInput(model) {
                detectTapGestures { tap ->
                    val cb = tapCallback ?: return@detectTapGestures
                    val pj = Projector(model, yaw, pitch, zoom, canvasSize.width, canvasSize.height)
                    val hit = model.nodes.minByOrNull { (pj.screen(it.pos) - tap).getDistance() }
                    cb(if (hit != null && (pj.screen(hit.pos) - tap).getDistance() < 60f) hit.id else null)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pj = Projector(model, yaw, pitch, zoom, size.width, size.height)
            val modelSize = pj.modelSize
            axesTriad(pj)

            val faded = view != ResultView.MODEL
            for (m in model.members) {
                drawLine(
                    if (faded) ink.copy(alpha = 0.25f) else ink,
                    pj.screen(model.node(m.i).pos), pj.screen(model.node(m.j).pos), if (faded) 2f else 4f,
                )
            }
            supports(pj, model)
            for (n in model.nodes) {
                val p = pj.screen(n.pos)
                if (n.id == selectedNode) drawCircle(Color(0xFFFFA000), 16f, p, style = Stroke(4f))
                drawCircle(ink, 4.5f, p)
            }
            loads(pj, model, loadColor)

            if (results == null) return@Canvas

            when (view) {
                ResultView.MODEL -> {}
                ResultView.DEFORMED -> {
                    val maxD = max(results.maxDisplacement().second, results.maxMemberDeflection())
                    val k = if (deformScale > 0) deformScale else if (maxD > 1e-12) 0.08 * modelSize / maxD else 1.0
                    val kk = k * phase
                    val colourMax = maxD.coerceAtLeast(1e-12)
                    for (m in model.members) {
                        val real = results.deformedShape(m.id, 1.0, 16)
                        val zero = results.deformedShape(m.id, 0.0, 16)
                        val shown = results.deformedShape(m.id, kk, 16)
                        for (a in 1 until shown.size) {
                            val d = (real[a] - zero[a]).length
                            drawLine(heat((d / colourMax).toFloat()), pj.screen(shown[a - 1]), pj.screen(shown[a]), 5f)
                        }
                    }
                    legend(maxD)
                }
                else -> {
                    val stations = model.members.associate { it.id to results.internalForces(it.id, 20) }
                    val maxV = stations.values.flatten().maxOfOrNull { abs(value(it, view)) } ?: 0.0
                    if (maxV < 1e-9) return@Canvas
                    val k = 0.12 * modelSize / maxV * grow.value
                    val paint = Paint().apply { textSize = 26f; isAntiAlias = true }
                    for (m in model.members) {
                        val ax = results.memberAxes[m.id]!!
                        val p0 = model.node(m.i).pos
                        val dir = if (view == ResultView.VY || view == ResultView.MZ) ax.y else ax.z
                        val st = stations[m.id]!!
                        val base = st.map { p0 + ax.x * it.s }
                        val tip = st.map { p0 + ax.x * it.s + dir * (value(it, view) * k) }
                        for (a in 1 until st.size) {
                            val c = if (value(st[a], view) >= 0) pos else neg
                            drawLine(c, pj.screen(tip[a - 1]), pj.screen(tip[a]), 2.5f)
                            drawLine(c.copy(alpha = 0.35f), pj.screen(base[a]), pj.screen(tip[a]), 1.2f)
                        }
                        val ext = st.indices.maxByOrNull { abs(value(st[it], view)) }!!
                        val ev = value(st[ext], view)
                        if (abs(ev) > 0.05 * maxV) {
                            val sp0 = pj.screen(tip[ext])
                            paint.color = (if (ev >= 0) pos else neg).toArgb()
                            drawIntoCanvas { it.nativeCanvas.drawText("%.1f".format(ev), sp0.x + 4, sp0.y - 4, paint) }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.axesTriad(pj: Projector) {
    val o = Offset(44f, size.height - 44f)
    val paint = Paint().apply { textSize = 26f; isAntiAlias = true }
    for ((v, c, l) in listOf(Triple(Vec3.X, Color.Red, "X"), Triple(Vec3.Y, Color(0xFF2E7D32), "Y"), Triple(Vec3.Z, Color.Blue, "Z"))) {
        val (x, y) = pj.proj(v)
        val e = Offset(o.x + (x * 30).toFloat(), o.y - (y * 30).toFloat())
        drawLine(c, o, e, 3f)
        paint.color = c.toArgb()
        drawIntoCanvas { it.nativeCanvas.drawText(l, e.x + 3, e.y, paint) }
    }
}

private fun DrawScope.supports(pj: Projector, model: FrameModel) {
    val g = Color(0xFF2E7D32)
    for (s in model.supports) {
        val p = pj.screen(model.node(s.node).pos)
        if (s.restraints.all { it }) {
            drawRect(g, Offset(p.x - 13f, p.y), Size(26f, 12f))
        } else {
            val path = Path().apply { moveTo(p.x, p.y); lineTo(p.x - 14f, p.y + 22f); lineTo(p.x + 14f, p.y + 22f); close() }
            drawPath(path, g, style = Stroke(3f))
        }
    }
}

/** Load arrows: nodal forces scaled to the largest one, gravity UDLs as rows of small arrows. */
private fun DrawScope.loads(pj: Projector, model: FrameModel, c: Color) {
    val maxF = model.nodalLoads.maxOfOrNull { Vec3(it.values[0], it.values[1], it.values[2]).length } ?: 0.0
    val paint = Paint().apply { textSize = 24f; isAntiAlias = true; color = c.toArgb() }
    for (nl in model.nodalLoads) {
        val f = Vec3(nl.values[0], nl.values[1], nl.values[2])
        if (f.length < 1e-9) continue
        val tip = pj.screen(model.node(nl.node).pos)
        val len = 30f + 60f * (f.length / maxF).toFloat()
        val d = pj.pixelDir(f.unit())
        val dl = d.getDistance().coerceAtLeast(1e-3f)
        val u = Offset(d.x / dl, d.y / dl)
        val tail = tip - u * len
        drawArrow(tail, tip, c, 3f)
        drawIntoCanvas { it.nativeCanvas.drawText("%.0f kN".format(f.length), tail.x + 4, tail.y - 4, paint) }
    }
    for (ml in model.memberLoads) {
        if (ml.dir != LoadDir.GLOBAL_Z || ml.w == 0.0) continue
        val m = model.members.firstOrNull { it.id == ml.member } ?: continue
        val a = model.node(m.i).pos
        val b = model.node(m.j).pos
        val down = if (ml.w < 0) 1f else -1f
        for (k in 1..5) {
            val p = pj.screen(a + (b - a) * (k / 6.0))
            drawArrow(Offset(p.x, p.y - 26f * down), p, c.copy(alpha = 0.7f), 1.6f)
        }
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, c: Color, w: Float) {
    drawLine(c, from, to, w)
    val d = to - from
    val l = d.getDistance().coerceAtLeast(1e-3f)
    val u = Offset(d.x / l, d.y / l)
    val n = Offset(-u.y, u.x)
    val h = 7f + w * 2
    drawLine(c, to, to - u * h + n * (h * 0.5f), w)
    drawLine(c, to, to - u * h - n * (h * 0.5f), w)
}

private fun DrawScope.legend(maxD: Double) {
    val x0 = size.width - 150f
    val y0 = 16f
    for (k in 0 until 20) drawRect(heat(k / 19f), Offset(x0 + k * 6f, y0), Size(6f, 14f))
    val paint = Paint().apply { textSize = 22f; isAntiAlias = true; color = android.graphics.Color.DKGRAY }
    drawIntoCanvas {
        it.nativeCanvas.drawText("0", x0, y0 + 36f, paint)
        it.nativeCanvas.drawText("%.2f mm".format(maxD * 1000), x0 + 58f, y0 + 36f, paint)
    }
}
