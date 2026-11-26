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
 * Gusset checks per member end: yield on the Whitmore effective width (30° spread, Cl. 6.2) and block shear of the
 * gusset round the bolt line (Cl. 6.4.1). Welded alternative: balanced fillet welds on heel and toe (Cl. 10.5.7).
 */
object TrussDetail {
    class MemberSpec(val name: String, val leg: Double, val t: Double)
    class EndBolts(val member: Int, val node: Int, val force: Double, val bolts: Int, val boltValue: Double)
    class Weld(val member: Int, val size: Double, val heel: Double, val toe: Double, val utilisation: Double)
    class GussetRow(val member: Int, val force: Double, val tension: Double, val whitmore: Double, val tdg: Double, val tdb: Double?) {
        val utilisation get() = max(force / tdg, tdb?.let { tension / it } ?: 0.0)
    }
    class Result(val ends: List<EndBolts>, val joints: List<ReportDoc.Block.Figure>, val drawing: Drawing, val checks: List<DesignCheck>, val steps: List<String>,
                 val gusset: List<GussetRow> = emptyList(), val welds: List<Weld> = emptyList(), val welded: Boolean = false)

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

    fun detail(model: FrameModel, spec: Map<Int, MemberSpec>, forces: Map<Int, Pair<Double, Double>>, double: Boolean, tg: Double, boltDia: Double, grade: SteelGrade,
               welded: Boolean = false, weldSize: Double = 0.0): Result {
        val bolt = IS800.Bolt.g46(boltDia)
        val d0 = bolt.holeDia
        val pitch = ceil(2.5 * boltDia / 5) * 5
        val edge = ceil(1.5 * d0 / 5) * 5 + 5
        val steps = ArrayList<String>(); val checks = ArrayList<DesignCheck>()
        val ends = ArrayList<EndBolts>()
        val boltCount = HashMap<Int, Int>()
        val welds = HashMap<Int, Weld>(); val weldChecks = ArrayList<Pair<Double, List<DesignCheck>>>()
        val gRows = ArrayList<GussetRow>()
        val gLen = HashMap<Int, Double>()            // connection length along the member (bolt group or weld)
        val tan30 = kotlin.math.tan(Math.PI / 6)
        for (m in model.members) {
            val sp = spec.getValue(m.id)
            val f = forces[m.id]?.let { max(it.first, it.second) } ?: 0.0
            val tb = if (double) min(2 * sp.t, tg) else min(sp.t, tg)
            val r = IS800.boltGroup(bolt, grade.fu, tb, max(f, 1e-3), edge, pitch, nn = if (double) 2 else 1)
            val n = max(2, ceil(f / r.capacity - 1e-9).toInt())
            boltCount[m.id] = n
            ends += EndBolts(m.id, m.i, f, n, r.capacity); ends += EndBolts(m.id, m.j, f, n, r.capacity)
            val ft = forces[m.id]?.first ?: 0.0
            val g = ceil(0.55 * sp.leg / 5) * 5
            if (welded) {
                // balanced welds: centroid c from the heel of an equal angle; heel takes Fa·(b − c)/b, toe Fa·c/b
                val fa = max(f, 1e-3) / (if (double) 2 else 1)
                val b = sp.leg; val t = sp.t
                val c = (b * b + b * t - t * t) / (2 * (2 * b - t))
                val thin = min(t, tg); val thick = max(t, tg)
                val sMin = when { thick <= 10 -> 3.0; thick <= 20 -> 5.0; thick <= 32 -> 6.0; else -> 8.0 }
                val sz = if (weldSize > 0) weldSize else max(sMin, min(6.0, kotlin.math.floor(thin - 1.5)))
                val wh = IS800.filletWeld(sz, fa * (b - c) / b, grade.fu, thin, thick)
                val wt = IS800.filletWeld(sz, fa * c / b, grade.fu, thin, thick)
                val lh = max(wh.capacity, 4 * sz + 2 * sz); val lt = max(wt.capacity, 4 * sz + 2 * sz)
                welds[m.id] = Weld(m.id, sz, lh, lt, max(wh.utilisation, wt.utilisation))
                weldChecks += max(wh.utilisation, wt.utilisation) to (wh.checks + wt.checks)
                gLen[m.id] = max(lh, lt)
                val lw = b + 2 * max(lh, lt) * tan30
                gRows += GussetRow(m.id, f, ft, lw, lw * tg * grade.fy / 1.10 / 1e3, null)
            } else {
                val lg = (n - 1) * pitch
                gLen[m.id] = lg
                val lw = 2 * lg * tan30
                val lv = lg + edge; val lt = sp.leg - g + 12
                val avg = lv * tg; val avn = (lv - (n - 0.5) * d0) * tg; val atg = lt * tg; val atn = (lt - 0.5 * d0) * tg
                val tdb1 = avg * grade.fy / (kotlin.math.sqrt(3.0) * 1.10) + 0.9 * atn * grade.fu / 1.25
                val tdb2 = 0.9 * avn * grade.fu / (kotlin.math.sqrt(3.0) * 1.25) + atg * grade.fy / 1.10
                gRows += GussetRow(m.id, f, ft, lw, lw * tg * grade.fy / 1.10 / 1e3, min(tdb1, tdb2) / 1e3)
            }
        }
        val v1 = ends.first().boltValue
        val gw = gRows.maxBy { it.force / it.tdg }
        steps += "Gusset yield on the Whitmore width (30° spread from the first ${if (welded) "weld end across the leg" else "bolt"}): Lw = ${if (welded) "b + 2·Lweld·tan30°" else "2·(n − 1)·p·tan30°"}, Tdg = Lw·tg·fy/γm0 — governing M${gw.member}: Lw = ${f0(gw.whitmore)} mm → ${f1(gw.tdg)} kN for ${f1(gw.force)} kN (IS 800 Cl. 6.2)"
        checks += DesignCheck("Gusset yield (Whitmore)", "M${gw.member}: F = ${f1(gw.force)} kN", "≤ Tdg = ${f1(gw.tdg)} kN (Lw ${f0(gw.whitmore)} mm)", if (gw.force <= gw.tdg) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 6.2")
        if (!welded) {
            val gb = gRows.filter { it.tdb != null }.maxBy { it.tension / it.tdb!! }
            steps += "Gusset block shear round the bolt line (one shear plane along the bolts + end distance, one tension plane to the gusset edge): Tdb = min(Avg·fy/(√3·γm0) + 0.9·Atn·fu/γm1, 0.9·Avn·fu/(√3·γm1) + Atg·fy/γm0) — governing M${gb.member}: ${f1(gb.tdb!!)} kN (IS 800 Cl. 6.4.1)"
            checks += DesignCheck("Gusset block shear", "M${gb.member}: T = ${f1(gb.tension)} kN", "≤ Tdb = ${f1(gb.tdb)} kN", if (gb.tension <= gb.tdb) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 6.4.1")
        } else {
            val worst = weldChecks.maxBy { it.first }
            val wm = welds.values.maxBy { it.utilisation }
            steps += "Fillet welds (shop, γmw 1.25): size ${f0(wm.size)} mm, fwd = fu/(√3·γmw); heel and toe lengths balanced about the angle centroid, + 2s end returns — e.g. M${wm.member}: heel ${f0(wm.heel)} mm, toe ${f0(wm.toe)} mm per ${if (double) "angle" else "end"} (IS 800 Cl. 10.5.7)"
            worst.second.distinctBy { it.item }.forEach { checks += it }
        }
        checks += DesignCheck("Gusset in compression", "free edge ≤ 42ε·tg = ${f0(42 * kotlin.math.sqrt(250 / grade.fy) * tg)} mm", "detailer to confirm on the drawing; else stiffen the edge", Status.INFO, "IS 800:2007 Table 2 (outstand)")
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
                val last = a0 + gLen.getValue(m.id)
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
                val wd = welds[m.id]
                if (wd != null) {
                    // weld runs along the heel and the toe of the connected leg, starting at the angle end
                    val s0 = a0 - edge
                    val hA = u * s0 + vv * (-g); val hB = u * (s0 + wd.heel) + vv * (-g)
                    val tA = u * s0 + vv * (sp.leg - g); val tB = u * (s0 + wd.toe) + vv * (sp.leg - g)
                    for (off in listOf(-3.0, 3.0)) {
                        ln(hA + vv * off, hB + vv * off, 3); ln(tA + vv * off, tB + vv * off, 3)
                    }
                    view.line(hA.x, hA.y, hB.x, hB.y, Layer.BOLT); view.line(tA.x, tA.y, tB.x, tB.y, Layer.BOLT)
                }
                for (k in 0 until (if (wd != null) 0 else n)) {
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
                val label = "M${m.id} ${if (double) "2-" else ""}${sp.name.removePrefix("2-")} · " + (wd?.let { "${f0(it.size)} FW ${f0(it.heel)}/${f0(it.toe)}" } ?: "$n M${f0(boltDia)}")
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
            figs += ReportDoc.Block.Figure("Joint J${node.id}${if (isSupport) " (support)" else ""} — gusset ${f0(tg)} mm, " + (if (welded) "fillet welds on heel and toe (heel/toe lengths in labels)" else "bolts M${f0(boltDia)} on the backmark line") + ", setting-out lines dashed (mm)", lines, txt, 190f)
        }
        return Result(ends, figs, dwg, checks, steps, gRows, welds.values.sortedBy { it.member }, welded)
    }

    internal fun ang(a: Double, b: Double) = atan2(a, b)
    internal fun ab(v: Double) = abs(v)
}
