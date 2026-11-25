package com.structura.core.rcc

import com.structura.core.codes.IS456
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Rectangular column: [b] along x, [d] along y (mm). Bending Mux acts about the x-axis (stresses the
 * D-faces, depth [d]); Muy about the y-axis (depth [b]). Bars: [nb] along each b-face, [nd] along each
 * d-face (corners counted in both) → total 2·nb + 2·nd − 4.
 */
data class ColumnInput(
    val b: Double,
    val d: Double,
    val clearCover: Double = 40.0,
    val barDia: Double = 16.0,
    val nb: Int = 3,
    val nd: Int = 3,
    val tieDia: Double = 8.0,
    val fck: Double = 25.0,
    val fy: Double = 500.0,
    val pu: Double,                  // kN
    val mux: Double = 0.0,           // kN·m
    val muy: Double = 0.0,
    val unsupportedLength: Double = 3000.0,   // mm
    val kx: Double = 1.0,            // effective length factors (Table 28)
    val ky: Double = 1.0,
    val braced: Boolean = true,
)

data class ColumnResult(
    val asc: Double, val puz: Double,
    val muxDesign: Double, val muyDesign: Double,
    val mux1: Double, val muy1: Double,
    val alphaN: Double, val interaction: Double,
    val tiePitch: Double,
    val checks: List<DesignCheck>, val steps: List<String>,
) {
    val isSafe: Boolean get() = checks.none { it.status == Status.FAIL }
}

/**
 * IS 456:2000 column design: slenderness Cl. 25.1.2, minimum eccentricity Cl. 25.4, additional moments of
 * slender columns Cl. 39.7.1 (with the k reduction of Cl. 39.7.1.1), uniaxial capacities by strain
 * compatibility with the Cl. 38.1 / 39.1 assumptions (parabolic-rectangular concrete, Fig. 23 steel,
 * εcu = 0.0035 − 0.75·εmin when the whole section is in compression), biaxial check Cl. 39.6.
 */
object ColumnDesigner {

    private fun f1(v: Double) = "%.1f".format(v)
    private fun f2(v: Double) = "%.2f".format(v)

    /** Bar rows (distance from the compression face, area) for bending with section depth [depth]. */
    fun barRows(depth: Double, nAlong: Int, nAcross: Int, cover: Double, tie: Double, dia: Double): List<Pair<Double, Double>> {
        val a = PI * dia * dia / 4
        val y0 = cover + tie + dia / 2
        val y1 = depth - y0
        return (0 until nAlong).map { k ->
            val y = if (nAlong == 1) depth / 2 else y0 + (y1 - y0) * k / (nAlong - 1)
            val count = if (k == 0 || k == nAlong - 1) nAcross else 2
            y to count * a
        }
    }

    private fun concreteStress(eps: Double, fck: Double): Double = when {
        eps <= 0 -> 0.0
        eps < 0.002 -> 0.446 * fck * (2 * eps / 0.002 - (eps / 0.002).pow(2))
        else -> 0.446 * fck
    }

    /** Axial force (N, compression +) and moment about the section centre (N·mm) for neutral-axis depth xu. */
    fun forces(xu: Double, width: Double, depth: Double, rows: List<Pair<Double, Double>>, fck: Double, fy: Double): Pair<Double, Double> {
        fun strain(y: Double): Double =
            if (xu <= depth) 0.0035 * (xu - y) / xu
            else 0.002 * (xu - y) / (xu - 3.0 / 7.0 * depth)
        val nStrips = 400
        val h = depth / nStrips
        var p = 0.0; var m = 0.0
        for (k in 0 until nStrips) {
            val y = (k + 0.5) * h
            val c = concreteStress(strain(y), fck) * width * h
            p += c; m += c * (depth / 2 - y)
        }
        for ((y, a) in rows) {
            val e = strain(y)
            val fs = IS456.steelStress(e, fy) * (if (e >= 0) 1 else -1)
            val f = (fs - concreteStress(e, fck)) * a
            p += f; m += f * (depth / 2 - y)
        }
        return p to m
    }

