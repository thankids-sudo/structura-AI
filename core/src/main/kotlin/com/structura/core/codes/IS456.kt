package com.structura.core.codes

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * IS 456:2000 — Plain and Reinforced Concrete, Code of Practice.
 *
 * Every constant and table here carries its clause / table reference so a
 * design report can quote it. Units: N, mm, MPa (N/mm²) unless stated.
 */
object IS456 {

    const val CODE = "IS 456:2000"

    /** Modulus of elasticity of steel, Cl. 5.6.3. */
    const val ES = 200_000.0

    /** Ultimate compressive strain in concrete in flexure, Cl. 38.1(b). */
    const val ECU = 0.0035

    /** Concrete grades permitted for RCC (Table 2 / Table 5 — M20 minimum for "moderate" exposure). */
    val CONCRETE_GRADES = listOf(15, 20, 25, 30, 35, 40, 45, 50)

    /** Steel grades in common use (IS 432 mild steel, IS 1786 HYSD). */
    val STEEL_GRADES = listOf(250, 415, 500, 550)

    fun ref(clause: String) = "$CODE $clause"

    /**
     * Limiting depth of neutral axis xu,max / d — Cl. 38.1, Note below (f).
     * Code-tabulated values are returned for Fe 250/415/500; other grades use the
     * strain-compatibility expression the table is derived from.
     */
    fun xuMaxByD(fy: Double): Double = when (fy) {
        250.0 -> 0.53
        415.0 -> 0.48
        500.0 -> 0.46
        else -> ECU / (0.0055 + 0.87 * fy / ES)
    }

    // ---------------------------------------------------------------- Table 19
    /** pt rows of Table 19 (100·As / b·d). */
    private val T19_PT = doubleArrayOf(0.15, 0.25, 0.50, 0.75, 1.00, 1.25, 1.50, 1.75, 2.00, 2.25, 2.50, 2.75, 3.00)

    /** Table 19 columns: M15, M20, M25, M30, M35, M40-and-above. */
    private val T19_FCK = intArrayOf(15, 20, 25, 30, 35, 40)
    private val T19_TC = arrayOf(
        doubleArrayOf(0.28, 0.35, 0.46, 0.54, 0.60, 0.64, 0.68, 0.71, 0.71, 0.71, 0.71, 0.71, 0.71), // M15
        doubleArrayOf(0.28, 0.36, 0.48, 0.56, 0.62, 0.67, 0.72, 0.75, 0.79, 0.81, 0.82, 0.82, 0.82), // M20
        doubleArrayOf(0.29, 0.36, 0.49, 0.57, 0.64, 0.70, 0.74, 0.78, 0.82, 0.85, 0.88, 0.90, 0.92), // M25
        doubleArrayOf(0.29, 0.37, 0.50, 0.59, 0.66, 0.71, 0.76, 0.80, 0.84, 0.88, 0.91, 0.94, 0.96), // M30
        doubleArrayOf(0.29, 0.37, 0.50, 0.59, 0.67, 0.73, 0.78, 0.82, 0.86, 0.90, 0.93, 0.96, 0.99), // M35
        doubleArrayOf(0.30, 0.38, 0.51, 0.60, 0.68, 0.74, 0.79, 0.84, 0.88, 0.92, 0.95, 0.98, 1.01), // M40+
    )

    /**
     * Design shear strength of concrete τc (MPa) — Table 19, Cl. 40.2.1.
     * pt is linearly interpolated between rows (pt ≤ 0.15 and ≥ 3.00 are clamped, as in the table).
     * For an intermediate concrete grade the next LOWER tabulated column is used (conservative;
     * the code gives no rule for interpolating between grades).
     */
    fun tauC(pt: Double, fck: Double): Double {
        val col = T19_FCK.indexOfLast { it <= fck }.coerceAtLeast(0)
        return interpolate(T19_PT, T19_TC[col], pt)
    }

    /** Maximum shear stress τc,max (MPa) — Table 20, Cl. 40.2.3. Next lower grade used for intermediate fck. */
    fun tauCMax(fck: Double): Double = when {
        fck >= 40 -> 4.0
        fck >= 35 -> 3.7
        fck >= 30 -> 3.5
        fck >= 25 -> 3.1
        fck >= 20 -> 2.8
        else -> 2.5
    }

    // ------------------------------------------------- Steel stress-strain (Fig. 23)
    /**
     * Design stress in steel (MPa) at a given strain — Cl. 38.1(e) & Fig. 23.
     * Fe 250: bilinear, fyd = 0.87 fy.
     * HYSD (Fe 415 / 500 / 550): elastic up to 0.80 fyd, then the inelastic points of Fig. 23B
     * (as tabulated in SP 16, Table A), linearly interpolated.
     */
    fun steelStress(strain: Double, fy: Double): Double {
        val fyd = 0.87 * fy
        val e = kotlin.math.abs(strain)
        if (fy <= 250.0) return min(e * ES, fyd)

        // Fig. 23B: stress ratio f/fyd vs inelastic strain; total strain = f/Es + inelastic.
        val ratio = doubleArrayOf(0.80, 0.85, 0.90, 0.95, 0.975, 1.00)
        val inelastic = doubleArrayOf(0.0, 0.0001, 0.0003, 0.0007, 0.0010, 0.0020)
        val strains = DoubleArray(ratio.size) { ratio[it] * fyd / ES + inelastic[it] }
        val stresses = DoubleArray(ratio.size) { ratio[it] * fyd }

        return when {
            e <= strains[0] -> e * ES
            e >= strains.last() -> fyd
            else -> interpolate(strains, stresses, e)
        }
    }

    // ------------------------------------------------------- Deflection (Cl. 23.2.1)
    enum class Support(val label: String, val basicLbyD: Double) {
        CANTILEVER("Cantilever", 7.0),
        SIMPLY_SUPPORTED("Simply supported", 20.0),
        CONTINUOUS("Continuous", 26.0),
    }

    /** Basic span/effective-depth ratio with the > 10 m span reduction — Cl. 23.2.1(a), (b). */
    fun basicSpanDepth(support: Support, spanMm: Double): Double {
        val spanM = spanMm / 1000.0
        return if (support != Support.CANTILEVER && spanM > 10.0)
            support.basicLbyD * 10.0 / spanM else support.basicLbyD
    }

    /**
     * Modification factor for tension reinforcement kt — Cl. 23.2.1(c), Fig. 4.
     * Uses the widely used closed-form fit of Fig. 4 (accuracy ≈ ±3 % over the plotted range),
     * capped at 2.0 as on the figure. fs = 0.58 fy (Ast,req / Ast,prov).
     */
    fun kt(ptProvided: Double, fs: Double): Double {
        val p = max(ptProvided, 0.1)
        val v = 1.0 / (0.225 + 0.00322 * fs - 0.625 * log10(1.0 / p))
        return v.coerceIn(0.0, 2.0)
    }

    /** Modification factor for compression reinforcement kc — Cl. 23.2.1(d), Fig. 5 (closed-form fit, 1.0…1.5). */
    fun kc(pcProvided: Double): Double {
        if (pcProvided <= 0.0) return 1.0
        return (1.6 * pcProvided / (pcProvided + 0.275)).coerceIn(1.0, 1.5)
    }

    // ------------------------------------------------------------------- helpers
    internal fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        for (i in 1 until xs.size) {
            if (x <= xs[i]) {
                val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
                return ys[i - 1] + t * (ys[i] - ys[i - 1])
            }
        }
        return ys.last()
    }
}
