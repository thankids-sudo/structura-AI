package com.structura.core.rcc

import com.structura.core.codes.IS456
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class SlabType(val label: String) {
    ONE_WAY_SS("One-way, simply supported"),
    TWO_WAY_SS("Two-way, simply supported corners free to lift (Table 27)"),
    CANTILEVER("Cantilever (one-way)"),
    TWO_WAY_RESTRAINED("Two-way, corners held down (Table 26)"),
}

/** IS 456 Table 26 edge conditions (Annex D-1.1). Discontinuous edges: S = short edge, L = long edge. */
enum class SlabEdgeCase(val label: String, val discShort: Int, val discLong: Int) {
    INTERIOR("1 · Interior panel", 0, 0),
    ONE_SHORT("2 · One short edge discontinuous", 1, 0),
    ONE_LONG("3 · One long edge discontinuous", 0, 1),
    TWO_ADJACENT("4 · Two adjacent edges discontinuous", 1, 1),
    TWO_SHORT("5 · Two short edges discontinuous", 2, 0),
    TWO_LONG("6 · Two long edges discontinuous", 0, 2),
    THREE_LONG_CONT("7 · Three edges discontinuous (one long edge continuous)", 2, 1),
    THREE_SHORT_CONT("8 · Three edges discontinuous (one short edge continuous)", 1, 2),
    FOUR("9 · Four edges discontinuous", 2, 2),
}

/** Slab input: spans in m, thickness & cover in mm, loads in kN/m² (characteristic). */
data class SlabInput(
    val lx: Double,                    // short effective span (m) — for a cantilever, its length
    val ly: Double,                    // long span (m)
    val thickness: Double = 150.0,
    val clearCover: Double = 20.0,
    val barDia: Double = 10.0,
    val distBarDia: Double = 8.0,
    val finishes: Double = 1.0,
    val liveLoad: Double = 3.0,
    val fck: Double = 20.0,
    val fy: Double = 500.0,
    val type: SlabType = SlabType.TWO_WAY_SS,
    val edgeCase: SlabEdgeCase = SlabEdgeCase.INTERIOR,
) {
    val twoWay: Boolean get() = type == SlabType.TWO_WAY_SS || type == SlabType.TWO_WAY_RESTRAINED
}

/** Top (hogging) steel over continuous edges and corner torsion steel — Table 26 slabs only. */
data class SlabTopSteel(
    val mxNeg: Double, val myNeg: Double,              // kN·m/m at continuous edges (0 = no continuous edge in that direction)
    val astXNeg: Double, val astYNeg: Double,          // mm²/m
    val spacingXNeg: Double, val spacingYNeg: Double,  // mm c/c (0 = none)
    val torsionFullCorners: Int, val torsionHalfCorners: Int,
    val torsionArea: Double,                           // mm² per m in EACH of 4 layers at a full corner (D-1.8)
    val torsionSpacing: Double,                        // mm c/c for full corners
    val torsionExtent: Double,                         // m, lx/5 each way
)

data class SlabResult(
    val wu: Double,                    // factored load kN/m²
    val mx: Double, val my: Double,    // kN·m per m
    val dx: Double, val dy: Double,    // effective depths mm
    val astX: Double, val astY: Double,   // required mm²/m
    val spacingX: Double, val spacingY: Double,   // adopted mm c/c
    val checks: List<DesignCheck>,
    val steps: List<String>,
    val top: SlabTopSteel? = null,
) {
    val isSafe: Boolean get() = checks.none { it.status == Status.FAIL }
}

/**
 * Solid slab design to IS 456:2000 — Cl. 24, Annex D (Table 27 for simply supported two-way slabs),
 * min steel Cl. 26.5.2.1, spacing Cl. 26.3.3(b), shear Cl. 40.2.1.1, deflection Cl. 23.2.1 / 24.1.
 */
object SlabDesigner {

    /** IS 456 Table 27 — simply supported slabs, corners not held down: ly/lx → (αx, αy). */
    private val T27_R = doubleArrayOf(1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.75, 2.0, 2.5, 3.0)
    private val T27_AX = doubleArrayOf(0.062, 0.074, 0.084, 0.093, 0.099, 0.104, 0.113, 0.118, 0.122, 0.124)
    private val T27_AY = doubleArrayOf(0.062, 0.061, 0.059, 0.055, 0.051, 0.046, 0.037, 0.029, 0.020, 0.014)

