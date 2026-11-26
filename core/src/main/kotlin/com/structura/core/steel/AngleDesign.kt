package com.structura.core.steel

import com.structura.core.rcc.DesignCheck
import com.structura.core.rcc.Status
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Single-angle members to IS 800:2007, connected through one leg (the longer leg [b]):
 *  • tension — yielding Cl. 6.2, rupture Cl. 6.3.3 simplified Tdn = α·An·fu/γm1 (α = 0.6 for 1–2 bolts, 0.7 for 3,
 *    0.8 for ≥ 4 bolts or welded), block shear Cl. 6.4.1 for a single bolt line (pitch, end distance and gauge);
 *  • compression — Cl. 7.5.1.2 equivalent slenderness λe = √(k1 + k2·λvv² + k3·λφ²) with Table 12 constants,
 *    buckling curve c (Table 10);
 *  • section classification Table 2 (b/t ≤ 15.7ε, d/t ≤ 15.7ε, (b+d)/t ≤ 25ε) — slender angles are flagged.
 * Dimensions mm, forces kN; nominal sharp-corner properties.
 */
object AngleDesign {
    class Props(val area: Double, val ixx: Double, val iyy: Double, val ixy: Double, val imin: Double, val imax: Double, val rvv: Double, val cx: Double, val cy: Double)

    /** b = leg along x, d = leg along y, t (mm). Centroid from the heel; product of inertia from the two leg rectangles. */
    fun props(b: Double, d: Double, t: Double): Props {
        val a1 = b * t; val x1 = b / 2; val y1 = t / 2
        val a2 = (d - t) * t; val x2 = t / 2; val y2 = t + (d - t) / 2
        val a = a1 + a2
        val cx = (a1 * x1 + a2 * x2) / a; val cy = (a1 * y1 + a2 * y2) / a
        val ixx = b * t * t * t / 12 + a1 * (y1 - cy) * (y1 - cy) + t * (d - t) * (d - t) * (d - t) / 12 + a2 * (y2 - cy) * (y2 - cy)
        val iyy = t * b * b * b / 12 + a1 * (x1 - cx) * (x1 - cx) + (d - t) * t * t * t / 12 + a2 * (x2 - cx) * (x2 - cx)
        val ixy = a1 * (x1 - cx) * (y1 - cy) + a2 * (x2 - cx) * (y2 - cy)
        val c = (ixx + iyy) / 2; val rr = sqrt(((ixx - iyy) / 2) * ((ixx - iyy) / 2) + ixy * ixy)
        return Props(a, ixx, iyy, ixy, c - rr, c + rr, sqrt((c - rr) / a), cx, cy)
    }

    /** Table 12 constants (k1, k2, k3). */
    fun k(bolts: Int, fixed: Boolean): Triple<Double, Double, Double> = when {
        bolts >= 2 && fixed -> Triple(0.20, 0.35, 20.0)
        bolts >= 2 -> Triple(0.70, 0.60, 5.0)
        fixed -> Triple(0.75, 0.35, 20.0)
        else -> Triple(1.25, 0.50, 60.0)
    }

