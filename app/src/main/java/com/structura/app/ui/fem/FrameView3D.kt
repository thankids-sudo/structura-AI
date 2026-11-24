package com.structura.app.ui.fem

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameResults
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

/** Orbit view of a frame model: drag to rotate, pinch to zoom. Diagrams drawn RFEM-style on local axes. */
@Composable
fun FrameView3D(
    model: FrameModel,
    results: FrameResults?,
    view: ResultView,
    modifier: Modifier = Modifier,
) {
    var yaw by remember { mutableFloatStateOf(-35f) }
    var pitch by remember { mutableFloatStateOf(25f) }
    var zoom by remember { mutableFloatStateOf(1f) }

    val ink = Color(0xFF1B2A41)
    val pos = Color(0xFFC62828)
    val neg = Color(0xFF1565C0)
    val deformedColor = Color(0xFFB4532A)

    Box(
        modifier
            .background(Color.White)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    yaw += pan.x * 0.4f
                    pitch = (pitch + pan.y * 0.4f).coerceIn(-89f, 89f)
                    zoom = (zoom * z).coerceIn(0.3f, 8f)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = cos(Math.toRadians(yaw.toDouble())); val sy = sin(Math.toRadians(yaw.toDouble()))
            val cp = cos(Math.toRadians(pitch.toDouble())); val sp = sin(Math.toRadians(pitch.toDouble()))
            fun proj(p: Vec3): Pair<Double, Double> {
                val sx = p.x * cy + p.y * sy
                val depth = -p.x * sy + p.y * cy
                val up = p.z * cp + depth * sp
                return sx to up
            }
            // fit
            val pts = model.nodes.map { proj(it.pos) }
            val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
            val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
            val span = max(maxX - minX, maxY - minY).coerceAtLeast(1.0)
            val scale = (min(size.width, size.height) * 0.70 / span * zoom)
            val cx = (minX + maxX) / 2; val cyy = (minY + maxY) / 2
            fun screen(p: Vec3): Offset {
                val (x, y) = proj(p)
                return Offset(
                    (size.width / 2 + (x - cx) * scale).toFloat(),
                    (size.height / 2 - (y - cyy) * scale).toFloat(),
                )
            }
            val modelSize = model.nodes.let { ns ->
                val xs = ns.map { it.x }; val ys = ns.map { it.y }; val zs = ns.map { it.z }
                maxOf(xs.max() - xs.min(), ys.max() - ys.min(), zs.max() - zs.min()).coerceAtLeast(1.0)
            }

            // global axes triad
            val o = Offset(40f, size.height - 40f)
            fun axis(v: Vec3, c: Color, label: String) {
                val (x, y) = proj(v)
                val e = Offset(o.x + (x * 30).toFloat(), o.y - (y * 30).toFloat())
                drawLine(c, o, e, 3f)
                drawIntoCanvas { it.nativeCanvas.drawText(label, e.x + 3, e.y, Paint().apply { color = c.toArgb(); textSize = 28f }) }
            }
            axis(Vec3.X, Color.Red, "X"); axis(Vec3.Y, Color(0xFF2E7D32), "Y"); axis(Vec3.Z, Color.Blue, "Z")

            // members
            val faded = view != ResultView.MODEL
            for (m in model.members) {
                drawLine(
                    if (faded) ink.copy(alpha = 0.35f) else ink,
                    screen(model.node(m.i).pos), screen(model.node(m.j).pos), if (faded) 2f else 4f,
                )
            }
            // supports
            for (s in model.supports) {
                val p = screen(model.node(s.node).pos)
                val fixedAll = s.restraints.all { it }
                val path = Path().apply {
                    moveTo(p.x, p.y); lineTo(p.x - 14f, p.y + 22f); lineTo(p.x + 14f, p.y + 22f); close()
                }
                if (fixedAll) drawRect(Color(0xFF2E7D32), Offset(p.x - 12f, p.y), Size(24f, 14f))
                else drawPath(path, Color(0xFF2E7D32), style = Stroke(3f))
            }
            for (n in model.nodes) drawCircle(ink, 4f, screen(n.pos))

            if (results == null) return@Canvas

            when (view) {
                ResultView.MODEL -> {}
                ResultView.DEFORMED -> {
                    val maxD = results.maxMemberDeflection().coerceAtLeast(results.maxDisplacement().second)
                    val k = if (maxD > 1e-12) 0.08 * modelSize / maxD else 1.0
                    for (m in model.members) {
                        val pth = results.deformedShape(m.id, k, 16).map { screen(it) }
                        for (a in 1 until pth.size) drawLine(deformedColor, pth[a - 1], pth[a], 3.5f)
                    }
                }
                else -> {
                    val stations = model.members.associate { it.id to results.internalForces(it.id, 20) }
                    val maxV = stations.values.flatten().maxOfOrNull { abs(value(it, view)) } ?: 0.0
                    if (maxV < 1e-9) return@Canvas
                    val k = 0.12 * modelSize / maxV
                    val paint = Paint().apply { textSize = 26f; isAntiAlias = true }
                    for (m in model.members) {
                        val ax = results.memberAxes[m.id]!!
                        val p0 = model.node(m.i).pos
                        val dir = if (view == ResultView.VY || view == ResultView.MZ) ax.y else ax.z
                        val st = stations[m.id]!!
                        val base = st.map { p0 + ax.x * it.s }
                        val tip = st.map { p0 + ax.x * it.s + dir * (value(it, view) * k) }
                        for (a in 1 until st.size) {
                            val v = value(st[a], view)
                            val c = if (v >= 0) pos else neg
                            drawLine(c, screen(tip[a - 1]), screen(tip[a]), 2.5f)
                            drawLine(c.copy(alpha = 0.35f), screen(base[a]), screen(tip[a]), 1.2f)
                        }
                        // label extreme value on this member
                        val ext = st.indices.maxByOrNull { abs(value(st[it], view)) }!!
                        val ev = value(st[ext], view)
                        if (abs(ev) > 0.05 * maxV) {
                            val sp0 = screen(tip[ext])
                            paint.color = (if (ev >= 0) pos else neg).toArgb()
                            drawIntoCanvas { it.nativeCanvas.drawText("%.1f".format(ev), sp0.x + 4, sp0.y - 4, paint) }
                        }
                    }
                }
            }
        }
    }
}