    /** IS 456 Table 26 (ly/lx = 1.0 … 2.0): short-span −ve, short-span +ve, long-span −ve, long-span +ve. null = no such edge. */
    private val T26_R = doubleArrayOf(1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.75, 2.0)
    private class T26(val xNeg: DoubleArray?, val xPos: DoubleArray, val yNeg: Double?, val yPos: Double)
    private val T26_DATA = mapOf(
        SlabEdgeCase.INTERIOR to T26(doubleArrayOf(0.032, 0.037, 0.043, 0.047, 0.051, 0.053, 0.060, 0.065),
            doubleArrayOf(0.024, 0.028, 0.032, 0.036, 0.039, 0.041, 0.045, 0.049), 0.032, 0.024),
        SlabEdgeCase.ONE_SHORT to T26(doubleArrayOf(0.037, 0.043, 0.048, 0.051, 0.055, 0.057, 0.064, 0.068),
            doubleArrayOf(0.028, 0.032, 0.036, 0.039, 0.041, 0.044, 0.048, 0.052), 0.037, 0.028),
        SlabEdgeCase.ONE_LONG to T26(doubleArrayOf(0.037, 0.044, 0.052, 0.057, 0.063, 0.067, 0.077, 0.085),
            doubleArrayOf(0.028, 0.033, 0.039, 0.044, 0.047, 0.051, 0.059, 0.065), 0.037, 0.028),
        SlabEdgeCase.TWO_ADJACENT to T26(doubleArrayOf(0.047, 0.053, 0.060, 0.065, 0.071, 0.075, 0.084, 0.091),
            doubleArrayOf(0.035, 0.040, 0.045, 0.049, 0.053, 0.056, 0.063, 0.069), 0.047, 0.035),
        SlabEdgeCase.TWO_SHORT to T26(doubleArrayOf(0.045, 0.049, 0.052, 0.056, 0.059, 0.060, 0.065, 0.069),
            doubleArrayOf(0.035, 0.037, 0.040, 0.043, 0.044, 0.045, 0.049, 0.052), null, 0.035),
        SlabEdgeCase.TWO_LONG to T26(null,
            doubleArrayOf(0.035, 0.043, 0.051, 0.057, 0.063, 0.068, 0.080, 0.088), 0.045, 0.035),
        SlabEdgeCase.THREE_LONG_CONT to T26(doubleArrayOf(0.057, 0.064, 0.071, 0.076, 0.080, 0.084, 0.091, 0.097),
            doubleArrayOf(0.043, 0.048, 0.053, 0.057, 0.060, 0.064, 0.069, 0.073), null, 0.043),
        SlabEdgeCase.THREE_SHORT_CONT to T26(null,
            doubleArrayOf(0.043, 0.051, 0.059, 0.065, 0.071, 0.076, 0.087, 0.096), 0.057, 0.043),
        SlabEdgeCase.FOUR to T26(null,
            doubleArrayOf(0.056, 0.064, 0.072, 0.079, 0.085, 0.089, 0.100, 0.107), null, 0.056),
    )

    /** Table 26 coefficients for [case] at ly/lx = [r] (clamped to 1–2): (αx−, αx+, αy−, αy+); 0 where the table has "—". */
    fun table26(case: SlabEdgeCase, r: Double): DoubleArray {
        val t = T26_DATA.getValue(case)
        val rr = r.coerceIn(1.0, 2.0)
        return doubleArrayOf(t.xNeg?.let { IS456.interpolate(T26_R, it, rr) } ?: 0.0, IS456.interpolate(T26_R, t.xPos, rr), t.yNeg ?: 0.0, t.yPos)
    }

    /** Corners by D-1.8 / D-1.9: (both edges discontinuous, exactly one discontinuous). Short edges S1,S2 and long edges L1,L2 meet at 4 corners. */
    fun torsionCorners(case: SlabEdgeCase): Pair<Int, Int> {
        val s = listOf(case.discShort >= 1, case.discShort >= 2)
        val l = listOf(case.discLong >= 1, case.discLong >= 2)
        var full = 0; var half = 0
        for (a in s) for (b in l) { if (a && b) full++ else if (a || b) half++ }
        return full to half
    }