    /**
     * [tension] and [compression] are the design axial forces (kN, both ≥ 0); [length] centre-to-centre of end
     * connections (mm); [bolts] in the connected leg (0 = welded); [fixed] end gussets fixity per Table 12.
     */
    fun check(b: Double, d: Double, t: Double, g: SteelGrade, tension: Double, compression: Double, length: Double,
              bolts: Int = 2, holeDia: Double = 18.0, fixed: Boolean = true, name: String = "ISA ${b.toInt()}×${d.toInt()}×${t.toInt()}",
              pitch: Double = 2.5 * (holeDia - 2), endDist: Double = 1.7 * holeDia, gauge: Double = 0.55 * b,
              slenderLimit: Double = 180.0): SteelResult {
        val p = props(b, d, t)
        val eps = sqrt(250.0 / g.fy)
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        steps += String.format(java.util.Locale.US, "%s: A = %.0f mm², Ixx = %.3e, Iyy = %.3e, Ixy = %.3e mm⁴ → Ivv = %.3e mm⁴, rvv = %.2f mm",
            name, p.area, p.ixx, p.iyy, p.ixy, p.imin, p.rvv)
        // classification
        val slender = b / t > 15.7 * eps || d / t > 15.7 * eps || (b + d) / t > 25 * eps
        checks += DesignCheck("Angle classification", String.format(java.util.Locale.US, "b/t %.1f, d/t %.1f, (b+d)/t %.1f", b / t, d / t, (b + d) / t),
            String.format(java.util.Locale.US, "≤ 15.7ε = %.1f, ≤ 25ε = %.1f", 15.7 * eps, 25 * eps), if (slender) Status.FAIL else Status.PASS, "IS 800:2007 Table 2 (vi)–(vii)")
        var util = 0.0; var cap = 0.0
        if (tension > 0) {
            val tdg = p.area * g.fy / IS800.GM0 / 1e3
            val an = p.area - (if (bolts > 0) holeDia * t else 0.0)
            val alpha = when { bolts == 0 || bolts >= 4 -> 0.8; bolts == 3 -> 0.7; else -> 0.6 }
            val tdn = alpha * an * g.fu / IS800.GM1 / 1e3
            steps += String.format(java.util.Locale.US, "Tdg = A·fy/γm0 = %.1f kN (Cl. 6.2); An = %.0f mm², α = %.1f → Tdn = α·An·fu/γm1 = %.1f kN (Cl. 6.3.3)", tdg, an, alpha, tdn)
            var tdb = Double.MAX_VALUE
            if (bolts > 0) {
                val avg = (endDist + (bolts - 1) * pitch) * t
                val avn = avg - (bolts - 0.5) * holeDia * t
                val atg = (b - gauge) * t
                val atn = atg - 0.5 * holeDia * t
                val t1 = avg * g.fy / (sqrt(3.0) * IS800.GM0) + 0.9 * atn * g.fu / IS800.GM1
                val t2 = 0.9 * avn * g.fu / (sqrt(3.0) * IS800.GM1) + atg * g.fy / IS800.GM0
                tdb = min(t1, t2) / 1e3
                steps += String.format(java.util.Locale.US, "Block shear (Cl. 6.4.1): p = %.0f, e = %.0f, g = %.0f mm → Avg %.0f, Avn %.0f, Atg %.0f, Atn %.0f mm²; Tdb = min(%.1f, %.1f) = %.1f kN",
                    pitch, endDist, gauge, avg, avn, atg, atn, t1 / 1e3, t2 / 1e3, tdb)
                checks += DesignCheck("Block shear", String.format(java.util.Locale.US, "T = %.1f kN", tension), String.format(java.util.Locale.US, "≤ Tdb = %.1f kN", tdb),
                    if (tension <= tdb) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 6.4.1")
            }
            val td = minOf(tdg, tdn, tdb)
            checks += DesignCheck("Tension", String.format(java.util.Locale.US, "T = %.1f kN", tension), String.format(java.util.Locale.US, "≤ Td = %.1f kN", td),
                if (tension <= td) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 6.2, 6.3.3, 6.4")
            util = max(util, tension / td); cap = td
            if (length / min(p.rvv, sqrt(p.imax / p.area)) > 400) checks += DesignCheck("Slenderness (tension)", String.format(java.util.Locale.US, "L/rvv %.0f", length / p.rvv), "≤ 400", Status.FAIL, "IS 800:2007 Table 3")
        }
        if (compression > 0) {
            val e = 2.0e5
            val lvv = (length / p.rvv) / (eps * PI * sqrt(e / 250.0))
            val lphi = ((b + d) / (2 * t)) / (eps * PI * sqrt(e / 250.0))
            val (k1, k2, k3) = k(bolts, fixed)
            val le = sqrt(k1 + k2 * lvv * lvv + k3 * lphi * lphi)
            val ph = 0.5 * (1 + 0.49 * (le - 0.2) + le * le)
            val fcd = min((g.fy / IS800.GM0) / (ph + sqrt(ph * ph - le * le)), g.fy / IS800.GM0)
            val pd = p.area * fcd / 1e3
            steps += String.format(java.util.Locale.US, "λvv = %.3f, λφ = %.3f, k = (%.2f, %.2f, %.0f) (Table 12, %s, %s) → λe = %.3f (Cl. 7.5.1.2)",
                lvv, lphi, k1, k2, k3, if (bolts >= 2 || bolts == 0) "≥ 2 bolts / welded" else "1 bolt", if (fixed) "fixed" else "hinged", le)
            steps += String.format(java.util.Locale.US, "Curve c, α = 0.49: φ = %.3f, fcd = %.1f MPa → Pd = %.1f kN (Cl. 7.1.2.1)", ph, fcd, pd)
            checks += DesignCheck("Compression (single angle)", String.format(java.util.Locale.US, "P = %.1f kN", compression), String.format(java.util.Locale.US, "≤ Pd = %.1f kN", pd),
                if (compression <= pd) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 7.5.1.2")
            val klr = length / p.rvv
            checks += DesignCheck("Slenderness (compression)", String.format(java.util.Locale.US, "L/rvv %.0f", klr), "≤ ${slenderLimit.toInt()}${if (slenderLimit > 180) " (compression from wind reversal only)" else ""}",
                if (klr <= slenderLimit) Status.PASS else Status.FAIL, "IS 800:2007 Table 3")
            util = max(util, compression / pd); if (cap == 0.0 || compression / pd >= util - 1e-12) cap = pd
        }
        if (tension <= 0 && compression <= 0) steps += "No axial force"
        return SteelResult(cap, util, checks, steps)
    }

    /**
     * Two equal-leg angles back to back on both sides of a gusset [tg] (mm), connected leg [b], outstand [d]:
     * tension = 2 × single-angle yielding / rupture / block shear (Cl. 6.2–6.4); compression as a built-up member,
     * KL = 0.85·L (Cl. 7.5.2.1, ≥ 2 bolts), r about the in-plane axis (2·Ixx) and the out-of-plane axis
     * (2·[Iyy + A(tg/2 + cx)²]), curve c; tack bolts so that each angle's slenderness between tacks ≤ min(40, 0.6·KL/r)
     * (Cl. 7.8.1).
     */
    fun checkDouble(b: Double, d: Double, t: Double, tg: Double, g: SteelGrade, tension: Double, compression: Double, length: Double,
                    bolts: Int = 2, holeDia: Double = 18.0, name: String = "2-ISA ${b.toInt()}×${d.toInt()}×${t.toInt()}", slenderLimit: Double = 180.0): SteelResult {
        val single = check(b, d, t, g, tension / 2, 0.0, length, bolts, holeDia, true, name, slenderLimit = slenderLimit)
        val p = props(d, b, t)                       // x along the outstand, y along the connected leg (against the gusset)
        val a2 = 2 * p.area
        val iIn = 2 * p.ixx                          // in-plane buckling (deflection along the connected leg)
        val iOut = 2 * (p.iyy + p.area * (tg / 2 + p.cx) * (tg / 2 + p.cx))
        val rIn = sqrt(iIn / a2); val rOut = sqrt(iOut / a2); val r = min(rIn, rOut)
        val eps = sqrt(250.0 / g.fy)
        val checks = ArrayList<DesignCheck>(); val steps = ArrayList<String>()
        steps += String.format(java.util.Locale.US, "%s on a %.0f mm gusset: A = %.0f mm², r in-plane %.1f mm, r out-of-plane %.1f mm (single-angle rvv %.1f mm)",
            name, tg, a2, rIn, rOut, p.rvv)
        checks += single.checks.filter { it.item == "Angle classification" }
        var util = 0.0; var cap = 0.0
        if (tension > 0) {
            val td = 2 * single.capacity
            steps += String.format(java.util.Locale.US, "Tension: 2 × single-angle Td (yielding, rupture, block shear) = %.1f kN", td)
            checks += DesignCheck("Tension (2 angles)", String.format(java.util.Locale.US, "T = %.1f kN", tension), String.format(java.util.Locale.US, "≤ Td = %.1f kN", td),
                if (tension <= td) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 6.2, 6.3.3, 6.4.1")
            util = max(util, tension / td); cap = td
        }
        if (compression > 0) {
            val kl = 0.85 * length
            val lam = kl / r
            val lamN = lam / (PI * sqrt(2.0e5 / g.fy))
            val ph = 0.5 * (1 + 0.49 * (lamN - 0.2) + lamN * lamN)
            val fcd = min((g.fy / IS800.GM0) / (ph + sqrt(ph * ph - lamN * lamN)), g.fy / IS800.GM0)
            val pd = a2 * fcd / 1e3
            steps += String.format(java.util.Locale.US, "Compression: KL = 0.85L = %.0f mm, KL/r = %.1f, λ = %.3f, curve c → fcd = %.1f MPa, Pd = %.1f kN (Cl. 7.1.2, 7.5.2.1)", kl, lam, lamN, fcd, pd)
            checks += DesignCheck("Compression (2 angles)", String.format(java.util.Locale.US, "P = %.1f kN", compression), String.format(java.util.Locale.US, "≤ Pd = %.1f kN", pd),
                if (compression <= pd) Status.PASS else Status.FAIL, "IS 800:2007 Cl. 7.5.2")
            checks += DesignCheck("Slenderness", String.format(java.util.Locale.US, "KL/r = %.0f", lam), "≤ ${slenderLimit.toInt()}", if (lam <= slenderLimit) Status.PASS else Status.FAIL, "IS 800:2007 Table 3")
            val sTack = p.rvv * min(40.0, 0.6 * lam)
            val nTack = max(2, Math.ceil(length / sTack).toInt() - 1)
            steps += String.format(java.util.Locale.US, "Tack bolts with %.0f mm packing: spacing ≤ rvv·min(40, 0.6·KL/r) = %.0f mm → at least %d tacks (Cl. 7.8.1)", tg, sTack, nTack)
            checks += DesignCheck("Tack connectors", "≤ ${sTack.toInt()} mm c/c", "$nTack tacks, packing = gusset thickness", Status.INFO, "IS 800:2007 Cl. 7.8.1")
            if (compression / pd >= util) cap = pd
            util = max(util, compression / pd)
        } else if (tension > 0) {
            val sTack = 1000.0
            checks += DesignCheck("Tack connectors", "≤ ${sTack.toInt()} mm c/c", "tension member", Status.INFO, "IS 800:2007 Cl. 10.2.5")
        }
        return SteelResult(cap, util, checks, steps)
    }

    /** Angle from a FEM section of shape ANGLE (m → mm): (b, d, t). */
    fun dims(b: Double, h: Double, t: Double) = Triple(b * 1000, h * 1000, t * 1000)

    internal fun absMax(v: List<Double>) = v.maxOf { abs(it) }
}
