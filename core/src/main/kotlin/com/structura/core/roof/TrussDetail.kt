package com.structura.core.roof

import com.structura.core.drawing.Drawing
import com.structura.core.drawing.Layer
import com.structura.core.drawing.Ucs
import com.structura.core.fem.FrameModel
import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.Status
import com.structura.core.report.ReportDoc
import com.structura.core.report.f0
import com.structura.core.report.f1
import com.structura.core.report.f2
import com.structura.core.steel.IS800
import com.structura.core.steel.SteelGrade
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Gusset and bolt detailing of a pin-jointed angle truss (IS 800 Cl. 10): bolts per member end from the bolt value
 * (shear Cl. 10.3.3, bearing Cl. 10.3.4; single shear for single angles, double shear for double angles), minimum
 * pitch 2.5d and end distance 1.5d0 (Cl. 10.2), bolts on the backmark (gauge) line from the setting-out point,
 * gusset outline as the convex hull of the bolt groups + edge distance. Produces joint sketches (mm) for the report
 * and a DXF drawing (elevation + every joint) with layers S-STEEL, S-BOLT, S-CENTER, S-TEXT.
 */
object TrussDetail {
    class MemberSpec(val name: String, val leg: Double, val t: Double)
    class EndBolts(val member: Int, val node: Int, val force: Double, val bolts: Int, val boltValue: Double)
    class Result(val ends: List<EndBolts>, val joints: List<ReportDoc.Block.Figure>, val drawing: Drawing, val checks: List<DesignCheck>, val steps: List<String>)

