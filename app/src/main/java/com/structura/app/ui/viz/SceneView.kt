package com.structura.app.ui.viz

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.structura.core.viz.Scene3D
import com.structura.core.viz.Scene3D.Load
import com.structura.core.viz.Scene3D.Mat
import com.structura.core.viz.Scene3D.V
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val Ground = Color(0xFF0E1726)
private val Ink = Color(0xFFE6EEF6)
private val Muted = Color(0xFF8BA3B8)
private val Cyan = Color(0xFF3DD6F5)
private val Orange = Color(0xFFF5A524)
private val Line = Color(0xFF1F3550)

/** Black-and-white rendering (main space). */
private var sceneMono = false

private fun matColor(m: Mat): Color = if (sceneMono) when (m) {
    Mat.CONCRETE -> Color(0xFFD9D9D9); Mat.SOIL -> Color(0xFFBDBDBD); Mat.GROUND -> Color(0xFF9E9E9E)
    Mat.MASONRY -> Color(0xFFBFBFBF); Mat.STEEL -> Color(0xFF8A8A8A); Mat.SHEET -> Color(0xFFCFCFCF)
} else when (m) {
    Mat.CONCRETE -> Color(0xFFB8C4CC)
    Mat.SOIL -> Color(0xFF9C7A54)
    Mat.GROUND -> Color(0xFF5E4630)
    Mat.MASONRY -> Color(0xFFC0664A)
    Mat.STEEL -> Color(0xFF90A4AE)
    Mat.SHEET -> Color(0xFF7FB3E0)
}

private fun loadColor(k: Load?): Color = if (sceneMono) Color(0xFF222222) else when (k) {
    Load.EARTH -> Color(0xFFF5A524)
    Load.SURCHARGE -> Color(0xFFFFD54F)
    Load.BEARING -> Color(0xFF3DD6F5)
    Load.GRAVITY -> Color(0xFFFF7043)
    Load.WIND -> Color(0xFF80DEEA)
    null -> Muted
}

/** Layers the viewer can switch off. */
enum class SceneLayer(val label: String) { SOIL("Soil"), CONCRETE("Concrete"), STEEL("Steel"), LOADS("Loads"), DIMS("Labels") }

private fun layerOf(it: Scene3D.Item): SceneLayer = when (it) {
    is Scene3D.Prism -> if (it.mat == Mat.SOIL || it.mat == Mat.GROUND) SceneLayer.SOIL else SceneLayer.CONCRETE
    is Scene3D.Face -> if (it.mat == Mat.SOIL || it.mat == Mat.GROUND) SceneLayer.SOIL else SceneLayer.CONCRETE
    is Scene3D.Solid -> if (it.mat == Mat.SOIL || it.mat == Mat.GROUND) SceneLayer.SOIL else SceneLayer.CONCRETE
    is Scene3D.Bar -> SceneLayer.STEEL
    is Scene3D.Arrow -> SceneLayer.LOADS
    is Scene3D.Line -> SceneLayer.LOADS
    is Scene3D.Label, is Scene3D.Dim -> SceneLayer.DIMS
}

class SceneCamera {
    var yaw by mutableFloatStateOf(-38f)
    var pitch by mutableFloatStateOf(22f)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    fun set(y: Float, p: Float) { yaw = y; pitch = p; zoom = 1f; panX = 0f; panY = 0f }
}

/** Drawable primitive after projection, sorted far → near. */
private sealed class Prim(val depth: Double) {
    class Poly(val pts: List<Offset>, val fill: Color, val edge: Color, d: Double) : Prim(d)
    class Seg(val a: Offset, val b: Offset, val color: Color, val width: Float, d: Double, val dashed: Boolean = false, val head: Boolean = false) : Prim(d)
    class Txt(val at: Offset, val text: String, val color: Color, val size: Float, val bold: Boolean, d: Double) : Prim(d)
}

/**
 * Animated 3D view of a designed element: the [scene] builds up stage by stage (excavation → concrete → backfill →
 * pressures → reinforcement → dimensions), then turns slowly until touched. One finger rotates, two fingers pan and
 * zoom, double-tap re-centres. Concrete turns translucent ("X-ray") once the reinforcement is placed.
 */