    /** Uniaxial moment capacity (kN·m) at axial load [pu] (kN), or null if pu exceeds the section capacity. */
    fun momentCapacity(pu: Double, width: Double, depth: Double, rows: List<Pair<Double, Double>>, fck: Double, fy: Double): Double? {
        val target = pu * 1e3
        var lo = 1e-3 * depth; var hi = 50 * depth
        if (forces(hi, width, depth, rows, fck, fy).first < target) return null
        if (forces(lo, width, depth, rows, fck, fy).first > target) lo = 1e-6
        repeat(100) {
            val mid = (lo + hi) / 2
            if (forces(mid, width, depth, rows, fck, fy).first < target) lo = mid else hi = mid
        }
        return abs(forces((lo + hi) / 2, width, depth, rows, fck, fy).second) / 1e6
    }

    fun design(inp: ColumnInput): ColumnResult {
        val checks = ArrayList<DesignCheck>()
        val steps = ArrayList<String>()
        val b = inp.b; val d = inp.d
        val ag = b * d
        val nBars = 2 * inp.nb + 2 * inp.nd - 4
        val asc = nBars * PI * inp.barDia * inp.barDia / 4
        val ac = ag - asc
        val p = 100 * asc / ag
        steps += "$nBars-${inp.barDia.toInt()} φ: Asc = ${f1(asc)} mm² (${f2(p)} %)"
        checks += DesignCheck("Longitudinal steel", "${f2(p)} %", "0.8 % ≤ p ≤ 4 % (6 % absolute)", if (p >= 0.8 && p <= 4.0) Status.PASS else if (p <= 6.0) Status.INFO else Status.FAIL, IS456.ref("Cl. 26.5.3.1(a),(b)"))
        checks += DesignCheck("Number of bars", "$nBars", "≥ 4 (rectangular)", if (nBars >= 4) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.3.1(c)"))
        checks += DesignCheck("Bar diameter", "${inp.barDia.toInt()} mm", "≥ 12 mm", if (inp.barDia >= 12) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.3.1(d)"))

        // slenderness
        val lex = inp.kx * inp.unsupportedLength
        val ley = inp.ky * inp.unsupportedLength
        val sx = lex / d; val sy = ley / b
        val slender = sx > 12 || sy > 12
        steps += "lex/D = ${f2(sx)}, ley/b = ${f2(sy)} → ${if (slender) "slender" else "short"} column (Cl. 25.1.2)"
        checks += DesignCheck("Slenderness", "lex/D ${f2(sx)}, ley/b ${f2(sy)}", "≤ 60 (Cl. 25.3.1); > 12 → slender",
            if (max(sx, sy) <= 60) (if (slender) Status.INFO else Status.PASS) else Status.FAIL, IS456.ref("Cl. 25.1.2, 25.3.1"))

        // minimum eccentricity
        val ex = max(inp.unsupportedLength / 500 + d / 30, 20.0)
        val ey = max(inp.unsupportedLength / 500 + b / 30, 20.0)
        var mux = max(inp.mux, inp.pu * ex / 1000)
        var muy = max(inp.muy, inp.pu * ey / 1000)
        steps += "e,min x = ${f1(ex)} mm, e,min y = ${f1(ey)} mm (Cl. 25.4) → Mux ≥ ${f2(inp.pu * ex / 1000)}, Muy ≥ ${f2(inp.pu * ey / 1000)} kN·m"

        val puz = (0.45 * inp.fck * ac + 0.75 * inp.fy * asc) / 1e3
        steps += "Puz = 0.45·fck·Ac + 0.75·fy·Asc = ${f1(puz)} kN (Cl. 39.6)"
        val rowsX = barRows(d, inp.nd, inp.nb, inp.clearCover, inp.tieDia, inp.barDia)
        val rowsY = barRows(b, inp.nb, inp.nd, inp.clearCover, inp.tieDia, inp.barDia)

        // additional moments for slender columns
        if (slender) {
            fun balanced(width: Double, depth: Double, rows: List<Pair<Double, Double>>): Double {
                val dEff = rows.maxOf { it.first }
                val xu = IS456.xuMaxByD(inp.fy) * dEff
                return forces(xu, width, depth, rows, inp.fck, inp.fy).first / 1e3
            }
            val pbx = balanced(b, d, rowsX); val pby = balanced(d, b, rowsY)
            val kxr = ((puz - inp.pu) / (puz - pbx)).coerceIn(0.0, 1.0)
            val kyr = ((puz - inp.pu) / (puz - pby)).coerceIn(0.0, 1.0)
            val addX = if (sx > 12) inp.pu * d / 2000 * sx * sx / 1000 * kxr else 0.0
            val addY = if (sy > 12) inp.pu * b / 2000 * sy * sy / 1000 * kyr else 0.0
            steps += "Additional moments Ma = Pu·D/2000·(le/D)² × k (Cl. 39.7.1, 39.7.1.1): Max = ${f2(addX)} kN·m (k ${f2(kxr)}), May = ${f2(addY)} kN·m (k ${f2(kyr)})"
            mux += addX; muy += addY
        }

        val mux1 = momentCapacity(inp.pu, b, d, rowsX, inp.fck, inp.fy)
        val muy1 = momentCapacity(inp.pu, d, b, rowsY, inp.fck, inp.fy)
        if (mux1 == null || muy1 == null) {
            checks += DesignCheck("Axial capacity", "Pu = ${f1(inp.pu)} kN", "exceeds the section's axial capacity", Status.FAIL, IS456.ref("Cl. 39.3, 39.6"))
            return ColumnResult(asc, puz, mux, muy, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, 0.0, checks, steps)
        }
        val ratio = inp.pu / puz
        val alpha = when { ratio <= 0.2 -> 1.0; ratio >= 0.8 -> 2.0; else -> 1.0 + (ratio - 0.2) / 0.6 }
        val inter = (mux / mux1).pow(alpha) + (muy / muy1).pow(alpha)
        steps += "Mux1 = ${f2(mux1)} kN·m, Muy1 = ${f2(muy1)} kN·m at Pu (strain compatibility, Cl. 39.1)"
        steps += "Pu/Puz = ${f2(ratio)} → αn = ${f2(alpha)}; (Mux/Mux1)^αn + (Muy/Muy1)^αn = ${"%.3f".format(inter)}"
        checks += DesignCheck("Biaxial interaction", "%.3f".format(inter), "≤ 1.0", if (inter <= 1.0) Status.PASS else Status.FAIL, IS456.ref("Cl. 39.6"))

        // lateral ties
        val tieMin = max(inp.barDia / 4, 6.0)
        val pitch = minOf(min(b, d), 16 * inp.barDia, 300.0)
        checks += DesignCheck("Tie diameter", "${inp.tieDia.toInt()} mm", "≥ max(φ/4, 6) = ${f1(tieMin)} mm", if (inp.tieDia >= tieMin) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.3.2(c)(2)"))
        checks += DesignCheck("Tie pitch", "${pitch.toInt()} mm", "≤ least of b, 16φ, 300 mm", Status.PASS, IS456.ref("Cl. 26.5.3.2(c)(1)"))
        checks += DesignCheck("Cover", "${f1(inp.clearCover)} mm", "≥ 40 mm for columns (or Table 16 if more)", if (inp.clearCover >= 40) Status.PASS else Status.INFO, IS456.ref("Cl. 26.4.2.1"))
        return ColumnResult(asc, puz, mux, muy, mux1, muy1, alpha, inter, pitch, checks, steps)
    }
}
