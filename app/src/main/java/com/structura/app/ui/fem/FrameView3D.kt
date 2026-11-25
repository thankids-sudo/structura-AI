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
import com.structura.core.fem.BucklingResult
import com.structura.core.fem.FrameModel
import com.structura.core.fem.Node
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
    MODEL("Model"), DEFORMED("Deformed"), N("N"), VZ("Vz"), MY("My"), VY("Vy"), MZ("Mz"), BUCKLING("Buckling mode"), MODAL("Mode shape")
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
class Projector(
    model: FrameModel, yawDeg: Float, pitchDeg: Float, zoom: Float,
    private val w: Float, private val h: Float, extra: List<Vec3> = emptyList(),
) {
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
        val all = model.nodes.map { it.pos } + extra
        val src = all.ifEmpty { listOf(Vec3.ZERO, Vec3(6.0, 6.0, 3.0)) }
        val pts = src.map { proj(it) }
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1.0)
        scale = min(w, h) * 0.62 / span * zoom
        cx = (minX + maxX) / 2; cyy = (minY + maxY) / 2
        val xs = src.map { it.x }; val ys = src.map { it.y }; val zs = src.map { it.z }
        modelSize = maxOf(xs.max() - xs.min(), ys.max() - ys.min(), zs.max() - zs.min()).coerceAtLeast(1.0)
    }

    /** Screen point → world point on a work plane (null if the plane is seen edge-on). */
    fun onPlane(s: Offset, plane: Plane, offset: Double): Vec3? {
        val sx = (s.x - w / 2) / scale + cx
        val up = -(s.y - h / 2) / scale + cyy
        return when (plane) {
            Plane.XY -> {
                if (abs(sp) < 0.08) return null
                val depth = (up - offset * cp) / sp
                Vec3(sx * cy - depth * sy, sx * sy + depth * cy, offset)
            }
            Plane.XZ -> {
                if (abs(cy) < 0.08 || abs(cp) < 0.08) return null
                val x = (sx - offset * sy) / cy
                val depth = -x * sy + offset * cy
                Vec3(x, offset, (up - depth * sp) / cp)
            }
            Plane.YZ -> {
                if (abs(sy) < 0.08 || abs(cp) < 0.08) return null
                val y = (sx - offset * cy) / sy
                val depth = -offset * sy + y * cy
                Vec3(offset, y, (up - depth * sp) / cp)
            }
        }
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

enum class Plane(val label: String) { XY("XY (plan)"), XZ("XZ (front)"), YZ("YZ (side)") }

/** Work plane for placing nodes: plane, offset along its normal, snap spacing (m). */
data class WorkPlane(val plane: Plane = Plane.XY, val offset: Double = 0.0, val snap: Double = 1.0, val visible: Boolean = true)

/** What a tap hit: nearest node / member within reach, and the snapped point on the work plane. */
data class TapInfo(val node: Int?, val member: Int?, val planePoint: Vec3?, val panel: Int? = null)

/** Orbit camera, hoistable so screens can set Top / Front / Right presets. */
class Camera(yaw: Float = -35f, pitch: Float = 25f) {
    var yaw by mutableFloatStateOf(yaw)
    var pitch by mutableFloatStateOf(pitch)
    var zoom by mutableFloatStateOf(1f)

    fun set(y: Float, p: Float) { yaw = y; pitch = p; zoom = 1f }
    fun iso() = set(-35f, 25f)
    fun top() = set(0f, 90f)
    fun front() = set(0f, 0f)
    fun right() = set(90f, 0f)
}

/** Grid extents on the work plane: model bounds plus a margin, at least 6 m × 6 m. */
fun gridCorners(model: FrameModel, wp: WorkPlane): Pair<Vec3, Vec3> {
    val ns = model.nodes
    fun range(sel: (Node) -> Double): Pair<Double, Double> {
        if (ns.isEmpty()) return 0.0 to 6.0
        val lo = ns.minOf(sel) - 2 * wp.snap
        val hi = ns.maxOf(sel) + 2 * wp.snap
        val c = (lo + hi) / 2
        val half = max((hi - lo) / 2, 3.0)
        return ModelEditorSnap.floor(c - half, wp.snap) to ModelEditorSnap.ceil(c + half, wp.snap)
    }
    val (x0, x1) = range { it.x }; val (y0, y1) = range { it.y }; val (z0, z1) = range { it.z }
    return when (wp.plane) {
        Plane.XY -> Vec3(x0, y0, wp.offset) to Vec3(x1, y1, wp.offset)
        Plane.XZ -> Vec3(x0, wp.offset, minOf(z0, 0.0)) to Vec3(x1, wp.offset, z1)
        Plane.YZ -> Vec3(wp.offset, y0, minOf(z0, 0.0)) to Vec3(wp.offset, y1, z1)
    }
}

private object ModelEditorSnap {
    fun floor(v: Double, s: Double) = if (s > 0) kotlin.math.floor(v / s) * s else v
    fun ceil(v: Double, s: Double) = if (s > 0) kotlin.math.ceil(v / s) * s else v
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
    camera: Camera = remember { Camera() },
    workPlane: WorkPlane? = null,
    selectedMember: Int? = null,
    pendingNode: Int? = null,
    buckling: BucklingResult? = null,
    onTap: ((TapInfo) -> Unit)? = null,
    modeShape: Map<Int, List<Pair<Vec3, Vec3>>>? = null,
    modeLabel: String = "",
    selectedPanel: Int? = null,
    pendingNodes: List<Int> = emptyList(),
    /** Member id → design utilisation (IS 800); colours members in the MODEL view when not null. */
    memberUtil: Map<Int, Double>? = null,
) {
    var canvasSize by remember { mutableStateOf(Size(1f, 1f)) }
    val tapCallback by rememberUpdatedState(onNodeTap)
    val tapInfoCallback by rememberUpdatedState(onTap)
    val wpState by rememberUpdatedState(workPlane)
    val modelState by rememberUpdatedState(model)
    fun extras(m: FrameModel): List<Vec3> = workPlane?.let { wp -> gridCorners(m, wp).toList() } ?: emptyList()

    val grow = remember { Animatable(1f) }
    LaunchedEffect(results) {
        if (results != null) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    }
    val osc by rememberInfiniteTransition(label = "osc").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "phase",
    )
    val phase = grow.value * (if (animate && (view == ResultView.DEFORMED || view == ResultView.BUCKLING || view == ResultView.MODAL)) osc else 1f)

    val ink = Color(0xFF1B2A41)
    val pos = Color(0xFFC62828)
    val neg = Color(0xFF1565C0)
    val loadColor = Color(0xFFEF6C00)

    Box(
        modifier
            .background(Color.White)
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(camera) {
                detectTransformGestures { _, pan, z, _ ->
                    camera.yaw += pan.x * 0.4f
                    camera.pitch = (camera.pitch + pan.y * 0.4f).coerceIn(-90f, 90f)
                    camera.zoom = (camera.zoom * z).coerceIn(0.3f, 8f)
                }
            }
            .pointerInput(camera) {
                detectTapGestures { tap ->
                    val m = modelState
                    val wp = wpState
                    val pj = Projector(m, camera.yaw, camera.pitch, camera.zoom, canvasSize.width, canvasSize.height,
                        wp?.let { gridCorners(m, it).toList() } ?: emptyList())
                    val hit = m.nodes.minByOrNull { (pj.screen(it.pos) - tap).getDistance() }
                    val nodeHit = if (hit != null && (pj.screen(hit.pos) - tap).getDistance() < 48f) hit.id else null
                    tapCallback?.invoke(nodeHit)
                    val info = tapInfoCallback ?: return@detectTapGestures
                    val memberHit = if (nodeHit != null) null else m.members.map { mem ->
                        mem.id to segmentDistance(tap, pj.screen(m.node(mem.i).pos), pj.screen(m.node(mem.j).pos))
                    }.filter { it.second < 30f }.minByOrNull { it.second }?.first
                    val panelHit = if (nodeHit != null || memberHit != null) null else m.panels.firstOrNull { p ->
                        pointInPolygon(tap, p.nodes.map { pj.screen(m.node(it).pos) })
                    }?.id
                    val pp = wp?.let { w ->
                        pj.onPlane(tap, w.plane, w.offset)?.let { p ->
                            Vec3(snap(p.x, w.snap), snap(p.y, w.snap), snap(p.z, w.snap))
                        }
                    }
                    info(TapInfo(nodeHit, memberHit, pp, panelHit))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pj = Projector(model, camera.yaw, camera.pitch, camera.zoom, size.width, size.height, extras(model))
            val modelSize = pj.modelSize
            workPlane?.takeIf { it.visible }?.let { grid(pj, model, it) }
            axesTriad(pj)

            val faded = view != ResultView.MODEL
            for (p in model.panels) {
                val poly = p.nodes.map { pj.screen(model.node(it).pos) }
                val path = Path().apply { moveTo(poly[0].x, poly[0].y); for (q in 1 until 4) lineTo(poly[q].x, poly[q].y); close() }
                val sel = p.id == selectedPanel
                drawPath(path, if (sel) Color(0x55FFA000) else Color(0x332196F3))
                drawPath(path, if (sel) Color(0xFFFFA000) else Color(0xFF90A4AE), style = Stroke(if (sel) 3f else 1.2f))
                if (p.pressure != 0.0 && view == ResultView.MODEL) {
                    val c = p.nodes.map { model.node(it).pos }.reduce { a, b -> a + b } * 0.25
                    val nrm = ((model.node(p.nodes[2]).pos - model.node(p.nodes[0]).pos) cross (model.node(p.nodes[3]).pos - model.node(p.nodes[1]).pos)).unit()
                    val dir = pj.pixelDir(nrm * (if (p.pressure > 0) 1.0 else -1.0))
                    val dl = dir.getDistance().coerceAtLeast(1e-3f)
                    val tip = pj.screen(c)
                    drawArrow(tip - Offset(dir.x / dl, dir.y / dl) * 34f, tip, loadColor.copy(alpha = 0.8f), 2f)
                }
            }
            for (m in model.members) {
                val sel = m.id == selectedMember
                val u = if (!faded) memberUtil?.get(m.id) else null
                drawLine(
                    if (sel) Color(0xFFFFA000) else if (u != null) (if (u > 1.0) Color(0xFFD50000) else heat(u.toFloat())) else if (faded) ink.copy(alpha = 0.25f) else ink,
                    pj.screen(model.node(m.i).pos), pj.screen(model.node(m.j).pos), if (sel) 6f else if (u != null) 7f else if (faded) 2f else 4f,
                )
                if (m.hingeI || m.hingeJ) {
                    val a = model.node(m.i).pos; val b = model.node(m.j).pos
                    if (m.hingeI) drawCircle(Color.White, 6f, pj.screen(a + (b - a) * 0.06), style = androidx.compose.ui.graphics.drawscope.Fill)
                    if (m.hingeI) drawCircle(ink, 6f, pj.screen(a + (b - a) * 0.06), style = Stroke(2f))
                    if (m.hingeJ) drawCircle(Color.White, 6f, pj.screen(a + (b - a) * 0.94), style = androidx.compose.ui.graphics.drawscope.Fill)
                    if (m.hingeJ) drawCircle(ink, 6f, pj.screen(a + (b - a) * 0.94), style = Stroke(2f))
                }
            }
            supports(pj, model)
            if (!faded && memberUtil != null) utilLegend()
            for (n in model.nodes) {
                val p = pj.screen(n.pos)
                if (n.id == selectedNode || n.id == pendingNode || n.id in pendingNodes) drawCircle(Color(0xFFFFA000), 16f, p, style = Stroke(4f))
                drawCircle(ink, 4.5f, p)
            }
            loads(pj, model, loadColor)

            if (view == ResultView.BUCKLING || view == ResultView.MODAL) {
                val shape = (if (view == ResultView.MODAL) modeShape else buckling?.modeShape) ?: return@Canvas
                val label = if (view == ResultView.MODAL) modeLabel else buckling?.let { "λcr = ${"%.3f".format(it.lambda)}  (${it.combination})" } ?: ""
                val k = 0.12 * modelSize * (if (view == ResultView.MODAL && animate) (2 * osc - 1) * grow.value else phase)
                for ((_, pts) in shape) {
                    val poly = pts.map { (p, d) -> pj.screen(p + d * k) }
                    for (a in 1 until poly.size) {
                        val t = ((pts[a].second.length + pts[a - 1].second.length) / 2).toFloat()
                        drawLine(heat(t), poly[a - 1], poly[a], 5f)
                    }
                }
                drawIntoCanvas {
                    it.nativeCanvas.drawText(
                        label, 16f, 36f,
                        Paint().apply { textSize = 30f; isAntiAlias = true; color = android.graphics.Color.BLACK },
                    )
                }
                return@Canvas
            }

            if (results == null) return@Canvas

            when (view) {
                ResultView.MODEL, ResultView.BUCKLING, ResultView.MODAL -> {}
                ResultView.DEFORMED -> {
                    val maxD = max(results.maxDisplacement().second, results.maxMemberDeflection())
                    val k = if (deformScale > 0) deformScale else if (maxD > 1e-12) 0.08 * modelSize / maxD else 1.0
                    val kk = k * phase
                    val colourMax = maxD.coerceAtLeast(1e-12)
                    for (p in model.panels) {
                        val disp = p.nodes.map { id -> results.nodeDisplacements[id]!!.let { d -> Vec3(d[0], d[1], d[2]) } }
                        val poly = p.nodes.mapIndexed { q, id -> pj.screen(model.node(id).pos + disp[q] * kk) }
                        val mean = disp.sumOf { it.length } / 4
                        val path = Path().apply { moveTo(poly[0].x, poly[0].y); for (q in 1 until 4) lineTo(poly[q].x, poly[q].y); close() }
                        drawPath(path, heat((mean / colourMax).toFloat()).copy(alpha = 0.55f))
                        drawPath(path, Color(0x66000000), style = Stroke(1f))
                    }
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

private fun pointInPolygon(p: Offset, poly: List<Offset>): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y + 1e-9f) + a.x) inside = !inside
        j = i
    }
    return inside
}

private fun snap(v: Double, s: Double) = if (s > 0) Math.round(v / s) * s else v

private fun segmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val l2 = ab.x * ab.x + ab.y * ab.y
    if (l2 < 1e-6f) return (p - a).getDistance()
    val t = (((p - a).x * ab.x + (p - a).y * ab.y) / l2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

private fun DrawScope.grid(pj: Projector, model: FrameModel, wp: WorkPlane) {
    val (lo, hi) = gridCorners(model, wp)
    val c = Color(0xFFB0BEC5)
    val s = wp.snap.coerceAtLeast(0.1)
    fun lines(u0: Double, u1: Double, v0: Double, v1: Double, pt: (Double, Double) -> Vec3) {
        var u = u0
        var guard = 0
        while (u <= u1 + 1e-9 && guard++ < 200) { drawLine(c, pj.screen(pt(u, v0)), pj.screen(pt(u, v1)), 1f); u += s }
        var v = v0
        guard = 0
        while (v <= v1 + 1e-9 && guard++ < 200) { drawLine(c, pj.screen(pt(u0, v)), pj.screen(pt(u1, v)), 1f); v += s }
    }
    when (wp.plane) {
        Plane.XY -> lines(lo.x, hi.x, lo.y, hi.y) { u, v -> Vec3(u, v, wp.offset) }
        Plane.XZ -> lines(lo.x, hi.x, lo.z, hi.z) { u, v -> Vec3(u, wp.offset, v) }
        Plane.YZ -> lines(lo.y, hi.y, lo.z, hi.z) { u, v -> Vec3(wp.offset, u, v) }
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
    val maxQ = model.memberLoads.maxOfOrNull { maxOf(kotlin.math.abs(it.w), kotlin.math.abs(it.w2 ?: it.w)) } ?: 0.0
    for (ml in model.memberLoads) {
        if (ml.dir != LoadDir.GLOBAL_Z || maxQ == 0.0) continue
        val m = model.members.firstOrNull { it.id == ml.member } ?: continue
        val a = model.node(m.i).pos
        val b = model.node(m.j).pos
        val len = (b - a).length
        val s0 = (ml.a ?: 0.0).coerceIn(0.0, len); val s1 = (ml.b ?: len).coerceIn(s0, len)
        for (k in 0..5) {
            val s = s0 + (s1 - s0) * (k + 0.5) / 6
            val q = ml.w + ((ml.w2 ?: ml.w) - ml.w) * (k + 0.5) / 6
            if (kotlin.math.abs(q) < 1e-9) continue
            val p = pj.screen(a + (b - a) * (s / len))
            val h = (10f + 26f * (kotlin.math.abs(q) / maxQ).toFloat()) * (if (q < 0) 1f else -1f)
            drawArrow(Offset(p.x, p.y - h), p, c.copy(alpha = 0.7f), 1.6f)
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

private fun DrawScope.utilLegend() {
    val x0 = size.width - 150f
    val y0 = 16f
    for (k in 0 until 20) drawRect(heat(k / 19f), Offset(x0 + k * 6f, y0), Size(6f, 14f))
    drawRect(Color(0xFFD50000), Offset(x0 + 124f, y0), Size(14f, 14f))
    val paint = Paint().apply { textSize = 22f; isAntiAlias = true; color = android.graphics.Color.DKGRAY }
    drawIntoCanvas {
        it.nativeCanvas.drawText("η 0", x0, y0 + 36f, paint)
        it.nativeCanvas.drawText("1.0  >1", x0 + 72f, y0 + 36f, paint)
    }
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