@Composable
fun SceneView(scene: Scene3D, modifier: Modifier = Modifier, fullScreen: Boolean = false, onFullScreen: (() -> Unit)? = null, mono: Boolean = false) {
    sceneMono = mono
    val bg = if (mono) Color.White else Ground
    val ink = if (mono) Color(0xFF111111) else Ink
    val accent = if (mono) Color(0xFF111111) else Cyan
    val cam = remember(scene) { SceneCamera() }
    val stagesN = scene.stages.size
    val progress = remember(scene) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var hidden by remember { mutableStateOf(setOf<SceneLayer>()) }
    var xray by remember { mutableStateOf<Boolean?>(null) }         // null = automatic
    var spin by remember(scene) { mutableStateOf(true) }
    fun play(from: Float = 0f) {
        scope.launch { progress.snapTo(from); progress.animateTo(stagesN.toFloat(), tween(((stagesN - from) * 1100).toInt().coerceAtLeast(300), easing = LinearEasing)) }
    }
    LaunchedEffect(scene) { play() }
    LaunchedEffect(spin) {
        var last = 0L
        while (spin) withFrameNanos { t -> if (last != 0L && progress.value >= stagesN) cam.yaw += (t - last) / 1e9f * 7f; last = t }
    }
    val firstBarStage = scene.items.filterIsInstance<Scene3D.Bar>().filter { !it.steel }.minOfOrNull { it.stage }
    val p = progress.value
    val cur = floor(p).toInt().coerceAtMost(stagesN - 1)
    val autoXray = firstBarStage != null && p > firstBarStage
    val xr = xray ?: autoXray

    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(if (fullScreen || mono) 0.dp else 14.dp)).background(bg)
                .pointerInput(cam) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        spin = false
                        do {
                            val ev = awaitPointerEvent()
                            val fingers = ev.changes.count { it.pressed }
                            val pan = ev.calculatePan(); val z = ev.calculateZoom()
                            if (fingers >= 2) { cam.panX += pan.x; cam.panY += pan.y } else {
                                cam.yaw += pan.x * 0.4f; cam.pitch = (cam.pitch + pan.y * 0.4f).coerceIn(-89f, 89f)
                            }
                            if (z != 1f) cam.zoom = (cam.zoom * z).coerceIn(0.3f, 12f)
                            if (pan != Offset.Zero || z != 1f) ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(cam) { detectTapGestures(onDoubleTap = { cam.zoom = 1f; cam.panX = 0f; cam.panY = 0f }) },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawScene(scene, cam, p, hidden, xr) }
            // stage caption
            Column(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                Text(scene.title, color = ink, fontSize = 13.sp, maxLines = 1)
                Text("Step ${cur + 1}/$stagesN · ${scene.stages.getOrElse(cur) { "" }}", color = if (mono) ink else Orange, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (k in 0 until stagesN) Box(Modifier.width(14.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (k < p) accent else if (mono) Color(0xFFD0D0D0) else Line))
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onFullScreen != null) Chip(if (fullScreen) "✕" else "⛶", false) { onFullScreen() }
            }
        }
        // controls
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = if (fullScreen) 8.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("▶ Replay", false) { spin = true; play() }
            Chip("⏮", false) { scope.launch { progress.stop(); progress.snapTo(max(0f, floor(p - 0.001f))) } }
            Chip("⏭", false) { scope.launch { progress.stop(); progress.animateTo(min(stagesN.toFloat(), floor(p) + 1f), tween(700)) } }
            Chip("3D", false) { cam.set(-38f, 22f) }
            Chip("Section", false) { spin = false; cam.set(0f, 0f) }
            Chip("Plan", false) { spin = false; cam.set(0f, 89f) }
            Chip(if (xr) "X-ray" else "Solid", xr) { xray = !xr }
            Chip(if (spin) "⏸ Spin" else "⟳ Spin", spin) { spin = !spin }
            SceneLayer.entries.forEach { l -> Chip(l.label, l !in hidden) { hidden = if (l in hidden) hidden - l else hidden + l } }
        }
    }
}

/** Card with the scene and a full-screen dialog. */
@Composable
fun SceneCard(scene: Scene3D, modifier: Modifier = Modifier, mono: Boolean = true) {
    var full by remember { mutableStateOf(false) }
    SceneView(scene, modifier, onFullScreen = { full = true }, mono = mono)
    if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SceneView(scene, Modifier.fillMaxSize().background(if (mono) Color.White else Ground), fullScreen = true, onFullScreen = { full = false }, mono = mono)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val m = sceneMono
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (m) (if (selected) Color(0xFF111111) else Color.White) else if (selected) Cyan.copy(alpha = 0.14f) else Ground)
            .border(BorderStroke(1.dp, if (m) Color(0xFF111111) else if (selected) Cyan else Line), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (m) (if (selected) Color.White else Color(0xFF111111)) else if (selected) Cyan else Ink, fontSize = 12.sp) }
}

