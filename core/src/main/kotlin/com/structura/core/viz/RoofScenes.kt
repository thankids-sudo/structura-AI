package com.structura.core.viz

import com.structura.core.fem.FrameModel
import com.structura.core.viz.Scene3D.Load
import com.structura.core.viz.Scene3D.Mat
import com.structura.core.viz.Scene3D.V
import com.structura.core.viz.Scene3D.Companion.along
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 3D scenes for the roof modules: a shed of trusses with purlins and sheeting, and the self-supported arch roof. */
object RoofScenes {
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    /** [model] is one truss in the x–z plane; it is repeated [bays] + 1 times at [spacing] m. */
    fun truss(model: FrameModel, spacing: Double, purlinSpacing: Double, eaves: Double, labels: Map<Int, String>, bays: Int = 3,
              gravity: Double = 0.0, uplift: Double = 0.0): Scene3D {
        val xs = model.nodes.map { it.x }; val x0 = xs.min(); val x1 = xs.max(); val span = x1 - x0
        val rise = model.nodes.maxOf { it.z }
        fun zTop(x: Double) = rise * (1 - abs(x - (x0 + span / 2)) / (span / 2))
        val top = model.nodes.filter { abs(it.z - zTop(it.x)) < 1e-6 }.sortedBy { it.x }
        val len = bays * spacing
        val ys = (0..bays).map { it * spacing }
        val e = eaves
        val sb = Scene3D.Builder("Roof truss shed — span ${f2(span)} m, trusses @ ${f2(spacing)} m")
        sb.stage("Columns")
        sb.prism(Scene3D.rect(x0 - 1.5, -0.2, x1 + 1.5, 0.0), -1.0, len + 1.0, Mat.GROUND)
        for (y in ys) for (x in listOf(x0, x1)) sb.prism(Scene3D.rect(x - 0.15, 0.0, x + 0.15, e), y - 0.15, y + 0.15, Mat.CONCRETE)
        sb.stage("Trusses")
        for (y in ys) for (m in model.members) {
            val a = model.node(m.i); val b = model.node(m.j)
            sb.member(V(a.x, y, a.z + e), V(b.x, y, b.z + e))
        }
        val mid = ys[ys.size / 2]
        for (m in model.members) labels[m.id]?.let { t ->
            val a = model.node(m.i); val b = model.node(m.j)
            if (m.id % 2 == 0 || model.members.size <= 12) sb.label(V((a.x + b.x) / 2, mid, (a.z + b.z) / 2 + e + 0.12), t)
        }
        sb.stage("Purlins")
        val purlinPts = ArrayList<V>()
        for (side in listOf(-1.0, 1.0)) {
            val slope = hypot(span / 2, rise)
            for (s in along(0.0, slope, purlinSpacing)) {
                val x = x0 + span / 2 + side * (span / 2) * (1 - s / slope); val z = zTop(x) + e
                purlinPts += V(x, 0.0, z)
                sb.member(V(x, -0.3, z + 0.08), V(x, len + 0.3, z + 0.08), 40.0)
            }
        }
        sb.stage("Roof sheeting")
        val ridge = x0 + span / 2
        sb.face(listOf(V(x0 - 0.4, -0.3, e - 0.4 * rise / (span / 2) + 0.15), V(ridge, -0.3, rise + e + 0.15), V(ridge, len + 0.3, rise + e + 0.15), V(x0 - 0.4, len + 0.3, e - 0.4 * rise / (span / 2) + 0.15)), Mat.SHEET)
        sb.face(listOf(V(ridge, -0.3, rise + e + 0.15), V(x1 + 0.4, -0.3, e - 0.4 * rise / (span / 2) + 0.15), V(x1 + 0.4, len + 0.3, e - 0.4 * rise / (span / 2) + 0.15), V(ridge, len + 0.3, rise + e + 0.15)), Mat.SHEET)
        sb.stage("Loads")
        val endY = len + 0.3
        for (n in top) {
            val p = V(n.x, endY, n.z + e + 0.2)
            if (gravity > 0) sb.arrow(p + V(0.0, 0.0, 1.0), p, Load.GRAVITY)
            if (uplift > 0) { val nx = if (n.x < ridge) -rise / (span / 2) else rise / (span / 2); val k = 0.9 / hypot(nx, 1.0); sb.arrow(p, p + V(nx * k, 0.0, k), Load.WIND) }
        }
        if (gravity > 0) sb.label(V(ridge, endY, rise + e + 1.4), "1.5(DL + LL) ${f2(gravity)} kN/m²", strong = true)
        if (uplift > 0) sb.label(V(x1, endY, rise + e + 0.9), "wind uplift ${f2(uplift)} kN/m²")
        sb.stage("Dimensions")
        sb.dim(V(x0, -0.6, e - 0.3), V(x1, -0.6, e - 0.3), "span ${f2(span)} m")
        sb.dim(V(x1 + 0.6, -0.6, e), V(x1 + 0.6, -0.6, e + rise), "rise ${f2(rise)} m")
        sb.dim(V(x1 + 0.6, 0.0, e + 0.2), V(x1 + 0.6, spacing, e + 0.2), "${f2(spacing)} m c/c")
        return sb.build()
    }