    fun table27(r: Double): Pair<Double, Double> = IS456.interpolate(T27_R, T27_AX, r) to IS456.interpolate(T27_R, T27_AY, r)

    /** Shear enhancement factor k for slabs, Cl. 40.2.1.1. */
    fun kSlab(d: Double): Double = when {
        d >= 300 -> 1.0
        d <= 150 -> 1.3
        else -> IS456.interpolate(doubleArrayOf(150.0, 175.0, 200.0, 225.0, 250.0, 275.0, 300.0), doubleArrayOf(1.30, 1.25, 1.20, 1.15, 1.10, 1.05, 1.00), d)
    }

    private fun f1(v: Double) = "%.1f".format(v)
    private fun f2(v: Double) = "%.2f".format(v)

    fun astFor(mu: Double, d: Double, fck: Double, fy: Double): Double {
        val b = 1000.0
        val r = 4.6 * mu * 1e6 / (fck * b * d * d)
        require(r < 1) { "Slab too thin for the moment — increase thickness" }
        return 0.5 * fck / fy * (1 - sqrt(1 - r)) * b * d
    }

    fun design(inp: SlabInput): SlabResult {
        val checks = ArrayList<DesignCheck>()
        val steps = ArrayList<String>()
        val t = inp.thickness
        val sw = 25.0 * t / 1000
        val w = sw + inp.finishes + inp.liveLoad
        val wu = 1.5 * w
        steps += "Loads: self-weight 25 × ${f2(t / 1000)} = ${f2(sw)} + finishes ${f2(inp.finishes)} + LL ${f2(inp.liveLoad)} = ${f2(w)} kN/m²; wu = 1.5 × ${f2(w)} = ${f2(wu)} kN/m² (Table 18)"
        val lx = min(inp.lx, inp.ly); val ly = max(inp.lx, inp.ly)
        val dx = t - inp.clearCover - inp.barDia / 2
        val dy = dx - inp.barDia / 2 - (if (inp.twoWay) inp.barDia / 2 else inp.distBarDia / 2)
        var coeff26: DoubleArray? = null
        val ratio = ly / lx
        val (mx, my, vu) = when (inp.type) {
            SlabType.ONE_WAY_SS -> Triple(wu * inp.lx * inp.lx / 8, 0.0, wu * inp.lx / 2)
            SlabType.CANTILEVER -> Triple(wu * inp.lx * inp.lx / 2, 0.0, wu * inp.lx)
            SlabType.TWO_WAY_SS -> {
                val (ax, ay) = table27(ratio.coerceAtMost(3.0))
                steps += "ly/lx = ${f2(ratio)} → αx = ${"%.4f".format(ax)}, αy = ${"%.4f".format(ay)} (Table 27)"
                if (ratio > 2) checks += DesignCheck("Span ratio", f2(ratio), "> 2 — behaves mainly one-way", Status.INFO, IS456.ref("Cl. 24.4"))
                Triple(ax * wu * lx * lx, ay * wu * lx * lx, wu * lx / 2)
            }
            SlabType.TWO_WAY_RESTRAINED -> {
                val c = table26(inp.edgeCase, ratio); coeff26 = c
                steps += "Edge condition ${inp.edgeCase.label}; ly/lx = ${f2(ratio)} → αx− = ${"%.4f".format(c[0])}, αx+ = ${"%.4f".format(c[1])}, " +
                    "αy− = ${"%.3f".format(c[2])}, αy+ = ${"%.3f".format(c[3])} (Table 26; 0 = edge not continuous)"
                if (ratio > 2) checks += DesignCheck("Span ratio", f2(ratio), "> 2 — Table 26 limited to 2.0; design as one-way", Status.FAIL, IS456.ref("Annex D-1.1, Cl. 24.4"))
                Triple(c[1] * wu * lx * lx, c[3] * wu * lx * lx, wu * lx / 2)
            }
        }
        val mainSpan = if (inp.twoWay) lx else inp.lx
        steps += "Mx = ${f2(mx)} kN·m/m" + (if (my > 0) ", My = ${f2(my)} kN·m/m" else "") + "; Vu = ${f2(vu)} kN/m; dx = ${f1(dx)} mm" + (if (my > 0) ", dy = ${f1(dy)} mm" else "")

        val astMin = (if (inp.fy >= 415) 0.0012 else 0.0015) * 1000 * t
        val astX = max(astFor(mx, dx, inp.fck, inp.fy), astMin)
        val astY = if (my > 0) max(astFor(my, dy, inp.fck, inp.fy), astMin) else astMin
        val aBar = PI * inp.barDia * inp.barDia / 4
        val aDist = PI * inp.distBarDia * inp.distBarDia / 4
        val sMaxMain = min(3 * dx, 300.0)
        val sMaxDist = min(5 * dx, 450.0)
        fun adopt(area: Double, need: Double, smax: Double) = (floor(min(1000 * area / need, smax) / 10.0) * 10.0).coerceAtLeast(75.0)
        val sx = adopt(aBar, astX, sMaxMain)
        val sy = if (inp.twoWay) adopt(aBar, astY, sMaxMain) else adopt(aDist, astY, sMaxDist)
        steps += "Ast,x = ${f1(astX)} mm²/m → φ${inp.barDia.toInt()} @ ${sx.toInt()} c/c; " +
            (if (inp.twoWay) "Ast,y = ${f1(astY)} mm²/m → φ${inp.barDia.toInt()} @ ${sy.toInt()} c/c"
            else "distribution ${f1(astY)} mm²/m → φ${inp.distBarDia.toInt()} @ ${sy.toInt()} c/c")
        checks += DesignCheck("Minimum steel", "${f1(min(astX, astY))} mm²/m", "≥ ${if (inp.fy >= 415) "0.12" else "0.15"} % bD = ${f1(astMin)} mm²/m", Status.PASS, IS456.ref("Cl. 26.5.2.1"))
        checks += DesignCheck("Main bar spacing", "${sx.toInt()} mm", "≤ min(3d, 300) = ${f1(sMaxMain)} mm", if (sx <= sMaxMain) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.3.3(b)(1)"))
        checks += DesignCheck(if (inp.twoWay) "Bar spacing (long span)" else "Distribution bar spacing", "${sy.toInt()} mm",
            if (inp.twoWay) "≤ min(3d, 300) = ${f1(sMaxMain)} mm" else "≤ min(5d, 450) = ${f1(sMaxDist)} mm",
            if (sy <= (if (inp.twoWay) sMaxMain else sMaxDist)) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.3.3(b)(2)"))
        checks += DesignCheck("Max bar diameter", "${inp.barDia.toInt()} mm", "≤ D/8 = ${f1(t / 8)} mm", if (inp.barDia <= t / 8) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.2.2"))

        // shear
        val astProvX = 1000 * aBar / sx
        val pt = 100 * astProvX / (1000 * dx)
        val tauV = vu * 1e3 / (1000 * dx)
        val tauC = kSlab(t) * IS456.tauC(pt, inp.fck)
        checks += DesignCheck("Shear", "τv = ${"%.3f".format(tauV)} MPa", "≤ k·τc = ${"%.3f".format(tauC)} MPa (no shear steel)",
            if (tauV <= tauC) Status.PASS else Status.FAIL, IS456.ref("Cl. 40.2.1.1, Table 19"))

        // deflection on the short span
        // Short span spans between the long edges: continuous over both → Continuous (26), otherwise simply supported (20, conservative for end spans).
        val support = when {
            inp.type == SlabType.CANTILEVER -> IS456.Support.CANTILEVER
            inp.type == SlabType.TWO_WAY_RESTRAINED && inp.edgeCase.discLong == 0 -> IS456.Support.CONTINUOUS
            else -> IS456.Support.SIMPLY_SUPPORTED
        }
        val basic = IS456.basicSpanDepth(support, mainSpan * 1000)
        val fs = 0.58 * inp.fy * astX / astProvX
        val kt = IS456.kt(pt, fs)
        val allowed = basic * kt
        val actual = mainSpan * 1000 / dx
        steps += "l/d allowed = ${f1(basic)} × kt ${"%.3f".format(kt)} = ${f2(allowed)}; actual ${f2(actual)}"
        checks += DesignCheck("Deflection (span/d)", f2(actual), "≤ ${f2(allowed)}", if (actual <= allowed) Status.PASS else Status.FAIL, IS456.ref("Cl. 23.2.1, 24.1"))
        checks += DesignCheck("Cover", "${f1(inp.clearCover)} mm", "≥ 20 mm (mild) / 30 mm (moderate; 25 mm allowed for φ ≤ 12)", Status.INFO, IS456.ref("Cl. 26.4.2, Table 16"))
        var top: SlabTopSteel? = null
        coeff26?.let { c ->
            val mxN = c[0] * wu * lx * lx; val myN = c[2] * wu * lx * lx
            val axN = if (mxN > 0) max(astFor(mxN, dx, inp.fck, inp.fy), astMin) else 0.0
            val ayN = if (myN > 0) max(astFor(myN, dy, inp.fck, inp.fy), astMin) else 0.0
            val sxN = if (axN > 0) adopt(aBar, axN, sMaxMain) else 0.0
            val syN = if (ayN > 0) adopt(aBar, ayN, sMaxMain) else 0.0
            if (mxN > 0) steps += "Support (long edges, short-span bars, top): Mx− = ${f2(mxN)} kN·m/m → Ast = ${f1(axN)} mm²/m → φ${inp.barDia.toInt()} @ ${sxN.toInt()} c/c"
            if (myN > 0) steps += "Support (short edges, long-span bars, top): My− = ${f2(myN)} kN·m/m → Ast = ${f1(ayN)} mm²/m → φ${inp.barDia.toInt()} @ ${syN.toInt()} c/c"
            if (mxN > 0 || myN > 0) steps += "Top bars over continuous edges: extend ≥ 0.3·lx = ${f2(0.3 * lx)} m into each panel (Annex D-1.4/D-1.5 practice, SP 34 Fig. 5.4); curtail per Cl. 26.2.3"
            val (full, half) = torsionCorners(inp.edgeCase)
            val tor = 0.75 * astX
            val sT = if (full + half > 0) adopt(aBar, tor, sMaxMain) else 0.0
            if (full > 0) steps += "Torsion steel at $full corner(s) with both edges discontinuous: 3/4 × Ast,x = ${f1(tor)} mm²/m in each of 4 layers (2 top, 2 bottom) over lx/5 = ${f2(lx / 5)} m each way → φ${inp.barDia.toInt()} @ ${sT.toInt()} c/c (Annex D-1.8)"
            if (half > 0) steps += "Torsion steel at $half corner(s) with one edge discontinuous: 3/8 × Ast,x = ${f1(tor / 2)} mm²/m per layer (Annex D-1.9)"
            if (full + half < 4) steps += "Corners with both edges continuous: no torsion steel (Annex D-1.10)"
            if (inp.edgeCase.discShort + inp.edgeCase.discLong > 0) steps += "Discontinuous edges: top steel ≥ 50 % of mid-span bottom steel over 0.1·l, edge strips minimum steel (Annex D-1.7, Cl. 26.5.2.1)"
            checks += DesignCheck("Torsion steel at corners", "$full full + $half half", if (full + half > 0) "0.75·Ast,x = ${f1(tor)} mm²/m per layer over lx/5" else "not required",
                Status.INFO, IS456.ref("Annex D-1.8 – D-1.10"))
            if (sxN > 0) checks += DesignCheck("Top bar spacing (long edges)", "${sxN.toInt()} mm", "≤ ${f1(sMaxMain)} mm", Status.PASS, IS456.ref("Cl. 26.3.3(b)(1)"))
            if (syN > 0) checks += DesignCheck("Top bar spacing (short edges)", "${syN.toInt()} mm", "≤ ${f1(sMaxMain)} mm", Status.PASS, IS456.ref("Cl. 26.3.3(b)(1)"))
            top = SlabTopSteel(mxN, myN, axN, ayN, sxN, syN, full, half, tor, sT, lx / 5)
        }
        return SlabResult(wu, mx, my, dx, dy, astX, astY, sx, sy, checks, steps, top)
    }

    /** Required number of bars of [dia] at [spacing] across a width (m). */
    fun barCount(widthM: Double, spacing: Double) = ceil(widthM * 1000 / spacing).toInt() + 1
}