private fun DrawScope.drawScene(scene: Scene3D, cam: SceneCamera, progress: Float, hidden: Set<SceneLayer>, xray: Boolean) {
    val cy = cos(Math.toRadians(cam.yaw.toDouble())); val sy = sin(Math.toRadians(cam.yaw.toDouble()))
    val cp = cos(Math.toRadians(cam.pitch.toDouble())); val spp = sin(Math.toRadians(cam.pitch.toDouble()))
    val (lo, hi) = scene.bounds
    val c = V((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2)
    fun sx(v: V) = (v.x - c.x) * cy + (v.y - c.y) * sy
    fun dep(v: V) = -(v.x - c.x) * sy + (v.y - c.y) * cy
    fun up(v: V) = (v.z - c.z) * cp + dep(v) * spp
    fun far(v: V) = dep(v) * cp - (v.z - c.z) * spp                      // larger = further from the viewer
    // fit on the bounding box corners (stable while animating)
    val corners = listOf(lo.x, hi.x).flatMap { x -> listOf(lo.y, hi.y).flatMap { y -> listOf(lo.z, hi.z).map { z -> V(x, y, z) } } }
    val w = max(corners.maxOf { sx(it) } - corners.minOf { sx(it) }, corners.maxOf { up(it) } - corners.minOf { up(it) }).coerceAtLeast(0.5)
    val scale = (min(size.width, size.height) * 0.80 / w * cam.zoom)
    val ox = size.width / 2 + cam.panX; val oy = size.height / 2 + cam.panY + size.height * 0.04f
    fun scr(v: V) = Offset((ox + sx(v) * scale).toFloat(), (oy - up(v) * scale).toFloat())
    val light = V(-0.4, -0.5, 0.75)
    val viewDir = V(-sy * cp, cy * cp, -spp)

    val prims = ArrayList<Prim>(4096)
    for (it in scene.items) {
        if (layerOf(it) in hidden) continue
        val f = (progress - it.stage).coerceIn(0f, 1f).toDouble()
        if (f <= 0.0) continue
        when (it) {
            is Scene3D.Prism -> {
                val zmin = it.section.minOf { s -> s.second }
                val sec = it.section.map { (x, z) -> x to zmin + (z - zmin) * f }       // rises from its base
                val base = matColor(it.mat)
                val alpha = when (it.mat) {
                    Mat.SOIL -> 0.30; Mat.GROUND -> 0.55
                    Mat.CONCRETE, Mat.MASONRY -> if (xray) 0.22 else 0.88
                    else -> 0.6
                } * min(1.0, f * 2.5)
                val faces = ArrayList<List<V>>()
                faces += sec.map { (x, z) -> V(x, it.y0, z) }
                faces += sec.map { (x, z) -> V(x, it.y1, z) }
                for (k in sec.indices) {
                    val a = sec[k]; val b = sec[(k + 1) % sec.size]
                    faces += listOf(V(a.first, it.y0, a.second), V(b.first, it.y0, b.second), V(b.first, it.y1, b.second), V(a.first, it.y1, a.second))
                }
                for (fc in faces) addFace(prims, fc, base, alpha, light, viewDir, ::scr, ::far)
            }
            is Scene3D.Solid -> {
                val all = it.faces.flatten()
                val zmin = all.minOf { q -> q.z }
                val alpha = when (it.mat) {
                    Mat.SOIL -> 0.30; Mat.GROUND -> if (sceneMono) 0.35 else 0.55
                    Mat.CONCRETE, Mat.MASONRY -> if (xray) 0.22 else 0.88
                    else -> 0.75
                } * min(1.0, f * 2.5)
                for (fc in it.faces) addFace(prims, fc.map { q -> V(q.x, q.y, zmin + (q.z - zmin) * f) }, matColor(it.mat), alpha, light, viewDir, ::scr, ::far)
            }
            is Scene3D.Face -> {
                val drop = (1 - f) * 0.8
                addFace(prims, it.pts.map { q -> q + V(0.0, 0.0, drop) }, matColor(it.mat), 0.45 * f, light, viewDir, ::scr, ::far)
            }
            is Scene3D.Bar -> {
                val pts = partial(it.pts, f)
                val col = if (sceneMono) (if (it.steel) Color(0xFF444444) else if (it.main) Color(0xFF000000) else Color(0xFF666666))
                    else if (it.steel) Color(0xFFCFD8DC) else if (it.main) Color(0xFFE53935) else Color(0xFFFF8A65)
                val wpx = if (it.steel) max(2.5f, (it.dia / 1000 * scale).toFloat().coerceAtMost(7f)) else max(1.4f, (it.dia / 1000 * scale * 1.3).toFloat().coerceAtMost(6f))
                for (k in 1 until pts.size) prims += Prim.Seg(scr(pts[k - 1]), scr(pts[k]), col, wpx, (far(pts[k - 1]) + far(pts[k])) / 2 - 0.01)
            }
            is Scene3D.Arrow -> {
                val tip = it.from + (it.to - it.from) * f
                prims += Prim.Seg(scr(it.from), scr(tip), loadColor(it.kind), 2.6f, -1e3, head = f > 0.6)
            }
            is Scene3D.Line -> {
                val b = it.a + (it.b - it.a) * f
                prims += Prim.Seg(scr(it.a), scr(b), loadColor(it.kind), 2f, -1e3, dashed = it.dashed)
            }
            is Scene3D.Label -> prims += Prim.Txt(scr(it.at), it.text, (if (sceneMono) Color(0xFF111111) else Ink).copy(alpha = f.toFloat()), if (it.strong) 27f else 23f, it.strong, -2e3)
            is Scene3D.Dim -> {
                val a = scr(it.a); val b = scr(it.b)
                val col = (if (sceneMono) Color(0xFF111111) else Cyan).copy(alpha = f.toFloat())
                prims += Prim.Seg(a, b, col, 1.6f, -1.5e3)
                val d = b - a; val l = d.getDistance().coerceAtLeast(1e-3f); val n = Offset(-d.y / l, d.x / l) * 9f
                prims += Prim.Seg(a - n, a + n, col, 1.6f, -1.5e3); prims += Prim.Seg(b - n, b + n, col, 1.6f, -1.5e3)
                prims += Prim.Txt((a + b) / 2f + n * 1.6f, it.text, col, 24f, false, -2e3)
            }
        }
    }
    prims.sortByDescending { it.depth }
    val paint = Paint().apply { isAntiAlias = true }
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    for (pr in prims) when (pr) {
        is Prim.Poly -> {
            val path = Path().apply { moveTo(pr.pts[0].x, pr.pts[0].y); for (k in 1 until pr.pts.size) lineTo(pr.pts[k].x, pr.pts[k].y); close() }
            drawPath(path, pr.fill); drawPath(path, pr.edge, style = Stroke(1f))
        }
        is Prim.Seg -> {
            drawLine(pr.color, pr.a, pr.b, pr.width, pathEffect = if (pr.dashed) dash else null)
            if (pr.head) {
                val d = pr.b - pr.a; val l = d.getDistance()
                if (l > 4f) {
                    val u = d / l; val n = Offset(-u.y, u.x); val h = 10f
                    drawLine(pr.color, pr.b, pr.b - u * h + n * (h * 0.55f), pr.width); drawLine(pr.color, pr.b, pr.b - u * h - n * (h * 0.55f), pr.width)
                }
            }
        }
        is Prim.Txt -> drawIntoCanvas { cv ->
            paint.textSize = pr.size; paint.color = pr.color.toArgb()
            paint.typeface = if (pr.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (sceneMono) paint.setShadowLayer(3f, 0f, 0f, android.graphics.Color.WHITE) else paint.setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            cv.nativeCanvas.drawText(pr.text, pr.at.x + 4, pr.at.y - 4, paint)
        }
    }
}

private fun addFace(out: ArrayList<Prim>, pts: List<V>, base: Color, alpha: Double, light: V, viewDir: V,
                    scr: (V) -> Offset, far: (V) -> Double) {
    if (pts.size < 3 || alpha <= 0.01) return
    // Newell normal
    var nx = 0.0; var ny = 0.0; var nz = 0.0
    for (k in pts.indices) { val a = pts[k]; val b = pts[(k + 1) % pts.size]; nx += (a.y - b.y) * (a.z + b.z); ny += (a.z - b.z) * (a.x + b.x); nz += (a.x - b.x) * (a.y + b.y) }
    val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12)
    val ll = sqrt(light.x * light.x + light.y * light.y + light.z * light.z)
    val lam = abs((nx * light.x + ny * light.y + nz * light.z) / (nl * ll))
    val k = (0.50 + 0.50 * lam).toFloat()
    val fill = Color(base.red * k, base.green * k, base.blue * k, alpha.toFloat())
    val d = pts.sumOf { far(it) } / pts.size
    out += Prim.Poly(pts.map(scr), fill, (if (sceneMono) Color(0xFF000000) else Color(0xFF0B1320)).copy(alpha = (0.5 * alpha).toFloat().coerceAtMost(0.5f)), d)
}

/** First fraction [f] of a polyline by length. */
private fun partial(pts: List<V>, f: Double): List<V> {
    if (f >= 1.0 || pts.size < 2) return pts
    val segs = (1 until pts.size).map { val d = pts[it] - pts[it - 1]; hypot(hypot(d.x, d.y), d.z) }
    var left = segs.sum() * f
    val out = arrayListOf(pts[0])
    for (k in segs.indices) {
        if (left >= segs[k]) { out += pts[k + 1]; left -= segs[k] } else { out += pts[k] + (pts[k + 1] - pts[k]) * (left / segs[k].coerceAtLeast(1e-12)); break }
    }
    return out
}