    private data class V(val x: Double, val y: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y); operator fun minus(o: V) = V(x - o.x, y - o.y); operator fun times(k: Double) = V(x * k, y * k)
        fun perp() = V(-y, x)
    }

    private fun hull(pts: List<V>): List<V> {
        val p = pts.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (p.size < 3) return p
        fun cross(o: V, a: V, b: V) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<V>(); for (q in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeAt(lower.size - 1); lower += q }
        val upper = ArrayList<V>(); for (q in p.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeAt(upper.size - 1); upper += q }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    fun detail(model: FrameModel, spec: Map<Int, MemberSpec>, forces: Map<Int, Pair<Double, Double>>, double: Boolean, tg: Double, boltDia: Double, grade: SteelGrade): Result {
        val bolt = IS800.Bolt.g46(boltDia)
        val d0 = bolt.holeDia
        val pitch = ceil(2.5 * boltDia / 5) * 5
        val edge = ceil(1.5 * d0 / 5) * 5 + 5
        val steps = ArrayList<String>(); val checks = ArrayList<DesignCheck>()
        val ends = ArrayList<EndBolts>()
        val boltCount = HashMap<Int, Int>()
        for (m in model.members) {
            val sp = spec.getValue(m.id)
            val f = forces[m.id]?.let { max(it.first, it.second) } ?: 0.0
            val tb = if (double) min(2 * sp.t, tg) else min(sp.t, tg)
            val r = IS800.boltGroup(bolt, grade.fu, tb, max(f, 1e-3), edge, pitch, nn = if (double) 2 else 1)
            val n = max(2, ceil(f / r.capacity - 1e-9).toInt())
            boltCount[m.id] = n
            ends += EndBolts(m.id, m.i, f, n, r.capacity); ends += EndBolts(m.id, m.j, f, n, r.capacity)
        }
        val v1 = ends.first().boltValue
        steps += "Bolts M${f0(boltDia)} grade 4.6 in ${if (double) "double shear (two angles on the gusset)" else "single shear"}, hole ${f0(d0)} mm; pitch ${f0(pitch)} mm (≥ 2.5d), end distance ${f0(edge)} mm (≥ 1.5d0); gusset ${f0(tg)} mm"
        steps += "Bolt value = min(shear Vdsb, bearing Vdpb on the thinner of ${if (double) "2t and the gusset" else "angle and gusset"}) — e.g. M1: ${f2(v1)} kN (IS 800 Cl. 10.3.3, 10.3.4); at least 2 bolts per end"
        checks += DesignCheck("Pitch / end distance", "${f0(pitch)} / ${f0(edge)} mm", "≥ 2.5d = ${f1(2.5 * boltDia)} / ≥ 1.5d0 = ${f1(1.5 * d0)} mm", Status.PASS, "IS 800:2007 Cl. 10.2.2, 10.2.4")
        checks += DesignCheck("Maximum bolts in one line", "${boltCount.values.max()}", "long joint (> 15d) reduction if (n − 1)·p > ${f0(15 * boltDia)} mm", if ((boltCount.values.max() - 1) * pitch > 15 * boltDia) Status.INFO else Status.PASS, "IS 800:2007 Cl. 10.3.3.1")

        // ---- geometry per joint (mm, truss plane: x horizontal, y = z up)
        val dwg = Drawing("Roof truss — gussets and bolts")
        val elev = dwg.view("ELEVATION (setting-out lines)", Ucs("ELEV", com.structura.core.drawing.P2(0.0, 0.0)))
        for (m in model.members) {
            val a = model.node(m.i); val b = model.node(m.j)
            elev.line(a.x * 1000, a.z * 1000, b.x * 1000, b.z * 1000, Layer.CENTER)
            elev.text((a.x + b.x) * 500, (a.z + b.z) * 500 + 60, 90.0, "M${m.id} ${spec.getValue(m.id).name}", centered = true)
        }
        for (n in model.nodes) elev.text(n.x * 1000, n.z * 1000 - 180, 90.0, "J${n.id}", centered = true)
        val span = model.nodes.maxOf { it.x } * 1000
        val figs = ArrayList<ReportDoc.Block.Figure>()
        var col = 0
        for (node in model.nodes) {
            val mems = model.members.filter { it.i == node.id || it.j == node.id }
            if (mems.isEmpty()) continue
            val dirs = mems.associateWith { m ->
                val o = model.node(if (m.i == node.id) m.j else m.i)
                val dx = o.x - node.x; val dz = o.z - node.z; val l = hypot(dx, dz)
                V(dx / l, dz / l)
            }
            fun minAngle(m: com.structura.core.fem.Member): Double = mems.filter { it !== m }.minOfOrNull { o ->
                val a = dirs.getValue(m); val b = dirs.getValue(o); acos((a.x * b.x + a.y * b.y).coerceIn(-1.0, 1.0))
            } ?: Math.PI
            val lines = ArrayList<ReportDoc.FLine>(); val txt = ArrayList<ReportDoc.FText>()
            val gussetPts = ArrayList<V>(); val o = V(0.0, 0.0)
            val ox = span + 1500 + (col % 3) * 1800.0; val oy = -2200.0 - (col / 3) * 1800.0; col++
            val view = dwg.view("JOINT J${node.id}", Ucs("J${node.id}", com.structura.core.drawing.P2(ox, oy)))
            for (m in mems) {
                val sp = spec.getValue(m.id); val u = dirs.getValue(m); val vv = u.perp()
                val n = boltCount.getValue(m.id)
                val g = ceil(0.55 * sp.leg / 5) * 5
                val ang = minAngle(m)
                val a0 = min(420.0, max(70.0, (0.6 * sp.leg + 25) / max(sin(min(ang, Math.PI / 2)), 0.25)))
                val last = a0 + (n - 1) * pitch
                val endOfDetail = last + edge + 160
                // angle band: heel at −g, toe at (leg − g) from the bolt line; double angle: same outline (one behind the gusset)
                val heelA = u * (a0 - edge) + vv * (-g); val heelB = u * endOfDetail + vv * (-g)
                val toeA = u * (a0 - edge) + vv * (sp.leg - g); val toeB = u * endOfDetail + vv * (sp.leg - g)
                fun ln(p: V, q: V, w: Int, dashed: Boolean = false) { lines += ReportDoc.FLine(p.x, p.y, q.x, q.y, w, dashed) }
                ln(heelA, heelB, 3); ln(toeA, toeB, 2); ln(heelA, toeA, 2)
                ln(u * (sp.t + a0 - edge) + vv * (-g + sp.t), u * endOfDetail + vv * (-g + sp.t), 1)            // root line of the outstanding leg
                ln(o, u * endOfDetail, 1, dashed = true)                                                       // setting-out line
                view.line(heelA.x, heelA.y, heelB.x, heelB.y, Layer.STEEL); view.line(toeA.x, toeA.y, toeB.x, toeB.y, Layer.STEEL)
                view.line(heelA.x, heelA.y, toeA.x, toeA.y, Layer.STEEL); view.line(0.0, 0.0, u.x * endOfDetail, u.y * endOfDetail, Layer.CENTER)
                for (k in 0 until n) {
                    val c = u * (a0 + k * pitch)
                    val r = d0 / 2
                    for (s in 0 until 8) {
                        val t1 = s * Math.PI / 4; val t2 = (s + 1) * Math.PI / 4
                        lines += ReportDoc.FLine(c.x + r * kotlin.math.cos(t1), c.y + r * kotlin.math.sin(t1), c.x + r * kotlin.math.cos(t2), c.y + r * kotlin.math.sin(t2), 2)
                    }
                    view.circle(c.x, c.y, r, Layer.BOLT)
                }
                gussetPts += u * (last + edge) + vv * (sp.leg - g + 12); gussetPts += u * (last + edge) + vv * (-g - 12)
                gussetPts += u * (a0 - edge) + vv * (sp.leg - g + 12); gussetPts += u * (a0 - edge) + vv * (-g - 12)
                val lab = u * (endOfDetail + 40)
                val label = "M${m.id} ${if (double) "2-" else ""}${sp.name.removePrefix("2-")} · ${n} M${f0(boltDia)}"
                txt += ReportDoc.FText(lab.x - 60, lab.y + 10, label, 5.5f)
                view.text(lab.x, lab.y, 25.0, label)
            }
            val isSupport = model.supports.any { it.node == node.id }
            if (isSupport) { gussetPts += V(-180.0, -60.0); gussetPts += V(180.0, -60.0) }
            val h = hull(gussetPts)
            for (k in h.indices) { val a = h[k]; val b = h[(k + 1) % h.size]; lines += ReportDoc.FLine(a.x, a.y, b.x, b.y, 3); view.line(a.x, a.y, b.x, b.y, Layer.STEEL) }
            if (isSupport) {
                lines += ReportDoc.FLine(-260.0, -60.0, 260.0, -60.0, 3); lines += ReportDoc.FLine(-260.0, -76.0, 260.0, -76.0, 3)
                view.line(-260.0, -60.0, 260.0, -60.0, Layer.STEEL); view.line(-260.0, -76.0, 260.0, -76.0, Layer.STEEL)
                txt += ReportDoc.FText(-250.0, -110.0, "Bearing / base plate on the support", 5.5f)
            }
            txt += ReportDoc.FText(-40.0, 25.0, "J${node.id}", 7f)
            view.text(0.0, -40.0, 30.0, "J${node.id} — gusset ${f0(tg)} mm", centered = true)
            figs += ReportDoc.Block.Figure("Joint J${node.id}${if (isSupport) " (support)" else ""} — gusset ${f0(tg)} mm, bolts M${f0(boltDia)} on the backmark line, setting-out lines dashed (mm)", lines, txt, 190f)
        }
        return Result(ends, figs, dwg, checks, steps)
    }

    internal fun ang(a: Double, b: Double) = atan2(a, b)
    internal fun ab(v: Double) = abs(v)
}
