package com.structura.core.rcc

import com.structura.core.codes.IS456
import com.structura.core.fem.FootingType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Foundations at the supports of the main-space model (IS 456:2000 Cl. 34):
 * isolated pad (uses [FootingDesigner]), isolated sloped and stepped (pad design + shear at the reduced depth of the
 * critical section, edge ≥ 150 mm, Cl. 34.1.2), and combined two-column rectangular footings (resultant at the centroid,
 * longitudinal beam action with top steel between columns, transverse bands under columns, punching at each column).
 */
object FoundationDesign {
    class Result(
        val node: Int, val type: FootingType, val l: Double, val b: Double, val depth: Double, val edge: Double,
        val partner: Int?, val barsL: String, val barsB: String, val barsTop: String, val checks: List<DesignCheck>, val steps: List<String>,
        val pu: Double, val concrete: Double, val cx: Double, val cy: Double, val angle: Double,
        val nL: Int, val nB: Int, val dia: Double,
    ) {
        val ok get() = checks.none { it.status == Status.FAIL }
        val summary get() = "${type.label} ${f2(l)} × ${f2(b)} m, D ${f0(depth)}${if (edge < depth) "/${f0(edge)}" else ""} mm · $barsL ∥ L, $barsB ∥ B" +
            (if (barsTop.isNotEmpty()) ", top $barsTop" else "") + " · ${if (ok) "✔" else "✘"}"
    }

    private fun f0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
    private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