    /** Self-supported arch: the arch polyline of [model] (x–z) extruded along [len] m with corrugation ribs every [pitch] m. */
    fun arch(model: FrameModel, len: Double, pitch: Double, depth: Double, springing: Double, gravity: Double, labelProfile: String): Scene3D {
        val pts = model.nodes.sortedBy { it.x }.map { it.x to it.z + springing }
        val span = pts.last().first - pts.first().first; val rise = pts.maxOf { it.second } - springing
        val sb = Scene3D.Builder("Self-supported arch roof — span ${f2(span)} m")
        sb.stage("Ground and springing walls")
        sb.prism(Scene3D.rect(pts.first().first - 1.5, -0.2, pts.last().first + 1.5, 0.0), -0.8, len + 0.8, Mat.GROUND)
        for (x in listOf(pts.first().first, pts.last().first)) sb.prism(Scene3D.rect(x - 0.12, 0.0, x + 0.12, springing), 0.0, len, Mat.MASONRY)
        sb.stage("Arch panels")
        for (k in 1 until pts.size) {
            val (ax, az) = pts[k - 1]; val (bx, bz) = pts[k]
            sb.face(listOf(V(ax, 0.0, az), V(bx, 0.0, bz), V(bx, len, bz), V(ax, len, az)), Mat.SHEET)
        }
        sb.stage("Corrugations (seamed panels)")
        val d = max(depth, 0.03) * 2
        for (y in along(0.0, len, max(pitch, 0.15) * 3, 40)) {
            val line = pts.mapIndexed { i, (x, z) ->
                val (px, pz) = pts[max(0, i - 1)]; val (qx, qz) = pts[min(pts.size - 1, i + 1)]
                val tx = qx - px; val tz = qz - pz; val l = hypot(tx, tz).coerceAtLeast(1e-9)
                V(x - tz / l * d, y, z + tx / l * d)
            }
            sb.bar(line, 6.0, main = false)
        }
        sb.label(V(pts.first().first + span / 2, len, springing + rise + 0.4), labelProfile, strong = true)
        sb.stage("Loads")
        for ((x, z) in pts.filterIndexed { i, _ -> i % 2 == 0 }) sb.arrow(V(x, len + 0.2, z + 1.0), V(x, len + 0.2, z + 0.05), Load.GRAVITY)
        sb.label(V(pts.first().first, len + 0.2, springing + rise + 1.2), "1.5(DL + LL) ${f2(gravity)} kN/m²")
        sb.stage("Dimensions")
        sb.dim(V(pts.first().first, -0.5, springing - 0.3), V(pts.last().first, -0.5, springing - 0.3), "span ${f2(span)} m")
        sb.dim(V(pts.last().first + 0.6, -0.5, springing), V(pts.last().first + 0.6, -0.5, springing + rise), "rise ${f2(rise)} m")
        return sb.build()
    }
}
