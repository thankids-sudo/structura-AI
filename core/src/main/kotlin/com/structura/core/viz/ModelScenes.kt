package com.structura.core.viz

import com.structura.core.fem.FrameModel
import com.structura.core.fem.FrameSolver
import com.structura.core.fem.FootingType
import com.structura.core.fem.LoadDir
import com.structura.core.fem.RccModelDesign
import com.structura.core.rcc.FoundationDesign
import com.structura.core.viz.Scene3D.Load
import com.structura.core.viz.Scene3D.Mat
import com.structura.core.viz.Scene3D.V
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The whole main-space structure as an animated 3D construction sequence: excavation and footings → columns → beams →
 * slabs → reinforcement (from the member and footing design) → loads → dimensions. [only] limits the scene to some
 * members (single-member detail view, with every stirrup / tie drawn).
 */
object ModelScenes {
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    private fun f0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
    private fun v(p: com.structura.core.fem.Vec3) = V(p.x, p.y, p.z)

    fun build(model: FrameModel, rcc: List<RccModelDesign.MemberDesign>?, footings: List<FoundationDesign.Result>?, only: Set<Int>? = null): Scene3D {
        val detail = only != null
        val members = model.members.filter { only == null || it.id in only }
        val design = rcc?.associateBy { it.member } ?: emptyMap()
        val sb = Scene3D.Builder(if (detail) "Member detail — ${members.joinToString { "M${it.id}" }}" else "Structure — ${model.title}")
        fun vertical(m: com.structura.core.fem.Member): Boolean {
            val d = model.node(m.j).pos - model.node(m.i).pos
            return abs(d.z) > 0.9 * d.length
        }
        // ---- 0 footings
        sb.stage("Excavation and footings")
        val nodesUsed = members.flatMap { listOf(it.i, it.j) }.toSet()
        val fts = footings?.filter { !detail || it.node in nodesUsed || (it.partner ?: -1) in nodesUsed } ?: emptyList()
        if (!detail && model.nodes.isNotEmpty()) {
            val xs = model.nodes.map { it.x }; val ys = model.nodes.map { it.y }; val z0 = model.nodes.minOf { it.z }
            sb.solid(Solids.box((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2, z0 - 0.04, z0, xs.max() - xs.min() + 4, ys.max() - ys.min() + 4), Mat.GROUND)
        }
        for (f in fts) {
            val n = model.node(f.node)
            val top = n.z; val bot = top - f.depth / 1000
            when (f.type) {
                FootingType.SLOPED -> {
                    sb.solid(Solids.box(f.cx, f.cy, bot, bot + f.edge / 1000, f.l, f.b), Mat.CONCRETE)
                    sb.solid(Solids.frustum(f.cx, f.cy, bot + f.edge / 1000, top, f.l, f.b, colSize(model, f.node).first + 0.15, colSize(model, f.node).second + 0.15), Mat.CONCRETE)
                }
                FootingType.STEPPED -> {
                    sb.solid(Solids.box(f.cx, f.cy, bot, bot + f.edge / 1000, f.l, f.b), Mat.CONCRETE)
                    val (lc, bc) = colSize(model, f.node)
                    sb.solid(Solids.box(f.cx, f.cy, bot + f.edge / 1000, top, (f.l + lc + 0.15) / 2, (f.b + bc + 0.15) / 2), Mat.CONCRETE)
                }
                else -> sb.solid(Solids.box(f.cx, f.cy, bot, top, f.l, f.b, f.angle), Mat.CONCRETE)
            }
            sb.label(V(f.cx + f.l / 2, f.cy, bot), "F${f.node} ${f.type.label.lowercase()} ${f2(f.l)}×${f2(f.b)}×${f0(f.depth)}", strong = detail)
        }
        // ---- 1 columns, 2 beams
        sb.stage("Columns")
        val cols = members.filter { vertical(it) }; val beams = members.filter { !vertical(it) }
        fun body(m: com.structura.core.fem.Member) {
            val ax = FrameSolver.axes(model, m)
            val a = v(model.node(m.i).pos); val b = v(model.node(m.j).pos)
            val ey = V(ax.y.x, ax.y.y, ax.y.z); val ez = V(ax.z.x, ax.z.y, ax.z.z)
            val s = m.section
            val concrete = RccModelDesign.fck(m.material) != null
            when {
                s.shape == "CIRCLE" -> sb.solid(Solids.sweep(a, b, ey, ez, Solids.circleRing(s.h)), Mat.CONCRETE)
                s.shape == "RECT" -> sb.solid(Solids.sweep(a, b, ey, ez, Solids.rectRing(s.b, s.h)), if (concrete) Mat.CONCRETE else Mat.STEEL)
                else -> sb.member(a, b, max(40.0, s.h * 1000 * 0.35))
            }
            if (detail || members.size <= 40) sb.label((a + b) * 0.5, "M${m.id} ${s.name}")
        }
        cols.forEach { body(it) }
        sb.stage("Beams")
        beams.forEach { body(it) }
        if (!detail && model.panels.isNotEmpty()) {
            sb.stage("Slabs")
            for (p in model.panels) {
                val pts = p.nodes.map { v(model.node(it).pos) }
                val t = p.t
                val top = pts.map { it + V(0.0, 0.0, t / 2) }; val bot = pts.map { it - V(0.0, 0.0, t / 2) }
                val faces = arrayListOf<List<V>>(top, bot.reversed())
                for (k in pts.indices) { val k2 = (k + 1) % pts.size; faces += listOf(bot[k], bot[k2], top[k2], top[k]) }
                sb.solid(faces, Mat.CONCRETE)
            }
        }
        // ---- reinforcement
        sb.stage("Reinforcement")
        val maxLinks = if (detail) 120 else 14
        for (m in members) {
            val d = design[m.id] ?: continue
            val ax = FrameSolver.axes(model, m)
            val a = v(model.node(m.i).pos); val b = v(model.node(m.j).pos)
            val ex = V(ax.x.x, ax.x.y, ax.x.z); val ey = V(ax.y.x, ax.y.y, ax.y.z); val ez = V(ax.z.x, ax.z.y, ax.z.z)
            val len = ax.length
            val c = 0.04
            val bw = m.section.b; val hh = m.section.h
            if (d.circular) {
                val r = hh / 2 - c - d.colDia / 2000
                for (k in 0 until d.nCirc) {
                    val t = 2 * Math.PI * k / d.nCirc
                    val off = ey * (r * Math.cos(t)) + ez * (r * Math.sin(t))
                    sb.bar(listOf(a + off - ex * 0.0, b + off + ex * 0.4), d.colDia)
                }
                val links = linkPositions(len, d.tiePitch / 1000, maxLinks)
                for (s in links) sb.bar(Solids.circleRing(hh - 2 * c + 0.01, 12).let { ring -> (ring + ring.first()).map { (u, w) -> a + ex * s + ey * u + ez * w } }, 8.0, main = false)
                continue
            }
            if (d.nb > 0) {                                      // rectangular column: bars round the perimeter
                val yb = bw / 2 - c - d.colDia / 2000; val zb = hh / 2 - c - d.colDia / 2000
                val pos = HashSet<Pair<Double, Double>>()
                for (k in 0 until d.nb) { val u = -yb + 2 * yb * k / (d.nb - 1); pos += u to -zb; pos += u to zb }
                for (k in 0 until d.nd) { val w = -zb + 2 * zb * k / (d.nd - 1); pos += -yb to w; pos += yb to w }
                for ((u, w) in pos) sb.bar(listOf(a + ey * u + ez * w, b + ey * u + ez * w + ex * 0.4), d.colDia)
                for (s in linkPositions(len, d.tiePitch / 1000, maxLinks)) sb.bar(rectLink(a + ex * s, ey, ez, bw - 2 * c + 0.012, hh - 2 * c + 0.012), 8.0, main = false)
                continue
            }
            if (d.nBot > 0) {                                    // beam: bottom (local +z) and top bars, stirrups
                val yb = bw / 2 - c - d.dBot / 2000
                val zb = hh / 2 - c - d.dBot / 2000; val zt = hh / 2 - c - d.dTop / 2000
                for (k in 0 until d.nBot) { val u = if (d.nBot == 1) 0.0 else -yb + 2 * yb * k / (d.nBot - 1); sb.bar(listOf(a + ey * u + ez * zb, b + ey * u + ez * zb), d.dBot) }
                for (k in 0 until d.nTop) { val u = if (d.nTop == 1) 0.0 else -yb + 2 * yb * k / (d.nTop - 1); sb.bar(listOf(a + ey * u - ez * zt, b + ey * u - ez * zt), d.dTop) }
                for (s in linkPositions(len, d.sv / 1000, maxLinks)) sb.bar(rectLink(a + ex * s, ey, ez, bw - 2 * c + 0.012, hh - 2 * c + 0.012), 8.0, main = false)
                if (detail) {
                    sb.label(a + ex * (len * 0.3) + ez * (hh / 2 + 0.15), "bottom ${d.bottom}", strong = true)
                    sb.label(a + ex * (len * 0.3) - ez * (hh / 2 + 0.12), "top ${d.top}", strong = true)
                    sb.label(a + ex * (len * 0.65) + ez * (hh / 2 + 0.15), d.stirrups)
                }
            }
        }
        for (f in fts) {
            val n = model.node(f.node)
            val z = n.z - f.depth / 1000 + 0.06
            val ang = Math.toRadians(f.angle)
            val ex = V(Math.cos(ang), Math.sin(ang), 0.0); val ey = V(-Math.sin(ang), Math.cos(ang), 0.0)
            val cc = V(f.cx, f.cy, z)
            val nL = min(f.nL, if (detail) 40 else 14); val nB = min(f.nB, if (detail) 40 else 14)
            for (k in 0 until nL) { val u = -f.b / 2 + 0.06 + (f.b - 0.12) * k / max(1, nL - 1); sb.bar(listOf(cc + ey * u - ex * (f.l / 2 - 0.06), cc + ey * u + ex * (f.l / 2 - 0.06)), f.dia) }
            for (k in 0 until nB) { val u = -f.l / 2 + 0.06 + (f.l - 0.12) * k / max(1, nB - 1); sb.bar(listOf(cc + ex * u - ey * (f.b / 2 - 0.06) + V(0.0, 0.0, f.dia / 1000), cc + ex * u + ey * (f.b / 2 - 0.06) + V(0.0, 0.0, f.dia / 1000)), f.dia, main = false) }
            if (f.type == FootingType.COMBINED) {
                val zt = n.z - 0.06
                for (k in 0 until min(10, nL)) { val u = -f.b / 2 + 0.06 + (f.b - 0.12) * k / max(1, min(10, nL) - 1); sb.bar(listOf(V(f.cx, f.cy, zt) + ey * u - ex * (f.l / 2 - 0.06), V(f.cx, f.cy, zt) + ey * u + ex * (f.l / 2 - 0.06)), f.dia) }
            }
        }
        // ---- loads
        sb.stage("Loads")
        val span = max(1.0, model.nodes.maxOfOrNull { it.z }?.let { it - model.nodes.minOf { n -> n.z } } ?: 1.0)
        val arrowLen = 0.12 * span.coerceIn(2.0, 20.0)
        val ids = members.map { it.id }.toSet()
        for (l in model.memberLoads.filter { it.member in ids && it.dir == LoadDir.GLOBAL_Z }.distinctBy { it.member }) {
            val m = model.members.first { it.id == l.member }
            val a = v(model.node(m.i).pos); val b = v(model.node(m.j).pos)
            val top = m.section.h / 2 + 0.05
            for (t in listOf(0.15, 0.38, 0.62, 0.85)) { val p = a + (b - a) * t + V(0.0, 0.0, top); sb.arrow(p + V(0.0, 0.0, arrowLen), p, Load.GRAVITY) }
        }
        for (nl in model.nodalLoads.filter { detail.not() || it.node in nodesUsed }) {
            val p = v(model.node(nl.node).pos); val f = V(nl.values[0], nl.values[1], nl.values[2])
            val len = kotlin.math.sqrt(f.x * f.x + f.y * f.y + f.z * f.z); if (len < 1e-9) continue
            sb.arrow(p - f * (arrowLen * 1.5 / len), p, Load.GRAVITY)
        }
        // ---- dimensions
        sb.stage("Dimensions")
        if (detail) for (m in members) {
            val a = v(model.node(m.i).pos); val b = v(model.node(m.j).pos)
            val ax = FrameSolver.axes(model, m); val ez = V(ax.z.x, ax.z.y, ax.z.z); val ey = V(ax.y.x, ax.y.y, ax.y.z)
            val off = ez * (m.section.h / 2 + 0.35)
            sb.dim(a + off, b + off, "${f2(ax.length)} m")
            sb.dim(a - ey * (m.section.b / 2) - ez * (m.section.h / 2 + 0.2), a + ey * (m.section.b / 2) - ez * (m.section.h / 2 + 0.2), "b ${f0(m.section.b * 1000)}")
        } else if (model.nodes.isNotEmpty()) {
            val xs = model.nodes.map { it.x }; val ys = model.nodes.map { it.y }; val zs = model.nodes.map { it.z }
            val y0 = ys.min() - 1.0; val x1 = xs.max() + 1.0
            if (xs.max() - xs.min() > 1e-6) sb.dim(V(xs.min(), y0, zs.min()), V(xs.max(), y0, zs.min()), "${f2(xs.max() - xs.min())} m")
            if (ys.max() - ys.min() > 1e-6) sb.dim(V(x1, ys.min(), zs.min()), V(x1, ys.max(), zs.min()), "${f2(ys.max() - ys.min())} m")
            if (zs.max() - zs.min() > 1e-6) sb.dim(V(xs.min() - 1.0, y0, zs.min()), V(xs.min() - 1.0, y0, zs.max()), "${f2(zs.max() - zs.min())} m")
        }
        return sb.build()
    }

    private fun colSize(model: FrameModel, n: Int): Pair<Double, Double> {
        val c = model.members.firstOrNull { (it.i == n || it.j == n) } ?: return 0.3 to 0.3
        return c.section.h to c.section.b
    }

    private fun linkPositions(len: Double, s: Double, maxCount: Int): List<Double> {
        if (len <= 0.1) return emptyList()
        val n = min(maxCount, max(2, ceil((len - 0.1) / max(s, 0.05)).toInt() + 1))
        return (0 until n).map { 0.05 + (len - 0.1) * it / (n - 1) }
    }

    private fun rectLink(o: V, ey: V, ez: V, b: Double, h: Double): List<V> {
        val c = listOf(-b / 2 to -h / 2, b / 2 to -h / 2, b / 2 to h / 2, -b / 2 to h / 2, -b / 2 to -h / 2)
        return c.map { (u, w) -> o + ey * u + ez * w }
    }
}