    /** Isolated footing of [type] under one column (service P, factored Pu in kN; moments kN·m; column mm). */
    fun isolated(node: Int, type: FootingType, p: Double, pu: Double, ml: Double, mb: Double, colB: Double, colD: Double, sbc: Double,
                 fck: Double, fy: Double, x: Double, y: Double, dia: Double = 12.0): Result {
        val pad = FootingDesigner.design(FootingInput(serviceLoad = max(p, 1.0), factoredLoad = max(pu, 1.5), colB = colB, colD = colD, sbc = sbc,
            fck = fck, fy = fy, barDia = dia, momentL = ml, momentB = mb, factoredMomentL = 1.5 * ml, factoredMomentB = 1.5 * mb))
        val checks = ArrayList(pad.checks); val steps = ArrayList(pad.steps)
        val dd = pad.depth; val l = pad.lengthL; val b = pad.widthB
        var edge = dd
        var vol = l * b * dd / 1000
        if (type == FootingType.SLOPED || type == FootingType.STEPPED) {
            val qu = pad.qu
            val lc = colD / 1000 + 0.15; val bc = colB / 1000 + 0.15             // 75 mm offset round the column
            if (type == FootingType.SLOPED) {
                edge = max(150.0, ceil(dd / 3 / 25) * 25)
                // one-way shear at d from the column face, with the depth there (linear slope from the offset to the edge)
                fun dAt(dist: Double, proj: Double) = (edge + (dd - edge) * (1 - (dist / proj).coerceIn(0.0, 1.0))) - 50 - dia
                for ((name, span, width) in listOf(Triple("∥ L", (l - colD / 1000) / 2, b), Triple("∥ B", (b - colB / 1000) / 2, l))) {
                    val dSec = dAt(pad.d / 1000, span)
                    val vu = qu * width * max(0.0, span - pad.d / 1000)
                    val tv = vu * 1000 / (width * 1000 * dSec)
                    val pt = 100 * (if (name == "∥ L") pad.astL else pad.astB) / (width * 1000 * dSec)
                    val tc = IS456.tauC(pt, fck)
                    checks += DesignCheck("One-way shear $name (sloped)", "τv ${f3(tv)} MPa at d = ${f0(dSec)} mm", "≤ τc ${f3(tc)} MPa", if (tv <= tc) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.2.4.1(a), 40.2"))
                }
                checks += DesignCheck("Edge thickness", "${f0(edge)} mm", "≥ 150 mm (on soils)", if (edge >= 150) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.1.2"))
                checks += DesignCheck("Slope of top", "1 in ${f1((min(l - lc, b - bc) / 2) / ((dd - edge) / 1000))}", "flatter than 1 V : 1.5 H so it can be cast without top shuttering",
                    if ((min(l - lc, b - bc) / 2) / ((dd - edge) / 1000) >= 1.5) Status.PASS else Status.INFO, "practice")
                val h1 = (dd - edge) / 1000
                vol = l * b * edge / 1000 + h1 / 3 * (l * b + lc * bc + sqrt(l * b * lc * bc))
                steps += "Sloped: depth ${f0(dd)} mm at the column, ${f0(edge)} mm at the edge; concrete ${f2(vol)} m³ (pad ${f2(l * b * dd / 1000)} m³)"
            } else {
                edge = ceil(dd / 2 / 25) * 25
                val l2 = (l + lc) / 2; val b2 = (b + bc) / 2
                for ((name, span, width) in listOf(Triple("∥ L", (l - l2) / 2, b), Triple("∥ B", (b - b2) / 2, l))) {
                    val dSec = edge - 50 - dia
                    val vu = qu * width * max(0.0, span - dSec / 1000)
                    val tv = vu * 1000 / (width * 1000 * dSec)
                    val tc = IS456.tauC(100 * (if (name == "∥ L") pad.astL else pad.astB) / (width * 1000 * dSec), fck)
                    checks += DesignCheck("One-way shear at the step $name", "τv ${f3(tv)} MPa (d ${f0(dSec)} mm)", "≤ τc ${f3(tc)} MPa", if (tv <= tc) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.2.4.1(a)"))
                }
                vol = l * b * edge / 1000 + l2 * b2 * (dd - edge) / 1000
                steps += "Stepped: lower step ${f2(l)} × ${f2(b)} × ${f0(edge)}, upper step ${f2(l2)} × ${f2(b2)} × ${f0(dd - edge)} mm; concrete ${f2(vol)} m³"
            }
        }
        return Result(node, type, l, b, dd, edge, null, "${pad.barsL}-φ${f0(dia)} @ ${f0(pad.spacingL)}", "${pad.barsB}-φ${f0(dia)} @ ${f0(pad.spacingB)}", "",
            checks, steps, pu, vol, x, y, 0.0, pad.barsL, pad.barsB, dia)
    }

    /**
     * Combined rectangular footing for two columns ([p1], [p2] service, [pu1], [pu2] factored, kN) [s] m apart along the line
     * joining them; [a] = projection beyond column 1. Column sizes c1, c2 = (b along the line, d across) mm.
     */
    fun combined(n1: Int, n2: Int, p1: Double, p2: Double, pu1: Double, pu2: Double, x1: Double, y1: Double, x2: Double, y2: Double,
                 c1: Pair<Double, Double>, c2: Pair<Double, Double>, sbc: Double, fck: Double, fy: Double, dia: Double = 16.0): Result {
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        val s = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
        val ang = Math.toDegrees(kotlin.math.atan2(y2 - y1, x2 - x1))
        val ptot = p1 + p2
        val xr = p2 * s / ptot                                         // resultant from column 1
        val a = max(c1.first / 2000 + 0.3, 0.5)
        val l = ceil(2 * (a + xr) / 0.05) * 0.05
        val a2 = l - a - s
        val b = max(ceil(1.1 * ptot / sbc / l / 0.05) * 0.05, max(c1.second, c2.second) / 1000 + 0.6)
        val q = 1.1 * ptot / (l * b)
        steps += "Resultant of ${f1(p1)} + ${f1(p2)} kN at ${f2(xr)} m from column 1 → L = 2(a + x̄) = ${f2(l)} m (projections ${f2(a)} / ${f2(a2)} m), B = ${f2(b)} m"
        checks += DesignCheck("Bearing pressure (service)", "${f1(q)} kN/m² (uniform, resultant at centroid)", "≤ SBC ${f1(sbc)} kN/m²", if (q <= sbc + 1e-6) Status.PASS else Status.FAIL, IS456.ref("Cl. 34.1"))
        if (a2 < 0) checks += DesignCheck("Geometry", "projection beyond column 2 < 0", "use a strap or trapezoidal footing", Status.FAIL, "IS 456 Cl. 34")
        val w = (pu1 + pu2) / l                                         // factored upward line load kN/m
        val xs1 = a; val xs2 = a + s
        fun shear(x: Double) = w * x - (if (x > xs1) pu1 else 0.0) - (if (x > xs2) pu2 else 0.0)
        fun moment(x: Double) = w * x * x / 2 - (if (x > xs1) pu1 * (x - xs1) else 0.0) - (if (x > xs2) pu2 * (x - xs2) else 0.0)
        val xsAll = (0..400).map { l * it / 400 }
        val mSag = xsAll.maxOf { moment(it) }.coerceAtLeast(0.0)           // + : bottom tension (cantilevers, under columns)
        val mHog = (-xsAll.minOf { moment(it) }).coerceAtLeast(0.0)        // − : top tension between the columns
        steps += "Longitudinal (factored line load ${f1(w)} kN/m): max hogging ${f1(mHog)} kN·m (top steel between columns), max sagging ${f1(mSag)} kN·m (bottom)"
        var depth = 400.0; var dEff = 0.0; var ok = false
        var astTop = 0.0; var astBot = 0.0
        while (depth <= 2000) {
            dEff = depth - 50 - dia / 2
            val muLim = (if (fy >= 500) 0.133 else 0.138) * fck * b * 1000 * dEff * dEff / 1e6
            if (max(mHog, mSag) > muLim) { depth += 50; continue }
            astTop = runCatching { SlabDesigner.astFor(mHog / b, dEff, fck, fy) * b }.getOrDefault(Double.MAX_VALUE)
            astBot = runCatching { SlabDesigner.astFor(mSag / b, dEff, fck, fy) * b }.getOrDefault(Double.MAX_VALUE)
            val astMin = 0.0012 * b * 1000 * depth
            astTop = max(astTop, astMin); astBot = max(astBot, astMin)
            // one-way shear at d from the inner faces
            val crit = listOf(xs1 + c1.first / 2000 + dEff / 1000, xs2 - c2.first / 2000 - dEff / 1000, xs1 - c1.first / 2000 - dEff / 1000, xs2 + c2.first / 2000 + dEff / 1000)
                .filter { it in 0.0..l }
            val vu = crit.maxOfOrNull { abs(shear(it)) } ?: 0.0
            val tv = vu * 1000 / (b * 1000 * dEff); val tc = IS456.tauC(100 * max(astTop, astBot) / (b * 1000 * dEff), fck)
            fun punch(pu: Double, c: Pair<Double, Double>): Pair<Double, Double> {
                val b0 = 2 * (c.first + dEff) + 2 * (c.second + dEff)
                val area = (c.first + dEff) * (c.second + dEff) / 1e6
                val v = pu - (pu1 + pu2) / (l * b) * area                 // column load less the upward pressure inside the perimeter
                val bc = min(c.first, c.second) / max(c.first, c.second)
                return v * 1000 / (b0 * dEff) to min(1.0, 0.5 + bc) * 0.25 * sqrt(fck)
            }
            val (tp1, tcp1) = punch(pu1, c1); val (tp2, tcp2) = punch(pu2, c2)
            if (tv <= tc && tp1 <= tcp1 && tp2 <= tcp2) {
                ok = true
                checks += DesignCheck("One-way shear (longitudinal)", "τv ${f3(tv)} MPa", "≤ τc ${f3(tc)} MPa", Status.PASS, IS456.ref("Cl. 34.2.4.1(a), 40.2"))
                checks += DesignCheck("Punching shear, column 1", "τv ${f3(tp1)} MPa", "≤ ks·0.25√fck = ${f3(tcp1)} MPa", Status.PASS, IS456.ref("Cl. 31.6.3"))
                checks += DesignCheck("Punching shear, column 2", "τv ${f3(tp2)} MPa", "≤ ks·0.25√fck = ${f3(tcp2)} MPa", Status.PASS, IS456.ref("Cl. 31.6.3"))
                break
            }
            depth += 50
        }
        if (!ok) checks += DesignCheck("Depth", "> 2000 mm", "no depth up to 2 m satisfies flexure and shear", Status.FAIL, IS456.ref("Cl. 34.2"))
        checks += DesignCheck("Flexure depth", "D = ${f0(depth)} mm, d = ${f0(dEff)} mm", "Mu ≤ Mu,lim (singly reinforced)", if (ok) Status.PASS else Status.FAIL, IS456.ref("Annex G-1.1"))
        val ab = PI * dia * dia / 4
        val nTop = max(4, ceil(astTop / ab).toInt()); val nBot = max(4, ceil(astBot / ab).toInt())
        // transverse bands under each column: width c + 2·0.75d (practice), cantilever of (B − c)/2
        val trans = listOf(pu1 to c1, pu2 to c2).map { (pu, c) ->
            val wq = pu / b; val proj = (b - c.second / 1000) / 2
            val mu = wq * proj * proj / 2
            val band = c.first / 1000 + 1.5 * dEff / 1000
            val ast = max(runCatching { SlabDesigner.astFor(mu / band, dEff - dia, fck, fy) * band }.getOrDefault(0.0), 0.0012 * band * 1000 * depth)
            Triple(band, mu, ast)
        }
        trans.forEachIndexed { k, (band, mu, ast) -> steps += "Transverse band under column ${k + 1}: width ${f2(band)} m, Mu ${f1(mu)} kN·m → ${f0(ast)} mm² (${ceil(ast / ab).toInt()}-φ${f0(dia)})" }
        val astB = 0.0012 * l * 1000 * depth
        val nB = max(ceil(astB / ab).toInt(), trans.sumOf { ceil(it.third / ab).toInt() })
        checks += DesignCheck("Minimum steel", "0.12 % of b·D each way", "Cl. 34.5.1 / 26.5.2.1", Status.PASS, IS456.ref("Cl. 34.5.1"))
        val ld = dia * 0.87 * fy / (4 * 1.6 * (if (fck >= 25) 1.4 else 1.2))
        checks += DesignCheck("Development length", "Ld ${f0(ld)} mm", "bars to run ≥ Ld beyond the critical sections", Status.INFO, IS456.ref("Cl. 26.2.1"))
        val vol = l * b * depth / 1000
        val cx = x1 + (x2 - x1) / s * (l / 2 - a); val cy = y1 + (y2 - y1) / s * (l / 2 - a)
        return Result(n1, FootingType.COMBINED, l, b, depth, depth, n2, "$nBot-φ${f0(dia)}", "$nB-φ${f0(dia)} (banded)", "$nTop-φ${f0(dia)}",
            checks, steps, pu1 + pu2, vol, cx, cy, ang, nBot, nB, dia)
    }
}
