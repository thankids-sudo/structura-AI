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
)

data class SlabResult(
    val wu: Double,                    // factored load kN/m²
    val mx: Double, val my: Double,    // kN·m per m
    val dx: Double, val dy: Double,    // effective depths mm
    val astX: Double, val astY: Double,   // required mm²/m
    val spacingX: Double, val spacingY: Double,   // adopted mm c/c
    val checks: List<DesignCheck>,
    val steps: List<String>,
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
        val dy = dx - inp.barDia / 2 - (if (inp.type == SlabType.TWO_WAY_SS) inp.barDia / 2 else inp.distBarDia / 2)
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
        }
        val mainSpan = if (inp.type == SlabType.TWO_WAY_SS) lx else inp.lx
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
        val sy = if (inp.type == SlabType.TWO_WAY_SS) adopt(aBar, astY, sMaxMain) else adopt(aDist, astY, sMaxDist)
        steps += "Ast,x = ${f1(astX)} mm²/m → φ${inp.barDia.toInt()} @ ${sx.toInt()} c/c; " +
            (if (inp.type == SlabType.TWO_WAY_SS) "Ast,y = ${f1(astY)} mm²/m → φ${inp.barDia.toInt()} @ ${sy.toInt()} c/c"
            else "distribution ${f1(astY)} mm²/m → φ${inp.distBarDia.toInt()} @ ${sy.toInt()} c/c")
        checks += DesignCheck("Minimum steel", "${f1(min(astX, astY))} mm²/m", "≥ ${if (inp.fy >= 415) "0.12" else "0.15"} % bD = ${f1(astMin)} mm²/m", Status.PASS, IS456.ref("Cl. 26.5.2.1"))
        checks += DesignCheck("Main bar spacing", "${sx.toInt()} mm", "≤ min(3d, 300) = ${f1(sMaxMain)} mm", if (sx <= sMaxMain) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.3.3(b)(1)"))
        checks += DesignCheck(if (inp.type == SlabType.TWO_WAY_SS) "Bar spacing (long span)" else "Distribution bar spacing", "${sy.toInt()} mm",
            if (inp.type == SlabType.TWO_WAY_SS) "≤ min(3d, 300) = ${f1(sMaxMain)} mm" else "≤ min(5d, 450) = ${f1(sMaxDist)} mm",
            if (sy <= (if (inp.type == SlabType.TWO_WAY_SS) sMaxMain else sMaxDist)) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.3.3(b)(2)"))
        checks += DesignCheck("Max bar diameter", "${inp.barDia.toInt()} mm", "≤ D/8 = ${f1(t / 8)} mm", if (inp.barDia <= t / 8) Status.PASS else Status.FAIL, IS456.ref("Cl. 26.5.2.2"))

        // shear
        val astProvX = 1000 * aBar / sx
        val pt = 100 * astProvX / (1000 * dx)
        val tauV = vu * 1e3 / (1000 * dx)
        val tauC = kSlab(t) * IS456.tauC(pt, inp.fck)
        checks += DesignCheck("Shear", "τv = ${"%.3f".format(tauV)} MPa", "≤ k·τc = ${"%.3f".format(tauC)} MPa (no shear steel)",
            if (tauV <= tauC) Status.PASS else Status.FAIL, IS456.ref("Cl. 40.2.1.1, Table 19"))

        // deflection on the short span
        val support = if (inp.type == SlabType.CANTILEVER) IS456.Support.CANTILEVER else IS456.Support.SIMPLY_SUPPORTED
        val basic = IS456.basicSpanDepth(support, mainSpan * 1000)
        val fs = 0.58 * inp.fy * astX / astProvX
        val kt = IS456.kt(pt, fs)
        val allowed = basic * kt
        val actual = mainSpan * 1000 / dx
        steps += "l/d allowed = ${f1(basic)} × kt ${"%.3f".format(kt)} = ${f2(allowed)}; actual ${f2(actual)}"
        checks += DesignCheck("Deflection (span/d)", f2(actual), "≤ ${f2(allowed)}", if (actual <= allowed) Status.PASS else Status.FAIL, IS456.ref("Cl. 23.2.1, 24.1"))
        checks += DesignCheck("Cover", "${f1(inp.clearCover)} mm", "≥ 20 mm (mild) / 30 mm (moderate; 25 mm allowed for φ ≤ 12)", Status.INFO, IS456.ref("Cl. 26.4.2, Table 16"))
        return SlabResult(wu, mx, my, dx, dy, astX, astY, sx, sy, checks, steps)
    }

    /** Required number of bars of [dia] at [spacing] across a width (m). */
    fun barCount(widthM: Double, spacing: Double) = ceil(widthM * 1000 / spacing).toInt() + 1
}
